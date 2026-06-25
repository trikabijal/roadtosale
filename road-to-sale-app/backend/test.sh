#!/usr/bin/env bash
# Run the Core API test suite (uses zonky embedded Postgres — no Docker needed).
set -euo pipefail
cd "$(dirname "$0")"
mvn -q test
