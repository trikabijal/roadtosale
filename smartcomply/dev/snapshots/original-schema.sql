-- ============================================================================
-- Audit Pro - Original Database Schema
-- Generated from production PostgreSQL database analysis
-- 40 tables (excluding flyway_schema_history), 39 sequences, 89 indexes, 167 FKs
--
-- Schema was originally created by Hibernate ddl-auto, not by Flyway V1.0
-- (which is an empty placeholder). Flyway migrations apply incremental changes.
-- ============================================================================

-- ============================================================================
-- SEQUENCES
-- ============================================================================
-- Sequences are implicitly created by BIGSERIAL columns below.
-- Listed here for documentation:
--   api_history_id_seq, app_versions_id_seq, checksheet_approvals_id_seq,
--   checksheet_approvals_history_id_seq, checksheets_id_seq,
--   checksheet_validations_id_seq, checksheet_validations_history_id_seq,
--   chks_general_fields_id_seq, chks_general_field_values_id_seq,
--   chks_header_data_id_seq, chks_header_data_files_id_seq, chks_headers_id_seq,
--   chks_question_files_id_seq, chks_question_result_matrices_id_seq,
--   chks_question_result_options_id_seq, chks_question_results_id_seq,
--   chks_questions_id_seq, departments_id_seq, lov_data_id_seq,
--   npd_master_id_seq, permissions_id_seq, refresh_token_id_seq,
--   role_permissions_id_seq, roles_id_seq, surprise_checksheet_fields_id_seq,
--   surprise_checksheets_id_seq, user_checksheet_answers_id_seq,
--   user_checksheet_approvals_id_seq, user_checksheet_approvals_history_id_seq,
--   user_checksheet_matrix_answers_id_seq, user_checksheets_id_seq,
--   user_checksheet_trace_values_id_seq, user_checksheet_validations_id_seq,
--   user_checksheet_validations_history_id_seq, user_role_departments_id_seq,
--   users_id_seq, usr_chksheet_ans_judgement_files_id_seq,
--   usr_chksheet_ans_judgements_id_seq
--   (user_checksheet_answer_files_id_seq -- for the extra table)

-- ============================================================================
-- TABLE CREATION (ordered by dependency)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. users (no FK dependencies except self-referencing)
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    email VARCHAR,
    fail_login_count INTEGER DEFAULT 0,
    first_name VARCHAR(255),
    jwt_token TEXT,
    last_name VARCHAR(255),
    lock_time TIMESTAMP,
    mobile VARCHAR,
    password VARCHAR(255),
    resend_otp_time TIMESTAMP,
    status VARCHAR(8),
    updated_at TIMESTAMP,
    user_details TEXT,
    username VARCHAR(255),
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 2. roles
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    name VARCHAR,
    role_code VARCHAR,
    updated_at TIMESTAMP,
    created_by BIGINT,
    deleted_by BIGINT,
    role_id BIGINT,
    updated_by BIGINT,
    parent_role_id BIGINT
);

-- ---------------------------------------------------------------------------
-- 3. permissions
-- ---------------------------------------------------------------------------
CREATE TABLE permissions (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    description TEXT,
    name VARCHAR(255) NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 4. role_permissions
-- ---------------------------------------------------------------------------
CREATE TABLE role_permissions (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    permission_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 5. departments
-- ---------------------------------------------------------------------------
CREATE TABLE departments (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    name VARCHAR,
    updated_at TIMESTAMP,
    created_by BIGINT,
    deleted_by BIGINT,
    department_id BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 6. lov_data
-- ---------------------------------------------------------------------------
CREATE TABLE lov_data (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    name VARCHAR NOT NULL,
    value TEXT NOT NULL,
    value_type VARCHAR NOT NULL
);

-- ---------------------------------------------------------------------------
-- 7. user_role_departments
-- ---------------------------------------------------------------------------
CREATE TABLE user_role_departments (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by BIGINT,
    deleted_by BIGINT,
    department_id BIGINT,
    role_id BIGINT NOT NULL,
    updated_by BIGINT,
    user_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 8. refresh_token
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_token (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    device_type VARCHAR(8),
    expiry_date TIMESTAMP WITH TIME ZONE NOT NULL,
    token VARCHAR(255) NOT NULL,
    user_id BIGINT
);

-- ---------------------------------------------------------------------------
-- 9. api_history
-- ---------------------------------------------------------------------------
CREATE TABLE api_history (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    method TEXT,
    request TEXT,
    request_headers TEXT,
    request_uri TEXT,
    response TEXT
);

-- ---------------------------------------------------------------------------
-- 10. app_versions
-- ---------------------------------------------------------------------------
CREATE TABLE app_versions (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_forcefully_update BOOLEAN DEFAULT false,
    os VARCHAR(255),
    updated_at TIMESTAMP,
    url VARCHAR(512),
    version BIGINT,
    version_name VARCHAR(255)
);

-- ---------------------------------------------------------------------------
-- 11. checksheets
-- ---------------------------------------------------------------------------
CREATE TABLE checksheets (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    alert_to_user_ids BIGINT[],
    approver_user_ids BIGINT[],
    asset_code VARCHAR,
    checksheet_type VARCHAR NOT NULL DEFAULT 'PRIVATE'::character varying,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_approver_user_ids BIGINT[],
    data_validator_user_ids BIGINT[],
    deleted_at TIMESTAMP,
    description TEXT,
    escalate_to_user_ids BIGINT[],
    escalation_guidelines_days BIGINT,
    expiry_date DATE,
    frequency_of_check VARCHAR,
    implementation_date TIMESTAMP,
    is_file_upload BOOLEAN,
    model_no VARCHAR(255),
    name VARCHAR,
    operator_user_ids BIGINT[],
    path VARCHAR,
    serial_number VARCHAR(10),
    status VARCHAR NOT NULL,
    submitted_at TIMESTAMP,
    uid VARCHAR,
    updated_at TIMESTAMP,
    validate_or_approve_version BIGINT NOT NULL DEFAULT 0,
    validator_user_ids BIGINT[],
    version BIGINT DEFAULT 0,
    version_remark VARCHAR,
    waiting_user_ids INTEGER[],
    alert_to_user_id BIGINT,
    checksheet_id BIGINT,
    created_by BIGINT,
    deleted_by BIGINT,
    department_id BIGINT,
    escalate_to_user_id BIGINT,
    preparer_user_id BIGINT,
    updated_by BIGINT,
    frequency_of_freq_of_chk SMALLINT,
    npd_day VARCHAR[] DEFAULT '{}'::character varying[],
    is_corporate VARCHAR
);

-- ---------------------------------------------------------------------------
-- 12. checksheet_approvals
-- ---------------------------------------------------------------------------
CREATE TABLE checksheet_approvals (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    approved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    remarks TEXT,
    status VARCHAR NOT NULL,
    updated_at TIMESTAMP,
    approver_user_id BIGINT NOT NULL,
    checksheet_id BIGINT NOT NULL,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 13. checksheet_approvals_history
-- ---------------------------------------------------------------------------
CREATE TABLE checksheet_approvals_history (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    approved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remarks TEXT,
    status VARCHAR NOT NULL,
    version BIGINT NOT NULL,
    approver_user_id BIGINT NOT NULL,
    checksheet_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 14. checksheet_validations
-- ---------------------------------------------------------------------------
CREATE TABLE checksheet_validations (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    remarks TEXT,
    status VARCHAR NOT NULL,
    updated_at TIMESTAMP,
    validated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    validator_user_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 15. checksheet_validations_history
-- ---------------------------------------------------------------------------
CREATE TABLE checksheet_validations_history (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remarks TEXT,
    status VARCHAR NOT NULL,
    validated_at TIMESTAMP,
    version BIGINT NOT NULL,
    checksheet_id BIGINT NOT NULL,
    validator_user_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 16. chks_headers
-- ---------------------------------------------------------------------------
CREATE TABLE chks_headers (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    is_result_column BOOLEAN DEFAULT false,
    is_traceable BOOLEAN,
    name VARCHAR NOT NULL,
    summary_report_level SMALLINT,
    updated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    chks_header_id BIGINT,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 17. chks_header_data
-- ---------------------------------------------------------------------------
CREATE TABLE chks_header_data (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    description TEXT,
    level BIGINT,
    name VARCHAR NOT NULL,
    updated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    chks_header_id BIGINT NOT NULL,
    chks_header_data_id BIGINT,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    order_no INTEGER
);

-- ---------------------------------------------------------------------------
-- 18. chks_header_data_files
-- ---------------------------------------------------------------------------
CREATE TABLE chks_header_data_files (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    path VARCHAR,
    updated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    chks_header_data_id BIGINT,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 19. chks_questions
-- ---------------------------------------------------------------------------
CREATE TABLE chks_questions (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    description TEXT,
    name VARCHAR NOT NULL,
    updated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    chks_header_id BIGINT NOT NULL,
    chks_header_data_id BIGINT,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    order_no INTEGER
);

-- ---------------------------------------------------------------------------
-- 20. chks_question_files
-- ---------------------------------------------------------------------------
CREATE TABLE chks_question_files (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    path VARCHAR,
    updated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    chks_question_id BIGINT,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 21. chks_question_results
-- ---------------------------------------------------------------------------
CREATE TABLE chks_question_results (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    answer_type VARCHAR,
    chks_matrix_col_name VARCHAR,
    chks_matrix_row_name VARCHAR,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    lower_limit DOUBLE PRECISION,
    matrix_column_header_names VARCHAR[],
    matrix_file_location VARCHAR,
    matrix_name VARCHAR,
    matrix_row_header_names VARCHAR[],
    no_of_columns BIGINT,
    no_of_results BIGINT,
    no_of_rows BIGINT,
    objective_type VARCHAR,
    unit VARCHAR,
    updated_at TIMESTAMP,
    upper_limit DOUBLE PRECISION,
    checksheet_id BIGINT NOT NULL,
    chks_header_id BIGINT NOT NULL,
    chks_question_id BIGINT NOT NULL,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    is_optional BOOLEAN DEFAULT false
);

-- ---------------------------------------------------------------------------
-- 22. chks_question_result_options
-- ---------------------------------------------------------------------------
CREATE TABLE chks_question_result_options (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    judgement VARCHAR NOT NULL,
    option VARCHAR,
    updated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    chks_question_result_id BIGINT NOT NULL,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 23. chks_question_result_matrices
-- ---------------------------------------------------------------------------
CREATE TABLE chks_question_result_matrices (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    chks_matrix_col_hdr VARCHAR,
    chks_matrix_row_hdr VARCHAR,
    column_id BIGINT,
    comment VARCHAR,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data VARCHAR,
    deleted_at TIMESTAMP,
    row_id BIGINT,
    updated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    chks_question_result_id BIGINT,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 24. chks_general_fields
-- ---------------------------------------------------------------------------
CREATE TABLE chks_general_fields (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    name VARCHAR NOT NULL,
    updated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 25. user_checksheets
-- ---------------------------------------------------------------------------
CREATE TABLE user_checksheets (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    shift VARCHAR,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR,
    submission_version SMALLINT NOT NULL DEFAULT 0,
    submitted_at TIMESTAMP,
    updated_at TIMESTAMP,
    waiting_user_ids INTEGER[],
    checksheet_id BIGINT NOT NULL,
    created_by BIGINT,
    deleted_by BIGINT,
    operator_user_id BIGINT,
    updated_by BIGINT,
    frequency_of_freq_of_chk_cnt SMALLINT
);

-- ---------------------------------------------------------------------------
-- 26. chks_general_field_values
-- ---------------------------------------------------------------------------
CREATE TABLE chks_general_field_values (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    updated_at TIMESTAMP,
    value VARCHAR,
    chks_general_field_id BIGINT NOT NULL,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    user_checksheet_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 27. user_checksheet_answers
-- ---------------------------------------------------------------------------
CREATE TABLE user_checksheet_answers (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    answer VARCHAR,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    judgement SMALLINT,
    updated_at TIMESTAMP,
    chks_question_id BIGINT,
    chks_question_result_id BIGINT,
    chks_question_rslt_option_id BIGINT,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    user_checksheet_id BIGINT NOT NULL,
    answered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_not_applicable BOOLEAN DEFAULT false
);

-- ---------------------------------------------------------------------------
-- 28. user_checksheet_answer_files (extra table -- not in JPA entities)
-- ---------------------------------------------------------------------------
CREATE TABLE user_checksheet_answer_files (
    id BIGSERIAL NOT NULL PRIMARY KEY
    -- Column details could not be fully retrieved due to permission issues
    -- This table exists in the database but is not mapped in any JPA entity
);

-- ---------------------------------------------------------------------------
-- 29. user_checksheet_matrix_answers
-- ---------------------------------------------------------------------------
CREATE TABLE user_checksheet_matrix_answers (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    judgement SMALLINT,
    mc_result VARCHAR,
    order_no INTEGER NOT NULL,
    result VARCHAR,
    updated_at TIMESTAMP,
    chks_question_id BIGINT,
    chks_question_result_id BIGINT,
    chks_question_result_matrix_id BIGINT,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    user_checksheet_id BIGINT NOT NULL,
    answered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- 30. user_checksheet_approvals
-- ---------------------------------------------------------------------------
CREATE TABLE user_checksheet_approvals (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    approved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    remarks TEXT,
    status VARCHAR NOT NULL,
    updated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    created_by BIGINT,
    data_approver_user_id BIGINT NOT NULL,
    deleted_by BIGINT,
    updated_by BIGINT,
    user_checksheet_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 31. user_checksheet_approvals_history
-- ---------------------------------------------------------------------------
CREATE TABLE user_checksheet_approvals_history (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    approved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remarks TEXT,
    status VARCHAR NOT NULL,
    version SMALLINT NOT NULL,
    checksheet_id BIGINT NOT NULL,
    data_approver_user_id BIGINT NOT NULL,
    user_checksheet_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 32. user_checksheet_validations
-- ---------------------------------------------------------------------------
CREATE TABLE user_checksheet_validations (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    remarks TEXT,
    status VARCHAR NOT NULL,
    updated_at TIMESTAMP,
    validated_at TIMESTAMP,
    checksheet_id BIGINT NOT NULL,
    created_by BIGINT,
    data_validator_user_id BIGINT NOT NULL,
    deleted_by BIGINT,
    updated_by BIGINT,
    user_checksheet_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 33. user_checksheet_validations_history
-- ---------------------------------------------------------------------------
CREATE TABLE user_checksheet_validations_history (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remarks TEXT,
    status VARCHAR NOT NULL,
    validated_at TIMESTAMP,
    version SMALLINT NOT NULL,
    checksheet_id BIGINT NOT NULL,
    data_validator_user_id BIGINT NOT NULL,
    user_checksheet_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 34. user_checksheet_trace_values
-- ---------------------------------------------------------------------------
CREATE TABLE user_checksheet_trace_values (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    trace_value VARCHAR NOT NULL,
    updated_at TIMESTAMP,
    chks_header_data_id BIGINT NOT NULL,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    user_checksheet_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 35. usr_chksheet_ans_judgements
-- ---------------------------------------------------------------------------
CREATE TABLE usr_chksheet_ans_judgements (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    judgement VARCHAR(10),
    remarks VARCHAR,
    updated_at TIMESTAMP,
    chks_question_id BIGINT NOT NULL,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    user_checksheet_id BIGINT NOT NULL
);

-- ---------------------------------------------------------------------------
-- 36. usr_chksheet_ans_judgement_files
-- ---------------------------------------------------------------------------
CREATE TABLE usr_chksheet_ans_judgement_files (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    path VARCHAR,
    updated_at TIMESTAMP,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT,
    user_checksheet_id BIGINT NOT NULL,
    usr_chksheet_ans_judgement_id BIGINT
);

-- ---------------------------------------------------------------------------
-- 37. surprise_checksheets
-- ---------------------------------------------------------------------------
CREATE TABLE surprise_checksheets (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    name VARCHAR NOT NULL,
    updated_at TIMESTAMP,
    created_by BIGINT,
    deleted_by BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 38. surprise_checksheet_fields
-- ---------------------------------------------------------------------------
CREATE TABLE surprise_checksheet_fields (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    concern TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creation_date DATE,
    deleted_at TIMESTAMP,
    file_paths VARCHAR[],
    remarks TEXT,
    updated_at TIMESTAMP,
    created_by BIGINT,
    deleted_by BIGINT,
    department_id BIGINT,
    responsible_user_id BIGINT,
    surprise_checksheet_id BIGINT,
    updated_by BIGINT
);

-- ---------------------------------------------------------------------------
-- 39. npd_master
-- ---------------------------------------------------------------------------
CREATE TABLE npd_master (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_exception BOOLEAN NOT NULL DEFAULT false,
    npd_date DATE NOT NULL DEFAULT CURRENT_DATE,
    shift VARCHAR(16),
    checksheet_id BIGINT,
    created_by BIGINT,
    remarks TEXT,
    updated_at TIMESTAMP,
    updated_by BIGINT
);


-- ============================================================================
-- INDEXES (89 total)
-- ============================================================================

-- Hibernate-generated indexes (primary keys are auto-indexed)
-- These are the non-PK indexes found in the database:

-- checksheets indexes
CREATE INDEX IF NOT EXISTS idx_checksheets_validator_any ON checksheets USING gin(validator_user_ids array_ops);
CREATE INDEX IF NOT EXISTS idx_checksheets_approver_any ON checksheets USING gin(approver_user_ids array_ops);
CREATE INDEX IF NOT EXISTS idx_checksheets_data_validator_any ON checksheets USING gin(data_validator_user_ids array_ops);
CREATE INDEX IF NOT EXISTS idx_checksheets_data_approver_any ON checksheets USING gin(data_approver_user_ids array_ops);
CREATE INDEX IF NOT EXISTS idx_checksheets_operator_any ON checksheets USING gin(operator_user_ids array_ops);

-- permissions indexes
CREATE INDEX IF NOT EXISTS idx_permissions_code ON permissions(permission_code);
CREATE INDEX IF NOT EXISTS idx_permissions_deleted_at ON permissions(deleted_at);

-- role_permissions indexes
CREATE INDEX IF NOT EXISTS idx_role_permissions_role_id ON role_permissions(role_id);
CREATE INDEX IF NOT EXISTS idx_role_permissions_permission_id ON role_permissions(permission_id);

-- roles indexes
CREATE INDEX IF NOT EXISTS idx_roles_parent_role_id ON roles(parent_role_id);

-- Hibernate auto-generated FK indexes
-- (Hibernate creates an index for each FK column automatically)
CREATE INDEX IF NOT EXISTS idx_checksheet_approvals_approver_user_id ON checksheet_approvals(approver_user_id);
CREATE INDEX IF NOT EXISTS idx_checksheet_approvals_checksheet_id ON checksheet_approvals(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_checksheet_approvals_created_by ON checksheet_approvals(created_by);
CREATE INDEX IF NOT EXISTS idx_checksheet_approvals_deleted_by ON checksheet_approvals(deleted_by);
CREATE INDEX IF NOT EXISTS idx_checksheet_approvals_updated_by ON checksheet_approvals(updated_by);

CREATE INDEX IF NOT EXISTS idx_checksheet_approvals_history_approver_user_id ON checksheet_approvals_history(approver_user_id);
CREATE INDEX IF NOT EXISTS idx_checksheet_approvals_history_checksheet_id ON checksheet_approvals_history(checksheet_id);

CREATE INDEX IF NOT EXISTS idx_checksheet_validations_checksheet_id ON checksheet_validations(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_checksheet_validations_created_by ON checksheet_validations(created_by);
CREATE INDEX IF NOT EXISTS idx_checksheet_validations_deleted_by ON checksheet_validations(deleted_by);
CREATE INDEX IF NOT EXISTS idx_checksheet_validations_updated_by ON checksheet_validations(updated_by);
CREATE INDEX IF NOT EXISTS idx_checksheet_validations_validator_user_id ON checksheet_validations(validator_user_id);

CREATE INDEX IF NOT EXISTS idx_checksheet_validations_history_checksheet_id ON checksheet_validations_history(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_checksheet_validations_history_validator_user_id ON checksheet_validations_history(validator_user_id);

CREATE INDEX IF NOT EXISTS idx_checksheets_alert_to_user_id ON checksheets(alert_to_user_id);
CREATE INDEX IF NOT EXISTS idx_checksheets_checksheet_id ON checksheets(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_checksheets_created_by ON checksheets(created_by);
CREATE INDEX IF NOT EXISTS idx_checksheets_deleted_by ON checksheets(deleted_by);
CREATE INDEX IF NOT EXISTS idx_checksheets_department_id ON checksheets(department_id);
CREATE INDEX IF NOT EXISTS idx_checksheets_escalate_to_user_id ON checksheets(escalate_to_user_id);
CREATE INDEX IF NOT EXISTS idx_checksheets_preparer_user_id ON checksheets(preparer_user_id);
CREATE INDEX IF NOT EXISTS idx_checksheets_updated_by ON checksheets(updated_by);

CREATE INDEX IF NOT EXISTS idx_chks_general_fields_checksheet_id ON chks_general_fields(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_chks_general_fields_created_by ON chks_general_fields(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_general_fields_deleted_by ON chks_general_fields(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_general_fields_updated_by ON chks_general_fields(updated_by);

CREATE INDEX IF NOT EXISTS idx_chks_general_field_values_chks_general_field_id ON chks_general_field_values(chks_general_field_id);
CREATE INDEX IF NOT EXISTS idx_chks_general_field_values_created_by ON chks_general_field_values(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_general_field_values_deleted_by ON chks_general_field_values(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_general_field_values_updated_by ON chks_general_field_values(updated_by);
CREATE INDEX IF NOT EXISTS idx_chks_general_field_values_user_checksheet_id ON chks_general_field_values(user_checksheet_id);

CREATE INDEX IF NOT EXISTS idx_chks_header_data_checksheet_id ON chks_header_data(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_chks_header_data_chks_header_id ON chks_header_data(chks_header_id);
CREATE INDEX IF NOT EXISTS idx_chks_header_data_chks_header_data_id ON chks_header_data(chks_header_data_id);
CREATE INDEX IF NOT EXISTS idx_chks_header_data_created_by ON chks_header_data(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_header_data_deleted_by ON chks_header_data(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_header_data_updated_by ON chks_header_data(updated_by);

CREATE INDEX IF NOT EXISTS idx_chks_header_data_files_checksheet_id ON chks_header_data_files(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_chks_header_data_files_chks_header_data_id ON chks_header_data_files(chks_header_data_id);
CREATE INDEX IF NOT EXISTS idx_chks_header_data_files_created_by ON chks_header_data_files(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_header_data_files_deleted_by ON chks_header_data_files(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_header_data_files_updated_by ON chks_header_data_files(updated_by);

CREATE INDEX IF NOT EXISTS idx_chks_headers_checksheet_id ON chks_headers(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_chks_headers_chks_header_id ON chks_headers(chks_header_id);
CREATE INDEX IF NOT EXISTS idx_chks_headers_created_by ON chks_headers(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_headers_deleted_by ON chks_headers(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_headers_updated_by ON chks_headers(updated_by);

CREATE INDEX IF NOT EXISTS idx_chks_question_files_checksheet_id ON chks_question_files(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_chks_question_files_chks_question_id ON chks_question_files(chks_question_id);
CREATE INDEX IF NOT EXISTS idx_chks_question_files_created_by ON chks_question_files(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_question_files_deleted_by ON chks_question_files(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_question_files_updated_by ON chks_question_files(updated_by);

CREATE INDEX IF NOT EXISTS idx_chks_question_result_matrices_checksheet_id ON chks_question_result_matrices(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_chks_question_result_matrices_chks_question_result_id ON chks_question_result_matrices(chks_question_result_id);
CREATE INDEX IF NOT EXISTS idx_chks_question_result_matrices_created_by ON chks_question_result_matrices(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_question_result_matrices_deleted_by ON chks_question_result_matrices(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_question_result_matrices_updated_by ON chks_question_result_matrices(updated_by);

CREATE INDEX IF NOT EXISTS idx_chks_question_result_options_checksheet_id ON chks_question_result_options(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_chks_question_result_options_chks_question_result_id ON chks_question_result_options(chks_question_result_id);
CREATE INDEX IF NOT EXISTS idx_chks_question_result_options_created_by ON chks_question_result_options(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_question_result_options_deleted_by ON chks_question_result_options(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_question_result_options_updated_by ON chks_question_result_options(updated_by);

CREATE INDEX IF NOT EXISTS idx_chks_question_results_checksheet_id ON chks_question_results(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_chks_question_results_chks_header_id ON chks_question_results(chks_header_id);
CREATE INDEX IF NOT EXISTS idx_chks_question_results_chks_question_id ON chks_question_results(chks_question_id);
CREATE INDEX IF NOT EXISTS idx_chks_question_results_created_by ON chks_question_results(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_question_results_deleted_by ON chks_question_results(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_question_results_updated_by ON chks_question_results(updated_by);

CREATE INDEX IF NOT EXISTS idx_chks_questions_checksheet_id ON chks_questions(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_chks_questions_chks_header_id ON chks_questions(chks_header_id);
CREATE INDEX IF NOT EXISTS idx_chks_questions_chks_header_data_id ON chks_questions(chks_header_data_id);
CREATE INDEX IF NOT EXISTS idx_chks_questions_created_by ON chks_questions(created_by);
CREATE INDEX IF NOT EXISTS idx_chks_questions_deleted_by ON chks_questions(deleted_by);
CREATE INDEX IF NOT EXISTS idx_chks_questions_updated_by ON chks_questions(updated_by);

CREATE INDEX IF NOT EXISTS idx_departments_created_by ON departments(created_by);
CREATE INDEX IF NOT EXISTS idx_departments_deleted_by ON departments(deleted_by);
CREATE INDEX IF NOT EXISTS idx_departments_department_id ON departments(department_id);
CREATE INDEX IF NOT EXISTS idx_departments_updated_by ON departments(updated_by);

CREATE INDEX IF NOT EXISTS idx_npd_master_checksheet_id ON npd_master(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_npd_master_created_by ON npd_master(created_by);
CREATE INDEX IF NOT EXISTS idx_npd_master_updated_by ON npd_master(updated_by);

CREATE INDEX IF NOT EXISTS idx_permissions_created_by ON permissions(created_by);
CREATE INDEX IF NOT EXISTS idx_permissions_deleted_by_fk ON permissions(deleted_by);
CREATE INDEX IF NOT EXISTS idx_permissions_updated_by ON permissions(updated_by);

CREATE INDEX IF NOT EXISTS idx_refresh_token_user_id ON refresh_token(user_id);

CREATE INDEX IF NOT EXISTS idx_roles_created_by ON roles(created_by);
CREATE INDEX IF NOT EXISTS idx_roles_deleted_by ON roles(deleted_by);
CREATE INDEX IF NOT EXISTS idx_roles_role_id ON roles(role_id);
CREATE INDEX IF NOT EXISTS idx_roles_updated_by ON roles(updated_by);

CREATE INDEX IF NOT EXISTS idx_surprise_checksheet_fields_created_by ON surprise_checksheet_fields(created_by);
CREATE INDEX IF NOT EXISTS idx_surprise_checksheet_fields_deleted_by ON surprise_checksheet_fields(deleted_by);
CREATE INDEX IF NOT EXISTS idx_surprise_checksheet_fields_department_id ON surprise_checksheet_fields(department_id);
CREATE INDEX IF NOT EXISTS idx_surprise_checksheet_fields_responsible_user_id ON surprise_checksheet_fields(responsible_user_id);
CREATE INDEX IF NOT EXISTS idx_surprise_checksheet_fields_surprise_checksheet_id ON surprise_checksheet_fields(surprise_checksheet_id);
CREATE INDEX IF NOT EXISTS idx_surprise_checksheet_fields_updated_by ON surprise_checksheet_fields(updated_by);

CREATE INDEX IF NOT EXISTS idx_surprise_checksheets_created_by ON surprise_checksheets(created_by);
CREATE INDEX IF NOT EXISTS idx_surprise_checksheets_deleted_by ON surprise_checksheets(deleted_by);
CREATE INDEX IF NOT EXISTS idx_surprise_checksheets_updated_by ON surprise_checksheets(updated_by);

CREATE INDEX IF NOT EXISTS idx_user_checksheet_answers_chks_question_id ON user_checksheet_answers(chks_question_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_answers_chks_question_result_id ON user_checksheet_answers(chks_question_result_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_answers_chks_question_rslt_option_id ON user_checksheet_answers(chks_question_rslt_option_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_answers_created_by ON user_checksheet_answers(created_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_answers_deleted_by ON user_checksheet_answers(deleted_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_answers_updated_by ON user_checksheet_answers(updated_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_answers_user_checksheet_id ON user_checksheet_answers(user_checksheet_id);

CREATE INDEX IF NOT EXISTS idx_user_checksheet_approvals_checksheet_id ON user_checksheet_approvals(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_approvals_created_by ON user_checksheet_approvals(created_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_approvals_data_approver_user_id ON user_checksheet_approvals(data_approver_user_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_approvals_deleted_by ON user_checksheet_approvals(deleted_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_approvals_updated_by ON user_checksheet_approvals(updated_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_approvals_user_checksheet_id ON user_checksheet_approvals(user_checksheet_id);

CREATE INDEX IF NOT EXISTS idx_user_checksheet_approvals_history_checksheet_id ON user_checksheet_approvals_history(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_approvals_history_data_approver_user_id ON user_checksheet_approvals_history(data_approver_user_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_approvals_history_user_checksheet_id ON user_checksheet_approvals_history(user_checksheet_id);

CREATE INDEX IF NOT EXISTS idx_user_checksheet_matrix_answers_chks_question_id ON user_checksheet_matrix_answers(chks_question_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_matrix_answers_chks_question_result_id ON user_checksheet_matrix_answers(chks_question_result_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_matrix_answers_chks_question_result_matrix_id ON user_checksheet_matrix_answers(chks_question_result_matrix_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_matrix_answers_created_by ON user_checksheet_matrix_answers(created_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_matrix_answers_deleted_by ON user_checksheet_matrix_answers(deleted_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_matrix_answers_updated_by ON user_checksheet_matrix_answers(updated_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_matrix_answers_user_checksheet_id ON user_checksheet_matrix_answers(user_checksheet_id);

CREATE INDEX IF NOT EXISTS idx_user_checksheet_trace_values_chks_header_data_id ON user_checksheet_trace_values(chks_header_data_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_trace_values_created_by ON user_checksheet_trace_values(created_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_trace_values_deleted_by ON user_checksheet_trace_values(deleted_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_trace_values_updated_by ON user_checksheet_trace_values(updated_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_trace_values_user_checksheet_id ON user_checksheet_trace_values(user_checksheet_id);

CREATE INDEX IF NOT EXISTS idx_user_checksheet_validations_checksheet_id ON user_checksheet_validations(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_validations_created_by ON user_checksheet_validations(created_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_validations_data_validator_user_id ON user_checksheet_validations(data_validator_user_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_validations_deleted_by ON user_checksheet_validations(deleted_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_validations_updated_by ON user_checksheet_validations(updated_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_validations_user_checksheet_id ON user_checksheet_validations(user_checksheet_id);

CREATE INDEX IF NOT EXISTS idx_user_checksheet_validations_history_checksheet_id ON user_checksheet_validations_history(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_validations_history_data_validator_user_id ON user_checksheet_validations_history(data_validator_user_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheet_validations_history_user_checksheet_id ON user_checksheet_validations_history(user_checksheet_id);

CREATE INDEX IF NOT EXISTS idx_user_checksheets_checksheet_id ON user_checksheets(checksheet_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheets_created_by ON user_checksheets(created_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheets_deleted_by ON user_checksheets(deleted_by);
CREATE INDEX IF NOT EXISTS idx_user_checksheets_operator_user_id ON user_checksheets(operator_user_id);
CREATE INDEX IF NOT EXISTS idx_user_checksheets_updated_by ON user_checksheets(updated_by);

CREATE INDEX IF NOT EXISTS idx_user_role_departments_created_by ON user_role_departments(created_by);
CREATE INDEX IF NOT EXISTS idx_user_role_departments_deleted_by ON user_role_departments(deleted_by);
CREATE INDEX IF NOT EXISTS idx_user_role_departments_department_id ON user_role_departments(department_id);
CREATE INDEX IF NOT EXISTS idx_user_role_departments_role_id ON user_role_departments(role_id);
CREATE INDEX IF NOT EXISTS idx_user_role_departments_updated_by ON user_role_departments(updated_by);
CREATE INDEX IF NOT EXISTS idx_user_role_departments_user_id ON user_role_departments(user_id);

CREATE INDEX IF NOT EXISTS idx_users_created_by ON users(created_by);
CREATE INDEX IF NOT EXISTS idx_users_deleted_by ON users(deleted_by);
CREATE INDEX IF NOT EXISTS idx_users_updated_by ON users(updated_by);

CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgement_files_created_by ON usr_chksheet_ans_judgement_files(created_by);
CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgement_files_deleted_by ON usr_chksheet_ans_judgement_files(deleted_by);
CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgement_files_updated_by ON usr_chksheet_ans_judgement_files(updated_by);
CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgement_files_user_checksheet_id ON usr_chksheet_ans_judgement_files(user_checksheet_id);
CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgement_files_usr_chksheet_ans_judgement_id ON usr_chksheet_ans_judgement_files(usr_chksheet_ans_judgement_id);

CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgements_chks_question_id ON usr_chksheet_ans_judgements(chks_question_id);
CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgements_created_by ON usr_chksheet_ans_judgements(created_by);
CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgements_deleted_by ON usr_chksheet_ans_judgements(deleted_by);
CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgements_updated_by ON usr_chksheet_ans_judgements(updated_by);
CREATE INDEX IF NOT EXISTS idx_usr_chksheet_ans_judgements_user_checksheet_id ON usr_chksheet_ans_judgements(user_checksheet_id);


-- ============================================================================
-- FOREIGN KEYS (167 total -- created by Hibernate, not Flyway)
-- ============================================================================

-- users self-references
ALTER TABLE users ADD CONSTRAINT fk_users_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE users ADD CONSTRAINT fk_users_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE users ADD CONSTRAINT fk_users_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- roles
ALTER TABLE roles ADD CONSTRAINT fk_roles_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE roles ADD CONSTRAINT fk_roles_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE roles ADD CONSTRAINT fk_roles_role_id FOREIGN KEY (role_id) REFERENCES roles(id);
ALTER TABLE roles ADD CONSTRAINT fk_roles_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE roles ADD CONSTRAINT fk_roles_parent_role_id FOREIGN KEY (parent_role_id) REFERENCES roles(id);

-- permissions
ALTER TABLE permissions ADD CONSTRAINT fk_permissions_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE permissions ADD CONSTRAINT fk_permissions_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE permissions ADD CONSTRAINT fk_permissions_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- role_permissions
ALTER TABLE role_permissions ADD CONSTRAINT fk_role_permissions_role_id FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE;
ALTER TABLE role_permissions ADD CONSTRAINT fk_role_permissions_permission_id FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE;

-- departments
ALTER TABLE departments ADD CONSTRAINT fk_departments_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE departments ADD CONSTRAINT fk_departments_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE departments ADD CONSTRAINT fk_departments_department_id FOREIGN KEY (department_id) REFERENCES departments(id);
ALTER TABLE departments ADD CONSTRAINT fk_departments_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- user_role_departments
ALTER TABLE user_role_departments ADD CONSTRAINT fk_urd_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE user_role_departments ADD CONSTRAINT fk_urd_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE user_role_departments ADD CONSTRAINT fk_urd_department_id FOREIGN KEY (department_id) REFERENCES departments(id);
ALTER TABLE user_role_departments ADD CONSTRAINT fk_urd_role_id FOREIGN KEY (role_id) REFERENCES roles(id);
ALTER TABLE user_role_departments ADD CONSTRAINT fk_urd_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE user_role_departments ADD CONSTRAINT fk_urd_user_id FOREIGN KEY (user_id) REFERENCES users(id);

-- refresh_token
ALTER TABLE refresh_token ADD CONSTRAINT fk_refresh_token_user_id FOREIGN KEY (user_id) REFERENCES users(id);

-- checksheets
ALTER TABLE checksheets ADD CONSTRAINT fk_checksheets_alert_to_user_id FOREIGN KEY (alert_to_user_id) REFERENCES users(id);
ALTER TABLE checksheets ADD CONSTRAINT fk_checksheets_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE checksheets ADD CONSTRAINT fk_checksheets_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE checksheets ADD CONSTRAINT fk_checksheets_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE checksheets ADD CONSTRAINT fk_checksheets_department_id FOREIGN KEY (department_id) REFERENCES departments(id);
ALTER TABLE checksheets ADD CONSTRAINT fk_checksheets_escalate_to_user_id FOREIGN KEY (escalate_to_user_id) REFERENCES users(id);
ALTER TABLE checksheets ADD CONSTRAINT fk_checksheets_preparer_user_id FOREIGN KEY (preparer_user_id) REFERENCES users(id);
ALTER TABLE checksheets ADD CONSTRAINT fk_checksheets_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- checksheet_approvals
ALTER TABLE checksheet_approvals ADD CONSTRAINT fk_ca_approver_user_id FOREIGN KEY (approver_user_id) REFERENCES users(id);
ALTER TABLE checksheet_approvals ADD CONSTRAINT fk_ca_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE checksheet_approvals ADD CONSTRAINT fk_ca_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE checksheet_approvals ADD CONSTRAINT fk_ca_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE checksheet_approvals ADD CONSTRAINT fk_ca_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- checksheet_approvals_history
ALTER TABLE checksheet_approvals_history ADD CONSTRAINT fk_cah_approver_user_id FOREIGN KEY (approver_user_id) REFERENCES users(id);
ALTER TABLE checksheet_approvals_history ADD CONSTRAINT fk_cah_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);

-- checksheet_validations
ALTER TABLE checksheet_validations ADD CONSTRAINT fk_cv_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE checksheet_validations ADD CONSTRAINT fk_cv_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE checksheet_validations ADD CONSTRAINT fk_cv_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE checksheet_validations ADD CONSTRAINT fk_cv_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE checksheet_validations ADD CONSTRAINT fk_cv_validator_user_id FOREIGN KEY (validator_user_id) REFERENCES users(id);

-- checksheet_validations_history
ALTER TABLE checksheet_validations_history ADD CONSTRAINT fk_cvh_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE checksheet_validations_history ADD CONSTRAINT fk_cvh_validator_user_id FOREIGN KEY (validator_user_id) REFERENCES users(id);

-- chks_headers
ALTER TABLE chks_headers ADD CONSTRAINT fk_ch_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE chks_headers ADD CONSTRAINT fk_ch_chks_header_id FOREIGN KEY (chks_header_id) REFERENCES chks_headers(id);
ALTER TABLE chks_headers ADD CONSTRAINT fk_ch_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_headers ADD CONSTRAINT fk_ch_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_headers ADD CONSTRAINT fk_ch_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- chks_header_data
ALTER TABLE chks_header_data ADD CONSTRAINT fk_chd_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE chks_header_data ADD CONSTRAINT fk_chd_chks_header_id FOREIGN KEY (chks_header_id) REFERENCES chks_headers(id);
ALTER TABLE chks_header_data ADD CONSTRAINT fk_chd_chks_header_data_id FOREIGN KEY (chks_header_data_id) REFERENCES chks_header_data(id);
ALTER TABLE chks_header_data ADD CONSTRAINT fk_chd_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_header_data ADD CONSTRAINT fk_chd_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_header_data ADD CONSTRAINT fk_chd_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- chks_header_data_files
ALTER TABLE chks_header_data_files ADD CONSTRAINT fk_chdf_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE chks_header_data_files ADD CONSTRAINT fk_chdf_chks_header_data_id FOREIGN KEY (chks_header_data_id) REFERENCES chks_header_data(id);
ALTER TABLE chks_header_data_files ADD CONSTRAINT fk_chdf_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_header_data_files ADD CONSTRAINT fk_chdf_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_header_data_files ADD CONSTRAINT fk_chdf_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- chks_questions
ALTER TABLE chks_questions ADD CONSTRAINT fk_cq_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE chks_questions ADD CONSTRAINT fk_cq_chks_header_id FOREIGN KEY (chks_header_id) REFERENCES chks_headers(id);
ALTER TABLE chks_questions ADD CONSTRAINT fk_cq_chks_header_data_id FOREIGN KEY (chks_header_data_id) REFERENCES chks_header_data(id);
ALTER TABLE chks_questions ADD CONSTRAINT fk_cq_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_questions ADD CONSTRAINT fk_cq_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_questions ADD CONSTRAINT fk_cq_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- chks_question_files
ALTER TABLE chks_question_files ADD CONSTRAINT fk_cqf_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE chks_question_files ADD CONSTRAINT fk_cqf_chks_question_id FOREIGN KEY (chks_question_id) REFERENCES chks_questions(id);
ALTER TABLE chks_question_files ADD CONSTRAINT fk_cqf_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_question_files ADD CONSTRAINT fk_cqf_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_question_files ADD CONSTRAINT fk_cqf_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- chks_question_results
ALTER TABLE chks_question_results ADD CONSTRAINT fk_cqr_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE chks_question_results ADD CONSTRAINT fk_cqr_chks_header_id FOREIGN KEY (chks_header_id) REFERENCES chks_headers(id);
ALTER TABLE chks_question_results ADD CONSTRAINT fk_cqr_chks_question_id FOREIGN KEY (chks_question_id) REFERENCES chks_questions(id);
ALTER TABLE chks_question_results ADD CONSTRAINT fk_cqr_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_question_results ADD CONSTRAINT fk_cqr_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_question_results ADD CONSTRAINT fk_cqr_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- chks_question_result_options
ALTER TABLE chks_question_result_options ADD CONSTRAINT fk_cqro_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE chks_question_result_options ADD CONSTRAINT fk_cqro_chks_question_result_id FOREIGN KEY (chks_question_result_id) REFERENCES chks_question_results(id);
ALTER TABLE chks_question_result_options ADD CONSTRAINT fk_cqro_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_question_result_options ADD CONSTRAINT fk_cqro_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_question_result_options ADD CONSTRAINT fk_cqro_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- chks_question_result_matrices
ALTER TABLE chks_question_result_matrices ADD CONSTRAINT fk_cqrm_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE chks_question_result_matrices ADD CONSTRAINT fk_cqrm_chks_question_result_id FOREIGN KEY (chks_question_result_id) REFERENCES chks_question_results(id);
ALTER TABLE chks_question_result_matrices ADD CONSTRAINT fk_cqrm_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_question_result_matrices ADD CONSTRAINT fk_cqrm_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_question_result_matrices ADD CONSTRAINT fk_cqrm_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- chks_general_fields
ALTER TABLE chks_general_fields ADD CONSTRAINT fk_cgf_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE chks_general_fields ADD CONSTRAINT fk_cgf_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_general_fields ADD CONSTRAINT fk_cgf_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_general_fields ADD CONSTRAINT fk_cgf_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- chks_general_field_values
ALTER TABLE chks_general_field_values ADD CONSTRAINT fk_cgfv_chks_general_field_id FOREIGN KEY (chks_general_field_id) REFERENCES chks_general_fields(id);
ALTER TABLE chks_general_field_values ADD CONSTRAINT fk_cgfv_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE chks_general_field_values ADD CONSTRAINT fk_cgfv_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE chks_general_field_values ADD CONSTRAINT fk_cgfv_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE chks_general_field_values ADD CONSTRAINT fk_cgfv_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);

-- user_checksheets
ALTER TABLE user_checksheets ADD CONSTRAINT fk_uc_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE user_checksheets ADD CONSTRAINT fk_uc_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE user_checksheets ADD CONSTRAINT fk_uc_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE user_checksheets ADD CONSTRAINT fk_uc_operator_user_id FOREIGN KEY (operator_user_id) REFERENCES users(id);
ALTER TABLE user_checksheets ADD CONSTRAINT fk_uc_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- user_checksheet_answers
ALTER TABLE user_checksheet_answers ADD CONSTRAINT fk_uca_chks_question_id FOREIGN KEY (chks_question_id) REFERENCES chks_questions(id);
ALTER TABLE user_checksheet_answers ADD CONSTRAINT fk_uca_chks_question_result_id FOREIGN KEY (chks_question_result_id) REFERENCES chks_question_results(id);
ALTER TABLE user_checksheet_answers ADD CONSTRAINT fk_uca_chks_question_rslt_option_id FOREIGN KEY (chks_question_rslt_option_id) REFERENCES chks_question_result_options(id);
ALTER TABLE user_checksheet_answers ADD CONSTRAINT fk_uca_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE user_checksheet_answers ADD CONSTRAINT fk_uca_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE user_checksheet_answers ADD CONSTRAINT fk_uca_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE user_checksheet_answers ADD CONSTRAINT fk_uca_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);

-- user_checksheet_matrix_answers
ALTER TABLE user_checksheet_matrix_answers ADD CONSTRAINT fk_ucma_chks_question_id FOREIGN KEY (chks_question_id) REFERENCES chks_questions(id);
ALTER TABLE user_checksheet_matrix_answers ADD CONSTRAINT fk_ucma_chks_question_result_id FOREIGN KEY (chks_question_result_id) REFERENCES chks_question_results(id);
ALTER TABLE user_checksheet_matrix_answers ADD CONSTRAINT fk_ucma_chks_question_result_matrix_id FOREIGN KEY (chks_question_result_matrix_id) REFERENCES chks_question_result_matrices(id);
ALTER TABLE user_checksheet_matrix_answers ADD CONSTRAINT fk_ucma_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE user_checksheet_matrix_answers ADD CONSTRAINT fk_ucma_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE user_checksheet_matrix_answers ADD CONSTRAINT fk_ucma_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE user_checksheet_matrix_answers ADD CONSTRAINT fk_ucma_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);

-- user_checksheet_approvals
ALTER TABLE user_checksheet_approvals ADD CONSTRAINT fk_ucap_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE user_checksheet_approvals ADD CONSTRAINT fk_ucap_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE user_checksheet_approvals ADD CONSTRAINT fk_ucap_data_approver_user_id FOREIGN KEY (data_approver_user_id) REFERENCES users(id);
ALTER TABLE user_checksheet_approvals ADD CONSTRAINT fk_ucap_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE user_checksheet_approvals ADD CONSTRAINT fk_ucap_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE user_checksheet_approvals ADD CONSTRAINT fk_ucap_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);

-- user_checksheet_approvals_history
ALTER TABLE user_checksheet_approvals_history ADD CONSTRAINT fk_ucaph_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE user_checksheet_approvals_history ADD CONSTRAINT fk_ucaph_data_approver_user_id FOREIGN KEY (data_approver_user_id) REFERENCES users(id);
ALTER TABLE user_checksheet_approvals_history ADD CONSTRAINT fk_ucaph_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);

-- user_checksheet_validations
ALTER TABLE user_checksheet_validations ADD CONSTRAINT fk_ucv_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE user_checksheet_validations ADD CONSTRAINT fk_ucv_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE user_checksheet_validations ADD CONSTRAINT fk_ucv_data_validator_user_id FOREIGN KEY (data_validator_user_id) REFERENCES users(id);
ALTER TABLE user_checksheet_validations ADD CONSTRAINT fk_ucv_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE user_checksheet_validations ADD CONSTRAINT fk_ucv_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE user_checksheet_validations ADD CONSTRAINT fk_ucv_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);

-- user_checksheet_validations_history
ALTER TABLE user_checksheet_validations_history ADD CONSTRAINT fk_ucvh_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE user_checksheet_validations_history ADD CONSTRAINT fk_ucvh_data_validator_user_id FOREIGN KEY (data_validator_user_id) REFERENCES users(id);
ALTER TABLE user_checksheet_validations_history ADD CONSTRAINT fk_ucvh_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);

-- user_checksheet_trace_values
ALTER TABLE user_checksheet_trace_values ADD CONSTRAINT fk_uctv_chks_header_data_id FOREIGN KEY (chks_header_data_id) REFERENCES chks_header_data(id);
ALTER TABLE user_checksheet_trace_values ADD CONSTRAINT fk_uctv_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE user_checksheet_trace_values ADD CONSTRAINT fk_uctv_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE user_checksheet_trace_values ADD CONSTRAINT fk_uctv_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE user_checksheet_trace_values ADD CONSTRAINT fk_uctv_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);

-- usr_chksheet_ans_judgements
ALTER TABLE usr_chksheet_ans_judgements ADD CONSTRAINT fk_ucaj_chks_question_id FOREIGN KEY (chks_question_id) REFERENCES chks_questions(id);
ALTER TABLE usr_chksheet_ans_judgements ADD CONSTRAINT fk_ucaj_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE usr_chksheet_ans_judgements ADD CONSTRAINT fk_ucaj_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE usr_chksheet_ans_judgements ADD CONSTRAINT fk_ucaj_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE usr_chksheet_ans_judgements ADD CONSTRAINT fk_ucaj_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);

-- usr_chksheet_ans_judgement_files
ALTER TABLE usr_chksheet_ans_judgement_files ADD CONSTRAINT fk_ucajf_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE usr_chksheet_ans_judgement_files ADD CONSTRAINT fk_ucajf_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE usr_chksheet_ans_judgement_files ADD CONSTRAINT fk_ucajf_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);
ALTER TABLE usr_chksheet_ans_judgement_files ADD CONSTRAINT fk_ucajf_user_checksheet_id FOREIGN KEY (user_checksheet_id) REFERENCES user_checksheets(id);
ALTER TABLE usr_chksheet_ans_judgement_files ADD CONSTRAINT fk_ucajf_usr_chksheet_ans_judgement_id FOREIGN KEY (usr_chksheet_ans_judgement_id) REFERENCES usr_chksheet_ans_judgements(id);

-- surprise_checksheets
ALTER TABLE surprise_checksheets ADD CONSTRAINT fk_sc_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE surprise_checksheets ADD CONSTRAINT fk_sc_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE surprise_checksheets ADD CONSTRAINT fk_sc_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- surprise_checksheet_fields
ALTER TABLE surprise_checksheet_fields ADD CONSTRAINT fk_scf_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE surprise_checksheet_fields ADD CONSTRAINT fk_scf_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id);
ALTER TABLE surprise_checksheet_fields ADD CONSTRAINT fk_scf_department_id FOREIGN KEY (department_id) REFERENCES departments(id);
ALTER TABLE surprise_checksheet_fields ADD CONSTRAINT fk_scf_responsible_user_id FOREIGN KEY (responsible_user_id) REFERENCES users(id);
ALTER TABLE surprise_checksheet_fields ADD CONSTRAINT fk_scf_surprise_checksheet_id FOREIGN KEY (surprise_checksheet_id) REFERENCES surprise_checksheets(id);
ALTER TABLE surprise_checksheet_fields ADD CONSTRAINT fk_scf_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);

-- npd_master
ALTER TABLE npd_master ADD CONSTRAINT fk_npd_checksheet_id FOREIGN KEY (checksheet_id) REFERENCES checksheets(id);
ALTER TABLE npd_master ADD CONSTRAINT fk_npd_created_by FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE npd_master ADD CONSTRAINT fk_npd_updated_by FOREIGN KEY (updated_by) REFERENCES users(id);


-- ============================================================================
-- UNIQUE CONSTRAINTS
-- ============================================================================
ALTER TABLE permissions ADD CONSTRAINT uk_permissions_permission_code UNIQUE (permission_code);
ALTER TABLE role_permissions ADD CONSTRAINT uk_role_permissions_role_permission UNIQUE (role_id, permission_id);
ALTER TABLE users ADD CONSTRAINT uk_users_username UNIQUE (username);
