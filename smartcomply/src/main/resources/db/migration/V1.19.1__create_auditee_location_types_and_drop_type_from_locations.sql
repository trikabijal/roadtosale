DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'auditee_locations'
          AND column_name = 'auditee_type_id'
    ) THEN
        ALTER TABLE auditee_locations
            DROP CONSTRAINT IF EXISTS fk_auditee_locations_auditee_type_id_auditee_types_id;

        ALTER TABLE auditee_locations
            DROP COLUMN auditee_type_id;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'auditees'
          AND column_name = 'auditee_type_id'
    ) THEN
        ALTER TABLE auditees
            DROP CONSTRAINT IF EXISTS fk_auditees_auditee_type_id_auditee_types_id;

        ALTER TABLE auditees
            DROP COLUMN auditee_type_id;
    END IF;
END $$;
