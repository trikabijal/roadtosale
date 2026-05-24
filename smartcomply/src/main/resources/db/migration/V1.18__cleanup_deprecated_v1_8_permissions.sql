-- Cleanup deprecated V1.8 abbreviated permission codes
-- These permissions were created in V1.8 but never used in application code.
-- V1.9 introduced full-name convention (DEPARTMENT_*, SUBDEPARTMENT_*) which is actively used.
-- This migration removes the unused abbreviated permissions to avoid confusion.

DO $$
DECLARE
    deprecated_permissions TEXT[] := ARRAY[
        'DEPT_CREATE',
        'DEPT_UPDATE', 
        'DEPT_DELETE',
        'DEPT_VIEW',
        'DEPT_LIST',
        'CHKSHEET_CREATE',
        'CHKSHEET_UPDATE',
        'CHKSHEET_DELETE',
        'CHKSHEET_VIEW',
        'CHKSHEET_LIST',
        'CHKSHEET_PREPARE',
        'CHKSHEET_VALIDATE',
        'CHKSHEET_APPROVE',
        'CHKSHEET_DATA_VALIDATE',
        'CHKSHEET_DATA_APPROVE'
    ];
    perm_code TEXT;
    perm_id BIGINT;
BEGIN
    FOREACH perm_code IN ARRAY deprecated_permissions
    LOOP
        -- Get the permission ID
        SELECT id INTO perm_id FROM permissions WHERE permission_code = perm_code;
        
        IF perm_id IS NOT NULL THEN
            -- Remove any role_permissions associations first
            DELETE FROM role_permissions WHERE permission_id = perm_id;
            
            -- Remove the permission itself
            DELETE FROM permissions WHERE id = perm_id;
            
            RAISE NOTICE 'Removed deprecated permission: %', perm_code;
        END IF;
    END LOOP;
END $$;
