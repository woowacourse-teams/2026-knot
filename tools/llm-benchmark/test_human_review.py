#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14"
# dependencies = ["pydantic", "pytest"]
# ///

# ─── How to run ───
#      uv run --with pydantic --with pytest pytest tools/llm-benchmark/test_human_review.py
# ──────────────────

from __future__ import annotations

from pathlib import Path

import pytest
from human_review import (
    HumanReviewDecision,
    HumanReviewRow,
    HumanReviewStatus,
    evaluate_human_review,
    load_human_review,
)
from pydantic import ValidationError


def _row(
    *,
    case_id: str = "W-001",
    repeat: int = 1,
    turn: int = 1,
    strategy: str = "rag",
    decision: str = "pending",
    answer_correct: bool | None = None,
    sources_relevant: bool | None = None,
    policy_compliant: bool | None = None,
    reviewer: str = "",
    result_fingerprint: str = "a" * 64,
) -> HumanReviewRow:
    return HumanReviewRow(
        case_id=case_id,
        repeat=repeat,
        turn=turn,
        strategy=strategy,
        decision=decision,
        answer_correct=answer_correct,
        sources_relevant=sources_relevant,
        policy_compliant=policy_compliant,
        reviewer=reviewer,
        notes="",
        result_fingerprint=result_fingerprint,
    )


def test_human_review_requires_complete_pass_labels() -> None:
    # Given: a generated answer with all three human quality dimensions confirmed
    expected = frozenset({("W-001", 1, 1, "rag")})
    rows = (
        _row(
            decision="pass",
            answer_correct=True,
            sources_relevant=True,
            policy_compliant=True,
            reviewer="reviewer-a",
        ),
    )

    # When: the human gate evaluates the label set
    summary = evaluate_human_review(rows, expected)

    # Then: the fully reviewed answer is the only passing state
    assert summary.status is HumanReviewStatus.PASS
    assert summary.gate_passed
    assert summary.passed == 1


def test_human_review_stays_pending_until_a_reviewer_finishes() -> None:
    # Given: a template row that has not received a human decision
    expected = frozenset({("W-001", 1, 1, "rag")})

    # When: the pending row is evaluated
    summary = evaluate_human_review((_row(),), expected)

    # Then: automatic checks cannot promote it to a pass
    assert summary.status is HumanReviewStatus.PENDING
    assert not summary.gate_passed
    assert summary.pending == 1


def test_human_review_reports_missing_and_duplicate_observations() -> None:
    # Given: one duplicated row and one expected row that is absent
    expected = frozenset(
        {
            ("W-001", 1, 1, "rag"),
            ("W-002", 1, 1, "rag"),
        }
    )
    duplicate = _row(
        decision="fail",
        answer_correct=False,
        sources_relevant=True,
        policy_compliant=False,
        reviewer="reviewer-a",
    )

    # When: the label set is evaluated
    summary = evaluate_human_review((duplicate, duplicate), expected)

    # Then: coverage problems remain visible and the gate fails
    assert summary.status is HumanReviewStatus.FAIL
    assert not summary.gate_passed
    assert summary.missing == (("W-002", 1, 1, "rag"),)
    assert summary.duplicates == (("W-001", 1, 1, "rag"),)


def test_human_review_without_rows_is_not_evaluated() -> None:
    # Given: an expected workload without any submitted human labels
    summary = evaluate_human_review((), frozenset({("W-001", 1, 1, "rag")}))

    # Then: absence is distinct from a completed failure review
    assert summary.status is HumanReviewStatus.NOT_EVALUATED
    assert not summary.gate_passed


def test_pass_or_fail_labels_require_reviewer_and_all_quality_dimensions() -> None:
    # Given: an incomplete terminal label
    # When & then: terminal decisions cannot omit evidence dimensions
    with pytest.raises(ValidationError):
        _row(
            decision="pass",
            answer_correct=True,
            sources_relevant=True,
            policy_compliant=None,
        )
    with pytest.raises(ValidationError):
        _row(
            decision="fail",
            answer_correct=False,
            sources_relevant=True,
            policy_compliant=False,
        )
    with pytest.raises(ValidationError):
        _row(
            decision="not_evaluated",
            answer_correct=True,
            sources_relevant=True,
            policy_compliant=True,
            reviewer="reviewer-a",
        )


def test_human_review_template_covers_every_independent_workload_turn() -> None:
    # Given: the checked-in pending template for the independent workload
    rows = load_human_review(
        Path("backend/docs/llm-search-benchmark-human-review-template.jsonl")
    )

    # Then: every workload turn has one pending row ready for a reviewer
    assert len(rows) == 33
    assert all(row.decision is HumanReviewDecision.PENDING for row in rows)
    assert len({row.key for row in rows}) == len(rows)
