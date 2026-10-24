#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14"
# dependencies = [
#     "httpx2[http2,brotli,zstd]",
#     "pydantic",
#     "pydantic-settings",
#     "rich",
#     "typer",
# ]
# ///

# ruff: noqa: B008  # Typer uses Option calls as the CLI declaration syntax.

# ─── How to run ───
# 1. Install uv (if not installed):
#      curl -LsSf https://astral.sh/uv/install.sh | sh
# 2. Run directly (no venv, no pip install needed):
#      uv run tools/llm-benchmark/run_benchmark.py --help
# 3. Or make executable and run:
#      chmod +x tools/llm-benchmark/run_benchmark.py && ./tools/llm-benchmark/run_benchmark.py --help
# ──────────────────

from __future__ import annotations

import json
import time
from contextlib import closing
from dataclasses import asdict, dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Final, TextIO, assert_never

import typer
from benchmark_core import (
    ContextPack,
    Document,
    SnapshotError,
    Strategy,
    build_context,
    load_snapshot,
)
from benchmark_metadata import BenchmarkMetadata
from benchmark_result_identity import observation_fingerprint
from gold_set import BenchmarkCase, GoldSetError, load_cases
from nim_client import (
    ChatMessage,
    NimClient,
    NimConfigurationError,
    NimRequestError,
    NimSettings,
    NimTransportError,
)
from pydantic import ValidationError
from rich.console import Console

_DEFAULT_SNAPSHOT = Path(".benchmark-data/notion-export")
_DEFAULT_GOLD_SET = Path("backend/docs/llm-search-benchmark-gold-set.md")
_DEFAULT_OUTPUT = Path(".benchmark-data/results.jsonl")
_SYSTEM_INSTRUCTIONS: Final[str] = """당신은 Knot 팀 문서 검색 평가용 답변자입니다.
반드시 제공된 문서 컨텍스트만 근거로 답하세요.
근거가 없으면 찾지 못했다고 말하고, 문서가 충돌하면 각 주장을 나열한 뒤 최종 결정을 단정하지 마세요.
질문에 날짜, 위치, 요약, 결정 사실, 결정 근거처럼 여러 요구가 있으면 명시된 요소를 모두 답하세요.
날짜·문서 위치 질문은 날짜와 문서 제목/경로뿐 아니라 문서의 핵심 내용을 짧게 요약하세요.
결정 이유 질문은 문제·대안·결정·근거를 구분하고, 문서에 결정일·현재 상태가 있으면 함께 답하세요.
문서의 경로·기술 식별자는 번역하지 말고 원문 표기를 유지하세요.
답변을 한 줄로 끝내지 말고, 컨텍스트에서 확인되는 근거를 2~5개의 문장 또는 글머리표로 정리하세요.
날짜는 문서의 회의 날짜를 사용하고 최종 수정일을 추측하지 마세요. 문서가 여러 개면 각각의 사실을 구분해 적으세요.
답변 끝에는 사용한 source_path를 [source: 경로] 형식으로 표시하세요.
"""


class RunMode(StrEnum):
    """CLI strategy selection."""

    ALL = "all"
    RAW = "raw"
    RAG = "rag"
    MCP_REPLAY = "mcp-replay"


@dataclass(frozen=True, slots=True)
class BenchmarkRecord:
    """One JSONL-compatible observation from a benchmark turn."""

    case_id: str
    repeat: int
    turn: int
    strategy: str
    question: str
    answer: str
    source_paths: tuple[str, ...]
    retrieved_count: int
    tool_calls: int
    context_chars: int
    search_ms: float
    model_ttft_ms: float | None
    model_total_ms: float | None
    ttft_ms: float | None
    total_ms: float | None
    dry_run: bool
    error: str | None
    embedding_ms: float = 0.0
    vector_db_ms: float = 0.0
    mcp_http_requests: int = 0
    mcp_page_count: int = 0
    mcp_retry_count: int = 0
    mcp_rate_limit_count: int = 0
    mcp_elapsed_ms: float = 0.0
    mcp_operations: tuple[str, ...] = ()
    metadata: BenchmarkMetadata | None = None
    result_fingerprint: str = field(init=False)

    def __post_init__(self) -> None:
        """Expose the immutable identity used by the human review workflow."""
        object.__setattr__(
            self,
            "result_fingerprint",
            observation_fingerprint(
                case_id=self.case_id,
                repeat=self.repeat,
                turn=self.turn,
                strategy=self.strategy,
                question=self.question,
                answer=self.answer,
                source_paths=self.source_paths,
                retrieved_count=self.retrieved_count,
                error=self.error,
            ),
        )


def main(
    snapshot_dir: Path = typer.Option(_DEFAULT_SNAPSHOT, help="Notion Markdown/CSV export directory."),
    gold_set: Path = typer.Option(_DEFAULT_GOLD_SET, help="Benchmark gold-set Markdown file."),
    output: Path = typer.Option(_DEFAULT_OUTPUT, help="JSONL result output path."),
    strategy: RunMode = typer.Option(RunMode.ALL, help="Strategy to run."),
    case: str | None = typer.Option(None, help="Comma-separated case IDs, for example G-001,G-007."),
    repeats: int = typer.Option(1, min=1, help="Number of independent runs per case and strategy."),
    top_k: int = typer.Option(5, min=1, help="Number of lexical chunks for RAG and MCP replay."),
    dry_run: bool = typer.Option(False, help="Load and plan requests without calling NIM."),
    list_cases: bool = typer.Option(False, help="Print parsed case IDs and exit."),
) -> None:
    """Run comparable Raw, RAG, and MCP replay NIM benchmark requests."""
    console = Console()
    cases = load_cases(gold_set)
    if list_cases:
        _print_cases(console, cases)
        return
    documents = load_snapshot(snapshot_dir)
    selected_cases = _select_cases(cases, case)
    selected_strategies = _strategies(strategy)
    output.parent.mkdir(parents=True, exist_ok=True)
    settings = None if dry_run else _load_settings(console)
    with output.open("w", encoding="utf-8") as stream:
        if settings is None:
            _write_dry_run(console, stream, selected_cases, documents, selected_strategies, top_k)
            return
        with closing(NimClient(settings)) as client:
            for repeat_number in range(1, repeats + 1):
                for selected_strategy in selected_strategies:
                    for benchmark_case in selected_cases:
                        _run_case(
                            stream,
                            client,
                            repeat_number,
                            benchmark_case,
                            documents,
                            selected_strategy,
                            top_k,
                        )
    console.print(f"[green]results written:[/green] {output}")


def _run_case(
    stream: TextIO,
    client: NimClient,
    repeat_number: int,
    benchmark_case: BenchmarkCase,
    documents: tuple[Document, ...],
    strategy: Strategy,
    top_k: int,
) -> None:
    history: list[ChatMessage] = []
    for turn_number, question in enumerate(benchmark_case.turns, start=1):
        search_started = time.perf_counter()
        context = build_context(strategy, documents, question, top_k)
        search_ms = (time.perf_counter() - search_started) * 1000
        messages = _messages(question, context, tuple(history))
        try:
            result = client.generate(messages)
            record = BenchmarkRecord(
                benchmark_case.case_id,
                repeat_number,
                turn_number,
                strategy.value,
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
            )
            history.extend(
                (
                    ChatMessage(role="user", content=question),
                    ChatMessage(role="assistant", content=result.text),
                )
            )
        except (NimRequestError, NimTransportError) as error:
            record = BenchmarkRecord(
                benchmark_case.case_id,
                repeat_number,
                turn_number,
                strategy.value,
                question,
                "",
                context.source_paths,
                context.retrieved_count,
                context.tool_calls,
                len(context.text),
                search_ms,
                None,
                None,
                None,
                None,
                False,
                str(error),
            )
        _write_record(stream, record)


def _messages(
    question: str,
    context: ContextPack,
    history: tuple[ChatMessage, ...],
) -> tuple[ChatMessage, ...]:
    prompt = f"""{_answer_requirements(question, history)}
<documents>
{context.text or '(검색된 문서 없음)'}
</documents>

질문: {question}"""
    return (ChatMessage(role="system", content=_SYSTEM_INSTRUCTIONS), *history, ChatMessage(role="user", content=prompt))


def _answer_requirements(question: str, history: tuple[ChatMessage, ...] = ()) -> str:
    """Give the generator a small checklist for the supported question shapes."""
    previous = " ".join(message.content for message in history if message.role == "user")
    normalized = f"{previous} {question}".casefold()
    requirements: list[str] = [
        "[답변 체크리스트] 문서 컨텍스트에 있는 사실만 사용하고, 아래 질문의 명시된 요구 요소를 빠짐없이 답하세요.",
    ]
    if any(marker in normalized for marker in ("왜", "이유", "선택한")):
        requirements.append("- 결정 이유: 문제, 대안, 결정, 근거를 구분하고 결정일·현재 상태가 있으면 함께 적으세요.")
    if "언제" in normalized or "회의 날짜" in normalized:
        requirements.append("- 날짜/문서 위치 질문: 회의 날짜, 문서 제목·경로, 핵심 논의 요약을 함께 적으세요.")
    requirements.append("- 컨텍스트에 없는 체크리스트 항목은 없다고 명시하고 추측하지 마세요.")
    return "\n".join(requirements)


def _write_dry_run(
    console: Console,
    stream: TextIO,
    cases: tuple[BenchmarkCase, ...],
    documents: tuple[Document, ...],
    strategies: tuple[Strategy, ...],
    top_k: int,
) -> None:
    for selected_strategy in strategies:
        for benchmark_case in cases:
            for turn_number, question in enumerate(benchmark_case.turns, start=1):
                search_started = time.perf_counter()
                context = build_context(selected_strategy, documents, question, top_k)
                search_ms = (time.perf_counter() - search_started) * 1000
                _write_record(
                    stream,
                    BenchmarkRecord(
                        benchmark_case.case_id,
                        0,
                        turn_number,
                        selected_strategy.value,
                        question,
                        "",
                        context.source_paths,
                        context.retrieved_count,
                        context.tool_calls,
                        len(context.text),
                        search_ms,
                        None,
                        None,
                        None,
                        None,
                        True,
                        None,
                    ),
                )
    console.print(f"[yellow]dry-run complete:[/yellow] {len(cases)} cases, {len(strategies)} strategies")


def _write_record(stream: TextIO, record: BenchmarkRecord) -> None:
    stream.write(json.dumps(asdict(record), ensure_ascii=False, sort_keys=True) + "\n")
    stream.flush()


def _load_settings(console: Console) -> NimSettings:
    try:
        return NimSettings()
    except ValidationError as error:
        console.print("[red]invalid NIM environment:[/red]", error)
        raise typer.Exit(code=2) from error


def _select_cases(cases: tuple[BenchmarkCase, ...], selection: str | None) -> tuple[BenchmarkCase, ...]:
    if selection is None:
        return cases
    wanted = frozenset(item.strip() for item in selection.split(",") if item.strip())
    selected = tuple(benchmark_case for benchmark_case in cases if benchmark_case.case_id in wanted)
    if len(selected) != len(wanted):
        missing = ", ".join(sorted(wanted - {item.case_id for item in selected}))
        raise typer.BadParameter(f"unknown case ID(s): {missing}")
    return selected


def _strategies(mode: RunMode) -> tuple[Strategy, ...]:
    match mode:
        case RunMode.ALL:
            return (Strategy.RAW, Strategy.RAG, Strategy.MCP_REPLAY)
        case RunMode.RAW:
            return (Strategy.RAW,)
        case RunMode.RAG:
            return (Strategy.RAG,)
        case RunMode.MCP_REPLAY:
            return (Strategy.MCP_REPLAY,)
        case unreachable:
            assert_never(unreachable)


def _print_cases(console: Console, cases: tuple[BenchmarkCase, ...]) -> None:
    for benchmark_case in cases:
        console.print(f"{benchmark_case.case_id}\t{benchmark_case.category}\t{len(benchmark_case.turns)} turn(s)")


if __name__ == "__main__":
    try:
        typer.run(main)
    except (GoldSetError, SnapshotError, NimConfigurationError) as error:
        Console().print(f"[red]{error}[/red]")
        raise typer.Exit(code=2) from error
