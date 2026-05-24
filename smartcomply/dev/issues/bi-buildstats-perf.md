# BI buildStats — materialise CTE once + add post-V1.28 indexes

## Why

`AuditServiceImpl.buildStats()` is the read path behind every BI panel
(`/stats/national`, `/stats/region/{regionId}`, `/stats/dealer/{auditeeId}`,
`/stats/location/{locationId}`). It calls `scopeCte(level)` which builds
an expensive `LATERAL` correlated subquery (per-row latest-wins across
audit-kind + intervention-kind inspections) and then re-runs that CTE
once per panel — band counts, what's-failing, table, red list, top-failing
checkpoints, recency, AI insight. Result: the same per-inspection latest-wins
join is recomputed N times per request.

Reviewer round 3 quote (V1.28 review, 2026-05-10):

> "scopeCte() is doing per-row latest-wins via LATERAL, which is fine
> at small scale but doesn't fan out well. buildStats() recomputes
> that CTE per panel — multiplicative. If this goes live on a large
> tenant, BI is where I expect pain first."

Pre-V1.28 BI ran on a much simpler row set (one UC per location, no
discriminator, no latest-wins). The shape is now structurally different
and the indexes haven't caught up.

## What

### 1. Materialise `audit_signal` once per request

Right now each panel-builder method (`buildBandCounts`, `buildWhatsFailing`,
`buildScopeTable`, `buildRedList`, `buildTopFailingCheckpoints`,
`buildRecencyForScope`) issues its own native query that includes the
full `WITH audit_signal AS (...)` prelude. Postgres re-plans and re-executes
the LATERAL each time.

Options to evaluate:
- (a) Build `audit_signal` as a session-scoped temporary table at the
  top of `buildStats()`, then have each panel `SELECT FROM audit_signal`
  by name. Cost: extra `CREATE TEMP TABLE` per request.
- (b) Wrap all panels into a single mega-query using common-table-
  expression with multiple `SELECT … FROM audit_signal` branches via
  `UNION ALL` + a discriminator column. Cost: harder to read; needs
  result-set demuxing in Java.
- (c) Materialise into a `Map<Long, Row>` on the Java side after one
  query, do the panel rollups in code. Cost: memory per request, but
  N + 1 queries become 1 query + Java reductions.

Likely (a) for clarity; benchmark before deciding.

### 2. Add post-V1.28 indexes

Run `EXPLAIN (ANALYZE, BUFFERS)` on each `audit_signal` shape with
production-sized data (e.g. UAT clone of Kia tenant). Likely-needed
indexes:

```sql
-- Used by every scope query: WHERE audit_id = ? AND deleted_at IS NULL
CREATE INDEX IF NOT EXISTS idx_inspections_audit_id_active
  ON inspections (audit_id) WHERE deleted_at IS NULL;

-- Used by audit-kind scope: AND kind = 'AUDIT' AND status = 'APPROVED'
CREATE INDEX IF NOT EXISTS idx_inspections_audit_kind_approved
  ON inspections (audit_id, kind, status) WHERE deleted_at IS NULL;

-- Used by per-location panel rollups
CREATE INDEX IF NOT EXISTS idx_inspections_location_kind_status
  ON inspections (auditee_location_id, kind, status) WHERE deleted_at IS NULL;

-- Used by latest-wins LATERAL: ORDER BY uca.user_checksheet_id DESC ...
-- (or whatever the current ordering criterion is — confirm via EXPLAIN)
CREATE INDEX IF NOT EXISTS idx_uca_uc_qresult
  ON user_checksheet_answers (user_checksheet_id, chks_question_result_id)
  WHERE deleted_at IS NULL;
```

Numbers above are "likely candidates," not commitments. Real index
choices come from the EXPLAIN output, not from guessing.

### 3. Benchmark before / after

Capture P50 / P95 latency on `/stats/national` and `/stats/dealer/{id}`
against the largest available tenant data set. Target: P95 < 500ms
on national, < 300ms on dealer.

## Acceptance

- [ ] EXPLAIN (ANALYZE) snapshots committed under `dev/perf/v128-bi-baseline.md`
      (before-state) and `v128-bi-after.md` (after-state).
- [ ] Indexes added via Flyway migration (V1.30+ depending on order).
- [ ] CTE materialisation refactor in `AuditServiceImpl.buildStats()`.
- [ ] All audit/intervention E2E tests still pass.
- [ ] BI panel screenshots from staging show identical numbers
      pre/post (correctness regression check).

## Out of scope

- Application-level caching (Redis, etc.). The right fix is a clean
  query plan, not a cache hiding a slow query.
- Schema changes beyond indexing. The collapse to one `inspections`
  table is V1.28's job; this issue is just performance hardening.
- Dashboard-level rendering changes (Angular). Backend-only.

## Source

External code review of V1.28 branch `feat/improvement-campaigns`
round 3, 2026-05-10. Filed as deferred-but-mandatory follow-up; not
blocking the V1.28 merge because correctness is intact, but expected
to be the first thing to bite under load.
