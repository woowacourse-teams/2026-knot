#!/usr/bin/env bash
set -euo pipefail

readonly DEPLOYMENT_MODE="${DEPLOYMENT_MODE:-legacy-in-place}"

rm -rf -- scripts/systemd scripts/nginx scripts/certs
mkdir -p scripts
find backend/scripts -mindepth 1 -maxdepth 1 ! -name tests -exec cp -R -- {} scripts/ \;

case "$DEPLOYMENT_MODE" in
  legacy-in-place)
    rm -rf -- scripts/systemd
    cp backend/appspec.yml appspec.yml
    ;;
  dev-bluegreen)
    cp backend/appspec-dev-bluegreen.yml appspec.yml
    mkdir -p scripts/systemd
    cp backend/ops/systemd/knot-backend-blue.service scripts/systemd/
    cp backend/ops/systemd/knot-backend-green.service scripts/systemd/
    mkdir -p scripts/nginx
    cp backend/ops/nginx/knot-backend-metrics.conf scripts/nginx/
    ;;
  prod-bluegreen)
    cp backend/appspec-prod-bluegreen.yml appspec.yml
    mkdir -p scripts/systemd
    cp backend/ops/systemd/prod/knot-backend-blue.service scripts/systemd/
    cp backend/ops/systemd/prod/knot-backend-green.service scripts/systemd/
    mkdir -p scripts/nginx
    cp backend/ops/nginx/knot-backend-metrics.conf scripts/nginx/
    mkdir -p scripts/certs
    cp backend/ops/certificates/cloudflare-origin-rsa-root.pem scripts/certs/
    ;;
  *)
    printf 'Unknown DEPLOYMENT_MODE: %s\n' "$DEPLOYMENT_MODE" >&2
    exit 2
    ;;
esac
