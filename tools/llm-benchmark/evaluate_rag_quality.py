#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14"
# dependencies = ["pydantic", "typer"]
# ///

"""Evaluate RAG retrieval policy and answer-shape gates against the gold set."""

# ruff: noqa: B008  # Typer uses Option calls as the CLI declaration syntax.

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import typer
from answer_quality_policy import answer_shape_passes
from benchmark_result_identity import observation_fingerprint
from gold_set import BenchmarkCase, load_cases
from human_review import (
    HumanReviewDecision,
    HumanReviewError,
    HumanReviewRow,
    HumanReviewSummary,
    evaluate_human_review,
    expected_review_keys,
    load_human_review,
)
from pydantic import BaseModel, ConfigDict, Field, ValidationError

_DEFAULT_RESULTS = Path(".benchmark-data/rag-quality-retrieval-final-10x.jsonl")
_DEFAULT_GOLD_SET = Path("backend/docs/llm-search-benchmark-gold-set.md")
_NO_ANSWER_CASES = frozenset({"G-011", "G-012"})
_NO_ANSWER_CATEGORIES = frozenset({"no_answer", "broad", "clarification"})


class EvaluationError(Exception):
    """Raised when a result file cannot be evaluated."""

    __slots__ = ("reason",)

    reason: str

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason

    def __str__(self) -> str:
        return f"RAG evaluation error: {self.reason}"


class EvaluationMetadata(BaseModel):
    """Run identity required when an external result is used for a final gate."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    run_id: str = Field(min_length=1)
    phase: Literal["control", "live"]
    condition: Literal["cold", "warm"]
    snapshot_id: str = Field(min_length=1)
    model: str = Field(min_length=1)
    prompt_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    generation_options: dict[str, str | int | float | bool | None]
    observed_at: str = Field(min_length=1)


class EvaluationRow(BaseModel):
    """Fields required from a benchmark JSONL observation."""

    model_config = ConfigDict(extra="ignore", frozen=True)

    case_id: str = Field(min_length=1)
    repeat: int = Field(ge=1)
    turn: int = Field(ge=1)
    strategy: str = Field(min_length=1)
    question: str = Field(min_length=1)
    answer: str
    source_paths: tuple[str, ...]
    retrieved_count: int = Field(ge=0)
    error: str | None = None
    result_fingerprint: str | None = None
    metadata: EvaluationMetadata | None = None


@dataclass(frozen=True, slots=True)
class TurnEvaluation:
    """One result row's retrieval and optional answer checks."""

    key: tuple[str, int, int]
    retrieval_passed: bool
    answer_passed: bool | None
    reasons: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class EvaluationSummary:
    """Aggregate gate result for one result file."""

    rows: int
    expected_rows: int
    retrieval_passed: int
    answer_evaluated: int
    answer_passed: int
    retrieval_failures: tuple[str, ...]
    answer_failures: tuple[str, ...]
    metadata_failures: tuple[str, ...] = ()

    @property
    def retrieval_gate_passed(self) -> bool:
        return (
            self.rows == self.expected_rows
            and not self.retrieval_failures
            and not self.metadata_failures
        )

    @property
    def answer_gate_passed(self) -> bool | None:
        if self.answer_evaluated != self.expected_rows:
            return None
        return self.answer_passed == self.expected_rows and not self.answer_failures


def main(
    results: list[Path] = typer.Option(
        [], "--results", help="One or more JSONL outputs from the RAG benchmark."
    ),
    gold_set: Path = typer.Option(_DEFAULT_GOLD_SET, help="Markdown gold-set path."),
    strategy: str = typer.Option("rag", help="Expected strategy label."),
    repeats: int = typer.Option(10, min=1, help="Expected repeats per case."),
    require_answer: bool = typer.Option(
        False, help="Fail when an answer is missing or fails the answer-shape gate."
    ),
    human_labels: Path | None = typer.Option(
        None, "--human-labels", help="Optional JSONL human-review labels."
    ),
    human_repeat: int = typer.Option(
        1, min=1, help="Result repeat represented by the human-review labels."
    ),
    require_human_review: bool = typer.Option(
        False, help="Fail unless every expected answer has a terminal human label."
    ),
    require_run_metadata: bool = typer.Option(
        False, help="Fail unless all rows share one complete run identity."
    ),
) -> None:
    """Check every expected case/turn/repeat and print a CI-friendly gate summary."""
    if human_repeat > repeats:
        raise typer.BadParameter("human_repeat must not exceed repeats")
    cases = load_cases(gold_set)
    result_paths = tuple(results) if results else (_DEFAULT_RESULTS,)
    rows = tuple(row for path in result_paths for row in _load_rows(path))
    summary = evaluate(
        rows, cases, strategy, repeats, require_metadata=require_run_metadata
    )
    human_summary = evaluate_human_labels(
        () if human_labels is None else load_human_review(human_labels),
        cases,
        strategy,
        repeat=human_repeat,
        result_rows=rows,
    )
    typer.echo(f"results={summary.rows} expected={summary.expected_rows}")
    typer.echo(
        "retrieval_gate="
        f"{'PASS' if summary.retrieval_gate_passed else 'FAIL'} "
        f"({summary.retrieval_passed}/{summary.expected_rows})"
    )
    if summary.answer_gate_passed is None:
        status = "NOT_EVALUATED" if summary.answer_evaluated == 0 else "PARTIAL"
        typer.echo(
            f"answer_gate={status} ({summary.answer_passed}/{summary.answer_evaluated})"
        )
    else:
        typer.echo(
            "answer_gate="
            f"{'PASS' if summary.answer_gate_passed else 'FAIL'} "
            f"({summary.answer_passed}/{summary.answer_evaluated})"
        )
    for failure in summary.retrieval_failures:
        typer.echo(f"FAIL: {failure}")
    for failure in summary.answer_failures:
        typer.echo(f"ANSWER FAIL: {failure}")
    for failure in summary.metadata_failures:
        typer.echo(f"RUN METADATA FAIL: {failure}")
    if summary.answer_evaluated < summary.expected_rows:
        typer.echo(
            f"answer coverage={summary.answer_evaluated}/{summary.expected_rows} "
            "(retrieval-only output is intentionally partial)"
        )
    typer.echo(
        f"human_gate={human_summary.status.name} "
        f"({human_summary.passed}/{human_summary.expected})"
    )
    for issue in (
        *human_summary.missing,
        *human_summary.duplicates,
        *human_summary.unexpected,
        *human_summary.invalid,
    ):
        typer.echo(f"HUMAN REVIEW COVERAGE: {issue}")
    if not summary.retrieval_gate_passed or (
        require_answer and summary.answer_gate_passed is not True
    ):
        raise typer.Exit(code=1)
    if require_human_review and not human_summary.gate_passed:
        raise typer.Exit(code=1)


def evaluate(
    rows: tuple[EvaluationRow, ...],
    cases: tuple[BenchmarkCase, ...],
    strategy: str,
    repeats: int = 10,
    *,
    require_metadata: bool = False,
) -> EvaluationSummary:
    """Evaluate structural, source, abstention, and optional answer-shape gates."""
    if repeats < 1:
        raise ValueError("repeats must be positive")
    case_by_id = {case.case_id: case for case in cases}
    expected_keys = {
        (case.case_id, repeat, turn)
        for case in cases
        for repeat in range(1, repeats + 1)
        for turn in range(1, len(case.turns) + 1)
    }
    by_key: dict[tuple[str, int, int], EvaluationRow] = {}
    retrieval_failures: list[str] = []
    for row in rows:
        key = (row.case_id, row.repeat, row.turn)
        if row.strategy != strategy:
            retrieval_failures.append(
                f"{key}: strategy={row.strategy!r}, expected={strategy!r}"
            )
        if key in by_key:
            retrieval_failures.append(f"{key}: duplicate result row")
        by_key[key] = row
    missing = sorted(expected_keys - by_key.keys())
    retrieval_failures.extend(f"{key}: missing result row" for key in missing)
    unexpected = sorted(by_key.keys() - expected_keys)
    retrieval_failures.extend(f"{key}: unexpected result row" for key in unexpected)

    evaluations: list[TurnEvaluation] = []
    answer_failures: list[str] = []
    for key, row in sorted(by_key.items()):
        case = case_by_id.get(row.case_id)
        if case is None or key not in expected_keys:
            continue
        reasons = list(_structural_failures(row))
        expected_question = case.turns[row.turn - 1]
        if row.question != expected_question:
            reasons.append("question does not match the workload turn")
        expected_sources = _expected_sources(case, row.turn)
        if not _source_match(
            row.source_paths, expected_sources, case.case_id, row.turn
        ):
            reasons.append(
                f"expected sources={expected_sources!r}, actual={row.source_paths!r}"
            )
        if row.case_id == "G-011" and row.source_paths:
            reasons.append("no-answer case returned a source")
        if row.case_id == "G-012" and row.source_paths:
            reasons.append("clarification case returned a source")
        answer_passed = (
            None
            if not row.answer.strip()
            else answer_shape_passes(row.answer, case.case_id, row.turn)
        )
        if answer_passed is False:
            answer_failures.append(
                f"{key}: answer does not satisfy the gold-set policy shape"
            )
        evaluations.append(
            TurnEvaluation(key, not reasons, answer_passed, tuple(reasons))
        )
        retrieval_failures.extend(f"{key}: {reason}" for reason in reasons)

    answer_evaluated = sum(item.answer_passed is not None for item in evaluations)
    answer_passed = sum(item.answer_passed is True for item in evaluations)
    retrieval_passed = sum(item.retrieval_passed for item in evaluations)
    metadata_failures = (
        _metadata_failures(tuple(row for row in rows if row.strategy == strategy))
        if require_metadata
        else ()
    )
    return EvaluationSummary(
        len(rows),
        len(expected_keys),
        retrieval_passed,
        answer_evaluated,
        answer_passed,
        tuple(dict.fromkeys(retrieval_failures)),
        tuple(dict.fromkeys(answer_failures)),
        metadata_failures,
    )


def _metadata_failures(rows: tuple[EvaluationRow, ...]) -> tuple[str, ...]:
    if not rows or any(row.metadata is None for row in rows):
        return ("run metadata is missing",)
    metadata = tuple(row.metadata for row in rows)
    assert all(item is not None for item in metadata)
    run_ids = {item.run_id for item in metadata}
    phases = {item.phase for item in metadata}
    conditions = {item.condition for item in metadata}
    snapshots = {item.snapshot_id for item in metadata}
    models = {item.model for item in metadata}
    prompts = {item.prompt_sha256 for item in metadata}
    options = {json.dumps(item.generation_options, sort_keys=True) for item in metadata}
    failures: list[str] = []
    if len(run_ids) != 1:
        failures.append("result rows contain multiple run IDs")
    if len(phases) != 1:
        failures.append("result rows contain multiple benchmark phases")
    if len(conditions) != 1:
        failures.append("result rows contain multiple cold/warm conditions")
    if len(snapshots) != 1:
        failures.append("result rows contain multiple snapshots")
    if len(models) != 1:
        failures.append("result rows contain multiple models")
    if len(prompts) != 1:
        failures.append("result rows contain multiple system prompts")
    if len(options) != 1:
        failures.append("result rows contain multiple generation option sets")
    return tuple(failures)


def evaluate_human_labels(
    rows: tuple[HumanReviewRow, ...],
    cases: tuple[BenchmarkCase, ...],
    strategy: str,
    result_rows: tuple[EvaluationRow, ...],
    repeat: int = 1,
) -> HumanReviewSummary:
    """Evaluate one human label for every turn in one selected result repeat."""
    case_turns = tuple(
        (case.case_id, turn) for case in cases for turn in range(1, len(case.turns) + 1)
    )
    result_by_key = {
        (row.case_id, row.repeat, row.turn): row
        for row in result_rows
        if row.strategy == strategy
    }
    invalid: set[tuple[str, int, int, str]] = set()
    for row in rows:
        if not row.result_fingerprint:
            continue
        result = result_by_key.get((row.case_id, row.repeat, row.turn))
        if result is None or row.result_fingerprint != observation_fingerprint_for_row(
            result
        ):
            invalid.add(row.key)
            continue
        if row.decision is HumanReviewDecision.PASS and (
            not result.answer.strip() or result.error is not None
        ):
            invalid.add(row.key)
    return evaluate_human_review(
        rows,
        expected_review_keys(case_turns, strategy, repeat),
        invalid_keys=invalid,
    )


def _load_rows(path: Path) -> tuple[EvaluationRow, ...]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (FileNotFoundError, OSError, UnicodeDecodeError) as error:
        raise EvaluationError(str(error)) from error
    rows: list[EvaluationRow] = []
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            rows.append(EvaluationRow.model_validate_json(line))
        except (ValidationError, json.JSONDecodeError) as error:
            raise EvaluationError(f"invalid record at line {line_number}") from error
    if not rows:
        raise EvaluationError("no records found")
    return tuple(rows)


def _structural_failures(row: EvaluationRow) -> tuple[str, ...]:
    failures: list[str] = []
    if row.error:
        failures.append(f"benchmark error={row.error}")
    if row.retrieved_count != len(row.source_paths):
        failures.append("retrieved_count does not equal source_paths length")
    if row.retrieved_count > 3:
        failures.append("more than three source documents returned")
    if len(set(row.source_paths)) != len(row.source_paths):
        failures.append("duplicate source document returned")
    if any(not path.strip() for path in row.source_paths):
        failures.append("blank source document returned")
    if (
        row.result_fingerprint is not None
        and row.result_fingerprint != observation_fingerprint_for_row(row)
    ):
        failures.append("result_fingerprint does not match observation")
    return tuple(failures)


def observation_fingerprint_for_row(row: EvaluationRow) -> str:
    """Calculate the canonical identity used to bind human labels to a row."""
    return observation_fingerprint(
        case_id=row.case_id,
        repeat=row.repeat,
        turn=row.turn,
        strategy=row.strategy,
        question=row.question,
        answer=row.answer,
        source_paths=row.source_paths,
        retrieved_count=row.retrieved_count,
        error=row.error,
    )


def _expected_sources(case: BenchmarkCase, turn: int) -> tuple[str, ...]:
    if case.case_id in _NO_ANSWER_CASES or case.category in _NO_ANSWER_CATEGORIES:
        return ()
    if case.source_ids_by_turn is not None:
        return case.sources_for_turn(turn)
    if case.case_id == "G-013" and turn == 1:
        return case.source_ids[:1]
    return case.sources_for_turn(turn)


_SOURCE_TOKEN_PATTERN = re.compile(r"[0-9A-Za-z]+(?:-[0-9A-Za-z]+)*")
_SOURCE_SUFFIX_PATTERN = re.compile(r"\.(?:csv|md|markdown|txt)$", re.IGNORECASE)


def _normalize_source_id(value: str) -> str:
    without_suffix = _SOURCE_SUFFIX_PATTERN.sub("", value.strip().casefold())
    return re.sub(r"[^0-9a-z]", "", without_suffix)


def _source_tokens(path: str) -> frozenset[str]:
    filename = re.split(r"[\\/]", path)[-1]
    return frozenset(
        normalized
        for token in _SOURCE_TOKEN_PATTERN.findall(filename)
        if (normalized := _normalize_source_id(token))
    )


def _source_match(
    actual_paths: tuple[str, ...],
    expected_ids: tuple[str, ...],
    case_id: str,
    turn: int,
) -> bool:
    if not expected_ids:
        return not actual_paths
    normalized_expected = frozenset(
        _normalize_source_id(value) for value in expected_ids
    )
    if len(normalized_expected) != len(expected_ids):
        return False
    if len(actual_paths) != len(normalized_expected):
        return False
    matched: list[str] = []
    for path in actual_paths:
        candidates = normalized_expected & _source_tokens(path)
        if len(candidates) != 1:
            return False
        matched.append(next(iter(candidates)))
    return frozenset(matched) == normalized_expected


if __name__ == "__main__":
    try:
        typer.run(main)
    except (EvaluationError, HumanReviewError) as error:
        typer.echo(str(error), err=True)
        raise typer.Exit(code=2) from error
