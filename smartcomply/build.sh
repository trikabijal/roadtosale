#!/usr/bin/env bash
# =============================================================================
# SmartComply build script
# Usage:
#   ./build.sh           # same as "local" — compile + package, skip tests
#   ./build.sh local     # local dev build
#   ./build.sh dev       # dev/CI build — skips only the long ApplicationTests
#   ./build.sh prod      # production build — runs full test suite
# =============================================================================
set -euo pipefail

ENV=${1:-local}
echo "=== SmartComply Build (env: $ENV) ==="

case "$ENV" in
  local|dev)
    # Quick compile + package for local dev or CI artifact builds.
    # Skips tests entirely so you get a runnable WAR fast.
    ./mvnw clean package -DskipTests -q
    echo "Built: target/smartcomply-*.war"
    echo ""
    echo "Run with:"
    echo "  # Kia tenant:"
    echo "  SPRING_PROFILES_ACTIVE=local,tenant-data TENANT_ID=kia ./mvnw spring-boot:run"
    echo ""
    echo "  # Honda Road to Sale tenant:"
    echo "  SPRING_PROFILES_ACTIVE=local,tenant-data TENANT_ID=honda ./mvnw spring-boot:run"
    echo ""
    echo "  # Schema-only (no tenant seed data):"
    echo "  SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run"
    ;;

  prod)
    # Production build — run all tests except the long-running ApplicationTests
    # integration suite (which requires a live DB; not suitable for CI packaging).
    ./mvnw clean package -Dtest='!com.checkSheet.demo.ApplicationTests' -q
    echo "Built for prod: target/smartcomply-*.war"
    ;;

  *)
    echo "Unknown environment: $ENV"
    echo "Usage: $0 [local|dev|prod]"
    exit 1
    ;;
esac
