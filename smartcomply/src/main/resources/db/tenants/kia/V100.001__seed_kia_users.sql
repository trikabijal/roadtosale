-- ============================================================================
-- Tenant data migration — Kia: users, departments, role assignments
-- ----------------------------------------------------------------------------
-- Loaded by Flyway only when tenant.id=kia (see spring.flyway.locations in
-- application.properties). Other tenants never see this file.
--
-- All INSERTs are idempotent (ON CONFLICT … DO NOTHING) so re-running on
-- an existing UAT or prod that already has Kia data is a no-op — Flyway
-- still records the migration in flyway_schema_history.
--
-- Source: scripts/seed-kia-uat-users.sql (replaced — Flyway-driven now)
-- ============================================================================

-- ============================================================================
-- Kia Setup - Seed UAT users for the Kia Sales department
-- ----------------------------------------------------------------------------
-- Purpose : Recreate the user / role / department setup we used during local
--           AuditPro testing, on the UAT (or any) smartcomply DB. Reusable —
--           every statement is idempotent, re-run any time the DB is wiped.
--
-- Source  : Kia Setup.xlsx, rows 2-11
--
-- Prereqs : - Schema migrated through V1.22 (departments seeded with master
--             rows: 1=Channel Development, 2=Sales, 3=Service).
--           - Active roles seeded by Flyway:
--               1=SUPER_ADMIN, 2=DEPT_ADMIN, 3=SUBDEPT_ADMIN, 9=OPERATOR.
--           - pgcrypto extension (auto-created below).
--
-- Variables (override with `-v` on the psql command line):
--   temp_pwd : shared temporary password (default Welcome@123)
--
-- Example:
--   psql -h 172.31.0.157 -U dilipv -d smartcomply \
--        -v temp_pwd='ChangeMe!2026' \
--        -f scripts/seed-kia-uat-users.sql
-- ============================================================================



BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Sections (sub-departments under Sales master, dept_id = 2 from V1.22).
-- Departments has UNIQUE (name, department_id), so insert via INSERT ... ON
-- CONFLICT on the natural key. If a section already exists under Sales (any
-- id), we leave it alone — the role-departments insert below looks up by
-- name to find the actual id.
-- ---------------------------------------------------------------------------
INSERT INTO departments (name, department_id, created_at, created_by)
SELECT v.name, 2, CURRENT_TIMESTAMP, 1
FROM (VALUES
  ('Sales Process Adherence'),
  ('Manpower Training and Infrastructure')
) AS v(name)
WHERE NOT EXISTS (
  SELECT 1 FROM departments d
  WHERE d.name = v.name AND d.department_id = 2
);

-- ---------------------------------------------------------------------------
-- 2. Users — ids 10..19 reserved for Kia seed; ON CONFLICT (username) DO
-- NOTHING so existing Kia accounts created via UI (currently at ids 156+)
-- aren't duplicated. Password hash is the standard team test password,
-- copied verbatim from existing accounts (sachin/suresh/cp01 share it).
-- ---------------------------------------------------------------------------
-- users has UNIQUE constraints on BOTH username AND email — ON CONFLICT
-- only handles one. Use NOT EXISTS to skip if either column already exists.
INSERT INTO users (id, username, email, first_name, last_name, mobile, password, status, fail_login_count, created_at, created_by)
SELECT v.id, v.username, v.email, v.first_name, v.last_name, v.mobile, v.password, v.status, v.fail_login_count, CURRENT_TIMESTAMP, 1
FROM (VALUES
  (10, 'kia_sales_dept_head',                'kia_sales_dept_head@gmail.com',                'Sales',                    'Head',     '1211111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (11, 'kia_sales_section_head',             'kia_sales_section_head@gmail.com',             'Sales Section',            'Head',     '1111111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (12, 'kia_sales_audit_preparer',           'kia_sales_audit_preparer@gmaill.com',          'Audit Preparer',           'Sales',    '1111111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (13, 'kia_sales_audit_template_validator', 'kia_sales_audit_template_validator@gmail.com', 'Audit Template Validator', 'Sales',    '1111111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (14, 'kia_sales_audit_template_approver',  'kia_sales_audit_template_approver@gmail.com',  'Audit Template Approver',  'Sales',    '1111111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (15, 'kia_sales_auditor',                  'kia_sales_auditor@gmail.com',                  'Auditor',                  'Sales',    '1111111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (16, 'kia_sales_audit_data_validator',     'kia_sales_audit_data_validator@gmail.com',     'Audit Data Validator',     'Sales',    '1111111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (17, 'kia_sales_audit_data_approver',      'kia_sales_audit_data_approver@gmail.com',      'Audit Data Approver',      'Sales',    '1111111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (18, 'kia_simple_audit_creator',           'kia_simple_audit_creator@gmail.com',           'Simple Audit Creator',     'Sales',    '1111111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0),
  (19, 'kia_simple_auditor',                 'KIA_SIMPLE_AUDITOR@gmail.com',                 'Auditor',                  'Sales',    '1111111111', '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK', 'A', 0)
) AS v(id, username, email, first_name, last_name, mobile, password, status, fail_login_count)
WHERE NOT EXISTS (
  SELECT 1 FROM users u WHERE u.username = v.username OR LOWER(u.email) = LOWER(v.email)
);

-- ---------------------------------------------------------------------------
-- 3. user_role_departments
-- ---------------------------------------------------------------------------
-- Workflow roles (CHK_SHT_*) were removed in V1.17/V1.21 and remapped:
--   Preparer / Validator / Approver       → SUBDEPT_ADMIN (3)
--   Data Validator / Data Approver        → DEPT_ADMIN    (2)
-- Section-level users land at id=100 (Sales Process Adherence).
-- Department-level users land at id=2   (Sales master).
-- ---------------------------------------------------------------------------
-- Look up users by username AND departments by name. Works regardless of
-- whether kia accounts were freshly inserted (ids 10..19) or pre-existed
-- (ids 156+), and regardless of what id the SPA section ended up at.
WITH dept_lookup AS (
  SELECT
    (SELECT id FROM departments WHERE name='Sales' AND department_id IS NULL) AS sales_id,
    (SELECT id FROM departments WHERE name='Sales Process Adherence'
       AND department_id = (SELECT id FROM departments WHERE name='Sales' AND department_id IS NULL)) AS spa_id
)
INSERT INTO user_role_departments (user_id, role_id, department_id, created_at, created_by)
SELECT u.id, m.role_id,
       CASE m.dept_key WHEN 'SALES' THEN dl.sales_id WHEN 'SPA' THEN dl.spa_id END,
       CURRENT_TIMESTAMP, 1
FROM users u
CROSS JOIN dept_lookup dl
JOIN (VALUES
  ('kia_sales_dept_head',                 2, 'SALES'),  -- Dept Head    → DEPT_ADMIN    @ Sales
  ('kia_sales_section_head',              3, 'SPA'),    -- Section Head → SUBDEPT_ADMIN @ SPA
  ('kia_sales_audit_preparer',            3, 'SPA'),    -- Preparer     → SUBDEPT_ADMIN @ SPA
  ('kia_sales_audit_template_validator',  3, 'SPA'),    -- Validator    → SUBDEPT_ADMIN @ SPA
  ('kia_sales_audit_template_approver',   3, 'SPA'),    -- Approver     → SUBDEPT_ADMIN @ SPA
  ('kia_sales_auditor',                   9, 'SPA'),    -- Operator     → OPERATOR      @ SPA
  ('kia_sales_audit_data_validator',      2, 'SALES'),  -- Data Valid   → DEPT_ADMIN    @ Sales
  ('kia_sales_audit_data_approver',       2, 'SALES'),  -- Data Approve → DEPT_ADMIN    @ Sales
  ('kia_simple_audit_creator',            3, 'SPA'),    -- Simple Creator (collapsed roles)
  ('kia_simple_auditor',                  9, 'SPA'),    -- Simple Auditor (operator part)
  ('kia_simple_auditor',                  2, 'SALES')   -- Simple Auditor (data role part)
) AS m(username, role_id, dept_key) ON u.username = m.username
ON CONFLICT (user_id, role_id, department_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. Reset sequences past the seeded IDs
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
JOIN roles r                   ON r.id = urd.role_id
LEFT JOIN departments d        ON d.id = urd.department_id
WHERE u.username LIKE 'kia_%'
ORDER BY u.id, r.role_code;
