-- V1.22: Seed master departments (top-level entries restored from production backup).
-- Master departments are rows in the departments table where department_id IS NULL.
-- These were present in the original production DB (extracted 2026-04-24).

INSERT INTO departments (id, name, department_id, created_at)
VALUES
  (1, 'Channel Development', NULL, '2026-04-11 16:41:28.485716'),
  (2, 'Sales',               NULL, '2026-04-11 16:41:43.291277'),
  (3, 'Service',             NULL, '2026-04-11 16:41:43.320256')
ON CONFLICT (id) DO NOTHING;

-- Ensure subsequent app-created departments start above the seeded IDs.
SELECT setval('departments_id_seq', GREATEST(100, (SELECT MAX(id) FROM departments) + 1), false);
