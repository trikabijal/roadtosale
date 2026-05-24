-- Seed data for local AI assessment testing
-- Creates: 1 user, 1 department, 1 checksheet with 15 questions, 1 user_checksheet

BEGIN;

-- 1. Create test user (password: "test123" bcrypt-hashed)
INSERT INTO users (id, first_name, last_name, email, username, password, created_at)
VALUES (1, 'Test', 'Auditor', 'auditor@kia.test', 'auditor',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        NOW())
ON CONFLICT (id) DO NOTHING;

-- 2. Create department
INSERT INTO departments (id, name, created_at)
VALUES (1, 'Kia Showroom Audit', NOW())
ON CONFLICT (id) DO NOTHING;

-- 3. Assign user to role + department
INSERT INTO user_role_departments (id, user_id, role_id, department_id, created_at)
VALUES (1, 1, 9, 1, NOW())  -- role 9 = Operator
ON CONFLICT (id) DO NOTHING;

-- 4. Create checksheet template
INSERT INTO checksheets (id, name, status, checksheet_type, frequency_of_check, created_at, created_by)
VALUES (1, 'Kia Showroom Audit - Exterior', 'APPROVED', 'PRIVATE', 'MONTHLY', NOW(), 1)
ON CONFLICT (id) DO NOTHING;

-- 5. Create headers (zones/categories)
-- Header 1: Exterior > Branding & Visibility
INSERT INTO chks_headers (id, name, is_result_column, checksheet_id, created_at, created_by)
VALUES
  (1, 'Branding & Visibility', true, 1, NOW(), 1),
  (2, 'Structure', true, 1, NOW(), 1)
ON CONFLICT (id) DO NOTHING;

-- 6. Create header data (elements)
INSERT INTO chks_header_data (id, name, chks_header_id, checksheet_id, order_no, created_at, created_by)
VALUES
  (1, 'Front ACP + Logo', 1, 1, 1, NOW(), 1),
  (2, 'Pylon', 1, 1, 2, NOW(), 1),
  (3, 'Directional Signage', 1, 1, 3, NOW(), 1),
  (4, 'Valet Parking', 1, 1, 4, NOW(), 1),
  (5, 'Security Guard', 1, 1, 5, NOW(), 1),
  (6, 'Facade Glass', 2, 1, 1, NOW(), 1),
  (7, 'Granite', 2, 1, 2, NOW(), 1),
  (8, 'Pavers', 2, 1, 3, NOW(), 1)
ON CONFLICT (id) DO NOTHING;

-- 7. Create questions (checkpoints) — all 15 lines
INSERT INTO chks_questions (id, name, description, checksheet_id, chks_header_id, chks_header_data_id, order_no, created_at, created_by)
VALUES
  (1,  'Damage',              'Check Front ACP and Logo for physical damage',    1, 1, 1, 1, NOW(), 1),
  (2,  'Cleanliness',         'Check Front ACP and Logo for cleanliness',        1, 1, 1, 2, NOW(), 1),
  (3,  'Lighting',            'Count non-functional lights on front ACP/logo',   1, 1, 1, 3, NOW(), 1),
  (4,  'Visibility',          'Check Pylon visibility from approach road',       1, 1, 2, 1, NOW(), 1),
  (5,  'Cleanliness',         'Check Pylon for cleanliness',                     1, 1, 2, 2, NOW(), 1),
  (6,  'Directional clarity', 'Check directional signage clarity and accuracy',  1, 1, 3, 1, NOW(), 1),
  (7,  'Signage visibility',  'Check valet parking signage visibility',          1, 1, 4, 1, NOW(), 1),
  (8,  'Uniform',             'Check security guard uniform compliance',         1, 1, 5, 1, NOW(), 1),
  (9,  'Grooming',            'Check security guard grooming standards',         1, 1, 5, 2, NOW(), 1),
  (10, 'Damage',              'Check facade glass for damage',                   1, 2, 6, 1, NOW(), 1),
  (11, 'Cleanliness',         'Check facade glass for cleanliness',              1, 2, 6, 2, NOW(), 1),
  (12, 'Cracks',              'Check granite for cracks or chips',               1, 2, 7, 1, NOW(), 1),
  (13, 'Cleanliness',         'Check granite for cleanliness',                   1, 2, 7, 2, NOW(), 1),
  (14, 'Damage',              'Check pavers for damage',                         1, 2, 8, 1, NOW(), 1),
  (15, 'Cleanliness',         'Check pavers for cleanliness',                    1, 2, 8, 2, NOW(), 1)
ON CONFLICT (id) DO NOTHING;

-- 8. Create question results (answer type config per question)
INSERT INTO chks_question_results (id, chks_header_id, chks_question_id, checksheet_id, answer_type, created_at, created_by)
VALUES
  (1,  1, 1,  1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (2,  1, 2,  1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (3,  1, 3,  1, 'OBJECTIVE',            NOW(), 1),
  (4,  1, 4,  1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (5,  1, 5,  1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (6,  1, 6,  1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (7,  1, 7,  1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (8,  1, 8,  1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (9,  1, 9,  1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (10, 2, 10, 1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (11, 2, 11, 1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (12, 2, 12, 1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (13, 2, 13, 1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (14, 2, 14, 1, 'SUBJECTIVE_CONDITION', NOW(), 1),
  (15, 2, 15, 1, 'SUBJECTIVE_CONDITION', NOW(), 1)
ON CONFLICT (id) DO NOTHING;

-- 9. Create result options (OK/NOT_OK criteria per question)
INSERT INTO chks_question_result_options (id, chks_question_result_id, checksheet_id, option, judgement, created_at, created_by)
VALUES
  -- Line 1: ACP Damage
  (1,  1, 1, 'No visible damage on ACP or logo',                'Ok',     NOW(), 1),
  (2,  1, 1, 'Visible dents, scratches, or panel damage',       'Not Ok', NOW(), 1),
  -- Line 2: ACP Cleanliness
  (3,  2, 1, 'ACP and logo clean, no dust or streaks',          'Ok',     NOW(), 1),
  (4,  2, 1, 'Dusty, stained, or streaked',                     'Not Ok', NOW(), 1),
  -- Line 4: Pylon Visibility
  (5,  4, 1, 'Pylon fully visible from approach road',          'Ok',     NOW(), 1),
  (6,  4, 1, 'Obstructed or not visible from road',             'Not Ok', NOW(), 1),
  -- Line 5: Pylon Cleanliness
  (7,  5, 1, 'Pylon clean, no dust or droppings',               'Ok',     NOW(), 1),
  (8,  5, 1, 'Dirty, stained, or covered in droppings',         'Not Ok', NOW(), 1),
  -- Line 6: Signage Clarity
  (9,  6, 1, 'Signage correct, legible, and logical',           'Ok',     NOW(), 1),
  (10, 6, 1, 'Missing, illegible, or misleading',               'Not Ok', NOW(), 1),
  -- Line 7: Valet Signage
  (11, 7, 1, 'Valet signage clearly visible',                   'Ok',     NOW(), 1),
  (12, 7, 1, 'Signage missing or obscured',                     'Not Ok', NOW(), 1),
  -- Line 8: Guard Uniform
  (13, 8, 1, 'Guard in complete, correct uniform',              'Ok',     NOW(), 1),
  (14, 8, 1, 'Uniform incomplete or non-compliant',             'Not Ok', NOW(), 1),
  -- Line 9: Guard Grooming
  (15, 9, 1, 'Well-groomed and tidy',                           'Ok',     NOW(), 1),
  (16, 9, 1, 'Poorly groomed or unkempt',                       'Not Ok', NOW(), 1),
  -- Line 10: Glass Damage
  (17, 10, 1, 'No cracks, chips, or broken sections',           'Ok',     NOW(), 1),
  (18, 10, 1, 'Cracks, chips, or broken sections',              'Not Ok', NOW(), 1),
  -- Line 11: Glass Cleanliness
  (19, 11, 1, 'Streak-free and clean',                          'Ok',     NOW(), 1),
  (20, 11, 1, 'Dirty, streaked, or smudged',                    'Not Ok', NOW(), 1),
  -- Line 12: Granite Cracks
  (21, 12, 1, 'No cracks or chips',                             'Ok',     NOW(), 1),
  (22, 12, 1, 'Visible cracks or broken sections',              'Not Ok', NOW(), 1),
  -- Line 13: Granite Cleanliness
  (23, 13, 1, 'Polished and clean',                             'Ok',     NOW(), 1),
  (24, 13, 1, 'Stained or grimy',                               'Not Ok', NOW(), 1),
  -- Line 14: Pavers Damage
  (25, 14, 1, 'All pavers intact and level',                    'Ok',     NOW(), 1),
  (26, 14, 1, 'Broken, sunken, or missing pavers',              'Not Ok', NOW(), 1),
  -- Line 15: Pavers Cleanliness
  (27, 15, 1, 'Clean, no stains or debris',                     'Ok',     NOW(), 1),
  (28, 15, 1, 'Stained, grimy, or littered',                    'Not Ok', NOW(), 1)
ON CONFLICT (id) DO NOTHING;

-- 10. (removed) The pre-V1.24 user_checksheet seed used to live here, but
-- V1.24 added a NOT NULL audit_assignment_id FK. A user_checksheet now requires
-- an Audit + AuditAssignment to exist first, so this kind of setup belongs in
-- dev/seed-kia-demo.py (which goes through the API and creates them properly).

-- ─────────────────────────────────────────────────────────────────────────────
-- Kia demo seed (used by dev/seed-kia-demo.py)
--
-- Creates 30 demo operator users (KIA_DEMO_AUDITOR_001 — KIA_DEMO_AUDITOR_030)
-- and registers data-validator + data-approver associations on checksheet 15
-- ("Kia Dealership Audit 5") so the user_checksheet validate / approve API
-- accepts those users.
--
-- Idempotent (NOT EXISTS guards). Password for all demo operators: "12345678".
-- BCrypt hash committed below — change DEFAULT_PWD_HASH in lockstep with seed.
-- ─────────────────────────────────────────────────────────────────────────────

-- Demo operator users — 30 of them, one per Kia operator slot.
-- pgcrypto isn't available on the UAT Postgres 11, so we use a literal hash.
DO $$
DECLARE
    i INT;
    new_user_id BIGINT;
    operator_role_id BIGINT;
    spa_dept_id BIGINT;
    pwd_hash TEXT := '$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK';
    uname TEXT;
    email TEXT;
BEGIN
    SELECT id INTO operator_role_id FROM roles WHERE role_code='OPERATOR' LIMIT 1;
    SELECT id INTO spa_dept_id FROM departments
        WHERE LOWER(name) LIKE 'sales process adherence' AND department_id IS NOT NULL LIMIT 1;

    IF operator_role_id IS NULL THEN
        RAISE NOTICE 'OPERATOR role not found — skipping demo operator seed';
        RETURN;
    END IF;

    FOR i IN 1..30 LOOP
        uname := format('KIA_DEMO_AUDITOR_%s', LPAD(i::text, 3, '0'));
        email := format('kia_demo_auditor_%s@example.com', LPAD(i::text, 3, '0'));

        INSERT INTO users (username, email, first_name, last_name, mobile, password,
                           status, fail_login_count, created_at, created_by)
        SELECT uname, email, 'Kia Demo', format('Auditor #%s', i), '0000000000',
               pwd_hash, 'A', 0, CURRENT_TIMESTAMP, 1
         WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = uname);

        SELECT id INTO new_user_id FROM users WHERE username = uname;

        IF new_user_id IS NOT NULL AND spa_dept_id IS NOT NULL THEN
            INSERT INTO user_role_departments (user_id, role_id, department_id, created_at, created_by)
            SELECT new_user_id, operator_role_id, spa_dept_id, CURRENT_TIMESTAMP, 1
             WHERE NOT EXISTS (
                SELECT 1 FROM user_role_departments
                 WHERE user_id = new_user_id AND role_id = operator_role_id AND department_id = spa_dept_id
             );
        END IF;
    END LOOP;
END $$;

-- Register KIA_SALES_AUDIT_DATA_VALIDATOR + KIA_SALES_AUDIT_DATA_APPROVER as
-- the data validator / approver for checksheet 15. The user_checksheet
-- validation/approval flow reads `data_validator_user_ids` and
-- `data_approver_user_ids` directly off the checksheet row.
UPDATE checksheets
   SET data_validator_user_ids = ARRAY[(SELECT id FROM users WHERE username='KIA_SALES_AUDIT_DATA_VALIDATOR')]::bigint[],
       data_approver_user_ids  = ARRAY[(SELECT id FROM users WHERE username='KIA_SALES_AUDIT_DATA_APPROVER')]::bigint[]
 WHERE id = 15
   AND EXISTS (SELECT 1 FROM users WHERE username='KIA_SALES_AUDIT_DATA_VALIDATOR')
   AND EXISTS (SELECT 1 FROM users WHERE username='KIA_SALES_AUDIT_DATA_APPROVER');

-- Reset sequences to avoid conflicts with future inserts
SELECT setval('users_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM users));
SELECT setval('departments_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM departments));
SELECT setval('checksheets_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM checksheets));
SELECT setval('chks_headers_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM chks_headers));
SELECT setval('chks_header_data_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM chks_header_data));
SELECT setval('chks_questions_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM chks_questions));
SELECT setval('chks_question_results_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM chks_question_results));
SELECT setval('chks_question_result_options_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM chks_question_result_options));
SELECT setval('user_checksheets_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM inspections));
SELECT setval('user_role_departments_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM user_role_departments));

COMMIT;
