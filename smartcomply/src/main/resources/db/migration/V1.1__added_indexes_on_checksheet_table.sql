-- For ANY operator searches
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables 
               WHERE table_schema = 'public' 
               AND table_name = 'checksheets') THEN
        CREATE INDEX IF NOT EXISTS idx_checksheets_validator_any
            ON checksheets USING gin(validator_user_ids array_ops);

        CREATE INDEX IF NOT EXISTS idx_checksheets_approver_any
            ON checksheets USING gin(approver_user_ids array_ops);

        CREATE INDEX IF NOT EXISTS idx_checksheets_data_validator_any
            ON checksheets USING gin(data_validator_user_ids array_ops);

        CREATE INDEX IF NOT EXISTS idx_checksheets_data_approver_any
            ON checksheets USING gin(data_approver_user_ids array_ops);

        CREATE INDEX IF NOT EXISTS idx_checksheets_operator_any
            ON checksheets USING gin(operator_user_ids array_ops);
    END IF;
END $$;

