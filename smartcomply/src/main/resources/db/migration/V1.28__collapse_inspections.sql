-- ============================================================================
-- V1.28: Collapse audit_assignments + intervention_assignments + user_checksheets
--        into a single `inspections` table.
-- ----------------------------------------------------------------------------
-- Architectural fix. Until V1.27 we had three tables representing one
-- conceptual entity (an inspection = one audit visit at one location):
--
--   audit_assignments        = work order for the original audit visit
--   intervention_assignments = work order/Plan for a re-inspection visit
--   user_checksheets         = the runtime form (status, answers, photos)
--
-- The split caused real confusion: BI rollup queries had to UNION across
-- three tables, every read joined two of them, and "is the inspection
-- started or not?" required a LEFT JOIN. One row per inspection from now on.
--
-- Rules:
--  - kind = 'AUDIT'        → audit_id set,        intervention_id NULL
--  - kind = 'INTERVENTION' → intervention_id set, audit_id NULL
--  - At most one inspection per (audit_id, auditee_location_id) and per
--    (intervention_id, auditee_location_id) — enforced by partial unique
--    index. Multi-wave is NOT supported; if a re-inspection is needed
--    after the first one closes, create a new intervention.
--  - Status flows:
--       ASSIGNED → IN_PROGRESS → SUBMITTED → VALIDATED → APPROVED
--       Validator/approver decline writes DECLINED on the inspection. The
--       operator's next createOrUpdate moves it forward (back to IN_PROGRESS
--       on save, SUBMITTED on resubmit) via the existing save path —
--       no explicit reopen-transition guard exists today. Lineage of which
--       path declined (validator vs approver) lives on the *_history rows.
--    (Acknowledged + Non-compliant are NOT modelled as states. Acknowledged
--     is a future flow we don't have yet. Non-compliant is a derived state
--     based on compliance thresholds, not a stored status — what counts as
--     non-compliant depends on per-customer score bands which can change.)
--
-- All 8 child tables (answers, answer_files, validations, _history, approvals,
-- _history, judgements, judgement_files, matrix_answers, trace_values,
-- general_field_values, ai_assessments) get their FK column renamed
-- user_checksheet_id → inspection_id.  The child tables themselves keep
-- their existing names (rename is in a follow-up migration to keep this
-- migration's blast radius bounded).
-- ============================================================================


-- ── 1. Create the inspections table ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS inspections (
    id                            BIGSERIAL PRIMARY KEY,
    kind                          VARCHAR NOT NULL,
    audit_id                      BIGINT,
    intervention_id               BIGINT,

    -- Where + who (was on audit_assignments)
    auditee_location_id           BIGINT NOT NULL,
    operator_user_id              BIGINT,
    dealer_principal_user_id      BIGINT,
    region_owner_user_id          BIGINT,

    -- Template binding (was on user_checksheets — direct FK to checksheets)
    checksheet_id                 BIGINT,
    shift                         VARCHAR,
    frequency_of_freq_of_chk_cnt  SMALLINT,

    -- Intervention-only payload (NULL for kind=AUDIT)
    priority                      VARCHAR,
    target_date                   DATE,

    -- Lifecycle
    status                        VARCHAR NOT NULL DEFAULT 'ASSIGNED',
    submission_version            SMALLINT NOT NULL DEFAULT 0,
    waiting_user_ids              INTEGER[],

    -- Lifecycle timestamps
    started_at                    TIMESTAMP,
    submitted_at                  TIMESTAMP,

    -- Audit trail
    created_at                    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP,
    deleted_at                    TIMESTAMP,
    created_by                    BIGINT NOT NULL,
    updated_by                    BIGINT,
    deleted_by                    BIGINT,

    CONSTRAINT chk_inspections_kind     CHECK (kind IN ('AUDIT','INTERVENTION')),
    CONSTRAINT chk_inspections_status   CHECK (status IN
        ('ASSIGNED','IN_PROGRESS','SUBMITTED','VALIDATED','APPROVED','DECLINED')),
    CONSTRAINT chk_inspections_priority CHECK (priority IS NULL OR priority IN ('P1','P2','P3')),
    CONSTRAINT chk_inspections_one_parent CHECK (
            (kind = 'AUDIT'        AND audit_id        IS NOT NULL AND intervention_id IS NULL)
         OR (kind = 'INTERVENTION' AND intervention_id IS NOT NULL AND audit_id        IS NULL)
    ),
    CONSTRAINT chk_inspections_intervention_only CHECK (
        kind = 'INTERVENTION' OR (priority IS NULL AND target_date IS NULL)
    )
);

-- FKs
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_audit_id              FOREIGN KEY (audit_id)                    REFERENCES audits(id);
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_intervention_id       FOREIGN KEY (intervention_id)             REFERENCES interventions(id);
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_auditee_location_id   FOREIGN KEY (auditee_location_id)         REFERENCES auditee_locations(id);
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_operator_user_id      FOREIGN KEY (operator_user_id)            REFERENCES users(id);
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_dealer_principal      FOREIGN KEY (dealer_principal_user_id)    REFERENCES users(id);
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_region_owner          FOREIGN KEY (region_owner_user_id)        REFERENCES users(id);
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_checksheet_id         FOREIGN KEY (checksheet_id)               REFERENCES checksheets(id);
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_created_by            FOREIGN KEY (created_by)                  REFERENCES users(id);
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_updated_by            FOREIGN KEY (updated_by)                  REFERENCES users(id);
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_deleted_by            FOREIGN KEY (deleted_by)                  REFERENCES users(id);

CREATE INDEX idx_inspections_audit_id            ON inspections(audit_id)        WHERE audit_id IS NOT NULL;
CREATE INDEX idx_inspections_intervention_id     ON inspections(intervention_id) WHERE intervention_id IS NOT NULL;
CREATE INDEX idx_inspections_location_id         ON inspections(auditee_location_id);
CREATE INDEX idx_inspections_operator_user_id    ON inspections(operator_user_id);
CREATE INDEX idx_inspections_dealer_principal    ON inspections(dealer_principal_user_id) WHERE dealer_principal_user_id IS NOT NULL;
CREATE INDEX idx_inspections_region_owner        ON inspections(region_owner_user_id)     WHERE region_owner_user_id     IS NOT NULL;
CREATE INDEX idx_inspections_status              ON inspections(status);
CREATE INDEX idx_inspections_kind_status         ON inspections(kind, status);

-- One inspection per pair (active rows only — soft-deleted rows can collide).
CREATE UNIQUE INDEX uk_inspections_audit_loc        ON inspections(audit_id, auditee_location_id)        WHERE deleted_at IS NULL AND kind='AUDIT';
CREATE UNIQUE INDEX uk_inspections_intervention_loc ON inspections(intervention_id, auditee_location_id) WHERE deleted_at IS NULL AND kind='INTERVENTION';


-- ── 2. Backfill: audit_assignments + their UCs → kind=AUDIT inspections ─────
-- Each AA becomes one inspection. If a UC exists for that AA, status &
-- runtime fields come from UC; otherwise it stays in ASSIGNED state.
-- Also stash the source UC id in a temp column so we can re-point children.

ALTER TABLE inspections ADD COLUMN _legacy_uc_id BIGINT;   -- temp, dropped at end
ALTER TABLE inspections ADD COLUMN _legacy_aa_id BIGINT;   -- temp, dropped at end
ALTER TABLE inspections ADD COLUMN _legacy_ia_id BIGINT;   -- temp, dropped at end

-- (2a) AAs with NO UC at all (any state) → one inspection in ASSIGNED state
INSERT INTO inspections (
    kind, audit_id, intervention_id,
    auditee_location_id, operator_user_id,
    dealer_principal_user_id, region_owner_user_id,
    checksheet_id, shift, frequency_of_freq_of_chk_cnt,
    priority, target_date,
    status, submission_version, waiting_user_ids,
    started_at, submitted_at,
    created_at, updated_at, deleted_at, created_by, updated_by, deleted_by,
    _legacy_uc_id, _legacy_aa_id, _legacy_ia_id
)
SELECT
    'AUDIT', aa.audit_id, NULL,
    aa.auditee_location_id, aa.operator_user_id,
    aa.dealer_principal_user_id, aa.region_owner_user_id,
    NULL, NULL, NULL,
    NULL, NULL,
    'ASSIGNED', 0, NULL,
    NULL, NULL,
    aa.created_at, aa.updated_at, aa.deleted_at, aa.created_by, aa.updated_by, aa.deleted_by,
    NULL, aa.id, NULL
FROM audit_assignments aa
WHERE NOT EXISTS (
    SELECT 1 FROM user_checksheets uc WHERE uc.audit_assignment_id = aa.id
);

-- (2b) Every UC under any AA → one inspection (preserves soft-deleted UCs as soft-deleted inspections)
INSERT INTO inspections (
    kind, audit_id, intervention_id,
    auditee_location_id, operator_user_id,
    dealer_principal_user_id, region_owner_user_id,
    checksheet_id, shift, frequency_of_freq_of_chk_cnt,
    priority, target_date,
    status, submission_version, waiting_user_ids,
    started_at, submitted_at,
    created_at, updated_at, deleted_at, created_by, updated_by, deleted_by,
    _legacy_uc_id, _legacy_aa_id, _legacy_ia_id
)
SELECT
    'AUDIT', aa.audit_id, NULL,
    aa.auditee_location_id,
    COALESCE(uc.operator_user_id, aa.operator_user_id),
    aa.dealer_principal_user_id, aa.region_owner_user_id,
    uc.checksheet_id, uc.shift, uc.frequency_of_freq_of_chk_cnt,
    NULL, NULL,
    -- Map legacy UC statuses to the V1.28 enum. INVALIDATED (validator decline)
    -- and NOT_APPROVED (approver decline) collapse into DECLINED — the V1.28
    -- terminal-decline state. Lineage (which path declined) is preserved on
    -- the *_history tables; storing DECLINED on the inspection itself keeps
    -- the post-decline state distinguishable from "operator hasn't started"
    -- (IN_PROGRESS would have been semantically lossy). Pre-V1.28 enum:
    -- IN_PROGRESS|SUBMITTED|VALIDATED|INVALIDATED|APPROVED|NOT_APPROVED.
    -- V1.28: ASSIGNED|IN_PROGRESS|SUBMITTED|VALIDATED|APPROVED|DECLINED.
    CASE uc.status
        WHEN 'INVALIDATED'  THEN 'DECLINED'
        WHEN 'NOT_APPROVED' THEN 'DECLINED'
        ELSE uc.status
    END,
    COALESCE(uc.submission_version, 0::smallint),
    uc.waiting_user_ids,
    uc.started_at, uc.submitted_at,
    COALESCE(uc.created_at, aa.created_at),
    COALESCE(uc.updated_at, aa.updated_at),
    GREATEST(uc.deleted_at, aa.deleted_at),     -- inspection deleted if EITHER UC or AA deleted
    COALESCE(uc.created_by, aa.created_by),
    COALESCE(uc.updated_by, aa.updated_by),
    COALESCE(uc.deleted_by, aa.deleted_by),
    uc.id, aa.id, NULL
FROM user_checksheets uc
JOIN audit_assignments aa ON aa.id = uc.audit_assignment_id;


-- ── 3. Backfill: intervention_assignments + their UCs → kind=INTERVENTION ──
-- Hard-fail if any IA has more than one active UC (multi-wave): we don't
-- support multi-wave and don't want to silently lose data. Verified zero on
-- the dev DB, but keep the guard for safety on future runs.
DO $$
DECLARE
    multi_wave_count BIGINT;
    non_compliant_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO multi_wave_count FROM (
        SELECT intervention_assignment_id
          FROM user_checksheets
         WHERE deleted_at IS NULL AND intervention_assignment_id IS NOT NULL
         GROUP BY intervention_assignment_id
        HAVING COUNT(*) > 1
    ) x;
    IF multi_wave_count > 0 THEN
        RAISE EXCEPTION 'V1.28: % intervention_assignments have multi-wave UCs — multi-wave is not supported. Resolve before proceeding.', multi_wave_count;
    END IF;

    -- Non-compliant is no longer a stored status (it's derived). Hard-fail
    -- if any IA has it set so we know the assumption is broken.
    SELECT COUNT(*) INTO non_compliant_count FROM intervention_assignments WHERE status = 'NON_COMPLIANT' OR closed_as_non_compliant_at IS NOT NULL;
    IF non_compliant_count > 0 THEN
        RAISE EXCEPTION 'V1.28: % intervention_assignments have NON_COMPLIANT state — this is now a derived state, not stored. Investigate.', non_compliant_count;
    END IF;
END $$;

-- (3a) IAs with NO UC at all → one inspection in ASSIGNED state (status mapped from IA.status)
INSERT INTO inspections (
    kind, audit_id, intervention_id,
    auditee_location_id, operator_user_id,
    dealer_principal_user_id, region_owner_user_id,
    checksheet_id, shift, frequency_of_freq_of_chk_cnt,
    priority, target_date,
    status, submission_version, waiting_user_ids,
    started_at, submitted_at,
    created_at, updated_at, deleted_at, created_by, updated_by, deleted_by,
    _legacy_uc_id, _legacy_aa_id, _legacy_ia_id
)
SELECT
    'INTERVENTION', NULL, ia.intervention_id,
    aa.auditee_location_id, aa.operator_user_id,
    aa.dealer_principal_user_id, aa.region_owner_user_id,
    NULL, NULL, NULL,
    ia.priority, ia.target_date,
    CASE ia.status
        WHEN 'PENDING'     THEN 'ASSIGNED'
        WHEN 'IN_PROGRESS' THEN 'IN_PROGRESS'
        WHEN 'COMPLETED'   THEN 'APPROVED'
        ELSE 'ASSIGNED'
    END,
    0, NULL,
    NULL, NULL,
    ia.created_at, ia.updated_at, ia.deleted_at, ia.created_by, ia.updated_by, ia.deleted_by,
    NULL, NULL, ia.id
FROM intervention_assignments ia
JOIN audit_assignments aa ON aa.id = ia.audit_assignment_id
WHERE NOT EXISTS (
    SELECT 1 FROM user_checksheets uc WHERE uc.intervention_assignment_id = ia.id
);

-- (3b) Every UC under any IA → one inspection (preserves soft-deleted)
INSERT INTO inspections (
    kind, audit_id, intervention_id,
    auditee_location_id, operator_user_id,
    dealer_principal_user_id, region_owner_user_id,
    checksheet_id, shift, frequency_of_freq_of_chk_cnt,
    priority, target_date,
    status, submission_version, waiting_user_ids,
    started_at, submitted_at,
    created_at, updated_at, deleted_at, created_by, updated_by, deleted_by,
    _legacy_uc_id, _legacy_aa_id, _legacy_ia_id
)
SELECT
    'INTERVENTION', NULL, ia.intervention_id,
    aa.auditee_location_id,
    COALESCE(uc.operator_user_id, aa.operator_user_id),
    aa.dealer_principal_user_id, aa.region_owner_user_id,
    uc.checksheet_id, uc.shift, uc.frequency_of_freq_of_chk_cnt,
    ia.priority, ia.target_date,
    -- Same legacy-status mapping as the audit-side backfill above:
    -- INVALIDATED / NOT_APPROVED → DECLINED (V1.28 terminal-decline state).
    CASE uc.status
        WHEN 'INVALIDATED'  THEN 'DECLINED'
        WHEN 'NOT_APPROVED' THEN 'DECLINED'
        ELSE uc.status
    END,
    COALESCE(uc.submission_version, 0::smallint),
    uc.waiting_user_ids,
    uc.started_at, uc.submitted_at,
    COALESCE(uc.created_at, ia.created_at),
    COALESCE(uc.updated_at, ia.updated_at),
    GREATEST(uc.deleted_at, ia.deleted_at),
    COALESCE(uc.created_by, ia.created_by),
    COALESCE(uc.updated_by, ia.updated_by),
    COALESCE(uc.deleted_by, ia.deleted_by),
    uc.id, NULL, ia.id
FROM user_checksheets uc
JOIN intervention_assignments ia ON ia.id = uc.intervention_assignment_id
JOIN audit_assignments aa ON aa.id = ia.audit_assignment_id;


-- ── 4. Re-point children: replace user_checksheet_id with inspection_id ────
--      11 tables have a direct FK to user_checksheets and need this surgery.
--      (user_checksheet_answer_files does NOT — it FKs the answers table.)
--      Pattern per table:
--        a. ADD inspection_id BIGINT
--        b. UPDATE c SET inspection_id = i.id FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id
--        c. Drop FK + index referencing user_checksheet_id
--        d. DROP user_checksheet_id, set inspection_id NOT NULL
--        e. ADD FK + index on inspection_id
--      Written explicitly per table — verbose but straightforward to debug.

-- 4.1 user_checksheet_answers (has UNIQUE on user_checksheet_id+chks_question_results_id — recreate on inspection_id)
ALTER TABLE user_checksheet_answers ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE user_checksheet_answers c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE user_checksheet_answers DROP CONSTRAINT IF EXISTS fk_user_checksheet_answers_user_checksheet_id_user_checksheets_;
ALTER TABLE user_checksheet_answers DROP CONSTRAINT IF EXISTS uk_user_checksheet_id_chks_question_results_id;
ALTER TABLE user_checksheet_answers DROP COLUMN user_checksheet_id;
ALTER TABLE user_checksheet_answers ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE user_checksheet_answers ADD CONSTRAINT fk_user_checksheet_answers_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
ALTER TABLE user_checksheet_answers ADD CONSTRAINT uk_inspection_id_chks_question_result_id UNIQUE (inspection_id, chks_question_result_id);
CREATE INDEX idx_user_checksheet_answers_inspection_id ON user_checksheet_answers(inspection_id);

-- 4.2 user_checksheet_validations
ALTER TABLE user_checksheet_validations ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE user_checksheet_validations c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE user_checksheet_validations DROP CONSTRAINT IF EXISTS fk_user_checksheet_validations_user_checksheet_id_user_checkshe;
ALTER TABLE user_checksheet_validations DROP COLUMN user_checksheet_id;
ALTER TABLE user_checksheet_validations ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE user_checksheet_validations ADD CONSTRAINT fk_user_checksheet_validations_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
CREATE INDEX idx_user_checksheet_validations_inspection_id ON user_checksheet_validations(inspection_id);

-- 4.3 user_checksheet_validations_history
ALTER TABLE user_checksheet_validations_history ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE user_checksheet_validations_history c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE user_checksheet_validations_history DROP CONSTRAINT IF EXISTS fk_user_checksheet_validations_history_user_checksheet_id_user_;
ALTER TABLE user_checksheet_validations_history DROP COLUMN user_checksheet_id;
ALTER TABLE user_checksheet_validations_history ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE user_checksheet_validations_history ADD CONSTRAINT fk_user_checksheet_validations_history_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
CREATE INDEX idx_user_checksheet_validations_history_inspection_id ON user_checksheet_validations_history(inspection_id);

-- 4.4 user_checksheet_approvals
ALTER TABLE user_checksheet_approvals ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE user_checksheet_approvals c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE user_checksheet_approvals DROP CONSTRAINT IF EXISTS fk_user_checksheet_approvals_user_checksheet_id_user_checksheet;
ALTER TABLE user_checksheet_approvals DROP COLUMN user_checksheet_id;
ALTER TABLE user_checksheet_approvals ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE user_checksheet_approvals ADD CONSTRAINT fk_user_checksheet_approvals_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
CREATE INDEX idx_user_checksheet_approvals_inspection_id ON user_checksheet_approvals(inspection_id);

-- 4.5 user_checksheet_approvals_history
ALTER TABLE user_checksheet_approvals_history ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE user_checksheet_approvals_history c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE user_checksheet_approvals_history DROP CONSTRAINT IF EXISTS fk_user_checksheet_approvals_history_user_checksheet_id_user_ch;
ALTER TABLE user_checksheet_approvals_history DROP COLUMN user_checksheet_id;
ALTER TABLE user_checksheet_approvals_history ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE user_checksheet_approvals_history ADD CONSTRAINT fk_user_checksheet_approvals_history_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
CREATE INDEX idx_user_checksheet_approvals_history_inspection_id ON user_checksheet_approvals_history(inspection_id);

-- 4.6 usr_chksheet_ans_judgements
ALTER TABLE usr_chksheet_ans_judgements ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE usr_chksheet_ans_judgements c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE usr_chksheet_ans_judgements DROP CONSTRAINT IF EXISTS fk_usr_chksheet_ans_judgements_user_checksheet_id_user_checkshe;
DROP INDEX IF EXISTS idx_usr_chksheet_ans_judgements_user_checksheet_id;
ALTER TABLE usr_chksheet_ans_judgements DROP COLUMN user_checksheet_id;
ALTER TABLE usr_chksheet_ans_judgements ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE usr_chksheet_ans_judgements ADD CONSTRAINT fk_usr_chksheet_ans_judgements_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
CREATE INDEX idx_usr_chksheet_ans_judgements_inspection_id ON usr_chksheet_ans_judgements(inspection_id);

-- 4.7 usr_chksheet_ans_judgement_files (has its own user_checksheet_id direct FK)
ALTER TABLE usr_chksheet_ans_judgement_files ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE usr_chksheet_ans_judgement_files c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE usr_chksheet_ans_judgement_files DROP CONSTRAINT IF EXISTS fk_usr_chksheet_ans_judgement_files_user_checksheets_id;
ALTER TABLE usr_chksheet_ans_judgement_files DROP COLUMN user_checksheet_id;
ALTER TABLE usr_chksheet_ans_judgement_files ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE usr_chksheet_ans_judgement_files ADD CONSTRAINT fk_usr_chksheet_ans_judgement_files_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
CREATE INDEX idx_usr_chksheet_ans_judgement_files_inspection_id ON usr_chksheet_ans_judgement_files(inspection_id);

-- 4.8 user_checksheet_matrix_answers (has UNIQUE w/ user_checksheet_id — recreate on inspection_id)
ALTER TABLE user_checksheet_matrix_answers ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE user_checksheet_matrix_answers c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE user_checksheet_matrix_answers DROP CONSTRAINT IF EXISTS fk_user_checksheet_matrix_answers_user_checksheet_id_user_check;
ALTER TABLE user_checksheet_matrix_answers DROP CONSTRAINT IF EXISTS uk_usr_chks_id_chks_qtion_rslt_id_chks_qtion_rslt_mtrx_id;
ALTER TABLE user_checksheet_matrix_answers DROP COLUMN user_checksheet_id;
ALTER TABLE user_checksheet_matrix_answers ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE user_checksheet_matrix_answers ADD CONSTRAINT fk_user_checksheet_matrix_answers_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
ALTER TABLE user_checksheet_matrix_answers ADD CONSTRAINT uk_inspection_id_chks_qtion_rslt_id_chks_qtion_rslt_mtrx_id UNIQUE (inspection_id, chks_question_result_id, chks_question_result_matrix_id, order_no);
CREATE INDEX idx_user_checksheet_matrix_answers_inspection_id ON user_checksheet_matrix_answers(inspection_id);

-- 4.9 user_checksheet_trace_values (has UNIQUE — recreate on inspection_id)
ALTER TABLE user_checksheet_trace_values ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE user_checksheet_trace_values c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE user_checksheet_trace_values DROP CONSTRAINT IF EXISTS user_checksheet_trace_values_fk_user_checksheets_id;
ALTER TABLE user_checksheet_trace_values DROP CONSTRAINT IF EXISTS user_checksheet_trace_values_uk_user_checksheet_id_chks_header_;
ALTER TABLE user_checksheet_trace_values DROP COLUMN user_checksheet_id;
ALTER TABLE user_checksheet_trace_values ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE user_checksheet_trace_values ADD CONSTRAINT fk_user_checksheet_trace_values_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
ALTER TABLE user_checksheet_trace_values ADD CONSTRAINT uk_user_checksheet_trace_values_inspection_id UNIQUE (inspection_id, chks_header_data_id);
CREATE INDEX idx_user_checksheet_trace_values_inspection_id ON user_checksheet_trace_values(inspection_id);

-- 4.10 chks_general_field_values
ALTER TABLE chks_general_field_values ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE chks_general_field_values c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE chks_general_field_values DROP CONSTRAINT IF EXISTS fk_chks_general_field_value_user_checksheet_id_user_checksheets;
ALTER TABLE chks_general_field_values DROP COLUMN user_checksheet_id;
ALTER TABLE chks_general_field_values ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE chks_general_field_values ADD CONSTRAINT fk_chks_general_field_values_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
CREATE INDEX idx_chks_general_field_values_inspection_id ON chks_general_field_values(inspection_id);

-- 4.11 ai_assessments
ALTER TABLE ai_assessments ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE ai_assessments c SET inspection_id = i.id
  FROM inspections i WHERE i._legacy_uc_id = c.user_checksheet_id AND c.inspection_id IS NULL;
ALTER TABLE ai_assessments DROP CONSTRAINT IF EXISTS fk_ai_assessments_user_checksheet_id;
DROP INDEX IF EXISTS idx_ai_assessments_user_checksheet_id;
DROP INDEX IF EXISTS idx_ai_assessments_lookup;
ALTER TABLE ai_assessments DROP COLUMN user_checksheet_id;
ALTER TABLE ai_assessments ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE ai_assessments ADD CONSTRAINT fk_ai_assessments_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
CREATE INDEX idx_ai_assessments_inspection_id ON ai_assessments(inspection_id);
CREATE INDEX idx_ai_assessments_lookup ON ai_assessments(inspection_id, chks_question_result_id);

-- (user_checksheet_answer_files keeps its existing FK to user_checksheet_answers
--  — that table stays, just with its own parent FK renamed above in 4.1.)


-- ── 5. Re-point intervention_assignment_targets + intervention_assignment_questions ──
-- These were FK'd to audit_assignments and intervention_assignments respectively.
-- Now they need to FK the relevant inspections.
--
-- IDENTITY-MAP CORRECTNESS (review-found, fixed):
--   §2b/§3b create one inspection per UC. A pre-V1.28 AA with one active UC
--   plus deleted historical UCs becomes multiple inspection rows that share
--   the same _legacy_aa_id (or _legacy_ia_id for IAs). A naive UPDATE … FROM
--   inspections WHERE i._legacy_aa_id = t.audit_assignment_id picks
--   non-deterministically — could bind a target/tracked-question to a
--   deleted historical inspection.
--
--   Fix: scope the lookup to active inspections only (deleted_at IS NULL),
--   AND hard-fail BEFORE the UPDATE if the active set isn't unique per
--   legacy id. There must be exactly one active inspection per legacy id
--   for the mapping to be unambiguous.

-- 5.0 Pre-flight uniqueness checks on the legacy-id columns of inspections.
DO $$
DECLARE
    aa_dupes BIGINT;
    ia_dupes BIGINT;
    iat_unmatched BIGINT;
    iaq_unmatched BIGINT;
BEGIN
    -- More than one ACTIVE inspection sharing a single _legacy_aa_id is
    -- ambiguous (a soft-delete in §2b could still produce this if v1.26
    -- dedupe was incomplete on a tenant; we need to fail explicitly rather
    -- than mis-bind a target).
    SELECT COUNT(*) INTO aa_dupes FROM (
        SELECT _legacy_aa_id FROM inspections
         WHERE _legacy_aa_id IS NOT NULL AND deleted_at IS NULL
         GROUP BY _legacy_aa_id HAVING COUNT(*) > 1
    ) x;
    IF aa_dupes > 0 THEN
        RAISE EXCEPTION 'V1.28: % audit_assignment(s) map to multiple active inspections — IAT remap would be ambiguous. Resolve before proceeding.', aa_dupes;
    END IF;

    SELECT COUNT(*) INTO ia_dupes FROM (
        SELECT _legacy_ia_id FROM inspections
         WHERE _legacy_ia_id IS NOT NULL AND deleted_at IS NULL
         GROUP BY _legacy_ia_id HAVING COUNT(*) > 1
    ) x;
    IF ia_dupes > 0 THEN
        RAISE EXCEPTION 'V1.28: % intervention_assignment(s) map to multiple active inspections — IAQ remap would be ambiguous. Resolve before proceeding.', ia_dupes;
    END IF;

    -- Every target row must find exactly one active inspection.
    SELECT COUNT(*) INTO iat_unmatched
      FROM intervention_assignment_targets t
     WHERE NOT EXISTS (
         SELECT 1 FROM inspections i
          WHERE i._legacy_aa_id = t.audit_assignment_id
            AND i.deleted_at IS NULL
     );
    IF iat_unmatched > 0 THEN
        RAISE EXCEPTION 'V1.28: % intervention_assignment_targets row(s) have no active inspection to bind to — backfill incomplete.', iat_unmatched;
    END IF;

    SELECT COUNT(*) INTO iaq_unmatched
      FROM intervention_assignment_questions q
     WHERE NOT EXISTS (
         SELECT 1 FROM inspections i
          WHERE i._legacy_ia_id = q.intervention_assignment_id
            AND i.deleted_at IS NULL
     );
    IF iaq_unmatched > 0 THEN
        RAISE EXCEPTION 'V1.28: % intervention_assignment_questions row(s) have no active inspection to bind to — backfill incomplete.', iaq_unmatched;
    END IF;
END $$;

-- intervention_assignment_targets.audit_assignment_id → inspection_id (kind=AUDIT, ACTIVE only)
ALTER TABLE intervention_assignment_targets ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE intervention_assignment_targets t
   SET inspection_id = i.id
  FROM inspections i
 WHERE i._legacy_aa_id = t.audit_assignment_id
   AND i.deleted_at IS NULL                 -- ← always bind to the live row
   AND t.inspection_id IS NULL;
ALTER TABLE intervention_assignment_targets DROP CONSTRAINT IF EXISTS fk_iat_audit_assignment_id;
ALTER TABLE intervention_assignment_targets DROP CONSTRAINT IF EXISTS uk_iat_intervention_audit_assignment;
DROP INDEX IF EXISTS idx_iat_audit_assignment_id;
ALTER TABLE intervention_assignment_targets DROP COLUMN audit_assignment_id;
ALTER TABLE intervention_assignment_targets ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE intervention_assignment_targets ADD CONSTRAINT fk_iat_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
ALTER TABLE intervention_assignment_targets ADD CONSTRAINT uk_iat_intervention_inspection UNIQUE (intervention_id, inspection_id);
CREATE INDEX IF NOT EXISTS idx_iat_inspection_id ON intervention_assignment_targets(inspection_id);

-- intervention_assignment_questions.intervention_assignment_id → inspection_id (kind=INTERVENTION, ACTIVE only)
ALTER TABLE intervention_assignment_questions ADD COLUMN IF NOT EXISTS inspection_id BIGINT;
UPDATE intervention_assignment_questions q
   SET inspection_id = i.id
  FROM inspections i
 WHERE i._legacy_ia_id = q.intervention_assignment_id
   AND i.deleted_at IS NULL                 -- ← always bind to the live row
   AND q.inspection_id IS NULL;
ALTER TABLE intervention_assignment_questions DROP CONSTRAINT IF EXISTS fk_iaq_intervention_assignment_id;
ALTER TABLE intervention_assignment_questions DROP CONSTRAINT IF EXISTS uk_iaq_assignment_question;
DROP INDEX IF EXISTS idx_iaq_intervention_assignment_id;
ALTER TABLE intervention_assignment_questions DROP COLUMN intervention_assignment_id;
ALTER TABLE intervention_assignment_questions ALTER COLUMN inspection_id SET NOT NULL;
ALTER TABLE intervention_assignment_questions ADD CONSTRAINT fk_iaq_inspection_id FOREIGN KEY (inspection_id) REFERENCES inspections(id);
ALTER TABLE intervention_assignment_questions ADD CONSTRAINT uk_iaq_inspection_question UNIQUE (inspection_id, chks_question_id);
CREATE INDEX IF NOT EXISTS idx_iaq_inspection_id ON intervention_assignment_questions(inspection_id);


-- ── 6. Pre-drop verification — assert every child child-table FK landed safely
--      BEFORE we destroy the source tables. Once the sources are gone we lose
--      the ability to compare counts, so this guard runs first.
DO $$
DECLARE
    table_name TEXT;
    orphan_count BIGINT;
    target_orphans BIGINT;
    question_orphans BIGINT;
BEGIN
    FOR table_name IN
        SELECT unnest(ARRAY[
            'user_checksheet_answers',
            'user_checksheet_validations', 'user_checksheet_validations_history',
            'user_checksheet_approvals', 'user_checksheet_approvals_history',
            'usr_chksheet_ans_judgements', 'usr_chksheet_ans_judgement_files',
            'user_checksheet_matrix_answers', 'user_checksheet_trace_values',
            'chks_general_field_values', 'ai_assessments'
        ])
    LOOP
        EXECUTE format('SELECT COUNT(*) FROM %I WHERE inspection_id IS NULL', table_name)
            INTO orphan_count;
        IF orphan_count > 0 THEN
            RAISE EXCEPTION 'V1.28: % orphan rows in % (inspection_id IS NULL) — backfill incomplete, aborting before DROP TABLE.', orphan_count, table_name;
        END IF;
    END LOOP;

    SELECT COUNT(*) INTO target_orphans FROM intervention_assignment_targets WHERE inspection_id IS NULL;
    IF target_orphans > 0 THEN
        RAISE EXCEPTION 'V1.28: % orphan intervention_assignment_targets — backfill incomplete.', target_orphans;
    END IF;
    SELECT COUNT(*) INTO question_orphans FROM intervention_assignment_questions WHERE inspection_id IS NULL;
    IF question_orphans > 0 THEN
        RAISE EXCEPTION 'V1.28: % orphan intervention_assignment_questions — backfill incomplete.', question_orphans;
    END IF;
END $$;


-- ── 7. Drop the three old tables ────────────────────────────────────────────
DROP TABLE IF EXISTS user_checksheets        CASCADE;
DROP TABLE IF EXISTS intervention_assignments CASCADE;
DROP TABLE IF EXISTS audit_assignments        CASCADE;


-- ── 8. Drop the temp legacy-id columns ──────────────────────────────────────
ALTER TABLE inspections DROP COLUMN IF EXISTS _legacy_uc_id;
ALTER TABLE inspections DROP COLUMN IF EXISTS _legacy_aa_id;
ALTER TABLE inspections DROP COLUMN IF EXISTS _legacy_ia_id;


-- ── 9. Final notice ─────────────────────────────────────────────────────────
DO $$
DECLARE
    audit_kind_count BIGINT;
    intervention_kind_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO audit_kind_count        FROM inspections WHERE kind = 'AUDIT';
    SELECT COUNT(*) INTO intervention_kind_count FROM inspections WHERE kind = 'INTERVENTION';
    RAISE NOTICE 'V1.28 collapse complete: % AUDIT inspections, % INTERVENTION inspections',
        audit_kind_count, intervention_kind_count;
END $$;
