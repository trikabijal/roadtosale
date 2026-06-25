#!/usr/bin/env bash
# Build the Core API jar (skips tests; use ./test.sh for tests).
set -euo pipefail
cd "$(dirname "$0")"
mvn -q clean package -DskipTests
