#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
# How to run: python3 scripts/tests/check_postgres_migration_compatibility.py
"""격리한 PostgreSQL 18에서 V61 쓰기를 V66/V69 적용 전후에 실행한다."""

from __future__ import annotations

import subprocess
import uuid
from pathlib import Path


def main() -> None:
    backend = Path(__file__).resolve().parents[2]
    migrations = sorted(
        (backend / "src/main/resources/db/migration").glob("V*.sql"),
        key=lambda path: int(path.name.split("__", 1)[0][1:]),
    )
    old = "\n".join(path.read_text() for path in migrations if int(path.name.split("__")[0][1:]) <= 61)
    writer = Path(__file__).with_name("legacy_migration_writer.sql").read_text()
    container = "knot-migration-proof-" + uuid.uuid4().hex[:10]
    subprocess.run([
        "docker", "run", "--detach", "--name", container, "--network", "none",
        "--tmpfs", "/var/lib/postgresql", "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
        "pgvector/pgvector:pg18",
    ], check=True, capture_output=True)
    try:
        subprocess.run([
            "docker", "exec", container, "bash", "-c",
            "for i in {1..50}; do pg_isready -h 127.0.0.1 -U postgres >/dev/null && exit 0; sleep 0.2; done; exit 1",
        ], check=True, capture_output=True)
        for target in (66, 69):
            expanded = "\n".join(path.read_text() for path in migrations
                                  if 61 < int(path.name.split("__")[0][1:]) <= target)
            sql = f"CREATE SCHEMA proof_{target}; SET search_path TO proof_{target},public;\n"
            sql += old + "\n" + writer + "\n" + expanded + "\n" + writer
            result = subprocess.run([
                "docker", "exec", "-i", container, "psql", "-X", "-U", "postgres",
                "-v", "ON_ERROR_STOP=1", "-q",
            ], input=sql, text=True, capture_output=True, check=False)
            if result.returncode:
                raise RuntimeError(result.stderr)
            print(f"PASS V61 -> V{target}: legacy insert/retry/complete/archive before and after migration")
    finally:
        subprocess.run(["docker", "rm", "--force", container], check=True, capture_output=True)


if __name__ == "__main__":
    main()
