# Audit Pro - Database Analysis

## Summary

| Metric | Count |
|--------|-------|
| Tables | 40 (excluding flyway_schema_history) |
| Sequences | 39 |
| Indexes | 89 |
| Foreign Keys | 167 |
| Flyway Migrations | 19 (V1.0 through V1.18) |

---

## Table Inventory

### Reference Tables (4)

These tables contain system configuration data seeded at deployment time.

| Table | Purpose | Approx Rows |
|-------|---------|-------------|
| `roles` | Role definitions (SUPER_ADMIN, DEPT_ADMIN, SUBDEPT_ADMIN, OPERATOR) | 4 (was 9 before V1.17) |
| `permissions` | Permission definitions (RBAC permission codes) | ~57 |
| `role_permissions` | Role-to-permission mapping (join table) | ~200 |
| `lov_data` | List of values (shifts, units, frequency, separator config) | 4 |

### Transactional Tables (35)

#### Core Entities

| Table | Purpose | Notes |
|-------|---------|-------|
| `users` | User accounts | Self-referencing created_by/updated_by/deleted_by |
| `departments` | Department/subdepartment hierarchy | Self-referencing department_id for parent |
| `user_role_departments` | User-role-department assignments | Many-to-many join |
| `refresh_token` | JWT refresh tokens | Per user per device |

#### Checksheet Definition (Template)

| Table | Purpose |
|-------|---------|
| `checksheets` | Master checksheet definitions |
| `chks_headers` | Column headers within a checksheet (self-referencing for nesting) |
| `chks_header_data` | Row data under each header (self-referencing for hierarchy) |
| `chks_header_data_files` | File attachments on header data rows |
| `chks_questions` | Questions within a checksheet |
| `chks_question_files` | File attachments on questions |
| `chks_question_results` | Expected result configuration per question (answer type, limits, etc.) |
| `chks_question_result_options` | Dropdown options for SUBJECTIVE_CONDITION answer type |
| `chks_question_result_matrices` | Matrix cell definitions for MATRIX answer type |
| `chks_general_fields` | Custom general fields on a checksheet |

#### Checksheet Workflow (Definition Approval)

| Table | Purpose |
|-------|---------|
| `checksheet_validations` | Current validation state of a checksheet definition |
| `checksheet_validations_history` | Historical validation records (versioned) |
| `checksheet_approvals` | Current approval state of a checksheet definition |
| `checksheet_approvals_history` | Historical approval records (versioned) |

#### User Checksheet Fill (Operational Data)

| Table | Purpose |
|-------|---------|
| `user_checksheets` | Operator's filled checksheet instance |
| `user_checksheet_answers` | Individual answers to questions |
| `user_checksheet_matrix_answers` | Matrix cell answers |
| `user_checksheet_answer_files` | File uploads with answers (**extra table, not in JPA entities**) |
| `chks_general_field_values` | Filled values for general fields |
| `user_checksheet_trace_values` | Traceability values per header data row |
| `usr_chksheet_ans_judgements` | Per-question OK/NOT OK judgements |
| `usr_chksheet_ans_judgement_files` | File attachments on judgements |

#### User Checksheet Workflow (Data Approval)

| Table | Purpose |
|-------|---------|
| `user_checksheet_validations` | Current data validation state |
| `user_checksheet_validations_history` | Historical data validation records |
| `user_checksheet_approvals` | Current data approval state |
| `user_checksheet_approvals_history` | Historical data approval records |

#### Surprise Checksheets

| Table | Purpose |
|-------|---------|
| `surprise_checksheets` | Ad-hoc inspection checksheets |
| `surprise_checksheet_fields` | Individual findings in a surprise checksheet |

#### NPD (No Production Day)

| Table | Purpose |
|-------|---------|
| `npd_master` | Non-production day records |

#### System

| Table | Purpose |
|-------|---------|
| `api_history` | HTTP request/response logging |
| `app_versions` | Mobile app version management |

### Extra Table (1)

| Table | Status |
|-------|--------|
| `user_checksheet_answer_files` | Exists in the database but is NOT mapped in any JPA entity. Column details could not be fully retrieved due to a database permission issue. Likely an orphaned or manually-created table for file uploads associated with answers. |

---

## Schema Origin

The database schema was **created by Hibernate `ddl-auto`**, not by Flyway migrations.

- **V1.0** (`V1.0__create_table.sql`) is an **empty placeholder** file -- it contains no SQL statements.
- Hibernate's auto-DDL generated all 40 tables, sequences, and the 167 foreign keys at application startup.
- Flyway migrations (V1.1 through V1.18) apply **incremental changes** on top of the Hibernate-generated schema.

This is a common Spring Boot pattern where `spring.jpa.hibernate.ddl-auto=update` creates the initial schema, and Flyway is introduced later for controlled migrations.

---

## Foreign Keys

All 167 foreign keys **do exist at the database level**. They were created by Hibernate, not by Flyway.

### Common FK Pattern

Nearly every table has three standard audit FKs:
- `created_by -> users(id)`
- `updated_by -> users(id)`
- `deleted_by -> users(id)`

This accounts for roughly 3 x 35 = 105 of the 167 FKs. The remaining ~62 are domain-specific relationships (checksheet_id, department_id, chks_question_id, etc.).

### Notable FK Relationships

- `checksheets.checksheet_id -> checksheets(id)` -- self-referencing for versioning
- `departments.department_id -> departments(id)` -- self-referencing for hierarchy
- `chks_headers.chks_header_id -> chks_headers(id)` -- self-referencing for nesting
- `chks_header_data.chks_header_data_id -> chks_header_data(id)` -- self-referencing for hierarchy
- `roles.role_id -> roles(id)` -- legacy self-reference (not actively used)
- `roles.parent_role_id -> roles(id)` -- active parent-child role hierarchy
- `role_permissions` has `ON DELETE CASCADE` on both role_id and permission_id FKs

---

## Flyway Migration History

All 19 migrations have been applied successfully:

| Version | Description | Purpose |
|---------|-------------|---------|
| V1.0 | create_table | Empty placeholder (schema created by Hibernate) |
| V1.1 | added_indexes_on_checksheet_table | GIN indexes on array columns for operator/validator/approver lookups |
| V1.2 | added_columns_into_the_checksheet_table | Added escalate_to_user_ids and alert_to_user_ids array columns |
| V1.3 | drop_unique_constraint_uid | Dropped unique constraint on checksheets.uid |
| V1.4 | added_column_order_no | Added order_no to chks_header_data and chks_questions |
| V1.5 | update_column_judgement | Made judgement NOT NULL in usr_chksheet_ans_judgements |
| V1.6 | add_index_roles_parent_role_id | Index on roles.parent_role_id |
| V1.7 | update_parent_role_id_in_roles_table | Set parent_role_id hierarchy for all roles |
| V1.8 | create_permissions_tables | Created permissions and role_permissions tables with 32 seed permissions |
| V1.9 | sync_role_permissions | Added 52 full-name permissions and mapped to roles via JSONB config |
| V1.10 | alter_jwt_token_in_users_table | Changed jwt_token column type |
| V1.11 | rename_arca_id_to_username | Renamed column and added unique constraint |
| V1.12 | drop_is_corporate_column | Dropped is_corporate column (later re-added by Hibernate) |
| V1.13 | remove_checksheet_management_filter_permission | Removed redundant CHECKSHEET_MANAGEMENT_FILTER |
| V1.14 | remove_filter_permissions | Removed 5 redundant FILTER permissions |
| V1.15 | add_permission_management_permissions | Added 5 PERMISSION_* permissions for SUPER_ADMIN |
| V1.16 | add_master_department_permissions | Added 5 MASTER_DEPARTMENT_* permissions |
| V1.17 | remove_checksheet_workflow_roles | **Major**: Removed 5 CHK_SHT_* roles, migrated users to SUBDEPT_ADMIN/DEPT_ADMIN |
| V1.18 | cleanup_deprecated_v1_8_permissions | Removed 15 deprecated abbreviated permission codes from V1.8 |

---

## Indexes

89 indexes across all tables. Categories:

- **Primary key indexes**: 40 (one per table, auto-created)
- **GIN array indexes**: 5 (on checksheets array columns for `ANY()` queries)
- **Explicit indexes from migrations**: 5 (permissions, role_permissions, roles)
- **Hibernate FK indexes**: ~39 (auto-created for FK columns)

---

## Array Columns

Several tables use PostgreSQL array types (created by Hibernate for `@Type` annotated JPA fields):

| Table | Column | Type |
|-------|--------|------|
| `checksheets` | alert_to_user_ids | bigint[] |
| `checksheets` | approver_user_ids | bigint[] |
| `checksheets` | data_approver_user_ids | bigint[] |
| `checksheets` | data_validator_user_ids | bigint[] |
| `checksheets` | escalate_to_user_ids | bigint[] |
| `checksheets` | operator_user_ids | bigint[] |
| `checksheets` | validator_user_ids | bigint[] |
| `checksheets` | waiting_user_ids | integer[] |
| `checksheets` | npd_day | varchar[] |
| `chks_question_results` | matrix_column_header_names | varchar[] |
| `chks_question_results` | matrix_row_header_names | varchar[] |
| `surprise_checksheet_fields` | file_paths | varchar[] |
| `user_checksheets` | waiting_user_ids | integer[] |

---

## Soft Delete Pattern

Most tables use a soft-delete pattern with a `deleted_at` timestamp and `deleted_by` FK:
- Records are never physically deleted (except in specific migration cleanup operations)
- Queries filter with `WHERE deleted_at IS NULL` or `WHERE deleted_by IS NULL`
- History tables (`*_history`) do NOT have deleted_at -- they are append-only audit logs

Tables WITHOUT soft delete: `api_history`, `app_versions`, `lov_data`, `checksheet_approvals_history`, `checksheet_validations_history`, `user_checksheet_approvals_history`, `user_checksheet_validations_history`, `role_permissions`, `npd_master`.
