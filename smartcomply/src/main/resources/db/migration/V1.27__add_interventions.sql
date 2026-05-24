-- ============================================================================
-- V1.27: Interventions = the dynamic improvement layer on top of audits.
-- ----------------------------------------------------------------------------
-- Naming maps to the PRD's user-facing vocabulary as follows:
--    PRD "Improvement Campaign" → code/db: intervention
--    PRD "Improvement Plan"     → code/db: intervention_assignment
--
-- Why "intervention" instead of "improvement"? It mirrors the existing
-- audit ↔ audit_assignment naming, which is the same shape — a top-level
-- thing with per-location instances. Using parallel names makes the
-- relationship obvious to anyone reading the code; UI labels still say
-- "Improvement Campaign" and "Improvement Plan" for stakeholder clarity.
--
-- Two tables added on the existing audit pipeline:
--    audit_assignments       : two new columns (dealer principal, region owner)
--    user_checksheets        : audit_assignment_id becomes nullable + new
--                              intervention_assignment_id column. Exactly one
--                              of the two parents is set per row, enforced
--                              by a CHECK constraint. Each "re-inspection
--                              wave" against an intervention_assignment is a
--                              new user_checksheet on the SAME existing
--                              user_checksheets table — same answers, same
--                              files, same submit/validate/approve workflow.
--
-- The PRD's "Re-Audit" concept is implicit: it's just a user_checksheet
-- attached to an intervention_assignment. The whole audit pipeline (answer
-- recording, photo upload, judgement, validation, approval) is reused
-- end-to-end, so there's no parallel re_audits / reaudit_answers /
-- reaudit_answer_files machinery.
--
-- Targeting: an intervention has a list of audit_assignments in scope,
-- materialised once at activation time into intervention_assignment_targets.
-- The OEM admin's UI choice (ALL / by region / manual) is a composition
-- shortcut that resolves to the same join table; runtime always reads from
-- the join table. No target_kind enum, no runtime branching.
-- ============================================================================


-- ── 1. interventions ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS interventions (
    id           BIGSERIAL PRIMARY KEY,
    audit_id     BIGINT NOT NULL,
    name         VARCHAR NOT NULL,
    theme        TEXT,
    priority     VARCHAR NOT NULL,                          -- P1 | P2 | P3
    target_date  DATE,
    status       VARCHAR NOT NULL DEFAULT 'DRAFT',          -- DRAFT | ACTIVE | CLOSED
    activated_at TIMESTAMP,
    closed_at    TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP,
    deleted_at   TIMESTAMP,
    created_by   BIGINT NOT NULL,
    updated_by   BIGINT,
    deleted_by   BIGINT,
    CONSTRAINT chk_interventions_priority CHECK (priority IN ('P1','P2','P3')),
    CONSTRAINT chk_interventions_status   CHECK (status   IN ('DRAFT','ACTIVE','CLOSED'))
);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name='fk_interventions_audit_id') THEN
        ALTER TABLE interventions ADD CONSTRAINT fk_interventions_audit_id     FOREIGN KEY (audit_id)   REFERENCES audits(id);
        ALTER TABLE interventions ADD CONSTRAINT fk_interventions_created_by   FOREIGN KEY (created_by) REFERENCES users(id);
        ALTER TABLE interventions ADD CONSTRAINT fk_interventions_updated_by   FOREIGN KEY (updated_by) REFERENCES users(id);
        ALTER TABLE interventions ADD CONSTRAINT fk_interventions_deleted_by   FOREIGN KEY (deleted_by) REFERENCES users(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_interventions_audit_id_status ON interventions(audit_id, status);
CREATE INDEX IF NOT EXISTS idx_interventions_priority        ON interventions(priority);


-- ── 2. intervention_questions  (which checklist questions are in scope) ──
CREATE TABLE IF NOT EXISTS intervention_questions (
    id               BIGSERIAL PRIMARY KEY,
    intervention_id  BIGINT NOT NULL,
    chks_question_id BIGINT NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at       TIMESTAMP,
    created_by       BIGINT NOT NULL,
    deleted_by       BIGINT,
    CONSTRAINT uk_intervention_questions UNIQUE (intervention_id, chks_question_id)
);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name='fk_iq_intervention_id') THEN
        ALTER TABLE intervention_questions ADD CONSTRAINT fk_iq_intervention_id    FOREIGN KEY (intervention_id)  REFERENCES interventions(id);
        ALTER TABLE intervention_questions ADD CONSTRAINT fk_iq_chks_question_id   FOREIGN KEY (chks_question_id) REFERENCES chks_questions(id);
        ALTER TABLE intervention_questions ADD CONSTRAINT fk_iq_created_by         FOREIGN KEY (created_by)       REFERENCES users(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_iq_intervention_id  ON intervention_questions(intervention_id);
CREATE INDEX IF NOT EXISTS idx_iq_chks_question_id ON intervention_questions(chks_question_id);


-- ── 3. intervention_assignment_targets  (which audit_assignments are in scope) ──
-- Materialised once at intervention activation. Single source of truth for
-- "who does this intervention reach" — read uniformly regardless of how the
-- admin originally composed the list (ALL / by region / manual).
CREATE TABLE IF NOT EXISTS intervention_assignment_targets (
    id                  BIGSERIAL PRIMARY KEY,
    intervention_id     BIGINT NOT NULL,
    audit_assignment_id BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMP,
    created_by          BIGINT NOT NULL,
    deleted_by          BIGINT,
    CONSTRAINT uk_iat_intervention_audit_assignment UNIQUE (intervention_id, audit_assignment_id)
);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name='fk_iat_intervention_id') THEN
        ALTER TABLE intervention_assignment_targets ADD CONSTRAINT fk_iat_intervention_id      FOREIGN KEY (intervention_id)     REFERENCES interventions(id);
        ALTER TABLE intervention_assignment_targets ADD CONSTRAINT fk_iat_audit_assignment_id  FOREIGN KEY (audit_assignment_id) REFERENCES audit_assignments(id);
        ALTER TABLE intervention_assignment_targets ADD CONSTRAINT fk_iat_created_by           FOREIGN KEY (created_by)          REFERENCES users(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_iat_intervention_id     ON intervention_assignment_targets(intervention_id);
CREATE INDEX IF NOT EXISTS idx_iat_audit_assignment_id ON intervention_assignment_targets(audit_assignment_id);


-- ── 4. intervention_assignments  (the persistent "Plan" — one per dealer per intervention) ──
CREATE TABLE IF NOT EXISTS intervention_assignments (
    id                          BIGSERIAL PRIMARY KEY,
    intervention_id             BIGINT,                        -- NULLABLE (Mandatory P1 has no parent)
    audit_assignment_id         BIGINT NOT NULL,               -- the original audit context (one link, derives all else)
    priority                    VARCHAR NOT NULL,              -- snapshotted from intervention at creation
    target_date                 DATE NOT NULL,                 -- snapshotted from intervention at creation
    status                      VARCHAR NOT NULL DEFAULT 'PENDING',
    acknowledged_at             TIMESTAMP,
    acknowledged_by             BIGINT,
    completed_at                TIMESTAMP,
    closed_as_non_compliant_at  TIMESTAMP,
    closed_as_non_compliant_by  BIGINT,
    closure_reason              TEXT,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP,
    deleted_at                  TIMESTAMP,
    created_by                  BIGINT NOT NULL,
    updated_by                  BIGINT,
    deleted_by                  BIGINT,
    CONSTRAINT chk_ia_status   CHECK (status   IN ('PENDING','IN_PROGRESS','COMPLETED','NON_COMPLIANT')),
    CONSTRAINT chk_ia_priority CHECK (priority IN ('P1','P2','P3')),
    CONSTRAINT uk_ia_intervention_audit_assignment UNIQUE (intervention_id, audit_assignment_id)
);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name='fk_ia_intervention_id') THEN
        ALTER TABLE intervention_assignments ADD CONSTRAINT fk_ia_intervention_id              FOREIGN KEY (intervention_id)              REFERENCES interventions(id);
        ALTER TABLE intervention_assignments ADD CONSTRAINT fk_ia_audit_assignment_id          FOREIGN KEY (audit_assignment_id)          REFERENCES audit_assignments(id);
        ALTER TABLE intervention_assignments ADD CONSTRAINT fk_ia_acknowledged_by              FOREIGN KEY (acknowledged_by)              REFERENCES users(id);
        ALTER TABLE intervention_assignments ADD CONSTRAINT fk_ia_closed_as_non_compliant_by   FOREIGN KEY (closed_as_non_compliant_by)   REFERENCES users(id);
        ALTER TABLE intervention_assignments ADD CONSTRAINT fk_ia_created_by                   FOREIGN KEY (created_by)                   REFERENCES users(id);
        ALTER TABLE intervention_assignments ADD CONSTRAINT fk_ia_updated_by                   FOREIGN KEY (updated_by)                   REFERENCES users(id);
        ALTER TABLE intervention_assignments ADD CONSTRAINT fk_ia_deleted_by                   FOREIGN KEY (deleted_by)                   REFERENCES users(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_ia_intervention_id     ON intervention_assignments(intervention_id);
CREATE INDEX IF NOT EXISTS idx_ia_audit_assignment_id ON intervention_assignments(audit_assignment_id);
CREATE INDEX IF NOT EXISTS idx_ia_status              ON intervention_assignments(status);
CREATE INDEX IF NOT EXISTS idx_ia_target_date         ON intervention_assignments(target_date);


-- ── 5. intervention_assignment_questions  (the failing-question subset this assignment tracks) ──
CREATE TABLE IF NOT EXISTS intervention_assignment_questions (
    id                          BIGSERIAL PRIMARY KEY,
    intervention_assignment_id  BIGINT NOT NULL,
    chks_question_id            BIGINT NOT NULL,
    chks_question_result_id     BIGINT,                       -- the original failing answer this tracks (nullable for safety)
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at                  TIMESTAMP,
    created_by                  BIGINT NOT NULL,
    deleted_by                  BIGINT,
    CONSTRAINT uk_iaq_assignment_question UNIQUE (intervention_assignment_id, chks_question_id)
);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name='fk_iaq_intervention_assignment_id') THEN
        ALTER TABLE intervention_assignment_questions ADD CONSTRAINT fk_iaq_intervention_assignment_id  FOREIGN KEY (intervention_assignment_id) REFERENCES intervention_assignments(id);
        ALTER TABLE intervention_assignment_questions ADD CONSTRAINT fk_iaq_chks_question_id            FOREIGN KEY (chks_question_id)           REFERENCES chks_questions(id);
        ALTER TABLE intervention_assignment_questions ADD CONSTRAINT fk_iaq_chks_question_result_id     FOREIGN KEY (chks_question_result_id)    REFERENCES chks_question_results(id);
        ALTER TABLE intervention_assignment_questions ADD CONSTRAINT fk_iaq_created_by                  FOREIGN KEY (created_by)                 REFERENCES users(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_iaq_intervention_assignment_id ON intervention_assignment_questions(intervention_assignment_id);
CREATE INDEX IF NOT EXISTS idx_iaq_chks_question_id           ON intervention_assignment_questions(chks_question_id);


-- ── 6. audit_assignments  (add the principal + region owner columns) ──────
ALTER TABLE audit_assignments ADD COLUMN IF NOT EXISTS dealer_principal_user_id BIGINT;
ALTER TABLE audit_assignments ADD COLUMN IF NOT EXISTS region_owner_user_id     BIGINT;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name='fk_audit_assignments_dealer_principal_user_id') THEN
        ALTER TABLE audit_assignments ADD CONSTRAINT fk_audit_assignments_dealer_principal_user_id  FOREIGN KEY (dealer_principal_user_id) REFERENCES users(id);
        ALTER TABLE audit_assignments ADD CONSTRAINT fk_audit_assignments_region_owner_user_id      FOREIGN KEY (region_owner_user_id)     REFERENCES users(id);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_audit_assignments_dealer_principal_user_id ON audit_assignments(dealer_principal_user_id) WHERE dealer_principal_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_assignments_region_owner_user_id     ON audit_assignments(region_owner_user_id)     WHERE region_owner_user_id     IS NOT NULL;


-- ── 7. user_checksheets  (relax NOT NULL on audit_assignment_id, add intervention_assignment_id) ──
-- Each row now has exactly one parent: audit_assignment_id (original audit
-- instance, 1:1 per V1.26) OR intervention_assignment_id (re-inspection wave,
-- many-to-1). The CHECK enforces the XOR.
ALTER TABLE user_checksheets ALTER COLUMN audit_assignment_id DROP NOT NULL;
ALTER TABLE user_checksheets ADD COLUMN IF NOT EXISTS intervention_assignment_id BIGINT;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name='fk_user_checksheets_intervention_assignment_id') THEN
        ALTER TABLE user_checksheets ADD CONSTRAINT fk_user_checksheets_intervention_assignment_id  FOREIGN KEY (intervention_assignment_id) REFERENCES intervention_assignments(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name='chk_user_checksheets_one_parent') THEN
        ALTER TABLE user_checksheets ADD CONSTRAINT chk_user_checksheets_one_parent
            CHECK ((audit_assignment_id IS NOT NULL) <> (intervention_assignment_id IS NOT NULL));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_user_checksheets_intervention_assignment_id
    ON user_checksheets(intervention_assignment_id) WHERE intervention_assignment_id IS NOT NULL;


-- ── 8. New role + permissions ─────────────────────────────────────────────
INSERT INTO roles (role_code, name, created_at, created_by)
SELECT 'DEALER_PRINCIPAL', 'Dealer Principal', CURRENT_TIMESTAMP, 1
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE role_code = 'DEALER_PRINCIPAL');

INSERT INTO permissions (permission_code, name, description)
SELECT 'INTERVENTION_MANAGE', 'Manage Interventions', 'Create / update / activate / close interventions'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_code = 'INTERVENTION_MANAGE');

INSERT INTO permissions (permission_code, name, description)
SELECT 'INTERVENTION_ASSIGNMENT_VIEW', 'View Intervention Assignments', 'Read assignments (the per-dealer Plan)'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_code = 'INTERVENTION_ASSIGNMENT_VIEW');

INSERT INTO permissions (permission_code, name, description)
SELECT 'INTERVENTION_ASSIGNMENT_MANAGE', 'Manage Intervention Assignments', 'Close assignments as non-compliant, edit'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_code = 'INTERVENTION_ASSIGNMENT_MANAGE');

INSERT INTO permissions (permission_code, name, description)
SELECT 'INTERVENTION_ASSIGNMENT_ACKNOWLEDGE', 'Acknowledge Intervention Assignment', 'Dealer principal acknowledges a plan'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_code = 'INTERVENTION_ASSIGNMENT_ACKNOWLEDGE');

INSERT INTO permissions (permission_code, name, description)
SELECT 'INTERVENTION_CONDUCT', 'Conduct Intervention Inspection', 'Auditor performs a re-inspection on an intervention assignment'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_code = 'INTERVENTION_CONDUCT');
