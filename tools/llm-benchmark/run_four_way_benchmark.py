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

"""Run comparable Raw, RAG, DB-direct, and MCP-replay trials."""

from __future__ import annotations

import time
from contextlib import ExitStack, closing
from pathlib import Path
from typing import Final, TextIO

import typer
from access_context import AccessContextTiming, AccessLabel, build_access_context
from benchmark_core import ContextPack, Document, SnapshotError, load_snapshot
from benchmark_metadata import (
    BenchmarkMetadata,
    create_benchmark_metadata,
    snapshot_fingerprint,
)
from gold_set import BenchmarkCase, GoldSetError, load_cases
from multi_schedule import MultiTrial, build_schedule
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
from pgvector_store import PgVectorStore, PgVectorStoreError
from pydantic import ValidationError
from retrieval_policy import plan_query
from rich.console import Console
from run_benchmark import (
    _SYSTEM_INSTRUCTIONS,
    BenchmarkRecord,
    _messages,
    _write_record,
)

_DEFAULT_SNAPSHOT = Path(".benchmark-data/notion-export")
_DEFAULT_GOLD_SET = Path("backend/docs/llm-search-benchmark-gold-set.md")
_DEFAULT_OUTPUT = Path(".benchmark-data/four-way-results.jsonl")
_DEFAULT_DATABASE_URL = "postgresql://knot_benchmark:knot_benchmark@localhost:55432/knot_benchmark"
_DEFAULT_CORPUS_KEY: Final[str] = "notion-export"
_DEFAULT_CHUNK_SIZE: Final[int] = 1200
_DEFAULT_CHUNK_OVERLAP: Final[int] = 180
_DEFAULT_CASES: Final[tuple[str, ...]] = (
    "G-001",
    "G-002",
    "G-003",
    "G-004",
    "G-005",
    "G-006",
    "G-009",
    "G-010",
    "G-011",
    "G-012",
)
_ALL_STRATEGIES: Final[tuple[AccessLabel, ...]] = ("raw", "rag", "db", "mcp-replay")


class RunMode(str):
    """CLI strategy selection values."""

    ALL = "all"
    RAW = "raw"
    RAG = "rag"
    DB = "db"
    MCP_REPLAY = "mcp-replay"


def main(
    snapshot_dir: Path = typer.Option(_DEFAULT_SNAPSHOT, help="Notion Markdown/CSV export directory."),
    gold_set: Path = typer.Option(_DEFAULT_GOLD_SET, help="Markdown gold-set path."),
    output: Path = typer.Option(_DEFAULT_OUTPUT, help="JSONL output path."),
    database_url: str = typer.Option(_DEFAULT_DATABASE_URL, envvar="PGVECTOR_DATABASE_URL"),
    corpus_key: str = typer.Option(_DEFAULT_CORPUS_KEY, help="Logical corpus identity in pgvector."),
    case: str | None = typer.Option(None, help="Comma-separated case IDs; defaults to ten single-turn cases."),
    strategy: str = typer.Option(RunMode.ALL, help="all, raw, rag, db, or mcp-replay."),
    repeats: int = typer.Option(10, min=1, help="Repeats per case."),
    top_k: int = typer.Option(3, min=1, help="Distinct source documents per retrieved context."),
    seed: int = typer.Option(20260901, help="Randomization seed."),
    warmup: int = typer.Option(1, min=0, help="Unrecorded chat warm-up calls."),
    run_id: str = typer.Option("", help="Stable identifier for this benchmark run."),
    phase: str = typer.Option("control", help="Comparison phase: control or live."),
    condition: str = typer.Option(
        "", help="Execution condition: cold or warm; defaults from --warmup."
    ),
    chunk_size: int = typer.Option(_DEFAULT_CHUNK_SIZE, min=100, help="Passage chunk size in characters."),
    chunk_overlap: int = typer.Option(_DEFAULT_CHUNK_OVERLAP, min=0, help="Passage overlap in characters."),
    max_generation_context_chars: int = typer.Option(120_000, min=1, help="Reject oversized model contexts."),
    retrieval_only: bool = typer.Option(False, help="Measure access latency without calling the chat model."),
    skip_index: bool = typer.Option(False, help="Reuse the existing persisted Qwen pgvector corpus."),
) -> None:
    """Run a fixed model comparison with balanced multi-strategy ordering."""
    console = Console()
    selected = _select_strategies(strategy)
    documents = load_snapshot(snapshot_dir)
    cases = _select_cases(load_cases(gold_set), case)
    schedule = build_schedule(tuple(item.case_id for item in cases), selected, repeats, seed)
    settings = _load_settings(console)
    metadata = create_benchmark_metadata(
        run_id=run_id,
        phase=phase,
        condition=condition or ("warm" if warmup > 0 else "cold"),
        snapshot_id=snapshot_fingerprint(documents),
        model=settings.model,
        prompt=_SYSTEM_INSTRUCTIONS,
        generation_options={
            "temperature": settings.temperature,
            "max_tokens": settings.max_tokens,
            "reasoning_effort": settings.reasoning_effort,
            "enable_thinking": settings.enable_thinking,
            "top_k": top_k,
            "chunk_size": chunk_size,
            "chunk_overlap": chunk_overlap,
            "max_generation_context_chars": max_generation_context_chars,
            "retrieval_only": retrieval_only,
            "seed": seed,
            "warmup": warmup,
            "corpus_key": corpus_key,
        },
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    with ExitStack() as stack:
        store = stack.enter_context(closing(PgVectorStore(database_url)))
        embedding_client = stack.enter_context(closing(NimEmbeddingClient(settings)))
        chat_client = None if retrieval_only else stack.enter_context(closing(NimClient(settings)))
        if not skip_index:
            index_corpus(store, embedding_client, chunk_documents(documents, chunk_size, chunk_overlap), corpus_key, console)
        else:
            console.print(f"using existing pgvector corpus: {store.count(corpus_key)} chunks")
        if chat_client is not None:
            _warmup(chat_client, warmup)
        with output.open("w", encoding="utf-8") as stream:
            case_by_id = {item.case_id: item for item in cases}
            for trial in schedule:
                for label in trial.order:
                    _run_observation(
                        stream,
                        chat_client,
                        embedding_client,
                        store,
                        trial,
                        case_by_id[trial.case_id],
                        documents,
                        corpus_key,
                        top_k,
                        label,
                        max_generation_context_chars,
                        retrieval_only,
                        metadata,
                    )
    console.print(f"[green]results written:[/green] {output} ({len(schedule)} pairs × {len(selected)} strategies)")


def _run_observation(
    stream: TextIO,
    chat_client: NimClient | None,
    embedding_client: NimEmbeddingClient,
    store: PgVectorStore,
    trial: MultiTrial,
    benchmark_case: BenchmarkCase,
    documents: tuple[Document, ...],
    corpus_key: str,
    top_k: int,
    label: AccessLabel,
    max_generation_context_chars: int,
    retrieval_only: bool,
    metadata: BenchmarkMetadata,
) -> None:
    history: list[ChatMessage] = []
    for turn_number, question in enumerate(benchmark_case.turns, start=1):
        started = time.perf_counter()
        context = ContextPack("", (), 0, 0)
        timing = AccessContextTiming(context, 0.0, 0.0)
        query_plan = plan_query(question, _previous_questions(history))
        try:
            if query_plan.should_clarify:
                answer = query_plan.clarification_text
                _write_record(
                    stream,
                    _record(
                        benchmark_case,
                        trial,
                        turn_number,
                        label,
                        question,
                        context,
                        (time.perf_counter() - started) * 1000,
                        timing,
                        answer,
                        None,
                        None,
                        None,
                        metadata,
                    ),
                )
                history.extend((ChatMessage(role="user", content=question), ChatMessage(role="assistant", content=answer)))
                continue
            timing = build_access_context(
                label,
                question,
                documents,
                store,
                embedding_client,
                corpus_key,
                top_k,
                query_plan=query_plan,
            )
            context = timing.context
            search_ms = (time.perf_counter() - started) * 1000
            if label == "rag" and context.retrieved_count == 0:
                answer = "현재 동기화된 팀 문서에서는 관련된 정보를 찾지 못했습니다. 최신 문서가 반영되지 않았다면 동기화 후 다시 검색해보세요."
                _write_record(
                    stream,
                    _record(
                        benchmark_case,
                        trial,
                        turn_number,
                        label,
                        question,
                        context,
                        search_ms,
                        timing,
                        answer,
                        None,
                        None,
                        None,
                        metadata,
                    ),
                )
                history.extend((ChatMessage(role="user", content=question), ChatMessage(role="assistant", content=answer)))
                continue
            if retrieval_only:
                _write_record(
                    stream,
                    _record(
                        benchmark_case,
                        trial,
                        turn_number,
                        label,
                        question,
                        context,
                        search_ms,
                        timing,
                        None,
                        None,
                        None,
                        None,
                        metadata,
                    ),
                )
                history.append(ChatMessage(role="user", content=question))
                continue
            if chat_client is None:
                raise NimTransportError("chat client is not configured")
            if len(context.text) > max_generation_context_chars:
                raise NimTransportError(
                    f"context exceeds generation limit: {len(context.text)} > {max_generation_context_chars} characters"
                )
            result = chat_client.generate(_messages(question, context, tuple(history)))
            _write_record(
                stream,
                _record(
                    benchmark_case,
                    trial,
                    turn_number,
                    label,
                    question,
                    context,
                    search_ms,
                    timing,
                    result.text,
                    result.ttft_ms,
                    result.total_ms,
                    None,
                    metadata,
                ),
            )
            history.extend((ChatMessage(role="user", content=question), ChatMessage(role="assistant", content=result.text)))
        except (NimRequestError, NimTransportError, PgVectorStoreError) as error:
            search_ms = (time.perf_counter() - started) * 1000
            _write_record(
                stream,
                _record(
                    benchmark_case,
                    trial,
                    turn_number,
                    label,
                    question,
                    context,
                    search_ms,
                    timing,
                    "",
                    None,
                    None,
                    str(error),
                    metadata,
                ),
            )


def _previous_questions(history: list[ChatMessage]) -> tuple[str, ...]:
    return tuple(message.content for message in history if message.role == "user")


def _record(
    benchmark_case: BenchmarkCase,
    trial: MultiTrial,
    turn_number: int,
    label: AccessLabel,
    question: str,
    context: ContextPack,
    search_ms: float,
    timing: AccessContextTiming,
    answer: str | None,
    model_ttft_ms: float | None,
    model_total_ms: float | None,
    error: str | None,
    metadata: BenchmarkMetadata,
) -> BenchmarkRecord:
    return BenchmarkRecord(
        benchmark_case.case_id,
        trial.repeat,
        turn_number,
        label,
        question,
        answer or "",
        context.source_paths,
        context.retrieved_count,
        context.tool_calls,
        len(context.text),
        search_ms,
        model_ttft_ms,
        model_total_ms,
        None if model_ttft_ms is None else search_ms + model_ttft_ms,
        None if model_total_ms is None else search_ms + model_total_ms,
        False,
        error,
        timing.embedding_ms,
        timing.database_ms,
        metadata=metadata,
    )


def _select_strategies(value: str) -> tuple[AccessLabel, ...]:
    if value == RunMode.ALL:
        return _ALL_STRATEGIES
    if value == "raw":
        return ("raw",)
    if value == "rag":
        return ("rag",)
    if value == "db":
        return ("db",)
    if value == "mcp-replay":
        return ("mcp-replay",)
    raise typer.BadParameter(f"unknown strategy: {value}")


def _select_cases(cases: tuple[BenchmarkCase, ...], selection: str | None) -> tuple[BenchmarkCase, ...]:
    if selection is None:
        wanted = (
            tuple(item.case_id for item in cases)
            if cases and all(item.case_id.startswith("W-") for item in cases)
            else _DEFAULT_CASES
        )
    else:
        wanted = tuple(item.strip() for item in selection.split(",") if item.strip())
    case_by_id = {item.case_id: item for item in cases}
    missing = tuple(case_id for case_id in wanted if case_id not in case_by_id)
    if missing:
        raise typer.BadParameter(f"unknown case ID(s): {', '.join(missing)}")
    return tuple(case_by_id[case_id] for case_id in wanted)


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


if __name__ == "__main__":
    try:
        typer.run(main)
    except (GoldSetError, SnapshotError, NimConfigurationError) as error:
        Console().print(f"[red]{error}[/red]")
        raise typer.Exit(code=2) from error
