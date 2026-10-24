#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14"
# dependencies = [
#     "httpx2[http2,brotli,zstd]",
#     "pgvector",
#     "psycopg[binary]",
#     "pydantic",
#     "pydantic-settings",
#     "rich",
#     "typer",
# ]
# ///

# ruff: noqa: B008  # Typer uses Option calls as the CLI declaration syntax.

"""Run Raw versus PostgreSQL/pgvector RAG with NVIDIA NIM."""

from __future__ import annotations

import time
from contextlib import closing
from dataclasses import dataclass
from pathlib import Path
from typing import Final, TextIO, assert_never

import typer
from ab_schedule import StrategyLabel, Trial, build_schedule
from benchmark_core import (
    ContextPack,
    Document,
    SnapshotError,
    load_snapshot,
)
from gold_set import BenchmarkCase, GoldSetError, load_cases
from nim_client import (
    ChatMessage,
    NimClient,
    NimConfigurationError,
    NimRequestError,
    NimSettings,
    NimTransportError,
)
from nim_embedding_client import NimEmbeddingClient
from pgvector_index import chunk_documents, index_corpus
from pgvector_rag import retrieve_pgvector_context
from pgvector_store import PgVectorStore
from pydantic import ValidationError
from rich.console import Console
from run_benchmark import BenchmarkRecord, _messages, _write_record

_DEFAULT_SNAPSHOT = Path(".benchmark-data/notion-export")
_DEFAULT_GOLD_SET = Path("backend/docs/llm-search-benchmark-gold-set.md")
_DEFAULT_OUTPUT = Path(".benchmark-data/pgvector-ab-results.jsonl")
_DEFAULT_DATABASE_URL = "postgresql://knot_benchmark:knot_benchmark@localhost:55432/knot_benchmark"
_DEFAULT_CORPUS_KEY: Final[str] = "notion-export"
_DEFAULT_CHUNK_SIZE: Final[int] = 1200
_DEFAULT_CHUNK_OVERLAP: Final[int] = 180


@dataclass(frozen=True, slots=True)
class ContextTiming:
    """Context and retrieval timings for one strategy."""

    context: ContextPack
    embedding_ms: float
    vector_db_ms: float


def main(
    snapshot_dir: Path = typer.Option(_DEFAULT_SNAPSHOT, help="Notion Markdown/CSV export directory."),
    gold_set: Path = typer.Option(_DEFAULT_GOLD_SET, help="Benchmark gold-set Markdown file."),
    output: Path = typer.Option(_DEFAULT_OUTPUT, help="Paired A/B JSONL result output path."),
    database_url: str = typer.Option(_DEFAULT_DATABASE_URL, envvar="PGVECTOR_DATABASE_URL"),
    corpus_key: str = typer.Option(_DEFAULT_CORPUS_KEY, help="Logical corpus identity in pgvector."),
    case: str | None = typer.Option(None, help="Comma-separated case IDs; defaults to 10 single-turn cases."),
    repeats: int = typer.Option(1, min=1, help="Paired repeats per selected case."),
    top_k: int = typer.Option(5, min=1, help="Number of distinct source documents for RAG."),
    seed: int = typer.Option(20260901, help="Seed for balanced A/B order randomization."),
    warmup: int = typer.Option(0, min=0, help="Unrecorded chat warm-up calls before paired trials."),
    chunk_size: int = typer.Option(_DEFAULT_CHUNK_SIZE, min=100, help="Passage chunk size in characters."),
    chunk_overlap: int = typer.Option(_DEFAULT_CHUNK_OVERLAP, min=0, help="Overlapping passage characters."),
    index_only: bool = typer.Option(False, help="Index the corpus and exit before chat trials."),
    skip_index: bool = typer.Option(False, help="Reuse an existing persisted pgvector corpus."),
) -> None:
    """Index the snapshot in pgvector, then run paired Raw versus pgvector RAG."""
    console = Console()
    documents = load_snapshot(snapshot_dir)
    cases = _select_cases(load_cases(gold_set), case)
    schedule = build_schedule(tuple(item.case_id for item in cases), repeats, seed)
    settings = _load_settings(console)
    output.parent.mkdir(parents=True, exist_ok=True)
    with (
        closing(PgVectorStore(database_url)) as store,
        closing(NimEmbeddingClient(settings)) as embedding_client,
        closing(NimClient(settings)) as chat_client,
    ):
        if not skip_index:
            chunks = chunk_documents(documents, chunk_size, chunk_overlap)
            index_corpus(store, embedding_client, chunks, corpus_key, console)
        else:
            console.print(f"using existing pgvector corpus: {store.count(corpus_key)} chunks")
        if index_only:
            return
        with output.open("w", encoding="utf-8") as stream:
            _warmup(chat_client, warmup)
            case_by_id = {item.case_id: item for item in cases}
            for trial in schedule:
                _run_trial(
                    stream,
                    chat_client,
                    embedding_client,
                    store,
                    trial,
                    case_by_id[trial.case_id],
                    documents,
                    corpus_key,
                    top_k,
                )
    console.print(f"[green]results written:[/green] {output} ({len(schedule)} paired trials)")


def _run_trial(
    stream: TextIO,
    chat_client: NimClient,
    embedding_client: NimEmbeddingClient,
    store: PgVectorStore,
    trial: Trial,
    benchmark_case: BenchmarkCase,
    documents: tuple[Document, ...],
    corpus_key: str,
    top_k: int,
) -> None:
    for label in (trial.first_strategy, trial.second_strategy):
        _run_strategy(stream, chat_client, embedding_client, store, trial, benchmark_case, documents, corpus_key, top_k, label)


def _run_strategy(
    stream: TextIO,
    chat_client: NimClient,
    embedding_client: NimEmbeddingClient,
    store: PgVectorStore,
    trial: Trial,
    benchmark_case: BenchmarkCase,
    documents: tuple[Document, ...],
    corpus_key: str,
    top_k: int,
    label: StrategyLabel,
) -> None:
    history: list[ChatMessage] = []
    for turn_number, question in enumerate(benchmark_case.turns, start=1):
        started = time.perf_counter()
        context = ContextPack("", (), 0, 0)
        embedding_ms = 0.0
        vector_db_ms = 0.0
        try:
            timing = _build_context(label, question, documents, store, embedding_client, corpus_key, top_k)
            context = timing.context
            embedding_ms = timing.embedding_ms
            vector_db_ms = timing.vector_db_ms
            search_ms = (time.perf_counter() - started) * 1000
            result = chat_client.generate(_messages(question, context, tuple(history)))
            record = BenchmarkRecord(
                benchmark_case.case_id,
                trial.repeat,
                turn_number,
                label,
                question,
                result.text,
                context.source_paths,
                context.retrieved_count,
                context.tool_calls,
                len(context.text),
                search_ms,
                result.ttft_ms,
                result.total_ms,
                search_ms + result.ttft_ms,
                search_ms + result.total_ms,
                False,
                None,
                embedding_ms,
                vector_db_ms,
            )
            history.extend((ChatMessage(role="user", content=question), ChatMessage(role="assistant", content=result.text)))
        except (NimRequestError, NimTransportError) as error:
            record = BenchmarkRecord(
                benchmark_case.case_id,
                trial.repeat,
                turn_number,
                label,
                question,
                "",
                context.source_paths,
                context.retrieved_count,
                context.tool_calls,
                len(context.text),
                (time.perf_counter() - started) * 1000,
                None,
                None,
                None,
                None,
                False,
                str(error),
                embedding_ms,
                vector_db_ms,
            )
        _write_record(stream, record)


def _build_context(
    label: StrategyLabel,
    question: str,
    documents: tuple[Document, ...],
    store: PgVectorStore,
    embedding_client: NimEmbeddingClient,
    corpus_key: str,
    top_k: int,
) -> ContextTiming:
    match label:
        case "raw":
            started = time.perf_counter()
            context = _raw_context(documents)
            return ContextTiming(context, 0.0, (time.perf_counter() - started) * 1000)
        case "rag":
            timing = retrieve_pgvector_context(question, store, embedding_client, corpus_key, top_k)
            return ContextTiming(timing.context, timing.embedding_ms, timing.vector_db_ms)
        case unreachable:
            assert_never(unreachable)


def _raw_context(documents: tuple[Document, ...]) -> ContextPack:
    return ContextPack(
        "\n\n".join(f"## {document.title}\nsource_path: {document.path}\n\n{document.content}" for document in documents),
        tuple(str(document.path) for document in documents),
        len(documents),
        0,
    )


def _warmup(client: NimClient, count: int) -> None:
    messages = (ChatMessage(role="user", content="warm-up request; answer with OK"),)
    for _ in range(count):
        client.generate(messages)


def _load_settings(console: Console) -> NimSettings:
    try:
        return NimSettings()
    except ValidationError as error:
        console.print("[red]invalid NIM environment:[/red]", error)
        raise typer.Exit(code=2) from error


def _select_cases(cases: tuple[BenchmarkCase, ...], selection: str | None) -> tuple[BenchmarkCase, ...]:
    defaults = ("G-001", "G-002", "G-003", "G-004", "G-005", "G-006", "G-009", "G-010", "G-011", "G-012")
    wanted = defaults if selection is None else tuple(item.strip() for item in selection.split(",") if item.strip())
    case_by_id = {item.case_id: item for item in cases}
    missing = tuple(case_id for case_id in wanted if case_id not in case_by_id)
    if missing:
        raise typer.BadParameter(f"unknown case ID(s): {', '.join(missing)}")
    return tuple(case_by_id[case_id] for case_id in wanted)


if __name__ == "__main__":
    try:
        typer.run(main)
    except (GoldSetError, SnapshotError, NimConfigurationError) as error:
        Console().print(f"[red]{error}[/red]")
        raise typer.Exit(code=2) from error
