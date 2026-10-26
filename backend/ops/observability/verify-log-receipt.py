#!/usr/bin/env python3
"""Verify a unique request ID/marker in the target slot through the local Loki API."""

import argparse
import json
import time
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--environment", required=True, choices=["dev", "prod"])
parser.add_argument("--slot", required=True, choices=["blue", "green"])
parser.add_argument("--marker", required=True)
parser.add_argument("--since", required=True, type=float, help="Probe start time as Unix seconds")
parser.add_argument("--timeout", type=int, default=30, choices=range(1, 61))
parser.add_argument("--loki-url", default="http://127.0.0.1:3100")
args = parser.parse_args()
if not 8 <= len(args.marker) <= 128:
    parser.error("Use a unique request ID or marker of 8–128 characters")
if args.since > time.time() or args.since < time.time() - 3600:
    parser.error("Probe start time must be within the last hour")
query = ('{project="knot",env=' + json.dumps(args.environment) + ',job="spring",slot='
         + json.dumps(args.slot) + ',unit=' + json.dumps('knot-backend-' + args.slot + '.service')
         + '} |= ' + json.dumps(args.marker))
deadline = time.monotonic() + args.timeout
while True:
    parameters = {"query": query, "start": str(int(args.since * 1e9)),
                  "end": str(int(time.time() * 1e9)), "limit": "10"}
    with urllib.request.urlopen(args.loki_url.rstrip("/") + "/loki/api/v1/query_range?"
                                + urllib.parse.urlencode(parameters), timeout=10) as response:
        data = json.load(response)
    if data.get("status") != "success":
        raise SystemExit("Loki query failed")
    count = sum(len(stream["values"]) for stream in data["data"]["result"])
    if count:
        print(json.dumps({"received": True, "environment": args.environment,
                          "slot": args.slot, "matching_lines": count}))
        break
    if time.monotonic() >= deadline:
        raise SystemExit("No matching log received for the target slot before timeout")
    time.sleep(min(2, max(0, deadline - time.monotonic())))
