-- Drop unique constraint if it exists
ALTER TABLE public.checksheets DROP CONSTRAINT IF EXISTS checksheets_uk_uid;