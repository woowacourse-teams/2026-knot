#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec /usr/bin/env KNOT_BLUEGREEN_PROFILE=dev bash "${SCRIPT_DIR}/bluegreen_deploy.sh"
