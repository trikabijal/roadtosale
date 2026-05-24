-- ============================================================================
-- CI admin seed — runs after baseline.sql.gz is restored.
-- ----------------------------------------------------------------------------
-- The baseline dump carries the local DB's users (Z006135 + Kia personas +
-- demo operators). The test suites need a stable bootstrap admin called
-- `ci_admin` — created here with SUPER_ADMIN role. Idempotent (ON CONFLICT
-- / NOT EXISTS) so re-running on a half-populated DB is safe.
-- ============================================================================

BEGIN;

INSERT INTO users (username, email, first_name, last_name, mobile,
                   password, status, fail_login_count, created_at, created_by)
SELECT 'ci_admin', 'ci_admin@example.com', 'CI', 'Admin', '9000000000',
       -- bcrypt('12345678') — matches V100.001 hash convention.
       '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK',
       'A', 0, NOW(), NULL
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'ci_admin');

-- SUPER_ADMIN @ Sales master department. role_id=1 / dept_id=2 are stable
-- across the local + UAT seeds.
INSERT INTO user_role_departments (user_id, role_id, department_id, created_at, created_by)
SELECT u.id, 1, (SELECT id FROM departments WHERE name='Sales' AND department_id IS NULL),
       NOW(), u.id
FROM users u
WHERE u.username = 'ci_admin'
  AND NOT EXISTS (
      SELECT 1 FROM user_role_departments urd
       WHERE urd.user_id = u.id AND urd.role_id = 1);

COMMIT;
