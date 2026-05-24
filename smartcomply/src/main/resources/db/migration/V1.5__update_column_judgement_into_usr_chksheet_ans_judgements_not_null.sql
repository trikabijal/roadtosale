-- Changes in chks_header_data table
DO $$
BEGIN
    IF EXISTS (
        SELECT column_name
        FROM information_schema.columns
        WHERE table_name = 'usr_chksheet_ans_judgements' and table_schema ='public'
          AND column_name = 'judgement'
    ) THEN
        ALTER TABLE public.usr_chksheet_ans_judgements ALTER COLUMN judgement DROP NOT NULL;
    END IF;
END $$;
