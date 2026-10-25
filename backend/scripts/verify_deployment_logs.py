#!/usr/bin/env python3
"""Verify Nginx -> active Spring slot -> Loki without granting arbitrary Loki reads."""

import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import urlencode, urlsplit
import uuid


class CheckFailure(Exception):
    def __init__(self, message, code):
        super().__init__(message)
        self.code = code


def curl_json(url, options, timeout):
    with tempfile.TemporaryDirectory(prefix="knot-log-check-") as temp:
        headers, body = Path(temp) / "headers", Path(temp) / "body"
        command = ["curl", "--noproxy", "*", "--silent", "--show-error",
                   "--connect-timeout", str(min(3, timeout)), "--max-time", str(timeout),
                   "--max-filesize", "65536", "--dump-header", str(headers),
                   "--output", str(body), "--write-out", "%{http_code}", *options, url]
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=timeout + 1)
        except (OSError, subprocess.SubprocessError):
            raise CheckFailure("HTTP transport failed", 3) from None
        if result.returncode or result.stdout.strip() != "200":
            raise CheckFailure("HTTP transport/status failed", 3)
        try:
            if body.stat().st_size > 65536 or headers.stat().st_size > 65536:
                raise ValueError("oversized response")
            return json.loads(body.read_text()), headers.read_text()
        except (OSError, ValueError):
            raise CheckFailure("Invalid HTTP response", 3) from None


def receipt_count(data):
    try:
        if data["status"] != "success" or data["data"]["resultType"] != "vector":
            raise ValueError("not an instant vector")
        result = data["data"]["result"]
        if not isinstance(result, list) or len(result) > 1:
            raise ValueError("unexpected series")
        if not result:
            return 0
        value = float(result[0]["value"][1])
        if not math.isfinite(value) or value < 0 or value != int(value):
            raise ValueError("invalid count")
        return int(value)
    except (KeyError, ValueError, TypeError, IndexError):
        raise CheckFailure("Invalid receipt query response", 3) from None


def request_id(data, headers):
    ids = {line.split(":", 1)[1].strip() for line in headers.splitlines()
           if line.lower().startswith("x-request-id:")}
    if not isinstance(data, dict) or data.get("status") != "UP" or len(ids) != 1:
        raise CheckFailure("Nginx health or request ID validation failed", 4)
    value = ids.pop()
    if not re.fullmatch(r"[0-9a-f]{32}", value):
        raise CheckFailure("Invalid Nginx request ID", 4)
    return value


def verify(args, fetch=curl_json, clock=time.monotonic, sleep=time.sleep):
    try:
        config = json.loads(args.config.read_text())
        origin = config["ingest_base_url"].rstrip("/")
        parsed = urlsplit(origin)
        if (config["environment"] != args.environment or parsed.scheme != "https"
                or not parsed.hostname or parsed.username or parsed.password
                or parsed.path or parsed.query or parsed.fragment):
            raise ValueError("invalid environment or origin")
    except (OSError, KeyError, ValueError, TypeError, AttributeError):
        raise CheckFailure("Invalid deployment log-check configuration", 4) from None
    tls = ["--cacert", str(args.tls_directory / "ca.crt"),
           "--cert", str(args.tls_directory / "client.crt"),
           "--key", str(args.tls_directory / "client.key")]
    deadline = clock() + args.timeout

    def count(marker):
        remaining = deadline - clock()
        if remaining <= 0:
            raise CheckFailure("Receipt deadline exceeded", 3)
        query = urlencode({"slot": args.slot, "request_id": marker})
        data, _ = fetch(origin + "/" + args.environment + "/log-receipt?" + query,
                        tls, min(5, remaining))
        return receipt_count(data)

    if args.preflight:
        # Exercise mTLS, the fixed query and Loki before starting the candidate.
        count(uuid.uuid4().hex)
        return {"preflight": "ok", "environment": args.environment, "slot": args.slot}

    host = {"prod": "api.knoted.kr", "dev": "dev-api.knoted.kr"}[args.environment]
    options = ["--resolve", host + ":443:127.0.0.1", "--header", "Connection: close"]
    if args.environment == "prod":
        if args.origin_ca is None or not args.origin_ca.is_file():
            raise CheckFailure("Prod origin CA certificate is required", 4)
        options += ["--cacert", str(args.origin_ca)]
    data, headers = fetch("https://" + host + "/actuator/health", options,
                          min(5, max(0.1, deadline - clock())))
    marker = request_id(data, headers)
    last_query_ok = False
    while clock() < deadline:
        try:
            received = count(marker)
            last_query_ok = True
            if received:
                return {"received": True, "environment": args.environment, "slot": args.slot,
                        "request_id": marker, "matching_lines": received}
        except CheckFailure:
            last_query_ok = False
        sleep(min(1, max(0, deadline - clock())))
    if last_query_ok:
        raise CheckFailure("Target slot request completion log was not received before timeout", 2)
    raise CheckFailure("Receipt endpoint unavailable at verification deadline", 3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True, choices=["dev", "prod"])
    parser.add_argument("--slot", required=True, choices=["blue", "green"])
    parser.add_argument("--preflight", action="store_true")
    parser.add_argument("--timeout", type=int, choices=range(1, 61), default=30)
    parser.add_argument("--config", type=Path,
                        default=Path("/etc/knot-observability-agent/deployment-log-check.json"))
    parser.add_argument("--tls-directory", type=Path, default=Path("/opt/knot-observability-agent/tls"))
    parser.add_argument("--origin-ca", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args)))
    except CheckFailure as error:
        # Never print private endpoint, curl stderr, response body or credentials.
        print(json.dumps({"received": False, "reason": str(error), "exit_code": error.code}))
        return error.code
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
