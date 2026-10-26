"""knot-feature 입구 스킬이 쓰는 결정적 검증기.

서브 에이전트가 돌려주는 반환 규약 JSON과 입구가 관리하는 상태 파일을 검증하고,
`changed` 목록을 이슈와 PR의 「노션 변경 사항」 Markdown으로 렌더링한다.
정본 규칙은 `.agents/skills/knot-feature/references/return-contract.md`다.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

STATUSES = ("done", "needs_user_input", "blocked")
KINDS = ("notion", "figma", "issue", "pr")
KIND_LABELS = {"notion": "노션", "figma": "Figma", "issue": "이슈", "pr": "PR"}
AGENTS = (
    "기능 요구사항",
    "도메인 규칙",
    "API 명세",
    "디자인",
    "QA 시트",
    "이슈",
    "FE 구현 하네스",
    "BE 구현 하네스",
)
PROPOSE_MESSAGE = "기존과 대조해 제안해라"
UPDATE_MESSAGE = "기존과 대조해 업데이트해라"
MESSAGES = (
    PROPOSE_MESSAGE,
    UPDATE_MESSAGE,
    "업데이트해라",
    "시나리오 뽑아 업데이트해라",
    "이슈 생성해",
    "구현해라",
)
AREAS = ("frontend", "backend", "both")
DONE_CONDITIONS = ("issue", "development")
PLAN_CONFIRMATION_STATUSES = ("pending", "confirmed", "rejected", "not_needed")
UNCONFIRMED_STATUSES = ("pending", "rejected")
NOTION_CHANGES_TITLE = "노션 변경 사항"


def _is_nonblank_string(value: Any) -> bool:
    return isinstance(value, str) and value.strip() != ""


def _is_url(value: Any) -> bool:
    return _is_nonblank_string(value) and value.startswith(("http://", "https://"))


def _string_list(value: Any, field: str, errors: list[str]) -> None:
    if not isinstance(value, list):
        errors.append(f"{field} must be a list")
        return
    for index, item in enumerate(value):
        if not _is_nonblank_string(item):
            errors.append(f"{field}[{index}] must be a non-blank string")


def validate_changed(changed: Any, field: str = "changed") -> list[str]:
    errors: list[str] = []
    if not isinstance(changed, list):
        return [f"{field} must be a list"]
    for index, item in enumerate(changed):
        prefix = f"{field}[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{prefix} must be an object")
            continue
        if item.get("kind") not in KINDS:
            errors.append(f"{prefix}.kind must be one of {', '.join(KINDS)}")
        if not _is_url(item.get("url")):
            errors.append(f"{prefix}.url must be an http(s) URL")
        if not _is_nonblank_string(item.get("summary")):
            errors.append(f"{prefix}.summary must be a non-blank string")
    return errors


def validate_result(result: Any, field: str = "result") -> list[str]:
    """서브 에이전트 반환값 하나를 검증한다."""
    if not isinstance(result, dict):
        return [f"{field} must be an object"]
    errors: list[str] = []
    status = result.get("status")
    if status not in STATUSES:
        errors.append(f"{field}.status must be one of {', '.join(STATUSES)}")
    errors.extend(validate_changed(result.get("changed", []), f"{field}.changed"))
    next_items = result.get("next", [])
    if not isinstance(next_items, list):
        errors.append(f"{field}.next must be a list")
    else:
        for index, item in enumerate(next_items):
            prefix = f"{field}.next[{index}]"
            if not isinstance(item, dict):
                errors.append(f"{prefix} must be an object")
                continue
            if item.get("agent") not in AGENTS:
                errors.append(f"{prefix}.agent must be one of {', '.join(AGENTS)}")
            if item.get("message") not in MESSAGES:
                errors.append(f"{prefix}.message must be one of {', '.join(MESSAGES)}")
    question = result.get("question", "")
    if question is not None and not isinstance(question, str):
        errors.append(f"{field}.question must be a string")
    if status in ("needs_user_input", "blocked") and not _is_nonblank_string(question):
        errors.append(f"{field}.question is required when status is {status}")
    _string_list(result.get("side_effects", []), f"{field}.side_effects", errors)
    return errors


def validate_state(state: Any, plan_confirmers: list[str] | None = None) -> list[str]:
    """입구가 관리하는 상태 파일을 검증한다."""
    if not isinstance(state, dict):
        return ["state must be an object"]
    errors: list[str] = []
    if not _is_nonblank_string(state.get("request")):
        errors.append("request must be a non-blank string")
    existing = state.get("existing_issue_url")
    if existing is not None and not _is_url(existing):
        errors.append("existing_issue_url must be null or an http(s) URL")
    if state.get("area") not in AREAS:
        errors.append(f"area must be one of {', '.join(AREAS)}")
    if state.get("done_condition") not in DONE_CONDITIONS:
        errors.append(f"done_condition must be one of {', '.join(DONE_CONDITIONS)}")
    if not isinstance(state.get("remote_write_authorized", False), bool):
        errors.append("remote_write_authorized must be a boolean")
    answers = state.get("answers", [])
    if not isinstance(answers, list):
        errors.append("answers must be a list")
    else:
        for index, item in enumerate(answers):
            prefix = f"answers[{index}]"
            if not isinstance(item, dict):
                errors.append(f"{prefix} must be an object")
                continue
            for key in ("to", "question", "answer"):
                if not _is_nonblank_string(item.get(key)):
                    errors.append(f"{prefix}.{key} must be a non-blank string")
    steps = state.get("steps", [])
    if not isinstance(steps, list):
        errors.append("steps must be a list")
    else:
        for index, step in enumerate(steps):
            prefix = f"steps[{index}]"
            if not isinstance(step, dict):
                errors.append(f"{prefix} must be an object")
                continue
            if step.get("agent") not in AGENTS:
                errors.append(f"{prefix}.agent must be one of {', '.join(AGENTS)}")
            if step.get("message") not in MESSAGES:
                errors.append(f"{prefix}.message must be one of {', '.join(MESSAGES)}")
            errors.extend(validate_result(step.get("result"), f"{prefix}.result"))
    for field in ("issues", "prs"):
        items = state.get(field, [])
        if not isinstance(items, list):
            errors.append(f"{field} must be a list")
            continue
        for index, item in enumerate(items):
            prefix = f"{field}[{index}]"
            if not isinstance(item, dict):
                errors.append(f"{prefix} must be an object")
                continue
            if item.get("area") not in AREAS:
                errors.append(f"{prefix}.area must be one of {', '.join(AREAS)}")
            if not _is_url(item.get("url")):
                errors.append(f"{prefix}.url must be an http(s) URL")
            if field == "issues":
                number = item.get("number")
                if not isinstance(number, int) or isinstance(number, bool) or number <= 0:
                    errors.append(f"{prefix}.number must be a positive integer")
    implementer = state.get("implementer")
    if implementer is not None and not _is_nonblank_string(implementer):
        errors.append("implementer must be null or a GitHub login")
    if (state.get("issues") or state.get("prs")) and not _is_nonblank_string(implementer):
        errors.append("implementer is required before issues or prs are created")
    errors.extend(validate_plan_confirmation(state, plan_confirmers))
    return errors


def validate_plan_confirmation(
    state: dict[str, Any], plan_confirmers: list[str] | None = None
) -> list[str]:
    """기능 추가, 수정, 삭제는 1차 컨펌 전에 노션, Figma, 이슈, PR을 바꾸지 않는다.

    확정 전 제안은 노션 「기능 요구사항 검토」 DB의 제안 페이지(`proposal_url`)에만 있다.
    1차 컨펌의 근거는 제안 페이지의 확정 버튼 기록(`상태`, `컨펌한 사람`, `컨펌 날짜`)뿐이다.
    `plan_confirmers`를 주면 `confirmed_by`에 기획 컨펌 담당이 있는지도 본다.
    """
    confirmation = state.get("plan_confirmation")
    if confirmation is None:
        return []
    if not isinstance(confirmation, dict):
        return ["plan_confirmation must be an object"]
    errors: list[str] = []
    required = confirmation.get("required")
    status = confirmation.get("status")
    if not isinstance(required, bool):
        errors.append("plan_confirmation.required must be a boolean")
    if status not in PLAN_CONFIRMATION_STATUSES:
        errors.append(
            f"plan_confirmation.status must be one of {', '.join(PLAN_CONFIRMATION_STATUSES)}"
        )
    if required is True and status == "not_needed":
        errors.append("plan_confirmation.status cannot be not_needed when required is true")
    if required is False and status in ("pending", "confirmed", "rejected"):
        errors.append("plan_confirmation.status must be not_needed when required is false")
    if required is True and not _is_url(confirmation.get("proposal_url")):
        errors.append("plan_confirmation.proposal_url must be the 기능 요구사항 검토 page URL")
    if status == "confirmed":
        confirmed_by = confirmation.get("confirmed_by")
        if not isinstance(confirmed_by, list) or not confirmed_by:
            errors.append("plan_confirmation.confirmed_by must be a non-empty list when confirmed")
        else:
            _string_list(confirmed_by, "plan_confirmation.confirmed_by", errors)
            if plan_confirmers is not None and not set(confirmed_by) & set(plan_confirmers):
                errors.append(
                    "plan_confirmation.confirmed_by must include a plan confirmer: "
                    f"{', '.join(plan_confirmers)}"
                )
        if not _is_nonblank_string(confirmation.get("confirmed_at")):
            errors.append("plan_confirmation.confirmed_at is required when confirmed (제안 페이지 컨펌 날짜)")
        if not _is_nonblank_string(confirmation.get("evidence")):
            errors.append("plan_confirmation.evidence is required when confirmed")
    if status in UNCONFIRMED_STATUSES:
        if collect_changed(state):
            errors.append(f"plan_confirmation is {status}: 1차 컨펌 전에는 노션과 Figma를 바꾸지 않는다")
        if state.get("issues") or state.get("prs"):
            errors.append(f"plan_confirmation is {status}: 1차 컨펌 전에는 이슈와 PR을 만들지 않는다")
        for index, step in enumerate(state.get("steps", [])):
            if isinstance(step, dict) and step.get("message") not in (None, PROPOSE_MESSAGE):
                errors.append(
                    f"steps[{index}] runs before 1차 컨펌: {status} 동안에는 「{PROPOSE_MESSAGE}」만 실행한다"
                )
    return errors


def collect_changed(state: dict[str, Any]) -> list[dict[str, Any]]:
    """상태 파일의 모든 단계 결과에서 changed 항목을 순서대로 모은다."""
    changed: list[dict[str, Any]] = []
    for step in state.get("steps", []):
        result = step.get("result") if isinstance(step, dict) else None
        if isinstance(result, dict):
            changed.extend(item for item in result.get("changed", []) if isinstance(item, dict))
    return changed


def render_notion_changes(changed: list[dict[str, Any]], for_issue: bool = False) -> str:
    """changed 목록을 「노션 변경 사항」 Markdown으로 만든다.

    PR 본문에서는 첫 번째 `##` 섹션으로, 이슈 본문에서는 세 섹션 규칙 때문에
    `##` 없이 굵은 글씨 제목으로 쓴다. 내용은 베끼지 않고 링크만 단다.
    """
    errors = validate_changed(changed)
    if errors:
        raise ValueError("; ".join(errors))
    title = f"**{NOTION_CHANGES_TITLE}**" if for_issue else f"## {NOTION_CHANGES_TITLE}"
    if not changed:
        return f"{title}\n\n- 없음\n"
    lines = [
        f"- {KIND_LABELS[item['kind']]}: [{item['summary'].strip()}]({item['url'].strip()})"
        for item in changed
    ]
    return f"{title}\n\n" + "\n".join(lines) + "\n"


def _plan_confirmers() -> list[str]:
    from team_roles import load_team

    return list(load_team().get("plan_confirmers", []))


def _load(path: str) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _report(errors: list[str], pretty: bool) -> int:
    payload = {"status": "pass" if not errors else "fail", "errors": errors}
    print(json.dumps(payload, ensure_ascii=False, indent=2 if pretty else None))
    return 0 if not errors else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate knot-feature results and state, and render 노션 변경 사항."
    )
    sub = parser.add_subparsers(dest="command", required=True)
    result_parser = sub.add_parser("result", help="서브 에이전트 반환값 JSON 검증")
    result_parser.add_argument("file")
    result_parser.add_argument("--pretty", action="store_true")
    state_parser = sub.add_parser("state", help="상태 파일 JSON 검증")
    state_parser.add_argument("file")
    state_parser.add_argument("--pretty", action="store_true")
    render_parser = sub.add_parser("render", help="상태 파일 또는 반환값의 changed를 Markdown으로")
    render_parser.add_argument("file")
    render_parser.add_argument("--issue", action="store_true", help="이슈 본문용(## 없이)")
    args = parser.parse_args(argv)

    data = _load(args.file)
    if args.command == "result":
        return _report(validate_result(data), args.pretty)
    if args.command == "state":
        return _report(validate_state(data, _plan_confirmers()), args.pretty)
    changed = collect_changed(data) if isinstance(data, dict) and "steps" in data else (
        data.get("changed", []) if isinstance(data, dict) else data
    )
    try:
        sys.stdout.write(render_notion_changes(changed, for_issue=args.issue))
    except ValueError as error:
        print(json.dumps({"status": "fail", "errors": str(error).split("; ")}, ensure_ascii=False))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
