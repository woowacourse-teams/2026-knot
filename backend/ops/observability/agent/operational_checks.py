#!/usr/bin/env python3
"""Publish bounded service/RDS backup checks for Alloy's textfile collector."""

import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

BACKEND = ("knot-backend.service", "knot-backend-blue.service", "knot-backend-green.service")
REQUIRED = ("nginx.service", "docker.service", "codedeploy-agent.service")


def command(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=25)
    if result.returncode and not (args[:2] == ["systemctl", "show"] and "LoadState=not-found" in result.stdout):
        # stderr can contain sensitive connection details; publish success=0 instead.
        raise RuntimeError("Operational query failed")
    return result.stdout


def atomic_write(path, content):
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix=".check-")
    try:
        with os.fdopen(fd, "w") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def service_metrics(states):
    lines = []
    for unit in REQUIRED + BACKEND:
        state = states.get(unit, {})
        loaded = state.get("LoadState") == "loaded"
        active = loaded and state.get("ActiveState") == "active"
        failed = loaded and state.get("ActiveState") == "failed"
        lines.append(f'knot_systemd_service_failed{{unit="{unit}"}} {int(failed)}')
        if unit in REQUIRED:
            lines.append(f'knot_required_service_active{{unit="{unit}"}} {int(active)}')
    active = any(states.get(unit, {}).get("ActiveState") == "active" for unit in BACKEND)
    lines.append(f"knot_backend_service_active {int(active)}")
    return "\n".join(lines) + "\n"


def backup_metrics(data):
    retained = int(data["RetentionDays"])
    stamp = datetime.datetime.fromisoformat(data["LatestRestorableTime"].replace("Z", "+00:00"))
    if stamp.tzinfo is None or retained < 0:
        raise ValueError("Invalid backup metadata")
    return (f"knot_rds_backup_retention_days {retained}\n"
            f"knot_rds_backup_latest_restorable_timestamp_seconds {stamp.timestamp()}\n")


def collect(directory, environment, run=command, now=None):
    now = time.time() if now is None else now
    directory.mkdir(parents=True, exist_ok=True, mode=0o755)
    service_success = 1
    try:
        states = {}
        for unit in REQUIRED + BACKEND:
            raw = run(["systemctl", "show", unit, "--property=LoadState,ActiveState"])
            states[unit] = dict(line.split("=", 1) for line in raw.splitlines() if "=" in line)
        atomic_write(directory / "services.prom", service_metrics(states))
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError):
        service_success = 0
    atomic_write(directory / "operational-check.prom",
                 f"knot_operational_check_timestamp_seconds {now}\n"
                 f"knot_service_check_success {service_success}\n")
    status_path = directory / "rds-check.prom"
    if status_path.exists() and now - status_path.stat().st_mtime < 300:
        return
    backup_success = 1
    try:
        database = {"dev": "knot-dev-database", "prod": "knot-database"}[environment]
        raw = run(["aws", "rds", "describe-db-instances", "--region", "ap-northeast-2",
                   "--db-instance-identifier", database, "--query",
                   "DBInstances[0].{RetentionDays:BackupRetentionPeriod,LatestRestorableTime:LatestRestorableTime}",
                   "--output", "json"])
        atomic_write(directory / "rds-backup.prom", backup_metrics(json.loads(raw)))
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError, TypeError, KeyError, AttributeError):
        backup_success = 0
    atomic_write(status_path, f"knot_rds_backup_check_success {backup_success}\n"
                 f"knot_rds_backup_check_timestamp_seconds {now}\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True, choices=["dev", "prod"])
    parser.add_argument("--directory", type=Path, default=Path("/var/lib/knot-observability/textfile"))
    args = parser.parse_args()
    collect(args.directory, args.environment)
