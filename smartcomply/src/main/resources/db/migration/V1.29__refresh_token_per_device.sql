-- ============================================================================
-- V1.29: refresh_token — drop the implicit UNIQUE(user_id) so a user can
--        hold one refresh token per device_type instead of one total.
-- ----------------------------------------------------------------------------
-- The RefreshToken entity originally declared @OneToOne on the user
-- relationship. Hibernate translated that into an implicit UNIQUE
-- constraint on user_id (auto-named "uk_f95ixxe7pa48ryn1awmh2evt7" on this
-- DB). Effect: a user who logged in from APP couldn't subsequently log in
-- from WEB — the second insert blew up on the constraint and the error
-- bubbled out as a generic "Invalid username or password" 400 to the
-- caller.
--
-- Fix: drop that constraint and add UNIQUE(user_id, device_type) which is
-- what the code's findByUserAndDeviceType + upsert logic actually wants.
-- The entity is changed to @ManyToOne so Hibernate stops re-creating the
-- single-column unique constraint.
-- ============================================================================

-- Drop the auto-generated single-column UNIQUE on user_id, whatever its name.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN
        SELECT con.conname
          FROM pg_constraint con
          JOIN pg_class cls ON cls.oid = con.conrelid
         WHERE cls.relname = 'refresh_token'
           AND con.contype = 'u'
           AND con.conkey = (SELECT array_agg(att.attnum::int2)
                               FROM pg_attribute att
                              WHERE att.attrelid = cls.oid AND att.attname = 'user_id')
    LOOP
        EXECUTE format('ALTER TABLE refresh_token DROP CONSTRAINT IF EXISTS %I', c.conname);
    END LOOP;
END $$;

-- Add the proper composite uniqueness (idempotent).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'uk_refresh_token_user_device'
           AND conrelid = 'refresh_token'::regclass
    ) THEN
        ALTER TABLE refresh_token
            ADD CONSTRAINT uk_refresh_token_user_device UNIQUE (user_id, device_type);
    END IF;
END $$;
