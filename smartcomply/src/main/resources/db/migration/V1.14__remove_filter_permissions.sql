-- Migration to remove all redundant FILTER permissions from the system
-- These permissions are redundant as their corresponding LIST/LISTING permissions provide the same functionality

-- Remove role-permission mappings for all FILTER permissions
DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT id FROM permissions 
    WHERE permission_code IN (
        'CHECKSHEET_FILTER',
        'CHECKSHEET_FILL_FILTER',
        'DEPARTMENT_FILTER',
        'SUBDEPARTMENT_FILTER',
        'USER_FILTER'
    )
);

-- Remove the permissions themselves
DELETE FROM permissions
WHERE permission_code IN (
    'CHECKSHEET_FILTER',
    'CHECKSHEET_FILL_FILTER',
    'DEPARTMENT_FILTER',
    'SUBDEPARTMENT_FILTER',
    'USER_FILTER'
);
