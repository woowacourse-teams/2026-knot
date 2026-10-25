#!/usr/bin/env python3
"""Render the shared EC2 Alloy configuration without changing live files."""

import argparse
import json
import os
from pathlib import Path
import re
from urllib.parse import urlsplit


def render(source, environment, ingest_url):
    if environment not in ("dev", "prod"):
        raise ValueError("Environment must be dev or prod")
    url = urlsplit(ingest_url)
    if (url.scheme != "https" or not url.hostname or url.username or url.password
            or url.query or url.fragment or url.path not in ("", "/")):
        raise ValueError("Ingestion endpoint must be an HTTPS origin without credentials")
    # Materialize environment choices so the installed file is easy to audit.
    source = re.sub(r'\{ dev = "([^"]+)", prod = "([^"]+)" \}\[sys.env\("KNOT_ENV"\)\]',
                    lambda m: json.dumps(m.group(1 if environment == "dev" else 2)), source)
    source = source.replace('sys.env("KNOT_INGEST_BASE_URL")', json.dumps(ingest_url.rstrip("/")))
    # Preserve the existing journal component identity and its stored cursor.
    if environment == "prod":
        source = source.replace('loki.source.journal "spring" {',
                                'loki.source.journal "spring_blue_legacy" {')
    validate_journal_matches(source)
    return source


def validate_journal_matches(source):
    expected = {"knot-backend.service", "knot-backend-blue.service", "knot-backend-green.service"}
    matches = re.findall(r'(?m)^\s*matches\s*=\s*"([^"]+)"', source)
    if set(matches) != {"_SYSTEMD_UNIT=" + unit for unit in expected} or len(matches) != 3:
        raise ValueError("Journal selectors must cover legacy, blue and green using _SYSTEMD_UNIT")
    for body in re.findall(r'loki.source.journal "[^"]+" \{(.*?)\n\}', source, re.S):
        unit = re.search(r'matches\s*=\s*"_SYSTEMD_UNIT=([^"]+)"', body).group(1)
        slot = "legacy" if unit == "knot-backend.service" else unit.removeprefix("knot-backend-").removesuffix(".service")
        if not re.search(r'\bslot\s*=\s*"' + slot + '"', body) or not re.search(r'\bunit\s*=\s*"' + re.escape(unit) + '"', body):
            raise ValueError("Journal labels must identify the exact slot and systemd unit")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True, choices=["dev", "prod"])
    parser.add_argument("--ingest-url-file", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    source = Path(__file__).with_name("config.alloy").read_text()
    rendered = render(source, args.environment, args.ingest_url_file.read_text().strip())
    # Exclusive creation prevents accidentally replacing a mounted/live config.
    with args.output.open("x") as output:
        output.write(rendered)
