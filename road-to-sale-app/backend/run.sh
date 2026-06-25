#!/usr/bin/env bash
# Run the Core API locally with the `dev` profile on port 8090.
# Requires Postgres reachable via SPRING_DATASOURCE_* (see .env.example).
set -euo pipefail
cd "$(dirname "$0")"
mvn -q spring-boot:run -Dspring-boot.run.profiles=dev
