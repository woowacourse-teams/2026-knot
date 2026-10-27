"""팀원 명단으로 이슈와 PR의 담당자, 이름 라벨, 리뷰어, 승인 조건을 정하는 결정적 도구.

knot-feature 흐름이 이슈와 PR을 만들 때 AI가 담당자나 리뷰어를 추측하지 않도록 이 결과를 쓴다.
명단은 `harness/team.json`이고, 규칙의 정본은 `.agents/skills/knot-feature/SKILL.md`의
「역할과 권한」이다.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

TEAM_FILE = Path(__file__).resolve().parent / "team.json"
AREAS = ("frontend", "backend")
AREA_LABELS = {"frontend": "FE", "backend": "BE"}
AREA_NAMES = {"frontend": "FE 팀원", "backend": "BE 팀원"}


def load_team(path: Path = TEAM_FILE) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_team(team: Any) -> list[str]:
    if not isinstance(team, dict):
        return ["team must be an object"]
    errors: list[str] = []
    members = team.get("members")
    if not isinstance(members, list) or not members:
        return ["members must be a non-empty list"]
    logins: list[str] = []
    notion_ids: list[str] = []
    for index, member in enumerate(members):
        prefix = f"members[{index}]"
        if not isinstance(member, dict):
            errors.append(f"{prefix} must be an object")
            continue
        for key in ("github", "name"):
            value = member.get(key)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{prefix}.{key} must be a non-blank string")
        if member.get("area") not in AREAS:
            errors.append(f"{prefix}.area must be one of {', '.join(AREAS)}")
        if isinstance(member.get("github"), str):
            logins.append(member["github"])
        notion = member.get("notion")
        if notion is not None:
            if not isinstance(notion, str) or not notion.strip():
                errors.append(f"{prefix}.notion must be a Notion user ID")
            else:
                notion_ids.append(_normalize_notion_id(notion))
    if len(logins) != len(set(logins)):
        errors.append("members.github must be unique")
    if len(notion_ids) != len(set(notion_ids)):
        errors.append("members.notion must be unique")
    confirmers = team.get("plan_confirmers")
    if not isinstance(confirmers, list) or not confirmers:
        errors.append("plan_confirmers must be a non-empty list")
    else:
        for login in confirmers:
            if login not in logins:
                errors.append(f"plan_confirmers has unknown member: {login}")
            elif not any(
                member.get("github") == login and member.get("notion") for member in members
            ):
                # 1차 컨펌은 제안 페이지 `컨펌한 사람`(노션 사용자)으로 확인하므로 노션 ID가 있어야 한다.
                errors.append(f"plan_confirmers needs a notion user ID: {login}")
    if team.get("release_merger") not in logins:
        errors.append("release_merger must be a member")
    return errors


def _normalize_notion_id(value: str) -> str:
    """`user://`나 하이픈 유무와 관계없이 같은 노션 사용자 ID로 비교한다."""
    return value.strip().removeprefix("user://").replace("-", "").lower()


def find_member(team: dict[str, Any], login: str) -> dict[str, Any]:
    for member in team["members"]:
        if member["github"].lower() == login.lower():
            return member
    raise ValueError(f"harness/team.json에 없는 GitHub 계정입니다: {login}")


def proposal_people(team: dict[str, Any], author: str) -> dict[str, Any]:
    """제안 페이지의 `제안자`와 `검토자`에 넣을 사람을 정한다.

    검토자는 기획 컨펌 담당이다. 노션은 사람 속성에 추가된 사람에게 알림을 보내므로 이 칸이 1차 컨펌
    요청 알림이 된다. 제안자의 노션 ID가 명단에 없으면 `notion`이 None이고, 입구는 Notion MCP에
    로그인한 요청자 계정(`self`)으로 채운다.
    """
    author_member = describe_member(team, author)
    return {
        "proposer": {**author_member, "notion": author_member.get("notion")},
        "reviewers": [describe_member(team, login) for login in team["plan_confirmers"]],
    }


def proposal_assignees(team: dict[str, Any], logins: list[str]) -> dict[str, Any]:
    """확정한 제안 페이지의 `담당자`에 넣을 사람을 정한다.

    담당자는 제안의 「구현할 사람」이다. 노션은 사람 속성에 추가된 사람에게 알림을 보내므로 확정할 때
    이 칸을 채우는 것이 담당자에게 가는 컨펌 알림이다. 노션 ID가 명단에 없는 사람은 `missing`으로
    돌려주고, 입구는 그 사람을 담당자 칸 대신 확정 댓글 본문에 이름으로 적는다.
    """
    if not logins:
        raise ValueError("담당자로 넣을 GitHub 계정이 없습니다. 제안의 「구현할 사람」을 확인하세요")
    assignees: list[dict[str, Any]] = []
    missing: list[dict[str, Any]] = []
    for login in dict.fromkeys(logins):
        member = describe_member(team, login)
        (assignees if member.get("notion") else missing).append(member)
    return {"assignees": assignees, "missing": missing}


def check_confirmation(team: dict[str, Any], notion_users: list[str]) -> dict[str, Any]:
    """제안 페이지 `컨펌한 사람`(노션 사용자 ID)이 기획 컨펌 담당인지 확인한다.

    디스코드나 구두 컨펌은 근거로 쓰지 않는다. 제안 페이지의 `상태`가 확정이고 `컨펌한 사람`에
    기획 컨펌 담당이 있어야 1차 컨펌으로 본다. 돌려주는 `confirmed_by`는 GitHub 계정이라
    상태 파일 `plan_confirmation.confirmed_by`에 그대로 쓴다.
    """
    if not notion_users:
        raise ValueError("제안 페이지의 컨펌한 사람이 비어 있습니다. 기획 컨펌 담당이 「이 제안 확정해 줘」로 확정했는지 확인하세요")
    wanted = {_normalize_notion_id(user) for user in notion_users}
    confirmed_by = [
        member["github"]
        for member in team["members"]
        if member["github"] in team["plan_confirmers"]
        and member.get("notion")
        and _normalize_notion_id(member["notion"]) in wanted
    ]
    if not confirmed_by:
        names = ", ".join(describe_member(team, login)["name"] for login in team["plan_confirmers"])
        raise ValueError(f"컨펌한 사람이 기획 컨펌 담당({names})이 아닙니다")
    return {"confirmed_by": confirmed_by}


def describe_member(team: dict[str, Any], login: str) -> dict[str, Any]:
    member = find_member(team, login)
    described = {
        "github": member["github"],
        "name": member["name"],
        "label": member["name"],
        "area": member["area"],
        "area_label": AREA_LABELS[member["area"]],
    }
    if member.get("notion"):
        described["notion"] = member["notion"]
    return described


def review_plan(
    team: dict[str, Any],
    area: str,
    author: str,
    plan_changed: bool = False,
    design_changed: bool = False,
) -> dict[str, Any]:
    """PR 하나의 담당자, 라벨, 리뷰어, 승인 조건, 머지할 사람을 정한다.

    - 담당자와 이름 라벨은 PR을 연 사람(실제 구현한 사람)이다.
    - 같은 영역 팀원 한 명 이상이 승인해야 한다. 다른 영역 팀원의 승인은 세지 않는다.
    - 기획이나 디자인이 바뀌었으면 기획 컨펌 담당(유월)의 승인이 더 필요하다(2차 컨펌).
    - 머지는 PR을 연 사람이 한다.
    """
    if area not in AREAS:
        raise ValueError(f"area must be one of {', '.join(AREAS)}")
    owner = find_member(team, author)
    area_members = [
        member["github"]
        for member in team["members"]
        if member["area"] == area and member["github"] != owner["github"]
    ]
    if not area_members:
        raise ValueError(f"{AREA_NAMES[area]} 중 승인할 수 있는 사람이 없습니다")
    approvals: list[dict[str, Any]] = [
        {"rule": f"{AREA_NAMES[area]} 승인", "any_of": area_members, "count": 1}
    ]
    reviewers = list(area_members)
    notes: list[str] = []
    if plan_changed or design_changed:
        confirmers = [login for login in team["plan_confirmers"] if login != owner["github"]]
        if confirmers:
            approvals.append({"rule": "기획 컨펌 담당의 2차 컨펌", "all_of": confirmers})
            reviewers.extend(login for login in confirmers if login not in reviewers)
        else:
            notes.append("PR을 연 사람이 기획 컨펌 담당이라 2차 컨펌은 1차 컨펌으로 갈음한다.")
    notes.append(
        f"승인 조건을 채우면 PR을 연 {owner['github']}가 직접 머지한다. AI는 머지하지 않는다."
    )
    return {
        "assignee": owner["github"],
        "labels": [AREA_LABELS[area], owner["name"]],
        "reviewers": reviewers,
        "approvals": approvals,
        "merge_by": owner["github"],
        "notes": notes,
    }


def _print(payload: dict[str, Any], pretty: bool) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2 if pretty else None))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Decide assignee, name label, reviewers and approvals from harness/team.json."
    )
    parser.add_argument("--team", default=str(TEAM_FILE), help="팀원 명단 JSON 경로")
    sub = parser.add_subparsers(dest="command", required=True)
    check_parser = sub.add_parser("check", help="팀원 명단 검증")
    check_parser.add_argument("--pretty", action="store_true")
    member_parser = sub.add_parser("member", help="GitHub 계정의 이름 라벨과 영역")
    member_parser.add_argument("login")
    member_parser.add_argument("--pretty", action="store_true")
    confirmers_parser = sub.add_parser("confirmers", help="1차 컨펌을 받을 사람")
    confirmers_parser.add_argument("--pretty", action="store_true")
    people_parser = sub.add_parser("proposal-people", help="제안 페이지의 제안자와 검토자")
    people_parser.add_argument("--author", required=True, help="요청자의 GitHub 계정")
    people_parser.add_argument("--pretty", action="store_true")
    assignees_parser = sub.add_parser("proposal-assignees", help="확정한 제안 페이지의 담당자")
    assignees_parser.add_argument(
        "--implementer", nargs="+", required=True, help="제안의 「구현할 사람」 GitHub 계정"
    )
    assignees_parser.add_argument("--pretty", action="store_true")
    confirmation_parser = sub.add_parser(
        "confirmation", help="제안 페이지 컨펌한 사람이 기획 컨펌 담당인지 확인"
    )
    confirmation_parser.add_argument(
        "--notion", nargs="*", default=[], help="제안 페이지 `컨펌한 사람`의 노션 사용자 ID"
    )
    confirmation_parser.add_argument("--pretty", action="store_true")
    review_parser = sub.add_parser("review", help="PR 담당자, 라벨, 리뷰어, 승인 조건")
    review_parser.add_argument("--area", required=True, choices=AREAS)
    review_parser.add_argument("--author", required=True, help="PR을 여는 사람의 GitHub 계정")
    review_parser.add_argument(
        "--plan-changed", action="store_true", help="1차 컨펌을 받은 기획 변경이다"
    )
    review_parser.add_argument(
        "--design-changed", action="store_true", help="Figma 디자인이 새로 생기거나 바뀌었다"
    )
    review_parser.add_argument("--pretty", action="store_true")
    args = parser.parse_args(argv)

    team = load_team(Path(args.team))
    errors = validate_team(team)
    if args.command == "check" or errors:
        _print({"status": "pass" if not errors else "fail", "errors": errors}, args.pretty)
        return 0 if not errors else 1
    try:
        if args.command == "member":
            payload = describe_member(team, args.login)
        elif args.command == "confirmers":
            payload = {
                "plan_confirmers": [describe_member(team, login) for login in team["plan_confirmers"]]
            }
        elif args.command == "proposal-people":
            payload = proposal_people(team, args.author)
        elif args.command == "proposal-assignees":
            payload = proposal_assignees(team, args.implementer)
        elif args.command == "confirmation":
            payload = check_confirmation(team, args.notion)
        else:
            payload = review_plan(
                team, args.area, args.author, args.plan_changed, args.design_changed
            )
    except ValueError as error:
        _print({"status": "fail", "errors": [str(error)]}, args.pretty)
        return 1
    _print({"status": "pass", **payload}, args.pretty)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
