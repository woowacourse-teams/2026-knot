#!/usr/bin/env python3

import unittest
from datetime import datetime, timedelta, timezone

from feedback_report import (
    DIVIDER,
    build_description,
    build_report,
    check_summary,
    count_new,
    parse_row,
    read_last_updated,
    render_header,
    split_description,
    split_text,
)


NOW = datetime(2026, 10, 27, 0, 0, tzinfo=timezone.utc)
SUMMARY = (
    "[팀 우선순위로 가져갈 것]\n- 녹음 저장 실패를 먼저 봐요 (#1, #2)\n\n"
    "[크리티컬 문제]\n- 녹음이 저장되지 않아요 (#1)\n\n"
    "[데이터에서 보이는 인사이트]\n- 녹음 영역이 가장 많아요 (#1, #3)\n\n"
    "[많은 사용자가 불편해하는 것]\n- 없음"
)


def page(number, *, days_ago, kind="버그", area="녹음", status="신규", count=1, representative=None):
    properties = {
        "접수 번호": {"type": "unique_id", "unique_id": {"prefix": None, "number": number}},
        "피드백": {"type": "title", "title": [{"plain_text": f"피드백 {number}"}]},
        "종류": {"type": "select", "select": {"name": kind}},
        "영역": {"type": "select", "select": {"name": area}},
        "접수 상태": {"type": "select", "select": {"name": status}},
        "출처 유형": {"type": "select", "select": {"name": "사용자 제보"}},
        "요청 수": {"type": "formula", "formula": {"type": "number", "number": count}},
        "대표 피드백": {"type": "relation", "relation": [{"id": representative}] if representative else []},
        "내용": {"type": "rich_text", "rich_text": [{"plain_text": "가" * 1000}]},
    }
    created = (NOW - timedelta(days=days_ago)).isoformat().replace("+00:00", ".000Z")
    return {"id": f"page-{number}", "created_time": created, "properties": properties}


def text(content, **annotations):
    return {
        "type": "text",
        "text": {"content": content, "link": None},
        "annotations": {"bold": False, **annotations},
        "plain_text": content,
        "href": None,
    }


class BuildReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.rows = [
            parse_row(page(1, days_ago=1, count=3)),
            parse_row(page(2, days_ago=1, status="중복", representative="page-1")),
            parse_row(page(3, days_ago=2, kind="칭찬", area="홈")),
            parse_row(page(4, days_ago=3, status="중복", representative="page-old")),
            parse_row(page(5, days_ago=10, kind="요청")),
        ]
        self.report = build_report(self.rows, NOW)

    def test_folds_rows_into_representative_in_same_week(self) -> None:
        items = {item["number"]: item for item in self.report["items"]}

        self.assertEqual([1, 3, 4], sorted(items))
        self.assertEqual([2], items[1]["same"])
        self.assertEqual([1], self.report["counts"]["top_request_numbers"])

    def test_counts_rows_for_this_and_last_week(self) -> None:
        counts = self.report["counts"]

        self.assertEqual((4, 1, 3), (counts["this_week"], counts["last_week"], counts["distinct_this_week"]))
        self.assertEqual({"버그": 3, "칭찬": 1}, counts["kind"]["this_week"])
        self.assertEqual({"요청": 1}, counts["kind"]["last_week"])
        self.assertEqual(2, counts["new_status_this_week"])

    def test_cuts_long_text(self) -> None:
        self.assertEqual(600, len(self.report["items"][0]["content"]))


class CountNewTest(unittest.TestCase):
    def test_counts_rows_since_last_update(self) -> None:
        rows = [parse_row(page(1, days_ago=2)), parse_row(page(2, days_ago=10))]

        self.assertEqual(1, count_new(rows, NOW, None))
        self.assertEqual(1, count_new(rows, NOW, NOW - timedelta(days=3)))
        self.assertEqual(0, count_new(rows, NOW, NOW - timedelta(days=1)))

    def test_counts_row_in_same_minute_as_last_update(self) -> None:
        self.assertEqual(1, count_new([parse_row(page(1, days_ago=1))], NOW, NOW - timedelta(days=1)))


class DescriptionTest(unittest.TestCase):
    def test_keeps_whole_guide_below_divider_on_first_run(self) -> None:
        guide = [text("작성 안내", bold=True)]

        above, below = split_description(guide)
        description = build_description("요약", below)

        self.assertIsNone(above)
        self.assertEqual(["요약", DIVIDER, "작성 안내"], [item["text"]["content"] for item in description])
        self.assertEqual({"bold": True}, description[-1]["annotations"])
        self.assertNotIn("plain_text", description[-1])

    def test_replaces_only_text_above_divider(self) -> None:
        stored = [text("옛 요약 (2026-10-26 09:00 KST 갱신)"), text(DIVIDER), text("작성 안내", italic=True)]

        above, below = split_description(stored)

        self.assertEqual("옛 요약 (2026-10-26 09:00 KST 갱신)\n", above)
        self.assertEqual(["작성 안내"], [item["text"]["content"] for item in below])
        self.assertEqual({"bold": False, "italic": True}, below[0]["annotations"])

    def test_finds_divider_inside_merged_segment(self) -> None:
        above, below = split_description([text(f"옛 요약{DIVIDER}작성 안내")])

        self.assertEqual("옛 요약\n", above)
        self.assertEqual(["작성 안내"], [item["text"]["content"] for item in below])

    def test_reads_last_update_from_header(self) -> None:
        report = build_report([parse_row(page(1, days_ago=1))], NOW)

        self.assertEqual(NOW, read_last_updated(render_header(report, NOW)))
        self.assertIsNone(read_last_updated("누가 고친 설명"))
        self.assertIsNone(read_last_updated(None))

    def test_splits_text_by_notion_limit(self) -> None:
        chunks = split_text("가" * 4500)

        self.assertEqual([2000, 2000, 500], [len(chunk) for chunk in chunks])
        self.assertEqual(["가", "😀"], split_text("가😀", limit=2))


class CheckSummaryTest(unittest.TestCase):
    def setUp(self) -> None:
        rows = [
            parse_row(page(1, days_ago=1, count=2)),
            parse_row(page(2, days_ago=1, status="중복", representative="page-1")),
            parse_row(page(3, days_ago=1)),
        ]
        self.report = build_report(rows, NOW)

    def test_accepts_summary_with_known_numbers(self) -> None:
        self.assertEqual([], check_summary(SUMMARY, self.report))

    def test_rejects_unknown_number(self) -> None:
        errors = check_summary(SUMMARY.replace("#3", "#99"), self.report)

        self.assertEqual(["입력에 없는 접수 번호를 썼어요: #99"], errors)

    def test_rejects_missing_section_and_divider(self) -> None:
        errors = check_summary(SUMMARY.replace("[크리티컬 문제]", "━"), self.report)

        self.assertEqual(["「크리티컬 문제」 절이 없어요.", "요약에 설명란 구분선 문자가 있어요."], errors)

    def test_rejects_empty_or_long_summary(self) -> None:
        self.assertEqual(["요약이 비어 있어요."], check_summary(" ", self.report))
        self.assertIn("요약이 3001자로 3000자를 넘어요.", check_summary("가" * 3001, self.report))


if __name__ == "__main__":
    unittest.main()
