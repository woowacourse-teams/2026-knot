import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness"))

import proposal_conflicts as pc  # noqa: E402

RECORDING_DOC = "https://app.notion.com/p/404b435175228141a5b5e8ccaff7bb86"
DOCUMENT_LIST_DOC = "https://app.notion.com/p/3c2b4351752280009122c2527b713db7"


def proposal(url, created, ids, documents=(RECORDING_DOC,), state="검토 요청"):
    return {
        "url": f"https://app.notion.com/p/{url}",
        "title": url,
        "state": state,
        "proposer": "poketopa",
        "created": created,
        "ids": ids,
        "documents": list(documents),
    }


FRONT = proposal("a" * 32, "2026-10-26T07:00:00Z", "STT-R38, STT-R50(후보)")


class RequirementIdsTest(unittest.TestCase):
    def test_reads_ids_from_text_and_lists(self):
        self.assertEqual(pc.requirement_ids("STT-R38, dok-r1, DOC-R18(후보)"), {"STT-R38", "DOK-R1", "DOC-R18"})
        self.assertEqual(pc.requirement_ids(["WS-R2", "WS-R4"]), {"WS-R2", "WS-R4"})


class CheckConflictsTest(unittest.TestCase):
    def test_earlier_open_proposal_on_same_id_blocks(self):
        back = proposal("b" * 32, "2026-10-26T06:00:00Z", "STT-R38")
        result = pc.check_conflicts(FRONT, [back])
        self.assertEqual(result["status"], "conflict")
        self.assertEqual(result["blocking"][0]["shared_ids"], ["STT-R38"])

    def test_same_candidate_id_blocks_too(self):
        # 둘 다 새 요구사항 후보 번호를 같은 번호로 잡으면 같은 자리에 추가하려는 것이다.
        back = proposal("b" * 32, "2026-10-26T06:00:00Z", "STT-R50(후보)")
        self.assertEqual(pc.check_conflicts(FRONT, [back])["status"], "conflict")

    def test_later_proposal_does_not_stop_the_earlier_one(self):
        back = proposal("b" * 32, "2026-10-26T08:00:00Z", "STT-R38")
        result = pc.check_conflicts(FRONT, [back])
        self.assertEqual(result["status"], "clear")
        self.assertEqual(len(result["later"]), 1)

    def test_reflected_or_rejected_proposals_are_ignored(self):
        # 반영 완료는 정본에 있으므로 정본을 바탕으로 하고, 반려는 정본을 건드리지 않았다.
        done = proposal("b" * 32, "2026-10-26T06:00:00Z", "STT-R38", state="반영 완료")
        rejected = proposal("c" * 32, "2026-10-26T06:00:00Z", "STT-R38", state="반려")
        self.assertEqual(pc.check_conflicts(FRONT, [done, rejected])["status"], "clear")

    def test_confirmed_but_not_reflected_still_blocks(self):
        back = proposal("b" * 32, "2026-10-26T06:00:00Z", "STT-R38", state="확정")
        self.assertEqual(pc.check_conflicts(FRONT, [back])["status"], "conflict")

    def test_same_document_without_shared_id_needs_review(self):
        back = proposal("b" * 32, "2026-10-26T06:00:00Z", "STT-R41")
        result = pc.check_conflicts(FRONT, [back])
        self.assertEqual(result["status"], "review")

    def test_other_document_and_ids_are_clear(self):
        other = proposal("b" * 32, "2026-10-26T06:00:00Z", "DOC-R12", documents=(DOCUMENT_LIST_DOC,))
        self.assertEqual(pc.check_conflicts(FRONT, [other])["status"], "clear")

    def test_candidate_itself_is_skipped(self):
        same = dict(FRONT, url=FRONT["url"].replace("https://app.notion.com/p/", ""))
        self.assertEqual(pc.check_conflicts(FRONT, [same])["status"], "clear")

    def test_candidate_needs_targets(self):
        with self.assertRaises(ValueError):
            pc.check_conflicts(dict(FRONT, ids="", documents=[]), [])


class CheckStaleTest(unittest.TestCase):
    def test_same_sentence_is_fresh(self):
        rows = [{"id": "WS-R2", "proposal_before": "이름은  한글, 영어만", "current": "이름은 한글, 영어만"}]
        self.assertEqual(pc.check_stale(rows)["status"], "fresh")

    def test_changed_sentence_is_stale(self):
        rows = [{"id": "STT-R38", "proposal_before": "요약 상태는 셋", "current": "요약 상태는 넷"}]
        result = pc.check_stale(rows)
        self.assertEqual(result["status"], "stale")
        self.assertEqual(result["stale"][0]["id"], "STT-R38")

    def test_added_id_already_in_canon_is_stale(self):
        rows = [{"id": "STT-R50", "proposal_before": "", "current": "STT-R50 녹음 다시 듣기"}]
        self.assertEqual(pc.check_stale(rows)["status"], "stale")


class CliTest(unittest.TestCase):
    def run_cli(self, *args, stdin):
        completed = subprocess.run(
            [sys.executable, str(ROOT / "harness" / "proposal_conflicts.py"), *args],
            input=json.dumps(stdin, ensure_ascii=False),
            capture_output=True,
            text=True,
        )
        return completed.returncode, json.loads(completed.stdout)

    def test_check_exit_codes(self):
        back = proposal("b" * 32, "2026-10-26T06:00:00Z", "STT-R38")
        code, payload = self.run_cli("check", "-", stdin={"candidate": FRONT, "proposals": [back]})
        self.assertEqual((code, payload["status"]), (2, "conflict"))
        code, payload = self.run_cli("check", "-", stdin={"candidate": FRONT, "proposals": []})
        self.assertEqual((code, payload["status"]), (0, "clear"))

    def test_stale_exit_code(self):
        rows = [{"id": "WS-R2", "proposal_before": "a", "current": "b"}]
        code, payload = self.run_cli("stale", "-", stdin={"rows": rows})
        self.assertEqual((code, payload["status"]), (2, "stale"))


if __name__ == "__main__":
    unittest.main()
