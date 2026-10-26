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
    if len(logins) != len(set(logins)):
        errors.append("members.github must be unique")
    confirmers = team.get("plan_confirmers")
    if not isinstance(confirmers, list) or not confirmers:
        errors.append("plan_confirmers must be a non-empty list")
    else:
        for login in confirmers:
            if login not in logins:
                errors.append(f"plan_confirmers has unknown member: {login}")
    if team.get("release_merger") not in logins:
        errors.append("release_merger must be a member")
    return errors


def find_member(team: dict[str, Any], login: str) -> dict[str, Any]:
    for member in team["members"]:
        if member["github"].lower() == login.lower():
            return member
    raise ValueError(f"harness/team.json에 없는 GitHub 계정입니다: {login}")


def describe_member(team: dict[str, Any], login: str) -> dict[str, Any]:
    member = find_member(team, login)
    return {
        "github": member["github"],
        "name": member["name"],
        "label": member["name"],
        "area": member["area"],
        "area_label": AREA_LABELS[member["area"]],
    }


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
    review_parser = sub.add_parser("review", help="PR 담당자, 라벨, 리뷰어, 승인 조건")
    review_parser.add_argument("--area", required=True, choices=AREAS)
    review_parser.add_argument("--author", required=True, help="PR을 여는 사람의 GitHub 계정")
    review_parser.add_argument(
        "--plan-changed", action="store_true", help="기능 추가, 수정, 삭제이거나 구현 중 기획을 바꿨다"
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
