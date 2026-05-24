-- V1.15: Add Permission Management permissions and assign to SUPER_ADMIN

DO $$
BEGIN
    -- Insert new permission management permissions
    INSERT INTO permissions (permission_code, name, description) VALUES
        ('PERMISSION_CREATE', 'Create Permission', 'Permission to create new permissions'),
        ('PERMISSION_UPDATE', 'Update Permission', 'Permission to update existing permissions'),
        ('PERMISSION_DELETE', 'Delete Permission', 'Permission to delete permissions'),
        ('PERMISSION_VIEW', 'View Permission', 'Permission to view permission details'),
        ('PERMISSION_LIST', 'List Permissions', 'Permission to list all permissions')
    ON CONFLICT (permission_code) DO NOTHING;

    -- Assign new permissions to SUPER_ADMIN role
    INSERT INTO role_permissions (role_id, permission_id, created_at)
    SELECT r.id, p.id, CURRENT_TIMESTAMP
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'SUPER_ADMIN'
    AND p.permission_code IN (
        'PERMISSION_CREATE',
        'PERMISSION_UPDATE',
        'PERMISSION_DELETE',
        'PERMISSION_VIEW',
        'PERMISSION_LIST'
    )
    ON CONFLICT (role_id, permission_id) DO NOTHING;

END $$;
