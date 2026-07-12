# Just Talk licensing / identity server — Keycloak on Railway.
#
# Two-stage build: the "builder" bakes the Postgres DB vendor and health/metrics into an
# optimized image (so runtime start is fast and needs no re-augmentation), then the final
# stage copies that build and drops in the realm to import on first boot.
#
# Runtime configuration (KC_HOSTNAME, DB credentials, bootstrap admin) is supplied by Railway
# env vars — see deployment/keycloak/README.md. Nothing secret lives in this file.

# ---- Stage 1: optimized build ------------------------------------------------
FROM quay.io/keycloak/keycloak:26.3 AS builder

# Baked-in, non-secret build options. DB *vendor* is fixed at build time; the DB URL and
# credentials are provided at runtime so this image carries no secrets.
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true

RUN /opt/keycloak/bin/kc.sh build

# ---- Stage 2: runtime --------------------------------------------------------
FROM quay.io/keycloak/keycloak:26.3

COPY --from=builder /opt/keycloak/ /opt/keycloak/

# Realm imported on first boot (idempotent: existing realm is skipped). Placeholders inside
# are substituted from env at import time — see the realm JSON and README.
COPY justtalk-realm.json /opt/keycloak/data/import/justtalk-realm.json

# Railway routes the public HTTPS domain to this port; keep it fixed and set the Railway
# service's target port to 8080.
ENV KC_HTTP_PORT=8080

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
# --optimized: use the pre-built config from stage 1. --import-realm: load data/import on boot.
CMD ["start", "--optimized", "--import-realm"]
