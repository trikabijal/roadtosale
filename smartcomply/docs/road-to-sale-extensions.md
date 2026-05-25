# Road to Sale — SmartComply Extensions

This document specifies the additive database extensions that Road to Sale
introduces on top of SmartComply V1.29. All changes are backward-compatible:
no existing column is modified or dropped, and existing SmartComply BI queries
are unaffected.

---

## 1. Purpose

Road to Sale is a Honda-tenant voice-coaching layer that runs on top of the
SmartComply audit runtime. It needs to:

1. Store which voice-engine cue triggered each `user_checksheet_answers` row,
   along with the transcript snippet and confidence score that produced it.
2. Store trade-in vehicle evidence photos taken by the rep during the Trade
   Appraisal step, keyed by camera angle (slot).

Both requirements are met with additive schema changes only:

- **Five new nullable columns** on `user_checksheet_answers` (prefixed `rts_`).
- **One new table** `rts_trade_photos`.

No existing column is modified. Existing answers with no voice data simply
carry NULLs in the new columns. The SmartComply BI rollup CTE
(`audit_signal`) reads only `judgement` and is entirely unaffected.

---

## 2. Migration

### Flyway version

`V1.34__road_to_sale_extension.sql`

Applied automatically on SmartComply startup after V1.29 (the current
production baseline). Safe to apply to a live database — all new columns are
nullable and the new table has no FK constraints that could block existing data.

### Rollback

```sql
-- Remove new columns from user_checksheet_answers
ALTER TABLE user_checksheet_answers
    DROP COLUMN IF EXISTS rts_cue_id,
    DROP COLUMN IF EXISTS rts_cue_source,
    DROP COLUMN IF EXISTS rts_transcript_snippet,
    DROP COLUMN IF EXISTS rts_confidence,
    DROP COLUMN IF EXISTS rts_detected_at_ms;

-- Remove trade photos table
DROP TABLE IF EXISTS rts_trade_photos;
```

No other tables or constraints are affected by rollback.

---

## 3. New columns on `user_checksheet_answers`

All five columns are nullable. Existing rows are unaffected (all NULLs).

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `rts_cue_id` | `VARCHAR(120)` | YES | The cue atom ID from `road-to-sale-v1.yaml`. Format: `<namespace>.<event>`, e.g. `workflow.greeting_30s`, `honda.feature.honda_sensing`, `universal.feature.wireless_apple_carplay`. Matches the cue catalogue in the `voice-engine` module. |
| `rts_cue_source` | `VARCHAR(20)` | YES | Which detection pipeline produced the cue. Constrained to `'feature'` (keyword/phrase detector) or `'workflow'` (step-completion detector). |
| `rts_transcript_snippet` | `TEXT` | YES | Verbatim transcript excerpt (≤200 chars) from which the cue was detected. Stored for audit trail and QA review. Not the full session transcript. |
| `rts_confidence` | `DECIMAL(5,4)` | YES | STT confidence score in the range 0.0000–1.0000. Scores below the configured threshold are not auto-completed; they surface as suggestions only. NULL when the answer was tapped manually. |
| `rts_detected_at_ms` | `BIGINT` | YES | Unix epoch milliseconds at which the cue was detected by the voice engine. NULL for manually tapped answers. |

### Migration SQL

```sql
-- V1.34__road_to_sale_extension.sql (section 1 of 2)

ALTER TABLE user_checksheet_answers
    ADD COLUMN IF NOT EXISTS rts_cue_id            VARCHAR(120),
    ADD COLUMN IF NOT EXISTS rts_cue_source         VARCHAR(20)
        CHECK (rts_cue_source IN ('feature', 'workflow')),
    ADD COLUMN IF NOT EXISTS rts_transcript_snippet TEXT,
    ADD COLUMN IF NOT EXISTS rts_confidence         DECIMAL(5,4)
        CHECK (rts_confidence IS NULL OR (rts_confidence >= 0 AND rts_confidence <= 1)),
    ADD COLUMN IF NOT EXISTS rts_detected_at_ms     BIGINT;
```

### Semantics example

A voice-auto-completed answer carries the full evidence chain:

```
rts_cue_id            = 'workflow.greeting_30s'
rts_cue_source        = 'workflow'
rts_transcript_snippet = 'Hi, welcome in — I''m Alex. Can I get you a coffee?'
rts_confidence        = 0.9400
rts_detected_at_ms    = 1748124000000
judgement             = 1    -- OK
```

A manually tapped answer (rep confirmed after voice missed):

```
rts_cue_id            = NULL
rts_cue_source        = NULL
rts_transcript_snippet = NULL
rts_confidence        = NULL
rts_detected_at_ms    = NULL
judgement             = 1
```

---

## 4. `rts_trade_photos` table

Stores trade-in vehicle evidence photos captured by the Road to Sale app
during the Trade Appraisal step. Each row is one camera slot for one session.

### DDL

```sql
-- V1.34__road_to_sale_extension.sql (section 2 of 2)

CREATE TABLE IF NOT EXISTS rts_trade_photos (
    id                    BIGSERIAL     PRIMARY KEY,
    user_checksheet_id    BIGINT        NOT NULL
        REFERENCES user_checksheet_answers(userChecksheetId)
        ON DELETE CASCADE,
    slot                  VARCHAR(20)   NOT NULL
        CHECK (slot IN (
            'front_left', 'front_right',
            'rear_left',  'rear_right',
            'interior',   'odometer',   'vin'
        )),
    photo_url             TEXT,
    thumbnail_url         TEXT,
    captured_at           TIMESTAMP,
    uploaded_at           TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_rts_trade_photos_session_slot
        UNIQUE (user_checksheet_id, slot)
);
```

### Column reference

| Column | Type | Description |
|--------|------|-------------|
| `id` | `BIGSERIAL` | Auto-incrementing primary key. |
| `user_checksheet_id` | `BIGINT` | FK to the session (`inspections.id` post-V1.28, surfaced as `userChecksheetId` in DTOs). `ON DELETE CASCADE` — photos are removed when the session is deleted. |
| `slot` | `VARCHAR(20)` | Camera angle / subject. One of 7 constrained values (see below). |
| `photo_url` | `TEXT` | Full-resolution photo URL. May be a presigned S3 URL (regenerate on read; default TTL 1 hour) or a local path in dev. |
| `thumbnail_url` | `TEXT` | Compressed thumbnail URL. Same lifetime policy as `photo_url`. |
| `captured_at` | `TIMESTAMP` | When the rep took the photo on-device (device clock). NULL if not provided by client. |
| `uploaded_at` | `TIMESTAMP` | When the server received and stored the photo. Set to `NOW()` by default. |

### Slot definitions

| Slot | What to photograph |
|---|---|
| `front_left` | Front-left 3/4 exterior angle |
| `front_right` | Front-right 3/4 exterior angle |
| `rear_left` | Rear-left 3/4 exterior angle |
| `rear_right` | Rear-right 3/4 exterior angle |
| `interior` | Dashboard / driver seat |
| `odometer` | Odometer reading |
| `vin` | VIN plate or sticker |

The `UNIQUE (user_checksheet_id, slot)` constraint enforces one photo per angle
per session. To replace a photo, UPDATE the existing row's `photo_url` and
`thumbnail_url`; do not insert a second row.

---

## 5. Honda tenant seed

### File: `V200.003__seed_honda_rts_checksheet.sql`

Applied under the `V200.*` namespace (tenant seeds, separate from core
migrations). Creates the complete Honda Road to Sale data set in a fresh
SmartComply database.

#### What it creates

**Checksheet (NADA template)**

```
checksheets
  id=2001, code='RTS_HONDA_V1', name='Road to the Sale – Honda US',
  status='APPROVED'
```

**10 NADA process steps** (`chks_headers`, orderNo 1–10):

| orderNo | Step name |
|---|---|
| 1 | Greet / Hospitality |
| 2 | Self-Introduction |
| 3 | Hospitality Offer |
| 4 | Needs Discovery — Lifestyle |
| 5 | Needs Discovery — Budget |
| 6 | Needs Discovery — Requirements |
| 7 | Vehicle Recommendation |
| 8 | Front-Line Readiness |
| 9 | Exterior Walkaround |
| 10 | Interior Walkaround / Features |

(Steps 11–16 covering Test Drive, Trade Appraisal, Pencil, F&I, and Close
continue the sequence; total step count follows the NADA Road to the Sale
structure.)

**16 audit questions** (`chks_questions`):
All 16 questions have `questionResultType = 'SUBJECTIVE_CONDITION'`.
Questions map 1:1 to cue bindings in `road-to-sale-v1.yaml`.

**32 result options** (`chks_question_result_options`):
Two options per question — OK (orderNo=1) and Not OK (orderNo=2) — for all 16
questions = 32 rows total.

**Walk-in campaign**

```
audits
  id=1, name='Road to Sale – Walk-In',
  checksheetId=2001, status='ACTIVE',
  startDate=<current year Jan 1>, endDate=<current year Dec 31>
```

**Bootstrap inspection** (assignment)

```
inspections
  id=1, kind='AUDIT', auditId=1,
  auditeeLocationId=1,   -- Honda Demo Showroom
  status='ASSIGNED'
```

The app calls `POST /api/audit/addAuditAssignments` to create a new
`inspections` row for each walk-in customer. The bootstrap row (id=1) exists
so the app can be demoed without first running an assignment-creation step.

---

## 6. Upstream proposal notes

The following changes would be required to merge this extension back into
SmartComply core (i.e., to make Road to Sale features available to all tenants,
not just Honda):

### (a) Schema — add `rts_*` columns to the official migration sequence

Promote `V1.34__road_to_sale_extension.sql` as an official Flyway migration.
The 5 columns and their constraints are already defined in this doc. The
CHECK constraint on `rts_cue_source` would need to be either broadened
(to allow future source types) or left open as a `VARCHAR` with application-
level validation.

### (b) Add a `TradePhotosController` and service

A new `TradePhotosController` in `com.checkSheet.controller` with:
- `POST /api/rts/tradePhoto/upload` — multipart upload, stored to S3/local
- `GET /api/rts/tradePhoto?userChecksheetId={id}` — list all slots

Corresponding `RtsTradePhotoService` and `RtsTradePhotoRepository` following
the existing service-interface + impl pattern.

### (c) Expose trade-photos endpoints in the API contract

Add `TradePhotoDTO` and the two endpoints to `docs/api.md` under a new
"Road to Sale" section. Update the OpenAPI / Swagger annotations if present.

### (d) Add a campaign type for walk-in sessions

The current `audits` model supports one campaign per OEM with a fixed date
range. Walk-in sessions require an always-open, high-throughput campaign
pattern. Upstream should add either:
- An `AuditType` enum (`STANDARD` | `WALK_IN`) with `WALK_IN` campaigns
  auto-renewing their end date, or
- A separate `WalkInCampaign` entity if the semantics diverge enough.

### (e) Add `SUBJECTIVE_CONDITION` to `QuestionResultType` enum (if absent)

All Road to Sale questions use `SUBJECTIVE_CONDITION` (OK / Not OK pairs).
Confirm this value is present in `ChecksheetStatusType` or the equivalent
enum. If not, add it before promoting the Honda checksheet seed.

---

## 7. Migration history

| Version | What changed |
|---|---|
| V1.34 | Initial `rts_cue_id`, `rts_cue_source`, `rts_transcript_snippet`, `rts_confidence`, `rts_detected_at_ms` columns on `user_checksheet_answers`; `rts_trade_photos` table. |
| V200.003 | Honda tenant seed — checksheet id=2001 (`RTS_HONDA_V1`), 10 NADA steps, 16 questions, 32 result options, walk-in campaign id=1, bootstrap inspection id=1. |
