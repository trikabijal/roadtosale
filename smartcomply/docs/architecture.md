# AuditPro -- Architecture

## TL;DR

AuditPro is a Spring Boot 3.1.4 / Java 17 REST API backing two consumers:
the Angular BI dashboards (`../smartcomply-angular`) and the Ionic+Capacitor
mobile field-auditor app (`../auditpro-mobile-app`). One PostgreSQL database
(`smartcomply`) holds everything. The runtime domain is built around the
`Inspection` entity (one row per audit or intervention visit at one
location), and every BI metric reads from a single shared `audit_signal`
CTE so numbers reconcile across panels by construction. AI photo
assessment runs async on every upload and persists verdicts in
`ai_assessments`. Latest Flyway migration is V1.32.

---

## System Overview

AuditPro is a digital audit-management backend. It powers field audits
(originally industrial checksheets, currently field-audit campaigns like
Kia showroom inspections) with AI-powered photo assessment via the Trika
LLM module.

### Deployable units

| Deployable | Repo | URL | Role |
|---|---|---|---|
| REST API | this repo (`smartcomply`) | `:8089` | Single backend serving both UIs |
| BI dashboards + audit mgmt | `../smartcomply-angular` | `:4200` | Angular SPA for admins, reviewers, BI |
| Field-auditor mobile app | `../auditpro-mobile-app` | `:8100` (PWA) | Ionic + Capacitor; operators fill inspections |

### Who uses it

| Actor | What they do |
|-------|-------------|
| **Super Admin** | Full system access — manages roles, permissions, users, departments. |
| **Department Admin / Section Admin** | Manages users + checksheet templates in their scope. |
| **Checksheet Preparer / Validator / Approver** | Designs and signs off the template. |
| **Operator (Auditor)** | Walks the site, fills the inspection on mobile, submits photos. |
| **Data Validator / Approver** | Reviews submitted inspection data. |
| **Dealer Principal** (V1.27+) | Acknowledges intervention plans for their own location. |
| **Region Owner** (V1.27+) | Oversight role for regional rollups. |

### What it manages

- **Checksheet templates** — hierarchical (Zone / Category / Element /
  Question) with answer types, scoring rules, options.
- **Audit campaigns** — a fixed scope of locations evaluated against one
  template within a time window.
- **Intervention campaigns ("Improvement Campaigns")** — narrower
  follow-up cycles that re-inspect only the questions that failed.
- **Inspections** — the runtime row carrying status + answers + photos
  for one (audit-or-intervention, location) pair.
- **AI photo verdicts** — per-photo OK/NOT OK suggestions with
  explanations, stored alongside operator answers.
- **BI rollups** — band counts, regional/dealer/location tables, red
  list, what's-failing, top-failing-checkpoints. All from one shared CTE.

---

## Entity model

The audit + intervention domain is built around the `Inspection` entity
with a `kind` discriminator (`'AUDIT'` | `'INTERVENTION'`). One inspection
per (campaign, location). Answers, photos, judgements, validations, and
approvals all hang off the inspection via FK `inspection_id`.

**See [`schema.md`](./schema.md) for the canonical table-by-table
reference, ER diagram, status enum, and migration history.** This doc
describes the cross-cutting architecture (security, BI, AI, infra); the
schema doc owns the data shape.

Orientation only:

- `Checksheet` is the reusable template (questions, options, scoring rules).
- `Audit` is a campaign that runs one template against a fixed scope.
- `Inspection (kind=AUDIT)` is one operator's visit to one location for one audit.
- `Intervention` is the follow-up campaign targeting failing questions.
- `Inspection (kind=INTERVENTION)` is the per-dealer re-inspection plan.

Child tables (answers, photos, judgements, validations, approvals) kept
their pre-V1.28 names — only the FK column was renamed
`user_checksheet_id → inspection_id`. Legacy class names
(`UserChecksheetServiceImpl`, `UserChecksheetController`,
`UserChecksheetDTO`, `AuditAssignmentRepository`) survive at the
service/controller/DTO layer for backwards compatibility; underneath
they all read/write `inspections`.

---

## Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| **Java** | 17 | Language runtime |
| **Spring Boot** | 3.1.4 | Application framework |
| **Spring Security** | (managed) | Authentication + authorization |
| **Spring Data JPA / Hibernate** | (managed) | ORM and data access |
| **PostgreSQL** | runtime | Primary DB. Uses PG-native features: array columns (`_int8`, `_varchar`), GIN indexes, `DATE()` casts, partial unique indexes. |
| **Flyway** | (managed) | Migration management. Latest = V1.32. |
| **jjwt** | 0.11.5 | JWT generation + parsing |
| **Firebase Admin SDK** | 8.1.0 | Push notifications (currently un-initialised) |
| **AWS SDK** | 1.11.683 | S3 file storage |
| **Spring Mail** | (managed) | Email with async retry |
| **Apache POI** | 5.1.0 | Excel (.xlsx) export |
| **Lombok** | (managed) | Boilerplate reduction |
| **Trika LLM module** | 1.0.0 | AI photo assessment (multi-provider) |
| **Amazon Corretto** | 17 (Alpine) | Docker base image |

Build output: WAR (`smartcomply-0.0.1-SNAPSHOT.war`) — declared
`<packaging>war</packaging>` for servlet-container compatibility, but
runs as an executable JAR in Docker.

---

## Package Structure

```
src/main/java/com/checkSheet/
|-- Application.java              Main class. @EnableScheduling/Async/TransactionManagement.
|                                  Defines 3 thread pool beans.
|
|-- config/                        Security, S3, LLM, file storage, JWT, CORS.
|   |-- ApplicationConfig          UserDetailsService, AuthenticationProvider, BCrypt
|   |-- SecurityConfiguration      Filter chain, whitelist, stateless sessions
|   |-- JwtAuthenticationFilter    Per-request JWT extraction + permission check
|   |-- JwtService                 Token gen/parse/validate
|   |-- ApiEndpointConfig          Maps endpoints to required permissions
|   |-- CustomUserDetails          UserDetails wrapper with permissions + roles
|   |-- AWSS3Config                S3 client bean
|   |-- FileStorageProperties      File-upload directory config
|
|-- constant/                      Enums: status types, frequency types, question result types,
|                                  email templates, permission groups.
|
|-- controller/                    REST controllers (~22).
|   |-- AuditController            Audit campaign + BI stats endpoints
|   |-- UserChecksheetController   Inspection lifecycle (legacy name; reads/writes inspections)
|   |-- InterventionController     Intervention CRUD + activation
|   |-- ...
|
|-- service/                       Interface + Impl pairs.
|   |-- AuditService               BI scope CTE (national/region/dealer/location)
|   |-- UserChecksheetService      Inspection answers + photos + merge
|   |-- InterventionService        Intervention CRUD
|   |-- InterventionAssignmentService Plan instantiation + completion
|   |-- AiAssessmentService        Async LLM photo evaluation
|   |-- ChecksheetService          Template CRUD, versioning, status transitions
|   |-- PermissionService          Permission hierarchy + scoped access
|   |-- DashboardService           Legacy industrial-checksheet analytics
|   |-- Email/EmailService         Async email + retry
|   |-- Notification/              Firebase push (partially implemented)
|   |-- export/xlsx/ExcelSheet     Apache POI streaming Excel export
|   |-- AWSS3Service, RefreshTokenService, AppVersionService, UtilityService, ...
|
|-- entity/                        JPA entities (~32). Key new entity: Inspection.
|-- repository/                    Spring Data JPA repos (~39).
|-- DAO/                           Native-query DAOs (33). Some legacy DAOs concatenate
|                                  user input into SQL — see "Known issues" below.
|-- DTO/                           DTOs in request/, response/.
|-- exception/                     GlobalExceptionHandler + CustomException
|-- helper/                        DateHelper, FileStorageUtil, ...
```

Top-level conventions:

- Package root: `com.checkSheet` (legacy capital S; do not change).
- Interface + `*Impl` pair for every service.
- Native SQL templates: prepend `scopeCte(level)` for any BI metric so the
  row set is shared.
- Photos allowed on **any** answer type, not just FILE_UPLOAD —
  `UserChecksheetServiceImpl.mergeAnswersWithContent` loads files for every
  answer that has an id.

---

## Security Architecture

### JWT flow

1. `/api/user/login` — `username` + `password` (+ `deviceType: APP|WEB`).
2. `ApplicationConfig.authenticationProvider()` validates with
   `DaoAuthenticationProvider` + `BCryptPasswordEncoder`.
3. `JwtService.generateToken()` issues HS256-signed JWT with claims:
   - subject = username
   - `permissions` (codes), `roles` (codes)
   - expiration: `app.jwtTokenExpiration` (seconds)
4. JWT also persisted on `users.jwt_token` (legacy field — see Known
   Issues for why this is unusual).
5. `RefreshToken` entity stores opaque refresh token. V1.29 added
   `UNIQUE(user_id, device_type)` so the same user can hold an APP token
   and a WEB token simultaneously.

### Request authentication

Every request passes through `JwtAuthenticationFilter`
(`OncePerRequestFilter`):

1. Pull `Authorization: Bearer <token>`.
2. Parse JWT → username.
3. Load user.
4. Extract permissions/roles from claims; **fallback**: if not in token,
   reload from DB via `PermissionService.getEffectivePermissionsForUser()`
   + `UserRoleDepartmentDAO`.
5. Build `CustomUserDetails`.
6. Check endpoint authorization (next section).
7. Validate signature + expiration.
8. Set `UsernamePasswordAuthenticationToken` in `SecurityContextHolder`.

Whitelisted (no auth): `/api/v1/auth/**`, `/api/user/register`,
`/api/user/login`, `/api/user/hello`, `/api/user/refreshToken`,
`/api/checksheet/doc/**`, swagger.

### RBAC

Role hierarchy (V1.7 seeded; V1.21 cleanup; V1.27 added DEALER_PRINCIPAL):

```
SUPER_ADMIN
  |-- DEPT_ADMIN
        |-- SUBDEPT_ADMIN
              |-- OPERATOR
              |-- DEALER_PRINCIPAL    (V1.27+)
```

Permissions:

- Each role has direct permissions via `role_permissions`.
- `resolvePermissionsWithHierarchy()` recursively unions a role's
  permissions with its parent's. Parent inheritance is **currently
  commented out** in code — only direct permissions take effect.
- A user can have multiple roles via `user_role_departments`. All
  permissions are unioned.
- `UserRoleDepartment.departmentId = null` = global scope (SUPER_ADMIN
  pattern). Non-null = scoped to that department/section.
- `PermissionService.getAllowedSectionIds()` returns `null` for global,
  or a list for scoped users; dashboard/listing DAOs filter on it.

Authorities in Spring Security context:

- Permissions: `perm:PERMISSION_CODE` (e.g. `perm:USER_CREATE`)
- Roles: `ROLE_ROLE_CODE` (e.g. `ROLE_SUPER_ADMIN`)

Intervention permissions (V1.27):

| Code | Used by |
|---|---|
| `INTERVENTION_MANAGE` | Create / activate / close interventions |
| `INTERVENTION_ASSIGNMENT_VIEW` | Read plans (lists, detail, BI) |
| `INTERVENTION_ASSIGNMENT_MANAGE` | Close-as-non-compliant, admin overrides |
| `INTERVENTION_ASSIGNMENT_ACKNOWLEDGE` | Dealer principal acks own plans |
| `INTERVENTION_CONDUCT` | Operator runs a re-inspection wave |

### Endpoint authorization

`ApiEndpointConfig` maps each path → list of required permission codes.
`JwtAuthenticationFilter.isAuthorizedForEndpoint()`:

- Endpoint not in map → access **allowed** (no permission gate).
- Endpoint in map → user needs **at least one** listed permission (OR).
- Failure → `403 Forbidden` JSON response.

`@EnableMethodSecurity` is on but not heavily used — most authorization
happens at the filter level.

### Account status / logout

- `status = "A"` active; `"I"` inactive.
- `failLoginCount` + `lockTime` for lockout.
- `LogoutService` only clears `SecurityContext`. Token revocation
  (repository check, mark expired) is **commented out**. Tokens stay
  valid until natural expiration.

---

## BI dashboards -- single shared scope CTE

`AuditServiceImpl.buildStats(...)` builds one `audit_signal` CTE per
request and runs **every** metric query (band counts, per-region /
per-dealer / per-location table, red-list, what's-failing,
top-failing-checkpoints) as a `SELECT FROM` that CTE. This guarantees the
same row set drives every panel — band counts reconcile across panels by
construction.

The CTE is parameterised by scope via `scopeCte(level)`:

| Scope | Filter |
|---|---|
| national | _(empty)_ |
| region | `AND s.region_id = :regionId` |
| dealer | `AND aloc.auditee_id = :auditeeId` |
| location | `AND aloc.id = :locationId` |

Post-V1.28 the CTE reads from a single `inspections` table and uses a
LATERAL subquery to roll up the **latest effective answer** per
`(audit_assignment, chks_question_result)` across:

1. The original AUDIT-kind inspection's answers, and
2. Every INTERVENTION-kind inspection at the same location whose
   intervention is tied to the same audit, filtered to `status='APPROVED'`.

Latest = `ORDER BY submitted_at DESC NULLS LAST, uca_id DESC`. That
answer's judgement drives `pct_ok` for the dealer.

```sql
WITH audit_signal AS (
  SELECT ins.id AS uc_id, ins.id AS aa_id, ins.status AS uc_status,
         ins.started_at, ins.submitted_at,
         ins.audit_id, aloc.id AS location_id, aloc.address AS location_address,
         a.id AS auditee_id, a.name AS auditee_name, a.code AS auditee_code,
         c.id AS city_id, c.name AS city_name, s.id AS state_id,
         r.id AS region_id, r.name AS region_name,
         score.pct_ok
    FROM inspections ins
    JOIN auditee_locations aloc ON aloc.id = ins.auditee_location_id AND aloc.deleted_at IS NULL
    JOIN auditees a            ON a.id = aloc.auditee_id
    LEFT JOIN cities c         ON c.id = aloc.city_id
    LEFT JOIN states s         ON s.id = c.state_id
    LEFT JOIN regions r        ON r.id = s.region_id
    LEFT JOIN LATERAL (
        SELECT 100.0 * SUM(CASE WHEN uca.judgement = 1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) AS pct_ok
          FROM inspections ins2
          LEFT JOIN interventions iv ON iv.id = ins2.intervention_id
          JOIN user_checksheet_answers uca ON uca.inspection_id = ins2.id
         WHERE ins2.deleted_at IS NULL AND ins2.status = 'APPROVED'
           AND ins2.auditee_location_id = ins.auditee_location_id
           AND ((ins2.kind = 'AUDIT'        AND ins2.audit_id = ins.audit_id)
             OR (ins2.kind = 'INTERVENTION' AND iv.audit_id   = ins.audit_id))
    ) score ON TRUE
   WHERE ins.deleted_at IS NULL AND ins.kind = 'AUDIT'
     AND ins.status = 'APPROVED' AND ins.audit_id = :auditId
     {{ scope filter }}
)
```

**Single change point.** Extending the CTE's `pct_ok` LATERAL once means
every BI panel inherits the post-intervention rollup automatically. Do
not introduce parallel scoring queries elsewhere.

The recency panel (counting **all** locations, not just audited ones) is
the one query that's structurally separate — it joins `auditee_locations`
directly with the same scope filter.

The audit-report screen, by contrast, surfaces the **full chain** of
answers per question (audit → wave 1 → wave 2 → …) so reviewers see the
audit trail. Both surfaces read the same source data; they summarise it
differently.

See [`flows.md`](./flows.md) for the end-to-end code-level walkthrough.

---

## AI photo assessment

AuditPro evaluates inspection photos via the Trika LLM module
(`com.trika.llm.*`) — never calling provider APIs directly. The same AI
infrastructure runs for both audit inspections and intervention
re-inspections; the path is identical.

### Where verdicts live

`ai_assessments` (V1.19, augmented V1.20) — one row per
`(inspection, chks_question_result, photo)`:

| Column | Purpose |
|---|---|
| `inspection_id` | FK (renamed from `user_checksheet_id` in V1.28) |
| `chks_question_result_id` | Which question result |
| `photo_path` | S3 path of the assessed photo |
| `suggested_judgement` | "OK" / "NOT OK" |
| `explanation` | Natural-language reasoning |
| `confidence` | 0..1 |
| `ai_model` | e.g. `gemini-2.0-flash-exp` |
| `ai_provider` | `ANTHROPIC` / `OPENAI` / `GEMINI` (V1.20) |
| `prompt_sent`, `raw_response` | Full prompt + response — replay/debug |
| `input_tokens`, `output_tokens`, `latency_ms` | Cost + perf telemetry (V1.20) |

Indexes on `(inspection_id)`, `(chks_question_result_id)`, and the
composite — the audit-report's lookup pattern.

### Three capabilities, one infrastructure

The current AI surface supports three modes against the same async
service. All three call the Trika LLM module with a generic
prompt-and-evaluate pattern; the difference is purely prompt shape.

**1. Photo-based subjective evaluation** (covers ~63% of a typical
Kia-style template):

```
Given this photo of [Element], evaluate: [Checkpoint description]

OK criteria:     [Option 1 text — e.g. "No visible damage on ACP or logo"]
NOT OK criteria: [Option 2 text — e.g. "Visible dents, scratches, or panel damage"]

Return: OK or NOT OK, with a brief explanation of what you observed.
```

The checkpoint description and OK/NOT OK option text already live in
`chks_question_result_options` — no new data entry needed.

Covers damage checks (cracks, dents, broken signage), cleanliness checks
(dust, streaks, stains, litter), compliance/setup (logo placement, car
alignment, brand wall), paint/finish, and organization.

Cannot reliably cover: grooming photos (privacy), staff knowledge
(requires conversation), CMS-content correctness (needs out-of-band
context), washroom/cafe hygiene (smell, stocking).

**2. Counting via object detection** (covers ~16% — all OBJECTIVE
checkpoints):

Every OBJECTIVE checkpoint follows the same pattern: count something that
shouldn't be there, expected = 0. Examples: non-functional lights,
non-Kia cars in parking, missing price tags, missing name badges,
non-functional EV chargers.

```
Count the number of [target] in this photo.
Target description: [what to look for]
Return: an integer count and a brief description of what you found.
```

Auto-scoring: count returned by AI is compared against the checkpoint's
`chks_question_results` criterion (`EQUAL_TO 0`). If count > 0,
judgement = NOT OK — fully automatic, override still possible.

**3. Reference-image comparison** (compliance subset, ~8 checkpoints):

For "does this match the Kia standard?" checks, the template can store a
`reference_image_url`. The prompt sends both reference + current photo
and the model identifies deviations. Same async service, extra image in
the prompt.

### Auditor UX

1. Auditor opens a checkpoint on mobile, takes/picks a photo.
2. Photo uploads via `/api/userChecksheet/createUserChksAnsFile`.
3. AI assessment runs async; verdict (judgement + explanation +
   confidence) is written to `ai_assessments`.
4. Within seconds the audit-report row shows the AI verdict alongside
   the auditor's own judgement.
5. Auditor accepts or overrides. Final operator judgement is what the
   downstream BI reads.

### Where verdicts surface in the UI

- **Audit-report per-question rows.** For every question with a photo,
  the row shows AI verdict + auditor verdict side-by-side, with
  explanation as tooltip. Disagreements are visually flagged so
  reviewers can spot questionable judgements.
- **Intervention re-inspection inspections** (V1.27+). Same surfacing on
  the chain view — a re-inspection's photos get the same AI treatment.
  Re-inspections POST to the same file-upload endpoint; the AI path is
  identical. No separate pipeline for interventions.

### Trade-offs

- AI **suggests**, human **confirms**. The operator's judgement remains
  authoritative; the AI verdict is advisory and stored as evidence.
- The audit trail gets richer: every operator judgement now has a
  photo + AI reasoning attached.
- Provider switching is a config knob — see `ai_provider` column for the
  per-call record.

---

## Scoring system

Every question gets one binary judgement: **OK** or **NOT OK**. No
percentages, no partial grades at the question level. The system **counts**
OKs and NOT OKs and uses those counts to drive alerts, escalations, and
dashboards. The only place a percentage is calculated is `pct_ok` — see
below.

### How each answer type produces a judgement

| Type | How |
|---|---|
| **OBJECTIVE** (numeric) | Operator enters a number; backend stores the criterion (range / equal / less / greater). **Today, judgement is operator-set** — the backend does not auto-evaluate the value against the criterion. AI counting mode (above) closes this gap for photo-able checkpoints. |
| **SUBJECTIVE** (free text) | Fully manual — operator types text + chooses OK/NOT OK. |
| **SUBJECTIVE_CONDITION** (dropdown) | Each option pre-mapped to OK/NOT OK in `chks_question_result_options`. Frontend reads the mapping and submits the judgement. Backend trusts the value — no re-check. Most Kia checkpoints use this mode with Yes/No. |
| **MATRIX** (grid) | Operator marks each cell OK/NOT OK; question-level judgement is one overall value. |
| **NA** | Skipped — no judgement recorded. |

### Two storage systems (legacy quirk)

| | What | Used for |
|---|---|---|
| **System A** | `user_checksheet_answers.judgement` (SMALLINT, 1=OK, 2=NOT_OK on inspection answers) | **Authoritative for audit BI**; legacy industrial Trend Chart |
| **System B** | `usr_chksheet_ans_judgements.judgement` (VARCHAR "OK"/"NOT OK" with remarks) | **Authoritative for legacy industrial flow** — alert emails, escalation, dashboard. Informational metadata for audit BI; may be missing on seeded data. |

These are submitted by separate actions and not auto-synced. Frontend is
responsible for sending consistent values. The audit BI uses the integer
because the seeded Kia data populates it deterministically; the text
System B judgement may be missing.

### `pct_ok` — the one percentage

For audit BI:

```
pct_ok = 100.0 * (count of answers where judgement = 1)
       / (count of answers)
```

Computed in the `audit_signal` CTE's LATERAL subquery, **per
audit_assignment** (i.e. per (audit, location)), using the latest
effective answer per question. Drives every band count, regional table,
red list, what's-failing panel, and top-failing-checkpoint list.

### Post-intervention rollup

When interventions layer on top of an audit, the BI must reflect the
**current effective state** of each question — not the original audit's
frozen state. If a re-inspection wave flipped a question from NOT OK to
OK, the dealer's `pct_ok` reflects that lift.

The `audit_signal` LATERAL picks the latest answer across:

1. The original AUDIT-kind inspection.
2. Every INTERVENTION-kind inspection at the same location whose
   intervention is tied to the same audit, filtered to `status='APPROVED'`.

Worked example for a single location:

| Setup | Effective `pct_ok` |
|---|---|
| Audit alone, 24/54 NOT OK | 55.56 |
| Re-inspection wave 1 flips 3 questions OK (later submitted_at) | 61.11 |
| Wave 2 flips 2 more OK | 64.81 |
| Wave 3 (later) flips one of those back to NOT OK | 62.96 |
| Wave 4 (later still) flips it OK again | 64.81 |
| Wave submitted but **NOT YET APPROVED** | unchanged (LATERAL filters APPROVED) |

The audit-report screen keeps the **full chain** visible regardless —
its job is the audit trail. The BI shows the latest. Both surfaces read
the same source data; they just summarise it differently.

### Legacy industrial alerts + escalation (System B)

The pre-audit industrial flow runs separately:

- **Alert email** on approve-with-NOT-OK, using `ALERT_NOT_OK_JUDGEMENTS`
  template.
- **Escalation email** when the same question is NOT OK for N consecutive
  fills (configurable per checksheet via `escalation_guidelines_days`).
- **Compliance heatmap** = OK count / total per (department, period).
- **Top Non-Conforming Questions** ranks by NOT-OK frequency.
- **Trend Chart** plots numeric (OBJECTIVE) values over time with CP/CPK.
- **Completion Funnel** counts inspections per workflow stage.

### What's NOT scored today

- No automatic OBJECTIVE numeric enforcement (range check) — backend
  trusts operator's manual call. AI counting mode is the bypass for
  photo-able OBJECTIVE checkpoints.
- No overall % per inspection (just OK/NOT-OK counts).
- No weighted scoring — every question counts equally.
- No partial judgement — dropdown options always resolve to OK or NOT OK.

---

## Infrastructure Services

### Email (`EmailService`)

- Dedicated thread pools: `threadPoolTaskExecutorForEmail`,
  `threadPoolTaskExecutorForEmailWithAttachment`.
- **Retry**: up to 3 attempts with exponential backoff (3s, 6s, 12s).
- Toggle via `is_email_send` per environment.
- CC/BCC via `audit.mail.cc.user` / `audit.mail.bcc.user`.
- Templates: `EmailTemplate` enum with placeholders (`[NAME]`,
  `[CHKS_NAME]`, `[ENVIRONMENT_DOMAIN]`, `[SENDER_NAMES]`, `[UID]`, …).

### Push notifications (`FCMInitializerService`)

- Firebase Cloud Messaging via `firebase-admin` SDK.
- `FirebaseInitializer` exists; **initialization is commented out** —
  currently non-functional.
- `sendPushNotification()` runs on `threadPoolTaskExecutorForNotification`.

### S3 file storage (`AWSS3Service`)

- Config: `aws.access_key_id`, `aws.secret_access_key`, `aws.s3.region`.
- Stores: checksheet documents, question files, header data files,
  judgement files, audit inspection photos, AI-assessed photos,
  surprise-checksheet evidence.
- Paths stored as strings on entity columns or PG `varchar` arrays.

### Excel export (`ExcelSheet`)

- Apache POI `SXSSFWorkbook` (streaming API — large datasets).
- Generates downloadable reports for departments / sections / users /
  inspection data.
- Written directly to `HttpServletResponse` output stream.

### API history logging (`MyPayloadCapturingFilter`)

- Optional filter, controlled by `is_API_log_save`.
- Captures request URI, method, headers, body, response body to
  `api_history`.
- Handles JSON and multipart differently.
- Perf note: captures full response bodies — can be large.

---

## Async Processing

### Thread pools (defined in `Application.java`)

| Bean | Purpose | Core | Max | Prefix |
|---|---|---|---|---|
| `threadPoolTaskExecutorForNotification` | Firebase push | 5 | 20 | `Async-` |
| `threadPoolTaskExecutorForEmail` | Email | 5 | 20 | `Async-Email` |
| `threadPoolTaskExecutorForEmailWithAttachment` | Email with attachments | 5 | 20 | `Async-Email` |

All three pools use `waitForTasksToCompleteOnShutdown = true`.

### Scheduling

`@EnableScheduling` on `Application`. Active scheduled jobs:

- `InterventionOverdueScheduler.markNonCompliantIfOverdue()` — daily
  cron. Any plan past `target_date` still in PENDING/IN_PROGRESS gets
  flipped to NON_COMPLIANT with `closure_reason = auto-overdue`.

### Transactional events

Intervention instantiation is event-driven:

```
Operator submits audit inspection
         ↓ (validate → approve)
UserChecksheetApprovalServiceImpl publishes UserChecksheetApprovedEvent
         ↓ (AFTER_COMMIT, async)
InterventionInstantiationListener
   if inspection is kind=AUDIT       → InterventionAssignmentService.instantiateForApprovedAudit():
       for each ACTIVE intervention targeting this inspection whose
       question scope intersects the failing answers, create one
       kind=INTERVENTION inspection + intervention_assignment_questions
       for the failing-question subset.
   if inspection is kind=INTERVENTION → InterventionAssignmentService.evaluatePlanCompletion():
       if every tracked question is OK on this re-inspection, flip the
       plan to COMPLETED.
```

`@EnableTransactionManagement` is on. Services use `@Transactional` with
`rollbackFor = Exception.class` on writes. `PermissionServiceImpl` uses
`@Transactional(readOnly = true)` at class level and overrides with
write transactions on mutating methods.

---

## Deployment

### Docker

```dockerfile
FROM amazoncorretto:17.0.7-alpine
# Non-root user (uid 1000)
COPY ./target/smartcomply-0.0.1-SNAPSHOT.war /app/smartcomply-0.0.1-SNAPSHOT.war
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "/app/smartcomply-0.0.1-SNAPSHOT.war"]
```

- Amazon Corretto 17 on Alpine — minimal footprint.
- Runs as non-root `appuser` (uid 1000).
- Port 8089.
- Requires pre-built WAR (`mvn package` before `docker build`).

### Profiles

- `spring.profiles.active=uat` is the default in `application.properties`.
- `application-local.properties` for dev (gitignored creds).
- Tenant Flyway is opt-in via the `tenant-data` profile (see
  [`tenant-overlay.md`](./tenant-overlay.md)).

### Key env vars

| Var | Purpose |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection |
| `application.security.jwt.secret-key` | JWT signing key (has hardcoded default — see Known Issues) |
| `app.jwtTokenExpiration` / `app.jwtRefreshTokenExpiration` | Token lifetimes (seconds; default 10000) |
| `aws.access_key_id` / `aws.secret_access_key` / `aws.s3.region` | S3 |
| `spring.mail.host` / `spring.mail.port` / `spring.mail.username` / `spring.mail.password` | SMTP |
| `audit.mail.cc.user` / `audit.mail.bcc.user` | Email CC/BCC |
| `is_email_send` / `is_API_log_save` | Feature toggles |
| `file.uploadDir` | Local file upload directory |
| `ANTHROPIC_KEY` / `OPENAI_KEY` / `GEMINI_KEY` | LLM provider keys |
| `GITHUB_USERNAME` / `GITHUB_TOKEN` | GitHub Packages auth for Trika LLM module |

CI/CD: **GitLab CI** (`.gitlab-ci.yml`) — not GitHub Actions. Pipeline:
Compile → Test → Package WAR → Docker (manual). Triggers on MRs +
main/development.

---

## Design decisions + known issues

### What works well

1. **Single `audit_signal` CTE** for all BI. Numbers reconcile by
   construction. Adding the post-intervention rollup was a one-line
   LATERAL extension.
2. **Inspection as the runtime unit** (post-V1.28). One row IS the
   assignment IS the filled form. Half the joins disappeared.
3. **Permission-based RBAC**. Migration V1.8–V1.18 moved from hardcoded
   role workflow to fine-grained permissions; works without code changes.
4. **PostgreSQL array columns** for user assignments. Avoids join tables
   for the checksheet-user relationship; `= ANY()` is enough.
5. **Async email with retry**. Dedicated thread pools and backoff
   isolate email failures from business operations.
6. **Soft deletes everywhere**. `deleted_at` + partial unique indexes
   scoped to live rows = re-create after delete works cleanly.
7. **AI verdicts persisted alongside answers**. Audit trail is rich:
   photo + AI explanation + operator override all stored.

### What's problematic

1. **Hibernate `ddl-auto=update` still in play** for some legacy tables
   (auditees, auditee_locations, cities, states, countries). V1.32
   exists specifically because `ddl-auto` recreated columns V1.28 had
   dropped. Migrations from V1.23 onward use `ALTER TABLE IF EXISTS` +
   gated DO-blocks to be safe on both fresh-DDL and prod-restored DBs.

2. **JWT secret key hardcoded as default**. `JwtService` has a default
   secret in source. If the property is unset, that default is used —
   serious vulnerability.

3. **`users.jwt_token` field**. JWT also persisted on the user row.
   Only one active token visible per user from that column, and it's
   accessible to anyone who can query `users`.

4. **Logout does not invalidate tokens**. `LogoutService` only clears
   the security context; revocation code is commented out. JWT remains
   valid until expiration.

5. **CORS entirely commented out**. `WebConfig.java` is fully commented
   out. CORS relies on the frontend and reverse proxy — fragile,
   undocumented.

6. **SQL injection risks in legacy DAOs**. `ChecksheetDAO` concatenates
   user input into native SQL:
   - `getChecksheets(...)`: `columnName` and `userId` concatenated.
   - `getRespectedChecksheet(...)`: search/status/userId concatenated.
   - `getUserChecksheets(...)`, `getChecksheetNames(...)`,
     `isUserAssignedToOtherChecksheets(...)` — similar.
   These should be parameterised.

7. **`submission_version` type drift**. Entity field is `Byte` (8-bit);
   column is SMALLINT. Silent truncation risk above 127. Filed as
   M-TYPES-001.

8. **Firebase init commented out**. Push notifications non-functional.

9. **Duplicate thread pool prefix**. Both email pools use prefix
   `Async-Email` — thread identification ambiguous in logs.

10. **User entity implements UserDetails**. Mixes persistence with
    security. `getAuthorities()` returns empty list; real authorities
    come from `CustomUserDetails` in the filter — two parallel
    "user details" implementations.

11. **Two judgement storage systems unsynced**. System A (integer on
    answers) and System B (text on `usr_chksheet_ans_judgements`) can
    drift. Audit BI uses A; legacy industrial uses B. Frontend must keep
    them consistent.

12. **No backend enforcement of dropdown judgements**. Backend trusts
    whatever the frontend sends. A misconfigured frontend could submit
    "OK" for a NOT-OK-mapped option.

13. **Editing dropdown options deletes the old set**. Previously
    submitted answers referencing the old options may break.

14. **Default judgement is NOT OK**. If frontend forgets to send a
    judgement, the system defaults to NOT OK (0). False negatives risk.

---

## Evolution

History at the end so readers know how we got here without it cluttering
the current-state narrative.

### V1.24 — Split Checksheet into Checksheet + Audit + AuditAssignment

The original schema overloaded `Checksheet` as both *template* and *audit
campaign*. V1.24 split them into four anchor entities (pre-V1.28 model):

| Entity (V1.24) | Table | Role |
|---|---|---|
| `Checksheet` | `checksheets` | Reusable template — questions, options, scoring rules. |
| `Audit` | `audits` | A campaign — name + start/end + ACTIVE/CLOSED + which template. |
| `AuditAssignment` | `audit_assignments` | One physical site assigned to one operator within one audit. Unique per (audit, location, active). |
| `UserChecksheet` | `user_checksheets` | The filled form, hung off `audit_assignment_id`. |

**Why the split.** With Audit as a separate entity, the same template can
be reused across multiple campaigns (FY26 H1, H2, etc.); a campaign owns
its expected scope (`audit_assignments` rows are the "expected to be
audited" list); and "how many sites are still pending" becomes a clean
query.

V1.24 also wired `user_checksheets.audit_assignment_id` and introduced
the original `audit_signal` CTE (then UNIONing across `audit_assignments`
+ `user_checksheets`).

### V1.25 / V1.26 — Auditee location dedupe + UC dedupe

V1.25 deduped 417 dealer-with-duplicate-address groups in
`auditee_locations`, re-parenting (then-existing) `audit_assignments`
onto the canonical row, and replacing a table-wide UNIQUE constraint
with a partial index. V1.26 deduped the 12 leftover `user_checksheets`
that V1.25's re-parenting created. Full ops detail:
[`dedupe-runbook.md`](./dedupe-runbook.md).

### V1.27 — Intervention layer added

An audit is the formal cycle: a fixed scope of locations evaluated
against one template at one point in time. An intervention is the
*follow-up*: a reactive, narrower-scope effort to fix the questions that
failed. Cadences differ (audit cycle is fiscal-half; intervention can be
weeks). Scopes differ (audit covers all assignments; intervention
typically covers a subset). Question sets differ (audit covers every
checkpoint; intervention tracks only failing ones). Modelling
intervention as its own parallel hierarchy kept the audit lifecycle clean.

V1.27 introduced `interventions`, `intervention_assignments`,
`intervention_assignment_targets`, `intervention_assignment_questions`,
plus the DEALER_PRINCIPAL role and 5 intervention permissions. A
`UserChecksheet` row's parent became XOR: either `audit_assignment_id`
or `intervention_assignment_id`. The audit_signal CTE was extended with
the post-intervention LATERAL rollup.

### V1.28 — Collapse to inspections

Until V1.27 three tables represented one conceptual entity (one
inspection = one visit at one location): `audit_assignments`,
`intervention_assignments`, `user_checksheets`. BI rollups had to UNION
across all three; every read joined two of them; "is the inspection
started?" required a LEFT JOIN. A pre-V1.28 row was three rows
masquerading as one.

V1.28 merged the three into a single `inspections` table with a `kind`
discriminator (`'AUDIT'` | `'INTERVENTION'`). CHECK constraint enforces
the XOR (kind=AUDIT requires `audit_id`; kind=INTERVENTION requires
`intervention_id`). Eleven child tables had `user_checksheet_id`
renamed to `inspection_id`. The status enum unified:
`INVALIDATED`/`NOT_APPROVED` both collapsed into `DECLINED`.

See [`schema.md`](./schema.md) §2 for the full collapse mechanics
(child-table FK renames, status mapping, what was NOT modelled as state).

### V1.29 — refresh_token unique-per-device

Dropped Hibernate's auto-generated `UNIQUE(user_id)` on `refresh_token`
(was `@OneToOne`) and added `UNIQUE(user_id, device_type)` so the same
user can hold an APP token and a WEB token simultaneously. Entity moved
to `@ManyToOne` so Hibernate doesn't recreate the offending constraint.

### V1.30 — Dealer Principal acknowledge flow

Added `inspections.acknowledged_at` + `acknowledged_by` (FK→users) as
soft signals — they don't advance status. Service-side check enforces
`acknowledged_by` matches `dealer_principal_user_id`. Dealer principal's
ack and operator's start are independent.

### V1.31 — validation/approval unique-per-inspection

Locked `(inspection_id, validator)` and `(inspection_id, approver)`
uniqueness on the review tables via partial UNIQUE indexes (scoped to
live rows). Defends against a regression where repository queries kept
keying on `(checksheet_id, reviewer)` post-V1.28 and clobbered review
history.

### V1.32 — drop leftover user_checksheet_id column

On some DBs Hibernate `ddl-auto=update` had recreated `user_checksheet_id`
on `user_checksheet_validations` and `user_checksheet_approvals` after
V1.28 dropped it (entity field hadn't been fully renamed yet). The
leftover column was NOT NULL with a dangling FK, so every
validation/approval insert blew up. V1.32 idempotently drops both the FK
and the column.

---

## Cross-references

| Doc | When to read it |
|---|---|
| [`schema.md`](./schema.md) | **Canonical** table-by-table reference, ER diagram, status enum, migration history. |
| [`system-overview.md`](./system-overview.md) | Orientation for the audit + intervention lifecycle, BI rollup design, worked Modi-Kia trace. |
| [`flows.md`](./flows.md) | Code-level walkthroughs of each major flow + UI rendering / permission rules (absorbed from the old frontend-contract.md). |
| [`api.md`](./api.md) | REST endpoint reference, including §"Mobile API flow (audit lifecycle)". |
| [`../dev/runbooks/dedupe-runbook.md`](../dev/runbooks/dedupe-runbook.md) | V1.25 / V1.26 background — what they touched, how to re-run. |
| [`../tenants/_docs/tenant-overlay.md`](../tenants/_docs/tenant-overlay.md) | Tenant overlay mechanism. |
