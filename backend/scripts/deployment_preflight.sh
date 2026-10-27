#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: sudo knot-deploy-preflight <dev|prod> <deployment-archive-directory>\n'
  printf 'Read-only: checks the current Nginx slot and migration review before deployment.\n'
}

if [[ "${1:-}" == --help ]]; then
  usage
  exit 0
fi
[[ "$#" == 2 ]] || { usage >&2; exit 2; }

readonly ENVIRONMENT="$1"
readonly REVISION="$2"
case "$ENVIRONMENT" in
  dev) default_config=/etc/nginx/conf.d/knot-backend-dev.conf ;;
  prod) default_config=/etc/nginx/conf.d/knot-backend.conf ;;
  *) usage >&2; exit 2 ;;
esac

readonly APP_ROOT="${KNOT_PREFLIGHT_APP_ROOT:-/opt/knot-backend}"
readonly NGINX_CONFIG="${KNOT_PREFLIGHT_NGINX_CONFIG:-$default_config}"
readonly REVIEWS="${KNOT_PREFLIGHT_REVIEWS:-/var/lib/knot-backend-deploy/blue-green/migration-reviews}"
readonly CHECKER="${KNOT_PREFLIGHT_CHECKER:-/usr/local/lib/knot-deploy/verify_migration_compatibility.py}"

fail() {
  printf '[deployment-preflight] %s\n' "$*" >&2
  exit 1
}

[[ -r "$NGINX_CONFIG" ]] || fail "Nginx config is unreadable: $NGINX_CONFIG"
matches="$(grep -Ec '^[[:space:]]*proxy_pass http://127\.0\.0\.1:(8080|8081);[[:space:]]*$' "$NGINX_CONFIG" || true)"
[[ "$matches" == 1 ]] || fail "Expected one supported Nginx application upstream"
port="$(sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:(8080|8081);[[:space:]]*$#\1#p' "$NGINX_CONFIG")"

case "$port" in
  8080)
    active_service=knot-backend-blue.service
    active_jar="$APP_ROOT/slots/blue/app.jar"
    if ! systemctl is-active --quiet "$active_service"; then
      active_service=knot-backend.service
      active_jar="$APP_ROOT/app.jar"
    elif systemctl is-active --quiet knot-backend.service; then
      fail "Both legacy and Blue services claim port 8080"
    fi
    ;;
  8081)
    active_service=knot-backend-green.service
    active_jar="$APP_ROOT/slots/green/app.jar"
    ;;
esac
systemctl is-active --quiet "$active_service" || fail "Nginx upstream service is inactive: $active_service"
[[ -f "$REVISION/app.jar" && -f "$active_jar" && -f "$CHECKER" ]] || fail "Active JAR, candidate JAR or installed checker is missing"
if [[ -f "$REVISION/scripts/verify_migration_compatibility.py" ]] \
  && ! cmp -s "$CHECKER" "$REVISION/scripts/verify_migration_compatibility.py"; then
  fail "Candidate checker differs from the installed checker; review the operator tool upgrade first"
fi
printf '[deployment-preflight] environment=%s active_service=%s\n' "$ENVIRONMENT" "$active_service"
printf '[deployment-preflight] active_jar=%s candidate_jar=%s\n' "$active_jar" "$REVISION/app.jar"

# The installed checker is trusted independently of the candidate archive.
# This command never issues approval or changes a service, route, schema or flag.
exec python3 "$CHECKER" --active "$active_jar" --candidate "$REVISION/app.jar" \
  --environment "$ENVIRONMENT" --reviews "$REVIEWS"
