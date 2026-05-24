-- Drop is_corporate column from checksheets table
ALTER TABLE checksheets DROP COLUMN IF EXISTS is_corporate;
