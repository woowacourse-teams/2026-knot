#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
# How to run: python3 scripts/verify_migration_compatibility.py --help
"""검토하지 않은 DB 변경을 막고 정확한 JAR 쌍의 한시적 승인만 허용한다."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Final, Literal

PREFIX: Final = "BOOT-INF/classes/db/migration/"
NAME: Final = re.compile(r"V[0-9]+(?:_[0-9]+)*__[A-Za-z0-9_]+\.sql")


class MigrationSafetyError(Exception):
    """검사 실패 시 배포 hook에 전달하는 안전 차단 사유."""


@dataclass(frozen=True, slots=True)
class JarMigrations:
    jar_sha256: str
    files: tuple[tuple[str, str], ...]


def read_jar(path: Path) -> JarMigrations:
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    with zipfile.ZipFile(path) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename.startswith(PREFIX)]
        files: dict[str, str] = {}
        for entry in entries:
            if entry.is_dir():
                continue
            name = entry.filename.removeprefix(PREFIX)
            if NAME.fullmatch(name) is None or name in files:
                raise MigrationSafetyError(f"Unsupported or duplicate migration entry: {name}")
            files[name] = hashlib.sha256(archive.read(entry)).hexdigest()
    if not files:
        raise MigrationSafetyError("JAR contains no versioned SQL migrations")
    return JarMigrations(digest, tuple(sorted(files.items())))


def read_review(path: Path) -> str:
    """CodeDeploy 아티팩트 밖의 운영자 소유 경로만 신뢰한다."""
    for entry in (path, *path.parents):
        info = entry.lstat()
        if stat.S_ISLNK(info.st_mode):
            raise MigrationSafetyError(f"Review path contains a symlink: {entry}")
        shared_temporary_parent = entry not in (path, path.parent) and info.st_uid == 0 and info.st_mode & stat.S_ISVTX
        if info.st_uid not in (0, os.geteuid()) or (info.st_mode & 0o022 and not shared_temporary_parent):
            raise MigrationSafetyError(f"Review path must be operator-owned and protected: {entry}")
    flags = os.O_RDONLY | os.O_NOFOLLOW
    with os.fdopen(os.open(path, flags), "r", encoding="utf-8") as source:
        return source.read(16385)


def verify_review(
    expected: tuple[str, str, str], review_file: Path
) -> Literal["rolling", "serial-workers"]:
    raw = read_review(review_file)
    if len(raw) > 16384:
        raise MigrationSafetyError("Review exceeds size limit")
    payload = json.loads(raw)
    if not isinstance(payload, dict):
        raise MigrationSafetyError("Review must be a JSON object")
    if payload.get("schema") != "knot.migration-review.v1":
        raise MigrationSafetyError("Unknown review schema")
    if (payload.get("environment"), payload.get("active_jar_sha256"),
            payload.get("candidate_jar_sha256")) != expected:
        raise MigrationSafetyError("Review does not match environment and exact JAR pair")
    expires = payload.get("expires_at_epoch")
    if type(expires) is not int or not time.time() < expires <= time.time() + 86400:
        raise MigrationSafetyError("Review expired or exceeds the 24-hour approval window")
    if payload.get("backward_compatible") is not True:
        raise MigrationSafetyError("Backward compatibility was not approved")
    evidence = payload.get("evidence")
    if not isinstance(evidence, str) or not evidence.strip():
        raise MigrationSafetyError("Review evidence is required")
    if payload.get("runtime_preconditions_verified") is not True:
        raise MigrationSafetyError("Runtime compatibility preconditions were not verified")
    mode = payload.get("execution_mode", "rolling")
    if mode == "rolling":
        return "rolling"
    if mode == "serial-workers" and expected[0] == "dev":
        return "serial-workers"
    raise MigrationSafetyError("Unknown or unauthorized migration execution mode")


def verify(active: JarMigrations, candidate: JarMigrations) -> tuple[str, ...]:
    old = dict(active.files)
    new = dict(candidate.files)
    removed = sorted(old.keys() - new.keys())
    modified = sorted(name for name in old.keys() & new.keys() if old[name] != new[name])
    if removed or modified:
        raise MigrationSafetyError(f"Existing migrations changed: removed={removed}, modified={modified}")
    added = tuple(sorted(new.keys() - old.keys()))
    if not added:
        return ()
    old_max = max(tuple(map(int, name[1:].split("__", 1)[0].split("_"))) for name in old)
    if any(tuple(map(int, name[1:].split("__", 1)[0].split("_"))) <= old_max for name in added):
        raise MigrationSafetyError("New migration must be above the active migration version")
    return added


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--active", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--environment", choices=("dev", "prod"), required=True)
    parser.add_argument("--reviews", type=Path, required=True)
    args = parser.parse_args()
    try:
        active = read_jar(args.active)
        candidate = read_jar(args.candidate)
        review = args.reviews / f"{args.environment}-{active.jar_sha256}-{candidate.jar_sha256}.json"
        added = verify(active, candidate)
        mode = "rolling"
        if added:
            mode = verify_review((args.environment, active.jar_sha256, candidate.jar_sha256), review)
        print(f"[migration-safety] verified added={','.join(added) or '<none>'} mode={mode}", file=sys.stderr)
        print(mode)
        return 0
    except (MigrationSafetyError, OSError, zipfile.BadZipFile, json.JSONDecodeError) as failure:
        print(f"[migration-safety] rejected: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
