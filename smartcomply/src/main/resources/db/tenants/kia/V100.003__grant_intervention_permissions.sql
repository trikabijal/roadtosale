-- Tenant-data: grant intervention-related permissions to existing Kia roles.
-- Role-permission grants are tenant-specific decisions; another OEM may give
-- the same permissions to different role names entirely.
--
-- KIA_SALES_DEPT_HEAD       — manages interventions, manages assignments, views
-- KIA_SALES_AUDITOR (=AUDITOR), KIA_DEMO_AUDITOR_* — conduct re-inspections
-- DEALER_PRINCIPAL          — acknowledges + views their own dealer's plans
--
-- Idempotent: each grant is guarded so re-running on a partially-seeded DB
-- is safe.

DO $$
DECLARE
  role_perm RECORD;
BEGIN
  FOR role_perm IN
    SELECT * FROM (VALUES
      ('SUPER_ADMIN',      'INTERVENTION_MANAGE'),
      ('SUPER_ADMIN',      'INTERVENTION_ASSIGNMENT_VIEW'),
      ('SUPER_ADMIN',      'INTERVENTION_ASSIGNMENT_MANAGE'),
      ('SUPER_ADMIN',      'INTERVENTION_ASSIGNMENT_ACKNOWLEDGE'),
      ('SUPER_ADMIN',      'INTERVENTION_CONDUCT'),
      ('DEPT_ADMIN',       'INTERVENTION_MANAGE'),
      ('DEPT_ADMIN',       'INTERVENTION_ASSIGNMENT_VIEW'),
      ('DEPT_ADMIN',       'INTERVENTION_ASSIGNMENT_MANAGE'),
      ('AUDITOR',          'INTERVENTION_CONDUCT'),
      ('OPERATOR',         'INTERVENTION_CONDUCT'),
      ('DEALER_PRINCIPAL', 'INTERVENTION_ASSIGNMENT_VIEW'),
      ('DEALER_PRINCIPAL', 'INTERVENTION_ASSIGNMENT_ACKNOWLEDGE')
    ) AS t(role_code, perm_code)
  LOOP
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
      FROM roles r, permissions p
     WHERE r.role_code = role_perm.role_code
       AND p.permission_code = role_perm.perm_code
       AND NOT EXISTS (
         SELECT 1 FROM role_permissions rp
          WHERE rp.role_id = r.id AND rp.permission_id = p.id
       );
  END LOOP;
END $$;
