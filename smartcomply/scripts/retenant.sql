-- ============================================================================
-- AuditPro - Re-tenant (in-place truncate) script
-- ----------------------------------------------------------------------------
-- Purpose : Wipe all audit/operational data + checksheet templates + users
--           from an EXISTING smartcomply database, while keeping:
--             - roles, permissions, role_permissions   (RBAC masters)
--             - departments                             (master data)
--             - lov_data, app_versions
--             - flyway_schema_history                   (NEVER touched)
--
-- Approach: One transaction. session_replication_role='replica' bypasses
--           FK trigger checks during TRUNCATE, so we don't have to drop /
--           recreate any FK constraints. RESTART IDENTITY resets all
--           sequences for the wiped tables to 1.
--
-- Prereqs : 1. Stop the Spring Boot app first.
--           2. Take a full pg_dump backup.
--           3. Connect as a superuser (or rds_superuser on AWS RDS).
--              session_replication_role requires elevated privileges.
--
-- Usage   : psql -h <host> -U <super> -d smartcomply -f scripts/retenant.sql
--
-- After   : Run scripts/seed-users.sql to create the 9 fresh users.
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

SET LOCAL session_replication_role = 'replica';

TRUNCATE TABLE
    -- AI assessments (depends on user_checksheets, chks_question_results)
    ai_assessments,

    -- Judgement files / judgements (per-question feedback on a filled audit)
    usr_chksheet_ans_judgement_files,
    usr_chksheet_ans_judgements,

    -- Trace values & validation/approval workflow on filled audits
    user_checksheet_trace_values,
    user_checksheet_validations_history,
    user_checksheet_validations,
    user_checksheet_approvals_history,
    user_checksheet_approvals,

    -- Filled audit answers
    user_checksheet_matrix_answers,
    user_checksheet_answer_files,
    user_checksheet_answers,
    chks_general_field_values,

    -- Filled audit headers
    user_checksheets,

    -- Surprise & NPD
    npd_master,
    surprise_checksheet_fields,
    surprise_checksheets,

    -- Template question-result children
    chks_question_result_matrices,
    chks_question_result_options,
    chks_question_results,

    -- Template questions & files
    chks_question_files,
    chks_questions,

    -- Template header-data tree & files
    chks_header_data_files,
    chks_header_data,
    chks_headers,

    -- Template general fields
    chks_general_fields,

    -- Template-level validation/approval workflow
    checksheet_validations_history,
    checksheet_validations,
    checksheet_approvals_history,
    checksheet_approvals,

    -- Templates themselves
    checksheets,

    -- Auth & request log
    refresh_token,
    api_history,

    -- User <-> role <-> dept assignments and users
    user_role_departments,
    users
RESTART IDENTITY;

-- ----------------------------------------------------------------------------
-- Detach retained master tables from the now-deleted user rows.
-- Their created_by/updated_by/deleted_by columns previously referenced
-- users.id values that no longer exist.
-- ----------------------------------------------------------------------------
UPDATE departments      SET created_by = NULL, updated_by = NULL, deleted_by = NULL;
UPDATE roles            SET created_by = NULL, updated_by = NULL, deleted_by = NULL;
UPDATE permissions      SET created_by = NULL, updated_by = NULL, deleted_by = NULL;
UPDATE role_permissions SET created_by = NULL;

SET LOCAL session_replication_role = 'origin';

COMMIT;

-- ----------------------------------------------------------------------------
-- Quick verification (read-only)
-- ----------------------------------------------------------------------------
SELECT 'users'                  AS table_name, count(*) FROM users
UNION ALL SELECT 'user_role_departments', count(*) FROM user_role_departments
UNION ALL SELECT 'checksheets',            count(*) FROM checksheets
UNION ALL SELECT 'user_checksheets',       count(*) FROM user_checksheets
UNION ALL SELECT 'user_checksheet_answers',count(*) FROM user_checksheet_answers
UNION ALL SELECT 'ai_assessments',         count(*) FROM ai_assessments
UNION ALL SELECT 'refresh_token',          count(*) FROM refresh_token
UNION ALL SELECT 'api_history',            count(*) FROM api_history
UNION ALL SELECT 'roles (KEEP)',           count(*) FROM roles
UNION ALL SELECT 'permissions (KEEP)',     count(*) FROM permissions
UNION ALL SELECT 'role_permissions (KEEP)',count(*) FROM role_permissions
UNION ALL SELECT 'departments (KEEP)',     count(*) FROM departments
ORDER BY table_name;
