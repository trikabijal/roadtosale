-- ============================================================================
-- AuditPro - Seed fresh users (one per role)
-- ----------------------------------------------------------------------------
-- Purpose : Create 4 starter users (IDs 1..4), one per active role, with a
--           shared BCrypt-hashed temporary password. To be run AFTER
--           retenant.sql (or on a freshly-migrated empty database).
--
-- Active roles: SUPER_ADMIN (1), DEPT_ADMIN (2), SUBDEPT_ADMIN (3), OPERATOR (9)
-- Removed roles: CHK_SHT_* workflow roles were removed via V1.21 migration.
--
-- Prereqs : pgcrypto extension (auto-created below). Requires CREATE EXTENSION
--           privilege the first time. On AWS RDS the rds_superuser has it.
--
-- Variables (override with -v on the psql command line):
--   email_domain : email domain used for all 4 seed users  (default example.com)
--   temp_pwd     : shared temporary password               (default Welcome@123)
--
-- Example :
--   psql -h <host> -U <user> -d smartcomply \
--        -v email_domain=acme.com -v temp_pwd='ChangeMe!2026' \
--        -f scripts/seed-users.sql
--
-- IMPORTANT: force every seed user to change their password on first login.
-- ============================================================================

\set ON_ERROR_STOP on

-- Default values if -v not supplied. psql ignores re-set if already defined.
\if :{?email_domain}
\else
    \set email_domain example.com
\endif
\if :{?temp_pwd}
\else
    \set temp_pwd Welcome@123
\endif

\echo Seeding users with email domain: :email_domain

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 4 users, one per active role (BCrypt cost=10).
INSERT INTO users (id, username, email, first_name, last_name, password, status, fail_login_count, created_at) VALUES
(1, 'superadmin',  'superadmin@'  || :'email_domain', 'Super',   'Admin',    crypt(:'temp_pwd', gen_salt('bf', 10)), 'A', 0, CURRENT_TIMESTAMP),
(2, 'depthead',    'depthead@'    || :'email_domain', 'Dept',    'Head',     crypt(:'temp_pwd', gen_salt('bf', 10)), 'A', 0, CURRENT_TIMESTAMP),
(3, 'sectionhead', 'sectionhead@' || :'email_domain', 'Section', 'Head',     crypt(:'temp_pwd', gen_salt('bf', 10)), 'A', 0, CURRENT_TIMESTAMP),
(4, 'operator',    'operator@'    || :'email_domain', 'Floor',   'Operator', crypt(:'temp_pwd', gen_salt('bf', 10)), 'A', 0, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- Reserve IDs 1..4 for the seed users; subsequent app-created users start at 100.
SELECT setval('users_id_seq', 100, false);

-- Map each user to its role. department_id is intentionally NULL — assign per
-- company structure via the UI (User Edit → Departments).
-- Role IDs: 1=SUPER_ADMIN  2=DEPT_ADMIN  3=SUBDEPT_ADMIN  9=OPERATOR
INSERT INTO user_role_departments (user_id, role_id, department_id, created_at, created_by) VALUES
(1, 1, NULL, CURRENT_TIMESTAMP, 1),
(2, 2, NULL, CURRENT_TIMESTAMP, 1),
(3, 3, NULL, CURRENT_TIMESTAMP, 1),
(4, 9, NULL, CURRENT_TIMESTAMP, 1)
ON CONFLICT (user_id, role_id, department_id) DO NOTHING;

-- Restamp departments.created_by to super admin so audit columns resolve.
UPDATE departments SET created_by = 1 WHERE created_by IS NULL;

COMMIT;

-- ----------------------------------------------------------------------------
-- Verification
-- ----------------------------------------------------------------------------
SELECT u.id, u.username, u.email, r.role_code
FROM users u
JOIN user_role_departments urd ON urd.user_id = u.id
JOIN roles r ON r.id = urd.role_id
ORDER BY u.id;
