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
#      uv run tools/llm-benchmark/run_ab_benchmark.py --help
# 3. Or make executable and run:
#      chmod +x tools/llm-benchmark/run_ab_benchmark.py && ./tools/llm-benchmark/run_ab_benchmark.py --help
# ──────────────────

from __future__ import annotations

from contextlib import closing
from pathlib import Path
from typing import Final, TextIO, assert_never

import typer
from ab_schedule import StrategyLabel, Trial, build_schedule
from benchmark_core import Document, SnapshotError, Strategy, load_snapshot
from gold_set import BenchmarkCase, GoldSetError, load_cases
from nim_client import ChatMessage, NimClient, NimConfigurationError, NimSettings
from pydantic import ValidationError
from rich.console import Console
from run_benchmark import _run_case

_DEFAULT_SNAPSHOT = Path(".benchmark-data/notion-export")
_DEFAULT_GOLD_SET = Path("backend/docs/llm-search-benchmark-gold-set.md")
_DEFAULT_OUTPUT = Path(".benchmark-data/ab-results.jsonl")
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


def main(
    snapshot_dir: Path = typer.Option(_DEFAULT_SNAPSHOT, help="Notion Markdown/CSV export directory."),
    gold_set: Path = typer.Option(_DEFAULT_GOLD_SET, help="Benchmark gold-set Markdown file."),
    output: Path = typer.Option(_DEFAULT_OUTPUT, help="Paired A/B JSONL result output path."),
    case: str | None = typer.Option(None, help="Comma-separated case IDs; defaults to 10 single-turn cases."),
    repeats: int = typer.Option(10, min=1, help="Paired repeats per selected case."),
    top_k: int = typer.Option(5, min=1, help="Number of lexical chunks for B/RAG."),
    seed: int = typer.Option(20260901, help="Seed for balanced A/B order randomization."),
    warmup: int = typer.Option(2, min=0, help="Unrecorded warm-up calls before the paired trials."),
    plan_only: bool = typer.Option(False, help="Print the balanced schedule without calling NIM."),
) -> None:
    """Run paired Raw-versus-RAG trials with balanced randomized order."""
    console = Console()
    cases = load_cases(gold_set)
    selected_cases = _select_cases(cases, case)
    schedule = build_schedule(tuple(item.case_id for item in selected_cases), repeats, seed)
    if plan_only:
        _print_schedule(console, schedule)
        return
    documents = load_snapshot(snapshot_dir)
    settings = _load_settings(console)
    output.parent.mkdir(parents=True, exist_ok=True)
    case_by_id = {item.case_id: item for item in selected_cases}
    with output.open("w", encoding="utf-8") as stream, closing(NimClient(settings)) as client:
        _warmup(client, warmup)
        for trial in schedule:
            _run_strategy_pair(stream, client, trial, case_by_id[trial.case_id], documents, top_k)
    console.print(f"[green]results written:[/green] {output} ({len(schedule)} paired trials)")


def _run_strategy_pair(
    stream: TextIO,
    client: NimClient,
    trial: Trial,
    benchmark_case: BenchmarkCase,
    documents: tuple[Document, ...],
    top_k: int,
) -> None:
    for label in (trial.first_strategy, trial.second_strategy):
        _run_case(stream, client, trial.repeat, benchmark_case, documents, _strategy_for(label), top_k)


def _strategy_for(label: StrategyLabel) -> Strategy:
    match label:
        case "raw":
            return Strategy.RAW
        case "rag":
            return Strategy.RAG
        case unreachable:
            assert_never(unreachable)


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
    wanted = _DEFAULT_CASES if selection is None else tuple(item.strip() for item in selection.split(",") if item.strip())
    case_by_id = {item.case_id: item for item in cases}
    missing = tuple(case_id for case_id in wanted if case_id not in case_by_id)
    if missing:
        raise typer.BadParameter(f"unknown case ID(s): {', '.join(missing)}")
    return tuple(case_by_id[case_id] for case_id in wanted)


def _print_schedule(console: Console, schedule: tuple[Trial, ...]) -> None:
    raw_first = sum(trial.first_strategy == "raw" for trial in schedule)
    console.print(f"planned paired trials: {len(schedule)}; raw-first={raw_first}; rag-first={len(schedule) - raw_first}")


if __name__ == "__main__":
    try:
        typer.run(main)
    except (GoldSetError, SnapshotError, NimConfigurationError) as error:
        Console().print(f"[red]{error}[/red]")
        raise typer.Exit(code=2) from error
