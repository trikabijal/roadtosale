-- Road to Sale — Core API baseline schema
-- Multi-tenant: every relevant table carries dealership_id (enforced server-side
-- from the authenticated token, never trusted from the request body).

-- gen_random_uuid() for UUID primary keys
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ─── dealerships (tenant) ────────────────────────────────────────────────────
CREATE TABLE dealerships (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ─── users (salespeople and future roles) ────────────────────────────────────
-- username is the GLOBAL login identity: login looks a user up by username alone
-- (no dealership in the credentials), so usernames must be unique across all
-- tenants — otherwise login would be ambiguous/broken (W5). dealership_id is
-- still the tenant FK (kept + indexed), it just isn't part of the login key.
CREATE TABLE users (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    dealership_id  uuid        NOT NULL REFERENCES dealerships (id),
    username       text        NOT NULL,
    password_hash  text        NOT NULL,
    name           text        NOT NULL,
    roles          text[]      NOT NULL DEFAULT '{}',
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE INDEX idx_users_dealership ON users (dealership_id);

-- ─── sessions (core entity) ──────────────────────────────────────────────────
CREATE TABLE sessions (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    dealership_id    uuid        NOT NULL REFERENCES dealerships (id),
    user_id          uuid        NOT NULL REFERENCES users (id),
    type             text        NOT NULL,
    status           text        NOT NULL DEFAULT 'ACTIVE',
    checksheet_code  text        NOT NULL,
    context          jsonb,
    transcript       text,
    started_at       timestamptz NOT NULL DEFAULT now(),
    ended_at         timestamptz,
    CONSTRAINT chk_sessions_type   CHECK (type IN ('LIVE', 'MOCK')),
    CONSTRAINT chk_sessions_status CHECK (status IN ('ACTIVE', 'COMPLETED'))
);

CREATE INDEX idx_sessions_dealership_user ON sessions (dealership_id, user_id);

-- ─── session_events (append-only log of detected cues) ───────────────────────
CREATE TABLE session_events (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       uuid          NOT NULL REFERENCES sessions (id),
    question_id      text          NOT NULL,
    step_no          int           NOT NULL,
    detected_at      timestamptz   NOT NULL,
    confidence       numeric(4,3)  NOT NULL,
    transcript_span  text,
    source           text          NOT NULL,
    cue_id           text          NOT NULL,
    CONSTRAINT chk_session_events_source     CHECK (source IN ('feature', 'workflow')),
    CONSTRAINT chk_session_events_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT chk_session_events_step_no    CHECK (step_no >= 1),
    CONSTRAINT uq_session_events_session_cue UNIQUE (session_id, cue_id)
);

CREATE INDEX idx_session_events_session ON session_events (session_id);

-- ─── photos (trade-in photos) ────────────────────────────────────────────────
CREATE TABLE photos (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id    uuid        NOT NULL REFERENCES sessions (id),
    slot          text        NOT NULL,
    storage_path  text        NOT NULL,
    mime_type     text        NOT NULL,
    uploaded_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_photos_slot CHECK (
        slot IN ('front_left', 'front_right', 'rear_left', 'rear_right',
                 'interior', 'odometer', 'vin')
    )
);

CREATE INDEX idx_photos_session ON photos (session_id);
