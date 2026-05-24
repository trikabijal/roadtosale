# AuditPro — Database Schema Reference

> **Canonical schema reference.** Read this if you need to understand the
> shape of the database. Cross-cuts are in
> [`architecture.md`](./architecture.md) (security, infrastructure) and
> [`system-overview.md`](./system-overview.md) (audit + intervention
> lifecycle). [`database-analysis.md`](./database-analysis.md) is the
> older inventory and stops at V1.18 — this doc supersedes it.

---

## TL;DR

- **One Postgres DB** (`smartcomply`) backs all three deliverables — the Spring Boot REST API, the Angular BI dashboards, and the mobile field-auditor app.
- **V1.28 was a major collapse:** three runtime tables (`audit_assignments`, `intervention_assignments`, `user_checksheets`) became one `inspections` table with a `kind` discriminator. The row IS the assignment IS the filled form.
- **Latest migration is V1.32.** V1.27 added the intervention layer; V1.28 collapsed inspections; V1.29 fixed multi-device login; V1.30 added dealer-principal ack columns; V1.31 locked the per-inspection review uniqueness; V1.32 dropped leftover legacy FK columns Hibernate kept recreating.
- **Soft deletes everywhere.** Every domain table has `deleted_at` + `deleted_by` (and `created_at` / `updated_at` / `created_by` / `updated_by`). Application queries MUST filter `WHERE deleted_at IS NULL`. Partial unique indexes are scoped to live rows so soft-deleted history doesn't collide.
- **Hibernate `ddl-auto=update` still in play for some tables** — the JV-era auditee / location / city / state / country tables were created by ddl-auto, not Flyway. Migrations from V1.23 onwards use `ALTER TABLE IF EXISTS` and gated DO blocks to be safe on both fresh-DDL DBs and DBs restored from production.

---

## 1. Anchor entities

The audit + intervention domain is built around six anchor entities. The Java
class names mostly survive from the pre-V1.28 era — the underlying tables
have evolved more than the entities. The most important shift: `Inspection`
is the new runtime entity, replacing what used to be three classes
(`AuditAssignment`, `InterventionAssignment`, `UserChecksheet`).

| Entity | Table | Role |
|---|---|---|
| `Checksheet` | `checksheets` | A reusable **template** — questions, options, scoring rules. One template can drive many audit campaigns. |
| `Audit` | `audits` | A **campaign** — name + start/end + ACTIVE/CLOSED status + which template. e.g. "FY26 H1 Kia Showroom Audit". |
| `Inspection` (kind=AUDIT) | `inspections` | One per (audit, location). The original audit visit. Carries operator, dealer principal, status, answers/photos via children. |
| `Intervention` | `interventions` | An **improvement campaign** layered on top of an audit. Targets specific failing questions across a subset of locations. PRD label: "Improvement Campaign". |
| `Inspection` (kind=INTERVENTION) | `inspections` | One per (intervention, location). The per-dealer plan / re-inspection. Same status enum and child tables as kind=AUDIT. PRD label: "Improvement Plan". |
| `AuditeeLocation` | `auditee_locations` | A physical site. Parent: `Auditee` (the dealer brand entity). |
| `User` | `users` | Operators, validators, approvers, dealer principals, admins. |

```
Checksheet (template) ──┐
                        │ checksheet_id
        Audit (campaign)─┴── audit_id ──> Inspection (kind=AUDIT) ── auditee_location_id
                                              │ (operator, dealer principal, status)
                                              ↓
                                    user_checksheet_answers + photos + judgements
                                          (child FK: inspection_id)

                   Intervention (campaign) ── intervention_assignment_targets
                                              │  (materialised at activation)
                                              ↓
                          Inspection (kind=INTERVENTION) ── auditee_location_id
                                              │  (intervention_id FK, same status enum)
                                              ↓
                                    user_checksheet_answers + photos + judgements
                                          (same child tables, FK: inspection_id)
```

The legacy class names `UserChecksheetServiceImpl`, `UserChecksheetController`,
`UserChecksheetDTO`, `AuditAssignmentRepository` etc. still survive at the
service / controller / repository layer for backwards compatibility — the
underlying entity is `Inspection`. Mobile API fields like `auditAssignmentId`
are kept as parameter names for frontend compatibility but resolve to
inspection ids server-side. A separate "noun rename" issue tracks dropping
the `user_checksheet_*` prefix entirely.

---

## 2. The V1.28 collapse — why and what

### Why

Until V1.27, three tables represented one conceptual entity (an inspection
= one audit visit at one location):

| Pre-V1.28 table | What it stored |
|---|---|
| `audit_assignments` | Work order for the original audit visit. (audit, location, operator). |
| `intervention_assignments` | Work order / "Plan" for a re-inspection visit. (intervention, audit_assignment). |
| `user_checksheets` | The runtime form (status, started_at, submitted_at, submission version) — hung off one of the above via a CHECK-enforced XOR. |

The split caused real problems: BI rollup queries had to UNION across three
tables, every read joined two of them, and "is the inspection started or
not?" required a LEFT JOIN. A pre-V1.28 row was three rows masquerading as
one.

### What

V1.28 collapses the three into a single `inspections` table with a `kind`
discriminator.

| Pre-V1.28 | Post-V1.28 |
|---|---|
| `audit_assignments` row + matching `user_checksheets` row | one `inspections` row with `kind='AUDIT'`, `audit_id` set |
| `audit_assignments` row with no `user_checksheets` row | one `inspections` row with `kind='AUDIT'`, status=`ASSIGNED` |
| `intervention_assignments` row + matching `user_checksheets` row | one `inspections` row with `kind='INTERVENTION'`, `intervention_id` set |
| `intervention_assignments` row with no `user_checksheets` row | one `inspections` row with `kind='INTERVENTION'`, status=`ASSIGNED` |

A `CHECK` constraint enforces the XOR: `kind='AUDIT'` requires `audit_id`
set and `intervention_id` NULL; `kind='INTERVENTION'` is the reverse.

### Child-table FK renames

Eleven child tables had `user_checksheet_id` FK columns. V1.28 renamed each
to `inspection_id` and re-pointed the FK to `inspections(id)`. The child
tables kept their existing names — only the FK column changed.

1. `user_checksheet_answers`
2. `user_checksheet_validations`
3. `user_checksheet_validations_history`
4. `user_checksheet_approvals`
5. `user_checksheet_approvals_history`
6. `usr_chksheet_ans_judgements`
7. `usr_chksheet_ans_judgement_files`
8. `user_checksheet_matrix_answers`
9. `user_checksheet_trace_values`
10. `chks_general_field_values`
11. `ai_assessments`

`user_checksheet_answer_files` was unaffected — it has its own FK to
`user_checksheet_answers`, not to the parent inspection.

Two intervention-side tables also re-pointed:

- `intervention_assignment_targets.audit_assignment_id` → `inspection_id`
- `intervention_assignment_questions.intervention_assignment_id` → `inspection_id`

### Status enum unification

| Pre-V1.28 (UCs) | V1.28 (inspections) | Mapping |
|---|---|---|
| `IN_PROGRESS` | `IN_PROGRESS` | unchanged |
| `SUBMITTED` | `SUBMITTED` | unchanged |
| `VALIDATED` | `VALIDATED` | unchanged |
| `APPROVED` | `APPROVED` | unchanged |
| `INVALIDATED` (validator decline) | `DECLINED` | collapsed |
| `NOT_APPROVED` (approver decline) | `DECLINED` | collapsed |
| _(no row at AA without UC)_ | `ASSIGNED` | new sentinel |

A `CHECK` constraint forbids the legacy enum values post-V1.28. The lineage
of which path declined (validator vs approver) is preserved on the
`user_checksheet_validations_history` and `user_checksheet_approvals_history`
tables.

### What was NOT modelled as state

- **Acknowledged** — soft signal added in V1.30 via `acknowledged_at` / `acknowledged_by` columns; doesn't advance status.
- **Non-compliant** — pre-V1.28 `intervention_assignments.status` had `NON_COMPLIANT`. V1.28 forbids it. Non-compliance is a derived state based on per-customer score bands that can change — not stored. V1.28 hard-fails its migration if any pre-existing IA had this status.

### V1.32 follow-up

On some DBs Hibernate `ddl-auto=update` recreated `user_checksheet_id` on
`user_checksheet_validations` and `user_checksheet_approvals` after V1.28
dropped it (entity field hadn't been fully renamed yet). The leftover column
was NOT NULL with a dangling FK, so every validation/approval insert blew
up. V1.32 idempotently drops both the FK and the column.

---

## 3. `inspections` table (post-V1.28 — the centerpiece)

### Columns

| Column | Type | Purpose |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `kind` | VARCHAR NOT NULL | `'AUDIT'` or `'INTERVENTION'` (CHECK) |
| `audit_id` | BIGINT (FK→audits) | Set when kind=AUDIT, NULL when kind=INTERVENTION |
| `intervention_id` | BIGINT (FK→interventions) | Set when kind=INTERVENTION, NULL when kind=AUDIT |
| `auditee_location_id` | BIGINT NOT NULL (FK→auditee_locations) | Where the inspection happens |
| `operator_user_id` | BIGINT (FK→users) | The auditor. Nullable until assigned. |
| `dealer_principal_user_id` | BIGINT (FK→users) | Drives "My Plans" scope + ack permission. PRD §2.3 Owner. |
| `region_owner_user_id` | BIGINT (FK→users) | Regional head. Nullable. PRD §2.3 Oversight. |
| `checksheet_id` | BIGINT (FK→checksheets) | Template binding. Direct FK so legacy `frequency_of_freq_of_chk_cnt` semantics work. |
| `shift` | VARCHAR | Inherited from legacy per-shift checksheet model. |
| `frequency_of_freq_of_chk_cnt` | SMALLINT | Inherited from legacy. |
| `priority` | VARCHAR | `P1` / `P2` / `P3`. Intervention-only — CHECK forces NULL when kind=AUDIT. |
| `target_date` | DATE | Intervention-only. Snapshotted from intervention at activation. |
| `status` | VARCHAR NOT NULL DEFAULT 'ASSIGNED' | See §12 |
| `submission_version` | SMALLINT NOT NULL DEFAULT 0 | Incremented per resubmit. **Note:** entity field is `Byte` (8-bit) but column is SMALLINT — silent truncation risk above 127. Filed as M-TYPES-001. |
| `waiting_user_ids` | INTEGER[] | Who needs to act next. List<Long> in entity. |
| `started_at` | TIMESTAMP | First operator save. |
| `submitted_at` | TIMESTAMP | When operator hit submit. |
| `acknowledged_at` | TIMESTAMP | V1.30. When the dealer principal acked. Kind=INTERVENTION only. Doesn't advance status. |
| `acknowledged_by` | BIGINT (FK→users) | V1.30. Must match `dealer_principal_user_id` — service-side check. |
| `created_at` / `updated_at` / `deleted_at` | TIMESTAMP | Audit columns. |
| `created_by` / `updated_by` / `deleted_by` | BIGINT (FK→users) | Audit columns. |

### Constraints

| Constraint | Definition |
|---|---|
| `chk_inspections_kind` | `kind IN ('AUDIT','INTERVENTION')` |
| `chk_inspections_status` | `status IN ('ASSIGNED','IN_PROGRESS','SUBMITTED','VALIDATED','APPROVED','DECLINED')` |
| `chk_inspections_priority` | `priority IS NULL OR priority IN ('P1','P2','P3')` |
| `chk_inspections_one_parent` | XOR of `audit_id` / `intervention_id` based on `kind` |
| `chk_inspections_intervention_only` | `priority` and `target_date` must be NULL unless `kind='INTERVENTION'` |

### Indexes

| Index | Columns | Notes |
|---|---|---|
| `idx_inspections_audit_id` | `audit_id` | Partial: `WHERE audit_id IS NOT NULL` |
| `idx_inspections_intervention_id` | `intervention_id` | Partial: `WHERE intervention_id IS NOT NULL` |
| `idx_inspections_location_id` | `auditee_location_id` | |
| `idx_inspections_operator_user_id` | `operator_user_id` | Mobile `/myAssignments` lookup |
| `idx_inspections_dealer_principal` | `dealer_principal_user_id` | Partial: NOT NULL. `/myPlans` lookup. |
| `idx_inspections_region_owner` | `region_owner_user_id` | Partial: NOT NULL |
| `idx_inspections_status` | `status` | |
| `idx_inspections_kind_status` | `kind, status` | BI rollup uses this |
| `idx_inspections_submitted_at` | `submitted_at` | Recency / latest-answer ordering |
| `idx_inspections_acknowledged_at` | `acknowledged_at` | Partial: NOT NULL (V1.30) |
| `uk_inspections_audit_loc` | UNIQUE `(audit_id, auditee_location_id)` | Partial: `WHERE deleted_at IS NULL AND kind='AUDIT'`. **No multi-wave** — one row per (audit, location). |
| `uk_inspections_intervention_loc` | UNIQUE `(intervention_id, auditee_location_id)` | Partial: `WHERE deleted_at IS NULL AND kind='INTERVENTION'`. Same constraint. |

**Multi-wave is NOT supported.** If a re-inspection is needed after the
first one closes (DECLINED), the operator's next `createOrUpdate` reopens
the same row — no new inspection is created. If a fresh re-inspection cycle
is genuinely needed, the admin creates a new intervention.

---

## 4. Child tables of `inspections`

All have `inspection_id BIGINT NOT NULL` FK and the standard
`created_at` / `updated_at` / `deleted_at` / `_by` audit columns.

| Table | What it stores | Notable invariants |
|---|---|---|
| `user_checksheet_answers` | One row per `(inspection, chks_question_result)`. The answer payload. | UNIQUE `(inspection_id, chks_question_result_id)`. `judgement` SMALLINT (1=OK, 2=NOT_OK) is authoritative. |
| `user_checksheet_answer_files` | Photo / file uploads attached to an answer. Multiple per answer allowed. | FKs `user_checksheet_answers`, not `inspections` directly. Photos allowed on ANY answer type — not just FILE_UPLOAD. |
| `user_checksheet_matrix_answers` | One row per matrix cell when the question is a MATRIX type. | UNIQUE `(inspection_id, chks_question_result_id, chks_question_result_matrix_id, order_no)`. |
| `user_checksheet_trace_values` | Trace values keyed by `chks_header_data_id` for traceable headers. | UNIQUE `(inspection_id, chks_header_data_id)`. |
| `chks_general_field_values` | Operator-entered values for free-form general fields defined on the template. | |
| `usr_chksheet_ans_judgements` | Per-question overall judgement ("OK" / "NOT OK") + auditor remarks. | String judgement is informational metadata; may be missing for seeded data — fall back to `user_checksheet_answers.judgement` integer. |
| `usr_chksheet_ans_judgement_files` | Files attached to judgements. | Has both `inspection_id` and an FK to `usr_chksheet_ans_judgements`. |
| `user_checksheet_validations` | One row per (inspection, validator) review event. `status` = VALIDATED / INVALIDATED. | V1.31: UNIQUE `(inspection_id, data_validator_user_id) WHERE deleted_at IS NULL` — one live review row per pair. |
| `user_checksheet_validations_history` | Append-only audit trail of validation events. | |
| `user_checksheet_approvals` | One row per (inspection, approver). `status` = APPROVED / NOT_APPROVED. | V1.31: UNIQUE `(inspection_id, data_approver_user_id) WHERE deleted_at IS NULL`. |
| `user_checksheet_approvals_history` | Append-only audit trail of approval events. | |
| `ai_assessments` | AI photo verdict per `(inspection, chks_question_result)`. | See §10. |

V1.31 invariant: the `*_validations` / `*_approvals` tables use a
"lookup-or-update" service pattern keyed on `(inspection_id, reviewer)`. The
partial UNIQUE index locks this at the DB so a missed switch in repository
queries can't corrupt review history again. Soft-deleted history rows are
excluded so a deleted row + a fresh row for the same pair is allowed.

---

## 5. Intervention layer (V1.27)

The intervention chain mirrors the audit chain. Naming maps to PRD vocab:
- PRD "Improvement Campaign" → `interventions`
- PRD "Improvement Plan" → `inspections` with `kind='INTERVENTION'` (post-V1.28)

### `interventions`

| Column | Type | Purpose |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `audit_id` | BIGINT NOT NULL (FK→audits) | The audit cycle this intervention sits inside |
| `name` | VARCHAR NOT NULL | "Q3 Cleanliness Push" |
| `theme` | TEXT | Free-form description |
| `priority` | VARCHAR NOT NULL | `P1` / `P2` / `P3` (CHECK) |
| `target_date` | DATE | Deadline for completion |
| `status` | VARCHAR NOT NULL DEFAULT 'DRAFT' | `DRAFT` / `ACTIVE` / `CLOSED` (CHECK) |
| `activated_at` | TIMESTAMP | When admin flipped DRAFT → ACTIVE |
| `closed_at` | TIMESTAMP | When admin closed the campaign |
| audit columns | | standard |

Indexes: `(audit_id, status)`, `(priority)`.

### `intervention_questions`

The set of checklist questions the intervention is responsible for tracking.

| Column | Type | Purpose |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `intervention_id` | BIGINT NOT NULL | |
| `chks_question_id` | BIGINT NOT NULL | |
| `created_at` / `created_by` / `deleted_at` / `deleted_by` | | |

UNIQUE `(intervention_id, chks_question_id)`. The activate-time conflict
check uses this to refuse activation if another ACTIVE intervention on the
same audit already claims any of these questions.

### `intervention_assignment_targets`

Materialised at intervention activation — the list of inspections the
campaign reaches. The admin's UI choice (ALL / by region / manual) resolves
to the same join table; runtime always reads from here.

| Column | Type | Purpose |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `intervention_id` | BIGINT NOT NULL | |
| `inspection_id` | BIGINT NOT NULL | V1.28 re-pointed from `audit_assignment_id`. Always binds to an active `kind='AUDIT'` inspection. |
| audit columns | | |

UNIQUE `(intervention_id, inspection_id)`.

### `intervention_assignment_questions`

Per-plan question scope: the failing-question subset this specific plan
tracks. Created by `InterventionInstantiationListener` when an audit UC is
approved and the listener determines which of the intervention's questions
overlap with that UC's failing answers.

| Column | Type | Purpose |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `inspection_id` | BIGINT NOT NULL | V1.28 re-pointed from `intervention_assignment_id`. Binds to the kind=INTERVENTION inspection (the per-dealer plan). |
| `chks_question_id` | BIGINT NOT NULL | |
| `chks_question_result_id` | BIGINT | The original failing answer this tracks (nullable for safety). |
| audit columns | | |

UNIQUE `(inspection_id, chks_question_id)`.

---

## 6. Audit campaign layer (V1.24)

### `audits`

| Column | Type | Purpose |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `name` | VARCHAR NOT NULL | "FY26 H1 Kia Showroom Audit" |
| `checksheet_id` | BIGINT NOT NULL (FK→checksheets) | The template this campaign runs |
| `status` | VARCHAR NOT NULL DEFAULT 'ACTIVE' | `ACTIVE` / `CLOSED` (no CHECK — relies on app code) |
| `start_date` / `end_date` | TIMESTAMP | Campaign window |
| audit columns | | |

Indexes: `(checksheet_id)`, `(status)`.

V1.24 also added `audit_assignments` — collapsed away in V1.28. The
historical table is documented in [`architecture.md`](./architecture.md) §"Audit campaign model" for context but no longer exists in the schema.

### The `audit_signal` CTE — single source of BI scoring

Every BI metric query in `AuditServiceImpl.buildStats(...)` derives from one
shared CTE built by `scopeCte(level)`. Post-V1.28 the CTE reads from a
single `inspections` table and uses a LATERAL subquery to roll up the latest
effective answer per `(audit_assignment, chks_question_result)` pair
across:

1. The original AUDIT-kind inspection's answers.
2. Every INTERVENTION-kind inspection at the same location whose
   intervention is tied to the same audit, filtered to `status='APPROVED'`.

Latest in time = `ORDER BY submitted_at DESC NULLS LAST, uca_id DESC`.

This is the **only** scoring change point in the entire BI layer — every
downstream metric (band counts, per-region/per-dealer/per-location table,
red list, what's-failing, top-failing-checkpoints) `SELECT FROM
audit_signal`. Extending the CTE once means every panel inherits the
post-intervention rollup automatically. See
[`system-overview.md`](./system-overview.md) §5 for the full design intent.

Pre-V1.28, the CTE had to UNION across `audit_assignments` +
`user_checksheets` + `intervention_assignments`; post-V1.28 it's a single
table with a `kind` filter.

---

## 7. Reference & hierarchy tables

### Geography

| Table | Created by | Purpose |
|---|---|---|
| `countries` | Hibernate ddl-auto (legacy) | Top of geo hierarchy. |
| `regions` | V1.23 Flyway | BI scope ladder — multiple states roll up to one region. `country_id` FK. |
| `states` | Hibernate ddl-auto (legacy); V1.23 added `region_id` | `country_id`, `region_id` FKs. |
| `cities` | Hibernate ddl-auto (legacy) | `state_id` FK. |

### Auditee (dealer) hierarchy

| Table | Purpose |
|---|---|
| `auditees` | The brand entity — "Modi Kia". V1.23 added contact columns: `phone`, `email`, `website`. |
| `auditee_locations` | One physical site. A dealership can have several. `auditee_id`, `city_id` FKs. V1.25 deduped 417 dealers with multi-line-of-business duplicate addresses; the table-wide UNIQUE became a partial index. |
| `auditee_types` | Lookup: "Dealership", "Service Center", etc. |

Customer terminology gotcha: **Dealership** = `auditees` (the brand entity);
**Location** = `auditee_locations` (the physical site). The frontend's
"Bottom Dealerships" panel groups by dealership and surfaces a
`locationCount` per row.

### Checksheet template

The template side splits a checksheet into a 4-level hierarchy (Kia uses all 4):

| Table | Hierarchy role |
|---|---|
| `checksheets` | The template root. Self-FK on `checksheet_id` for versioning. |
| `chks_headers` | Column definitions / top-level groupings. Self-FK for sub-headers. |
| `chks_header_data` | Hierarchical sections — zones / categories / elements. Self-FK on `chks_header_data_id` for nesting. `level` (bigint) + `order_no` (int). |
| `chks_questions` | The actual inspection checkpoint. FKs `checksheet_id`, `chks_header_id`, `chks_header_data_id`. |
| `chks_question_results` | Expected answer config per question. `answer_type` ∈ OBJECTIVE / SUBJECTIVE / SUBJECTIVE_CONDITION / MATRIX / NA. UNIQUE `(chks_header_id, chks_question_id)`. |
| `chks_question_result_options` | Predefined choices for subjective questions. Each option has `judgement` ("OK" / "NOT OK"). |
| `chks_question_result_matrices` | Matrix cell definitions when answer_type=MATRIX. UNIQUE `(chks_question_result_id, chks_matrix_row_hdr, chks_matrix_col_hdr)`. |
| `chks_general_fields` | Custom metadata fields per checksheet. |
| `chks_header_data_files` | File attachments on header data items. |
| `chks_question_files` | File attachments on questions. |

Template review tables (separate from inspection review tables):
`checksheet_validations`, `checksheet_validation_history`,
`checksheet_approvals`, `checksheet_approval_history`. These govern the
template lifecycle (NEW → CREATE_TEMPLATE → CREATE_CONTENT →
SUBMITTED_FOR_VALIDATE → VALIDATED → APPROVED).

The `checksheets` table also carries Postgres array columns
(`validator_user_ids`, `approver_user_ids`, `data_validator_user_ids`,
`data_approver_user_ids`, `operator_user_ids`, `waiting_user_ids`,
`escalate_to_user_ids`, `alert_to_user_ids`) instead of join tables — see
[`architecture.md`](./architecture.md) §"Key Design Patterns" for the
trade-off (simpler `= ANY()` queries vs no FK constraint).

---

## 8. Permissions & users

| Table | Purpose |
|---|---|
| `users` | username (unique), email (unique), password (BCrypt), status `A`/`I`, `jwt_token` (text — also stored on user row), `fail_login_count`, `lock_time`. Self-FK on `created_by`. |
| `roles` | name, `role_code` (unique), self-FK `parent_role_id` for hierarchy. V1.7 set the hierarchy: `SUPER_ADMIN > DEPT_ADMIN > SUBDEPT_ADMIN > OPERATOR`. V1.27 added `DEALER_PRINCIPAL`. V1.21 removed five obsolete workflow roles. |
| `permissions` | `permission_code` (unique varchar(100)), name, description. V1.8 created. V1.9 sync'd granular role↔permission mappings. V1.13–V1.18 cleaned up. V1.27 added 5 intervention permissions. |
| `role_permissions` | Many-to-many. UNIQUE `(role_id, permission_id)`. |
| `user_role_departments` | User has multiple (role, department) tuples. `department_id` NULL = global scope (SUPER_ADMIN pattern). UNIQUE `(user_id, role_id, department_id)`. |
| `departments` | name, self-FK `department_id` (NULL = top-level master department; non-NULL = section). UNIQUE `(name, department_id)`. V1.22 seeded the three top-level master departments (Channel Development / Sales / Service). |
| `refresh_token` | id, `user_id`, `token` (unique indexed), `device_type` (varchar(8)), `expiry_date`. V1.29 dropped Hibernate's auto-`UNIQUE(user_id)` (the `@OneToOne` translation) and added `UNIQUE(user_id, device_type)` — fixes a user logging in from APP being unable to log in simultaneously from WEB. Entity moved to `@ManyToOne` so Hibernate doesn't recreate the offending constraint. |

JWT auth flow + the role hierarchy detail are in
[`architecture.md`](./architecture.md) §"Security Architecture".

Permission inventory for intervention surface (V1.27):

| Permission code | Used by |
|---|---|
| `INTERVENTION_MANAGE` | Create / activate / close interventions |
| `INTERVENTION_ASSIGNMENT_VIEW` | Read plans (lists, detail, BI) |
| `INTERVENTION_ASSIGNMENT_MANAGE` | Close-as-non-compliant, admin overrides |
| `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` | Dealer principal acks their own plans |
| `INTERVENTION_CONDUCT` | Operator runs a re-inspection wave |

---

## 9. AI assessments

`ai_assessments` stores the AI photo verdict per `(inspection,
chks_question_result)`. Written by the Trika LLM module when a photo is
uploaded.

| Column | Type | Purpose |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `inspection_id` | BIGINT NOT NULL (FK→inspections) | V1.28 renamed from `user_checksheet_id` |
| `chks_question_result_id` | BIGINT NOT NULL | |
| `photo_path` | VARCHAR(500) NOT NULL | S3 path to the assessed photo |
| `suggested_judgement` | VARCHAR(10) NOT NULL | "OK" / "NOT OK" |
| `explanation` | TEXT | Model's natural-language explanation |
| `confidence` | DOUBLE PRECISION | 0..1 |
| `ai_model` | VARCHAR(100) | Specific model ID (e.g., `gemini-2.0-flash-exp`) |
| `ai_provider` | VARCHAR(50) | V1.20 added. `ANTHROPIC` / `OPENAI` / `GEMINI`. |
| `prompt_sent` | TEXT | The full prompt — kept for replay/debug |
| `raw_response` | TEXT | The provider's raw response |
| `input_tokens` / `output_tokens` | INTEGER | V1.20 added. Cost tracking. |
| `latency_ms` | BIGINT | V1.20 added. |
| `assessed_at` | TIMESTAMP NOT NULL | |
| audit columns | | standard |

Indexes: `(inspection_id)`, `(chks_question_result_id)`, `(inspection_id,
chks_question_result_id)` — the audit-report's lookup pattern.

Architecture and prompt design: [`architecture.md`](./architecture.md) §"AI photo assessment".

---

## 10. Other tables

| Table | Purpose |
|---|---|
| `surprise_checksheets` | Ad-hoc audit container. |
| `surprise_checksheet_fields` | Concerns within a surprise audit. `responsible_user_id`, `file_paths` array (S3 paths), `creation_date`, free-form `concern` + `remarks`. |
| `npd_master` | "No Production Day" tracking. `checksheet_id`, `npd_date`, `shift`, `is_exception` flag. UNIQUE `(checksheet_id, npd_date, shift)`. |
| `lov_data` | Generic lookup-of-values / system configuration store. |
| `app_versions` | Mobile app version tracking. `version` (bigint), `version_name`, `url`, `os`, `is_forcefully_update`. UNIQUE `(version, os)`. |
| `api_history` | Optional API request/response audit log written by `MyPayloadCapturingFilter` when `is_API_log_save` is enabled. |
| `checksheet_assignments` | Legacy template-side assignment (separate from the audit-side `inspections`). Used by the older pre-AuditPro flow. |
| `checksheet_auditee_types` | Many-to-many between checksheet templates and auditee types. |

---

## 11. Migration history

### Base migrations (`db/migration/`)

| Version | Purpose |
|---|---|
| V1.0 | `create_table.sql` — **EMPTY**. Schema bootstrapped by Hibernate `ddl-auto`. |
| V1.1 | GIN indexes on checksheet array columns. |
| V1.2 | Added columns to `checksheets`. |
| V1.3 | Drop unique constraint on `checksheets.uid`. |
| V1.4 | Added `order_no` to `chks_header_data` and `chks_questions`. |
| V1.5 | Made `judgement` NOT NULL in `usr_chksheet_ans_judgements`. |
| V1.6 | Index on `roles.parent_role_id`. |
| V1.7 | Seeded role hierarchy via `parent_role_id`. |
| V1.8 | Created `permissions` + `role_permissions` tables, seeded initial codes. |
| V1.9 | Synced granular role↔permission mappings. |
| V1.10 | Altered `users.jwt_token` to `text`. |
| V1.11 | Renamed `arca_id` → `username` on users + unique constraint. |
| V1.12 | Dropped `is_corporate` from `checksheets`. |
| V1.13 | Removed checksheet management filter permission. |
| V1.14 | Removed filter permissions. |
| V1.15 | Added permission-management permissions. |
| V1.16 | Added master-department permissions. |
| V1.17 | Removed checksheet workflow roles (cleanup). |
| V1.18 | Cleaned up deprecated V1.8 permission codes. |
| V1.19 | Created `ai_assessments` table. |
| V1.20 | Added `ai_provider`, `input_tokens`, `output_tokens`, `latency_ms` to `ai_assessments`. |
| V1.21 | Permanently removed CHK_SHT_PREPARE/VALIDATOR/APPROVER/DATA_VALIDATOR/DATA_APPROVER roles; migrated user assignments to SUBDEPT_ADMIN / DEPT_ADMIN. |
| V1.22 | Seeded the 3 top-level master departments restored from production backup. |
| V1.23 | Added `regions` table + `states.region_id` + auditees contact columns (phone, email, website) + BI grouping indexes. |
| V1.24 | Added `audits` + `audit_assignments` (latter collapsed away in V1.28). Wired `user_checksheets.audit_assignment_id`. |
| V1.25 | Deduped `auditee_locations` — collapsed 417 dealer-with-duplicate-address groups; re-parented assignments. |
| V1.26 | Deduped leftover multiple `user_checksheets` per assignment (12 cases V1.25's re-parenting created). |
| V1.27 | Added `interventions` + `intervention_questions` + `intervention_assignment_targets` + `intervention_assignments` + `intervention_assignment_questions`. Added dealer-principal + region-owner cols on `audit_assignments`. Relaxed `user_checksheets.audit_assignment_id` to nullable + added `intervention_assignment_id` with XOR CHECK. Created DEALER_PRINCIPAL role + 5 intervention permissions. |
| V1.28 | **Schema collapse:** merged `audit_assignments` + `intervention_assignments` + `user_checksheets` → `inspections` with `kind` discriminator. Renamed `user_checksheet_id` → `inspection_id` on 11 child tables. Re-pointed `intervention_assignment_targets` + `_questions` to `inspection_id`. Unified status enum (INVALIDATED/NOT_APPROVED → DECLINED). Hard-fails on multi-wave UCs or NON_COMPLIANT-state IAs. |
| V1.29 | Dropped Hibernate's auto-generated `UNIQUE(user_id)` on `refresh_token` and added `UNIQUE(user_id, device_type)` so a user can hold an APP token and a WEB token simultaneously. |
| V1.30 | Added `inspections.acknowledged_at` + `acknowledged_by` (FK→users). Soft signal — doesn't advance status. M-ACK-001 (deferred from V1.28). |
| V1.31 | Locked `(inspection_id, validator)` and `(inspection_id, approver)` uniqueness on validation/approval review tables via partial UNIQUE indexes. Defends against a regression where repository queries kept keying on `(checksheet_id, reviewer)` post-V1.28. |
| V1.32 | Idempotently dropped leftover `user_checksheet_id` column + FK on `user_checksheet_validations` and `user_checksheet_approvals` that Hibernate `ddl-auto=update` had recreated on some DBs after V1.28. |

### Tenant migrations (`db/tenants/<id>/V100.x__*.sql`)

Tenant-scoped seed data, loaded only when `tenant.id` matches. Currently
only the Kia tenant exists as a worked example.

| Version | Purpose |
|---|---|
| `kia/V100.001__seed_kia_users.sql` | Seeds Kia operator + admin users (KIA_DEMO_AUDITOR_001..030, KIA_SALES_DEPT_HEAD, etc.). |
| `kia/V100.002__seed_kia_dealerships.sql` | Seeds Kia dealerships, auditee_locations, states/regions/cities. |
| `kia/V100.003__grant_intervention_permissions.sql` | Wires the V1.27 INTERVENTION_* permissions to the right Kia roles. |
| `kia/V100.004__seed_dealer_principals.sql` | Seeds Kia dealer principal users and ties them to their dealer locations. |

The `_base/` directory under `db/tenants/` holds shared tenant-bootstrap
infrastructure. Tenant overlay mechanics: [`tenant-overlay.md`](./tenant-overlay.md).

---

## 12. Status enum (inspections)

```
ASSIGNED ──operator opens it──▶ IN_PROGRESS ──submit──▶ SUBMITTED
                                                          │
                                                          ├── validator declines ──▶ DECLINED
                                                          │                            │
                                                          │                            └─ operator's next createOrUpdate
                                                          │                               reopens (no explicit re-open
                                                          │                               transition guard; the save
                                                          │                               path flips to IN_PROGRESS,
                                                          │                               then SUBMITTED on resubmit)
                                                          │
                                                          └── validator approves ──▶ VALIDATED ──┐
                                                                                                  │
                                                                                approver declines ─┴──▶ DECLINED
                                                                                                  │
                                                                                approver approves ─┴──▶ APPROVED
```

The full state set is `ASSIGNED | IN_PROGRESS | SUBMITTED | VALIDATED |
APPROVED | DECLINED` (enforced by the `chk_inspections_status` CHECK).

Legacy values `INVALIDATED` (validator decline) and `NOT_APPROVED` (approver
decline) are **forbidden** post-V1.28 — both collapsed into the single
terminal `DECLINED` state on the inspection itself. The lineage of which
path declined lives on `user_checksheet_validations_history` and
`user_checksheet_approvals_history`.

### Not modelled as state

- **Acknowledged** (V1.30) — soft signal via `acknowledged_at` /
  `acknowledged_by` columns on intervention inspections. Doesn't advance
  status; dealer principal's ack and operator's start are independent.
- **Non-compliant** — derived state based on per-customer score bands that
  can change. Not stored on the inspection. V1.28 hard-fails its backfill if
  any pre-existing intervention assignment had `NON_COMPLIANT` to flag that
  the assumption is broken.

---

## 13. Soft-delete pattern

Every domain table has:

- `created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`
- `updated_at TIMESTAMP`
- `deleted_at TIMESTAMP`
- `created_by BIGINT NOT NULL (FK→users)`
- `updated_by BIGINT (FK→users)`
- `deleted_by BIGINT (FK→users)`

**Application queries MUST filter `WHERE deleted_at IS NULL`** to exclude
soft-deleted rows. The `audit_signal` CTE and every BI query do this
explicitly. Repository methods often have a `findActive*` variant that
encodes the filter — prefer that over the generic `findBy*` if you can.

Partial unique indexes used throughout the schema are scoped to live rows
only (`WHERE deleted_at IS NULL`) so that:

- A soft-deleted row + a fresh row with the same natural key co-exist
  without violating the constraint.
- The post-soft-delete fresh row is the unambiguous "live" one.

Examples:
- `inspections`: `uk_inspections_audit_loc` / `uk_inspections_intervention_loc`
- `user_checksheet_validations`: `uk_user_checksheet_validations_inspection_validator` (V1.31)
- `user_checksheet_approvals`: `uk_user_checksheet_approvals_inspection_approver` (V1.31)
- `auditee_locations`: per-dealer dedupe partial index (V1.25)

V1.28's backfill respects this — soft-deleted source rows produce
soft-deleted `inspections` rows (it `GREATEST`-merges `deleted_at` from both
the UC and the AA, so a delete on either side propagates).

---

## Cross-references

| Doc | When to read it |
|---|---|
| [`architecture.md`](./architecture.md) | Security architecture, request flow, package layout, scoring rules, AI photo assessment, intervention layer rationale, evolution history (V1.24+). |
| [`system-overview.md`](./system-overview.md) | Orientation for the audit + intervention lifecycle, BI rollup design, and the worked Modi-Kia data flow trace. |
| [`flows.md`](./flows.md) | Code-level walkthroughs of each major flow. |
| [`api.md`](./api.md) | REST endpoint reference, including §"Mobile API flow (audit lifecycle)". |
| [`../dev/runbooks/dedupe-runbook.md`](../dev/runbooks/dedupe-runbook.md) | V1.25 / V1.26 background — what they touched, how to re-run. |
| [`../tenants/_docs/tenant-overlay.md`](../tenants/_docs/tenant-overlay.md) | Tenant overlay mechanism. |
| [`../tenants/_docs/onboarding-new-tenant.md`](../tenants/_docs/onboarding-new-tenant.md) | Adding a new OEM tenant. |
| [`../dev/archive/database-analysis.md`](../dev/archive/database-analysis.md) | Historical inventory (V1.0–V1.18). Superseded by this doc for V1.19+. |
