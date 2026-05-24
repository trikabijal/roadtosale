#!/usr/bin/env bash
# ============================================================================
# Refresh dev/ci-data/baseline.sql.gz from the local smartcomply DB.
# ----------------------------------------------------------------------------
# Run after schema migrations or bulk seed changes. The dump excludes the
# large rolling-log + photo + AI tables so the file stays under 1 MB and
# checkable into git.
#
# Usage: ./dev/scripts/refresh-ci-baseline.sh
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="${SCRIPT_DIR}/../ci-data/baseline.sql.gz"
SRC_DB="${SRC_DB:-smartcomply}"
SRC_HOST="${SRC_HOST:-localhost}"
SRC_PORT="${SRC_PORT:-5432}"
SRC_USER="${SRC_USER:-${USER}}"

# pg_dump major version must match the server. macOS Homebrew installs
# them under /opt/homebrew/Cellar/postgresql@<v>/<v>.x/bin/pg_dump.
# Pick the binary whose version matches `SELECT server_version` of SRC_DB.
SERVER_MAJOR=$(psql -h "$SRC_HOST" -p "$SRC_PORT" -U "$SRC_USER" -d "$SRC_DB" -tA \
                   -c "SHOW server_version" | cut -d. -f1)
PG_DUMP="pg_dump"
for candidate in \
    "/opt/homebrew/Cellar/postgresql@${SERVER_MAJOR}"/*/bin/pg_dump \
    "/usr/local/Cellar/postgresql@${SERVER_MAJOR}"/*/bin/pg_dump \
    "$(command -v pg_dump || true)"; do
  if [ -x "$candidate" ]; then PG_DUMP="$candidate"; break; fi
done

echo "── dump $SRC_DB → $OUT using $PG_DUMP ──"
# --no-table-access-method: pg_dump 15+ emits a top-of-file
# `SET default_table_access_method = heap;` that older Postgres
# servers (e.g. UAT Postgres on 172.31.0.157) reject with
# "unrecognized configuration parameter". The SET is purely
# informational on local — drop it from the dump so restores onto
# older servers don't fail at line 1.
"$PG_DUMP" -h "$SRC_HOST" -p "$SRC_PORT" -U "$SRC_USER" -d "$SRC_DB" \
  --no-owner --no-acl \
  --no-table-access-method \
  --exclude-table-data=ai_assessments \
  --exclude-table-data=user_checksheet_answer_files \
  --exclude-table-data=api_history \
  | gzip > "$OUT"
echo "✓ wrote $OUT ($(du -h "$OUT" | cut -f1))"
