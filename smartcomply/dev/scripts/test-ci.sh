#!/usr/bin/env bash
# ============================================================================
# CI test runner — usable locally and on the GitLab runner.
# ----------------------------------------------------------------------------
# Lifecycle:
#   1. DROP DATABASE IF EXISTS <CI_DB_NAME>;
#   2. CREATE DATABASE <CI_DB_NAME>;
#   3. Restore dev/ci-data/baseline.sql.gz (~1 MB gzipped — schema +
#      foundational data, photo/AI/log tables excluded).
#   4. Apply dev/ci-data/ci-admin-seed.sql (adds the ci_admin SUPER_ADMIN).
#   5. Run the backend JUnit E2E suite with -Dspring.profiles.active=ci.
#   6. On pass → DROP DATABASE <CI_DB_NAME>.
#      On fail → leave the DB intact for debug. Next run drops it.
#
# Why a dump instead of "boot from empty + V200.001 seed": this codebase
# uses Hibernate for schema creation (no V1.0__create_full_schema.sql),
# and the role / permission / department seeds live outside Flyway too
# (loaded historically from a production dump). Recreating them from
# scratch via raw SQL hits Hibernate-vs-V1.x column mismatches that take
# significant iteration to reconcile. A periodic dump-and-restore avoids
# all of that and tests against realistic state.
#
# When to refresh dev/ci-data/baseline.sql.gz:
#   - After any schema migration that runs in local
#   - After bulk seed changes
#   Regenerate: ./dev/scripts/refresh-ci-baseline.sh
#
# Defaults to localhost / current user / no password (matches the dev
# laptop profile). CI overrides via env vars to point at the UAT Postgres
# server.
#
# Usage:
#   ./dev/scripts/test-ci.sh                                # local, full suite
#   ./dev/scripts/test-ci.sh -Dtest=AuditFlowE2ETest        # local, one class
#   CI_DB_HOST=172.31.0.157 CI_DB_USERNAME=dilipv \
#     CI_DB_PASSWORD=… ./dev/scripts/test-ci.sh             # against UAT Postgres
#
# Required tools: psql, gunzip, mvn (or ./mvnw), Java 17.
# ============================================================================

set -euo pipefail

# -----------------------------------------------------------------------------
# Config (env vars > defaults). Same names application-ci.properties reads.
# -----------------------------------------------------------------------------
CI_DB_HOST="${CI_DB_HOST:-localhost}"
CI_DB_PORT="${CI_DB_PORT:-5432}"
CI_DB_NAME="${CI_DB_NAME:-smartcomply_ci}"
CI_DB_USERNAME="${CI_DB_USERNAME:-${USER}}"
CI_DB_PASSWORD="${CI_DB_PASSWORD:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASELINE_DUMP="${SCRIPT_DIR}/../ci-data/baseline.sql.gz"
CI_ADMIN_SEED="${SCRIPT_DIR}/../ci-data/ci-admin-seed.sql"

if [ ! -f "$BASELINE_DUMP" ]; then
  echo "❌ baseline dump not found: $BASELINE_DUMP" >&2
  echo "   regenerate via dev/scripts/refresh-ci-baseline.sh" >&2
  exit 2
fi
if [ ! -f "$CI_ADMIN_SEED" ]; then
  echo "❌ ci-admin seed not found: $CI_ADMIN_SEED" >&2
  exit 2
fi

# psql connects to the postgres maintenance DB so it can create/drop the
# target DB. PGPASSWORD avoids the password prompt.
export PGPASSWORD="$CI_DB_PASSWORD"
PSQL_ADMIN=(psql -h "$CI_DB_HOST" -p "$CI_DB_PORT" -U "$CI_DB_USERNAME" -d postgres -v ON_ERROR_STOP=1)
PSQL_CI=(psql -h "$CI_DB_HOST" -p "$CI_DB_PORT" -U "$CI_DB_USERNAME" -d "$CI_DB_NAME" -v ON_ERROR_STOP=1)

# Extra args after `-- ` get appended to the Maven test command.
MVN_TEST_ARGS=("$@")
if [ ${#MVN_TEST_ARGS[@]} -eq 0 ]; then
  MVN_TEST_ARGS=("-Dtest=com.checkSheet.audit.*E2ETest")
fi

# -----------------------------------------------------------------------------
# 1. DROP + CREATE database.
# -----------------------------------------------------------------------------
echo "── reset CI database $CI_DB_NAME @ $CI_DB_HOST:$CI_DB_PORT ──"
"${PSQL_ADMIN[@]}" <<SQL
SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity
 WHERE datname = '$CI_DB_NAME' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS $CI_DB_NAME;
CREATE DATABASE $CI_DB_NAME;
SQL

# -----------------------------------------------------------------------------
# 2. Restore baseline dump.
# -----------------------------------------------------------------------------
echo "── restore baseline ($(du -h "$BASELINE_DUMP" | cut -f1)) ──"
gunzip -c "$BASELINE_DUMP" | "${PSQL_CI[@]}" >/dev/null

# -----------------------------------------------------------------------------
# 3. Add the ci_admin bootstrap user.
# -----------------------------------------------------------------------------
echo "── seed ci_admin SUPER_ADMIN ──"
"${PSQL_CI[@]}" -f "$CI_ADMIN_SEED" >/dev/null

# -----------------------------------------------------------------------------
# 4. Run the test suite. Spring runs against the populated DB.
# -----------------------------------------------------------------------------
echo "── run backend E2E suite (Spring profile = ci) ──"
set +e
MVN_BIN="./mvnw"
if [ ! -x "$MVN_BIN" ]; then MVN_BIN="mvn"; fi

CI_DB_HOST="$CI_DB_HOST" CI_DB_PORT="$CI_DB_PORT" CI_DB_NAME="$CI_DB_NAME" \
CI_DB_USERNAME="$CI_DB_USERNAME" CI_DB_PASSWORD="$CI_DB_PASSWORD" \
"$MVN_BIN" test -Dspring.profiles.active=ci "${MVN_TEST_ARGS[@]}"
TEST_EXIT=$?
set -e

# -----------------------------------------------------------------------------
# 5. On pass → drop. On fail → leave for debug.
# -----------------------------------------------------------------------------
if [ $TEST_EXIT -eq 0 ]; then
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
  echo "  Inspect: psql -h $CI_DB_HOST -p $CI_DB_PORT -U $CI_DB_USERNAME -d $CI_DB_NAME"
  exit $TEST_EXIT
fi
