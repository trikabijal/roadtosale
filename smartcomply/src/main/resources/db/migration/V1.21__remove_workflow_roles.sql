-- V1.21: Permanently remove checksheet workflow roles.
-- Roles removed: CHK_SHT_PREPARE, CHK_SHT_VALIDATOR, CHK_SHT_APPROVER,
--                CHK_SHT_DATA_VALIDATOR, CHK_SHT_DATA_APPROVER
--
-- Retained roles: SUPER_ADMIN, DEPT_ADMIN, SUBDEPT_ADMIN, OPERATOR
--
-- Any existing user assignments to removed roles are migrated:
--   CHK_SHT_PREPARE / CHK_SHT_VALIDATOR / CHK_SHT_APPROVER  → SUBDEPT_ADMIN
--   CHK_SHT_DATA_VALIDATOR / CHK_SHT_DATA_APPROVER           → DEPT_ADMIN

DO $$
DECLARE
    subdept_admin_role_id BIGINT;
    dept_admin_role_id    BIGINT;
BEGIN
    SELECT id INTO subdept_admin_role_id FROM roles WHERE role_code = 'SUBDEPT_ADMIN';
    SELECT id INTO dept_admin_role_id    FROM roles WHERE role_code = 'DEPT_ADMIN';

    IF subdept_admin_role_id IS NULL THEN
        RAISE EXCEPTION 'SUBDEPT_ADMIN role not found — cannot migrate.';
    END IF;
    IF dept_admin_role_id IS NULL THEN
        RAISE EXCEPTION 'DEPT_ADMIN role not found — cannot migrate.';
    END IF;

    -- Migrate CHK_SHT_PREPARE / VALIDATOR / APPROVER → SUBDEPT_ADMIN
    INSERT INTO user_role_departments (user_id, role_id, department_id, created_by, created_at)
    SELECT DISTINCT urd.user_id, subdept_admin_role_id, urd.department_id, urd.created_by, CURRENT_TIMESTAMP
    FROM user_role_departments urd
    JOIN roles r ON urd.role_id = r.id
    WHERE r.role_code IN ('CHK_SHT_PREPARE', 'CHK_SHT_VALIDATOR', 'CHK_SHT_APPROVER')
    AND NOT EXISTS (
        SELECT 1 FROM user_role_departments urd2
        WHERE urd2.user_id = urd.user_id
          AND urd2.department_id IS NOT DISTINCT FROM urd.department_id
          AND urd2.role_id = subdept_admin_role_id
    );

    -- Migrate CHK_SHT_DATA_VALIDATOR / DATA_APPROVER → DEPT_ADMIN
    INSERT INTO user_role_departments (user_id, role_id, department_id, created_by, created_at)
    SELECT DISTINCT urd.user_id, dept_admin_role_id, urd.department_id, urd.created_by, CURRENT_TIMESTAMP
    FROM user_role_departments urd
    JOIN roles r ON urd.role_id = r.id
    WHERE r.role_code IN ('CHK_SHT_DATA_VALIDATOR', 'CHK_SHT_DATA_APPROVER')
    AND NOT EXISTS (
        SELECT 1 FROM user_role_departments urd2
        WHERE urd2.user_id = urd.user_id
          AND urd2.department_id IS NOT DISTINCT FROM urd.department_id
          AND urd2.role_id = dept_admin_role_id
    );

    -- Remove old user_role_departments assignments
    DELETE FROM user_role_departments urd
    USING roles r
    WHERE urd.role_id = r.id
      AND r.role_code IN ('CHK_SHT_PREPARE', 'CHK_SHT_VALIDATOR', 'CHK_SHT_APPROVER',
                          'CHK_SHT_DATA_VALIDATOR', 'CHK_SHT_DATA_APPROVER');

    -- Remove role_permissions for the removed roles
    DELETE FROM role_permissions rp
    USING roles r
    WHERE rp.role_id = r.id
      AND r.role_code IN ('CHK_SHT_PREPARE', 'CHK_SHT_VALIDATOR', 'CHK_SHT_APPROVER',
                          'CHK_SHT_DATA_VALIDATOR', 'CHK_SHT_DATA_APPROVER');

    -- Delete the roles themselves
    DELETE FROM roles
    WHERE role_code IN ('CHK_SHT_PREPARE', 'CHK_SHT_VALIDATOR', 'CHK_SHT_APPROVER',
                        'CHK_SHT_DATA_VALIDATOR', 'CHK_SHT_DATA_APPROVER');

    RAISE NOTICE 'V1.21 complete: workflow roles removed. Users migrated to SUBDEPT_ADMIN / DEPT_ADMIN.';
END $$;
