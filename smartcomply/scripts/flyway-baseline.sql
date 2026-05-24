-- ============================================================================
-- AuditPro - Flyway Baseline (run after docs/original-schema.sql)
-- ----------------------------------------------------------------------------
-- Purpose:
--   1. Creates the ai_assessments table (V1.19 + V1.20 columns merged), which
--      is NOT in docs/original-schema.sql (added later by Flyway migrations).
--   2. Creates flyway_schema_history and marks ALL migrations V1.0-V1.20 as
--      already applied, so the Spring Boot app's Flyway has nothing to do.
--
-- Why needed:
--   V1.0__create_table.sql is an empty placeholder; the original schema was
--   created by Hibernate ddl-auto. Incremental migrations V1.1-V1.20 were
--   applied against that pre-existing schema. Running them on a brand-new
--   empty DB (where only V1.0 would run) causes failures such as:
--     "relation public.checksheets does not exist" (V1.2)
--     "column arca_id does not exist" (V1.11)
--   Applying original-schema.sql + this file sidesteps all of that.
--
-- Run order:
--   1. psql ... -f docs/original-schema.sql
--   2. psql ... -f scripts/flyway-baseline.sql   <-- this file
--   3. psql ... -f docs/seed-data.sql
--   4. psql ... -f scripts/seed-users.sql
--   (Then start the app — Flyway will see history is complete and do nothing.)
-- ============================================================================

\set ON_ERROR_STOP on

-- ----------------------------------------------------------------------------
-- 1. ai_assessments table (V1.19 columns + V1.20 extra columns merged)
--    docs/original-schema.sql was generated before V1.19 was applied.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_assessments (
    id                       BIGSERIAL PRIMARY KEY,
    user_checksheet_id       BIGINT        NOT NULL,
    chks_question_result_id  BIGINT        NOT NULL,
    photo_path               VARCHAR(500)  NOT NULL,
    suggested_judgement      VARCHAR(10)   NOT NULL,
    explanation              TEXT,
    confidence               DOUBLE PRECISION,
    ai_model                 VARCHAR(100),
    ai_provider              VARCHAR(50),   -- added by V1.20
    input_tokens             INTEGER,        -- added by V1.20
    output_tokens            INTEGER,        -- added by V1.20
    latency_ms               BIGINT,         -- added by V1.20
    prompt_sent              TEXT,
    raw_response             TEXT,
    assessed_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP,
    deleted_at               TIMESTAMP,
    created_by               BIGINT,
    updated_by               BIGINT,
    deleted_by               BIGINT,
    CONSTRAINT fk_ai_assessments_user_checksheet_id      FOREIGN KEY (user_checksheet_id)      REFERENCES user_checksheets(id),
    CONSTRAINT fk_ai_assessments_chks_question_result_id FOREIGN KEY (chks_question_result_id) REFERENCES chks_question_results(id),
    CONSTRAINT fk_ai_assessments_created_by              FOREIGN KEY (created_by)              REFERENCES users(id),
    CONSTRAINT fk_ai_assessments_updated_by              FOREIGN KEY (updated_by)              REFERENCES users(id),
    CONSTRAINT fk_ai_assessments_deleted_by              FOREIGN KEY (deleted_by)              REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_ai_assessments_user_checksheet_id      ON ai_assessments(user_checksheet_id);
CREATE INDEX IF NOT EXISTS idx_ai_assessments_chks_question_result_id ON ai_assessments(chks_question_result_id);
CREATE INDEX IF NOT EXISTS idx_ai_assessments_lookup                  ON ai_assessments(user_checksheet_id, chks_question_result_id);

-- ----------------------------------------------------------------------------
-- 2. flyway_schema_history — standard Flyway table
--    validate-on-migrate=false in application-local.properties, so checksums
--    can safely be NULL.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank  INTEGER       NOT NULL,
    version         VARCHAR(50),
    description     VARCHAR(200)  NOT NULL,
    type            VARCHAR(20)   NOT NULL,
    script          VARCHAR(1000) NOT NULL,
    checksum        INTEGER,
    installed_by    VARCHAR(100)  NOT NULL DEFAULT current_user,
    installed_on    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_time  INTEGER       NOT NULL DEFAULT 0,
    success         BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
);
CREATE INDEX IF NOT EXISTS flyway_schema_history_s_idx ON flyway_schema_history (success);

-- Insert one row per migration file so Flyway considers them all applied.
-- baseline-version=1 in application-local.properties → rank-1 entry uses version '1'.
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script)
VALUES
  (1,  '1',    'Flyway Baseline',                                                  'BASELINE', '<< Flyway Baseline >>'),
  (2,  '1.1',  'added indexes on checksheet table',                                'SQL',      'V1.1__added_indexes_on_checksheet_table.sql'),
  (3,  '1.2',  'added columns into the checksheet table',                          'SQL',      'V1.2__added_columns_into_the_checksheet_table.sql'),
  (4,  '1.3',  'drop unique constraint uid',                                       'SQL',      'V1.3__drop_unique_constraint_uid.sql'),
  (5,  '1.4',  'added column order no into chks header data and chks questions',   'SQL',      'V1.4__added_column_order_no_into_chks_header_data_and_chks_questions.sql'),
  (6,  '1.5',  'update column judgement into usr chksheet ans judgements not null','SQL',      'V1.5__update_column_judgement_into_usr_chksheet_ans_judgements_not_null.sql'),
  (7,  '1.6',  'add index roles parent role id',                                   'SQL',      'V1.6__add_index_roles_parent_role_id.sql'),
  (8,  '1.7',  'update parent role id in roles table',                             'SQL',      'V1.7__update_parent_role_id_in_roles_table.sql'),
  (9,  '1.8',  'create permissions tables',                                        'SQL',      'V1.8__create_permissions_tables.sql'),
  (10, '1.9',  'sync role permissions',                                            'SQL',      'V1.9__sync_role_permissions.sql'),
  (11, '1.10', 'alter jwt token in users table',                                   'SQL',      'V1.10__alter_jwt_token_in_users_table.sql'),
  (12, '1.11', 'rename arca id to username and add constraint',                    'SQL',      'V1.11__rename_arca_id_to_username_and_add_constraint.sql'),
  (13, '1.12', 'drop is corporate column',                                         'SQL',      'V1.12__drop_is_corporate_column.sql'),
  (14, '1.13', 'remove checksheet management filter permission',                   'SQL',      'V1.13__remove_checksheet_management_filter_permission.sql'),
  (15, '1.14', 'remove filter permissions',                                        'SQL',      'V1.14__remove_filter_permissions.sql'),
  (16, '1.15', 'add permission management permissions',                            'SQL',      'V1.15__add_permission_management_permissions.sql'),
  (17, '1.16', 'add master department permissions',                                'SQL',      'V1.16__add_master_department_permissions.sql'),
  (18, '1.17', 'remove checksheet workflow roles',                                 'SQL',      'V1.17__remove_checksheet_workflow_roles.sql'),
  (19, '1.18', 'cleanup deprecated v1 8 permissions',                             'SQL',      'V1.18__cleanup_deprecated_v1_8_permissions.sql'),
  (20, '1.19', 'create ai assessments table',                                      'SQL',      'V1.19__create_ai_assessments_table.sql'),
  (21, '1.20', 'add provider and token tracking',                                  'SQL',      'V1.20__add_provider_and_token_tracking.sql'),
  (22, '1.21', 'remove workflow roles',                                            'SQL',      'V1.21__remove_workflow_roles.sql'),
  (23, '1.22', 'seed master departments',                                          'SQL',      'V1.22__seed_master_departments.sql')
ON CONFLICT (installed_rank) DO NOTHING;
