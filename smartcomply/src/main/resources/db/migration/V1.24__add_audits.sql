-- ============================================================================
-- V1.24: Audit (campaign) entity + AuditAssignment + FK on user_checksheets
-- ----------------------------------------------------------------------------
-- Splits the overloaded `checksheet` (template + campaign) into:
--   checksheet        = template only (questions, options, headers)
--   audit             = a named campaign (e.g., "FY26 H1 Kia Showroom Audit")
--                       that references one checksheet template
--   audit_assignment  = one row per (audit, location, operator) — the unit of
--                       work for an auditor. Future-proof name: an assignment
--                       can be fulfilled by a human visit, CCTV feed, voice
--                       interview, or an automated road-to-sale check
--   user_checksheet   = the actual filled-in audit form for one assignment
--
-- Cleanliness over compatibility: every user_checksheet row now carries an
-- audit_assignment_id (NOT NULL). Pre-audit test rows are deleted along with
-- their dependents — no customers on this yet, so no real data loss.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. audits
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audits (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR NOT NULL,
    checksheet_id BIGINT NOT NULL,
    status        VARCHAR NOT NULL DEFAULT 'ACTIVE',
    start_date    TIMESTAMP,
    end_date      TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP,
    deleted_at    TIMESTAMP,
    created_by    BIGINT NOT NULL,
    updated_by    BIGINT,
    deleted_by    BIGINT
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audits_checksheet_id_checksheets_id') THEN
        ALTER TABLE audits ADD CONSTRAINT fk_audits_checksheet_id_checksheets_id
            FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audits_created_by_user_id') THEN
        ALTER TABLE audits ADD CONSTRAINT fk_audits_created_by_user_id
            FOREIGN KEY (created_by) REFERENCES users(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audits_updated_by_user_id') THEN
        ALTER TABLE audits ADD CONSTRAINT fk_audits_updated_by_user_id
            FOREIGN KEY (updated_by) REFERENCES users(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audits_deleted_by_user_id') THEN
        ALTER TABLE audits ADD CONSTRAINT fk_audits_deleted_by_user_id
            FOREIGN KEY (deleted_by) REFERENCES users(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_audits_checksheet_id ON audits(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_audits_status        ON audits(status);

-- ---------------------------------------------------------------------------
-- 2. audit_assignments — one work item: (audit, location, operator)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_assignments (
    id                  BIGSERIAL PRIMARY KEY,
    audit_id            BIGINT NOT NULL,
    auditee_location_id BIGINT NOT NULL,
    operator_user_id    BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    deleted_at          TIMESTAMP,
    created_by          BIGINT NOT NULL,
    updated_by          BIGINT,
    deleted_by          BIGINT
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'uk_audit_assignments_audit_loc') THEN
        ALTER TABLE audit_assignments ADD CONSTRAINT uk_audit_assignments_audit_loc UNIQUE (audit_id, auditee_location_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audit_assignments_audit_id_audits_id') THEN
        ALTER TABLE audit_assignments ADD CONSTRAINT fk_audit_assignments_audit_id_audits_id
            FOREIGN KEY (audit_id) REFERENCES audits(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audit_assignments_auditee_location_id') THEN
        ALTER TABLE audit_assignments ADD CONSTRAINT fk_audit_assignments_auditee_location_id
            FOREIGN KEY (auditee_location_id) REFERENCES auditee_locations(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audit_assignments_operator_user_id') THEN
        ALTER TABLE audit_assignments ADD CONSTRAINT fk_audit_assignments_operator_user_id
            FOREIGN KEY (operator_user_id) REFERENCES users(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audit_assignments_created_by_user_id') THEN
        ALTER TABLE audit_assignments ADD CONSTRAINT fk_audit_assignments_created_by_user_id
            FOREIGN KEY (created_by) REFERENCES users(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audit_assignments_updated_by_user_id') THEN
        ALTER TABLE audit_assignments ADD CONSTRAINT fk_audit_assignments_updated_by_user_id
            FOREIGN KEY (updated_by) REFERENCES users(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_audit_assignments_deleted_by_user_id') THEN
        ALTER TABLE audit_assignments ADD CONSTRAINT fk_audit_assignments_deleted_by_user_id
            FOREIGN KEY (deleted_by) REFERENCES users(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_audit_assignments_audit_id            ON audit_assignments(audit_id);
CREATE INDEX IF NOT EXISTS idx_audit_assignments_auditee_location_id ON audit_assignments(auditee_location_id);
CREATE INDEX IF NOT EXISTS idx_audit_assignments_operator_user_id    ON audit_assignments(operator_user_id);

-- ---------------------------------------------------------------------------
-- 3. Wipe pre-audit user_checksheet data + dependents.
-- Brand-new instance; the 3 existing rows are pre-audit test data with no
-- audit context. Safe to delete on a fresh DB; UAT baseline already captured.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'user_checksheets') THEN
        DELETE FROM user_checksheet_answer_files
         WHERE user_checksheet_answer_id IN (
             SELECT id FROM user_checksheet_answers
             WHERE user_checksheet_id IN (SELECT id FROM user_checksheets));
        DELETE FROM user_checksheet_answers              WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM user_checksheet_matrix_answers       WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM user_checksheet_trace_values         WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM user_checksheet_validations_history  WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM user_checksheet_validations          WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM user_checksheet_approvals_history    WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM user_checksheet_approvals            WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM usr_chksheet_ans_judgement_files
         WHERE usr_chksheet_ans_judgement_id IN (SELECT id FROM usr_chksheet_ans_judgements);
        DELETE FROM usr_chksheet_ans_judgements          WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM chks_general_field_values            WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM ai_assessments                       WHERE user_checksheet_id IN (SELECT id FROM user_checksheets);
        DELETE FROM user_checksheets;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 4. Add NOT NULL audit_assignment_id on user_checksheets
-- ---------------------------------------------------------------------------
ALTER TABLE IF EXISTS user_checksheets
    ADD COLUMN IF NOT EXISTS audit_assignment_id BIGINT NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_user_checksheets_audit_assignment_id') THEN
        ALTER TABLE user_checksheets ADD CONSTRAINT fk_user_checksheets_audit_assignment_id
            FOREIGN KEY (audit_assignment_id) REFERENCES audit_assignments(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_user_checksheets_audit_assignment_id ON user_checksheets(audit_assignment_id);
