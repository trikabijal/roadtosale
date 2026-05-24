# AuditPro — CLAUDE.md

## What is this?

AuditPro is a digital audit-management backend (Spring Boot 3.1.4, Java 17). Forked from an older "SmartComply" codebase — the internal artifact is still named `smartcomply`. It powers field audits (e.g. Kia showroom inspections) with AI-powered photo assessment via the Trika LLM module.

Two consumers:
- **smartcomply-angular** — the BI dashboards + audit management web app. Lives at `../smartcomply-angular`.
- **auditpro-mobile-app** — the field-auditor mobile app (Ionic + Capacitor). Lives at `../auditpro-mobile-app`.

This repo is the REST API for both.

---

## Build & Run

```bash
# Prerequisites: Java 17, Maven 3.9+, PostgreSQL running locally
# Copy .env.example → .env and fill in credentials

./mvnw compile
./mvnw test -Dtest='!com.checkSheet.demo.ApplicationTests'
./mvnw package -DskipTests

# Run locally (port 8089)
java -jar target/smartcomply-0.0.1-SNAPSHOT.war --spring.profiles.active=local
```

### Local demo: stop the long-running processes

Demos keep the backend (`:8089`) and the Angular dev server (`:4200`, in the
sibling `smartcomply-angular` repo) running across Claude sessions so the
browser stays connected and ng-serve hot-reload keeps working. They DO NOT
die when a Claude session ends. To stop them cleanly after the demo:

```bash
# Kill whatever is listening on each port
for port in 8089 4200; do
  pid=$(lsof -nP -iTCP:$port -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$pid" ] && kill "$pid" && echo "killed $pid on :$port"
done

# Verify gone (each should now connection-refuse)
curl -s -o /dev/null -w ":8089 %{http_code}\n" http://localhost:8089/api/audit/list
curl -s -o /dev/null -w ":4200 %{http_code}\n" http://localhost:4200
```

If `lsof` returns nothing, the process is already dead. Multi-day sessions
also accumulate dozens of orphaned `until ... do sleep N; done` zsh shells
from prior Bash `run_in_background` calls — `ps -u "$USER" | grep "do sleep"`
to find them, then `kill <pid>` one at a time (zsh's argument parser
truncates space-separated kill args).

### Trika LLM dependency

Depends on `com.trika:trika-llm-module:1.0.0` from GitHub Packages. Local builds need `~/.m2/settings.xml` with GitHub credentials:

```xml
<settings>
  <servers>
    <server>
      <id>github-trika-llm</id>
      <username>${GITHUB_USERNAME}</username>
      <password>${GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

---

## Domain model

Three anchor entities. **V1.28 collapsed** what used to be three runtime tables (`audit_assignments`, `intervention_assignments`, `user_checksheets`) into a single `inspections` table with a `kind` discriminator. There is no separate `AuditAssignment` or `InterventionAssignment` entity anymore — the inspection IS the assignment.

| Entity | Table | What it represents |
|---|---|---|
| `Checksheet` | `checksheets` | A **template** — questions, options, scoring rules. Reusable across multiple audit campaigns. |
| `Audit` | `audits` | A **campaign** — name + start/end + which template + ACTIVE/CLOSED. New in V1.24. |
| `Intervention` | `interventions` | An **improvement campaign** tied to one audit, with priority + target date + status (DRAFT/ACTIVE/CLOSED). V1.27. |
| `Inspection` | `inspections` | The runtime row. **One per (audit, location)** with `kind='AUDIT'` for original audit visits; **one per (intervention, location)** with `kind='INTERVENTION'` for re-inspection visits. Carries the full lifecycle status (ASSIGNED → IN_PROGRESS → SUBMITTED → VALIDATED → APPROVED, with DECLINED bouncing back to IN_PROGRESS). Owns answers + photos via `inspection_answers`/`inspection_answer_files`-keyed children. V1.28. |

```
Checksheet (template) ──── checksheet_id ────┐
                                             │
        Audit (campaign) ─────────────────── ┼──> Inspection (kind='AUDIT')
                                             │      ↑ audit_id
                                             │      │ One row per (audit, auditee_location).
                                             │      │ Holds status + answers + photos.
                                             │      │
        Intervention (improvement campaign)  │      │
              │ audit_id ───────────────────┘       │
              │                                     │
              └──> Inspection (kind='INTERVENTION') ┘
                     ↑ intervention_id
                     One row per (intervention, auditee_location).
                     Same lifecycle as audit-kind. Re-inspection answers
                     contribute to the same audit_signal rollup.
```

Children of `inspections` (the answer/file/validation/approval tables) keep their pre-V1.28 names (`user_checksheet_answers`, `user_checksheet_validations`, etc.) — only their FK column was renamed `user_checksheet_id` → `inspection_id`. A separate "rename child tables" follow-up tracks dropping the `user_checksheet_*` prefix entirely.

The legacy Java class names `UserChecksheetServiceImpl`, `UserChecksheetController`, `UserChecksheetDTO`, etc. survive at the service/controller/DTO layer for now — see GitLab issue for the full noun rename.

Hierarchical entities (Kia uses 4 levels):

| Hierarchy level | Entity | Example |
|---|---|---|
| Zone | `chks_header_data` (top of tree) | Exterior, Interior |
| Category | `chks_header_data` (nested) | Branding & Visibility |
| Element | `chks_header_data` (nested) | Front ACP + Logo |
| Question (checkpoint) | `chks_questions` | Damage |

Question result types: `SUBJECTIVE_CONDITION` (pick one of N options), `OBJECTIVE` (numeric with criterion), `SUBJECTIVE` (free text), `FILE_UPLOAD`. Photo evidence is now allowed on **any** answer type, not just FILE_UPLOAD.

---

## Customer terminology overrides

The frontend renames generic terms per customer (Kia maps `Checksheet → Audit`, `Section → Dealership`, `Operator → Auditor`). Two distinct concepts to keep straight in the BI:

- **Dealership** = `auditees` table = a brand entity like "Modi Kia"
- **Location** = `auditee_locations` table = one physical site (a dealership can have several)

A dealership rolls up across all its locations. The BI's "Bottom Dealerships" panel groups by dealership and shows a `locationCount` per row. A "Location dashboard" shows one site.

---

## Project Layout

```
src/main/java/com/checkSheet/
├── controller/          # REST controllers (~22)
│   └── AuditController  # Audit + BI stats endpoints
├── service/             # Interface + Impl pairs
│   ├── AuditService(Impl)              # Audit campaigns + BI scope CTE
│   ├── UserChecksheetServiceImpl       # Audit instances + answers + photos
│   ├── Notification/   Email/   export/xlsx/
├── entity/              # JPA entities (~32)
│   ├── Audit, Intervention                # campaigns
│   └── Inspection                         # post-V1.28: replaces UC + AA + IA
├── repository/          # Spring Data JPA repos
│   ├── AuditRepository, AuditAssignmentRepository  # AA repo kept; queries Inspection where kind='AUDIT'
├── DTO/
│   ├── AuditDTO, AuditAssignmentDTO, AuditStatsDTO    # BI payloads
│   ├── AuditAssignmentCreateDTO, AddAuditAssignmentsRequestDTO
│   ├── request/, response/
├── DAO/                 # Custom native queries
├── config/              # Security, S3, LLM, file storage, JWT
├── constant/            # Enums (ChecksheetStatusType, AuditStatus, ...)
├── exception/           # GlobalExceptionHandler + CustomException
├── helper/              # DateHelper, FileStorageUtil, ...
└── Application.java

src/main/resources/
├── application.properties              # Active profile: uat
├── application-local.properties        # Local dev (DB url + creds gitignored)
└── db/migration/                       # Flyway V1.0 → V1.26

src/test/java/com/checkSheet/
└── audit/AuditFlowE2ETest, BiDrillDownE2ETest

dev/
├── seed-test-data.sql                  # Demo operators (KIA_DEMO_AUDITOR_001..030)
├── seed-kia-demo.py                    # 200 audits, 175 UCs, photos + AI calls
├── seed-multi-location.py              # Adds 2-3 locations to 5 dealerships
└── backfill-remarks.py                 # 8167 contextual auditor remarks

docs/                                   # See "Documentation" section below
```

---

## Database

- **PostgreSQL** — database name: `smartcomply`
- **Flyway** migrations in `src/main/resources/db/migration/`
  - Baseline: V1.18 (local profile baselines at V1.22 since dev DB was restored from a UAT pg_dump)
  - **Latest: V1.29**

Recent migrations of interest:

| Version | What it does |
|---|---|
| `V1.23` | Adds `regions` + auditee contact fields (BI scope ladder) |
| `V1.24` | Adds `audits` + `audit_assignments` tables. Wires `user_checksheets` to `audit_assignment_id`. (Both `audit_assignments` and `user_checksheets` collapsed away in V1.28.) |
| `V1.25` | **Dedupes `auditee_locations`** — collapses 417 dealer-with-duplicate-address groups, re-parents the (then-existing) `audit_assignments` onto the canonical row, replaces the table-wide unique constraint with a partial index. |
| `V1.26` | Dedupes leftover multiple `user_checksheets` per assignment (12 cases V1.25's re-parenting created). |
| `V1.27` | **Adds `interventions` + `intervention_assignments` + `intervention_assignment_targets` + `intervention_assignment_questions`.** Lets a UC parent EITHER an audit_assignment OR an intervention_assignment (XOR via CHECK). |
| **`V1.28`** | **Schema collapse: 3 tables → 1.** Merges `audit_assignments`, `intervention_assignments`, and `user_checksheets` into a unified `inspections` table with `kind` discriminator ('AUDIT' \| 'INTERVENTION'). Renames `user_checksheet_id` → `inspection_id` on 11 child tables. Re-points `intervention_assignment_targets` and `_questions` to inspection_id. Maps legacy statuses (INVALIDATED/NOT_APPROVED → IN_PROGRESS) into the V1.28 enum (ASSIGNED/IN_PROGRESS/SUBMITTED/VALIDATED/APPROVED/DECLINED). |
| `V1.29` | Drops Hibernate's auto-generated UNIQUE(user_id) on `refresh_token` (was @OneToOne) and adds proper UNIQUE(user_id, device_type) so a user can be logged in from APP and WEB simultaneously. |

---

## BI dashboards — single shared scope CTE

Every BI metric query in `AuditServiceImpl.buildStats(...)` derives from one shared CTE built by `scopeCte(level)`:

```sql
-- Post-V1.28: single inspections table; kind discriminator gates the base set;
-- LATERAL pct_ok rolls up the latest answer per (chks_question_result_id) across
-- the AUDIT-kind row + any INTERVENTION-kind rows at the same location whose
-- intervention is tied to the same audit. The shape is unchanged from pre-V1.28
-- semantically — only the SQL layer collapsed.
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

`{{ scope filter }}` is `AND s.region_id = :regionId` for region scope, `AND aloc.auditee_id = :auditeeId` for dealer scope, `AND aloc.id = :locationId` for location scope, empty for national.

Every downstream metric (band counts, per-region/per-dealer/per-location table, red list, what's-failing, top-failing-checkpoints) `SELECT FROM audit_signal`. This guarantees the same row set drives every panel.

The recency panel (counting **all** locations, not just audited ones) is the one query that's structurally separate — it joins `auditee_locations` directly with the same scope filter.

---

## REST API surface

### Audit management

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/audit/createAudit` | Create a new audit campaign |
| `POST` | `/api/audit/addAuditAssignments` | Bulk-attach assignments (idempotent on `(audit_id, location_id)`) |
| `GET` | `/api/audit/list` | All audits with summary stats |
| `GET` | `/api/audit/{auditId}` | Audit detail + assignment status |

### BI stats (consumed by smartcomply-angular)

| Method | Path |
|---|---|
| `GET` | `/api/audit/{auditId}/stats/national` |
| `GET` | `/api/audit/{auditId}/stats/region/{regionId}` |
| `GET` | `/api/audit/{auditId}/stats/dealer/{auditeeId}` |
| `GET` | `/api/audit/{auditId}/stats/location/{locationId}` |
| `GET` | `/api/audit/userChecksheetMeta?userChecksheetId=N` — header context for the audit-report page |

### Mobile (consumed by auditpro-mobile-app)

| Method | Path |
|---|---|
| `GET` | `/api/audit/myAssignments` — current operator's open assignments |
| `POST` | `/api/userChecksheet/createOrUpdate` — accepts `auditAssignmentId` (server resolves audit + location) |
| `POST` | `/api/userChecksheet/createOrUpdateUserChksAns` — answer payloads (one per `chksQuestionResultId`) |
| `POST` | `/api/userChecksheet/createUserChksAnsFile` — multipart photo upload, attached to any answer type |

Mobile login: `POST /api/user/login` with `deviceType: APP` (web uses `WEB`).

---

## CI/CD

- **GitLab CI** (`.gitlab-ci.yml`) — not GitHub Actions
- Pipeline: Compile → Test → Package (WAR) → Docker (manual)
- Triggers on MRs + main/development branches
- Docker image: Amazon Corretto 17 Alpine, port 8089

---

## Key Conventions

- **Package root**: `com.checkSheet` (yes, capital S — legacy naming, don't change)
- **Service pattern**: Interface + `*Impl` class (`AiAssessmentService` / `AiAssessmentServiceImpl`)
- **DTOs**: Separate request/response classes in `DTO/request/` and `DTO/response/`
- **Entity naming**: Prefixed with `Chks` for checksheet template entities (e.g. `ChksHeader`, `ChksQuestionResult`); audit-instance entities use plain names (`Audit`, `AuditAssignment`, `UserChecksheet`)
- **Config via properties**: Use `application-{profile}.properties`, not YAML
- **LLM calls**: Go through the Trika LLM module (`com.trika.llm.*`), never call provider APIs directly
- **Native SQL templates**: prepend `scopeCte(level)` for any BI metric query so the row set is shared
- **Photos on any answer type**: the read-side merge in `UserChecksheetServiceImpl.mergeAnswersWithContent` loads files for every answer that has an id, not just FILE_UPLOAD-typed ones
- **`uca.judgement` integer (1=OK, 2=NOT OK)** is authoritative; the string judgement on `usr_chksheet_ans_judgements` is informational metadata and may be missing for seeded data — fall back to the integer

---

## Environment Variables

See `.env.example` for the full list. Key ones:

| Variable | Purpose |
|----------|---------|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL connection |
| `ANTHROPIC_KEY` | Anthropic API key (primary LLM provider) |
| `OPENAI_KEY` | OpenAI API key (benchmark/fallback) |
| `GEMINI_KEY` | Gemini API key (benchmark/fallback — used by Kia demo seed for photo assessment) |

---

## Demo data

To populate a fresh local DB for the Kia demo:

```bash
# 1. Migrate to V1.26 (auto-runs on app startup)
java -jar target/smartcomply-0.0.1-SNAPSHOT.war --spring.profiles.active=local
# 2. Seed test users + audit
psql -d smartcomply < tenants/kia/bin/seed-test-data.sql
# 3. Generate the 200-audit dataset (real Gemini Flash AI calls — needs GEMINI_KEY)
python3 tenants/kia/bin/seed-kia-demo.py
# 4. Add multi-location dealerships for the drill demo
python3 tenants/kia/bin/seed-multi-location.py
# 5. Backfill contextual auditor remarks
python3 tenants/kia/bin/backfill-remarks.py
```

Demo users (all password `12345678`):
- `KIA_SALES_DEPT_HEAD` — BI dashboard viewer
- `KIA_DEMO_AUDITOR_001..030` — field operators (the mobile app's test users)

---

## Documentation

All docs live in `docs/`:

| File | Purpose |
|------|---------|
| `api.md` | REST API reference. Includes §"Mobile API flow (audit lifecycle)" (the mobile contract) and §"Appendix: Permission Endpoint Mapping". |
| `architecture.md` | System architecture + design decisions, plus folded scoring-system + AI photo assessment content. Evolution history (V1.24+) at the end. |
| `schema.md` | **Canonical** table-by-table schema reference + ER diagram. |
| `flows.md` | End-to-end code flow walkthroughs + UI rendering / permission-based UI rules (absorbed from the former frontend-contract.md). |
| `system-overview.md` | High-level orientation: audit + intervention lifecycle, BI rollup, worked Modi-Kia trace. |
| `role-flows.md` | Per-role UX walkthroughs (work-in-progress). |
| `build.md` | Build and deployment details. |

Non-`docs/` documentation that's still load-bearing:
- `dev/runbooks/dedupe-runbook.md` — V1.25 + V1.26 dedupe migrations background.
- `dev/test-plans/test-plan-intervention.md` — frozen intervention test plan.
- `dev/test-plans/e2e-tests.md` — audit-flow test plan.
- `dev/changelogs/` — infra / config / CI change logs.
- `tenants/_docs/tenant-overlay.md` and `tenants/_docs/onboarding-new-tenant.md` — tenant mechanism + how to add a new OEM.
- `tenants/kia/docs/uat-kia-seed-runbook.md` — Kia UAT seed runbook.
