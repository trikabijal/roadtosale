DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables 
               WHERE table_schema = 'public' 
               AND table_name = 'users') THEN

        ALTER TABLE users ADD CONSTRAINT uk_users_username UNIQUE (username);
        UPDATE users set username = arca_id where 1=1;
        ALTER TABLE users DROP COLUMN IF EXISTS arca_id;

    END IF;
END $$;