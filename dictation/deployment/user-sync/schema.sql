-- Our own user table, the system-of-record for app-side user data (usage, billing links, etc.).
-- Keyed by the Keycloak subject id (`sub`) — stable, opaque, survives email changes. Populated by
-- sync.js from the Keycloak Admin API; app-specific columns can be added over time without touching
-- Keycloak. Lives in the same Postgres as Keycloak but is fully isolated (our table, our schema).

CREATE TABLE IF NOT EXISTS app_users (
    keycloak_sub        text PRIMARY KEY,
    email               text,
    name                text,
    enabled             boolean,
    paid                boolean      NOT NULL DEFAULT false,
    keycloak_created_at timestamptz,
    first_seen_at       timestamptz  NOT NULL DEFAULT now(),  -- first time WE recorded them
    last_synced_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS app_users_email_idx ON app_users (email);
CREATE INDEX IF NOT EXISTS app_users_paid_idx  ON app_users (paid);
