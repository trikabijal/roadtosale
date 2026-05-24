-- ============================================================================
-- V1.31: Enforce per-INSPECTION uniqueness on validator/approver review rows.
-- ----------------------------------------------------------------------------
-- Post-V1.28 the review tables (user_checksheet_validations,
-- user_checksheet_approvals) anchor on inspection_id. Each (inspection,
-- reviewer) pair must hold at most one live row — the lookup-or-create path
-- in UserChecksheetValidationServiceImpl / UserChecksheetApprovalServiceImpl
-- updates an existing row if the same reviewer reviews the same inspection
-- again (e.g. after a DECLINE-and-resubmit cycle).
--
-- Why a constraint?
-- Pre-V1.28 the parent table's natural key was (user_checksheet_id, reviewer)
-- and was enforced implicitly by the lookup-or-create code keying on it.
-- After V1.28 the code was supposed to switch to (inspection_id, reviewer),
-- but the repository finder methods stayed at (checksheet_id, reviewer) —
-- the bug fixed in this commit. With the repo + service now fixed, this
-- migration adds a partial UNIQUE index to lock the invariant at the DB so
-- the bug can never reappear and so any out-of-band INSERT (script,
-- migration, etc.) fails fast instead of corrupting review history.
--
-- Partial-index choice: WHERE deleted_at IS NULL — soft-deleted rows are
-- excluded so that a deleted row plus a fresh row for the same pair are
-- allowed (matches the dedupe pattern used elsewhere in the schema).
-- ============================================================================

-- 1. user_checksheet_validations: one live (inspection, validator) row.
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_checksheet_validations_inspection_validator
    ON user_checksheet_validations (inspection_id, data_validator_user_id)
    WHERE deleted_at IS NULL;

-- 2. user_checksheet_approvals: one live (inspection, approver) row.
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_checksheet_approvals_inspection_approver
    ON user_checksheet_approvals (inspection_id, data_approver_user_id)
    WHERE deleted_at IS NULL;
