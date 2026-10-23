"""knot-feature 입구 스킬이 쓰는 결정적 검증기와 분기기.

서브 에이전트가 돌려주는 반환 규약 JSON과 입구가 관리하는 상태 파일을 검증하고,
상태 파일만 보고 다음에 부를 서브 에이전트를 정하며(`next`), 지금 어디까지 왔는지
사람이 읽게 요약하고(`status`), `changed` 목록을 이슈와 PR의 「노션 변경 사항」
Markdown으로 렌더링한다(`render`).
정본 규칙은 `.agents/skills/knot-feature/references/return-contract.md`다.

분기 규칙이 이 파일에 있는 이유: 서브 에이전트가 다음 단계를 고르면 AI 판단에 따라
흐름이 달라진다. 서브 에이전트는 사실(`flags`)만 돌려주고 분기는 여기서 한다.
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
MESSAGES = (
    "기존과 대조해 업데이트해라",
    "업데이트해라",
    "시나리오 뽑아 업데이트해라",
    "이슈 생성해",
    "구현해라",
)
AREAS = ("frontend", "backend", "both")
DONE_CONDITIONS = ("issue", "development")
REQUEST_KINDS = ("feature", "fix", "chore")
# 서브 에이전트가 돌려주는 사실. 값의 형은 bool이거나 figma_url처럼 URL 또는 null이다.
FLAG_TYPES = {
    "requirements_changed": "bool",
    "backend_rules_affected": "bool",
    "domain_rules_changed": "bool",
    "screen_changed": "bool",
    "figma_url": "url_or_null",
}
IMPLEMENTERS = {"frontend": "FE 구현 하네스", "backend": "BE 구현 하네스"}
DEFAULT_IMPLEMENTATION_ORDER = ("backend", "frontend")
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


def validate_flags(flags: Any, field: str = "flags") -> list[str]:
    if flags is None:
        return []
    if not isinstance(flags, dict):
        return [f"{field} must be an object"]
    errors: list[str] = []
    for key, value in flags.items():
        kind = FLAG_TYPES.get(key)
        if kind is None:
            errors.append(f"{field}.{key} is not a known flag ({', '.join(FLAG_TYPES)})")
        elif kind == "bool" and not isinstance(value, bool):
            errors.append(f"{field}.{key} must be a boolean")
        elif kind == "url_or_null" and value is not None and not _is_url(value):
            errors.append(f"{field}.{key} must be null or an http(s) URL")
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
    if "next" in result:
        errors.append(f"{field}.next is no longer accepted; return facts in {field}.flags and let `next` decide")
    errors.extend(validate_flags(result.get("flags"), f"{field}.flags"))
    question = result.get("question", "")
    if question is not None and not isinstance(question, str):
        errors.append(f"{field}.question must be a string")
    if status in ("needs_user_input", "blocked") and not _is_nonblank_string(question):
        errors.append(f"{field}.question is required when status is {status}")
    _string_list(result.get("side_effects", []), f"{field}.side_effects", errors)
    return errors


def validate_state(state: Any) -> list[str]:
    """입구가 관리하는 상태 파일을 검증한다."""
    if not isinstance(state, dict):
        return ["state must be an object"]
    errors: list[str] = []
    if not _is_nonblank_string(state.get("request")):
        errors.append("request must be a non-blank string")
    existing = state.get("existing_issue_url")
    if existing is not None and not _is_url(existing):
        errors.append("existing_issue_url must be null or an http(s) URL")
    if state.get("kind") not in REQUEST_KINDS:
        errors.append(f"kind must be one of {', '.join(REQUEST_KINDS)}")
    if state.get("area") not in AREAS:
        errors.append(f"area must be one of {', '.join(AREAS)}")
    if state.get("done_condition") not in DONE_CONDITIONS:
        errors.append(f"done_condition must be one of {', '.join(DONE_CONDITIONS)}")
    if not isinstance(state.get("remote_write_authorized", False), bool):
        errors.append("remote_write_authorized must be a boolean")
    order = state.get("implementation_order", list(DEFAULT_IMPLEMENTATION_ORDER))
    if not isinstance(order, list) or sorted(order) != sorted(DEFAULT_IMPLEMENTATION_ORDER):
        errors.append("implementation_order must list backend and frontend once each")
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
    assumptions = state.get("assumptions", [])
    if not isinstance(assumptions, list):
        errors.append("assumptions must be a list")
    else:
        for index, item in enumerate(assumptions):
            prefix = f"assumptions[{index}]"
            if not isinstance(item, dict):
                errors.append(f"{prefix} must be an object")
                continue
            if item.get("stage") not in AGENTS:
                errors.append(f"{prefix}.stage must be one of {', '.join(AGENTS)}")
            for key in ("assumed", "reason"):
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
    return errors


def collect_changed(state: dict[str, Any]) -> list[dict[str, Any]]:
    """상태 파일의 모든 단계 결과에서 changed 항목을 순서대로 모은다."""
    changed: list[dict[str, Any]] = []
    for step in state.get("steps", []):
        result = step.get("result") if isinstance(step, dict) else None
        if isinstance(result, dict):
            changed.extend(item for item in result.get("changed", []) if isinstance(item, dict))
    return changed


def collect_flags(state: dict[str, Any]) -> dict[str, Any]:
    """끝난 단계들이 돌려준 사실을 순서대로 합친다. 뒤 단계가 같은 키를 주면 덮는다."""
    flags: dict[str, Any] = {key: (None if kind == "url_or_null" else False) for key, kind in FLAG_TYPES.items()}
    for step in state.get("steps", []):
        result = step.get("result") if isinstance(step, dict) else None
        if isinstance(result, dict) and result.get("status") == "done":
            flags.update(result.get("flags") or {})
    return flags


def _done_agents(state: dict[str, Any]) -> set[str]:
    return {
        step["agent"]
        for step in state.get("steps", [])
        if isinstance(step, dict) and isinstance(step.get("result"), dict) and step["result"].get("status") == "done"
    }


def _answered(state: dict[str, Any], agent: str, question: str) -> bool:
    return any(
        isinstance(item, dict) and item.get("to") == agent and item.get("question") == question
        for item in state.get("answers", [])
    )


def next_stage(state: dict[str, Any]) -> dict[str, Any]:
    """상태 파일만 보고 다음 행동을 정한다. 결과의 status는 next, waiting, blocked, finished 중 하나다.

    분기 규칙(SKILL.md 3절의 표와 같다):
    - 마지막 단계가 needs_user_input이고 답이 아직 없으면 waiting, 답이 있으면 같은 단계를 다시 부른다.
    - 마지막 단계가 blocked면 blocked.
    - kind가 fix나 chore이거나 기존 이슈 URL이 있으면 문서 단계를 건너뛴다.
    - feature는 기능 요구사항부터. 백엔드 길에서 backend_rules_affected면 도메인 규칙, 도메인 규칙이
      바뀌었으면 API 명세. 프론트 길에서 screen_changed인데 figma_url이 없으면 디자인.
      requirements_changed면 QA 시트. 그다음 이슈.
    - 완료 조건이 development면 영역별 구현 하네스를 implementation_order 순서로 부른다.
    """
    steps = state.get("steps", [])
    if steps:
        last = steps[-1]
        last_result = last.get("result", {})
        last_status = last_result.get("status")
        if last_status == "needs_user_input":
            question = last_result.get("question", "")
            if _answered(state, last["agent"], question):
                return {
                    "status": "next",
                    "agent": last["agent"],
                    "message": last["message"],
                    "reason": "답을 붙여 같은 서브 에이전트를 다시 부른다",
                }
            return {"status": "waiting", "agent": last["agent"], "question": question}
        if last_status == "blocked":
            return {"status": "blocked", "agent": last["agent"], "question": last_result.get("question", "")}

    done = _done_agents(state)
    flags = collect_flags(state)
    area = state.get("area")
    kind = state.get("kind", "feature")
    skip_documents = kind != "feature" or bool(state.get("existing_issue_url"))

    def go(agent: str, message: str, reason: str) -> dict[str, Any]:
        return {"status": "next", "agent": agent, "message": message, "reason": reason}

    if not skip_documents:
        if "기능 요구사항" not in done:
            return go("기능 요구사항", "기존과 대조해 업데이트해라", "feature 요청은 항상 기능 요구사항부터")
        if area in ("backend", "both") and flags["backend_rules_affected"] and "도메인 규칙" not in done:
            return go("도메인 규칙", "업데이트해라", "백엔드 길이고 기능 요구사항이 백엔드 규칙 변경을 보고했다")
        if "도메인 규칙" in done and flags["domain_rules_changed"] and "API 명세" not in done:
            return go("API 명세", "업데이트해라", "도메인 규칙이 바뀌었다")
        if area in ("frontend", "both") and flags["screen_changed"] and not flags["figma_url"] and "디자인" not in done:
            return go("디자인", "업데이트해라", "프론트 길이고 화면이 바뀌는데 Figma 링크가 없다")
        if flags["requirements_changed"] and "QA 시트" not in done:
            return go("QA 시트", "시나리오 뽑아 업데이트해라", "기능 요구사항이 바뀌었다")
    if not state.get("existing_issue_url") and "이슈" not in done:
        reason = "문서 갱신이 끝났다" if not skip_documents else f"{kind} 요청은 문서 단계 없이 이슈부터"
        return go("이슈", "이슈 생성해", reason)
    if state.get("done_condition") != "development":
        return {"status": "finished", "reason": "완료 조건이 「이슈까지만」이다"}
    order = state.get("implementation_order", list(DEFAULT_IMPLEMENTATION_ORDER))
    targets = [area] if area in IMPLEMENTERS else [item for item in order if item in IMPLEMENTERS]
    for target in targets:
        implementer = IMPLEMENTERS[target]
        if implementer not in done:
            return go(implementer, "구현해라", f"{target} 구현이 남았다")
    return {"status": "finished", "reason": "모든 단계가 끝났다"}


def summarize_status(state: dict[str, Any]) -> str:
    """사람이 읽는 진행 요약. 「방금 뭐 하고 있었지」에 답한다."""
    lines = [
        f"요청: {state.get('request', '')}",
        "종류: {kind} / 영역: {area} / 완료 조건: {done} / 게시 허용: {remote}".format(
            kind=state.get("kind", "?"),
            area=state.get("area", "?"),
            done=state.get("done_condition", "?"),
            remote="예" if state.get("remote_write_authorized") else "아니오",
        ),
    ]
    if state.get("existing_issue_url"):
        lines.append(f"기존 이슈: {state['existing_issue_url']}")
    steps = state.get("steps", [])
    lines.append("끝난 단계:" if steps else "끝난 단계: 없음")
    for index, step in enumerate(steps, 1):
        result = step.get("result", {})
        changed = len(result.get("changed", []))
        lines.append(f"  {index}. {step.get('agent')} 「{step.get('message')}」 — {result.get('status')} (변경 {changed}건)")
    decision = next_stage(state)
    if decision["status"] == "next":
        lines.append(f"다음: {decision['agent']} 「{decision['message']}」 ({decision['reason']})")
    elif decision["status"] == "waiting":
        lines.append(f"기다리는 답: {decision['agent']} — {decision['question']}")
    elif decision["status"] == "blocked":
        lines.append(f"막힘: {decision['agent']} — {decision['question']}")
    else:
        lines.append(f"다음: 없음 ({decision['reason']})")
    assumptions = state.get("assumptions", [])
    if assumptions:
        lines.append("기본값으로 처리한 미결:")
        for item in assumptions:
            lines.append(f"  - [{item.get('stage')}] {item.get('assumed')} (이유: {item.get('reason')})")
    for label, field in (("이슈", "issues"), ("PR", "prs")):
        items = state.get(field, [])
        if items:
            lines.append(f"{label}: " + ", ".join(f"{item.get('area')} {item.get('url')}" for item in items))
    return "\n".join(lines) + "\n"


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


def _load(path: str) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _report(errors: list[str], pretty: bool) -> int:
    payload = {"status": "pass" if not errors else "fail", "errors": errors}
    print(json.dumps(payload, ensure_ascii=False, indent=2 if pretty else None))
    return 0 if not errors else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate knot-feature results and state, decide the next stage, and render 노션 변경 사항."
    )
    sub = parser.add_subparsers(dest="command", required=True)
    result_parser = sub.add_parser("result", help="서브 에이전트 반환값 JSON 검증")
    result_parser.add_argument("file")
    result_parser.add_argument("--pretty", action="store_true")
    state_parser = sub.add_parser("state", help="상태 파일 JSON 검증")
    state_parser.add_argument("file")
    state_parser.add_argument("--pretty", action="store_true")
    next_parser = sub.add_parser("next", help="상태 파일만 보고 다음에 부를 서브 에이전트를 정한다")
    next_parser.add_argument("file")
    next_parser.add_argument("--pretty", action="store_true")
    status_parser = sub.add_parser("status", help="어디까지 했고 다음이 무엇인지 사람이 읽게 요약")
    status_parser.add_argument("file")
    render_parser = sub.add_parser("render", help="상태 파일 또는 반환값의 changed를 Markdown으로")
    render_parser.add_argument("file")
    render_parser.add_argument("--issue", action="store_true", help="이슈 본문용(## 없이)")
    args = parser.parse_args(argv)

    data = _load(args.file)
    if args.command == "result":
        return _report(validate_result(data), args.pretty)
    if args.command == "state":
        return _report(validate_state(data), args.pretty)
    if args.command in ("next", "status"):
        errors = validate_state(data)
        if errors:
            return _report(errors, getattr(args, "pretty", False))
        if args.command == "next":
            print(json.dumps(next_stage(data), ensure_ascii=False, indent=2 if args.pretty else None))
        else:
            sys.stdout.write(summarize_status(data))
        return 0
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
