"""실제 ZIP 검사기로 읽기 전용 배포 사전 점검을 검증한다."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]


class DeploymentPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.root / "app"
        self.revision = self.root / "revision"
        self.revision.mkdir()
        self.config = self.root / "nginx.conf"
        self.config.write_text("proxy_pass http://127.0.0.1:8081;\n")
        self.active = self.app / "slots/green/app.jar"
        self.write_jar(self.active, {"V1__initial.sql": "SELECT 1;"})
        self.write_jar(self.revision / "app.jar", {"V1__initial.sql": "SELECT 1;"})
        fake = self.root / "bin"
        fake.mkdir()
        systemctl = fake / "systemctl"
        systemctl.write_text(
            '#!/bin/sh\n'
            '[ "$1" = is-active ] && [ "$2" = --quiet ] || exit 99\n'
            '[ "$3" = "$TEST_ACTIVE_SERVICE" ]\n'
        )
        systemctl.chmod(0o755)
        self.environment = {
            **os.environ,
            "PATH": str(fake) + os.pathsep + os.environ["PATH"],
            "TEST_ACTIVE_SERVICE": "knot-backend-green.service",
            "KNOT_PREFLIGHT_APP_ROOT": str(self.app),
            "KNOT_PREFLIGHT_NGINX_CONFIG": str(self.config),
            "KNOT_PREFLIGHT_REVIEWS": str(self.root / "absent-reviews"),
            "KNOT_PREFLIGHT_CHECKER": str(SCRIPTS / "verify_migration_compatibility.py"),
        }

    def write_jar(self, target, sql):
        target.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(target, "w") as archive:
            for name, content in sql.items():
                archive.writestr("BOOT-INF/classes/db/migration/" + name, content)

    def run_preflight(self):
        return subprocess.run(
            ["bash", str(SCRIPTS / "deployment_preflight.sh"), "prod", str(self.revision)],
            env=self.environment, capture_output=True, text=True,
        )

    def test_same_sql_passes_without_review_and_does_not_create_one(self):
        with zipfile.ZipFile(self.revision / "app.jar", "a") as archive:
            archive.writestr("BOOT-INF/classes/build-info.properties", "build=new-code")
        self.assertNotEqual(self.active.read_bytes(), (self.revision / "app.jar").read_bytes())
        result = self.run_preflight()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("rolling", result.stdout)
        self.assertIn("knot-backend-green.service", result.stdout)
        self.assertFalse((self.root / "absent-reviews").exists())

    def test_new_sql_requires_review_and_is_not_auto_approved(self):
        self.write_jar(self.revision / "app.jar", {
            "V1__initial.sql": "SELECT 1;", "V2__new.sql": "SELECT 2;",
        })
        result = self.run_preflight()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("migration-safety", result.stderr)
        self.assertFalse((self.root / "absent-reviews").exists())

    def test_same_filename_modified_sql_is_rejected(self):
        self.write_jar(self.revision / "app.jar", {"V1__initial.sql": "DROP TABLE original;"})
        self.assertNotEqual(self.run_preflight().returncode, 0)

    def test_changed_candidate_checker_requires_operator_tool_review(self):
        scripts = self.revision / "scripts"
        scripts.mkdir()
        (scripts / "verify_migration_compatibility.py").write_text("raise RuntimeError('untrusted')")
        result = self.run_preflight()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Candidate checker differs", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_inactive_upstream_is_rejected(self):
        self.environment["TEST_ACTIVE_SERVICE"] = "knot-backend-blue.service"
        self.assertNotEqual(self.run_preflight().returncode, 0)

    def test_ambiguous_routes_are_rejected(self):
        self.config.write_text(self.config.read_text() + "proxy_pass http://127.0.0.1:8080;\n")
        self.assertNotEqual(self.run_preflight().returncode, 0)

    def test_legacy_slot_is_detected(self):
        self.config.write_text("proxy_pass http://127.0.0.1:8080;\n")
        self.environment["TEST_ACTIVE_SERVICE"] = "knot-backend.service"
        self.write_jar(self.app / "app.jar", {"V1__initial.sql": "SELECT 1;"})
        result = self.run_preflight()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("active_service=knot-backend.service", result.stdout)


if __name__ == "__main__":
    unittest.main()
