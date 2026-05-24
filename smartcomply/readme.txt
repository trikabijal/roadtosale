
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
