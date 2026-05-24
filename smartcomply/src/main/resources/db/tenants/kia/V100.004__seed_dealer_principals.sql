-- ============================================================================
-- Tenant data migration — Kia: dealer principal seed
-- ----------------------------------------------------------------------------
-- Purpose:
--   Wire up the DEALER_PRINCIPAL persona introduced in V1.27.
--   Creates one demo Dealer Principal user (KIA_DEALER_PRINCIPAL_001) and
--   attaches them to ~10 audit_assignments on the seeded FY26 H1 audit so
--   the "My Plans" screen has data when logged in as that user.
--
-- Idempotent: re-running on a partially seeded DB is a no-op.
-- ============================================================================

BEGIN;

-- 1. Create the demo dealer-principal user. Standard dev password
--    "12345678" (same hash as KIA_DEMO_AUDITOR_001..030 from earlier seed).
INSERT INTO users (username, email, first_name, last_name, mobile, password, status, fail_login_count, created_at, created_by)
SELECT v.username, v.email, v.first_name, v.last_name, v.mobile, v.password, v.status, v.fail_login_count, CURRENT_TIMESTAMP, 1
FROM (VALUES
  ('KIA_DEALER_PRINCIPAL_001',
   'kia_dealer_principal_001@example.com',
   'Dealer',
   'Principal',
   '9990000001',
   '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK',
   'A',
   0)
) AS v(username, email, first_name, last_name, mobile, password, status, fail_login_count)
WHERE NOT EXISTS (
  SELECT 1 FROM users u
   WHERE u.username = v.username OR LOWER(u.email) = LOWER(v.email)
);

-- 2. Attach the DEALER_PRINCIPAL role @ Sales department.
WITH dept_lookup AS (
  SELECT (SELECT id FROM departments WHERE name='Sales' AND department_id IS NULL) AS sales_id
)
INSERT INTO user_role_departments (user_id, role_id, department_id, created_at, created_by)
SELECT u.id,
       (SELECT id FROM roles WHERE role_code='DEALER_PRINCIPAL'),
       dl.sales_id,
       CURRENT_TIMESTAMP, 1
  FROM users u CROSS JOIN dept_lookup dl
 WHERE u.username = 'KIA_DEALER_PRINCIPAL_001'
   AND NOT EXISTS (
     SELECT 1 FROM user_role_departments urd
      WHERE urd.user_id = u.id
        AND urd.role_id = (SELECT id FROM roles WHERE role_code='DEALER_PRINCIPAL')
   );

-- 3. Stamp this user as dealer_principal on a deterministic 10-assignment slice
--    of the FY26 H1 audit. Post-V1.28: audit_assignments was collapsed into
--    inspections (kind='AUDIT'). dealer_principal_user_id moved onto
--    inspections in the same migration. Sorting by inspections.id keeps the
--    slice stable across re-runs and tenants. Skip rows that already have a
--    dealer principal assigned so we never override hand-curated data.
UPDATE inspections ins
   SET dealer_principal_user_id = (SELECT id FROM users WHERE username = 'KIA_DEALER_PRINCIPAL_001'),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE ins.id IN (
   SELECT id FROM inspections
    WHERE kind = 'AUDIT'
      AND audit_id = (SELECT id FROM audits WHERE name = 'FY26 H1 Kia Showroom Audit' LIMIT 1)
      AND deleted_at IS NULL
      AND dealer_principal_user_id IS NULL
    ORDER BY id ASC
    LIMIT 10
 );

COMMIT;
