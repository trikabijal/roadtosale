# POST-V1.28 NOTE: this script was authored against the pre-collapse schema
# (separate audit_assignments / intervention_assignments / user_checksheets
# tables). After V1.28 the three collapsed into a single 'inspections' table.
# This file still has many SQL statements referencing the old names. Rewrite
# before next seed run, OR seed from a UAT pg_dump of the post-V1.28 DB.

#!/usr/bin/env python3
"""
Add 1-2 extra physical sites to a small set of existing Kia dealers + spin up
audits at the new sites.

After V1.25 collapsed every duplicate-address row in auditee_locations, every
dealer ended up at exactly one physical location. That's accurate for most of
the network, but it leaves the BI's dealer→location drill empty — there's no
case to click through.

This seeder picks a handful of existing dealers and gives them realistic
secondary locations (a sales+service split: Showroom in one part of town,
Service Centre in another), then attaches audit_assignments + APPROVED
inspections at the new locations so the dealer's roll-up shows >1 row.

Run: python3 dev/seed-multi-location.py
"""
import hashlib
import os
import random
from datetime import datetime, timedelta
import psycopg2

DB = dict(
    host=os.environ.get("DB_HOST", "localhost"),
    port=int(os.environ.get("DB_PORT", "5432")),
    dbname=os.environ.get("DB_NAME", "smartcomply"),
    user=os.environ.get("DB_USER", "bijalsanghavi"),
    password=os.environ.get("DB_PASS", ""),
)
AUDIT_ID = 10
DEFAULT_USER_ID = 169  # KIA_DEMO_AUDITOR_001
OPERATOR_USER_IDS = [169, 170, 171, 172, 173]  # demo auditors

# (auditee_id, [(suffix_label, address_template, city_id_offset)])
# Each tuple becomes a new auditee_locations row tied to the same auditee.
MULTI_LOC_PLAN = [
    # Sahib Kia, Gorakhpur — already 1 location, add Service Centre + Body Shop
    (1517, [
        ("Service Centre", "Plot No. 42, Industrial Estate, GIDC Bharuch, Bharuch, Gujarat - 392001"),
        ("Body Shop",      "Survey No. 88, Hajipur Road, Bharuch Industrial Area, Bharuch, Gujarat - 392011"),
    ]),
    (1079, [
        ("Service Centre", "Plot 71-A, Patia Industrial Area, Bhubaneswar, Odisha - 751024"),
        ("Pre-owned Showroom", "Survey No. 244, Patrapada, Bhubaneswar, Odisha - 751019"),
    ]),
    (1049, [
        ("Service Centre", "Survey No. 612, Manpur Industrial Estate, Gaya, Bihar - 823001"),
    ]),
    (1359, [
        ("Service Centre", "No. 14/3, Kogilu Cross, Yelahanka, Bangalore, Karnataka - 560064"),
        ("Pre-owned Showroom", "No. 87, Bellary Road, Jakkur, Bangalore, Karnataka - 560064"),
    ]),
    (1430, [
        ("Service Centre", "Plot No. 15/2, GST Road, Tambaram, Chennai, Tamilnadu - 600045"),
    ]),
]


def main() -> None:
    conn = psycopg2.connect(**DB)
    conn.autocommit = False
    cur = conn.cursor()

    # Map auditee_id → city_id (use the same city as the existing primary
    # location, so cities/states/regions roll up correctly).
    cur.execute("""
        SELECT auditee_id, MIN(city_id), MIN(auditee_type_id)
          FROM auditee_locations
         WHERE auditee_id = ANY(%s) AND deleted_at IS NULL
         GROUP BY auditee_id
    """, ([a for a, _ in MULTI_LOC_PLAN],))
    ctx = {row[0]: (row[1], row[2]) for row in cur.fetchall()}

    new_assignment_ids = []
    for auditee_id, locations in MULTI_LOC_PLAN:
        if auditee_id not in ctx:
            print(f"  ! auditee {auditee_id} not found, skipping")
            continue
        city_id, auditee_type_id = ctx[auditee_id]

        for label, address in locations:
            # Insert new physical site for this dealer.
            # Pin code is required NOT NULL — extract from the address tail
            # if present, else default. Format examples we see in seed data:
            # "..., Bharuch, Gujarat - 392001"
            import re
            pin_match = re.search(r'(\d{6})', address)
            pin_code = pin_match.group(1) if pin_match else '000000'
            cur.execute("""
                INSERT INTO auditee_locations
                  (auditee_id, address, pin_code, city_id, auditee_type_id,
                   created_by, created_at)
                VALUES (%s, %s, %s, %s, %s, %s, CURRENT_TIMESTAMP)
                RETURNING id
            """, (auditee_id, address, pin_code, city_id, auditee_type_id, DEFAULT_USER_ID))
            new_loc_id = cur.fetchone()[0]

            # Attach this site to the audit campaign.
            operator = random.choice(OPERATOR_USER_IDS)
            cur.execute("""
                INSERT INTO audit_assignments
                  (audit_id, auditee_location_id, operator_user_id,
                   created_by, created_at)
                VALUES (%s, %s, %s, %s, CURRENT_TIMESTAMP)
                RETURNING id
            """, (AUDIT_ID, new_loc_id, operator, DEFAULT_USER_ID))
            aa_id = cur.fetchone()[0]
            new_assignment_ids.append((auditee_id, new_loc_id, aa_id, label))
            print(f"  + auditee {auditee_id}  →  {label}  loc={new_loc_id} aa={aa_id} op={operator}")

    print(f"\n{len(new_assignment_ids)} new (location, assignment) pairs created")

    # Now create an APPROVED user_checksheet for each new assignment, with
    # a deterministic OK/Not OK mix per question. We share the existing
    # answer-set template by copying from a "donor" UC for the same checksheet.
    # The donor is the most-recent APPROVED UC at the same auditee — keeps
    # category/element scoring shapes plausible across a dealer's locations.

    for auditee_id, new_loc_id, aa_id, label in new_assignment_ids:
        # Find a donor UC (any APPROVED UC at the same dealer, currently 1 each).
        cur.execute("""
            SELECT uc.id
              FROM inspections uc
              JOIN audit_assignments aa ON aa.id = uc.audit_assignment_id
              JOIN auditee_locations aloc ON aloc.id = aa.auditee_location_id
             WHERE aloc.auditee_id = %s
               AND uc.status = 'APPROVED'
               AND uc.deleted_at IS NULL
             LIMIT 1
        """, (auditee_id,))
        row = cur.fetchone()
        if not row:
            print(f"  ! auditee {auditee_id} has no donor UC, skipping fill")
            continue
        donor_uc_id = row[0]

        # Pick the audit's checksheet for the new UC.
        cur.execute("SELECT checksheet_id FROM audits WHERE id=%s", (AUDIT_ID,))
        checksheet_id = cur.fetchone()[0]

        # Stagger started/submitted dates 1-3 weeks after the donor's date so
        # the BI's "last audited" column shows the secondary site as more
        # recent (or older — we'll randomise).
        cur.execute("SELECT started_at, submitted_at FROM inspections WHERE id=%s", (donor_uc_id,))
        donor_started, donor_submitted = cur.fetchone()
        offset_days = random.choice([-30, -15, +14, +28])
        new_started = (donor_started or datetime.utcnow()) + timedelta(days=offset_days)
        new_submitted = new_started + timedelta(days=random.randint(1, 3))

        cur.execute("""
            INSERT INTO inspections
              (audit_assignment_id, checksheet_id, status, shift,
               started_at, submitted_at, submission_version,
               frequency_of_freq_of_chk_cnt,
               created_by, created_at)
            VALUES (%s, %s, 'APPROVED', 'First', %s, %s, 0, 1, %s, CURRENT_TIMESTAMP)
            RETURNING id
        """, (aa_id, checksheet_id, new_started, new_submitted, DEFAULT_USER_ID))
        new_uc_id = cur.fetchone()[0]

        # Copy the donor's answers — but flip a deterministic ~15% slice so the
        # secondary site doesn't score identically to the primary. Salt by
        # (auditee_id, new_loc_id) so each new site gets a different mix.
        cur.execute("""
            SELECT id, chks_question_id, chks_question_result_id,
                   chks_question_rslt_option_id, judgement, answer
              FROM user_checksheet_answers
             WHERE inspection_id = %s AND deleted_at IS NULL
        """, (donor_uc_id,))
        donor_rows = cur.fetchall()

        for r in donor_rows:
            d_id, q_id, qr_id, opt_id, judgement, answer = r
            # Decide flip via hash so the seed is reproducible.
            h = int(hashlib.md5(f"{new_loc_id}|{q_id}".encode()).hexdigest(), 16)
            flip = (h % 100) < 15
            new_judgement = (3 - judgement) if flip and judgement in (1, 2) else judgement
            # If we flipped, swap the option id to the matching judgement.
            new_opt_id = opt_id
            if flip and opt_id is not None:
                cur.execute("""
                    SELECT id FROM chks_question_result_options
                     WHERE chks_question_result_id = %s
                       AND LOWER(judgement) = %s
                       AND deleted_at IS NULL
                     ORDER BY id LIMIT 1
                """, (qr_id, 'ok' if new_judgement == 1 else 'not ok'))
                opt_row = cur.fetchone()
                if opt_row:
                    new_opt_id = opt_row[0]

            cur.execute("""
                INSERT INTO user_checksheet_answers
                  (inspection_id, chks_question_id, chks_question_result_id,
                   chks_question_rslt_option_id, judgement, answer,
                   is_not_applicable, answered_at, created_by, created_at)
                VALUES (%s, %s, %s, %s, %s, %s, FALSE, %s, %s, CURRENT_TIMESTAMP)
            """, (new_uc_id, q_id, qr_id, new_opt_id, new_judgement, answer,
                  new_started + timedelta(minutes=random.randint(5, 240)),
                  DEFAULT_USER_ID))
        print(f"    UC {new_uc_id} created at {label} ({len(donor_rows)} answers, {sum(1 for r in donor_rows if (int(hashlib.md5(f'{new_loc_id}|{r[1]}'.encode()).hexdigest(), 16) % 100) < 15)} flipped)")

    conn.commit()

    # Verify
    cur.execute("""
        SELECT a.id, a.name, COUNT(DISTINCT aloc.id) AS locs
          FROM auditees a
          JOIN auditee_locations aloc ON aloc.auditee_id = a.id AND aloc.deleted_at IS NULL
         WHERE a.id = ANY(%s)
         GROUP BY a.id, a.name ORDER BY locs DESC, a.name
    """, ([a for a, _ in MULTI_LOC_PLAN],))
    print("\nDealer → location count after seed:")
    for r in cur.fetchall():
        print(f"  {r[0]:5d}  {r[1]:50s}  {r[2]} locations")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
