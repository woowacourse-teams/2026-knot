#!/usr/bin/env python3
"""Plan a Knot Issue contract and optionally publish it to GitHub."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import subprocess
from pathlib import Path
from typing import Any, Callable

import adr_contract


HIGH_RISK_SIGNALS = {
    "data",
    "security",
    "external",
    "cross-boundary",
    "core-flow",
    "shared",
    "hard-to-reverse",
}

ISSUE_H2_HEADINGS = ("## 구현 기능 설명", "## TODO", "## 메모")
MARKDOWN_H2_PATTERN = re.compile(r"(?m)^[ \t]{0,3}##(?:[ \t]+|$)")
REPO_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
ISSUE_URL_NUMBER_PATTERN = re.compile(r"/issues/([1-9]\d*)(?:$|[?#])")

LIGHTWEIGHT_REQUIRED = (
    "title",
    "purpose",
    "scope",
    "acceptance_criteria",
    "verification",
    "evidence",
)

HARNESSED_REQUIRED = (
    "non_goals",
    "normal_flows",
    "failure_flows",
    "recovery_flows",
    "impacts",
    "dependencies",
    "residual_risks",
    "interview",
    "grill",
    "adr",
)

INTERVIEW_EVIDENCE_FIELDS = (
    "context",
    "situation",
    "need",
    "alternatives",
    "decision",
    "rationale",
)

ADR_REQUIRED = (
    "status",
    "decision",
    "context",
    "situation",
    "decision_drivers",
    "alternatives",
    "alternatives_confirmed",
    "long_term_impact",
    "future_reference",
    "rationale",
    "consequences",
    "revisit_when",
    "slug",
)

LIST_FIELDS = (
    "scope",
    "acceptance_criteria",
    "verification",
    "evidence",
    "risk_signals",
    "non_goals",
    "normal_flows",
    "failure_flows",
    "recovery_flows",
    "impacts",
    "dependencies",
    "residual_risks",
)

ADR_STRING_FIELDS = (
    "status",
    "decision",
    "context",
    "situation",
    "rationale",
)

ADR_LIST_FIELDS = (
    "decision_drivers",
    "alternatives",
    "consequences",
    "revisit_when",
)

ADR_TRUE_FIELDS = (
    "alternatives_confirmed",
    "long_term_impact",
    "future_reference",
)


def _is_nonblank_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _has_value(value: Any) -> bool:
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, list):
        return bool(value)
    if isinstance(value, dict):
        return bool(value)
    return value is not None


def _missing_fields(snapshot: dict[str, Any], fields: tuple[str, ...]) -> list[str]:
    return [field for field in fields if not _has_value(snapshot.get(field))]


def _missing_keys(snapshot: dict[str, Any], fields: tuple[str, ...]) -> list[str]:
    return [field for field in fields if field not in snapshot]


def _string_list_errors(field: str, value: Any) -> list[str]:
    if not isinstance(value, list):
        return [f"{field} must be a list"]
    if not all(_is_nonblank_string(item) for item in value):
        return [f"{field} must contain non-empty strings"]
    return []


def _markdown_h2_errors(value: Any, field: str = "snapshot") -> list[str]:
    if isinstance(value, str):
        if MARKDOWN_H2_PATTERN.search(value):
            return [f"{field} must not contain Markdown level-2 headings"]
        return []
    if isinstance(value, list):
        errors: list[str] = []
        for index, item in enumerate(value):
            errors.extend(_markdown_h2_errors(item, f"{field}[{index}]"))
        return errors
    if isinstance(value, dict):
        errors = []
        for key, item in value.items():
            errors.extend(_markdown_h2_errors(item, f"{field}.{key}"))
        return errors
    return []


def _interview_errors(interview: Any) -> list[str]:
    if not isinstance(interview, dict):
        return ["interview must be an object"]

    errors: list[str] = []
    status = interview.get("status")
    if status not in {"completed", "skipped"}:
        errors.append("interview.status must be completed or skipped")

    evidence = interview.get("evidence")
    if not isinstance(evidence, dict):
        errors.append("interview.evidence must be an object")
    else:
        for field in INTERVIEW_EVIDENCE_FIELDS:
            item = evidence.get(field)
            prefix = f"interview.evidence.{field}"
            if not isinstance(item, dict):
                errors.append(f"missing: {prefix}")
                continue
            if not _is_nonblank_string(item.get("summary")):
                errors.append(f"missing: {prefix}.summary")
            sources = item.get("sources")
            if not isinstance(sources, list):
                errors.append(f"{prefix}.sources must be a list")
            elif not sources:
                errors.append(f"missing: {prefix}.sources")
            else:
                errors.extend(_string_list_errors(f"{prefix}.sources", sources))

    conflicts = interview.get("conflicts")
    if not isinstance(conflicts, list):
        errors.append("interview.conflicts must be a list")
    else:
        errors.extend(_string_list_errors("interview.conflicts", conflicts))
        if conflicts:
            errors.append("interview.conflicts must be empty before pass")

    if interview.get("current_validity") != "confirmed":
        errors.append("interview.current_validity must be confirmed")

    resolved_questions = interview.get("resolved_questions")
    if not isinstance(resolved_questions, list):
        errors.append("interview.resolved_questions must be a list")
    else:
        errors.extend(
            _string_list_errors("interview.resolved_questions", resolved_questions)
        )
        if status == "completed" and not resolved_questions:
            errors.append("missing: interview.resolved_questions")
        if status == "skipped" and resolved_questions:
            errors.append(
                "interview.resolved_questions must be empty when interview is skipped"
            )

    return errors


def _requested_action(snapshot: Any) -> str:
    if not isinstance(snapshot, dict):
        return "unknown"
    if snapshot.get("operation") == "create":
        return "publish_issue"
    if snapshot.get("operation") == "draft":
        return "render_draft"
    return "unknown"


def _issue_h2_headings(markdown: str) -> tuple[str, ...]:
    return tuple(line for line in markdown.splitlines() if line.startswith("## "))


def classify_risk(snapshot: dict[str, Any]) -> tuple[str, list[str]]:
    if "risk_signals" not in snapshot:
        return "unknown", ["missing: risk_signals"]

    signals = snapshot["risk_signals"]
    if not isinstance(signals, list):
        return "unknown", ["risk_signals must be a list"]
    if not all(_is_nonblank_string(signal) for signal in signals):
        return "unknown", ["risk_signals must contain non-empty strings"]

    unknown = sorted(set(signals) - HIGH_RISK_SIGNALS)
    if unknown:
        return "unknown", [f"unknown risk signal: {signal}" for signal in unknown]
    return ("high" if signals else "low"), []


def resolve_adr_path(
    snapshot: Any,
    *,
    require_final: bool = False,
) -> tuple[str | None, list[str]]:
    if not isinstance(snapshot, dict):
        return None, ["snapshot must be an object"]

    adr = snapshot.get("adr")
    if not isinstance(adr, dict):
        return None, ["adr must be an object"]

    errors = adr_contract.validate_slug(adr.get("slug"))
    issue_number = snapshot.get("issue_number")
    issue_number_valid = False
    if issue_number is not None:
        issue_number_errors = adr_contract.validate_issue_number(issue_number)
        errors.extend(issue_number_errors)
        issue_number_valid = not issue_number_errors
    elif require_final:
        errors.append("missing: issue_number for ADR materialization")

    planned_path = adr.get("planned_path")
    resolved_path: str | None = None
    if "planned_path" in adr:
        path_errors = validate_adr_path(planned_path)
        errors.extend(path_errors)
        if snapshot.get("operation") == "create" and issue_number is None:
            errors.append(
                "issue_number is required to finalize an ADR path after Issue creation"
            )
        if not path_errors:
            resolved_path = planned_path
            if not adr_contract.validate_slug(adr.get("slug")):
                if adr_contract.planned_slug(planned_path) != adr["slug"]:
                    errors.append("adr.planned_path slug must match adr.slug")
            if issue_number_valid:
                if adr_contract.planned_issue_number(planned_path) != str(issue_number):
                    errors.append(
                        "adr.planned_path issue number must match issue_number"
                    )
    elif issue_number_valid and not adr_contract.validate_slug(adr.get("slug")):
        resolved_path = adr_contract.build_planned_path(issue_number, adr["slug"])

    return resolved_path, sorted(set(errors))


def validate(snapshot: Any) -> tuple[str, list[str]]:
    if not isinstance(snapshot, dict):
        return "unknown", ["snapshot must be an object"]

    errors: list[str] = []
    operation = snapshot.get("operation")
    if not isinstance(operation, str) or operation not in {"draft", "create"}:
        errors.append("operation must be draft or create")

    title = snapshot.get("title")
    if title is not None and not isinstance(title, str):
        errors.append("title must be a string")
    elif (
        isinstance(title, str)
        and title.strip()
        and not re.match(r"^\[(BE|FE)\] \S", title)
    ):
        errors.append("title must start with [BE] or [FE]")

    purpose = snapshot.get("purpose")
    if purpose is not None and not isinstance(purpose, str):
        errors.append("purpose must be a string")

    if "issue_number" in snapshot:
        errors.extend(adr_contract.validate_issue_number(snapshot["issue_number"]))

    for field in LIST_FIELDS:
        if field in snapshot and field != "risk_signals":
            errors.extend(_string_list_errors(field, snapshot[field]))

    risk_level, risk_errors = classify_risk(snapshot)
    errors.extend(risk_errors)
    errors.extend(
        f"missing: {field}" for field in _missing_fields(snapshot, LIGHTWEIGHT_REQUIRED)
    )
    errors.extend(_markdown_h2_errors(snapshot))

    if risk_level == "high":
        for field in _missing_keys(snapshot, HARNESSED_REQUIRED):
            errors.append(f"missing: {field}")

        for field in (
            "non_goals",
            "normal_flows",
            "failure_flows",
            "recovery_flows",
            "impacts",
        ):
            if field in snapshot and not _has_value(snapshot[field]):
                errors.append(f"missing: {field}")

        if "interview" in snapshot:
            errors.extend(_interview_errors(snapshot["interview"]))

        grill = snapshot.get("grill")
        if grill is not None and not isinstance(grill, dict):
            errors.append("grill must be an object")
        elif isinstance(grill, dict):
            if grill.get("status") != "pass":
                errors.append("grill.status must be pass")
            resolved_questions = grill.get("resolved_questions")
            if not _has_value(resolved_questions):
                errors.append("missing: grill.resolved_questions")
            elif not isinstance(resolved_questions, list):
                errors.append("grill.resolved_questions must be a list")
            else:
                errors.extend(
                    _string_list_errors("grill.resolved_questions", resolved_questions)
                )

    adr = snapshot.get("adr")
    if adr is not None and not isinstance(adr, dict):
        errors.append("adr must be an object")
    elif isinstance(adr, dict):
        required = adr.get("required")
        if required is True:
            if risk_level != "high":
                errors.append("adr.required=true requires a high-risk contract")
            for field in _missing_fields(adr, ADR_REQUIRED):
                errors.append(f"missing: adr.{field}")
            for field in ADR_STRING_FIELDS:
                if field in adr and not isinstance(adr[field], str):
                    errors.append(f"adr.{field} must be a string")
            for field in ADR_LIST_FIELDS:
                if field in adr:
                    errors.extend(_string_list_errors(f"adr.{field}", adr[field]))
            if (
                isinstance(adr.get("alternatives"), list)
                and len(adr["alternatives"]) < 2
            ):
                errors.append(
                    "adr.alternatives must include at least 2 confirmed real alternatives"
                )
            for field in ADR_TRUE_FIELDS:
                if field in adr and adr.get(field) is not True:
                    errors.append(f"adr.{field} must be true")
            if "status" in adr and adr.get("status") != "proposed":
                errors.append("adr.status must be proposed")
            if "decision_makers" in adr and not _is_nonblank_string(
                adr["decision_makers"]
            ):
                errors.append("adr.decision_makers must be a non-empty string")
            _, path_errors = resolve_adr_path(snapshot)
            errors.extend(path_errors)
        elif required is False:
            reason = adr.get("reason")
            if not _has_value(reason):
                errors.append("missing: adr.reason")
            elif not isinstance(reason, str):
                errors.append("adr.reason must be a string")
        else:
            errors.append("adr.required must be true or false")

    return risk_level, sorted(set(errors))


def contract_id(snapshot: Any) -> str:
    canonical = json.dumps(
        snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


def _markdown_items(values: list[str]) -> str:
    return "\n".join(f"- {value}" for value in values) if values else "- 없음"


def _checkbox_items(values: list[str]) -> str:
    return "\n".join(f"- [ ] {value}" for value in values) if values else "- [ ] 없음"


def validate_adr_path(planned_path: Any) -> list[str]:
    return adr_contract.validate_planned_path(planned_path)


def render_issue_body(
    snapshot: dict[str, Any], identifier: str, risk_level: str
) -> str:
    adr = snapshot.get("adr")
    adr_required = (
        risk_level == "high" and isinstance(adr, dict) and adr.get("required") is True
    )
    description = snapshot["purpose"]
    if adr_required:
        description = "\n\n".join([adr["situation"], snapshot["purpose"]])

    todo_items = list(snapshot["scope"]) + [
        f"검증: {check}" for check in snapshot["verification"]
    ]
    memo_items = ["없음"]
    if adr_required:
        planned_path, _ = resolve_adr_path(snapshot)
        displayed_path = planned_path or f"backend/docs/adr/{{ISSUE_NUMBER}}-{adr['slug']}.md"
        memo_items = [
            f"ADR: {adr['decision']} — 예정 경로: `{displayed_path}` (Proposed)",
        ]

    sections = [
        f"<!-- knot-issue-contract:{identifier} -->",
        "## 구현 기능 설명",
        description,
        "## TODO",
        _checkbox_items(todo_items),
        "## 메모",
        _markdown_items(memo_items),
    ]

    return "\n\n".join(sections) + "\n"


def plan(snapshot: Any) -> dict[str, Any]:
    risk_level, errors = validate(snapshot)
    identifier = contract_id(snapshot)
    requested_action = _requested_action(snapshot)
    if errors:
        return {
            "status": "hold",
            "action": "report_hold",
            "requested_action": requested_action,
            "risk_level": risk_level,
            "remote_write_authorized": False,
            "publish_ready": False,
            "contract_id": identifier,
            "errors": errors,
        }

    operation = snapshot["operation"]
    adr = snapshot.get("adr", {})
    adr_required = (
        risk_level == "high" and isinstance(adr, dict) and adr.get("required") is True
    )
    resolved_adr_path = None
    if adr_required:
        resolved_adr_path, _ = resolve_adr_path(snapshot)
    issue_body = render_issue_body(snapshot, identifier, risk_level)
    if _issue_h2_headings(issue_body) != ISSUE_H2_HEADINGS:
        return {
            "status": "hold",
            "action": "report_hold",
            "requested_action": requested_action,
            "risk_level": risk_level,
            "remote_write_authorized": False,
            "publish_ready": False,
            "contract_id": identifier,
            "errors": [
                "rendered Issue must contain exactly the three allowed level-2 headings"
            ],
        }
    result = {
        "status": "pass",
        "action": "render_draft",
        "requested_action": requested_action,
        "risk_level": risk_level,
        "remote_write_authorized": False,
        "publish_ready": operation == "create",
        "contract_id": identifier,
        "next_on_implementation": (
            "materialize_proposed_adr" if adr_required else "none"
        ),
        "issue_body": issue_body,
    }
    if risk_level == "high":
        interview_status = snapshot["interview"]["status"]
        result["interview_status"] = interview_status
        result["interview_notice"] = (
            "자료 충분으로 인터뷰 생략"
            if interview_status == "skipped"
            else "사용자 확인으로 인터뷰 완료"
        )
    if adr_required:
        result["adr_path_status"] = (
            "finalized" if resolved_adr_path else "pending_issue_number"
        )
        result["next_after_issue_created"] = (
            "none" if resolved_adr_path else "finalize_adr_path"
        )
    return result


CommandRunner = Callable[..., subprocess.CompletedProcess[str]]


def _run_gh(
    argv: list[str],
    *,
    runner: CommandRunner,
    body: str | None = None,
) -> subprocess.CompletedProcess[str]:
    try:
        return runner(
            argv,
            check=False,
            capture_output=True,
            text=True,
            input=body,
        )
    except OSError as error:
        return subprocess.CompletedProcess(argv, 127, stdout="", stderr=str(error))


def _issue_number_from_url(issue_url: str) -> int | None:
    match = ISSUE_URL_NUMBER_PATTERN.search(issue_url.strip())
    return int(match.group(1)) if match else None


def _issue_number(record: dict[str, Any]) -> int | None:
    number = record.get("number")
    if isinstance(number, int) and number > 0:
        return number
    if isinstance(number, str) and number.isdigit() and int(number) > 0:
        return int(number)
    return None


def _issue_url(record: dict[str, Any]) -> str | None:
    url = record.get("url")
    return url.strip() if _is_nonblank_string(url) else None


def _publish_hold(
    planned: dict[str, Any],
    error: str,
    *,
    action: str = "publish_issue_hold",
    issue_url: str | None = None,
    issue_number: int | None = None,
    issue_body: str | None = None,
) -> dict[str, Any]:
    result = {
        **planned,
        "status": "hold",
        "action": action,
        "remote_write_authorized": True,
        "errors": [error],
    }
    if issue_url is not None:
        result["issue_url"] = issue_url
    if issue_number is not None:
        result["issue_number"] = issue_number
    if issue_body is not None:
        result["issue_body"] = issue_body
    return result


def _gh_failure(command: str, completed: subprocess.CompletedProcess[str]) -> str:
    detail = completed.stderr.strip() or completed.stdout.strip()
    suffix = f": {detail}" if detail else ""
    return f"{command} failed with exit code {completed.returncode}{suffix}"


def _finalize_adr_body(
    snapshot: dict[str, Any],
    planned: dict[str, Any],
    issue_number: int,
) -> tuple[dict[str, Any], str]:
    finalized_snapshot = copy.deepcopy(snapshot)
    finalized_snapshot["issue_number"] = issue_number
    issue_body = render_issue_body(
        finalized_snapshot,
        planned["contract_id"],
        planned["risk_level"],
    )
    finalized_plan = {
        **planned,
        "issue_body": issue_body,
        "adr_path_status": "finalized",
        "next_after_issue_created": "none",
    }
    return finalized_plan, issue_body


def _edit_issue_body(
    *,
    repo: str,
    issue_number: int,
    issue_body: str,
    runner: CommandRunner,
) -> subprocess.CompletedProcess[str]:
    return _run_gh(
        [
            "gh",
            "issue",
            "edit",
            str(issue_number),
            "--repo",
            repo,
            "--body-file",
            "-",
        ],
        runner=runner,
        body=issue_body,
    )


def publish_issue(
    snapshot: Any,
    repo: str | None,
    *,
    runner: CommandRunner = subprocess.run,
) -> dict[str, Any]:
    planned = plan(snapshot)
    if planned["status"] != "pass":
        return planned
    if not isinstance(snapshot, dict) or snapshot.get("operation") != "create":
        return {
            **planned,
            "status": "hold",
            "action": "report_hold",
            "remote_write_authorized": False,
            "publish_ready": False,
            "errors": ["publish requires operation=create"],
        }
    if not isinstance(repo, str) or REPO_PATTERN.fullmatch(repo) is None:
        return {
            **planned,
            "status": "hold",
            "action": "report_hold",
            "remote_write_authorized": False,
            "publish_ready": False,
            "errors": ["--repo must be provided as OWNER/REPO when --publish is used"],
        }

    marker = f"<!-- knot-issue-contract:{planned['contract_id']} -->"
    search = _run_gh(
        [
            "gh",
            "issue",
            "list",
            "--repo",
            repo,
            "--state",
            "all",
            "--search",
            f"knot-issue-contract:{planned['contract_id']} in:body",
            "--limit",
            "100",
            "--json",
            "number,url,body,title",
        ],
        runner=runner,
    )
    if search.returncode != 0:
        return _publish_hold(planned, _gh_failure("gh issue list", search))
    try:
        candidates = json.loads(search.stdout or "[]")
    except json.JSONDecodeError as error:
        return _publish_hold(planned, f"gh issue list returned invalid JSON: {error}")
    if not isinstance(candidates, list):
        return _publish_hold(planned, "gh issue list returned a non-list JSON payload")

    matches = [
        candidate
        for candidate in candidates
        if isinstance(candidate, dict)
        and isinstance(candidate.get("body"), str)
        and marker in candidate["body"]
    ]
    if len(matches) > 1:
        return _publish_hold(
            planned,
            f"multiple existing issues contain contract marker {planned['contract_id']}",
        )

    adr_required = (
        planned["risk_level"] == "high"
        and isinstance(snapshot.get("adr"), dict)
        and snapshot["adr"].get("required") is True
    )
    if matches:
        issue_number = _issue_number(matches[0])
        issue_url = _issue_url(matches[0])
        if issue_number is None or issue_url is None:
            return _publish_hold(planned, "existing issue is missing number or url")
        result_plan = planned
        if adr_required:
            result_plan, issue_body = _finalize_adr_body(
                snapshot, planned, issue_number
            )
            edited = _edit_issue_body(
                repo=repo,
                issue_number=issue_number,
                issue_body=issue_body,
                runner=runner,
            )
            if edited.returncode != 0:
                return _publish_hold(
                    result_plan,
                    _gh_failure("gh issue edit", edited),
                    action="partial_publish_issue",
                    issue_url=issue_url,
                    issue_number=issue_number,
                )
        return {
            **result_plan,
            "action": "reuse_existing_issue",
            "remote_write_authorized": True,
            "issue_url": issue_url,
            "issue_number": issue_number,
        }

    created = _run_gh(
        [
            "gh",
            "issue",
            "create",
            "--repo",
            repo,
            "--title",
            snapshot["title"],
            "--body-file",
            "-",
        ],
        runner=runner,
        body=planned["issue_body"],
    )
    if created.returncode != 0:
        return _publish_hold(planned, _gh_failure("gh issue create", created))

    issue_url = created.stdout.strip()
    issue_number = _issue_number_from_url(issue_url)
    if issue_number is None:
        return _publish_hold(
            planned,
            "gh issue create did not return a parseable issue URL",
            action="partial_publish_issue",
            issue_url=issue_url,
        )

    result_plan = planned
    if adr_required:
        result_plan, issue_body = _finalize_adr_body(snapshot, planned, issue_number)
        edited = _edit_issue_body(
            repo=repo,
            issue_number=issue_number,
            issue_body=issue_body,
            runner=runner,
        )
        if edited.returncode != 0:
            return _publish_hold(
                result_plan,
                _gh_failure("gh issue edit", edited),
                action="partial_publish_issue",
                issue_url=issue_url,
                issue_number=issue_number,
            )

    return {
        **result_plan,
        "action": "publish_issue",
        "remote_write_authorized": True,
        "issue_url": issue_url,
        "issue_number": issue_number,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path, help="Issue contract JSON snapshot")
    parser.add_argument(
        "--pretty", action="store_true", help="Pretty-print JSON output"
    )
    parser.add_argument(
        "--publish",
        action="store_true",
        help="Create the GitHub Issue after the contract passes",
    )
    parser.add_argument(
        "--repo", help="GitHub repository in OWNER/REPO form, required with --publish"
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
        result = publish_issue(snapshot, args.repo or "") if args.publish else plan(snapshot)
    except (OSError, json.JSONDecodeError) as error:
        result = {
            "status": "hold",
            "action": "report_hold",
            "requested_action": "unknown",
            "risk_level": "unknown",
            "remote_write_authorized": False,
            "publish_ready": False,
            "contract_id": contract_id({"invalid_snapshot": str(error)}),
            "errors": [f"snapshot could not be read as JSON: {error}"],
        }
    print(
        json.dumps(
            result,
            ensure_ascii=False,
            indent=2 if args.pretty else None,
            sort_keys=True,
        )
    )
    return 0 if result["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
