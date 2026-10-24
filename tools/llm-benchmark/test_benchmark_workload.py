#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.14"
# dependencies = ["pydantic", "pytest"]
# ///

# ─── How to run ───
#      uv run --with pydantic --with pytest pytest tools/llm-benchmark/test_benchmark_workload.py
# ──────────────────

from __future__ import annotations

import json
from pathlib import Path

import pytest
from benchmark_workload import WorkloadError, WorkloadManifest, load_workload
from gold_set import load_cases

_MANIFEST = Path("backend/docs/llm-search-benchmark-independent-30.json")


def test_independent_workload_has_thirty_cases_and_all_required_question_shapes() -> (
    None
):
    # Given: the checked-in independent question manifest
    manifest = load_workload(_MANIFEST)

    # Then: it is large and diverse enough for the semantic review gate
    assert len(manifest.cases) >= 30
    assert len({case.case_id for case in manifest.cases}) == len(manifest.cases)
    assert {case.category.value for case in manifest.cases} >= {
        "fact",
        "decision_reason",
        "document_location",
        "follow_up",
        "conflict",
        "no_answer",
        "broad",
    }
    assert all(0 < len(case.expected_source_ids) <= 3 for case in manifest.cases)
    assert all(case.expected_facts for case in manifest.cases)


def test_workload_loader_rejects_duplicate_case_ids(tmp_path: Path) -> None:
    # Given: a malformed manifest with two observations sharing one identity
    path = tmp_path / "duplicate.json"
    path.write_text(
        json.dumps(
            {
                "version": "1",
                "cases": [
                    {
                        "case_id": "W-001",
                        "category": "fact",
                        "turns": ["질문"],
                        "expected_source_ids": ["page-1"],
                        "expected_facts": ["사실"],
                    },
                    {
                        "case_id": "W-001",
                        "category": "fact",
                        "turns": ["다른 질문"],
                        "expected_source_ids": ["page-2"],
                        "expected_facts": ["다른 사실"],
                    },
                ],
            }
        ),
        encoding="utf-8",
    )

    # When & then: duplicate identities cannot enter a benchmark
    with pytest.raises(WorkloadError, match="duplicate"):
        load_workload(path, minimum_cases=1)


def test_workload_manifest_rejects_more_than_three_sources() -> None:
    # Given: a typed case with too many related documents
    # When & then: the source contract remains bounded to the product maximum
    with pytest.raises(ValueError):
        WorkloadManifest.model_validate(
            {
                "version": "1",
                "cases": [
                    {
                        "case_id": "W-001",
                        "category": "fact",
                        "turns": ["질문"],
                        "expected_source_ids": ["1", "2", "3", "4"],
                        "expected_facts": ["사실"],
                    }
                ],
            }
        )


def test_workload_manifest_supports_sources_per_conversational_turn() -> None:
    # Given: a follow-up workload whose answer evidence changes with the subject
    manifest = WorkloadManifest.model_validate(
        {
            "version": "1",
            "cases": [
                {
                    "case_id": "W-001",
                    "category": "follow_up",
                    "turns": ["첫 질문", "후속 질문"],
                    "expected_source_ids": ["page-1", "page-2"],
                    "expected_source_ids_by_turn": [["page-1"], ["page-2"]],
                    "expected_facts": ["첫 사실", "후속 사실"],
                }
            ],
        }
    )

    # Then: each turn keeps its own expected source set
    assert manifest.cases[0].expected_source_ids_by_turn == (
        ("page-1",),
        ("page-2",),
    )


def test_workload_manifest_rejects_source_sets_with_wrong_turn_count() -> None:
    # Given: source evidence that cannot be aligned to every conversational turn
    with pytest.raises(ValueError, match="turn"):
        WorkloadManifest.model_validate(
            {
                "version": "1",
                "cases": [
                    {
                        "case_id": "W-001",
                        "category": "follow_up",
                        "turns": ["첫 질문", "후속 질문"],
                        "expected_source_ids": ["page-1"],
                        "expected_source_ids_by_turn": [["page-1"]],
                        "expected_facts": ["사실"],
                    }
                ],
            }
        )


def test_workload_manifest_accepts_clarification_category() -> None:
    # Given: a broad question whose expected behavior is clarification
    manifest = WorkloadManifest.model_validate(
        {
            "version": "1",
            "cases": [
                {
                    "case_id": "W-001",
                    "category": "clarification",
                    "turns": ["범위가 넓은 질문"],
                    "expected_source_ids": ["evidence-page"],
                    "expected_facts": ["범위를 좁혀달라고 요청"],
                }
            ],
        }
    )

    # Then: the manifest category agrees with the evaluator policy vocabulary
    assert manifest.cases[0].category.value == "clarification"


def test_gold_set_loader_accepts_the_independent_json_workload() -> None:
    # Given: the same independent workload used by the benchmark runners
    cases = load_cases(_MANIFEST)

    # Then: the existing runner contract exposes its turns and source IDs
    assert len(cases) == 31
    assert cases[0].case_id == "W-001"
    assert cases[0].turns == ("백엔드 데이터베이스로 무엇을 사용해?",)
    assert cases[0].source_ids == ("fffde1156a83837097bc818fab8a1fa4",)
