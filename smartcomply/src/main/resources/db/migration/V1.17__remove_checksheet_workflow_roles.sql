-- Migration: Remove checksheet workflow roles
-- Roles to remove: CHK_SHT_PREPARE, CHK_SHT_VALIDATOR, CHK_SHT_APPROVER, CHK_SHT_DATA_VALIDATOR, CHK_SHT_DATA_APPROVER
-- Replacement mapping:
--   CHK_SHT_PREPARE, CHK_SHT_VALIDATOR, CHK_SHT_APPROVER -> SUBDEPT_ADMIN
--   CHK_SHT_DATA_VALIDATOR, CHK_SHT_DATA_APPROVER -> DEPT_ADMIN

DO $$
DECLARE
    subdept_admin_role_id BIGINT;
    dept_admin_role_id BIGINT;
    user_assignment RECORD;
    affected_user_count INT := 0;
BEGIN
    -- Get the replacement role IDs
    SELECT id INTO subdept_admin_role_id FROM roles WHERE role_code = 'SUBDEPT_ADMIN';
    SELECT id INTO dept_admin_role_id FROM roles WHERE role_code = 'DEPT_ADMIN';

    IF subdept_admin_role_id IS NULL THEN
        RAISE EXCEPTION 'SUBDEPT_ADMIN role not found. Cannot proceed with migration.';
    END IF;

    IF dept_admin_role_id IS NULL THEN
        RAISE EXCEPTION 'DEPT_ADMIN role not found. Cannot proceed with migration.';
    END IF;

    -- Log affected users before migration
    RAISE NOTICE '=== AFFECTED USERS REPORT ===';
    
    FOR user_assignment IN 
        SELECT 
            u.id AS user_id,
            u.username,
            r.role_code,
            urd.department_id,
            CASE 
                WHEN r.role_code IN ('CHK_SHT_PREPARE', 'CHK_SHT_VALIDATOR', 'CHK_SHT_APPROVER') 
                    THEN 'SUBDEPT_ADMIN'
                WHEN r.role_code IN ('CHK_SHT_DATA_VALIDATOR', 'CHK_SHT_DATA_APPROVER') 
                    THEN 'DEPT_ADMIN'
            END AS replacement_role
        FROM users u
        JOIN user_role_departments urd ON u.id = urd.user_id
        JOIN roles r ON urd.role_id = r.id
        WHERE r.role_code IN (
            'CHK_SHT_PREPARE',
            'CHK_SHT_VALIDATOR', 
            'CHK_SHT_APPROVER',
            'CHK_SHT_DATA_VALIDATOR',
            'CHK_SHT_DATA_APPROVER'
        )
        AND urd.deleted_by IS NULL
    LOOP
        affected_user_count := affected_user_count + 1;
        RAISE NOTICE 'User: %, Old Role: %, New Role: %, Department ID: %', 
            user_assignment.username, 
            user_assignment.role_code, 
            user_assignment.replacement_role,
            user_assignment.department_id;
    END LOOP;
    
    RAISE NOTICE 'Total affected user-role assignments: %', affected_user_count;
    RAISE NOTICE '=== END AFFECTED USERS REPORT ===';

    -- Step 1: Insert SUBDEPT_ADMIN for users who don't already have it
    -- (for users with CHK_SHT_PREPARE, CHK_SHT_VALIDATOR, CHK_SHT_APPROVER)
    INSERT INTO user_role_departments (user_id, role_id, department_id, created_by, created_at)
    SELECT DISTINCT urd.user_id, subdept_admin_role_id, urd.department_id, urd.created_by, CURRENT_TIMESTAMP
    FROM user_role_departments urd
    JOIN roles r ON urd.role_id = r.id
    WHERE r.role_code IN ('CHK_SHT_PREPARE', 'CHK_SHT_VALIDATOR', 'CHK_SHT_APPROVER')
    AND urd.deleted_by IS NULL
    AND NOT EXISTS (
        -- Check if user already has SUBDEPT_ADMIN for this department (active or deleted)
        SELECT 1 FROM user_role_departments urd2
        WHERE urd2.user_id = urd.user_id
        AND urd2.department_id = urd.department_id
        AND urd2.role_id = subdept_admin_role_id
    );

    RAISE NOTICE 'Inserted SUBDEPT_ADMIN assignments for users who did not have it';

    -- Step 2: Insert DEPT_ADMIN for users who don't already have it
    -- (for users with CHK_SHT_DATA_VALIDATOR, CHK_SHT_DATA_APPROVER)
    INSERT INTO user_role_departments (user_id, role_id, department_id, created_by, created_at)
    SELECT DISTINCT urd.user_id, dept_admin_role_id, urd.department_id, urd.created_by, CURRENT_TIMESTAMP
    FROM user_role_departments urd
    JOIN roles r ON urd.role_id = r.id
    WHERE r.role_code IN ('CHK_SHT_DATA_VALIDATOR', 'CHK_SHT_DATA_APPROVER')
    AND urd.deleted_by IS NULL
    AND NOT EXISTS (
        -- Check if user already has DEPT_ADMIN for this department (active or deleted)
        SELECT 1 FROM user_role_departments urd2
        WHERE urd2.user_id = urd.user_id
        AND urd2.department_id = urd.department_id
        AND urd2.role_id = dept_admin_role_id
    );

    RAISE NOTICE 'Inserted DEPT_ADMIN assignments for users who did not have it';

    -- Step 3: Hard DELETE all old role assignments (instead of soft-delete to avoid constraint issues)
    DELETE FROM user_role_departments urd
    USING roles r
    WHERE urd.role_id = r.id
    AND r.role_code IN (
        'CHK_SHT_PREPARE',
        'CHK_SHT_VALIDATOR', 
        'CHK_SHT_APPROVER',
        'CHK_SHT_DATA_VALIDATOR',
        'CHK_SHT_DATA_APPROVER'
    );

    RAISE NOTICE 'Deleted all old role assignments from user_role_departments';

    -- Step 4: Remove role_permissions for the old roles (NOT the permissions themselves)
    DELETE FROM role_permissions rp
    USING roles r
    WHERE rp.role_id = r.id
    AND r.role_code IN (
        'CHK_SHT_PREPARE',
        'CHK_SHT_VALIDATOR', 
        'CHK_SHT_APPROVER',
        'CHK_SHT_DATA_VALIDATOR',
        'CHK_SHT_DATA_APPROVER'
    );

    RAISE NOTICE 'Deleted role_permissions for old roles (permissions retained for other roles)';

    -- Step 5: Hard delete the roles from roles table
    DELETE FROM roles
    WHERE role_code IN (
        'CHK_SHT_PREPARE',
        'CHK_SHT_VALIDATOR', 
        'CHK_SHT_APPROVER',
        'CHK_SHT_DATA_VALIDATOR',
        'CHK_SHT_DATA_APPROVER'
    );

    RAISE NOTICE 'Migration completed successfully!';
    RAISE NOTICE 'Roles removed: CHK_SHT_PREPARE, CHK_SHT_VALIDATOR, CHK_SHT_APPROVER, CHK_SHT_DATA_VALIDATOR, CHK_SHT_DATA_APPROVER';
    RAISE NOTICE 'Users migrated to SUBDEPT_ADMIN or DEPT_ADMIN based on their old roles';
END $$;
