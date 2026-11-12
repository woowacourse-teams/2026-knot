#!/usr/bin/env bash
set -Eeuo pipefail

readonly BLUEGREEN_PROFILE="${KNOT_BLUEGREEN_PROFILE:-dev}"

case "$BLUEGREEN_PROFILE" in
  dev)
    readonly EXPECTED_DEPLOYMENT_GROUP="knot-backend-dev-deployment-group"
    readonly DEFAULT_NGINX_CONFIG="/etc/nginx/conf.d/knot-backend-dev.conf"
    ;;
  prod)
    readonly EXPECTED_DEPLOYMENT_GROUP="knot-backend-deployment-group"
    readonly DEFAULT_NGINX_CONFIG="/etc/nginx/conf.d/knot-backend.conf"
    ;;
  *)
    printf '[blue-green-preserve] Unsupported environment profile: %s\n' "$BLUEGREEN_PROFILE" >&2
    exit 2
    ;;
esac

readonly APP_ROOT="${KNOT_BLUEGREEN_APP_ROOT:-/opt/knot-backend}"
readonly NGINX_CONFIG="${KNOT_BLUEGREEN_NGINX_CONFIG:-$DEFAULT_NGINX_CONFIG}"
readonly LEGACY_JAR="${APP_ROOT}/app.jar"
readonly BLUE_JAR="${APP_ROOT}/slots/blue/app.jar"

DEPLOYMENT_GROUP_NAME="${DEPLOYMENT_GROUP_NAME:-}"
DEPLOYMENT_ID="${DEPLOYMENT_ID:-}"

log() {
  printf '[blue-green-preserve:%s] %s\n' "$BLUEGREEN_PROFILE" "$*"
}

die() {
  log "$*"
  exit 1
}

service_is_active() {
  systemctl is-active --quiet "$1"
}

read_upstream_port() {
  local match_count
  local port

  [[ -r "$NGINX_CONFIG" ]] || die "Nginx config is unreadable: ${NGINX_CONFIG}"
  match_count="$(grep -Ec '^[[:space:]]*proxy_pass http://127\.0\.0\.1:(8080|8081);[[:space:]]*$' "$NGINX_CONFIG" || true)"
  [[ "$match_count" == "1" ]] || die "Expected exactly one application upstream on port 8080 or 8081; found ${match_count}"
  port="$(sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:(8080|8081);[[:space:]]*$#\1#p' "$NGINX_CONFIG")"
  [[ "$port" == "8080" || "$port" == "8081" ]] || die "Unsupported Nginx upstream port: ${port}"
  printf '%s\n' "$port"
}

[[ "$DEPLOYMENT_GROUP_NAME" == "$EXPECTED_DEPLOYMENT_GROUP" ]] \
  || die "Refusing deployment group: ${DEPLOYMENT_GROUP_NAME:-<missing>}"
[[ "$DEPLOYMENT_ID" =~ ^d-[A-Za-z0-9]+$ ]] || die "Missing or invalid CodeDeploy deployment ID"

active_port="$(read_upstream_port)"
if service_is_active knot-backend-blue.service; then
  [[ "$active_port" == "8080" ]] || die "Blue service is active while Nginx points to port ${active_port}"
  log "Blue slot is already active; leaving its JAR untouched"
  exit 0
fi

if service_is_active knot-backend-green.service; then
  [[ "$active_port" == "8081" ]] || die "Green service is active while Nginx points to port ${active_port}"
  log "Green slot is already active; leaving its JAR untouched"
  exit 0
fi

[[ "$active_port" == "8080" ]] || die "Nginx points to Green but no Green service is active"
if [[ ! -f "$LEGACY_JAR" ]]; then
  [[ -f "$BLUE_JAR" ]] || die "Legacy and preserved Blue JARs are missing"
  log "Legacy JAR is already cleaned; reusing the preserved Blue JAR"
  exit 0
fi

install -d -o ubuntu -g ubuntu -m 0755 "$(dirname "$BLUE_JAR")"
temporary_jar="$(mktemp "${BLUE_JAR}.XXXXXX")"
if ! cp -p "$LEGACY_JAR" "$temporary_jar"; then
  rm -f "$temporary_jar"
  die "Could not preserve the legacy JAR"
fi
chown ubuntu:ubuntu "$temporary_jar"
chmod 0500 "$temporary_jar"
mv -f "$temporary_jar" "$BLUE_JAR"
log "Preserved ${LEGACY_JAR} as ${BLUE_JAR} before CodeDeploy Install"
