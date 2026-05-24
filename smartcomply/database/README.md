# UAT DB Baselines

Pg_dump snapshots of the UAT (SIT) database. Captured by the
`backup-uat-db` manual CI job and stored here for restore + reseed
workflows.

Format: `pg_dump -Fc -Z 9` (PostgreSQL custom format, max compression).

## Contents

| File | Captured | Notes |
|---|---|---|
| `uat-baseline-20260507-143644.dump` | 2026-05-07 14:36 IST | Pre-Kia-seed baseline. Schema includes V1.23 (regions, BI indexes, auditees contact columns). Reference data: 4 roles, 79 permissions, 234 role_permissions, 3 master departments, 4 lov_data, 26 users, 108 chks_question_results, 4K api_history rows. No Kia data. |

## Restore

```bash
# Drop + recreate db (destructive!)
PGPASSWORD=... psql -h <host> -U <super-user> -d postgres -c \
  "DROP DATABASE smartcomply; CREATE DATABASE smartcomply OWNER dilipv;"

# Restore from dump
PGPASSWORD=... pg_restore -h <host> -U dilipv -d smartcomply \
  --no-owner --no-privileges \
  database/uat-baseline-20260507-143644.dump

# Then re-run Kia seeds via GitLab pipeline manual jobs
# (seed-kia-uat-users + seed-kia-dealerships)
```

## Re-capturing

Run the `backup-uat-db` CI job from any devops pipeline (Pipelines →
pick latest → click Play on `backup-uat-db`). Download the artifact
from the job page, drop it in this folder, commit.
