-- Step 2: Create Permissions Tables and Seed Initial Data

DO $$
BEGIN
    -- Create permissions table
    IF NOT EXISTS (SELECT FROM information_schema.tables 
                   WHERE table_schema = 'public' 
                   AND table_name = 'permissions') THEN
        
        CREATE TABLE permissions (
            id BIGSERIAL PRIMARY KEY,
            permission_code VARCHAR(100) NOT NULL UNIQUE,
            name VARCHAR(255) NOT NULL,
            description TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
            updated_at TIMESTAMP,
            deleted_at TIMESTAMP,
            created_by BIGINT,
            deleted_by BIGINT,
            updated_by BIGINT,
            CONSTRAINT fk_permissions_created_by FOREIGN KEY (created_by) REFERENCES users(id),
            CONSTRAINT fk_permissions_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id),
            CONSTRAINT fk_permissions_updated_by FOREIGN KEY (updated_by) REFERENCES users(id)
        );

        CREATE INDEX idx_permissions_code ON permissions(permission_code);
        CREATE INDEX idx_permissions_deleted_at ON permissions(deleted_at);
    END IF;

    -- Create role_permissions join table
    IF NOT EXISTS (SELECT FROM information_schema.tables 
                   WHERE table_schema = 'public' 
                   AND table_name = 'role_permissions') THEN
        
        CREATE TABLE role_permissions (
            id BIGSERIAL PRIMARY KEY,
            role_id BIGINT NOT NULL,
            permission_id BIGINT NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
            created_by BIGINT,
            CONSTRAINT fk_role_permissions_role_id FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
            CONSTRAINT fk_role_permissions_permission_id FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE,
            CONSTRAINT uk_role_permissions_role_permission UNIQUE (role_id, permission_id)
        );

        CREATE INDEX idx_role_permissions_role_id ON role_permissions(role_id);
        CREATE INDEX idx_role_permissions_permission_id ON role_permissions(permission_id);
    END IF;

    -- Seed core permissions
    INSERT INTO permissions (permission_code, name, description) VALUES
        -- User Management Permissions
        ('USER_CREATE', 'Create User', 'Permission to create new users'),
        ('USER_UPDATE', 'Update User', 'Permission to update existing users'),
        ('USER_DELETE', 'Delete User', 'Permission to delete users'),
        ('USER_VIEW', 'View User', 'Permission to view user details'),
        ('USER_LIST', 'List Users', 'Permission to list all users'),
        ('USER_ASSIGN_ROLE', 'Assign Role', 'Permission to assign roles to users'),
        ('USER_REMOVE_ROLE', 'Remove Role', 'Permission to remove roles from users'),
        
        -- Role Management Permissions
        ('ROLE_CREATE', 'Create Role', 'Permission to create new roles'),
        ('ROLE_UPDATE', 'Update Role', 'Permission to update existing roles'),
        ('ROLE_DELETE', 'Delete Role', 'Permission to delete roles'),
        ('ROLE_VIEW', 'View Role', 'Permission to view role details'),
        ('ROLE_LIST', 'List Roles', 'Permission to list all roles'),
        ('ROLE_ASSIGN_PERMISSION', 'Assign Permission to Role', 'Permission to assign permissions to roles'),
        
        -- Department Management Permissions
        ('DEPT_CREATE', 'Create Department', 'Permission to create departments'),
        ('DEPT_UPDATE', 'Update Department', 'Permission to update departments'),
        ('DEPT_DELETE', 'Delete Department', 'Permission to delete departments'),
        ('DEPT_VIEW', 'View Department', 'Permission to view department details'),
        ('DEPT_LIST', 'List Departments', 'Permission to list all departments'),
        
        -- Checksheet Management Permissions
        ('CHKSHEET_CREATE', 'Create Checksheet', 'Permission to create checksheets'),
        ('CHKSHEET_UPDATE', 'Update Checksheet', 'Permission to update checksheets'),
        ('CHKSHEET_DELETE', 'Delete Checksheet', 'Permission to delete checksheets'),
        ('CHKSHEET_VIEW', 'View Checksheet', 'Permission to view checksheet details'),
        ('CHKSHEET_LIST', 'List Checksheets', 'Permission to list all checksheets'),
        ('CHKSHEET_PREPARE', 'Prepare Checksheet', 'Permission to prepare checksheets'),
        ('CHKSHEET_VALIDATE', 'Validate Checksheet', 'Permission to validate checksheets'),
        ('CHKSHEET_APPROVE', 'Approve Checksheet', 'Permission to approve checksheets'),
        ('CHKSHEET_DATA_VALIDATE', 'Validate Checksheet Data', 'Permission to validate checksheet data'),
        ('CHKSHEET_DATA_APPROVE', 'Approve Checksheet Data', 'Permission to approve checksheet data'),
        
        -- Dashboard and Reports
        ('DASHBOARD_VIEW', 'View Dashboard', 'Permission to view dashboard'),
        ('REPORT_VIEW', 'View Reports', 'Permission to view reports'),
        ('REPORT_EXPORT', 'Export Reports', 'Permission to export reports'),
        
        -- System Administration
        ('SYSTEM_CONFIG', 'System Configuration', 'Permission to configure system settings'),
        ('AUDIT_VIEW', 'View Audit Logs', 'Permission to view audit logs')
    ON CONFLICT (permission_code) DO NOTHING;

    -- Map permissions to roles based on hierarchy
    -- Super Admin gets ALL permissions
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'SUPER_ADMIN'
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    -- Department Admin gets most permissions except system config
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'DEPT_ADMIN'
    AND p.permission_code NOT IN ('SYSTEM_CONFIG', 'ROLE_CREATE', 'ROLE_DELETE', 'ROLE_ASSIGN_PERMISSION')
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    -- Section Admin gets department and user management within their scope
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'SUBDEPT_ADMIN'
    AND p.permission_code IN (
        'USER_CREATE', 'USER_UPDATE', 'USER_DELETE', 'USER_VIEW', 'USER_LIST', 'USER_ASSIGN_ROLE', 'USER_REMOVE_ROLE',
        'DEPT_VIEW', 'DEPT_LIST',
        'CHKSHEET_CREATE', 'CHKSHEET_UPDATE', 'CHKSHEET_VIEW', 'CHKSHEET_LIST', 'CHKSHEET_PREPARE',
        'CHKSHEET_VALIDATE', 'CHKSHEET_APPROVE', 'CHKSHEET_DATA_VALIDATE', 'CHKSHEET_DATA_APPROVE',
        'DASHBOARD_VIEW', 'REPORT_VIEW', 'REPORT_EXPORT'
    )
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    -- Checksheet Preparer permissions
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'CHK_SHT_PREPARE'
    AND p.permission_code IN (
        'CHKSHEET_CREATE', 'CHKSHEET_UPDATE', 'CHKSHEET_VIEW', 'CHKSHEET_LIST', 'CHKSHEET_PREPARE',
        'DASHBOARD_VIEW', 'REPORT_VIEW'
    )
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    -- Checksheet Validator permissions
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'CHK_SHT_VALIDATOR'
    AND p.permission_code IN (
        'CHKSHEET_VIEW', 'CHKSHEET_LIST', 'CHKSHEET_VALIDATE',
        'DASHBOARD_VIEW', 'REPORT_VIEW'
    )
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    -- Checksheet Approver permissions
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'CHK_SHT_APPROVER'
    AND p.permission_code IN (
        'CHKSHEET_VIEW', 'CHKSHEET_LIST', 'CHKSHEET_APPROVE',
        'DASHBOARD_VIEW', 'REPORT_VIEW'
    )
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    -- Checksheet Data Validator permissions
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'CHK_SHT_DATA_VALIDATOR'
    AND p.permission_code IN (
        'CHKSHEET_VIEW', 'CHKSHEET_LIST', 'CHKSHEET_DATA_VALIDATE',
        'DASHBOARD_VIEW', 'REPORT_VIEW'
    )
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    -- Checksheet Data Approver permissions
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'CHK_SHT_DATA_APPROVER'
    AND p.permission_code IN (
        'CHKSHEET_VIEW', 'CHKSHEET_LIST', 'CHKSHEET_DATA_APPROVE',
        'DASHBOARD_VIEW', 'REPORT_VIEW'
    )
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    -- Operator permissions (minimal)
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    CROSS JOIN permissions p
    WHERE r.role_code = 'OPERATOR'
    AND p.permission_code IN (
        'CHKSHEET_VIEW', 'CHKSHEET_LIST',
        'DASHBOARD_VIEW'
    )
    ON CONFLICT (role_id, permission_id) DO NOTHING;

END $$;

