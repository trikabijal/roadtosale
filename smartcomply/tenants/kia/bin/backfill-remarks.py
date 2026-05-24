# POST-V1.28 NOTE: this script was authored against the pre-collapse schema
# (separate audit_assignments / intervention_assignments / user_checksheets
# tables). After V1.28 the FK column 'user_checksheet_id' on child tables
# (user_checksheet_answers, usr_chksheet_ans_judgements, etc.) was renamed
# to 'inspection_id'. SQL in this file references the old column name.
# Rewrite before running, OR run against a pre-V1.28 DB snapshot.

#!/usr/bin/env python3
"""
One-shot backfill: for every (user_checksheet, chks_question) where the answer
row carries a judgement but no usr_chksheet_ans_judgements record exists,
insert one with a contextually-relevant remark + the matching judgement string.

Why a separate script and not SQL:
- Remarks need to be question-aware AND varied across audits. Encoding 50+
  per-element variant lists in raw SQL CASE statements is gnarly to maintain.
- Picking a variant per uca.id is straightforward in Python.

Picks variants deterministically by hash(uc_id, question_id) so the same UC
gets the same remark every run, but two different UCs against the same
question get different remarks. The audit Excel's "Option 1" / "Option 2"
text is the natural source of truth — remarks riff on that wording in
several plausible auditor voices.

Run:  python3 dev/backfill-remarks.py
"""
import hashlib
import os
import psycopg2

DB = dict(
    host=os.environ.get("DB_HOST", "localhost"),
    port=int(os.environ.get("DB_PORT", "5432")),
    dbname=os.environ.get("DB_NAME", "smartcomply"),
    user=os.environ.get("DB_USER", "bijalsanghavi"),
    password=os.environ.get("DB_PASS", ""),
)
DEFAULT_USER_ID = 169  # KIA_DEMO_AUDITOR_001 — used as created_by

# ── Remark variants by checkpoint family ───────────────────────────────────
# Keys are the lowercase checkpoint name (q.name). When a checkpoint isn't
# in the table, we fall back to GENERIC_REMARKS keyed on the answer judgement.
# Every variant list MUST have ≥3 entries so the picker has room to vary.
#
# Wording style: short, observational, in an auditor's voice. NOT-OK remarks
# stay specific to the checkpoint (paint, lights, signage, etc.) so we never
# end up with "chairs broken" on a damage check.

REMARKS = {
    # ── exterior — front fascia ───────────────────────────────────────────
    "damage": {
        "OK": [
            "Front panel intact, finish uniform, no visible defects.",
            "No dents or scratches observed on inspection.",
            "Surface in good condition, edges clean and even.",
            "Panel free of damage, paint finish consistent across face.",
            "Inspected from 2m and at close range — no damage flagged.",
        ],
        "NOT OK": [
            "Visible scratch on the lower-left edge, ~5 cm long.",
            "Hairline dent observed near the centre of the panel.",
            "Paint chipping at the right edge, base material exposed.",
            "Two impact marks above logo line, looks recent.",
            "Surface dent on upper-right quadrant, needs touch-up.",
        ],
    },
    "cleanliness": {
        "OK": [
            "Surface clean, no dust or streaks visible.",
            "Recently cleaned — no smudges or marks.",
            "Cleanliness up to standard, finish dust-free.",
            "Clean across the inspected area, no debris.",
            "Polished and free of streaking.",
        ],
        "NOT OK": [
            "Heavy dust accumulation across the surface.",
            "Streaks and water marks visible after recent rain.",
            "Bird droppings on the upper edge, not cleaned.",
            "Stained patches present, cleaning overdue.",
            "Visible smudges and dirt build-up across the face.",
        ],
    },
    "lighting": {  # OBJECTIVE — count of non-functional lights
        "OK": [
            "All lighting units operational at time of audit.",
            "Lights tested, none flickering or dim.",
            "Lighting array fully functional.",
            "No bulbs out, illumination uniform across the fixture.",
            "Inspected after dusk — all lights working.",
        ],
        "NOT OK": [
            "Multiple lights out — needs bulb replacement.",
            "Flickering observed in two of the units.",
            "One unit non-functional, electrical fault suspected.",
            "Several lights dim or completely out.",
            "Lighting partial — half the fixture is dark.",
        ],
    },
    # ── pylon ─────────────────────────────────────────────────────────────
    "visibility": {
        "OK": [
            "Pylon clearly visible from approach road.",
            "Visible from 100m approach, no obstructions.",
            "Sightline clean, sign reads clearly from the main road.",
            "No trees or signage blocking the pylon.",
            "Visibility unobstructed in both directions.",
        ],
        "NOT OK": [
            "Pylon obstructed by overgrown trees on the approach.",
            "Visibility limited — adjacent banners block view.",
            "Sign partially hidden by parked vehicles.",
            "Signage faded, hard to read from the road.",
            "Visibility from south approach is poor.",
        ],
    },
    # ── signage ────────────────────────────────────────────────────────────
    "directional clarity": {
        "OK": [
            "Directional signage clear and correctly oriented.",
            "Arrows consistent with on-ground layout.",
            "Wayfinding logical, no contradictions noted.",
            "Signs placed at decision points as expected.",
            "Customer flow well-supported by current signage.",
        ],
        "NOT OK": [
            "Arrow points the wrong way at the entry junction.",
            "Signage missing at a key decision point.",
            "Conflicting directional cues observed.",
            "Wayfinding incomplete past the reception turn.",
            "Sign legibility poor due to faded print.",
        ],
    },
    "signage visibility": {
        "OK": [
            "Valet signage prominently visible at the entry.",
            "Sign well-lit and clearly readable from the drop-off.",
            "Signage visible from approach, no obstructions.",
            "Valet board correctly placed and clean.",
            "Visibility good in daylight and after dark.",
        ],
        "NOT OK": [
            "Valet sign missing — replaced with a paper notice.",
            "Sign obscured by a plant pot near the entry.",
            "Valet board faded, text barely legible.",
            "Signage placed too low — hidden behind cars.",
            "No valet signage visible at the drop-off point.",
        ],
    },
    # ── personnel ──────────────────────────────────────────────────────────
    "uniform": {
        "OK": [
            "Guard in full uniform with name badge.",
            "Uniform clean, pressed, and complete.",
            "All personnel in correct attire as per standard.",
            "Uniform compliant with brand guidelines.",
            "Inspected on shift change — both guards in uniform.",
        ],
        "NOT OK": [
            "Guard wearing partial uniform — cap missing.",
            "Uniform crumpled and stained, not presentable.",
            "Name badge not worn at time of inspection.",
            "Footwear non-compliant — sneakers instead of formal shoes.",
            "Uniform shirt visibly dirty.",
        ],
    },
    "grooming": {
        "OK": [
            "Personnel well-groomed, hair trimmed.",
            "Clean-shaven and presentable.",
            "Grooming meets brand expectation.",
            "Hair, nails, and overall appearance acceptable.",
            "Personnel look professional on the floor.",
        ],
        "NOT OK": [
            "Unshaven and hair untidy at start of shift.",
            "Personal grooming below standard.",
            "Hair visibly unkempt, needs grooming attention.",
            "Beard not trimmed per standard.",
            "Overall presentation needs improvement.",
        ],
    },
    "as per norms": {
        "OK": [
            "Uniform compliant with Kia norms across the team.",
            "All staff in approved attire.",
            "Uniform standards being followed without exception.",
            "Floor staff observed in correct uniform.",
            "No uniform deviations noted.",
        ],
        "NOT OK": [
            "Two staff members observed in non-Kia attire.",
            "Uniform colour mismatch — using older variant.",
            "Brand badge missing on multiple staff.",
            "Staff wearing personal jackets over uniform.",
            "Uniform not consistent across the shift.",
        ],
    },
    # ── facade / structure ─────────────────────────────────────────────────
    "cracks": {
        "OK": [
            "Granite intact, no cracks observed.",
            "Surface inspected from multiple angles — clean.",
            "No structural cracks or chips on the granite.",
            "Granite finish smooth, no fault lines.",
            "Inspection passed for structural integrity.",
        ],
        "NOT OK": [
            "Hairline crack observed along the central panel.",
            "Visible chip near the base of the granite.",
            "Cracking propagating from the corner.",
            "Multiple cracks across the surface — needs repair.",
            "Spider crack near the joint, structural concern.",
        ],
    },
    "black paint compliance": {
        "OK": [
            "Gates uniformly painted black per Kia standard.",
            "Paint finish consistent and compliant.",
            "Gate paintwork up to standard.",
            "Black colour matches Kia spec across the gates.",
            "Paint inspection passed for the entry gates.",
        ],
        "NOT OK": [
            "Gates show rust patches — re-paint due.",
            "Paint colour deviates from Kia spec — too matte.",
            "Peeling observed near the hinges.",
            "Two gate panels in wrong colour — likely repaired.",
            "Paint chipped at the base of both gates.",
        ],
    },
    # ── interior — flooring ────────────────────────────────────────────────
    "no clutter": {
        "OK": [
            "Delivery area clear of clutter.",
            "Workspace organised, no obstructions.",
            "Area clean and ready for handover.",
            "No items stored in the delivery zone.",
            "Floor clear, ready for next delivery.",
        ],
        "NOT OK": [
            "Boxes stacked in the delivery area, obstructing flow.",
            "Spare parts dumped along the wall — should be in store.",
            "Multiple items stored in handover space.",
            "Delivery area being used for general storage.",
            "Cluttered with promotional material and packaging.",
        ],
    },
    # ── tech / display ─────────────────────────────────────────────────────
    "functionality": {
        "OK": [
            "Video wall fully operational, content cycling.",
            "Display functional with no glitches or freezes.",
            "All panels working, content rendering correctly.",
            "Video wall tested — no issues observed.",
            "Display performance up to standard.",
        ],
        "NOT OK": [
            "Two panels of the video wall are dark.",
            "Display freezing every few minutes.",
            "Content not loading — likely CMS issue.",
            "Visible vertical lines across one panel.",
            "Video wall powered off at time of audit.",
        ],
    },
    "functional": {  # OBJECTIVE — count of non-functional lights/ev points
        "OK": [
            "All lights operational at time of inspection.",
            "Lighting/charging units tested — all working.",
            "Functionality check passed across all units.",
            "No faults found on operational test.",
            "All units delivering power as expected.",
        ],
        "NOT OK": [
            "Several units non-functional — service ticket needed.",
            "Two charging points not delivering power.",
            "Units flickering, electrical issue suspected.",
            "Multiple lights out across the fixture.",
            "Functional test failed on more than one unit.",
        ],
    },
    "cms": {
        "OK": [
            "CMS-approved content displayed throughout.",
            "Content matches central CMS playlist.",
            "Display content up to date with current campaign.",
            "All assets verified against CMS schedule.",
            "Content compliance confirmed.",
        ],
        "NOT OK": [
            "Older campaign still playing — CMS not synced.",
            "Local content shown instead of CMS-approved playlist.",
            "Display showing default placeholder, not branded content.",
            "Outdated promotional material on the wall.",
            "Local override active — CMS not in control.",
        ],
    },
    "logo": {
        "OK": [
            "Brand wall logo correctly placed and lit.",
            "Logo per brand standard, alignment exact.",
            "Logo finish clean, no scratches or fading.",
            "Brand wall logo compliant with current spec.",
            "Logo inspection passed.",
        ],
        "NOT OK": [
            "Logo backlight failing — half the logo dark.",
            "Logo placement skewed compared to brand spec.",
            "Logo paint chipped near the lower-left edge.",
            "Outdated logo variant still in use on brand wall.",
            "Logo discoloured — appears yellowed.",
        ],
    },
    "kia approved": {
        "OK": [
            "All furniture matches Kia-approved catalogue.",
            "Furniture compliant — colour, material, layout per spec.",
            "Catalogue inspection passed for the showroom set.",
            "Approved furniture in use across the floor.",
            "No non-approved furniture observed.",
        ],
        "NOT OK": [
            "Two non-approved chairs in use at the consultation desk.",
            "Reception sofa is older variant, not in current catalogue.",
            "Local furniture replacing approved set in waiting area.",
            "Furniture material differs from Kia spec.",
            "Wood finish deviates from approved samples.",
        ],
    },
    "kia elements": {
        "OK": [
            "Reception fully on-brand — all required Kia elements present.",
            "Brand elements correctly placed and visible.",
            "All Kia signage and accents per standard.",
            "Reception design audit passed.",
            "On-brand elements consistent with current spec.",
        ],
        "NOT OK": [
            "Reception missing the Kia welcome backwall.",
            "Older Kia branding still in use at reception.",
            "Brand accents missing — counter looks generic.",
            "Required Kia accent walls not present.",
            "Reception lacks current Kia identity elements.",
        ],
    },
    "kia standards": {
        "OK": [
            "Showroom elements compliant with Kia standards.",
            "Layout, colour, and signage all per Kia spec.",
            "Standards inspection passed across the showroom.",
            "All elements consistent with current brand guidelines.",
            "Showroom looks on-brand throughout.",
        ],
        "NOT OK": [
            "Floor layout deviates from current Kia standard.",
            "Unapproved promotional banners on display.",
            "Wall colour does not match Kia palette.",
            "Several elements look out of brand.",
            "Showroom partially refreshed — old elements still present.",
        ],
    },
    # ── miscellaneous ──────────────────────────────────────────────────────
    "infra check": {
        "OK": [
            "Conference room infra in working order — projector, AV, lighting.",
            "All AV equipment tested and functional.",
            "Conference setup compliant.",
            "Infrastructure check passed for the meeting room.",
            "Room ready for customer presentations.",
        ],
        "NOT OK": [
            "Projector bulb dim — needs replacement.",
            "AV remote not working, room hard to operate.",
            "HDMI cable missing from the conference setup.",
            "Whiteboard markers dried out, no spares.",
            "Air-conditioning unit non-functional.",
        ],
    },
    "organized": {
        "OK": [
            "Back office well-organised, files easy to locate.",
            "Workspace tidy, no loose paperwork.",
            "Filing in order, desks clear.",
            "Back office passes organisation check.",
            "Storage and workflow well-managed.",
        ],
        "NOT OK": [
            "Back office cluttered with stacked files on desks.",
            "Cabinets disorganised, papers spilling out.",
            "Loose paperwork across the workspace.",
            "Back office storage exceeds capacity.",
            "Filing system not being maintained.",
        ],
    },
    "hygiene": {
        "OK": [
            "Hygiene standards maintained — clean and odour-free.",
            "Café/washroom inspected — fully sanitary.",
            "Hygiene practices visible and consistent.",
            "Area meets cleanliness expectations.",
            "Hygiene check passed.",
        ],
        "NOT OK": [
            "Strong odour observed — needs cleaning attention.",
            "Bins overflowing, hygiene compromised.",
            "Floor sticky in the customer-facing area.",
            "Soap and tissue dispensers empty.",
            "Hygiene below standard at time of audit.",
        ],
    },
    "clean entry": {
        "OK": [
            "Entry area clean and welcoming.",
            "Floor clean, no debris near the entrance.",
            "Entry mat clean and in good condition.",
            "Customer-facing entry area well-presented.",
            "Entry inspection passed.",
        ],
        "NOT OK": [
            "Dirt and leaves accumulating at the entry.",
            "Entry mat heavily soiled.",
            "Stained patches on the entry floor.",
            "Cobwebs visible above the entry door.",
            "Entry area looks neglected.",
        ],
    },
    "clean": {
        "OK": [
            "Area clean — meets the cleanliness standard.",
            "No debris or stains observed.",
            "Floor and surfaces clean.",
            "Cleaning routine clearly being followed.",
            "Inspection passed for cleanliness.",
        ],
        "NOT OK": [
            "Visible debris and dust in the area.",
            "Stained surfaces — cleaning overdue.",
            "Floor not mopped recently.",
            "Spills and marks across the area.",
            "Cleanliness below standard at audit time.",
        ],
    },
    "alignment": {
        "OK": [
            "Display cars aligned to spec, spacing uniform.",
            "Vehicle alignment per showroom layout.",
            "Cars positioned correctly on display floor.",
            "Spacing and angle compliant with brand guidelines.",
            "Display alignment passed.",
        ],
        "NOT OK": [
            "Display cars not in line — angles off.",
            "Two vehicles parked too close together.",
            "Display layout deviates from spec.",
            "Cars not centered on the display platforms.",
            "Vehicle alignment looks ad-hoc.",
        ],
    },
    "display": {
        "OK": [
            "Accessory display tidy and complete.",
            "Display rack stocked and presented well.",
            "Accessories visible and labelled correctly.",
            "Display area meets merchandising standard.",
            "Inspection passed for accessories display.",
        ],
        "NOT OK": [
            "Accessory rack half-empty.",
            "Items mis-placed on the display.",
            "Pricing tags missing on multiple accessories.",
            "Display dusty and unattended.",
            "Accessory shelf disorganised.",
        ],
    },
    "price tags": {  # OBJECTIVE — number of accessories without tags
        "OK": [
            "All accessories carry correct price tags.",
            "Pricing visible and current on every item.",
            "No missing tags observed.",
            "Price labelling fully compliant.",
            "All accessories tagged per current price list.",
        ],
        "NOT OK": [
            "Several accessories missing price tags.",
            "Multiple items with outdated pricing.",
            "Pricing tags torn or illegible on a few items.",
            "Tags inconsistent across the display.",
            "Accessories on display without any price marking.",
        ],
    },
    "no non-kia cars": {  # OBJECTIVE
        "OK": [
            "Customer parking holds Kia vehicles only.",
            "No non-Kia cars observed in customer parking.",
            "Parking compliance maintained.",
            "Customer parking dedicated to Kia vehicles.",
            "Parking inspection passed.",
        ],
        "NOT OK": [
            "Multiple non-Kia cars in customer parking.",
            "Two non-Kia vehicles occupying customer slots.",
            "Customer parking being used by other brands.",
            "Non-compliant vehicles in dedicated Kia spots.",
            "Parking discipline lapsed at time of audit.",
        ],
    },
    "available": {
        "OK": [
            "Name badge available and worn by staff.",
            "All staff observed wearing name badges.",
            "Name badge inventory in stock at reception.",
            "Badging compliant across the team.",
            "Badge availability check passed.",
        ],
        "NOT OK": [
            "Two staff members without name badges.",
            "Name badges not in use during the shift.",
            "Stock of badges run out at reception.",
            "Badge holders empty on multiple staff.",
            "Name badging not being enforced.",
        ],
    },
    "healthy plants": {
        "OK": [
            "Plantation healthy, watered, and well-maintained.",
            "Plants in good condition across the area.",
            "Greenery looks fresh and cared for.",
            "Plantation inspection passed.",
            "All plants healthy and trimmed.",
        ],
        "NOT OK": [
            "Several plants visibly dying — needs watering.",
            "Plantation looks neglected.",
            "Dead leaves accumulating, plants not trimmed.",
            "Soil dry, plants showing stress.",
            "Multiple plant pots empty or with dead plants.",
        ],
    },
    "setup": {
        "OK": [
            "EV zone setup per Kia spec — clean and complete.",
            "Setup compliant with current EV branding.",
            "All required EV elements in place.",
            "EV zone presented well.",
            "Setup inspection passed.",
        ],
        "NOT OK": [
            "EV zone setup incomplete — branding missing.",
            "Required EV display materials absent.",
            "Setup deviates from current Kia EV standard.",
            "EV zone looks under-developed.",
            "Setup not consistent with brand guidelines.",
        ],
    },
    "wiring": {
        "OK": [
            "EV zone wiring concealed and tidy.",
            "Cables routed correctly, no exposed wiring.",
            "Wiring inspection passed.",
            "All cabling secured and labelled.",
            "Electrical setup looks safe and compliant.",
        ],
        "NOT OK": [
            "Exposed wiring visible at the EV zone.",
            "Cables tangled, presenting trip hazard.",
            "Wiring not secured to the wall — hanging loose.",
            "Cable labelling missing — hard to identify circuits.",
            "Electrical conduit broken in two places.",
        ],
    },
    "correct": {
        "OK": [
            "Signage content correct and current.",
            "Signs reflect latest brand messaging.",
            "Content audit passed for displayed signage.",
            "All signs verified against current spec.",
            "Signage messaging consistent across locations.",
        ],
        "NOT OK": [
            "Outdated signage content still in use.",
            "Sign references discontinued model.",
            "Promotional sign refers to expired campaign.",
            "Multiple signs carry incorrect information.",
            "Signage content inconsistent with current brand spec.",
        ],
    },
    "damage free": {
        "OK": [
            "Signage in good condition, no damage.",
            "Signs intact across the showroom.",
            "Signage damage check passed.",
            "All signs free of cracks, dents, or fading.",
            "Sign material in good shape.",
        ],
        "NOT OK": [
            "Sign cracked at the corner — needs replacement.",
            "Visible damage on multiple signs.",
            "Sign panel peeling and faded.",
            "Damage to sign mount — sign hanging loose.",
            "Signage shows wear and minor damage.",
        ],
    },
    "paint": {
        "OK": [
            "Wall paint in good condition, no marks.",
            "Paint finish uniform and clean.",
            "Wall paintwork compliant.",
            "Paint inspection passed.",
            "Paint colour matches Kia palette.",
        ],
        "NOT OK": [
            "Wall paint chipping in multiple spots.",
            "Scuff marks visible across the lower wall.",
            "Paint colour faded — needs touch-up.",
            "Wall paint peeling near the door frame.",
            "Watermarks visible on the wall.",
        ],
    },
    "awareness": {  # SUBJECTIVE — text answer
        "OK": [
            "Staff aware of My Kia App and able to demo it.",
            "App walkthrough confidently provided.",
            "Staff trained on My Kia App key features.",
            "All staff aware of the latest app updates.",
            "Awareness check passed.",
        ],
        "NOT OK": [
            "Staff unaware of the My Kia App features.",
            "Unable to demo the app to a customer.",
            "Staff not trained on recent app updates.",
            "Awareness gap noted across the team.",
            "App promotion not happening at the showroom.",
        ],
    },
}

# Shared fallback when a checkpoint name doesn't match any of the keys.
GENERIC = {
    "OK": [
        "Inspection passed for this checkpoint.",
        "Compliant with current standard at audit time.",
        "No issues observed for this item.",
        "Meets brand expectation as inspected.",
        "Auditor verified — checkpoint cleared.",
    ],
    "NOT OK": [
        "Checkpoint failed inspection — needs corrective action.",
        "Non-compliance noted at audit time.",
        "Below standard for this item.",
        "Issue observed — flagged for follow-up.",
        "Does not meet brand expectation as inspected.",
    ],
}


def pick_remark(checkpoint: str, judgement_str: str, salt: int) -> str:
    """Deterministic pick — same (checkpoint, judgement, salt) → same remark."""
    family = REMARKS.get(checkpoint.strip().lower(), GENERIC)
    bucket = family.get(judgement_str, GENERIC[judgement_str])
    h = int(hashlib.md5(f"{checkpoint}|{judgement_str}|{salt}".encode()).hexdigest(), 16)
    return bucket[h % len(bucket)]


def main() -> None:
    conn = psycopg2.connect(**DB)
    conn.autocommit = False
    cur = conn.cursor()

    # One judgement per (user_checksheet, chks_question) — pick best answer
    # row when multiple results share a question (subjective + spec).
    cur.execute("""
        SELECT uca.user_checksheet_id,
               uca.chks_question_id,
               q.name AS checkpoint,
               -- If ANY answer for this question is NOT OK, treat the question as NOT OK.
               -- Otherwise OK if at least one is OK.
               MAX(CASE WHEN uca.judgement = 2 THEN 2 ELSE uca.judgement END) AS final_judgement,
               MIN(uca.id) AS sample_uca_id
          FROM user_checksheet_answers uca
          JOIN chks_questions q ON q.id = uca.chks_question_id
          WHERE uca.deleted_at IS NULL
            AND uca.judgement IS NOT NULL
            AND NOT EXISTS (
              SELECT 1 FROM usr_chksheet_ans_judgements ucaj
               WHERE ucaj.user_checksheet_id = uca.user_checksheet_id
                 AND ucaj.chks_question_id = uca.chks_question_id
                 AND ucaj.deleted_at IS NULL
            )
          GROUP BY uca.user_checksheet_id, uca.chks_question_id, q.name
    """)
    rows = cur.fetchall()
    print(f"Backfilling {len(rows)} judgement rows...")

    insert_sql = """
        INSERT INTO usr_chksheet_ans_judgements
          (user_checksheet_id, chks_question_id, judgement, remarks, created_by, created_at)
        VALUES (%s, %s, %s, %s, %s, CURRENT_TIMESTAMP)
    """

    BATCH = 500
    for i, (uc_id, q_id, checkpoint, judgement_int, sample_uca_id) in enumerate(rows):
        j_str = "OK" if judgement_int == 1 else "NOT OK"
        # Salt with sample_uca_id so two audits at the same dealer-question
        # get different remark variants — keeps reports varied across audits.
        remark = pick_remark(checkpoint, j_str, sample_uca_id or i)
        cur.execute(insert_sql, (uc_id, q_id, j_str, remark, DEFAULT_USER_ID))
        if (i + 1) % BATCH == 0:
            conn.commit()
            print(f"  ...{i + 1} / {len(rows)}")

    conn.commit()
    print(f"Done. Inserted {len(rows)} usr_chksheet_ans_judgements rows.")

    # Sanity sample
    cur.execute("""
        SELECT q.name AS checkpoint, ucaj.judgement, ucaj.remarks
          FROM usr_chksheet_ans_judgements ucaj
          JOIN chks_questions q ON q.id = ucaj.chks_question_id
         WHERE ucaj.user_checksheet_id = 123
         ORDER BY ucaj.id LIMIT 8
    """)
    print("\nSample backfilled rows for UC 123:")
    for r in cur.fetchall():
        print(f"  {r[0]:30s} {r[1]:7s} {r[2]}")
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
