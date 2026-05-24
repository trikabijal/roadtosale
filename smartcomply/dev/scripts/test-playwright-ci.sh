#!/usr/bin/env bash
# ============================================================================
# Playwright CI runner — brings up backend + ng-serve against an isolated
# CI database, runs the Playwright e2e suite, drops everything on success.
# ----------------------------------------------------------------------------
# Lifecycle (mirrors test-ci.sh's DB lifecycle, adds long-running services):
#   1. DROP+CREATE <CI_DB_NAME>, restore baseline, seed ci_admin.
#   2. Start the smartcomply backend WAR with spring.profiles.active=ci on
#      port :8089 (or CI_BACKEND_PORT). Wait for /api/audit/list to answer.
#   3. Start ng-serve in the sibling smartcomply-angular repo on :4200.
#   4. Run `playwright test qc/src --workers=4` from smartcomply-angular,
#      with PLAYWRIGHT_API_URL / PLAYWRIGHT_FE_URL / PLAYWRIGHT_BOOTSTRAP_USER
#      exported so the suite hits OUR backend + uses ci_admin.
#   5. Stop backend + ng-serve.
#   6. On pass → DROP DATABASE. On fail → leave for debug.
#
# Requires: the smartcomply WAR built (./mvnw package -DskipTests) and the
# sibling repo at ../smartcomply-angular with node_modules installed.
#
# Usage:
#   ./dev/scripts/test-playwright-ci.sh                # full suite
#   ./dev/scripts/test-playwright-ci.sh qc/src/roles   # subset
# ============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# Config
# -----------------------------------------------------------------------------
CI_DB_HOST="${CI_DB_HOST:-localhost}"
CI_DB_PORT="${CI_DB_PORT:-5432}"
CI_DB_NAME="${CI_DB_NAME:-smartcomply_ci}"
CI_DB_USERNAME="${CI_DB_USERNAME:-${USER}}"
CI_DB_PASSWORD="${CI_DB_PASSWORD:-}"
# Export so the `npx playwright test` subprocess (which doesn't get
# the inline VAR=val treatment the java + ng serve invocations do)
# can read them. qc/fixtures/api-builders.ts::loadQuestionIdsForChecksheet
# shells out to psql with these — without the export it sees the
# laptop default `smartcomply` DB and CI sees nothing.
export CI_DB_HOST CI_DB_PORT CI_DB_NAME CI_DB_USERNAME CI_DB_PASSWORD
CI_BACKEND_PORT="${CI_BACKEND_PORT:-8089}"
CI_FRONTEND_PORT="${CI_FRONTEND_PORT:-4200}"
CI_PLAYWRIGHT_WORKERS="${CI_PLAYWRIGHT_WORKERS:-4}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FRONTEND_ROOT="${FRONTEND_ROOT:-${BACKEND_ROOT}/../smartcomply-angular}"
BASELINE_DUMP="${BACKEND_ROOT}/dev/ci-data/baseline.sql.gz"
CI_ADMIN_SEED="${BACKEND_ROOT}/dev/ci-data/ci-admin-seed.sql"
WAR_PATH="${BACKEND_ROOT}/target/smartcomply-0.0.1-SNAPSHOT.war"

PLAYWRIGHT_TARGET="${1:-qc/src}"

export PGPASSWORD="$CI_DB_PASSWORD"
PSQL_ADMIN=(psql -h "$CI_DB_HOST" -p "$CI_DB_PORT" -U "$CI_DB_USERNAME" -d postgres -v ON_ERROR_STOP=1)
PSQL_CI=(psql -h "$CI_DB_HOST" -p "$CI_DB_PORT" -U "$CI_DB_USERNAME" -d "$CI_DB_NAME" -v ON_ERROR_STOP=1)

# -----------------------------------------------------------------------------
# Preflight
# -----------------------------------------------------------------------------
[ -f "$BASELINE_DUMP" ]    || { echo "❌ baseline dump missing: $BASELINE_DUMP"; exit 2; }
[ -f "$CI_ADMIN_SEED" ]    || { echo "❌ ci-admin seed missing: $CI_ADMIN_SEED"; exit 2; }
[ -f "$WAR_PATH" ]         || { echo "❌ backend WAR missing: $WAR_PATH (run ./mvnw package -DskipTests)"; exit 2; }
[ -d "$FRONTEND_ROOT" ]    || { echo "❌ frontend repo missing at: $FRONTEND_ROOT (set FRONTEND_ROOT env var)"; exit 2; }
[ -d "$FRONTEND_ROOT/node_modules" ] || { echo "❌ frontend node_modules missing — run 'npm ci' in $FRONTEND_ROOT"; exit 2; }

# -----------------------------------------------------------------------------
# Bookkeeping for service teardown
# -----------------------------------------------------------------------------
BACKEND_PID=""
NGSERVE_PID=""

cleanup_services() {
  if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "── stop backend pid=$BACKEND_PID ──"
    kill "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  if [ -n "$NGSERVE_PID" ] && kill -0 "$NGSERVE_PID" 2>/dev/null; then
    echo "── stop ng-serve pid=$NGSERVE_PID ──"
    kill "$NGSERVE_PID" 2>/dev/null || true
    wait "$NGSERVE_PID" 2>/dev/null || true
  fi
}
trap cleanup_services EXIT

wait_for_url() {
  local url="$1"
  local label="$2"
  local log_path="${3:-}"
  # 300s deadline because CI runners + UAT Postgres network round-trip
  # for Hibernate ddl-auto=update can easily push Spring startup
  # over the original 120s budget (local laptop boots in ~12s, the
  # GitLab runner has been observed at 90s+ steady-state).
  local deadline=$((SECONDS + 300))
  until curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null | grep -qE "^(200|301|302|401|403)$"; do
    if [ $SECONDS -gt $deadline ]; then
      echo "❌ timed out waiting for $label ($url) after ${SECONDS}s"
      if [ -n "$log_path" ] && [ -f "$log_path" ]; then
        echo "── last 80 lines of $log_path ──"
        tail -80 "$log_path" || true
      fi
      exit 3
    fi
    sleep 2
  done
  echo "✓ $label ready"
}

# -----------------------------------------------------------------------------
# 1. CI DB lifecycle (reset + restore + seed)
# -----------------------------------------------------------------------------
echo "── reset CI database $CI_DB_NAME @ $CI_DB_HOST:$CI_DB_PORT ──"
"${PSQL_ADMIN[@]}" <<SQL
SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity
 WHERE datname = '$CI_DB_NAME' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS $CI_DB_NAME;
CREATE DATABASE $CI_DB_NAME;
SQL

echo "── restore baseline ($(du -h "$BASELINE_DUMP" | cut -f1)) ──"
gunzip -c "$BASELINE_DUMP" | "${PSQL_CI[@]}" >/dev/null
"${PSQL_CI[@]}" -f "$CI_ADMIN_SEED" >/dev/null

# -----------------------------------------------------------------------------
# 2. Start backend on the CI DB
# -----------------------------------------------------------------------------
echo "── start backend WAR (profile=ci, port=$CI_BACKEND_PORT) ──"
BACKEND_LOG="/tmp/smartcomply-ci-backend.log"
# Kill any stray process already on the port.
for pid in $(lsof -nP -iTCP:$CI_BACKEND_PORT -sTCP:LISTEN -t 2>/dev/null); do
  kill -9 "$pid" 2>/dev/null || true
done

# TENANT_CONFIG_PATH must be an absolute path to the backend repo's
# tenants/ dir — the backend's TenantInsightsConfig defaults to
# `./tenants` (relative to cwd), but the script runs from the
# smartcomply-angular workspace where there is no tenants/ dir.
CI_DB_HOST="$CI_DB_HOST" CI_DB_PORT="$CI_DB_PORT" CI_DB_NAME="$CI_DB_NAME" \
CI_DB_USERNAME="$CI_DB_USERNAME" CI_DB_PASSWORD="$CI_DB_PASSWORD" \
TENANT_CONFIG_PATH="$BACKEND_ROOT/tenants" \
nohup java -jar "$WAR_PATH" \
  --spring.profiles.active=ci \
  --server.port="$CI_BACKEND_PORT" > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
echo "  pid=$BACKEND_PID, log=$BACKEND_LOG"
wait_for_url "http://localhost:$CI_BACKEND_PORT/api/audit/list" "backend" "$BACKEND_LOG"

# -----------------------------------------------------------------------------
# 3. Start ng-serve
# -----------------------------------------------------------------------------
echo "── start ng-serve (port=$CI_FRONTEND_PORT) ──"
NGSERVE_LOG="/tmp/smartcomply-ci-ngserve.log"
for pid in $(lsof -nP -iTCP:$CI_FRONTEND_PORT -sTCP:LISTEN -t 2>/dev/null); do
  kill -9 "$pid" 2>/dev/null || true
done

cd "$FRONTEND_ROOT"
nohup npx ng serve --host 0.0.0.0 --port "$CI_FRONTEND_PORT" > "$NGSERVE_LOG" 2>&1 &
NGSERVE_PID=$!
echo "  pid=$NGSERVE_PID, log=$NGSERVE_LOG"
wait_for_url "http://localhost:$CI_FRONTEND_PORT" "ng-serve" "$NGSERVE_LOG"

# -----------------------------------------------------------------------------
# 4. Run Playwright
# -----------------------------------------------------------------------------
echo "── playwright test $PLAYWRIGHT_TARGET (workers=$CI_PLAYWRIGHT_WORKERS) ──"
set +e
PLAYWRIGHT_API_URL="http://localhost:$CI_BACKEND_PORT/api" \
PLAYWRIGHT_FE_URL="http://localhost:$CI_FRONTEND_PORT" \
PLAYWRIGHT_BOOTSTRAP_USER="ci_admin" \
CI=1 \
npx playwright test "$PLAYWRIGHT_TARGET" --workers="$CI_PLAYWRIGHT_WORKERS" --reporter=list
TEST_EXIT=$?
set -e
cd "$BACKEND_ROOT"

# -----------------------------------------------------------------------------
# 5. Service teardown happens in EXIT trap. DB drop is success-only.
# -----------------------------------------------------------------------------
if [ $TEST_EXIT -eq 0 ]; then
  cleanup_services
  trap - EXIT
  echo "── tests passed, dropping $CI_DB_NAME ──"
  "${PSQL_ADMIN[@]}" <<SQL
SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity
 WHERE datname = '$CI_DB_NAME' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS $CI_DB_NAME;
SQL
  echo "✓ done"
else
  echo "✗ tests failed (exit $TEST_EXIT)."
  echo "  Leaving database '$CI_DB_NAME' for debug. Next run will drop+recreate."
  echo "  Logs:"
  echo "    backend  : $BACKEND_LOG"
  echo "    ng-serve : $NGSERVE_LOG"
  echo "  Inspect DB: psql -h $CI_DB_HOST -p $CI_DB_PORT -U $CI_DB_USERNAME -d $CI_DB_NAME"
  exit $TEST_EXIT
fi
