-- ============================================================================
-- V1.34: Road to Sale voice-detection extension fields
-- ----------------------------------------------------------------------------
-- These columns extend user_checksheet_answers with voice-engine data.
-- All columns are nullable and prefixed rts_ for easy identification.
--
-- Proposal-back policy:
--   Once the Road to Sale pilot proves the schema (target: 3-month field run,
--   ≥50 inspections with voice data), propose rts_* columns and
--   rts_trade_photos to the upstream SmartComply team as a contrib.
--   The rts_ prefix keeps them clearly segregated from core columns until
--   that merge happens, making a clean diff trivial.
-- ============================================================================


-- ── 1. voice-detection extension columns on user_checksheet_answers ─────────

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                   AND table_name = 'user_checksheet_answers'
                   AND column_name = 'rts_cue_id') THEN
        ALTER TABLE user_checksheet_answers
            ADD COLUMN rts_cue_id VARCHAR(255);
        COMMENT ON COLUMN user_checksheet_answers.rts_cue_id
            IS 'Voice-engine cue atom ID (e.g. ''workflow.hospitality_offer'')';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                   AND table_name = 'user_checksheet_answers'
                   AND column_name = 'rts_cue_source') THEN
        ALTER TABLE user_checksheet_answers
            ADD COLUMN rts_cue_source VARCHAR(50);
        COMMENT ON COLUMN user_checksheet_answers.rts_cue_source
            IS 'Origin of the voice cue: ''feature'' or ''workflow''';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                   AND table_name = 'user_checksheet_answers'
                   AND column_name = 'rts_transcript_snippet') THEN
        ALTER TABLE user_checksheet_answers
            ADD COLUMN rts_transcript_snippet TEXT;
        COMMENT ON COLUMN user_checksheet_answers.rts_transcript_snippet
            IS 'Short excerpt from the speech transcript that triggered detection (≤200 chars)';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                   AND table_name = 'user_checksheet_answers'
                   AND column_name = 'rts_cue_confidence') THEN
        ALTER TABLE user_checksheet_answers
            ADD COLUMN rts_cue_confidence NUMERIC(4,3);
        COMMENT ON COLUMN user_checksheet_answers.rts_cue_confidence
            IS 'Voice-engine confidence score 0.000–1.000; NULL = no voice data';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                   AND table_name = 'user_checksheet_answers'
                   AND column_name = 'rts_voice_auto_completed') THEN
        ALTER TABLE user_checksheet_answers
            ADD COLUMN rts_voice_auto_completed BOOLEAN DEFAULT FALSE;
        COMMENT ON COLUMN user_checksheet_answers.rts_voice_auto_completed
            IS 'TRUE = answer was set by voice detection; FALSE = manual tap or no voice data';
    END IF;
END $$;


-- ── 2. rts_trade_photos — trade-in vehicle evidence photos ──────────────────
-- One row per (inspection, slot). Slot is the camera angle:
--   front_left | front_right | rear_left | rear_right | interior | odometer | vin
--
-- file_key   = the S3 object key (or local filesystem path in dev)
-- file_url   = a presigned URL or static URL; regenerated on read —
--              do NOT cache this value longer than its TTL.

CREATE TABLE IF NOT EXISTS rts_trade_photos (
    id              BIGSERIAL PRIMARY KEY,
    inspection_id   BIGINT        NOT NULL REFERENCES inspections(id),
    slot            VARCHAR(50)   NOT NULL,
    file_key        VARCHAR(500),
    file_url        TEXT,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_rts_trade_photos_inspection_slot UNIQUE (inspection_id, slot),
    CONSTRAINT chk_rts_trade_photos_slot CHECK (
        slot IN ('front_left', 'front_right', 'rear_left', 'rear_right',
                 'interior', 'odometer', 'vin')
    )
);

CREATE INDEX IF NOT EXISTS idx_rts_trade_photos_inspection
    ON rts_trade_photos(inspection_id);

DO $$
BEGIN
    RAISE NOTICE 'V1.34 complete: rts_* columns added to user_checksheet_answers; rts_trade_photos created.';
END $$;
