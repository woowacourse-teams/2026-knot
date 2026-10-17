#!/usr/bin/env python3
"""Run Alloy with a bounded local log; launchd owns process restarts."""

import os
from pathlib import Path
import signal
import subprocess
import sys

LOG_LIMIT = 2 * 1024 * 1024


def append_log(path, chunk, limit=LOG_LIMIT):
    if path.exists() and path.stat().st_size + len(chunk) > limit:
        path.replace(path.with_suffix('.log.1'))
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(descriptor, 'ab') as stream:
        stream.write(chunk)


def main():
    os.umask(0o077)
    root = Path(os.environ['KNOT_MAC_CONFIG_DIR'])
    process = subprocess.Popen([
        sys.argv[1], 'run', '--server.http.listen-addr=127.0.0.1:12346',
        '--disable-reporting', '--storage.path=' + str(root / 'wal'),
        str(root / 'config.alloy'),
    ], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def stop(signum, _frame):
        if process.poll() is None:
            process.send_signal(signum)
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        while chunk := process.stdout.read1(8192):
            append_log(root / 'logs' / 'alloy.log', chunk)
    finally:
        if process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    return process.returncode


if __name__ == '__main__':
    sys.exit(main())
