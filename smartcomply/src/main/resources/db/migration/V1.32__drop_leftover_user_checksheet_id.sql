-- V1.32: drop leftover user_checksheet_id columns on review tables.
--
-- V1.28's §4 was supposed to rename user_checksheet_id → inspection_id on
-- the 11 child tables. For user_checksheet_validations and
-- user_checksheet_approvals, the column came back on some DBs — almost
-- certainly Hibernate ddl-auto=update recreating it on a later startup
-- when the entity field had not yet been fully renamed to `inspection`.
-- The column is NOT NULL with a dangling FK to user_checksheets (now
-- merged into inspections), so every validation/approval insert throws
-- "null value in column user_checksheet_id violates not-null constraint"
-- — the entity only writes inspection_id, leaving the leftover NULL.
--
-- History tables (user_checksheet_validations_history /
-- user_checksheet_approvals_history) are already clean per V1.28.
--
-- Idempotent: DROP ... IF EXISTS on both the constraint and the column.

ALTER TABLE user_checksheet_validations
    DROP CONSTRAINT IF EXISTS fk_user_checksheet_validations_user_checksheet_id_user_checkshe;
ALTER TABLE user_checksheet_validations
    DROP COLUMN IF EXISTS user_checksheet_id;

ALTER TABLE user_checksheet_approvals
    DROP CONSTRAINT IF EXISTS fk_user_checksheet_approvals_user_checksheet_id_user_checksheet;
ALTER TABLE user_checksheet_approvals
    DROP COLUMN IF EXISTS user_checksheet_id;
