FROM amazoncorretto:17.0.7-alpine

# Add app user
ARG APPLICATION_USER=appuser
RUN adduser --no-create-home -u 1000 -D $APPLICATION_USER

# Configure working directory
RUN mkdir /app && \
    chown -R $APPLICATION_USER /app

USER 1000

COPY ./target/smartcomply-0.0.1-SNAPSHOT.war /app/smartcomply-0.0.1-SNAPSHOT.war

# Tenant overlay folder — the JVM reads tenants/<tenant.id>/config/ at
# startup (see TenantInsightsConfig + docs/tenant-overlay.md). Without
# this COPY, app startup fails: "Tenant _base is incomplete: missing
# /app/tenants/_base/config/insights.yaml".
COPY ./tenants /app/tenants

WORKDIR /app

EXPOSE 8089
ENTRYPOINT [ "java", "-jar", "/app/smartcomply-0.0.1-SNAPSHOT.war" ]

