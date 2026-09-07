#!/usr/bin/env bash
# Walk the operations UI in a real browser against the built images.
#
#   scripts/verify-admin-ui.sh           bring the stack up (admin-ui/e2e/docker-compose.yml),
#                                        run the Playwright walk, leave the stack running
#   scripts/verify-admin-ui.sh --down    remove it, including the database
#
# Images: APP_IMAGE (default ai-customer-service-java:local, `docker build -t ... .`) and
# ADMIN_UI_IMAGE (default ai-customer-service-java-admin-ui:local, `docker build admin-ui`);
# nothing is built here, so CI's cached build is the one walked. The walk needs the Playwright
# package (admin-ui: npm ci) and a Chromium (npx playwright install --with-deps chromium).
#
# What it proves that the unit tests and the API tests cannot: the bundle nginx serves, the
# proxy in front of the service, the session and CSRF cookies crossing it, and the pages as a
# person sees them. No turn is sent, so no model key is needed.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
COMPOSE=(docker compose -f "$ROOT/admin-ui/e2e/docker-compose.yml")
export ADMIN_SEED_USERNAME=${ADMIN_SEED_USERNAME:-ops}
export ADMIN_SEED_PASSWORD=${ADMIN_SEED_PASSWORD:-ops-walk-password-2026}
export DEFAULT_TENANT_API_KEY=${DEFAULT_TENANT_API_KEY:-cs_$(openssl rand -hex 4)$(openssl rand -hex 16)}
export E2E_UI_PORT=${E2E_UI_PORT:-18084}

if [[ ${1:-} == --down ]]; then
  "${COMPOSE[@]}" down -v --remove-orphans
  exit 0
fi

printf '\n\033[1m== up\033[0m\n'
"${COMPOSE[@]}" up -d --wait --wait-timeout 300
for _ in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$E2E_UI_PORT/admin/api/me" || echo 000)
  [[ $code == 401 ]] && break
  sleep 2
done
[[ $code == 401 ]] || { echo "the UI did not answer 401 on /admin/api/me (got $code)"; exit 1; }

printf '\n\033[1m== walk\033[0m\n'
cd "$ROOT/admin-ui"
E2E_BASE_URL="http://localhost:$E2E_UI_PORT" E2E_ADMIN_USERNAME=$ADMIN_SEED_USERNAME E2E_ADMIN_PASSWORD=$ADMIN_SEED_PASSWORD npm run e2e
printf '\n  stack left running on http://localhost:%s; %s --down to remove it\n' "$E2E_UI_PORT" "$0"
