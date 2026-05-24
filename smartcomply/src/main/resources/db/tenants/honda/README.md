# Honda Road to Sale — Flyway Seed

This directory contains Honda-specific Flyway migrations that populate a
fresh SmartComply DB with the NADA Road to Sale checksheet and demo data.
Flyway runs these when `TENANT_ID=honda` and the `tenant-data` profile is active.

---

## What the seed creates

| Migration | What it creates |
|-----------|----------------|
| `V200.001` | 1 Honda admin user (`honda_admin`), 1 sales rep user (`honda_rep1`), a "Road to Sale" department under Sales, and role assignments (DEPT_ADMIN + OPERATOR). |
| `V200.002` | 1 auditee (`Honda Demo Dealership`, code `HONDA_DEMO_001`), 1 location (`Honda Demo Showroom`, id 2001). |
| `V200.003` | Full NADA Road to Sale checksheet (10 steps, 16 checkpoints, 32 result options), a standing "Road to Sale – Walk-In" audit campaign, and one bootstrap inspection assigned to `honda_rep1`. |

### Checksheet structure

```
NADA Road to Sale (APPROVED, uid=RTS_HONDA_V1)
├── 1. Greet / Hospitality        [3 mandatory questions]
├── 2. Discovery                  [2 mandatory + 1 optional]
├── 3. Vehicle Match / Recommendation [1 mandatory]
├── 4. Front-Line Ready           [1 mandatory]
├── 5. Walkaround                 [2 mandatory]
├── 6. Test Drive                 [1 mandatory]
├── 7. Trade Appraisal            [2 optional — trade-in may not be present]
├── 8. Proposal / Pencil          [1 mandatory]
├── 9. F&I Handoff                [1 mandatory]
└── 10. Completion                [1 mandatory]
```

Each question has two result options: **OK** (judgement='OK') and **Not OK** (judgement='NOT OK').
The BI rollup counts `judgement=1` (integer) on `user_checksheet_answers` as the OK signal.

### Voice-engine integration (V1.34 columns)

Every answer row in `user_checksheet_answers` can carry Road to Sale
voice-detection evidence via the `rts_*` columns added by V1.34:

| Column | Type | Purpose |
|--------|------|---------|
| `rts_cue_id` | VARCHAR(255) | Voice cue atom ID (e.g. `workflow.hospitality_offer`) |
| `rts_cue_source` | VARCHAR(50) | `'feature'` or `'workflow'` |
| `rts_transcript_snippet` | TEXT | Short transcript excerpt (≤200 chars) |
| `rts_cue_confidence` | NUMERIC(4,3) | Confidence 0.000–1.000 |
| `rts_voice_auto_completed` | BOOLEAN | TRUE = voice-detected, FALSE = manual |

---

## How to run (local dev)

```bash
# 1. Start the app with the honda tenant + tenant-data profile
SPRING_PROFILES_ACTIVE=local,tenant-data TENANT_ID=honda ./mvnw spring-boot:run
```

Flyway runs `db/migration/V1.x` (universal schema) then `db/tenants/honda/V200.x`
(Honda seed) on first boot. The app serves on `http://localhost:8089`.

### Demo credentials

All demo users share the test password: **`Welcome@123`**

| Username | Role | Purpose |
|----------|------|---------|
| `honda_admin` | DEPT_ADMIN | Manages the Road to Sale section; creates audits, sees BI dashboards |
| `honda_rep1` | OPERATOR | Field sales rep; logs in via mobile app, completes walk-in inspections |

---

## SmartComply API calls to get started

### 1. Login

```
POST /api/user/login
{
  "username": "honda_rep1",
  "password": "Welcome@123",
  "deviceType": "APP"
}
```

Response includes `jwtToken` — pass as `Authorization: Bearer <token>` on all subsequent calls.

### 2. Get my open assignments (mobile rep flow)

```
GET /api/audit/myAssignments
Authorization: Bearer <token>
```

Returns the bootstrap inspection created by V200.003 (audit_id=2001, Honda Demo Showroom).

### 3. Open / start an inspection

```
POST /api/userChecksheet/createOrUpdate
{
  "auditAssignmentId": <inspection_id_from_myAssignments>
}
```

Moves the inspection from ASSIGNED → IN_PROGRESS.

### 4. Submit an answer (with voice data)

```
POST /api/userChecksheet/createOrUpdateUserChksAns
{
  "inspectionId": <id>,
  "chksQuestionResultId": 2001,
  "judgement": 1,
  "rts_cue_id": "workflow.greeting_30s",
  "rts_cue_source": "workflow",
  "rts_transcript_snippet": "Hi, welcome to Honda, I'm Alex",
  "rts_cue_confidence": 0.92,
  "rts_voice_auto_completed": true
}
```

Note: the `rts_*` fields are extension columns — the core SmartComply answer
endpoint may not map them yet. Wire them directly via the JDBC layer or a
Road-to-Sale-specific endpoint in the `road-to-sale-app` module.

### 5. Submit the completed inspection

```
POST /api/userChecksheet/createOrUpdate
{
  "auditAssignmentId": <inspection_id>,
  "status": "SUBMITTED"
}
```

---

## Adding more demo data

To create additional inspections (simulate multiple walk-in customers):

```bash
# Create a new inspection via the API for the same audit + location
POST /api/userChecksheet/createOrUpdate
{
  "auditAssignmentId": <new>,
  "auditId": 2001,
  "auditeeLocationId": 2001
}
```

Or call `POST /api/audit/addAuditAssignments` to bulk-assign more
locations to the walk-in audit (add US dealer locations first via
a follow-up `V200.004` seed).
