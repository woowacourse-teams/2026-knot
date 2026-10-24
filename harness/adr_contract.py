from __future__ import annotations

import re
from pathlib import Path
from typing import Any


ADR_PATH_PATTERN = re.compile(
    r"^backend/docs/adr/(?P<issue>[1-9]\d*)-(?P<slug>[a-z0-9]+(?:-[a-z0-9]+)*)\.md$"
)
ADR_SLUG_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


def validate_issue_number(issue_number: Any) -> list[str]:
    if (
        isinstance(issue_number, bool)
        or not isinstance(issue_number, int)
        or issue_number < 1
    ):
        return ["issue_number must be a positive integer"]
    return []


def validate_slug(slug: Any) -> list[str]:
    if not isinstance(slug, str) or not slug.strip():
        return ["missing: adr.slug"]
    if not ADR_SLUG_PATTERN.match(slug):
        return ["adr.slug must contain lowercase letters, digits, and single hyphens"]
    return []


def build_planned_path(issue_number: int, slug: str) -> str:
    return f"backend/docs/adr/{issue_number}-{slug}.md"


def validate_planned_path(planned_path: Any) -> list[str]:
    if not isinstance(planned_path, str) or not planned_path.strip():
        return ["missing: adr.planned_path"]
    if planned_path.startswith("/") or ".." in Path(planned_path).parts:
        return ["adr.planned_path must stay under backend/docs/adr"]
    if not ADR_PATH_PATTERN.match(planned_path):
        return ["adr.planned_path must match backend/docs/adr/<issue-number>-<slug>.md"]
    return []


def planned_issue_number(planned_path: str) -> str:
    match = ADR_PATH_PATTERN.match(planned_path)
    if match is None:
        raise ValueError(f"invalid planned ADR path: {planned_path}")
    return match.group("issue")


def planned_slug(planned_path: str) -> str:
    match = ADR_PATH_PATTERN.match(planned_path)
    if match is None:
        raise ValueError(f"invalid planned ADR path: {planned_path}")
    return match.group("slug")


def _markdown_items(values: list[str]) -> str:
    return "\n".join(f"- {value}" for value in values) if values else "- 없음"


def render_proposed_adr(snapshot: dict[str, Any], planned_path: str) -> str:
    adr = snapshot["adr"]
    issue_number = planned_issue_number(planned_path)
    decision = adr["decision"]

    sections = [
        f"# {decision}",
        "## 상태",
        "Proposed",
        "## 관련 Issue",
        f"- #{issue_number} {snapshot['title']}",
        "## 한 줄 요약",
        decision,
        "## 왜 이 결정이 필요했나",
        "\n\n".join(
            [
                adr["context"],
                adr["situation"],
                "결정 동인:\n\n" + _markdown_items(adr["decision_drivers"]),
            ]
        ),
        "## 트레이드 오프",
        _markdown_items(adr["alternatives"]),
        "## 무엇을 결정했나",
        "\n\n".join([decision, adr["rationale"]]),
        "## 결과",
        _markdown_items(adr["consequences"]),
        "## 다시 논의해야 할 조건",
        _markdown_items(adr["revisit_when"]),
        "## 확인",
        "\n".join(
            [
                f"- 예정 경로: `{planned_path}`",
                f"- 결정 주체: {adr.get('decision_makers', 'Knot 팀')}",
                "- AI 하네스가 Proposed ADR 파일을 생성했다.",
                "- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.",
            ]
        ),
    ]

    return "\n\n".join(sections) + "\n"
