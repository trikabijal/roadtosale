-- For ANY operator searches
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables 
               WHERE table_schema = 'public' 
               AND table_name = 'roles') THEN

        -- Dept Admin parent = Super Admin
        UPDATE roles
        SET parent_role_id = (SELECT id FROM roles WHERE role_code = 'SUPER_ADMIN')
        WHERE role_code = 'DEPT_ADMIN';

        -- Section Admin parent = Dept Admin
        UPDATE roles
        SET parent_role_id = (SELECT id FROM roles WHERE role_code = 'DEPT_ADMIN')
        WHERE role_code = 'SUBDEPT_ADMIN';

        -- Operational roles parent = Section Admin
        UPDATE roles
        SET parent_role_id = (SELECT id FROM roles WHERE role_code = 'SUBDEPT_ADMIN')
        WHERE role_code IN (
        'OPERATOR',
        'CHK_SHT_PREPARE',
        'CHK_SHT_VALIDATOR',
        'CHK_SHT_APPROVER',
        'CHK_SHT_DATA_VALIDATOR',
        'CHK_SHT_DATA_APPROVER'
        );

    END IF;
END $$;

