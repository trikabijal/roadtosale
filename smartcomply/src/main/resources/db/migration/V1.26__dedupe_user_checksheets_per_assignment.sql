-- Dedupe user_checksheets so each audit_assignment has at most one active UC.
--
-- The location-dedupe in V1.25 re-parented orphan user_checksheets onto
-- canonical assignments. Where the canonical assignment already had its own
-- UC, both rows ended up active under the same assignment — leading to
-- impossible BI counts (e.g. 15 audits / 13 assignments per region).
--
-- Strategy: for each (audit_assignment_id) group with >1 active UC, keep the
-- "most progressed" one — APPROVED beats VALIDATED beats SUBMITTED beats
-- IN_PROGRESS beats anything else. Ties broken by most recent submitted_at,
-- then highest id. Soft-delete the losers and their dependent rows.

CREATE TEMP TABLE _uc_dups AS
WITH ranked AS (
  SELECT uc.id,
         uc.audit_assignment_id,
         uc.status,
         ROW_NUMBER() OVER (
           PARTITION BY uc.audit_assignment_id
           ORDER BY
             CASE uc.status
               WHEN 'APPROVED' THEN 0
               WHEN 'VALIDATED' THEN 1
               WHEN 'SUBMITTED' THEN 2
               WHEN 'IN_PROGRESS' THEN 3
               ELSE 4
             END,
             uc.submitted_at DESC NULLS LAST,
             uc.id DESC
         ) AS rn
    FROM user_checksheets uc
   WHERE uc.deleted_at IS NULL
)
SELECT id AS uc_id FROM ranked WHERE rn > 1;

CREATE INDEX ON _uc_dups (uc_id);

-- Soft-delete the dependent rows on losing UCs, then the UCs themselves.
UPDATE user_checksheet_answer_files
   SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
 WHERE user_checksheet_answer_id IN (
   SELECT id FROM user_checksheet_answers
   WHERE user_checksheet_id IN (SELECT uc_id FROM _uc_dups)
 ) AND deleted_at IS NULL;

UPDATE user_checksheet_answers
   SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
 WHERE user_checksheet_id IN (SELECT uc_id FROM _uc_dups)
   AND deleted_at IS NULL;

UPDATE user_checksheet_matrix_answers
   SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
 WHERE user_checksheet_id IN (SELECT uc_id FROM _uc_dups)
   AND deleted_at IS NULL;

UPDATE usr_chksheet_ans_judgements
   SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
 WHERE user_checksheet_id IN (SELECT uc_id FROM _uc_dups)
   AND deleted_at IS NULL;

UPDATE user_checksheets
   SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
 WHERE id IN (SELECT uc_id FROM _uc_dups)
   AND deleted_at IS NULL;

DROP TABLE _uc_dups;
