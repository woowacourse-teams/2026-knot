#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
# How to run: python3 scripts/tests/test_migration_compatibility.py
"""실제 ZIP과 CLI를 사용해 승인 누락·변조 시 배포 차단을 검증한다."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import zipfile

CHECKER = Path(__file__).resolve().parents[1] / "verify_migration_compatibility.py"


class MigrationCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.active = self.root / "active.jar"
        self.candidate = self.root / "candidate.jar"
        self.reviews = self.root / "reviews"
        self.reviews.mkdir(mode=0o700)
        self.original = {"V1__initial.sql": "CREATE TABLE original(id bigint);"}
        self.added = {**self.original, "V2__expanded.sql": "ALTER TABLE original ADD COLUMN extra text;"}
        self.write_jar(self.active, self.original)
        self.write_jar(self.candidate, self.added)

    def tearDown(self):
        self.temporary.cleanup()

    def write_jar(self, path, files):
        with zipfile.ZipFile(path, "w") as archive:
            for name, content in files.items():
                archive.writestr("BOOT-INF/classes/db/migration/" + name, content)

    def approve(self, **overrides):
        active_sha = hashlib.sha256(self.active.read_bytes()).hexdigest()
        candidate_sha = hashlib.sha256(self.candidate.read_bytes()).hexdigest()
        review = self.reviews / f"dev-{active_sha}-{candidate_sha}.json"
        payload = {
            "schema": "knot.migration-review.v1", "environment": "dev",
            "active_jar_sha256": active_sha, "candidate_jar_sha256": candidate_sha,
            "expires_at_epoch": int(time.time()) + 3600,
            "backward_compatible": True, "runtime_preconditions_verified": True,
            "evidence": "PostgreSQL legacy writer compatibility proof",
        }
        payload.update(overrides)
        review.write_text(json.dumps(payload))
        review.chmod(0o600)
        return review

    def run_checker(self, environment="dev"):
        return subprocess.run([
            "python3", str(CHECKER), "--active", str(self.active),
            "--candidate", str(self.candidate), "--environment", environment,
            "--reviews", str(self.reviews),
        ], capture_output=True, text=True, check=False)

    def test_identical_migrations_need_no_review(self):
        self.write_jar(self.candidate, self.original)
        self.assertEqual(self.run_checker().returncode, 0)

    def test_new_migration_without_review_is_rejected(self):
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_reviewed_exact_pair_passes(self):
        self.approve()
        self.assertEqual(self.run_checker().returncode, 0)

    def test_serial_workers_requires_explicit_review_mode(self):
        self.approve(execution_mode="serial-workers")
        result = self.run_checker()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "serial-workers")

    def test_prod_reviewed_rolling_pair_passes(self):
        review = self.approve(environment="prod", execution_mode="rolling")
        review.rename(review.with_name(review.name.replace("dev-", "prod-", 1)))
        result = self.run_checker(environment="prod")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "rolling")

    def test_prod_serial_workers_review_is_rejected(self):
        review = self.approve(environment="prod", execution_mode="serial-workers")
        review.rename(review.with_name(review.name.replace("dev-", "prod-", 1)))
        result = self.run_checker(environment="prod")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unauthorized migration execution mode", result.stderr)

    def test_review_beyond_twenty_four_hours_is_rejected(self):
        self.approve(expires_at_epoch=int(time.time()) + 90000)
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_sql_changed_under_same_filename_is_rejected(self):
        self.write_jar(self.candidate, {"V1__initial.sql": "DROP TABLE original;"})
        self.approve()
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_removed_migration_is_rejected_even_with_review(self):
        self.write_jar(self.active, self.added)
        self.write_jar(self.candidate, self.original)
        self.approve()
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_expired_review_is_rejected(self):
        self.approve(expires_at_epoch=int(time.time()) - 1)
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_other_environment_review_is_rejected(self):
        self.approve(environment="prod")
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_another_candidate_invalidates_review(self):
        self.approve()
        self.write_jar(self.candidate, {**self.added, "V3__unreviewed.sql": "DROP TABLE original;"})
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_writable_review_is_rejected(self):
        self.approve().chmod(0o666)
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_review_symlink_is_rejected(self):
        review = self.approve()
        moved = review.with_suffix(".real")
        review.rename(moved)
        review.symlink_to(moved)
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_unverified_runtime_is_rejected(self):
        self.approve(runtime_preconditions_verified=False)
        self.assertNotEqual(self.run_checker().returncode, 0)

    def test_backfilled_migration_is_rejected(self):
        self.write_jar(self.active, {"V2__initial.sql": "SELECT 1;"})
        self.write_jar(self.candidate, {"V2__initial.sql": "SELECT 1;", "V1__backfill.sql": "SELECT 2;"})
        self.approve()
        self.assertNotEqual(self.run_checker().returncode, 0)


if __name__ == "__main__":
    unittest.main()
