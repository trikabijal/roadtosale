DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables 
               WHERE table_schema = 'public' 
               AND table_name = 'users') THEN

        ALTER TABLE users ALTER COLUMN jwt_token TYPE text;
    
    END IF;
END $$;