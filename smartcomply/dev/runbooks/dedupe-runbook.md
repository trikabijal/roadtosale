# Dedupe runbook — V1.25 + V1.26

Two Flyway migrations clean up data shape that the original Kia seed left behind. They run automatically on app startup; this doc explains *what* they do and *when* you'd need to re-run them.

---

## V1.25 — auditee_locations dedupe

**Why.** The OEM seed import gave most dealers three rows in `auditee_locations` with **the same address** (one for sales, one for service, one for parts). The audit model treats a "location" as a physical site, so those three rows collapsed to one in the BI's mental model — but the data still had three. Dealer pages showed duplicate rows, drill links pointed at arbitrary copies, and the band-distribution counts disagreed across panels.

**What it does:**

1. Identifies every `(auditee_id, address)` group with > 1 active row → 417 groups, 944 rows total.
2. Picks the lowest-id row in each group as canonical.
3. Re-parents `audit_assignments.auditee_location_id` onto the canonical row (95 assignments).
4. Where re-parenting would collide with an existing canonical-side assignment in the same audit (15 cases), re-parents the *user_checksheets* off the duplicate-side assignment onto the canonical-side assignment, then soft-deletes the duplicate assignment.
5. Soft-deletes the 527 duplicate `auditee_locations` rows (`deleted_at = NOW()`).
6. Replaces the table-wide `UNIQUE (audit_id, auditee_location_id)` constraint on `audit_assignments` with a partial unique index that only covers active rows. The original constraint blocked the dedupe (a soft-deleted row still counted) and was wrong semantically — once a row is soft-deleted, it shouldn't occupy the unique slot.

**Net result:**

| Before | After |
|---|---|
| 1154 active locations | 627 active locations |
| 417 (auditee, address) duplicate groups | 0 |

**Re-running.** This migration is one-shot — Flyway records it and won't re-apply. If you want to re-apply (e.g. after restoring an older `pg_dump`), delete the row from `flyway_schema_history`:

```sql
DELETE FROM flyway_schema_history WHERE version = '1.25';
```

then restart the backend.

---

## V1.26 — user_checksheets dedupe per assignment

**Why.** V1.25's step 4 re-parented some user_checksheets onto canonical assignments that *already* had their own user_checksheet. Result: 12 assignments ended up with 2-3 active user_checksheets each (14 extras total). That's structurally wrong — an assignment is "this operator's audit pass at this site for this campaign", which should be exactly one filled form.

**What it does:** for each assignment with > 1 active user_checksheet, picks the most-progressed one (`APPROVED` > `VALIDATED` > `SUBMITTED` > `IN_PROGRESS`) — ties broken by most recent `submitted_at`, then highest id. Soft-deletes the rest along with their dependent rows (answers, files, matrix answers, judgement records).

**Net result:**

| Before | After |
|---|---|
| 176 active user_checksheets | 162 |
| 12 assignments with multiple active UCs | 0 |
| 150 APPROVED audits | 139 (the lower count is the truth — the rest were re-parented duplicates) |

---

## How to verify after a re-run

```sql
-- Should be 0
SELECT COUNT(*) FROM (
  SELECT auditee_id, address FROM auditee_locations WHERE deleted_at IS NULL
  GROUP BY auditee_id, address HAVING COUNT(*) > 1
) g;

-- Should be 0
SELECT COUNT(*) FROM (
  SELECT aa.id FROM audit_assignments aa
  JOIN user_checksheets uc ON uc.audit_assignment_id = aa.id AND uc.deleted_at IS NULL
  WHERE aa.deleted_at IS NULL GROUP BY aa.id HAVING COUNT(uc.id) > 1
) g;

-- BI band counts should reconcile across panels
-- (national.greenCount + amberCount + redCount == sum of per-region G+A+R == totalAudits)
```

---

## What to do if you intentionally want multi-location dealerships back

V1.25 collapsed every duplicate-address site into one. For a demo where you want a dealer to show up with > 1 location, run:

```bash
python3 tenants/kia/bin/seed-multi-location.py
```

This creates 2-3 distinct sites (different addresses, in different cities) for 5 dealerships and seeds APPROVED user_checksheets at the new sites.
