"""기능 요구사항 검토 제안끼리, 제안과 정본 사이의 충돌을 가리는 결정적 도구.

같은 요구사항을 프론트와 백엔드가 따로 제안하면 나중에 확정된 쪽이 먼저 반영된 문장을 덮을 수 있다.
그래서 두 가지를 검사한다.

- `check`: 새 제안이 아직 반영 전인 다른 제안과 같은 요구사항 ID를 건드리는지. 먼저 만든 제안이
  기준이고, 나중 제안은 멈춘다.
- `stale`: 확정된 제안을 정본에 옮기기 직전, 제안의 「지금 문장」이 정본의 지금 문장과 같은지. 다르면
  제안 뒤에 다른 사람이 먼저 반영한 것이므로 덮어쓰지 않고 멈춘다.

입력은 입구가 Notion MCP로 읽은 값을 JSON으로 넘긴다. 규칙의 정본은
`.agents/skills/knot-feature/subagents/requirements.md`다.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

# 정본에 아직 반영되지 않아 같은 요구사항을 두고 겹칠 수 있는 상태
OPEN_STATES = ("작성 중", "검토 요청", "보류", "확정")
REQUIREMENT_ID = re.compile(r"[A-Z]+-R\d+")


def requirement_ids(value: Any) -> set[str]:
    """「STT-R38, STT-R42, DOC-R18(후보)」 같은 글이나 목록에서 요구사항 ID를 뽑는다."""
    if isinstance(value, list):
        text = " ".join(str(item) for item in value)
    else:
        text = str(value or "")
    return set(REQUIREMENT_ID.findall(text.upper()))


def _normalize_url(value: str) -> str:
    """노션 URL과 ID 표기가 달라도 같은 페이지로 비교한다."""
    match = re.search(r"[0-9a-f]{32}", value.replace("-", "").lower())
    return match.group(0) if match else value.strip().lower()


def _order_key(proposal: dict[str, Any]) -> tuple[str, str]:
    return (str(proposal.get("created", "")), _normalize_url(str(proposal.get("url", ""))))


def check_conflicts(candidate: dict[str, Any], proposals: list[dict[str, Any]]) -> dict[str, Any]:
    """새 제안과 겹치는 다른 제안을 찾아 멈출지 정한다.

    - 같은 요구사항 ID를 건드리는, 반영 전인 다른 제안이 candidate보다 먼저 만들어졌으면 `conflict`다.
      candidate는 멈추고 먼저 낸 제안이 확정, 반영될 때까지 기다린다.
    - ID는 겹치지 않지만 같은 정본 문서를 건드리면 `review`다. 두 제안을 읽어 같은 기능이면 충돌로 본다.
    - candidate가 먼저 만들어졌으면 겹쳐도 candidate는 그대로 간다. 나중 제안이 멈춘다.
    """
    own_key = _order_key(candidate)
    own_url = _normalize_url(str(candidate.get("url", "")))
    own_ids = requirement_ids(candidate.get("ids"))
    own_documents = {_normalize_url(str(url)) for url in candidate.get("documents") or []}
    if not own_ids and not own_documents:
        raise ValueError("제안이 건드리는 요구사항 ID나 정본 문서가 없습니다. 「대상 요구사항」을 먼저 채우세요")

    blocking: list[dict[str, Any]] = []
    same_document: list[dict[str, Any]] = []
    later: list[dict[str, Any]] = []
    for proposal in proposals:
        if _normalize_url(str(proposal.get("url", ""))) == own_url:
            continue
        if proposal.get("state") not in OPEN_STATES:
            continue
        shared_ids = sorted(own_ids & requirement_ids(proposal.get("ids")))
        shared_documents = own_documents & {
            _normalize_url(str(url)) for url in proposal.get("documents") or []
        }
        if not shared_ids and not shared_documents:
            continue
        summary = {
            "url": proposal.get("url"),
            "title": proposal.get("title"),
            "state": proposal.get("state"),
            "proposer": proposal.get("proposer"),
            "created": proposal.get("created"),
            "shared_ids": shared_ids,
        }
        if _order_key(proposal) > own_key:
            later.append(summary)
        elif shared_ids:
            blocking.append(summary)
        else:
            same_document.append(summary)

    if blocking:
        status = "conflict"
    elif same_document:
        status = "review"
    else:
        status = "clear"
    return {"status": status, "blocking": blocking, "same_document": same_document, "later": later}


def _normalize_sentence(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def check_stale(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """제안의 「지금 문장」이 정본의 지금 문장과 같은지 행마다 비교한다.

    추가하는 행(제안의 지금 문장이 비어 있음)은 같은 ID가 정본에 이미 생겼는지만 본다.
    """
    stale: list[dict[str, Any]] = []
    for row in rows:
        before = _normalize_sentence(str(row.get("proposal_before", "")))
        current = _normalize_sentence(str(row.get("current", "")))
        if before != current:
            stale.append(
                {
                    "id": row.get("id"),
                    "proposal_before": row.get("proposal_before", ""),
                    "current": row.get("current", ""),
                }
            )
    return {"status": "stale" if stale else "fresh", "stale": stale}


def _print(payload: dict[str, Any], pretty: bool) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2 if pretty else None))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Detect conflicts between plan-change proposals.")
    sub = parser.add_subparsers(dest="command", required=True)
    check_parser = sub.add_parser("check", help="반영 전인 다른 제안과 겹치는지")
    check_parser.add_argument("input", help='{"candidate": {...}, "proposals": [...]} JSON 경로, -는 표준 입력')
    check_parser.add_argument("--pretty", action="store_true")
    stale_parser = sub.add_parser("stale", help="제안의 지금 문장이 정본과 같은지")
    stale_parser.add_argument("input", help='{"rows": [{"id", "proposal_before", "current"}]} JSON 경로, -는 표준 입력')
    stale_parser.add_argument("--pretty", action="store_true")
    args = parser.parse_args(argv)

    raw = sys.stdin.read() if args.input == "-" else Path(args.input).read_text(encoding="utf-8")
    try:
        data = json.loads(raw)
        if args.command == "check":
            payload = check_conflicts(data["candidate"], data.get("proposals") or [])
            code = 0 if payload["status"] == "clear" else 2
        else:
            payload = check_stale(data.get("rows") or [])
            code = 0 if payload["status"] == "fresh" else 2
    except (KeyError, TypeError, ValueError) as error:
        _print({"status": "fail", "errors": [str(error)]}, args.pretty)
        return 1
    _print(payload, args.pretty)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
