# Kia demo data — UAT seed runbook

**Audience:** anyone on the team populating the Kia demo on a UAT environment
that's already deployed off the `development` branch of all three repos
(`smartcomply`, `smartcomply-angular`, `auditpro-mobile-app`).

**Time to run:** ~25 minutes if everything works first try. The 200-audit seed
makes real Gemini Flash AI calls per photo, which is the slow part.

**Where to run from:** a developer/operator workstation, NOT inside the app
container. You need a local clone of the `smartcomply` repo on the
`development` branch — the seed scripts live in `dev/`. All `python3 dev/...`
and `psql ... -f dev/...` commands below assume you're at the repo root.

```bash
git clone https://gitlab.tiez.net/Tiez/smartcomply.git
cd smartcomply
git checkout development
```

**Outcome:** A working Kia demo with:
- 1 active audit ("FY26 H1 Kia Showroom Audit") covering 200 dealership locations
- 175 user_checksheets (150 APPROVED, 25 IN_PROGRESS, 25 not-started)
- Photos + AI assessments on each answered question
- Multi-location dealers (so dealer → location drill has data)
- ~30 demo auditor users for the mobile app
- Login users for Head of Sales, Validator, Approver, Dealer Principal

---

## 0 · Prerequisites checklist

Confirm all of these BEFORE you start running scripts. Stopping mid-flow is
fine (the scripts are idempotent), but it's faster to surface blockers up
front.

- [ ] UAT backend is up and reachable. Get a 200 from
      `https://<your-uat-host>/actuator/health`.
- [ ] You know the **UAT database connection details** (host, port, name,
      user, password). The seed scripts talk to the DB directly for the
      heavy lifting and to the API for the orchestration.
- [ ] You have a **Gemini API key** (`GEMINI_KEY`). The seed runs ~1700 AI
      calls; total spend ≈ USD 0.50 at current Flash pricing.
- [ ] The smartcomply repo is checked out on a machine that can:
      reach the UAT DB (network + creds) AND the UAT API (HTTPS).
      The seed scripts run from a developer/operator workstation, not from
      inside the app container.
- [ ] **Python 3.10+** with the packages `psycopg2-binary` and `requests`
      installed. One-liner: `pip3 install psycopg2-binary requests`.
- [ ] **psql** client tool installed (used in step 2).
- [ ] **Checkpoint photos available locally.** The seed uploads real photos
      from a directory of ~80 sample images. These are NOT in the smartcomply
      repo. Three options (any one is fine):
      - **(a) Clone the `auditpro` repo as a sibling of smartcomply.** The
        seed automatically falls back to
        `../auditpro/dev/test-photos/checkpoint-photos/`:
        ```bash
        cd ..   # parent of smartcomply
        git clone https://gitlab.tiez.net/Tiez/auditpro.git
        ```
      - **(b) Copy the photos into smartcomply.** From a machine that has
        them, copy to `smartcomply/dev/test-photos/checkpoint-photos/`.
      - **(c) Point the seed at any directory of JPGs** via the `PHOTO_DIR`
        env var in step 3. The directory just needs JPGs; any naming works,
        the seed picks randomly.

      Quick check that this is set up correctly:
      ```bash
      ls dev/test-photos/checkpoint-photos 2>/dev/null \
        || ls ../auditpro/dev/test-photos/checkpoint-photos 2>/dev/null \
        || echo "NO PHOTOS FOUND — seed will fail"
      ```
- [ ] You confirmed the UAT environment is **safe to seed into**. The seed
      will:
      - Create ~30 new users (`KIA_DEMO_AUDITOR_001..030`)
      - Push checksheet 15 ("Kia Dealership Audit 5") to APPROVED
      - Create one audit campaign ("FY26 H1 Kia Showroom Audit")
      - Attach 200 audit assignments
      - Create 175 user_checksheets with backdated `started_at`

      If UAT already has the Kia seed users (`KIA_SALES_AUDIT_PREPARER`,
      `KIA_SALES_DEPT_HEAD`, etc.) the scripts skip those steps. If you
      need a clean slate first, see Appendix A.

---

## 1 · Confirm the tenant overlay is loaded

The Kia seed users + dealerships come from Flyway tenant migrations in
`src/main/resources/db/tenants/kia/`. They only run when the backend boots
with the `tenant-data` Spring profile and `TENANT_ID=kia`.

UAT should already be booted this way (the deploy uses `SPRING_PROFILES_ACTIVE=uat`
which auto-includes `tenant-data`). Confirm by running this against the UAT DB:

```bash
psql "host=$UAT_DB_HOST dbname=$UAT_DB_NAME user=$UAT_DB_USER password=$UAT_DB_PASS" \
  -c "SELECT version, description FROM flyway_schema_history
       WHERE version LIKE '100.%' ORDER BY version;"
```

You should see at least:

```
 version |          description
---------+--------------------------------
 100.001 | seed kia users
 100.002 | seed kia dealerships
```

If these aren't there, the deploy profile isn't loading the tenant overlay.
**STOP** and fix that before going further — the seed will fail without these
master rows.

---

## 2 · Seed the 30 demo operators (REQUIRED — step 3 will fail without this)

`tenants/kia/bin/seed-test-data.sql` creates the `KIA_DEMO_AUDITOR_001..030` users that
the python seed in step 3 expects to find. The python script intentionally
won't fall back to creating them itself; if they're missing it will exit
with `Only N demo operators found, expected 30. Run: psql ...`.

```bash
export UAT_DB_HOST=<host>
export UAT_DB_NAME=<db>
export UAT_DB_USER=<user>
export PGPASSWORD=<password>

psql "host=$UAT_DB_HOST dbname=$UAT_DB_NAME user=$UAT_DB_USER" \
  -f tenants/kia/bin/seed-test-data.sql
```

Idempotent — re-runs are no-ops (every INSERT uses `ON CONFLICT DO NOTHING`).

Sanity check after this step:

```bash
psql "host=$UAT_DB_HOST dbname=$UAT_DB_NAME user=$UAT_DB_USER" \
  -c "SELECT count(*) FROM users WHERE username LIKE 'KIA_DEMO_AUDITOR_%';"
```

Expected: `30`.

---

## 3 · Run the main demo seed

This is the long one (~15-20 min). Set env vars first:

```bash
# DB — required
export DB_HOST=<uat-db-host>
export DB_NAME=<uat-db-name>
export DB_USER=<uat-db-user>
export DB_PASS=<uat-db-password>

# API — required
export BASE_URL=https://<your-uat-host>     # NO trailing slash, NO /api

# AI — required (the seed uploads photos and waits for AI assessments)
export GEMINI_KEY=<your-key>

# Optional overrides (defaults shown)
# export CHECKSHEET_ID=15
# export AUDIT_NAME="FY26 H1 Kia Showroom Audit"
# export TOTAL_LOCATIONS=200
# export APPROVED_COUNT=150
# export IN_PROGRESS_COUNT=25
```

### 3a · Smoke first (2 audits, ~30 sec)

ALWAYS run smoke before the full seed. It logs in, creates one APPROVED +
one IN_PROGRESS, and exits. If smoke fails, the full run will too.

```bash
python3 tenants/kia/bin/seed-kia-demo.py --smoke
```

Watch for:
- `Logged in as KIA_SALES_AUDIT_PREPARER` — backend reachable, user exists, password correct
- `Checksheet 15 → APPROVED` — template walk worked
- `Created 1 APPROVED user_checksheet at <location>` — answer + photo + AI path works
- `Created 1 IN_PROGRESS user_checksheet at <location>` — partial-fill path works
- Final `Smoke done` line

### 3b · Full run

```bash
python3 tenants/kia/bin/seed-kia-demo.py
```

What you'll see streaming:
- `Picked 200 locations` (deterministic via hash; same locations every run)
- `Attached 200 audit assignments`
- 175 lines like `[045/175] APPROVED auditee_loc_id=10599 …photos uploaded`
- Periodic `[verify] band split G/A/R = 124/19/4` checkpoints

The run is **resumable**. If it dies (network blip, Gemini rate limit, you
Ctrl-C-ed), just re-run the same command — already-created user_checksheets
are skipped.

### 3c · Verify shape

```bash
python3 tenants/kia/bin/seed-kia-demo.py --verify
```

This re-runs the BI-shape SQL queries directly against the DB. Expected
output looks like:

```
=== VERIFICATION (audit_id=...) ===

  Status counts (user_checksheets):
    APPROVED         150
    IN_PROGRESS       25

  audit_assignments:
    total: 200

  Band split (APPROVED audits only):
    green=124  amber=19  red=4  avg=84.0  total=147

  What's failing (top 6 categories):
    EV & Sustainability             38.1%  (...)
    Branding & Visibility           27.6%  (...)
    Customer Touchpoints            20.0%  (...)
    Customer Interaction            18.x%  (...)
    Customer Experience             ...
    Infrastructure                  ...

  Per-region failure rate:
    Northeast                       (highest)
    South                           ...
    North                           ...
    East                            ...
    Central                         ...
    West                            (lowest)
```

The exact numbers can drift a bit run-to-run because of random seeds. The
shape (band split ≈ 60/30/10, EV at the top of "what's failing", Northeast
worst region, West best) should always hold. If band split is heavily off
or EV isn't on top, see Troubleshooting.

---

## 4 · Add multi-location dealers

The deduplication migration V1.25 collapsed every dealer down to one
location. This script gives 5 dealers a second physical site (Showroom +
Service Centre split) so the dealer→location drill in the BI has something
to click into.

```bash
python3 tenants/kia/bin/seed-multi-location.py
```

Takes about 30 seconds. Adds:
- 5 new auditee_locations (one per selected dealer)
- 5 new audit_assignments at those locations
- 5 new APPROVED user_checksheets there

Idempotent (skips any dealer that already has 2+ locations).

---

## 5 · Backfill contextual auditor remarks

The seed-kia-demo run leaves answer remarks as terse "Not OK" / "OK" strings.
This script adds contextual sentences (e.g., "Display lighting flickering,
needs ballast replacement") that read more naturally in the audit-report UI.

```bash
python3 tenants/kia/bin/backfill-remarks.py
```

~1 minute. Updates ~8000 answer rows in place.

---

## 6 · Final verification

### Login as Head of Sales and open the BI dashboard

```
URL:       https://<your-uat-host>/  (Angular)
Username:  KIA_SALES_DEPT_HEAD
Password:  12345678
```

Expected on the National BI dashboard:
- **Active Audits:** 2
- **Locations in Audit:** ≥ 195 (200 − any that didn't complete)
- **Completed:** ≈ 147
- **Avg Score:** ~84%
- **Band Distribution:** ~124 G / 19 A / 4 R
- **What's Failing:** EV & Sustainability at the top, ~38%
- **Bottom Dealerships:** 4 red rows
- **Audit Recency / Never Audited:** ~445 of 635

### Login on mobile as an operator

```
Username:  KIA_DEMO_AUDITOR_001
Password:  12345678
deviceType: APP
```

Expected:
- "My Audits" shows 1–2 assignments (whichever sites this operator was
  round-robin-assigned). At least one in IN_PROGRESS.
- Tapping into one loads the checksheet content tree (2 zones, ~10 categories).

### All five demo personas

| Login | Password | What they should see |
|---|---|---|
| `KIA_SALES_DEPT_HEAD` | `12345678` | Lands on National BI; can drill region → dealer → location; can view any audit-report |
| `KIA_SALES_AUDIT_PREPARER` | `12345678` | Same as above; also has "Create Template" on the checksheet-management page |
| `KIA_SALES_AUDIT_TEMPLATE_APPROVER` | `12345678` | Same as above |
| `KIA_SALES_AUDIT_DATA_VALIDATOR` | `12345678` | Lands on BI; validator inbox empty in the demo (everything's already past validation) |
| `KIA_SALES_AUDIT_DATA_APPROVER` | `12345678` | Same as validator |
| `KIA_DEALER_PRINCIPAL_001` | `12345678` | "My Plans" empty in the demo (no active interventions seeded) |
| `KIA_DEMO_AUDITOR_001..030` | `12345678` | Mobile: 1–2 assignments per operator |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Login failed for KIA_SALES_AUDIT_PREPARER` | Tenant overlay didn't run | Re-check step 1. The Kia seed users come from V100.001 — if they're missing, the deploy didn't include `tenant-data` profile |
| `Checksheet 15 not found` | Tenant overlay didn't run (checksheets come from V100.002) | Same as above |
| `403 on /api/checksheet/updateChecksheetStatus` | Preparer/validator/approver users don't have the right permissions | Check `role_permissions` table; the V100.x migrations grant these |
| `Gemini rate limit` mid-run | Free-tier quota hit | Wait a minute, re-run. Seed picks up where it left off |
| `Connection refused` to `BASE_URL` | UAT backend isn't reachable, or `BASE_URL` includes `/api` (it shouldn't) | Confirm `curl $BASE_URL/actuator/health` returns 200 |
| `psycopg2.OperationalError` | DB env vars wrong, or your IP isn't whitelisted on the UAT DB | Confirm `psql` with the same vars works first |
| `BI dashboard shows zeros after seed` | The FE picked the wrong audit (e.g. a leftover empty one) | Check the audits list — only "FY26 H1 Kia Showroom Audit" should have data. Delete any stray empty audits if there are leftovers |
| `Mobile login 401 / "Invalid device type"` | Mobile app is sending `deviceType: WEB` or missing it | Confirm mobile app is on `development` branch (it sends `APP`) |
| `Multi-location seed says "no eligible dealers"` | seed-multi-location was already run; nothing to do | Skip step 4 |

---

## Appendix A · Resetting UAT to a clean state

**Only do this if explicitly asked.** Drops all operational data — keeps
roles, permissions, departments, master tables.

```bash
chmod +x scripts/recreate-db.sh
PGHOST=<uat-host> SUPERUSER=<superuser> ./scripts/recreate-db.sh
```

The script will prompt for confirmation (you have to type the DB name).
After it finishes, restart the backend so Flyway re-applies all migrations
including the tenant overlay, then run steps 2-5 above.

If you only want to wipe audit-side data while keeping users/templates:

```bash
psql "$UAT_DSN" < scripts/retenant.sql
```

---

## Appendix B · Estimated AI spend

The full seed uploads ~1700 photos and calls Gemini Flash on each one.

```
1700 calls × ~$0.0003 per call ≈ USD 0.51
```

`llm.max-spend-usd` in `application-uat.properties` should be set to at
least USD 5 to give comfortable headroom. If it's lower, the seed will
abort partway with a `BudgetExceededException`.
