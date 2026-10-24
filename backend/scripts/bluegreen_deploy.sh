#!/usr/bin/env bash
set -Eeuo pipefail

readonly BLUEGREEN_PROFILE="${KNOT_BLUEGREEN_PROFILE:-dev}"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BUNDLE_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

case "$BLUEGREEN_PROFILE" in
  dev)
    readonly PROFILE_LABEL="Dev"
    readonly EXPECTED_DEPLOYMENT_GROUP="knot-backend-dev-deployment-group"
    readonly DEFAULT_NGINX_CONFIG="/etc/nginx/conf.d/knot-backend-dev.conf"
    readonly DEFAULT_NGINX_METRICS_CONFIG="/etc/nginx/conf.d/knot-backend-dev-metrics.conf"
    readonly DEFAULT_HEALTH_HOST="dev-api.knoted.kr"
    ;;
  prod)
    readonly PROFILE_LABEL="Prod"
    readonly EXPECTED_DEPLOYMENT_GROUP="knot-backend-deployment-group"
    readonly DEFAULT_NGINX_CONFIG="/etc/nginx/conf.d/knot-backend.conf"
    readonly DEFAULT_NGINX_METRICS_CONFIG="/etc/nginx/conf.d/knot-backend-metrics.conf"
    readonly DEFAULT_HEALTH_HOST="api.knoted.kr"
    ;;
  *)
    printf '[blue-green] Unsupported environment profile: %s\n' "$BLUEGREEN_PROFILE" >&2
    exit 2
    ;;
esac

readonly APP_ROOT="${KNOT_BLUEGREEN_APP_ROOT:-/opt/knot-backend}"
readonly NGINX_CONFIG="${KNOT_BLUEGREEN_NGINX_CONFIG:-$DEFAULT_NGINX_CONFIG}"
readonly NGINX_METRICS_CONFIG="${KNOT_BLUEGREEN_NGINX_METRICS_CONFIG:-$DEFAULT_NGINX_METRICS_CONFIG}"
readonly ALLOY_CONFIG="${KNOT_BLUEGREEN_ALLOY_CONFIG:-/opt/knot-observability-agent/config.alloy}"
readonly ALLOY_RELOAD_URL="${KNOT_BLUEGREEN_ALLOY_RELOAD_URL:-http://127.0.0.1:12345/-/reload}"
readonly ALLOY_READY_URL="${KNOT_BLUEGREEN_ALLOY_READY_URL:-http://127.0.0.1:12345/-/ready}"
readonly SYSTEMD_DIR="${KNOT_BLUEGREEN_SYSTEMD_DIR:-/etc/systemd/system}"
readonly STATE_ROOT="${KNOT_BLUEGREEN_STATE_ROOT:-/var/lib/knot-backend-deploy/blue-green}"
readonly ALLOY_CONFIG_APPLIED_MARKER="${STATE_ROOT}/alloy-config-applied.sha256"
readonly ROUTE_UNCERTAIN_MARKER="${STATE_ROOT}/route-uncertain"
readonly LOCK_FILE="${KNOT_BLUEGREEN_LOCK_FILE:-/run/lock/knot-backend-bluegreen.lock}"
readonly HEALTH_ATTEMPTS="${KNOT_BLUEGREEN_HEALTH_ATTEMPTS:-60}"
readonly HEALTH_INTERVAL_SECONDS="${KNOT_BLUEGREEN_HEALTH_INTERVAL_SECONDS:-2}"
readonly CURL_TIMEOUT_SECONDS="${KNOT_BLUEGREEN_CURL_TIMEOUT_SECONDS:-5}"
readonly DRAIN_TIMEOUT_SECONDS="${KNOT_BLUEGREEN_DRAIN_TIMEOUT_SECONDS:-900}"
readonly DRAIN_POLL_INTERVAL_SECONDS="${KNOT_BLUEGREEN_DRAIN_POLL_INTERVAL_SECONDS:-2}"
readonly HEALTH_HOST="${KNOT_BLUEGREEN_HEALTH_HOST:-$DEFAULT_HEALTH_HOST}"
readonly PROD_ORIGIN_CA_CERT="${SCRIPT_DIR}/certs/cloudflare-origin-rsa-root.pem"
readonly INCOMING_JAR="${BUNDLE_ROOT}/app.jar"
readonly METRICS_NGINX_SOURCE="${SCRIPT_DIR}/nginx/knot-backend-metrics.conf"

DEPLOYMENT_GROUP_NAME="${DEPLOYMENT_GROUP_NAME:-}"
DEPLOYMENT_ID="${DEPLOYMENT_ID:-}"
CANDIDATE_STARTED=false
ROUTE_CHANGE_ATTEMPTED=false
MONITORING_ROUTE_READY=false
SYSTEMD_STATE_CAPTURED=false
COMMITTED=false
ACTIVE_PORT=""
ACTIVE_SERVICE=""
ACTIVE_JAR=""
CANDIDATE_PORT=""
CANDIDATE_SERVICE=""
CANDIDATE_SLOT=""
ACTIVE_WAS_ENABLED=false
CANDIDATE_WAS_ENABLED=false
TRANSACTION_DIR=""
PREVIOUS_NGINX_CONFIG=""
PREVIOUS_NGINX_METRICS_CONFIG=""
NGINX_METRICS_CONFIG_WAS_PRESENT=false
NGINX_METRICS_ROUTE_CHANGED=false
LAST_HEALTH_FAILURE_REASON=""

log() {
  printf '[blue-green:%s] %s\n' "$BLUEGREEN_PROFILE" "$*"
}

die() {
  log "$*"
  exit 1
}

service_is_active() {
  systemctl is-active --quiet "$1"
}

service_is_enabled() {
  local state

  state="$(systemctl is-enabled "$1" 2>/dev/null || true)"
  [[ "$state" == "enabled" || "$state" == "enabled-runtime" ]]
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

read_metrics_upstream_port() {
  local match_count
  local port

  [[ -r "$NGINX_METRICS_CONFIG" ]] || return 1
  match_count="$(grep -Ec '^[[:space:]]*proxy_pass http://127\.0\.0\.1:(8080|8081);[[:space:]]*$' "$NGINX_METRICS_CONFIG" || true)"
  [[ "$match_count" == "1" ]] || return 1
  port="$(sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:(8080|8081);[[:space:]]*$#\1#p' "$NGINX_METRICS_CONFIG")"
  [[ "$port" == "8080" || "$port" == "8081" ]] || return 1
  printf '%s\n' "$port"
}

read_alloy_metrics_target_port() {
  local match_count
  local port

  [[ -r "$ALLOY_CONFIG" ]] || return 1
  match_count="$(grep -Ec '^[[:space:]]*targets = \[\{ __address__ = "127\.0\.0\.1:(8080|18080)", service = "knot-backend",' "$ALLOY_CONFIG" || true)"
  [[ "$match_count" == "1" ]] || return 1
  port="$(sed -nE 's#^[[:space:]]*targets = \[\{ __address__ = "127\.0\.0\.1:(8080|18080)", service = "knot-backend",.*#\1#p' "$ALLOY_CONFIG")"
  [[ "$port" == "8080" || "$port" == "18080" ]] || return 1
  printf '%s\n' "$port"
}

migration_files() {
  local jar_file="$1"
  local contents

  contents="$(jar tf "$jar_file")" || return 1
  printf '%s\n' "$contents" \
    | grep -E '^BOOT-INF/classes/db/migration/[^/]+\.sql$' \
    | LC_ALL=C sort || true
}

health_is_up() {
  local response
  local http_status

  if ! response="$(curl --noproxy '*' --fail --silent --show-error --max-time "$CURL_TIMEOUT_SECONDS" --write-out $'\n%{http_code}' "$@" 2>&1)"; then
    LAST_HEALTH_FAILURE_REASON="$(printf '%s' "$response" | tr '\n' ' ' | cut -c1-240)"
    [[ -n "$LAST_HEALTH_FAILURE_REASON" ]] || LAST_HEALTH_FAILURE_REASON="curl exited without a diagnostic"
    return 1
  fi

  http_status="${response##*$'\n'}"
  if [[ "$http_status" != "200" ]]; then
    LAST_HEALTH_FAILURE_REASON="HTTP status ${http_status}"
    return 1
  fi
  response="${response%$'\n'*}"
  if ! health_body_is_up "$response"; then
    LAST_HEALTH_FAILURE_REASON="HTTP 200 body did not report status UP"
    return 1
  fi
  LAST_HEALTH_FAILURE_REASON=""
}

health_is_up_at_url() {
  local url="$1"

  health_is_up "$url"
}

health_body_is_up() {
  python3 -c '
import json
import sys

body = json.load(sys.stdin)
if not isinstance(body, dict):
    sys.exit(1)
if body.get("status") != "UP":
    sys.exit(1)
' <<< "$1" 2>/dev/null
}

health_is_up_through_nginx() {
  local url="https://${HEALTH_HOST}/actuator/health"

  if [[ "$BLUEGREEN_PROFILE" == "prod" ]]; then
    health_is_up --cacert "$PROD_ORIGIN_CA_CERT" \
      --resolve "${HEALTH_HOST}:443:127.0.0.1" "$url"
    return
  fi

  health_is_up --resolve "${HEALTH_HOST}:443:127.0.0.1" "$url"
}

wait_for_health() {
  local url="$1"
  local attempt

  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    if health_is_up_at_url "$url"; then
      return 0
    fi
    sleep "$HEALTH_INTERVAL_SECONDS"
  done

  return 1
}

wait_for_nginx_health() {
  local attempt

  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    if health_is_up_through_nginx; then
      return 0
    fi
    sleep "$HEALTH_INTERVAL_SECONDS"
  done

  log "Nginx Health Check failed for ${HEALTH_HOST}: ${LAST_HEALTH_FAILURE_REASON:-no usable response}"
  return 1
}

metrics_is_available() {
  local url="http://127.0.0.1:18080/actuator/prometheus"

  curl --noproxy '*' --fail --silent --show-error --max-time "$CURL_TIMEOUT_SECONDS" "$url" 2>/dev/null \
    | grep -q '^jvm_memory_used_bytes'
}

wait_for_metrics_endpoint() {
  local attempt

  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    if metrics_is_available; then
      return 0
    fi
    sleep "$HEALTH_INTERVAL_SECONDS"
  done

  return 1
}

alloy_is_ready() {
  curl --noproxy '*' --fail --silent --show-error --max-time "$CURL_TIMEOUT_SECONDS" \
    "$ALLOY_READY_URL" >/dev/null 2>&1
}

wait_for_alloy_ready() {
  local attempt

  for ((attempt = 1; attempt <= HEALTH_ATTEMPTS; attempt++)); do
    if alloy_is_ready; then
      return 0
    fi
    sleep "$HEALTH_INTERVAL_SECONDS"
  done

  return 1
}

reload_alloy_config() {
  curl --noproxy '*' --fail --silent --show-error --max-time "$CURL_TIMEOUT_SECONDS" \
    --request POST "$ALLOY_RELOAD_URL" >/dev/null || return 1
  wait_for_alloy_ready
}

reload_alloy_config_if_needed() {
  local config_hash
  local applied_hash
  local marker_temp

  alloy_is_ready || return 0
  config_hash="$(sha256sum "$ALLOY_CONFIG" | awk '{print $1}')" || return 1
  applied_hash="$(cat "$ALLOY_CONFIG_APPLIED_MARKER" 2>/dev/null || true)"
  [[ "$config_hash" != "$applied_hash" ]] || return 0
  reload_alloy_config || return 1
  marker_temp="$(mktemp "${ALLOY_CONFIG_APPLIED_MARKER}.XXXXXX")" || return 1
  if ! printf '%s\n' "$config_hash" > "$marker_temp"; then
    rm -f "$marker_temp"
    return 1
  fi
  if ! mv -f "$marker_temp" "$ALLOY_CONFIG_APPLIED_MARKER"; then
    rm -f "$marker_temp"
    return 1
  fi
}

write_metrics_route() {
  local port="$1"
  local temporary_file
  local match_count

  [[ "$port" == "8080" || "$port" == "8081" ]] || return 1
  match_count="$(grep -Ec '^[[:space:]]*proxy_pass http://127\.0\.0\.1:8080;[[:space:]]*$' "$METRICS_NGINX_SOURCE" || true)"
  [[ "$match_count" == "1" ]] || return 1
  temporary_file="$(mktemp "${NGINX_METRICS_CONFIG}.bluegreen.XXXXXX")" || return 1
  if ! sed "s#proxy_pass http://127.0.0.1:8080;#proxy_pass http://127.0.0.1:${port};#" \
    "$METRICS_NGINX_SOURCE" > "$temporary_file"; then
    rm -f "$temporary_file"
    return 1
  fi
  if [[ -f "$NGINX_METRICS_CONFIG" ]] && cmp -s "$temporary_file" "$NGINX_METRICS_CONFIG"; then
    rm -f "$temporary_file"
    NGINX_METRICS_ROUTE_CHANGED=false
    return 0
  fi
  chmod 0644 "$temporary_file" || return 1
  mv -f "$temporary_file" "$NGINX_METRICS_CONFIG" || return 1
  NGINX_METRICS_ROUTE_CHANGED=true
  [[ "$(read_metrics_upstream_port)" == "$port" ]]
}

update_alloy_metrics_target() {
  local current_port
  local temporary_file

  current_port="$(read_alloy_metrics_target_port)" || return 1
  [[ "$current_port" == "18080" ]] && return 0
  temporary_file="$(mktemp "${ALLOY_CONFIG}.bluegreen.XXXXXX")" || return 1
  if ! sed 's#__address__ = "127.0.0.1:8080"#__address__ = "127.0.0.1:18080"#' \
    "$ALLOY_CONFIG" > "$temporary_file"; then
    rm -f "$temporary_file"
    return 1
  fi
  # Alloy bind-mounts this file; keep its inode so the container sees the update.
  if ! cat "$temporary_file" > "$ALLOY_CONFIG"; then
    rm -f "$temporary_file"
    return 1
  fi
  rm -f "$temporary_file"
  [[ "$(read_alloy_metrics_target_port)" == "18080" ]]
}

prepare_monitoring_route() {
  ROUTE_CHANGE_ATTEMPTED=true
  write_metrics_route "$ACTIVE_PORT" || return 1
  if [[ "$NGINX_METRICS_ROUTE_CHANGED" == true ]]; then
    nginx -t || return 1
    nginx -s reload || return 1
  fi
  wait_for_metrics_endpoint || return 1
  MONITORING_ROUTE_READY=true
  update_alloy_metrics_target || return 1
  reload_alloy_config_if_needed || return 1
}

established_connection_count() {
  local port="$1"
  local connections

  connections="$(ss -Htn state established "( sport = :${port} )")" || return 1
  if [[ -z "$connections" ]]; then
    printf '0\n'
    return 0
  fi
  awk 'END { print NR + 0 }' <<< "$connections"
}

nginx_upstream_connection_count() {
  local port="$1"
  local connections

  connections="$(ss -Htnp state established "( dport = :${port} )")" || return 1
  if [[ -z "$connections" ]]; then
    printf '0\n'
    return 0
  fi
  awk '/users:\(\("nginx",/ { count++ } END { print count + 0 }' <<< "$connections"
}

wait_for_connections_to_drain() {
  local port="$1"
  local label="$2"
  local deadline=$((SECONDS + DRAIN_TIMEOUT_SECONDS))
  local count
  local last_log_time=0

  while true; do
    count="$(nginx_upstream_connection_count "$port")" || return 1
    [[ "$count" =~ ^[0-9]+$ ]] || return 1
    if [[ "$count" == "0" ]]; then
      log "${label} has no established upstream connections"
      return 0
    fi

    if (( SECONDS >= deadline )); then
      log "Drain timed out on port ${port} with ${count} established connection(s)"
      return 1
    fi

    if (( SECONDS - last_log_time >= 30 )); then
      log "Waiting for ${label} connections to drain on port ${port}: ${count} remaining"
      last_log_time="$SECONDS"
    fi
    sleep "$DRAIN_POLL_INTERVAL_SECONDS"
  done
}

restore_nginx_config() {
  local restore_file

  [[ -f "$PREVIOUS_NGINX_CONFIG" ]] || return 1
  restore_file="$(mktemp "${NGINX_CONFIG}.bluegreen.XXXXXX")" || return 1
  if ! cp "$PREVIOUS_NGINX_CONFIG" "$restore_file"; then
    rm -f "$restore_file"
    return 1
  fi
  chmod 0644 "$restore_file" || return 1
  mv -f "$restore_file" "$NGINX_CONFIG" || return 1
  if [[ "$MONITORING_ROUTE_READY" == true ]]; then
    write_metrics_route "$ACTIVE_PORT" || return 1
  elif [[ "$NGINX_METRICS_CONFIG_WAS_PRESENT" == true ]]; then
    restore_file="$(mktemp "${NGINX_METRICS_CONFIG}.bluegreen.XXXXXX")" || return 1
    if ! cp "$PREVIOUS_NGINX_METRICS_CONFIG" "$restore_file"; then
      rm -f "$restore_file"
      return 1
    fi
    chmod 0644 "$restore_file" || return 1
    mv -f "$restore_file" "$NGINX_METRICS_CONFIG" || return 1
  else
    rm -f "$NGINX_METRICS_CONFIG" || return 1
  fi
  nginx -t || return 1
  nginx -s reload || return 1
  [[ "$(read_upstream_port)" == "$ACTIVE_PORT" ]] || return 1
  if [[ "$MONITORING_ROUTE_READY" == true || "$NGINX_METRICS_CONFIG_WAS_PRESENT" == true ]]; then
    [[ "$(read_metrics_upstream_port)" == "$ACTIVE_PORT" ]] || return 1
  fi
}

restore_systemd_enablement() {
  [[ "$SYSTEMD_STATE_CAPTURED" == true ]] || return 0

  if [[ "$ACTIVE_WAS_ENABLED" == true ]]; then
    systemctl enable "$ACTIVE_SERVICE" || log "Could not restore enablement for ${ACTIVE_SERVICE}"
  else
    systemctl disable "$ACTIVE_SERVICE" >/dev/null 2>&1 || true
  fi

  if [[ "$CANDIDATE_WAS_ENABLED" == true ]]; then
    systemctl enable "$CANDIDATE_SERVICE" || log "Could not restore enablement for ${CANDIDATE_SERVICE}"
  else
    systemctl disable "$CANDIDATE_SERVICE" >/dev/null 2>&1 || true
  fi
}

rollback_transaction() {
  local active_url
  local candidate_connections
  local route_restored=false

  set +e
  log "Deployment failed; restoring the previous Blue route"

  if [[ "$ROUTE_CHANGE_ATTEMPTED" == true ]]; then
    if ! service_is_active "$ACTIVE_SERVICE"; then
      systemctl start "$ACTIVE_SERVICE"
    fi
    active_url="http://127.0.0.1:${ACTIVE_PORT}/actuator/health"
    if wait_for_health "$active_url"; then
      if restore_nginx_config; then
        route_restored=true
        log "Previous application route restored to port ${ACTIVE_PORT}"
      else
        printf '%s\n' "$DEPLOYMENT_ID" > "$ROUTE_UNCERTAIN_MARKER" \
          || log "Could not write the route-uncertain marker"
        log "Nginx route restore failed; both application processes are being preserved"
      fi
    else
      printf '%s\n' "$DEPLOYMENT_ID" > "$ROUTE_UNCERTAIN_MARKER" \
        || log "Could not write the route-uncertain marker"
      log "Previous application is not healthy; current route and both processes are being preserved"
    fi
    if [[ "$route_restored" == false ]]; then
      log "Future deployments are blocked until the Nginx route is checked and the route-uncertain marker is cleared"
    fi
  fi

  restore_systemd_enablement

  if [[ "$CANDIDATE_STARTED" == true ]]; then
    if [[ "$ROUTE_CHANGE_ATTEMPTED" == false ]]; then
      systemctl stop "$CANDIDATE_SERVICE"
    elif [[ "$route_restored" == true ]]; then
      candidate_connections="$(established_connection_count "$CANDIDATE_PORT" 2>/dev/null)"
      if [[ "$candidate_connections" == "0" ]]; then
        systemctl stop "$CANDIDATE_SERVICE"
      else
        log "Leaving ${CANDIDATE_SERVICE} running while candidate connections remain"
      fi
    else
      log "Leaving ${CANDIDATE_SERVICE} running because the previous route was not confirmed restored"
    fi
  fi
}

on_exit() {
  local exit_status="$?"

  trap - EXIT
  if [[ "$exit_status" -ne 0 && "$COMMITTED" != true ]]; then
    rollback_transaction
  fi
  exit "$exit_status"
}

select_active_service() {
  case "$ACTIVE_PORT" in
    8080)
      if service_is_active knot-backend-blue.service; then
        service_is_active knot-backend.service && die "Both Blue slot and legacy service are active on port 8080"
        ACTIVE_SERVICE="knot-backend-blue.service"
        ACTIVE_JAR="${APP_ROOT}/slots/blue/app.jar"
        return
      fi
      service_is_active knot-backend.service || die "Nginx points to 8080 but no supported Blue service is active"
      ACTIVE_SERVICE="knot-backend.service"
      ACTIVE_JAR="${APP_ROOT}/app.jar"
      ;;
    8081)
      service_is_active knot-backend-green.service || die "Nginx points to 8081 but Green service is not active"
      ACTIVE_SERVICE="knot-backend-green.service"
      ACTIVE_JAR="${APP_ROOT}/slots/green/app.jar"
      ;;
  esac
}

restore_legacy_service_from_blue() {
  local temporary_jar

  install -d -o ubuntu -g ubuntu -m 0755 "$APP_ROOT"
  temporary_jar="$(mktemp "${APP_ROOT}/app.jar.bluegreen.XXXXXX")" || return 1
  if ! cp -p "$ACTIVE_JAR" "$temporary_jar"; then
    rm -f "$temporary_jar"
    return 1
  fi
  if ! chown ubuntu:ubuntu "$temporary_jar" \
    || ! chmod 0500 "$temporary_jar" \
    || ! mv -f "$temporary_jar" "$APP_ROOT/app.jar"; then
    rm -f "$temporary_jar"
    return 1
  fi

  if service_is_active knot-backend-blue.service; then
    if ! systemctl stop knot-backend-blue.service >/dev/null 2>&1; then
      if service_is_active knot-backend-blue.service \
        && wait_for_health "http://127.0.0.1:8080/actuator/health"; then
        log "Preserved Blue remains healthy; leaving it active"
        return 0
      fi
      return 1
    fi
  fi
  systemctl disable knot-backend-blue.service >/dev/null 2>&1 || true
  if systemctl enable knot-backend.service \
    && systemctl start knot-backend.service \
    && service_is_active knot-backend.service \
    && wait_for_health "http://127.0.0.1:8080/actuator/health"; then
    log "Restored the legacy service from the preserved Blue JAR"
    return 0
  fi

  log "Legacy service recovery failed; trying the preserved Blue slot"
  systemctl stop knot-backend.service >/dev/null 2>&1 || true
  systemctl disable knot-backend.service >/dev/null 2>&1 || true
  systemctl daemon-reload || return 1
  systemctl enable knot-backend-blue.service || return 1
  systemctl start knot-backend-blue.service || return 1
  service_is_active knot-backend-blue.service || return 1
  wait_for_health "http://127.0.0.1:8080/actuator/health"
}

bootstrap_initial_blue() {
  [[ "$ACTIVE_PORT" == "8080" ]] || return 0
  service_is_active knot-backend-blue.service && return 0
  service_is_active knot-backend.service && return 0
  service_is_active knot-backend-green.service && die "Green is active while Nginx points to port 8080"

  ACTIVE_SERVICE="knot-backend-blue.service"
  ACTIVE_JAR="${APP_ROOT}/slots/blue/app.jar"
  [[ -f "$ACTIVE_JAR" ]] || die "Preserved legacy Blue JAR is missing: ${ACTIVE_JAR}"

  if ! install -m 0644 \
    "${SCRIPT_DIR}/systemd/knot-backend-blue.service" \
    "${SYSTEMD_DIR}/knot-backend-blue.service"; then
    restore_legacy_service_from_blue \
      && die "Could not install Blue unit; restored a service on port 8080" \
      || die "Could not install Blue unit or restore a service on port 8080"
  fi
  if ! systemctl daemon-reload; then
    restore_legacy_service_from_blue \
      && die "Could not reload systemd; restored a service on port 8080" \
      || die "Could not reload systemd or restore a service on port 8080"
  fi
  if ! systemctl enable "$ACTIVE_SERVICE"; then
    restore_legacy_service_from_blue \
      && die "Could not enable Blue; restored a service on port 8080" \
      || die "Could not enable Blue or restore a service on port 8080"
  fi
  if ! systemctl start "$ACTIVE_SERVICE"; then
    restore_legacy_service_from_blue \
      && die "Could not start Blue; restored a service on port 8080" \
      || die "Could not start Blue or restore a service on port 8080"
  fi
  if ! service_is_active "$ACTIVE_SERVICE"; then
    restore_legacy_service_from_blue \
      && die "Blue did not stay active; restored a service on port 8080" \
      || die "Blue did not stay active or restore a service on port 8080"
  fi

  active_url="http://127.0.0.1:${ACTIVE_PORT}/actuator/health"
  if ! wait_for_health "$active_url"; then
    restore_legacy_service_from_blue \
      && die "Blue did not become healthy; restored a service on port 8080" \
      || die "Blue did not become healthy or restore a service on port 8080"
  fi
  log "Started preserved Blue before preparing the new slot"
}

select_candidate_slot() {
  case "$ACTIVE_PORT" in
    8080)
      CANDIDATE_PORT=8081
      CANDIDATE_SLOT=green
      CANDIDATE_SERVICE=knot-backend-green.service
      ;;
    8081)
      CANDIDATE_PORT=8080
      CANDIDATE_SLOT=blue
      CANDIDATE_SERVICE=knot-backend-blue.service
      ;;
  esac
}

assert_numeric_settings() {
  local setting_name
  local setting_value

  for setting_name in \
    HEALTH_ATTEMPTS \
    HEALTH_INTERVAL_SECONDS \
    CURL_TIMEOUT_SECONDS \
    DRAIN_TIMEOUT_SECONDS \
    DRAIN_POLL_INTERVAL_SECONDS; do
    setting_value="${!setting_name}"
    [[ "$setting_value" =~ ^[0-9]+$ ]] || die "${setting_name} must be a non-negative integer"
  done
  [[ "$HEALTH_ATTEMPTS" -gt 0 ]] || die "HEALTH_ATTEMPTS must be greater than zero"
  [[ "$DRAIN_POLL_INTERVAL_SECONDS" -gt 0 ]] || die "DRAIN_POLL_INTERVAL_SECONDS must be greater than zero"
}

trap on_exit EXIT

[[ "$DEPLOYMENT_GROUP_NAME" == "$EXPECTED_DEPLOYMENT_GROUP" ]] \
  || die "Refusing deployment group: ${DEPLOYMENT_GROUP_NAME:-<missing>}"
[[ "$DEPLOYMENT_ID" =~ ^d-[A-Za-z0-9]+$ ]] || die "Missing or invalid CodeDeploy deployment ID"
assert_numeric_settings
[[ -f "$INCOMING_JAR" ]] || die "Revision app.jar is missing"
[[ -f "${SCRIPT_DIR}/systemd/knot-backend-blue.service" ]] || die "Blue systemd unit is missing from the revision"
[[ -f "${SCRIPT_DIR}/systemd/knot-backend-green.service" ]] || die "Green systemd unit is missing from the revision"
[[ -f "$METRICS_NGINX_SOURCE" ]] || die "Loopback metrics Nginx config is missing from the revision"
if [[ "$BLUEGREEN_PROFILE" == "prod" ]]; then
  [[ -r "$PROD_ORIGIN_CA_CERT" ]] || die "Prod Cloudflare Origin RSA CA root is missing from the revision"
fi
[[ -r "$ALLOY_CONFIG" ]] || die "Alloy config is unreadable: ${ALLOY_CONFIG}"

mkdir -p "$STATE_ROOT" "$(dirname "$LOCK_FILE")"
exec 9>"$LOCK_FILE"
flock -n 9 || die "Another Blue-Green deployment is already running"
[[ ! -e "$ROUTE_UNCERTAIN_MARKER" ]] \
  || die "A previous route rollback is uncertain; inspect Nginx and clear ${ROUTE_UNCERTAIN_MARKER} after recovery"

ACTIVE_PORT="$(read_upstream_port)"
bootstrap_initial_blue
select_active_service
select_candidate_slot
[[ -f "$ACTIVE_JAR" ]] || die "Active JAR is missing: ${ACTIVE_JAR}"
service_is_active "$ACTIVE_SERVICE" || die "Active service is no longer active"

active_url="http://127.0.0.1:${ACTIVE_PORT}/actuator/health"
wait_for_health "$active_url" || die "Active Blue health check failed before deployment"
if [[ "$BLUEGREEN_PROFILE" == "prod" ]]; then
  wait_for_nginx_health || die "Current Prod Nginx health check failed before deployment; route was not changed"
  log "Current Prod Nginx returned 200 UP before candidate startup"
fi

active_migrations="$(migration_files "$ACTIVE_JAR")" || die "Could not read migration files from the active JAR"
candidate_migrations="$(migration_files "$INCOMING_JAR")" || die "Could not read migration files from the candidate JAR"
[[ "$active_migrations" == "$candidate_migrations" ]] \
  || die "Flyway migration file set changed; deploy only after Blue-Green schema compatibility is verified"

candidate_unit="${SCRIPT_DIR}/systemd/${CANDIDATE_SERVICE}"
candidate_jar="${APP_ROOT}/slots/${CANDIDATE_SLOT}/app.jar"
candidate_directory="${APP_ROOT}/slots/${CANDIDATE_SLOT}"
if service_is_active "$CANDIDATE_SERVICE"; then
  candidate_connection_count="$(established_connection_count "$CANDIDATE_PORT")" \
    || die "Could not inspect existing candidate connections"
  [[ "$candidate_connection_count" == "0" ]] \
    || die "Inactive slot ${CANDIDATE_SLOT} still has established connections; refusing to replace it"
  systemctl stop "$CANDIDATE_SERVICE" || die "Could not stop the inactive candidate service"
fi

ACTIVE_WAS_ENABLED=false
CANDIDATE_WAS_ENABLED=false
service_is_enabled "$ACTIVE_SERVICE" && ACTIVE_WAS_ENABLED=true
service_is_enabled "$CANDIDATE_SERVICE" && CANDIDATE_WAS_ENABLED=true
SYSTEMD_STATE_CAPTURED=true

install -d -o ubuntu -g ubuntu -m 0755 "$candidate_directory"
install -m 0500 "$INCOMING_JAR" "$candidate_jar"
chown ubuntu:ubuntu "$candidate_jar"
install -m 0644 "$candidate_unit" "${SYSTEMD_DIR}/${CANDIDATE_SERVICE}"
systemctl daemon-reload

TRANSACTION_DIR="$(mktemp -d "${STATE_ROOT}/deployment-${DEPLOYMENT_ID}.XXXXXX")"
PREVIOUS_NGINX_CONFIG="${TRANSACTION_DIR}/nginx.conf"
PREVIOUS_NGINX_METRICS_CONFIG="${TRANSACTION_DIR}/nginx-metrics.conf"
cp -p "$NGINX_CONFIG" "$PREVIOUS_NGINX_CONFIG"
if [[ -f "$NGINX_METRICS_CONFIG" ]]; then
  cp -p "$NGINX_METRICS_CONFIG" "$PREVIOUS_NGINX_METRICS_CONFIG"
  NGINX_METRICS_CONFIG_WAS_PRESENT=true
fi

CANDIDATE_STARTED=true
systemctl start "$CANDIDATE_SERVICE" || die "Could not start ${CANDIDATE_SERVICE}"
candidate_url="http://127.0.0.1:${CANDIDATE_PORT}/actuator/health"
wait_for_health "$candidate_url" || die "Candidate health check did not return 200 UP"
log "Candidate ${CANDIDATE_SLOT} returned 200 UP on port ${CANDIDATE_PORT}"

prepare_monitoring_route || die "Could not prepare the stable Alloy metrics route"

route_temp="$(mktemp "${NGINX_CONFIG}.bluegreen.XXXXXX")"
sed "s#proxy_pass http://127.0.0.1:${ACTIVE_PORT};#proxy_pass http://127.0.0.1:${CANDIDATE_PORT};#" \
  "$NGINX_CONFIG" > "$route_temp"
chmod 0644 "$route_temp"
mv -f "$route_temp" "$NGINX_CONFIG"
write_metrics_route "$CANDIDATE_PORT" || die "Could not switch the Alloy metrics route to the candidate"
nginx -t || die "Nginx validation failed after candidate route was written"
nginx -s reload || die "Nginx reload failed after candidate route was written"
[[ "$(read_upstream_port)" == "$CANDIDATE_PORT" ]] || die "Nginx route did not change to the candidate port"
[[ "$(read_metrics_upstream_port)" == "$CANDIDATE_PORT" ]] || die "Alloy metrics route did not change to the candidate port"
wait_for_nginx_health || die "${PROFILE_LABEL} Nginx health check did not return 200 UP after switching"
log "${PROFILE_LABEL} Nginx returned 200 UP through port ${CANDIDATE_PORT}"
wait_for_metrics_endpoint || die "Alloy metrics proxy did not return JVM metrics through port ${CANDIDATE_PORT}"
log "Alloy metrics proxy returned JVM metrics through port ${CANDIDATE_PORT}"

wait_for_connections_to_drain "$ACTIVE_PORT" "$ACTIVE_SERVICE" \
  || die "Previous Blue connections did not drain; route will be restored"

systemctl enable "$CANDIDATE_SERVICE" || die "Could not enable ${CANDIDATE_SERVICE} for reboot"
if [[ "$ACTIVE_WAS_ENABLED" == true ]]; then
  systemctl disable "$ACTIVE_SERVICE" || die "Could not disable previous service ${ACTIVE_SERVICE}"
fi

if ! systemctl stop "$ACTIVE_SERVICE"; then
  service_is_active "$ACTIVE_SERVICE" && die "Could not gracefully stop ${ACTIVE_SERVICE}"
fi
service_is_active "$ACTIVE_SERVICE" && die "Previous service ${ACTIVE_SERVICE} remained active after stop"

log "Deployment ${DEPLOYMENT_ID} route is active; removing the saved Nginx config"
rm -rf -- "$TRANSACTION_DIR"
COMMITTED=true
log "Deployment ${DEPLOYMENT_ID} succeeded: port ${CANDIDATE_PORT} is active; ${ACTIVE_SERVICE} drained and stopped" || true
