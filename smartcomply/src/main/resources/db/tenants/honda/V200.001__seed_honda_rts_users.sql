-- ============================================================================
-- Tenant data migration — Honda Road to Sale: users + role assignments
-- ----------------------------------------------------------------------------
-- Loaded by Flyway only when tenant.id=honda (see spring.flyway.locations in
-- application-tenant-data.properties). Other tenants never see this file.
--
-- All INSERTs are idempotent (ON CONFLICT / NOT EXISTS guards) so re-running
-- on an existing DB is a no-op — Flyway still records the migration.
--
-- User IDs 20..29 are reserved for the Honda seed block. If those slots are
-- already taken by a prior manual insert, the NOT EXISTS guard skips the row
-- and the user_role_departments CTE looks up by username instead.
--
-- Password hash: bcrypt of 'Welcome@123' (same shared test hash as Kia seed).
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Honda department tree
--    Road to Sale lives under a "Road to Sale" section within the Sales
--    master department (department_id = 2, seeded by V1.22).
-- ---------------------------------------------------------------------------
INSERT INTO departments (name, department_id, created_at, created_by)
SELECT v.name, 2, CURRENT_TIMESTAMP, 1
FROM (VALUES
  ('Road to Sale')
) AS v(name)
WHERE NOT EXISTS (
  SELECT 1 FROM departments d
  WHERE d.name = v.name AND d.department_id = 2
);

-- ---------------------------------------------------------------------------
-- 2. Users
--    IDs 20-21 reserved for Honda seed.
--    ON CONFLICT guard is on username; the email uniqueness is handled via
--    the NOT EXISTS sub-select (same pattern as Kia seed).
-- ---------------------------------------------------------------------------
INSERT INTO users (
    id, username, email, first_name, last_name,
    mobile, password, status, fail_login_count,
    created_at, created_by
)
SELECT v.id, v.username, v.email,
       v.first_name, v.last_name,
       v.mobile, v.password, v.status, v.fail_login_count,
       CURRENT_TIMESTAMP, 1
FROM (VALUES
  -- id  username         email                          first_name   last_name  mobile         bcrypt(Welcome@123)                                                   status  fail
  (20, 'honda_admin',  'honda_admin@roadtosale.local',  'Honda',     'Admin',   '2000000001', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (21, 'honda_rep1',   'honda_rep1@roadtosale.local',   'Demo',      'Rep',     '2000000002', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0)
) AS v(id, username, email, first_name, last_name, mobile, password, status, fail_login_count)
WHERE NOT EXISTS (
  SELECT 1 FROM users u
  WHERE u.username = v.username
     OR LOWER(u.email) = LOWER(v.email)
);

-- ---------------------------------------------------------------------------
-- 3. Role assignments
--    honda_admin  → DEPT_ADMIN   (role_id=2) @ Road to Sale section
--    honda_rep1   → OPERATOR     (role_id=9) @ Road to Sale section
--
--    Role IDs match the seeded master roles:
--      1 = SUPER_ADMIN, 2 = DEPT_ADMIN, 3 = SUBDEPT_ADMIN, 9 = OPERATOR
-- ---------------------------------------------------------------------------
WITH dept_lookup AS (
  SELECT
    (SELECT id FROM departments
      WHERE name = 'Road to Sale'
        AND department_id = (
              SELECT id FROM departments
              WHERE name = 'Sales' AND department_id IS NULL
            )
    ) AS rts_id
)
INSERT INTO user_role_departments (user_id, role_id, department_id, created_at, created_by)
SELECT u.id, m.role_id, dl.rts_id, CURRENT_TIMESTAMP, 1
FROM users u
CROSS JOIN dept_lookup dl
JOIN (VALUES
  ('honda_admin', 2),  -- Dept Admin  → manages the RTS section
  ('honda_rep1',  9)   -- Operator    → field sales rep
) AS m(username, role_id) ON u.username = m.username
ON CONFLICT (user_id, role_id, department_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. Advance sequences past the seeded IDs
-- ---------------------------------------------------------------------------
SELECT setval('users_id_seq',       GREATEST(100, (SELECT MAX(id) FROM users) + 1),       false);
SELECT setval('departments_id_seq', GREATEST(200, (SELECT MAX(id) FROM departments) + 1), false);

COMMIT;

-- ---------------------------------------------------------------------------
-- Verification
-- ---------------------------------------------------------------------------
SELECT u.id, u.username, r.role_code, d.name AS department
FROM users u
JOIN user_role_departments urd ON urd.user_id = u.id
JOIN roles r                   ON r.id        = urd.role_id
LEFT JOIN departments d        ON d.id        = urd.department_id
WHERE u.username IN ('honda_admin', 'honda_rep1')
ORDER BY u.id, r.role_code;
