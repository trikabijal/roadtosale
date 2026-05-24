-- For ANY operator searches
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables 
               WHERE table_schema = 'public' 
               AND table_name = 'roles') THEN

        CREATE INDEX IF NOT EXISTS idx_roles_parent_role_id ON roles(parent_role_id);

    END IF;
END $$;

