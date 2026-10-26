#!/usr/bin/env python3
"""Write the recent feedback summary at the top of the Notion feedback inbox description (ADR 993).

`.github/workflows/feedback-report.yml`이 매일 09:00(KST)에 부른다.
최근 14일 접수를 읽어 건수는 이 스크립트가 세고, 해석과 문장은 도구를 끈 `claude -p`가 쓴다.
요약은 설명란 구분선 위에만 쓰고, 구분선 아래의 작성 안내는 서식째 그대로 둔다.
지난 갱신 뒤 새 접수가 없으면 Claude를 부르지 않고 끝낸다.

환경 변수: NOTION_TOKEN(접수함에 연결한 읽기, 업데이트 연동), CLAUDE_CODE_OAUTH_TOKEN(claude setup-token),
DRY_RUN=1이면 노션에 쓰지 않는다. 로컬에서만 요약을 출력하고, Actions(공개 저장소)에서는 건수와 검사 결과만 남긴다.
종료 코드: 0 갱신 또는 건너뜀, 1 노션이나 Claude 호출 실패, 요약 검사 실패.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


DATABASE_ID = "98224ba95fff4abe81a4a9ee67591523"
NOTION_API_URL = "https://api.notion.com/v1"
# 프론트 Worker와 새 소식 스크립트와 같은 판을 쓴다. 이 판은 DB ID로 조회하고 설명란을 바꾼다
NOTION_VERSION = "2022-06-28"
NOTION_TEXT_LIMIT = 2000
PROMPT_FILE = Path(__file__).with_name("feedback_report_prompt.md")

KST = timezone(timedelta(hours=9))
REPORT_DAYS = 7
HEADER_PREFIX = "최근 7일 피드백 요약"
HEADER_TIME_PATTERN = re.compile(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}) KST 갱신")
DIVIDER_LINE = "━" * 16
DIVIDER = f"\n{DIVIDER_LINE}\n"
SUMMARY_LIMIT = 3000
SECTIONS = (
    "팀 우선순위로 가져갈 것",
    "크리티컬 문제",
    "데이터에서 보이는 인사이트",
    "많은 사용자가 불편해하는 것",
)
# shortcut: 긴 글은 앞부분만 넘긴다, 요약이 잘린 내용을 놓치면 한도를 올린다
FIELD_LIMIT = 600


def read_property(prop: dict[str, Any] | None) -> Any:
    if not prop:
        return None
    kind = prop.get("type")
    value = prop.get(kind)
    if kind in ("title", "rich_text"):
        return "".join(part.get("plain_text", "") for part in value or [])
    if kind in ("select", "status"):
        return value["name"] if value else None
    if kind == "multi_select":
        return ", ".join(option["name"] for option in value or [])
    if kind == "unique_id":
        return value["number"] if value else None
    if kind == "formula":
        return value.get(value.get("type")) if value else None
    if kind == "relation":
        return [item["id"] for item in value or []]
    return value


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def parse_row(page: dict[str, Any]) -> dict[str, Any]:
    properties = page.get("properties", {})
    text = lambda name: (read_property(properties.get(name)) or "")[:FIELD_LIMIT]
    request_count = read_property(properties.get("요청 수"))
    representative = read_property(properties.get("대표 피드백")) or []
    return {
        "id": page["id"],
        "created": parse_time(page["created_time"]),
        "number": read_property(properties.get("접수 번호")),
        "kind": read_property(properties.get("종류")),
        "area": read_property(properties.get("영역")),
        "status": read_property(properties.get("접수 상태")),
        "source": read_property(properties.get("출처 유형")),
        "request_count": int(request_count) if isinstance(request_count, (int, float)) else 1,
        "representative": representative[0] if representative else None,
        "title": text("피드백"),
        "content": text("내용"),
        "situation": text("사용 상황"),
        "wish": text("바라는 결과"),
    }


def count_by(rows: list[dict[str, Any]], key: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for row in rows:
        name = row[key] or "(비어 있음)"
        counts[name] = counts.get(name, 0) + 1
    return counts


def build_report(rows: list[dict[str, Any]], now: datetime) -> dict[str, Any]:
    """최근 7일 접수를 Claude에게 넘길 데이터로 만든다. 건수는 직전 7일과 함께 접수 행 단위로 센다.

    「대표 피드백」으로 묶인 행은 대표 행이 같은 기간에 있으면 대표 행의 `same`으로 접고,
    대표 행의 요청 수가 그 행들을 이미 센다. 대표 행이 기간 밖이면 따로 둔다.
    """
    week_start = now - timedelta(days=REPORT_DAYS)
    this_week = [row for row in rows if row["created"] >= week_start]
    last_week = [row for row in rows if row["created"] < week_start]
    ids = {row["id"] for row in this_week}
    same: dict[str, list[int]] = {}
    for row in this_week:
        if row["representative"] in ids:
            same.setdefault(row["representative"], []).append(row["number"])

    items = [
        {
            "number": row["number"],
            "kind": row["kind"],
            "area": row["area"],
            "status": row["status"],
            "source": row["source"],
            "request_count": row["request_count"],
            "same": sorted(number for number in same.get(row["id"], []) if number is not None),
            "title": row["title"],
            "content": row["content"],
            "situation": row["situation"],
            "wish": row["wish"],
        }
        for row in sorted(this_week, key=lambda row: row["number"] or 0)
        if row["representative"] not in ids
    ]
    top = sorted(
        (item for item in items if item["request_count"] > 1),
        key=lambda item: -item["request_count"],
    )

    return {
        "period": {
            "from": week_start.astimezone(KST).date().isoformat(),
            "to": now.astimezone(KST).date().isoformat(),
        },
        "counts": {
            "this_week": len(this_week),
            "last_week": len(last_week),
            "distinct_this_week": len(items),
            "new_status_this_week": sum(row["status"] == "신규" for row in this_week),
            "kind": {"this_week": count_by(this_week, "kind"), "last_week": count_by(last_week, "kind")},
            "area": {"this_week": count_by(this_week, "area"), "last_week": count_by(last_week, "area")},
            "top_request_numbers": [item["number"] for item in top[:5]],
        },
        "items": items,
    }


def count_new(rows: list[dict[str, Any]], now: datetime, last_updated: datetime | None) -> int:
    since = now - timedelta(days=REPORT_DAYS)
    if last_updated is not None:
        # 노션 created_time은 분 단위라 갱신 시각과 같은 분의 접수도 새 접수로 센다
        since = max(since, last_updated)
    return sum(row["created"] >= since for row in rows)


def to_request(segment: dict[str, Any]) -> dict[str, Any]:
    kind = segment["type"]
    request = {"type": kind, kind: segment[kind]}
    if segment.get("annotations"):
        request["annotations"] = segment["annotations"]
    return request


def split_description(segments: list[dict[str, Any]]) -> tuple[str | None, list[dict[str, Any]]]:
    """설명란을 구분선 위 글과 구분선 아래 조각(서식 유지)으로 나눈다. 구분선이 없으면 위 글은 None이다."""
    above = ""
    for index, segment in enumerate(segments):
        text = segment.get("plain_text", "")
        at = text.find(DIVIDER_LINE)
        if at == -1:
            above += text
            continue
        above += text[:at]
        rest = text[at + len(DIVIDER_LINE):].lstrip("\n")
        tail = []
        if rest and segment["type"] == "text":
            tail.append(to_request({**segment, "text": {**segment["text"], "content": rest}}))
        return above, tail + [to_request(item) for item in segments[index + 1:]]
    return None, [to_request(item) for item in segments]


def read_last_updated(above: str | None) -> datetime | None:
    match = HEADER_TIME_PATTERN.search(above or "")
    if not match:
        return None
    return datetime.strptime(match.group(1), "%Y-%m-%d %H:%M").replace(tzinfo=KST)


def split_text(text: str, limit: int = NOTION_TEXT_LIMIT) -> list[str]:
    """노션 text 한 덩어리 한도에 맞춰 나눈다. 이모지처럼 UTF-16 두 칸인 글자를 두 칸으로 센다."""
    chunks, current, size = [], "", 0
    for char in text:
        width = 2 if ord(char) > 0xFFFF else 1
        if size + width > limit:
            chunks.append(current)
            current, size = "", 0
        current += char
        size += width
    return chunks + [current] if current else chunks


def text_segment(content: str) -> dict[str, Any]:
    return {"type": "text", "text": {"content": content}}


def build_description(summary: str, below: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [text_segment(chunk) for chunk in split_text(summary)] + [text_segment(DIVIDER)] + below


def render_header(report: dict[str, Any], now: datetime) -> str:
    counts = report["counts"]
    return (
        f"{HEADER_PREFIX} ({now.astimezone(KST):%Y-%m-%d %H:%M} KST 갱신, "
        f"{report['period']['from']}~{report['period']['to']} 접수 {counts['this_week']}건, "
        f"직전 7일 {counts['last_week']}건)"
    )


def check_summary(summary: str, report: dict[str, Any]) -> list[str]:
    errors = []
    if not summary.strip():
        return ["요약이 비어 있어요."]
    if len(summary) > SUMMARY_LIMIT:
        errors.append(f"요약이 {len(summary)}자로 {SUMMARY_LIMIT}자를 넘어요.")
    errors += [f"「{section}」 절이 없어요." for section in SECTIONS if section not in summary]
    if DIVIDER_LINE[0] in summary:
        errors.append("요약에 설명란 구분선 문자가 있어요.")
    known = {item["number"] for item in report["items"]}
    known.update(number for item in report["items"] for number in item["same"])
    unknown = sorted({int(number) for number in re.findall(r"#(\d+)", summary)} - known)
    if unknown:
        errors.append("입력에 없는 접수 번호를 썼어요: " + ", ".join(f"#{number}" for number in unknown))
    return errors


class ReportError(Exception):
    pass


def notion(token: str, method: str, path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
    request = urllib.request.Request(
        NOTION_API_URL + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {token}",
            "Notion-Version": NOTION_VERSION,
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try:
            code = json.loads(error.read()).get("code", "")
        except ValueError:
            code = ""
        raise ReportError(f"노션 {method} {path} 호출이 {error.code} {code}로 실패했어요.") from error
    except urllib.error.URLError as error:
        raise ReportError(f"노션 {method} {path}에 연결하지 못했어요: {error.reason}") from error


def query_rows(token: str, since: datetime) -> list[dict[str, Any]]:
    body: dict[str, Any] = {
        "filter": {"timestamp": "created_time", "created_time": {"on_or_after": since.isoformat()}},
        "page_size": 100,
    }
    pages = []
    while True:
        result = notion(token, "POST", f"/databases/{DATABASE_ID}/query", body)
        pages += result.get("results", [])
        if not result.get("has_more"):
            return pages
        body["start_cursor"] = result["next_cursor"]


def ask_claude(report: dict[str, Any]) -> str:
    prompt = (
        PROMPT_FILE.read_text(encoding="utf-8")
        + "\n\n## 피드백 데이터\n\n```json\n"
        + json.dumps(report, ensure_ascii=False, indent=1)
        + "\n```\n"
    )
    # 노션 토큰은 넘기지 않는다. 접수함은 누구나 쓰는 곳이라 Claude에게 도구도 주지 않는다
    env = {key: os.environ[key] for key in ("PATH", "HOME", "USER", "CLAUDE_CODE_OAUTH_TOKEN") if key in os.environ}
    command = [
        "claude", "-p",
        "--tools", "",
        "--strict-mcp-config",
        "--setting-sources", "",
        "--no-session-persistence",
        "--output-format", "text",
    ]
    # 저장소 밖에서 실행해 저장소의 CLAUDE.md와 설정을 읽지 않게 한다
    with tempfile.TemporaryDirectory() as cwd:
        try:
            completed = subprocess.run(
                command, input=prompt, capture_output=True, text=True, env=env, cwd=cwd, timeout=600
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ReportError(f"claude를 실행하지 못했어요: {error}") from error
    if completed.returncode != 0:
        # stdout에는 요약이나 피드백 내용이 있을 수 있어 CLI 오류(stderr)만 남긴다
        raise ReportError(f"claude가 {completed.returncode}로 끝났어요: {completed.stderr.strip()[-500:]}")
    return completed.stdout.strip()


def log(message: str) -> None:
    print(message)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as file:
            file.write(f"- {message}\n")


def main() -> int:
    token = os.environ.get("NOTION_TOKEN")
    if not token:
        log("NOTION_TOKEN이 없어 접수함을 읽지 못했어요.")
        return 1
    dry_run = os.environ.get("DRY_RUN") == "1"
    in_actions = os.environ.get("GITHUB_ACTIONS") == "true"
    now = datetime.now(timezone.utc).replace(second=0, microsecond=0)

    try:
        database = notion(token, "GET", f"/databases/{DATABASE_ID}")
        above, below = split_description(database.get("description", []))
        last_updated = read_last_updated(above)
        rows = [parse_row(page) for page in query_rows(token, now - timedelta(days=REPORT_DAYS * 2))]
        report = build_report(rows, now)
        new_count = count_new(rows, now, last_updated)
        log(
            f"최근 7일 접수 {report['counts']['this_week']}건, 직전 7일 {report['counts']['last_week']}건, "
            f"지난 갱신({'없음' if last_updated is None else f'{last_updated:%Y-%m-%d %H:%M} KST'}) 뒤 새 접수 {new_count}건"
        )
        if new_count == 0:
            log("새 접수가 없어 Claude를 부르지 않고 설명란을 그대로 뒀어요.")
            return 0

        summary = ask_claude(report)
        errors = check_summary(summary, report)
        if errors:
            for error in errors:
                log(f"요약 검사 실패: {error}")
            return 1
        log(f"요약 검사 통과({len(summary)}자)")

        description = f"{render_header(report, now)}\n\n{summary}"
        if dry_run:
            if not in_actions:
                print(f"\n{description}\n")
            log("DRY_RUN이라 노션에 쓰지 않았어요.")
            return 0
        notion(token, "PATCH", f"/databases/{DATABASE_ID}", {"description": build_description(description, below)})
        log("접수함 설명란 맨 위 요약을 바꿨어요.")
        return 0
    except ReportError as error:
        log(str(error))
        return 1


if __name__ == "__main__":
    sys.exit(main())
