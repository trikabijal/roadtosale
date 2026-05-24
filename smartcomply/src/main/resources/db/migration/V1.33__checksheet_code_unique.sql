-- V1.33: Enforce unique checksheet code (model_no) across root checksheets.
-- Versioned checksheets (those with a parent checksheet_id) inherit the parent's
-- code and are intentionally excluded from the uniqueness constraint so that a new
-- version can carry the same code as its approved predecessor.
-- The index is case-insensitive and skips NULL / blank values (model_no is optional).

-- Step 1: Deduplicate existing root checksheets that share a model_no.
-- For each group of duplicates keep the row with the lowest id (the original)
-- and set model_no to NULL on all others so the unique index can be created.
UPDATE checksheets
   SET model_no = NULL
 WHERE id NOT IN (
         SELECT MIN(id)
           FROM checksheets
          WHERE model_no IS NOT NULL
            AND model_no <> ''
            AND checksheet_id IS NULL
          GROUP BY LOWER(model_no)
       )
   AND model_no IS NOT NULL
   AND model_no <> ''
   AND checksheet_id IS NULL;

-- Step 2: Create the partial unique index.
CREATE UNIQUE INDEX IF NOT EXISTS idx_checksheets_model_no
    ON checksheets (LOWER(model_no))
    WHERE model_no IS NOT NULL
      AND model_no <> ''
      AND checksheet_id IS NULL;
