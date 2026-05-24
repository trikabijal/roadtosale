DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                   AND table_name = 'checksheets'
                   AND column_name = 'escalate_to_user_ids') THEN
        ALTER TABLE public.checksheets ADD escalate_to_user_ids _int8 NULL;

        UPDATE public.checksheets
        SET escalate_to_user_ids = ARRAY[escalate_to_user_id]::bigint[];
END IF;
END $$;


DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                   AND table_name = 'checksheets'
                   AND column_name = 'alert_to_user_ids') THEN
        ALTER TABLE public.checksheets ADD alert_to_user_ids _int8 NULL;

        UPDATE public.checksheets
        SET alert_to_user_ids = ARRAY[alert_to_user_id]::bigint[];
    END IF;
END $$;
