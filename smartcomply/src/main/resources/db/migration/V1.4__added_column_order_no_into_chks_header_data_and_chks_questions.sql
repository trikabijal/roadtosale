-- Changes in chks_header_data table
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT column_name
        FROM information_schema.columns
        WHERE table_name = 'chks_header_data' and table_schema ='public'
          AND column_name = 'order_no'
    ) THEN
        ALTER TABLE public.chks_header_data ADD order_no int4 NULL;
    END IF;
END $$;

-- Changes in chks_header_data table
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT column_name
        FROM information_schema.columns
        WHERE table_name = 'chks_questions' and table_schema ='public'
          AND column_name = 'order_no'
    ) THEN
        ALTER TABLE public.chks_questions ADD order_no int4 NULL;
    END IF;
END $$;


---------------------------------------
-- First, create a temporary table with row numbers
WITH numbered_records AS (
    SELECT
        id,
        checksheet_id,
        chks_header_data_id,
        ROW_NUMBER() OVER (
            PARTITION BY checksheet_id, chks_header_data_id
            ORDER BY id
            ) AS new_order_no
    FROM chks_header_data
)

-- Then update the main table using this temporary table
UPDATE chks_header_data AS chd
SET order_no = nr.new_order_no
FROM numbered_records AS nr
WHERE chd.id = nr.id and chd.order_no is null;


---------------------------------------
-- First, create a temporary table with row numbers
WITH numbered_records AS (
    SELECT
        id,
        checksheet_id,
        chks_header_data_id,
        ROW_NUMBER() OVER (
            PARTITION BY checksheet_id, chks_header_data_id
            ORDER BY id
            ) AS new_order_no
    FROM chks_questions
)

-- Then update the main table using this temporary table
UPDATE chks_questions AS cq
SET order_no = nr.new_order_no
FROM numbered_records AS nr
WHERE cq.id = nr.id and cq.order_no is null;

-----------------------------------------
-- ALTER TABLE public.chks_questions ALTER COLUMN order_no SET NOT NULL;
-- ALTER TABLE public.chks_header_data ALTER COLUMN order_no SET NOT NULL;
