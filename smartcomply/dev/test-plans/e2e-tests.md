# SmartComply / AuditPro — E2E Test Plan

Source of truth for what the test suite covers. Tests are built from this — they don't define it.

Black-box rule: every E2E test interacts with the system **through public facades only** — REST endpoints, JWT auth, the seed SQL. No internal-class imports, no private-method calls, no white-box assertions. Internal logic belongs to unit tests.

## Scope of this document

This plan focuses on the **audit flow** introduced by V1.24 (Audit + AuditAssignment). Pre-V1.24 surface (template management, dashboards, user CRUD, etc.) is out of scope here — those have their own coverage or lack thereof, tracked separately.

## Public facades in scope

| Facade | Type | Purpose |
|---|---|---|
| `POST /api/user/login` | REST | Authenticate; obtain JWT |
| `POST /api/audit/createAudit` | REST | Create an audit campaign |
| `POST /api/audit/addAuditAssignments` | REST | Bulk-attach assignments (location + operator) to an audit |
| `GET /api/audit/list` | REST | List audits with assignment-count aggregates |
| `GET /api/audit/{auditId}` | REST | Audit detail + per-assignment status |
| `GET /api/audit/{auditId}/stats/national` | REST | BI: national-scope stats |
| `GET /api/audit/{auditId}/stats/region/{regionId}` | REST | BI: region-scope stats |
| `GET /api/audit/{auditId}/stats/dealer/{auditeeId}` | REST | BI: dealer-scope stats |
| `GET /api/audit/{auditId}/stats/location/{locationId}` | REST | BI: location-scope stats |
| `POST /api/userChecksheet/createOrUpdate` | REST | Create or update an audit instance |
| `POST /api/userChecksheet/createOrUpdateUserChksAns` | REST | Submit answers |
| `POST /api/userChecksheet/createUserChksAnsFile` | REST | Attach photo evidence |
| `POST /api/ai/assess` | REST | Run AI on a photo (Gemini Flash via Trika LLM module) |
| `POST /api/userChecksheetValidation/addUserChecksheetValidation` | REST | Validator step |
| `POST /api/userChecksheetApproval/addUserChecksheetApproval` | REST | Approver step |
| `tenants/kia/bin/seed-test-data.sql` | SQL | Demo operator + validator/approver seed |

## Tier definitions

| Tier | Environment | Speed target | When to run |
|---|---|---|---|
| **Tier 1** | Spring Boot test context, real Postgres (V1.24 applied), Gemini mocked at the `LlmClient` bean | < 30s suite | Every PR |
| **Tier 2** | Real local backend on port 8089 + real Postgres + mocked Gemini | < 2 min suite | Pre-merge, before release branch cut |
| **Tier 3** | Real backend + real Gemini Flash + real photos | 2–5 min | Manual / weekly. The `tenants/kia/bin/seed-kia-demo.py` smoke + full run lives here. |

## Facade Coverage Ledger

### Authentication

| # | Facade | Behavior | Inputs/State | Expected Output | Tier | Notes |
|---|---|---|---|---|---|---|
| A1 | POST /user/login | Successful operator login | KIA_DEMO_AUDITOR_001 / 12345678 / WEB | 200 with accessToken | 1 | Used as setup for every audit-flow test |
| A2 | POST /user/login | Wrong password | bad creds | 401 / structured error | 2 | Pre-existing behavior, regression guard |

### Audit campaign management

| # | Facade | Behavior | Inputs/State | Expected Output | Tier | Notes |
|---|---|---|---|---|---|---|
| C1 | POST /audit/createAudit | Happy path | name + APPROVED checksheetId + dates | 200 with audit id | 1 | |
| C2 | POST /audit/createAudit | Missing name | empty name | 422 "Please provide audit name" | 1 | |
| C3 | POST /audit/createAudit | Non-APPROVED checksheet | checksheetId of CREATE_CONTENT template | 422 "Checksheet template must be APPROVED" | 1 | Round-1 fix |
| C4 | POST /audit/createAudit | Invalid status | status="DROP TABLE" | 422 "Invalid audit status" | 1 | Round-1 fix |
| C5 | POST /audit/createAudit | Duplicate name | second create with same name | 200 returning existing audit (idempotent) | 1 | |
| C6 | POST /audit/addAuditAssignments | Bulk happy path | auditId + 5 valid (loc, operator) | 200 "Added 5" | 1 | |
| C7 | POST /audit/addAuditAssignments | Duplicates within one call | same auditeeLocationId twice | 200 "Added 1" (deduped) | 1 | Round-1 fix |
| C8 | POST /audit/addAuditAssignments | Re-submit existing | same payload twice | 200 "Added 0" (idempotent skip) | 1 | |
| C9 | POST /audit/addAuditAssignments | Invalid auditeeLocationId | nonexistent id | 422 with the bad id in message | 1 | |
| C10 | POST /audit/addAuditAssignments | Invalid operatorUserId | nonexistent id | 422 with the bad id in message | 1 | Round-1 fix |
| C11 | POST /audit/addAuditAssignments | Bad payload (string id) | "auditeeLocationId":"abc" | 400 "Invalid request" (no stack leak) | 1 | Round-1 fix |
| C12 | GET /audit/list | Returns aggregates | 1 audit with 200 assignments, 150 APPROVED | row with totalAssignments=200, done=150 | 1 | |
| C13 | GET /audit/{id} | Returns assignments | seeded audit | dto.assignments has all rows | 1 | |
| C14 | GET /audit/{id} | Invalid id | 99999 | 422 "Invalid auditId" | 1 | |

### User checksheet creation + ownership

| # | Facade | Behavior | Inputs/State | Expected Output | Tier | Notes |
|---|---|---|---|---|---|---|
| U1 | POST /userChecksheet/createOrUpdate | CREATE happy path | auditAssignmentId assigned to me, status=IN_PROGRESS | 200 with id, derived auditId/locationId/checksheetId | 1 | |
| U2 | POST /userChecksheet/createOrUpdate | CREATE missing auditAssignmentId | no id | 422 "Please provide auditAssignmentId" | 1 | |
| U3 | POST /userChecksheet/createOrUpdate | CREATE invalid auditAssignmentId | nonexistent | 422 "Invalid auditAssignmentId" | 1 | |
| U4 | POST /userChecksheet/createOrUpdate | CREATE on assignment owned by another operator | as A, target B's assignment | **403 "not assigned to you"** | 1 | **Round-1 security fix** |
| U5 | POST /userChecksheet/createOrUpdate | CREATE on soft-deleted assignment | aa.deleted_at != null | 422 "Audit assignment has been deleted" | 1 | Round-1 fix |
| U6 | POST /userChecksheet/createOrUpdate | CREATE while another IN_PROGRESS exists | second create on same assignment | 422 "already in IN PROGRESS" | 1 | |
| U7 | POST /userChecksheet/createOrUpdate | UPDATE happy path (own UC, status=SUBMITTED) | id of my UC | 200, status=SUBMITTED, startedAt preserved | 1 | |
| U8 | POST /userChecksheet/createOrUpdate | UPDATE someone else's UC | as A, target B's UC by id | **403 "not authorized to modify"** | 1 | **Round-1 security fix** |
| U9 | POST /userChecksheet/createOrUpdate | UPDATE without re-sending startedAt | only id + status=SUBMITTED | 200, startedAt unchanged in DB | 1 | Round-1 fix |
| U10 | POST /userChecksheet/createOrUpdate | UPDATE non-existent id | id=99999 | 422 "Invalid Userchecksheet Id" | 1 | |

### Answers + photo evidence

| # | Facade | Behavior | Inputs/State | Expected Output | Tier | Notes |
|---|---|---|---|---|---|---|
| Q1 | POST /createOrUpdateUserChksAns | Submit batch of mixed types (OBJECTIVE, SUBJECTIVE, SUBJECTIVE_CONDITION) | 30 answers | 200 with all saved DTOs (each has id) | 1 | |
| Q2 | POST /createOrUpdateUserChksAns | Submit on UC I don't own | as A, parent UC owned by B | **403** | 1 | **Round-1 security fix** |
| Q3 | POST /createOrUpdateUserChksAns | Missing answer for SUBJECTIVE_CONDITION (no option, no flag) | invalid payload | 422 "Please provide Answer" | 1 | |
| F1 | POST /createUserChksAnsFile | Photo on SUBJECTIVE_CONDITION answer | answerId of my UC, valid jpg | 200 (Round-1 lifted FILE_UPLOAD restriction) | 1 | |
| F2 | POST /createUserChksAnsFile | Photo on FILE_UPLOAD answer | unchanged path | 200 | 1 | Regression check |
| F3 | POST /createUserChksAnsFile | Photo on someone else's UC | as A, answerId of B's UC | **403** | 1 | **Round-1 security fix** |
| F4 | POST /createUserChksAnsFile | Missing userChecksheetAnswerId | no id | 422 | 1 | |
| F5 | POST /createUserChksAnsFile | Empty file | userChecksheetAnswerId set, no file | 422 "Please provide valid file" | 1 | |

### State machine

| # | Facade | Behavior | Inputs/State | Expected Output | Tier | Notes |
|---|---|---|---|---|---|---|
| S1 | Validate API | Valid VALIDATED transition | DATA_VALIDATOR token, SUBMITTED UC | 200 "validation added"; UC.status → VALIDATED | 1 | |
| S2 | Validate API | INVALIDATED transition | DATA_VALIDATOR token | UC.status → INVALIDATED (terminal) | 1 | |
| S3 | Validate API | Wrong status enum | "APPROVED" instead of VALIDATED | 422 / 4xx | 1 | |
| S4 | Approve API | Valid APPROVED transition | DATA_APPROVER token, VALIDATED UC | UC.status → APPROVED | 1 | |
| S5 | Approve API | NOT_APPROVED transition | DATA_APPROVER token | UC.status → NOT_APPROVED (terminal) | 1 | |
| S6 | createOrUpdate (re-do path) | Operator creates new UC after NOT_APPROVED | original UC still in NOT_APPROVED state | new UC accepted on same assignment | 1 | docs claim this works |

### BI stats endpoints

| # | Facade | Behavior | Inputs/State | Expected Output | Tier | Notes |
|---|---|---|---|---|---|---|
| B1 | /stats/national | Returns shape | seeded audit | { greenCount, amberCount, redCount, whatsFailing[], topFailingCheckpoints[], aiInsights[] } | 1 | |
| B2 | /stats/region/{regionId} | Filters by region | seeded audit | only that region's UCs counted | 1 | |
| B3 | /stats/dealer/{auditeeId} | Filters by dealer | seeded audit | only that dealer's UCs | 1 | |
| B4 | /stats/location/{locationId} | Per-UC trend | seeded audit | scoreTrend rows per UC | 1 | |
| B5 | All BI endpoints | Empty audit (no APPROVED UCs) | new audit | green=0/amber=0/red=0; no nulls | 1 | |
| B6 | /stats/national | AI-insight co-occurrence | seeded paver+signage co-fail | aiInsights non-empty with %  | 2 | Depends on real seed data; mocked in Tier 1 |

### AI assessment

| # | Facade | Behavior | Inputs/State | Expected Output | Tier | Notes |
|---|---|---|---|---|---|---|
| AI1 | /api/ai/assess | Happy path | photo + UC + qr_id, mocked Gemini returns OK verdict | 200, ai_assessments row written | 1 | Gemini mocked at LlmClient bean level |
| AI2 | /api/ai/assess | Spending guard tripped | mocked LlmClient throws SpendingGuardException | 429 with structured error | 1 | |
| AI3 | /api/ai/assess | Real Gemini Flash | one photo against real provider | 200 with non-null verdict | 3 | Run weekly; budget guard active |

### Migration safety (Tier 1 only via clean Postgres)

| # | Facade | Behavior | Inputs/State | Expected Output | Tier | Notes |
|---|---|---|---|---|---|---|
| M1 | V1.24 cold-apply | Empty pre-V1.24 DB | flyway:migrate from V1.18 baseline | All tables exist; no orphan FKs | 1 | testcontainers Postgres |
| M2 | V1.24 idempotent re-run | Already-applied DB | flyway:migrate again | No-op (history table guard) | 1 | |

## Test isolation rules

- Every test creates its own audit + assignments via the API. No two tests share an audit id.
- DB cleanup runs in `@AfterEach` for each test (delete user_checksheet rows + dependents created during the test).
- Operators KIA_DEMO_AUDITOR_001..030 are pre-seeded by `tenants/kia/bin/seed-test-data.sql`; tests pick distinct operator slots so no two tests collide on the same operator.
- The mocked `LlmClient` bean is per-test — no shared state across tests.
- Tests are xdist-safe: no shared ports, no fixed file paths, no order assumptions.

## Test markers (planned)

```
@Tag("tier1")  // ~25 tests, < 30s
@Tag("tier2")  // ~5 tests, < 2 min, requires running backend
@Tag("tier3")  // 2 tests, manual, real Gemini
```

CI runs `mvn test -Dgroups=tier1` on every PR. Release branch adds `tier2`. Tier 3 is `tenants/kia/bin/seed-kia-demo.py` smoke + full run.

## Deferred (tracked in Round 3 GitLab issue)

- BI endpoint authorization tests (currently no scoping → will fail when scoping ships; written then)
- Concurrent IN_PROGRESS race regression (requires partial unique index from V1.25)
- AI spending guard persistence + atomic check
- AuditAssignment soft-delete cascade behavior

## Role-based review

| Lens | Question | Answer |
|---|---|---|
| **Tester** | Are all behaviors observable from the facade? | Yes — every assertion is a status code or a JSON field. |
| **Developer** | Do tests assert on contract or implementation? | Contract — DTO shape, status, derived fields. No internal calls. |
| **Product Manager** | Does Tier 1 prove the demo works? | Yes — happy path of every step + the security guards. |
| **Engineering Manager** | Is Tier 1 < 30s? | Target. Each test does ~6 API calls; with @SpringBootTest reuse and mocked Gemini, doable. |
