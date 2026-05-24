# Road to Sale Extensions

## Overview

V1.34 adds a Road to Sale (RTS) extension layer to SmartComply's
`user_checksheet_answers` table and introduces a new `rts_trade_photos` table.
These extensions wire the AuditPro/SmartComply audit runtime to the
Road to Sale voice engine's cue detection output.

All extension columns are:
- **Nullable** — existing answers with no voice data are unaffected.
- **Prefixed `rts_`** — immediately distinguishable from core SmartComply columns.
- **Additive only** — no existing column is modified or dropped.

---

## V1.34 extension columns on `user_checksheet_answers`

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `rts_cue_id` | `VARCHAR(255)` | YES | Voice-engine cue atom ID. Format: `<source>.<event>`, e.g. `workflow.hospitality_offer` or `feature.test_drive_offer`. Matches the cue catalogue in the `voice-engine` module. |
| `rts_cue_source` | `VARCHAR(50)` | YES | Which detection pipeline produced the cue. One of `'feature'` (keyword/phrase detector) or `'workflow'` (step-completion detector). |
| `rts_transcript_snippet` | `TEXT` | YES | The short verbatim transcript excerpt (≤200 chars) from which the cue was detected. Stored for audit trail and QA review. Not the full call transcript. |
| `rts_cue_confidence` | `NUMERIC(4,3)` | YES | Engine confidence score in the range 0.000–1.000. Scores below the configured threshold are not auto-completed; they surface as suggestions only. |
| `rts_voice_auto_completed` | `BOOLEAN` | YES (default FALSE) | `TRUE` = the answer was set automatically by the voice engine. `FALSE` = the rep tapped manually, or no voice data exists for this answer. |

### Semantics

A voice-auto-completed answer carries the full evidence chain:
```
rts_cue_id            = "workflow.greeting_30s"
rts_cue_source        = "workflow"
rts_transcript_snippet = "Hi, welcome in, I'm Alex — can I get you a coffee?"
rts_cue_confidence    = 0.94
rts_voice_auto_completed = true
judgement             = 1    -- OK (the integer on user_checksheet_answers)
```

A manually overridden answer (rep tapped after voice missed):
```
rts_cue_id            = NULL
rts_cue_source        = NULL
rts_transcript_snippet = NULL
rts_cue_confidence    = NULL
rts_voice_auto_completed = false   -- explicit manual
judgement             = 1
```

---

## `rts_trade_photos` table

Stores trade-in vehicle evidence photos captured by the road-to-sale mobile
app during the Trade Appraisal step. Each row is one camera slot.

```sql
CREATE TABLE rts_trade_photos (
    id              BIGSERIAL PRIMARY KEY,
    inspection_id   BIGINT       NOT NULL REFERENCES inspections(id),
    slot            VARCHAR(50)  NOT NULL,
    file_key        VARCHAR(500),
    file_url        TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_rts_trade_photos_inspection_slot UNIQUE (inspection_id, slot),
    CONSTRAINT chk_rts_trade_photos_slot CHECK (
        slot IN ('front_left','front_right','rear_left','rear_right',
                 'interior','odometer','vin')
    )
);
```

### Column reference

| Column | Type | Description |
|--------|------|-------------|
| `inspection_id` | `BIGINT` | FK → `inspections.id`. One inspection = one trade-in candidate. |
| `slot` | `VARCHAR(50)` | The camera angle / subject. Constrained to 7 values (see CHECK). |
| `file_key` | `VARCHAR(500)` | The S3 object key (or local path in dev). Stable; does not expire. |
| `file_url` | `TEXT` | Short-lived presigned URL. **Regenerate on every read** — do not cache beyond the S3 TTL (default 1 hour). |
| `created_at` | `TIMESTAMP` | Row creation time. |

### Slot definitions

| Slot | What to photograph |
|------|--------------------|
| `front_left` | Front-left 3/4 angle |
| `front_right` | Front-right 3/4 angle |
| `rear_left` | Rear-left 3/4 angle |
| `rear_right` | Rear-right 3/4 angle |
| `interior` | Dashboard / driver seat |
| `odometer` | Odometer reading |
| `vin` | VIN plate or sticker |

The UNIQUE constraint on `(inspection_id, slot)` enforces one photo per angle
per inspection. To replace a photo, UPDATE the existing row's `file_key` and
`file_url`; do not insert a second row.

---

## How the extensions interact with the core SmartComply BI

The BI rollup CTE (`audit_signal` in `AuditServiceImpl`) reads
`user_checksheet_answers.judgement` (the integer, 1=OK/2=NOT OK) for
`pct_ok` calculation. The `rts_*` columns are transparent to the core BI —
they add no joins and do not affect any existing query path.

A future Road-to-Sale BI panel can filter:
```sql
-- Voice-auto-completed OK rate vs manual OK rate
SELECT
    rts_voice_auto_completed,
    COUNT(*) FILTER (WHERE judgement = 1) AS ok_count,
    COUNT(*)                              AS total
FROM user_checksheet_answers
WHERE inspection_id IN (
    SELECT id FROM inspections WHERE audit_id = 2001
)
GROUP BY rts_voice_auto_completed;
```

---

## Walk-in audit campaign pattern

The Honda seed (V200.003) establishes a standing "walk-in" audit pattern:

1. **One `audits` row** (`Road to Sale – Walk-In`) with a year-long open window.
2. **One inspection per walk-in customer** — created by the rep when the
   customer enters, linked to the Honda Demo Showroom location.
3. **Answers populated in real time** — the voice engine fires
   `POST /api/userChecksheet/createOrUpdateUserChksAns` as cues are detected,
   with `rts_*` fields populated. The rep reviews and taps to confirm or
   override before submission.

This creates a continuous stream of completed inspections from a single
standing audit campaign, which the BI dashboards roll up as usual.

For a multi-location deployment: call `POST /api/audit/addAuditAssignments`
to attach additional `auditee_location_id` values to the same audit_id.
Each location gets its own inspection row; answers and trade photos are
scoped per inspection.

---

## Proposal-back to upstream SmartComply

### Trigger condition

After the Road to Sale pilot completes a **3-month field run with ≥50
submitted inspections carrying `rts_voice_auto_completed = true`**, evaluate:

1. **Accuracy** — voice-auto-completed answers match manual audit scores at ≥85%.
2. **Coverage** — voice detection fires on ≥60% of NADA checkpoints per inspection.
3. **No regressions** — all existing SmartComply BI queries return identical
   results with the `rts_*` columns present (null columns are transparent).

If all three pass, open a PR against the upstream SmartComply repo.

### What to contribute

- `V1.34__road_to_sale_extension.sql` — the migration itself.
- The `rts_trade_photos` table DDL.
- This doc, updated with pilot metrics.
- The Honda tenant seeds as a reference implementation for OEM onboarding.

### What NOT to contribute yet

- The voice engine integration code (Road to Sale proprietary).
- The Honda-specific checksheet seed (tenant data, not a core contribution).
- The `application-honda.properties` (tenant runtime config, not generic).

---

## Migration history

| Version | What changed |
|---------|-------------|
| V1.34 | Initial `rts_*` columns on `user_checksheet_answers`; `rts_trade_photos` table. |
