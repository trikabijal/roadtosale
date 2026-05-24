-- ============================================================================
-- Tenant data migration — Honda Road to Sale: NADA checksheet + walk-in audit
-- ----------------------------------------------------------------------------
-- Loaded by Flyway only when tenant.id=honda.
-- All INSERTs are idempotent (ON CONFLICT / NOT EXISTS guards).
--
-- Creates:
--   1 checksheet    : NADA Road to Sale (code RTS_HONDA_V1, status APPROVED)
--   1 chks_header   : one top-level column header (required by the schema;
--                     NADA steps are modelled as chks_header_data levels)
--   10 chks_header_data : the 10 NADA Road to Sale steps (order_no 1-10)
--   16 chks_questions   : audit checkpoints, one per NADA cue
--   16 chks_question_results : one result record per question (SUBJECTIVE_CONDITION)
--   32 chks_question_result_options : OK + "Not OK" for every result
--   1 audit (campaign) : "Road to Sale – Walk-In", linked to the checksheet
--
-- Domain model reminder (from CLAUDE.md):
--   checksheets           → the reusable template
--   chks_headers          → column headers (e.g. "Condition")
--   chks_header_data      → hierarchical rows (Zone → Category → Element)
--   chks_questions        → the individual audit checkpoint
--   chks_question_results → how the question is answered (answer_type)
--   chks_question_result_options → the selectable options + their judgement
--   audits                → a campaign that references the checksheet
--
-- ID ranges used:
--   checksheets             : 2001
--   chks_headers            : 2001
--   chks_header_data        : 2001-2010   (10 NADA steps)
--   chks_questions          : 2001-2016   (16 checkpoints)
--   chks_question_results   : 2001-2016   (1 per question)
--   chks_question_result_options : 2001-2032 (2 per result)
--   audits                  : 2001
-- ============================================================================

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Checksheet template
--    status = APPROVED so the app treats it as ready-to-use without needing
--    to run the validate → approve workflow on a fresh demo DB.
--    checksheet_type = PRIVATE (default; only the Honda tenant sees it).
--    created_by = 1 (system user, always present after Flyway baseline).
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO checksheets (
    id, name, status, checksheet_type,
    version, validate_or_approve_version,
    uid, serial_number,
    waiting_user_ids, npd_day,
    created_at, created_by
)
VALUES (
    2001,
    'NADA Road to Sale',
    'APPROVED',
    'PRIVATE',
    1, 0,
    'RTS_HONDA_V1', 'RTS001',
    '{}', '{}',
    CURRENT_TIMESTAMP, 1
)
ON CONFLICT (id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Column header
--    chks_headers is the header definition row for the checksheet's answer
--    columns. NADA RTS uses a single result column ("Condition").
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO chks_headers (
    id, name, checksheet_id, is_result_column, is_traceable,
    created_at, created_by
)
VALUES (
    2001, 'Condition', 2001, TRUE, FALSE,
    CURRENT_TIMESTAMP, 1
)
ON CONFLICT (id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 3. NADA Road to Sale steps → chks_header_data rows
--    level=1 → top-level step (no parent chks_header_data_id).
--    order_no follows NADA sequence (1-10).
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO chks_header_data (id, name, level, order_no, checksheet_id, chks_header_id, chks_header_data_id, created_at, created_by)
VALUES
    (2001, 'Greet / Hospitality',          1, 1,  2001, 2001, NULL, CURRENT_TIMESTAMP, 1),
    (2002, 'Discovery',                    1, 2,  2001, 2001, NULL, CURRENT_TIMESTAMP, 1),
    (2003, 'Vehicle Match / Recommendation', 1, 3, 2001, 2001, NULL, CURRENT_TIMESTAMP, 1),
    (2004, 'Front-Line Ready',             1, 4,  2001, 2001, NULL, CURRENT_TIMESTAMP, 1),
    (2005, 'Walkaround',                   1, 5,  2001, 2001, NULL, CURRENT_TIMESTAMP, 1),
    (2006, 'Test Drive',                   1, 6,  2001, 2001, NULL, CURRENT_TIMESTAMP, 1),
    (2007, 'Trade Appraisal',              1, 7,  2001, 2001, NULL, CURRENT_TIMESTAMP, 1),
    (2008, 'Proposal / Pencil',            1, 8,  2001, 2001, NULL, CURRENT_TIMESTAMP, 1),
    (2009, 'F&I Handoff',                  1, 9,  2001, 2001, NULL, CURRENT_TIMESTAMP, 1),
    (2010, 'Completion',                   1, 10, 2001, 2001, NULL, CURRENT_TIMESTAMP, 1)
ON CONFLICT (id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 4. Checkpoints → chks_questions
--    name = the auditable question text that the rep (operator) answers.
--    chks_header_data_id = the NADA step the checkpoint belongs to.
--    order_no = display order within the step.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO chks_questions (
    id, name, order_no,
    checksheet_id, chks_header_id, chks_header_data_id,
    created_at, created_by
)
VALUES
    -- Greet / Hospitality (step 2001)
    (2001, 'Did the salesperson greet the customer within 30 seconds of arrival?',  1, 2001, 2001, 2001, CURRENT_TIMESTAMP, 1),
    (2002, 'Did the salesperson introduce themselves by name?',                      2, 2001, 2001, 2001, CURRENT_TIMESTAMP, 1),
    (2003, 'Was the customer offered hospitality (coffee, water, or a drink)?',      3, 2001, 2001, 2001, CURRENT_TIMESTAMP, 1),

    -- Discovery (step 2002)
    (2004, 'Did the salesperson conduct a thorough needs discovery (family, commute, usage)?',    1, 2001, 2001, 2002, CURRENT_TIMESTAMP, 1),
    (2005, 'Did the salesperson identify the customer''s budget or payment preference?',          2, 2001, 2001, 2002, CURRENT_TIMESTAMP, 1),
    (2006, 'Did the salesperson probe for lifestyle requirements (school runs, highway, towing)?', 3, 2001, 2001, 2002, CURRENT_TIMESTAMP, 1),

    -- Vehicle Match / Recommendation (step 2003)
    (2007, 'Did the salesperson make a vehicle recommendation with clear reasoning?', 1, 2001, 2001, 2003, CURRENT_TIMESTAMP, 1),

    -- Front-Line Ready (step 2004)
    (2008, 'Was the vehicle presented as front-line ready (clean, fuelled, full features operational)?', 1, 2001, 2001, 2004, CURRENT_TIMESTAMP, 1),

    -- Walkaround (step 2005)
    (2009, 'Did the salesperson conduct a structured exterior walkaround?',           1, 2001, 2001, 2005, CURRENT_TIMESTAMP, 1),
    (2010, 'Did the salesperson present interior features during the walkaround?',    2, 2001, 2001, 2005, CURRENT_TIMESTAMP, 1),

    -- Test Drive (step 2006)
    (2011, 'Was the customer offered and taken on a test drive?',                     1, 2001, 2001, 2006, CURRENT_TIMESTAMP, 1),

    -- Trade Appraisal (step 2007)
    (2012, 'Was the customer''s trade-in vehicle inspected with condition notes?',    1, 2001, 2001, 2007, CURRENT_TIMESTAMP, 1),
    (2013, 'Were trade-in tires and exterior condition assessed and noted?',          2, 2001, 2001, 2007, CURRENT_TIMESTAMP, 1),

    -- Proposal / Pencil (step 2008)
    (2014, 'Was a financial proposal (pencil) presented to the customer?',            1, 2001, 2001, 2008, CURRENT_TIMESTAMP, 1),

    -- F&I Handoff (step 2009)
    (2015, 'Was the customer introduced to the Finance & Insurance team?',            1, 2001, 2001, 2009, CURRENT_TIMESTAMP, 1),

    -- Completion (step 2010)
    (2016, 'Was the sale completed or a clear next step agreed with the customer?',   1, 2001, 2001, 2010, CURRENT_TIMESTAMP, 1)
ON CONFLICT (id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 5. Question results → chks_question_results
--    answer_type = SUBJECTIVE_CONDITION for all NADA checkpoints.
--    is_optional: mandatory checkpoints = FALSE, optional = TRUE.
--    One result row per question (1:1 for SUBJECTIVE_CONDITION).
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO chks_question_results (
    id, answer_type, is_optional,
    checksheet_id, chks_header_id, chks_question_id,
    created_at, created_by
)
VALUES
    -- Greet / Hospitality — all mandatory
    (2001, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2001, CURRENT_TIMESTAMP, 1),
    (2002, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2002, CURRENT_TIMESTAMP, 1),
    (2003, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2003, CURRENT_TIMESTAMP, 1),

    -- Discovery — 2 mandatory, 1 optional
    (2004, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2004, CURRENT_TIMESTAMP, 1),
    (2005, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2005, CURRENT_TIMESTAMP, 1),
    (2006, 'SUBJECTIVE_CONDITION', TRUE,  2001, 2001, 2006, CURRENT_TIMESTAMP, 1),

    -- Vehicle Match / Recommendation — mandatory
    (2007, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2007, CURRENT_TIMESTAMP, 1),

    -- Front-Line Ready — mandatory
    (2008, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2008, CURRENT_TIMESTAMP, 1),

    -- Walkaround — both mandatory
    (2009, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2009, CURRENT_TIMESTAMP, 1),
    (2010, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2010, CURRENT_TIMESTAMP, 1),

    -- Test Drive — mandatory
    (2011, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2011, CURRENT_TIMESTAMP, 1),

    -- Trade Appraisal — both optional (trade-in not always present)
    (2012, 'SUBJECTIVE_CONDITION', TRUE,  2001, 2001, 2012, CURRENT_TIMESTAMP, 1),
    (2013, 'SUBJECTIVE_CONDITION', TRUE,  2001, 2001, 2013, CURRENT_TIMESTAMP, 1),

    -- Proposal / Pencil — mandatory
    (2014, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2014, CURRENT_TIMESTAMP, 1),

    -- F&I Handoff — mandatory
    (2015, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2015, CURRENT_TIMESTAMP, 1),

    -- Completion — mandatory
    (2016, 'SUBJECTIVE_CONDITION', FALSE, 2001, 2001, 2016, CURRENT_TIMESTAMP, 1)
ON CONFLICT (id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 6. Result options — OK + Not OK for every question result
--    judgement values match the system convention: 'OK' and 'NOT OK'
--    (confirmed from DashboardDAO.java and the BI CTE in CLAUDE.md).
--    The integer judgement on user_checksheet_answers maps:
--      1 = OK (detected / covered)
--      2 = NOT OK (missed)
--    The string judgement on chks_question_result_options drives the
--    option display label ("OK" / "Not OK") and the derived pct_ok rollup.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO chks_question_result_options (
    id, option, judgement,
    chks_question_result_id, checksheet_id,
    created_at, created_by
)
VALUES
    -- Q1 (result 2001)
    (2001, 'OK',     'OK',     2001, 2001, CURRENT_TIMESTAMP, 1),
    (2002, 'Not OK', 'NOT OK', 2001, 2001, CURRENT_TIMESTAMP, 1),
    -- Q2 (result 2002)
    (2003, 'OK',     'OK',     2002, 2001, CURRENT_TIMESTAMP, 1),
    (2004, 'Not OK', 'NOT OK', 2002, 2001, CURRENT_TIMESTAMP, 1),
    -- Q3 (result 2003)
    (2005, 'OK',     'OK',     2003, 2001, CURRENT_TIMESTAMP, 1),
    (2006, 'Not OK', 'NOT OK', 2003, 2001, CURRENT_TIMESTAMP, 1),
    -- Q4 (result 2004)
    (2007, 'OK',     'OK',     2004, 2001, CURRENT_TIMESTAMP, 1),
    (2008, 'Not OK', 'NOT OK', 2004, 2001, CURRENT_TIMESTAMP, 1),
    -- Q5 (result 2005)
    (2009, 'OK',     'OK',     2005, 2001, CURRENT_TIMESTAMP, 1),
    (2010, 'Not OK', 'NOT OK', 2005, 2001, CURRENT_TIMESTAMP, 1),
    -- Q6 (result 2006)
    (2011, 'OK',     'OK',     2006, 2001, CURRENT_TIMESTAMP, 1),
    (2012, 'Not OK', 'NOT OK', 2006, 2001, CURRENT_TIMESTAMP, 1),
    -- Q7 (result 2007)
    (2013, 'OK',     'OK',     2007, 2001, CURRENT_TIMESTAMP, 1),
    (2014, 'Not OK', 'NOT OK', 2007, 2001, CURRENT_TIMESTAMP, 1),
    -- Q8 (result 2008)
    (2015, 'OK',     'OK',     2008, 2001, CURRENT_TIMESTAMP, 1),
    (2016, 'Not OK', 'NOT OK', 2008, 2001, CURRENT_TIMESTAMP, 1),
    -- Q9 (result 2009)
    (2017, 'OK',     'OK',     2009, 2001, CURRENT_TIMESTAMP, 1),
    (2018, 'Not OK', 'NOT OK', 2009, 2001, CURRENT_TIMESTAMP, 1),
    -- Q10 (result 2010)
    (2019, 'OK',     'OK',     2010, 2001, CURRENT_TIMESTAMP, 1),
    (2020, 'Not OK', 'NOT OK', 2010, 2001, CURRENT_TIMESTAMP, 1),
    -- Q11 (result 2011)
    (2021, 'OK',     'OK',     2011, 2001, CURRENT_TIMESTAMP, 1),
    (2022, 'Not OK', 'NOT OK', 2011, 2001, CURRENT_TIMESTAMP, 1),
    -- Q12 (result 2012)
    (2023, 'OK',     'OK',     2012, 2001, CURRENT_TIMESTAMP, 1),
    (2024, 'Not OK', 'NOT OK', 2012, 2001, CURRENT_TIMESTAMP, 1),
    -- Q13 (result 2013)
    (2025, 'OK',     'OK',     2013, 2001, CURRENT_TIMESTAMP, 1),
    (2026, 'Not OK', 'NOT OK', 2013, 2001, CURRENT_TIMESTAMP, 1),
    -- Q14 (result 2014)
    (2027, 'OK',     'OK',     2014, 2001, CURRENT_TIMESTAMP, 1),
    (2028, 'Not OK', 'NOT OK', 2014, 2001, CURRENT_TIMESTAMP, 1),
    -- Q15 (result 2015)
    (2029, 'OK',     'OK',     2015, 2001, CURRENT_TIMESTAMP, 1),
    (2030, 'Not OK', 'NOT OK', 2015, 2001, CURRENT_TIMESTAMP, 1),
    -- Q16 (result 2016)
    (2031, 'OK',     'OK',     2016, 2001, CURRENT_TIMESTAMP, 1),
    (2032, 'Not OK', 'NOT OK', 2016, 2001, CURRENT_TIMESTAMP, 1)
ON CONFLICT (id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 7. Walk-in audit campaign
--    A standing campaign that uses the NADA checksheet. Field reps create
--    inspections against this audit for every walk-in customer.
--    status = ACTIVE, start = today, end = 1 year from today.
--    The location linkage (audit → auditee_location) is established by
--    creating an inspection row via the API; no direct audit↔location FK
--    exists in the schema — audits.checksheet_id is the only template FK.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO audits (
    id, name, checksheet_id, status,
    start_date, end_date,
    created_at, created_by
)
VALUES (
    2001,
    'Road to Sale – Walk-In',
    2001,
    'ACTIVE',
    CURRENT_DATE,
    CURRENT_DATE + INTERVAL '1 year',
    CURRENT_TIMESTAMP, 1
)
ON CONFLICT (id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- 8. Bootstrap inspection for honda_rep1 at Honda Demo Showroom
--    This gives the demo rep one assigned inspection to log into immediately.
--    status = ASSIGNED; the rep opens it and it moves to IN_PROGRESS on first save.
--    created_by = 1 (system bootstrap). operator_user_id = user 21 (honda_rep1).
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO inspections (
    kind, audit_id, intervention_id,
    auditee_location_id,
    operator_user_id,
    dealer_principal_user_id, region_owner_user_id,
    checksheet_id,
    status, submission_version,
    created_at, created_by
)
SELECT
    'AUDIT', 2001, NULL,
    2001,                                              -- Honda Demo Showroom
    (SELECT id FROM users WHERE username = 'honda_rep1'),
    NULL, NULL,
    2001,
    'ASSIGNED', 0,
    CURRENT_TIMESTAMP, 1
WHERE NOT EXISTS (
    SELECT 1 FROM inspections
    WHERE audit_id = 2001
      AND auditee_location_id = 2001
      AND deleted_at IS NULL
);


-- ─────────────────────────────────────────────────────────────────────────────
-- 9. Advance sequences
-- ─────────────────────────────────────────────────────────────────────────────
SELECT setval('checksheets_id_seq',                   GREATEST(3000, (SELECT MAX(id) FROM checksheets)                   + 1), false);
SELECT setval('chks_headers_id_seq',                  GREATEST(3000, (SELECT MAX(id) FROM chks_headers)                  + 1), false);
SELECT setval('chks_header_data_id_seq',              GREATEST(3000, (SELECT MAX(id) FROM chks_header_data)              + 1), false);
SELECT setval('chks_questions_id_seq',                GREATEST(3000, (SELECT MAX(id) FROM chks_questions)                + 1), false);
SELECT setval('chks_question_results_id_seq',         GREATEST(3000, (SELECT MAX(id) FROM chks_question_results)         + 1), false);
SELECT setval('chks_question_result_options_id_seq',  GREATEST(3000, (SELECT MAX(id) FROM chks_question_result_options)  + 1), false);
SELECT setval('audits_id_seq',                        GREATEST(3000, (SELECT MAX(id) FROM audits)                        + 1), false);
SELECT setval('inspections_id_seq',                   GREATEST(3000, (SELECT MAX(id) FROM inspections)                   + 1), false);

COMMIT;

-- ─────────────────────────────────────────────────────────────────────────────
-- Verification
-- ─────────────────────────────────────────────────────────────────────────────
SELECT
    cs.name      AS checksheet,
    cs.status    AS chk_status,
    COUNT(DISTINCT hd.id) AS steps,
    COUNT(DISTINCT q.id)  AS questions,
    COUNT(DISTINCT qr.id) AS results,
    COUNT(DISTINCT opt.id) AS options
FROM checksheets cs
JOIN chks_header_data hd    ON hd.checksheet_id = cs.id
JOIN chks_questions q       ON q.checksheet_id  = cs.id
JOIN chks_question_results qr  ON qr.checksheet_id = cs.id
JOIN chks_question_result_options opt ON opt.checksheet_id = cs.id
WHERE cs.id = 2001
GROUP BY cs.name, cs.status;
