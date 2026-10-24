#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14"
# dependencies = ["numpy", "pydantic", "typer"]
# ///

# ruff: noqa: B008  # Typer uses Option calls as the CLI declaration syntax.

"""Analyze multi-strategy access and end-to-end benchmark observations."""

from __future__ import annotations

import hashlib
import json
from dataclasses import replace
from pathlib import Path
from typing import Final, Literal, assert_never

import numpy as np
import typer
from access_report_render import render_report
from access_report_types import PairSummary, RunMetadataSummary, StrategySummary
from evaluate_rag_quality import EvaluationMetadata
from gold_set import BenchmarkCase, load_cases
from pydantic import BaseModel, ConfigDict, ValidationError

MetricName = Literal[
    "search_ms",
    "embedding_ms",
    "vector_db_ms",
    "ttft_ms",
    "total_ms",
    "model_ttft_ms",
    "model_total_ms",
]
StrategyName = Literal["raw", "rag", "db", "mcp-replay"]
_DEFAULT_BOOTSTRAP: Final[int] = 10_000
_DEFAULT_PERMUTATION: Final[int] = 10_000
_SEED: Final[int] = 20260901


class AccessReportError(Exception):
    """Raised when benchmark records cannot be analyzed."""

    __slots__ = ("reason",)

    reason: str

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason

    def __str__(self) -> str:
        return f"access benchmark error: {self.reason}"


class AccessRow(BaseModel):
    """Validated fields used by the multi-strategy analysis."""

    model_config = ConfigDict(extra="ignore", frozen=True)

    case_id: str
    repeat: int
    turn: int
    strategy: StrategyName
    source_paths: tuple[str, ...] = ()
    context_chars: int = 0
    search_ms: float
    embedding_ms: float = 0.0
    vector_db_ms: float = 0.0
    model_ttft_ms: float | None = None
    model_total_ms: float | None = None
    ttft_ms: float | None = None
    total_ms: float | None = None
    error: str | None = None
    metadata: EvaluationMetadata | None = None


def main(
    results: Path = typer.Option(..., help="JSONL output from run_four_way_benchmark.py."),
    e2e_results: Path | None = typer.Option(None, help="Optional JSONL with model-generation observations."),
    gold_set: Path = typer.Option(Path("backend/docs/llm-search-benchmark-gold-set.md"), help="Gold set used to resolve expected source IDs."),
    output: Path = typer.Option(Path("backend/docs/llm-search-ab-test-report.md"), help="Markdown report path."),
    chat_model: str = typer.Option("qwen/qwen3.6-27b"),
    embedding_model: str = typer.Option("text-embedding-qwen3-embedding-0.6b:2"),
    context_length: int = typer.Option(32768, min=1),
    max_tokens: int = typer.Option(4096, min=1),
    bootstrap_iterations: int = typer.Option(_DEFAULT_BOOTSTRAP, min=1),
    permutation_iterations: int = typer.Option(_DEFAULT_PERMUTATION, min=1),
) -> None:
    """Write a report without declaring a winner below the sample gate."""
    rows = _load_rows(results)
    generation_rows = rows if e2e_results is None else _load_rows(e2e_results)
    cases = load_cases(gold_set)
    expected = _expected_sources(cases)
    rng = np.random.default_rng(_SEED)
    summaries = tuple(
        _merge_summary(_summarize(rows, strategy, expected), _summarize(generation_rows, strategy, expected))
        for strategy in ("raw", "rag", "db", "mcp-replay")
    )
    retrieval_pairs = tuple(
        _pair_summary(rows, left, right, "search_ms", bootstrap_iterations, permutation_iterations, rng)
        for left, right in _comparisons()
    )
    e2e_pairs = tuple(
        _pair_summary(generation_rows, left, right, "total_ms", bootstrap_iterations, permutation_iterations, rng)
        for left, right in _comparisons()
    )
    e2e_ttft_pairs = tuple(
        _pair_summary(generation_rows, left, right, "ttft_ms", bootstrap_iterations, permutation_iterations, rng)
        for left, right in _comparisons()
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata_summaries = (
        *_metadata_summaries("검색 결과", rows),
        *_metadata_summaries("생성 결과", generation_rows),
    )
    output.write_text(
        render_report(
            len(rows),
            sum(row.error is None for row in rows),
            summaries,
            retrieval_pairs,
            e2e_ttft_pairs,
            e2e_pairs,
            chat_model,
            embedding_model,
            context_length,
            max_tokens,
            metadata_summaries,
        ),
        encoding="utf-8",
    )
    typer.echo(f"report written: {output}")


def _expected_sources(
    cases: tuple[BenchmarkCase, ...],
) -> dict[tuple[str, int], set[str]]:
    return {
        (case.case_id, turn): {
            source.replace("-", "") for source in case.sources_for_turn(turn)
        }
        for case in cases
        for turn in range(1, len(case.turns) + 1)
    }


def _metadata_summaries(
    input_label: str,
    rows: tuple[AccessRow, ...],
) -> tuple[RunMetadataSummary, ...]:
    groups: dict[tuple[str, str, str, str, str, str, str], int] = {}
    missing = 0
    for row in rows:
        metadata = row.metadata
        if metadata is None:
            missing += 1
            continue
        options = json.dumps(
            metadata.generation_options,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        options_sha256 = hashlib.sha256(options.encode("utf-8")).hexdigest()
        identity = (
            metadata.phase,
            metadata.condition,
            metadata.run_id,
            metadata.snapshot_id,
            metadata.model,
            metadata.prompt_sha256,
            options_sha256,
        )
        groups[identity] = groups.get(identity, 0) + 1
    summaries = [
        RunMetadataSummary(input_label, count, *identity)
        for identity, count in sorted(
            groups.items(), key=lambda item: (_phase_order(item[0][0]), item[0])
        )
    ]
    if missing:
        summaries.append(RunMetadataSummary(input_label, missing, "missing", "", "", "", "", "", ""))
    return tuple(summaries)


def _phase_order(phase: str) -> int:
    return {"control": 0, "live": 1, "missing": 2}.get(phase, 3)


def _load_rows(path: Path) -> tuple[AccessRow, ...]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (FileNotFoundError, OSError, UnicodeDecodeError) as error:
        raise AccessReportError(str(error)) from error
    rows: list[AccessRow] = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            rows.append(AccessRow.model_validate_json(line))
        except (ValidationError, json.JSONDecodeError) as error:
            raise AccessReportError(f"invalid record at line {line_number}") from error
    if not rows:
        raise AccessReportError("no records found")
    return tuple(rows)


def _summarize(
    rows: tuple[AccessRow, ...],
    strategy: StrategyName,
    expected: dict[tuple[str, int], set[str]],
) -> StrategySummary:
    group = tuple(row for row in rows if row.strategy == strategy)
    search = _positive_values(group, "search_ms")
    embedding = _positive_values(group, "embedding_ms")
    database = _positive_values(group, "vector_db_ms")
    model_success = tuple(row for row in group if row.error is None and _valid(row.total_ms))
    hits = sum(
        any(
            source_id in path
            for source_id in expected.get((row.case_id, row.turn), set())
            for path in row.source_paths
        )
        for row in group
        if expected.get((row.case_id, row.turn))
    )
    quality_count = sum(
        bool(expected.get((row.case_id, row.turn))) for row in group
    )
    return StrategySummary(
        strategy,
        len(group),
        len(model_success),
        len(group) - len(model_success),
        _percentile(search, 50),
        _percentile(search, 95),
        float(np.mean(search)),
        _percentile(embedding, 50),
        _percentile(database, 50),
        _optional_percentile(group, "model_ttft_ms", 50),
        _optional_percentile(group, "model_total_ms", 50),
        _optional_percentile(group, "ttft_ms", 50),
        _optional_percentile(group, "total_ms", 50),
        sum(1 for row in group if _valid(row.ttft_ms) and row.ttft_ms <= 5000),
        sum(_valid(row.ttft_ms) for row in group),
        hits,
        quality_count,
    )


def _merge_summary(access: StrategySummary, generation: StrategySummary) -> StrategySummary:
    return replace(
        access,
        successful_model_records=generation.successful_model_records,
        error_records=generation.error_records,
        model_ttft_p50=generation.model_ttft_p50,
        model_total_p50=generation.model_total_p50,
        e2e_ttft_p50=generation.e2e_ttft_p50,
        e2e_total_p50=generation.e2e_total_p50,
        e2e_ttft_under_5s=generation.e2e_ttft_under_5s,
        e2e_ttft_observations=generation.e2e_ttft_observations,
    )


def _positive_values(rows: tuple[AccessRow, ...], metric: MetricName) -> np.ndarray:
    values = [value for row in rows if (value := _metric_value(row, metric)) is not None and _valid(value)]
    return np.asarray(values, dtype=np.float64)


def _optional_percentile(rows: tuple[AccessRow, ...], metric: MetricName, percentile: float) -> float | None:
    values = _positive_values(rows, metric)
    return None if not len(values) else _percentile(values, percentile)


def _metric_value(row: AccessRow, metric: MetricName) -> float | None:
    match metric:
        case "search_ms":
            return row.search_ms
        case "embedding_ms":
            return row.embedding_ms
        case "vector_db_ms":
            return row.vector_db_ms
        case "ttft_ms":
            return row.ttft_ms
        case "total_ms":
            return row.total_ms
        case "model_ttft_ms":
            return row.model_ttft_ms
        case "model_total_ms":
            return row.model_total_ms
        case unreachable:
            assert_never(unreachable)


def _valid(value: float | None) -> bool:
    return value is not None and np.isfinite(value) and value > 0


def _percentile(values: np.ndarray, percentile: float) -> float:
    if not len(values):
        return 0.0
    return float(np.percentile(values, percentile))


def _comparisons() -> tuple[tuple[StrategyName, StrategyName], ...]:
    return (("rag", "db"), ("rag", "mcp-replay"), ("db", "mcp-replay"))


def _pair_summary(
    rows: tuple[AccessRow, ...],
    strategy_a: StrategyName,
    strategy_b: StrategyName,
    metric: MetricName,
    bootstrap_iterations: int,
    permutation_iterations: int,
    rng: np.random.Generator,
) -> PairSummary:
    left = {(row.case_id, row.repeat, row.turn): row for row in rows if row.strategy == strategy_a}
    right = {(row.case_id, row.repeat, row.turn): row for row in rows if row.strategy == strategy_b}
    deltas_list: list[float] = []
    for key in sorted(left.keys() & right.keys()):
        delta = _paired_delta(left[key], right[key], metric)
        if delta is not None:
            deltas_list.append(delta)
    deltas = np.asarray(deltas_list, dtype=np.float64)
    if not len(deltas):
        return PairSummary(metric, strategy_a, strategy_b, 0, 0.0, 0.0, 0.0, 0, 1.0)
    indices = rng.integers(0, len(deltas), size=(bootstrap_iterations, len(deltas)))
    samples = np.median(deltas[indices], axis=1)
    median_delta = float(np.median(deltas))
    null_signs = rng.choice(np.asarray((-1.0, 1.0), dtype=np.float64), size=(permutation_iterations, len(deltas)))
    null = np.abs(np.mean(null_signs * deltas, axis=1))
    p_value = float((np.count_nonzero(null >= abs(float(np.mean(deltas)))) + 1) / (permutation_iterations + 1))
    return PairSummary(
        metric,
        strategy_a,
        strategy_b,
        len(deltas),
        median_delta,
        float(np.percentile(samples, 2.5)),
        float(np.percentile(samples, 97.5)),
        int(np.count_nonzero(deltas < 0)),
        p_value,
    )


def _paired_delta(left: AccessRow, right: AccessRow, metric: MetricName) -> float | None:
    a = _metric_value(left, metric)
    b = _metric_value(right, metric)
    return None if not _valid(a) or not _valid(b) else b - a


if __name__ == "__main__":
    try:
        typer.run(main)
    except AccessReportError as error:
        typer.echo(str(error), err=True)
        raise typer.Exit(code=2) from error
