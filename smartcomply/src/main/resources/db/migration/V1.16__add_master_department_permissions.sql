-- Add Master Department Management permissions
-- These permissions are separate from existing DEPARTMENT_* permissions which are for Department Admin assignment

INSERT INTO permissions (permission_code, name, description, created_at) VALUES
('MASTER_DEPARTMENT_LIST', 'Master Department List', 'View list of master departments', CURRENT_TIMESTAMP),
('MASTER_DEPARTMENT_VIEW', 'Master Department View', 'View master department details', CURRENT_TIMESTAMP),
('MASTER_DEPARTMENT_CREATE', 'Master Department Create', 'Create new master departments', CURRENT_TIMESTAMP),
('MASTER_DEPARTMENT_UPDATE', 'Master Department Update', 'Update master department details', CURRENT_TIMESTAMP),
('MASTER_DEPARTMENT_DELETE', 'Master Department Delete', 'Delete master departments', CURRENT_TIMESTAMP);

-- Assign new permissions to SUPER_ADMIN role (role_id = 1 typically for SUPER_ADMIN)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.role_code = 'SUPER_ADMIN'
AND p.permission_code IN (
    'MASTER_DEPARTMENT_LIST',
    'MASTER_DEPARTMENT_VIEW',
    'MASTER_DEPARTMENT_CREATE',
    'MASTER_DEPARTMENT_UPDATE',
    'MASTER_DEPARTMENT_DELETE'
)
AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);
