-- Migration to remove CHECKSHEET_MANAGEMENT_FILTER permission from the system
-- This permission is redundant as CHECKSHEET_MANAGEMENT_LIST provides the same functionality

-- Remove role-permission mappings for CHECKSHEET_MANAGEMENT_FILTER
DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT id FROM permissions WHERE permission_code = 'CHECKSHEET_MANAGEMENT_FILTER'
);

-- Remove the permission itself
DELETE FROM permissions
WHERE permission_code = 'CHECKSHEET_MANAGEMENT_FILTER';
