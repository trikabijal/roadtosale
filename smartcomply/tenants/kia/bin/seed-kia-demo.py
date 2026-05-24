# POST-V1.28 NOTE: this script was authored against the pre-collapse schema
# (separate audit_assignments / intervention_assignments / user_checksheets
# tables). After V1.28 the three collapsed into a single 'inspections' table.
# This file still has many SQL statements referencing the old names. Rewrite
# before next seed run, OR seed from a UAT pg_dump of the post-V1.28 DB.

#!/usr/bin/env python3
"""
Kia FY26 H1 demo seed — sequenced, idempotent, smoke-testable.

Modes:
  python3 dev/seed-kia-demo.py --smoke      # 1 APPROVED + 1 IN_PROGRESS, verbose
  python3 dev/seed-kia-demo.py --verify     # only run BI-shape verification
  python3 dev/seed-kia-demo.py              # full run (175 inspections)

The seed:
  1. Pushes checksheet 15 → APPROVED
  2. Creates ~30 demo operators (so we can have 25 IN_PROGRESS without
     hitting the per-operator IN_PROGRESS-conflict guard)
  3. Creates audit "FY26 H1 Kia Showroom Audit"
  4. Picks 200 locations, attaches them
  5. For 175 of them, creates a user_checksheet (150 APPROVED + 25
     IN_PROGRESS) with backdated startedAt. Real /api/ai/assess on photos.

Re-runnable: every step skips if its target already exists.

Env vars:
  BASE_URL         http://localhost:8089 (default)
  DB_HOST/NAME/USER/PASS
  CHECKSHEET_ID    default 15
  AUDIT_NAME       default "FY26 H1 Kia Showroom Audit"
  TOTAL_LOCATIONS  default 200
  APPROVED_COUNT   default 150
  IN_PROGRESS_COUNT default 25
"""
import argparse
import os
import random
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import psycopg2
import psycopg2.extras
import requests

# ─── Config ─────────────────────────────────────────────────────────────────

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8089")
DB_HOST  = os.environ.get("DB_HOST", "localhost")
DB_NAME  = os.environ.get("DB_NAME", "smartcomply")
DB_USER  = os.environ.get("DB_USER", "bijalsanghavi")
DB_PASS  = os.environ.get("DB_PASS", "")

CHECKSHEET_ID = int(os.environ.get("CHECKSHEET_ID", "15"))
AUDIT_NAME    = os.environ.get("AUDIT_NAME", "FY26 H1 Kia Showroom Audit")
AUDIT_START   = "2025-04-01T00:00:00.000"
AUDIT_END     = "2025-09-30T00:00:00.000"

TOTAL_LOCATIONS    = int(os.environ.get("TOTAL_LOCATIONS", "200"))
APPROVED_COUNT     = int(os.environ.get("APPROVED_COUNT", "150"))
IN_PROGRESS_COUNT  = int(os.environ.get("IN_PROGRESS_COUNT", "25"))
NUM_DEMO_OPERATORS = int(os.environ.get("NUM_DEMO_OPERATORS", "30"))

# PHOTO_DIR resolves to <repo>/dev/test-photos/checkpoint-photos by default,
# overridable via env. Fallback to the auditpro sibling repo if photos haven't
# been copied into smartcomply yet.
# This script lives at <repo>/tenants/kia/bin/seed-kia-demo.py, so parents[3]
# is the repo root and parents[4] is the parent dir holding the auditpro sibling.
_DEFAULT_PHOTO_DIR = Path(__file__).resolve().parents[3] / "dev" / "test-photos" / "checkpoint-photos"
_FALLBACK_PHOTO_DIR = Path(__file__).resolve().parents[4] / "auditpro" / "dev" / "test-photos" / "checkpoint-photos"
PHOTO_DIR = Path(os.environ.get(
    "PHOTO_DIR",
    str(_DEFAULT_PHOTO_DIR if _DEFAULT_PHOTO_DIR.exists() else _FALLBACK_PHOTO_DIR),
))
DEFAULT_PWD = "12345678"
# BCrypt of "12345678" — same hash already on team accounts
DEFAULT_PWD_HASH = "$2a$10$IDcjr6bjC.zpRWSNBrlT0uluLTZyddk8dMG7aMBR2INuDPTVG.GxK"

PREPARER          = "KIA_SALES_AUDIT_PREPARER"
DATA_VALIDATOR    = "KIA_SALES_AUDIT_DATA_VALIDATOR"
DATA_APPROVER     = "KIA_SALES_AUDIT_DATA_APPROVER"

# Layered randomness
PROFILES = [
    ("Excellent",     0.20, 0.12),
    ("Good",          0.30, 0.05),
    ("Average",       0.30, 0.00),
    ("Below-average", 0.15, -0.10),
    ("Poor",          0.05, -0.22),
]
CATEGORY_BASE_FAILURE = {
    "EV & Sustainability":   0.35,
    "Branding & Visibility": 0.28,
    "Customer Touchpoints":  0.22,
    "Customer Interaction":  0.18,
    "Functional Areas":      0.17,
    "Infrastructure":        0.16,
    "Compliance":            0.14,
    "Structure":             0.12,
    "People":                0.11,
    "Brand Experience":      0.09,
}
DEFAULT_CATEGORY_FAILURE = 0.20
CHECKPOINT_HOT_MULT = {
    "Paver cleanliness":              1.8,
    "Showroom signage compliance":    1.7,
    "EV charging station availability": 1.6,
    "Test drive process compliance":  1.5,
    "Pavers":                         1.6,
}
REGION_BIAS = {
    "South":     {"EV & Sustainability": 0.15},
    "North":     {"Branding & Visibility": 0.10},
    "East":      {"Infrastructure": 0.10},
    "Northeast": {"Customer Touchpoints": 0.20},
    "West":      {"_all": -0.05},
    "Central":   {},
}
PHOTO_PREFIXES = {
    "acp": "cp01_acp",
    "logo": "cp01_acp",
    "front": "cp01_acp",
    "brand": "cp_brandwall",
    "wall": "cp_brandwall",
    "display": "cp_display",
    "reception": "cp_reception",
    "lounge": "cp_reception",
}

# ─── HTTP / Auth ────────────────────────────────────────────────────────────

_token_cache = {}

def login(username, password=DEFAULT_PWD):
    if username in _token_cache:
        return _token_cache[username]
    r = requests.post(
        f"{BASE_URL}/api/user/login",
        json={"username": username, "password": password, "deviceType": "WEB"},
        timeout=15,
    )
    r.raise_for_status()
    body = r.json()
    if not body.get("status"):
        raise RuntimeError(f"Login failed for {username}: {body.get('message')}")
    tok = body["data"]["accessToken"]
    _token_cache[username] = tok
    return tok

def auth(username):
    return {"Authorization": f"Bearer {login(username)}"}

# ─── DB helpers ─────────────────────────────────────────────────────────────

def db():
    return psycopg2.connect(host=DB_HOST, dbname=DB_NAME, user=DB_USER, password=DB_PASS)

def fetch_all(sql, params=None):
    with db() as c, c.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute(sql, params or ())
        return cur.fetchall()

def fetch_one(sql, params=None):
    rows = fetch_all(sql, params)
    return rows[0] if rows else None

def execute_sql(sql, params=None):
    with db() as c, c.cursor() as cur:
        cur.execute(sql, params or ())
        c.commit()

# ─── Phase 0 — bootstrap demo operators ─────────────────────────────────────

def ensure_demo_operators(verbose=False):
    """Read the demo operators that dev/seed-test-data.sql provisioned.
    User creation lives there now (idempotent SQL) — the Python seed only
    consumes the result. If the count is short, prompt the operator to re-run
    seed-test-data.sql rather than fall back to writing to the DB from here."""
    existing = fetch_all(
        "SELECT username FROM users WHERE username LIKE 'KIA_DEMO_AUDITOR_%%' ORDER BY username"
    )
    if len(existing) < NUM_DEMO_OPERATORS:
        sys.exit(
            f"Only {len(existing)} demo operators found, expected {NUM_DEMO_OPERATORS}.\n"
            "Run: psql -U bijalsanghavi -d smartcomply -f dev/seed-test-data.sql"
        )
    if verbose:
        print(f"  ✓ {len(existing)} demo operators present")
    return [r["username"] for r in existing]

# ─── Phase 1 — setup ────────────────────────────────────────────────────────

def push_checksheet_to_approved(verbose=False):
    row = fetch_one("SELECT status FROM checksheets WHERE id=%s", (CHECKSHEET_ID,))
    if not row:
        sys.exit(f"Checksheet {CHECKSHEET_ID} not found")
    if row["status"] == "APPROVED":
        if verbose: print(f"  ✓ template already APPROVED")
        return
    if verbose: print(f"  template status: {row['status']} → APPROVED (via SQL)")
    execute_sql("UPDATE checksheets SET status='APPROVED' WHERE id=%s", (CHECKSHEET_ID,))

def find_or_create_audit(verbose=False):
    r = requests.get(f"{BASE_URL}/api/audit/list", headers=auth(PREPARER), timeout=15)
    r.raise_for_status()
    body = r.json()
    if body.get("status") and body.get("data"):
        for a in body["data"]:
            if a["name"] == AUDIT_NAME:
                if verbose: print(f"  ✓ audit exists: id={a['id']}")
                return a["id"]
    r = requests.post(
        f"{BASE_URL}/api/audit/createAudit",
        headers={**auth(PREPARER), "Content-Type": "application/json"},
        json={
            "name": AUDIT_NAME,
            "checksheetId": CHECKSHEET_ID,
            "status": "ACTIVE",
            "startDate": AUDIT_START,
            "endDate": AUDIT_END,
        },
        timeout=15,
    )
    r.raise_for_status()
    body = r.json()
    if not body.get("status"):
        raise RuntimeError(f"createAudit failed: {body}")
    aid = body["data"]["id"]
    if verbose: print(f"  ✓ audit created: id={aid}")
    return aid

def pick_locations():
    rows = fetch_all("""
        SELECT al.id AS loc_id, al.auditee_id, al.address,
               c.name AS city, s.name AS state, r.name AS region
          FROM auditee_locations al
          LEFT JOIN cities c  ON c.id = al.city_id
          LEFT JOIN states s  ON s.id = c.state_id
          LEFT JOIN regions r ON r.id = s.region_id
         WHERE al.deleted_at IS NULL
         ORDER BY al.id
    """)
    rng = random.Random(42)
    rng.shuffle(rows)
    return rows[:TOTAL_LOCATIONS]

def operator_id_by_username(username):
    r = fetch_one("SELECT id FROM users WHERE username=%s", (username,))
    return r["id"] if r else None

def attach_assignments(audit_id, locs, operator_pool, verbose=False):
    op_ids = [operator_id_by_username(u) for u in operator_pool]
    op_ids = [x for x in op_ids if x is not None]
    if verbose:
        print(f"  using {len(op_ids)} operators in round-robin")
    payload_assignments = [
        {"auditeeLocationId": loc["loc_id"], "operatorUserId": op_ids[i % len(op_ids)]}
        for i, loc in enumerate(locs)
    ]
    r = requests.post(
        f"{BASE_URL}/api/audit/addAuditAssignments",
        headers={**auth(PREPARER), "Content-Type": "application/json"},
        json={"auditId": audit_id, "assignments": payload_assignments},
        timeout=180,
    )
    r.raise_for_status()
    if verbose:
        print(f"  ✓ {r.json().get('message')}")

def fetch_audit_assignments(audit_id):
    """Read audit_assignments from the DB and return a map keyed by auditee_location_id.
    Each value is dict(assignment_id, operator_user_id, operator_username) — the seed
    must call the API as the assigned operator, otherwise the backend's ownership
    check returns 403."""
    rows = fetch_all(
        """
        SELECT aa.id AS assignment_id, aa.auditee_location_id, aa.operator_user_id,
               u.username AS operator_username
          FROM audit_assignments aa
          LEFT JOIN users u ON u.id = aa.operator_user_id
         WHERE aa.audit_id = %s AND aa.deleted_at IS NULL
        """,
        (audit_id,),
    )
    return {
        r["auditee_location_id"]: {
            "assignment_id": r["assignment_id"],
            "operator_user_id": r["operator_user_id"],
            "operator_username": r["operator_username"],
        }
        for r in rows
    }

# ─── Checksheet structure ───────────────────────────────────────────────────

def load_checksheet_structure(verbose=False):
    rows = fetch_all("""
        SELECT q.id AS qid, q.name AS qname,
               qr.id AS qr_id, qr.answer_type, qr.objective_type,
               qr.upper_limit, qr.lower_limit, qr.unit,
               elt.name AS element,
               cat.name AS category,
               zone.name AS zone,
               (SELECT id FROM chks_question_result_options
                  WHERE chks_question_result_id = qr.id AND judgement = 'OK'
                  ORDER BY id LIMIT 1) AS ok_option_id,
               (SELECT id FROM chks_question_result_options
                  WHERE chks_question_result_id = qr.id AND judgement = 'NOT OK'
                  ORDER BY id LIMIT 1) AS notok_option_id
          FROM chks_questions q
          JOIN chks_question_results qr ON qr.chks_question_id = q.id
          JOIN chks_header_data elt ON elt.id = q.chks_header_data_id
          JOIN chks_header_data cat ON cat.id = elt.chks_header_data_id
          JOIN chks_header_data zone ON zone.id = cat.chks_header_data_id
         WHERE q.checksheet_id = %s
         ORDER BY q.id
    """, (CHECKSHEET_ID,))
    if verbose:
        cats = sorted({r["category"] for r in rows})
        print(f"  ✓ loaded {len(rows)} questions across {len(cats)} categories: {', '.join(cats)}")
    return rows

# ─── Layered randomness ─────────────────────────────────────────────────────

def assign_profile(rng):
    pick = rng.random()
    cum = 0.0
    for name, weight, mod in PROFILES:
        cum += weight
        if pick < cum:
            return name, mod
    return PROFILES[-1][0], PROFILES[-1][2]

def ok_probability(rng, q, region, profile_mod):
    cat_fail = CATEGORY_BASE_FAILURE.get(q["category"], DEFAULT_CATEGORY_FAILURE)
    base_ok = 1.0 - cat_fail
    qmult = CHECKPOINT_HOT_MULT.get(q["qname"], 1.0)
    bias = REGION_BIAS.get(region, {})
    region_extra = bias.get("_all", 0.0) + bias.get(q["category"], 0.0)
    ok = base_ok + profile_mod - (qmult - 1.0) * cat_fail - region_extra
    ok = max(0.30, min(0.98, ok))
    ok += rng.uniform(-0.04, 0.04)
    return max(0.30, min(0.98, ok))

def force_correlated(rng, results, questions):
    """If a 'paver' question failed for this audit, force ~70% of 'signage'
    questions to also fail. Same for ev-charging ↔ solar."""
    pairs = [
        ("paver", "signage"),
        ("ev charg", "solar"),
    ]
    for a_kw, b_kw in pairs:
        a_qids = [q["qid"] for q in questions if a_kw in q["qname"].lower()]
        b_qids = [q["qid"] for q in questions if b_kw in q["qname"].lower()]
        a_failed = any(results.get(qid) == 2 for qid in a_qids)
        b_failed = any(results.get(qid) == 2 for qid in b_qids)
        if a_failed and not b_failed and rng.random() < 0.70:
            for qid in b_qids:
                results[qid] = 2
        elif b_failed and not a_failed and rng.random() < 0.70:
            for qid in a_qids:
                results[qid] = 2

# ─── Photos ─────────────────────────────────────────────────────────────────

def find_photo(element_name, ok, rng):
    el_lower = element_name.lower() if element_name else ""
    for tok, prefix in PHOTO_PREFIXES.items():
        if tok in el_lower:
            kind = "ok" if ok else "notok"
            candidates = list(PHOTO_DIR.glob(f"{prefix}_{kind}_*.jpg"))
            if not candidates:
                candidates = list(PHOTO_DIR.glob(f"{prefix}_*.jpg"))
            if candidates:
                return rng.choice(candidates)
    return None

# ─── Per-location seed loop ─────────────────────────────────────────────────

def already_seeded_by_assignment(assignment_id):
    return fetch_one(
        "SELECT id, status FROM inspections WHERE audit_assignment_id=%s",
        (assignment_id,),
    )

def create_user_checksheet(assignment_id, operator_username, started_at):
    """Server resolves audit + location + checksheet from auditAssignmentId."""
    payload = {
        "auditAssignmentId": assignment_id,
        "status": "IN_PROGRESS",
        "shift": "First",
        "startedAt": started_at.strftime("%Y-%m-%d %H:%M:%S.000"),
        "submissionVersion": 0,
        "frequencyOfFreqOfChkCnt": 1,
    }
    # Endpoint takes List<UserChecksheetDTO>; response data is a list of saved DTOs.
    r = requests.post(
        f"{BASE_URL}/api/userChecksheet/createOrUpdate",
        headers={**auth(operator_username), "Content-Type": "application/json"},
        json=[payload], timeout=30,
    )
    r.raise_for_status()
    body = r.json()
    if not body.get("status"):
        raise RuntimeError(f"createOrUpdate failed: {body}")
    return body["data"][0]["id"]

def submit_answers(uc_id, operator_username, answers):
    """POST answers, return the saved DTOs so callers can read each row's id."""
    if not answers:
        return []
    r = requests.post(
        f"{BASE_URL}/api/userChecksheet/createOrUpdateUserChksAns",
        headers={**auth(operator_username), "Content-Type": "application/json"},
        json=answers, timeout=60,
    )
    if r.status_code != 200:
        raise RuntimeError(f"answers POST {r.status_code}: {r.text[:200]}")
    body = r.json()
    return body.get("data") or []

def upload_photo(answer_id, photo_path, operator_username, verbose=False):
    """Attach a photo to an existing user_checksheet_answer row.
    Now allowed on any answer type since the FILE_UPLOAD-only restriction
    was lifted on the backend."""
    with open(photo_path, "rb") as f:
        r = requests.post(
            f"{BASE_URL}/api/userChecksheet/createUserChksAnsFile",
            headers={"Authorization": f"Bearer {login(operator_username)}"},
            files={"file": (photo_path.name, f, "image/jpeg")},
            data={"userChecksheetAnswerId": answer_id},
            timeout=60,
        )
        if verbose and r.status_code != 200:
            print(f"        photo upload failed: HTTP {r.status_code} {r.text[:200]}")
        return r.status_code == 200

class AiSpendingGuardTripped(RuntimeError):
    """Raised when the LLM provider's budget cap is hit (HTTP 429).
    Caller should stop calling AI; otherwise we keep tickling the cap."""

def call_ai_assess(uc_id, qr_id, photo_path, operator_username):
    try:
        with open(photo_path, "rb") as f:
            r = requests.post(
                f"{BASE_URL}/api/ai/assess",
                headers={"Authorization": f"Bearer {login(operator_username)}"},
                files={"photo": (photo_path.name, f, "image/jpeg")},
                data={"userChecksheetId": uc_id, "chksQuestionResultId": qr_id},
                timeout=60,
            )
        if r.status_code == 429:
            # Budget cap tripped — surface loudly so the seed stops calling AI.
            raise AiSpendingGuardTripped(f"AI spending guard tripped: {r.text[:300]}")
        return r.status_code == 200
    except AiSpendingGuardTripped:
        raise
    except Exception as e:
        print(f"        AI assess failed: {e}")
        return False

def transition_to_approved(uc_id, operator_username, verbose=False, submitted_at=None):
    """Walk IN_PROGRESS → SUBMITTED → VALIDATED → APPROVED. Raises on any failure.

    The service's createOrUpdate now preserves startedAt on UPDATE, so the SUBMIT
    payload only carries the fields that actually change.
    """
    def _check(r, label):
        try:
            body = r.json()
        except Exception:
            body = {"text": r.text[:200]}
        if r.status_code != 200 or not body.get("status", True):
            raise RuntimeError(f"{label} failed: HTTP {r.status_code} {body}")
        if verbose:
            print(f"        {label}: {body.get('message','OK')}")

    # Default the submission timestamp to ~1-3 days after the audit started
    # if the caller didn't pass one — keeps "Last Audited" realistic.
    submitted_str = (submitted_at or datetime.now(timezone.utc).replace(tzinfo=None)).strftime("%Y-%m-%d %H:%M:%S.000")
    r = requests.post(
        f"{BASE_URL}/api/userChecksheet/createOrUpdate",
        headers={**auth(operator_username), "Content-Type": "application/json"},
        json=[{
            "id": uc_id,
            "status": "SUBMITTED",
            "submittedAt": submitted_str,
        }], timeout=30,
    )
    _check(r, "SUBMIT")

    # Status enums: VALIDATED for the validate API, APPROVED for the approve API.
    r = requests.post(
        f"{BASE_URL}/api/userChecksheetValidation/addUserChecksheetValidation",
        headers={**auth(DATA_VALIDATOR), "Content-Type": "application/json"},
        json={"userChecksheetId": uc_id, "status": "VALIDATED", "remarks": "OK"},
        timeout=15,
    )
    _check(r, "VALIDATE")

    r = requests.post(
        f"{BASE_URL}/api/userChecksheetApproval/addUserChecksheetApproval",
        headers={**auth(DATA_APPROVER), "Content-Type": "application/json"},
        json={"userChecksheetId": uc_id, "status": "APPROVED", "remarks": "OK"},
        timeout=15,
    )
    _check(r, "APPROVE")

def seed_one_location(assignment_id, location, operator_username, profile_mod, target_status,
                      questions, started_at, verbose=False):
    if already_seeded_by_assignment(assignment_id):
        if verbose: print(f"    skip (already seeded): assignment={assignment_id} loc={location['loc_id']}")
        return None

    if verbose: print(f"    creating user_checksheet for loc={location['loc_id']} as {operator_username}")
    uc_id = create_user_checksheet(assignment_id, operator_username, started_at)
    if verbose: print(f"      ✓ uc_id={uc_id}")

    rng = random.Random(f"{assignment_id}:{location['loc_id']}")
    region = location.get("region") or "Central"

    # Decide each question's verdict
    results = {}
    for q in questions:
        prob_ok = ok_probability(rng, q, region, profile_mod)
        results[q["qid"]] = 1 if rng.random() < prob_ok else 2
    force_correlated(rng, results, questions)
    n_ok = sum(1 for v in results.values() if v == 1)
    n_notok = sum(1 for v in results.values() if v == 2)
    if verbose: print(f"      verdicts: {n_ok} OK / {n_notok} NOT_OK")

    questions_to_answer = questions if target_status == "APPROVED" else questions[:30]

    # Build answers payload
    answers_payload = []
    for q in questions_to_answer:
        judgement = results[q["qid"]]
        ok = judgement == 1
        ans = {
            "userChecksheetId": uc_id,
            "chksQuestionResultId": q["qr_id"],
            "isNotApplicable": False,
            "answeredAt": (started_at + timedelta(minutes=rng.randint(1, 240))).strftime("%Y-%m-%d %H:%M:%S.000"),
            "judgement": judgement,
        }
        if q["answer_type"] == "SUBJECTIVE_CONDITION":
            opt = q["ok_option_id"] if ok else q["notok_option_id"]
            if opt is None:
                # fallback if options missing — set NA
                ans["isNotApplicable"] = True
            else:
                ans["chksQuestionRsltOptionId"] = opt
        elif q["answer_type"] == "OBJECTIVE":
            ans["answer"] = "0" if ok else str(rng.choices([1, 2, 3], weights=[3, 2, 1])[0])
        elif q["answer_type"] == "SUBJECTIVE":
            ans["answer"] = "Compliant" if ok else "Non-compliant: requires action"
        answers_payload.append(ans)

    saved_answers = submit_answers(uc_id, operator_username, answers_payload)
    if verbose: print(f"      ✓ submitted {len(answers_payload)} answers")

    # Map chks_question_result_id → saved user_checksheet_answer_id so we can
    # attach photos in the second step.
    answer_id_by_qr = {a["chksQuestionResultId"]: a["id"] for a in saved_answers if a.get("id")}

    # For the first ~25 questions where element matches a photo prefix:
    #   1. POST createUserChksAnsFile to attach the photo to the answer row.
    #      Lands in user_checksheet_answer_files — the audit Excel/PDF report
    #      reads from this table.
    #   2. POST /api/ai/assess so Gemini Flash stores its verdict. Lands in
    #      ai_assessments — the BI dashboard's AI agreement story reads here.
    # The photo is transmitted twice today (~875 MB total at full seed). The
    # right long-term fix is a backend endpoint that takes an existing
    # answer-file id and runs AI on it — tracked as a Round 3 GitLab issue.
    photos_uploaded = 0
    ai_calls = 0
    ai_disabled = False
    for q in questions_to_answer[:25]:
        photo = find_photo(q["element"], ok=results[q["qid"]] == 1, rng=rng)
        if photo is None:
            continue
        ans_id = answer_id_by_qr.get(q["qr_id"])
        if ans_id and upload_photo(ans_id, photo, operator_username, verbose=verbose):
            photos_uploaded += 1
        if not ai_disabled:
            try:
                if call_ai_assess(uc_id, q["qr_id"], photo, operator_username):
                    ai_calls += 1
            except AiSpendingGuardTripped as e:
                # Stop calling AI for the rest of THIS audit; outer loop will
                # see the next call hit the same wall and decide whether to
                # keep going.
                print(f"      ⚠ {e}")
                ai_disabled = True
        time.sleep(0.05)
    if verbose: print(f"      ✓ photos={photos_uploaded}, AI assessments={ai_calls}")

    if target_status == "APPROVED":
        # Submit ~2 days after the audit started so "Last Audited" timestamps
        # span the same date range as started_at (April–May 2025).
        submitted = started_at + timedelta(days=rng.randint(1, 3), hours=rng.randint(0, 23))
        transition_to_approved(uc_id, operator_username, verbose=verbose, submitted_at=submitted)
        if verbose: print(f"      ✓ transitioned to APPROVED")

    return {"uc_id": uc_id, "photos": photos_uploaded, "ai_calls": ai_calls, "status": target_status}

# ─── Verification ───────────────────────────────────────────────────────────

def verify(audit_id=None, expected_total=None):
    """Print BI-shape stats. Used after smoke or full run."""
    if audit_id is None:
        a = fetch_one("SELECT id FROM audits WHERE name=%s AND deleted_at IS NULL", (AUDIT_NAME,))
        if not a:
            print("  no audit found")
            return False
        audit_id = a["id"]

    print(f"\n=== VERIFICATION (audit_id={audit_id}) ===")
    print("\n  Status counts (inspections):")
    for r in fetch_all("""
        SELECT uc.status, COUNT(*) AS n
          FROM inspections uc
          JOIN audit_assignments aa ON aa.id = uc.audit_assignment_id
         WHERE aa.audit_id=%s GROUP BY uc.status ORDER BY uc.status
    """, (audit_id,)):
        print(f"    {r['status']:15s} {r['n']:4d}")

    print("\n  audit_assignments:")
    r = fetch_one("SELECT COUNT(*) AS n FROM audit_assignments WHERE audit_id=%s", (audit_id,))
    print(f"    total: {r['n']}")

    print("\n  Band split (APPROVED audits only):")
    rows = fetch_all("""
        WITH scores AS (
          SELECT uc.id,
                 100.0 * SUM(CASE WHEN uca.judgement=1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0) AS pct
            FROM inspections uc
            JOIN audit_assignments aa ON aa.id = uc.audit_assignment_id
            JOIN user_checksheet_answers uca ON uca.inspection_id = uc.id
           WHERE aa.audit_id=%s AND uc.status='APPROVED'
           GROUP BY uc.id
        )
        SELECT
          COUNT(*) FILTER (WHERE pct >= 75) AS green,
          COUNT(*) FILTER (WHERE pct >= 60 AND pct < 75) AS amber,
          COUNT(*) FILTER (WHERE pct < 60) AS red,
          ROUND(AVG(pct)::numeric, 1) AS avg_score,
          COUNT(*) AS total
        FROM scores
    """, (audit_id,))
    if rows:
        r = rows[0]
        print(f"    green={r['green']}  amber={r['amber']}  red={r['red']}  avg={r['avg_score']}  total={r['total']}")

    print("\n  What's failing (top 6 categories):")
    for r in fetch_all("""
        SELECT cat.name AS category,
               ROUND((100.0 * SUM(CASE WHEN uca.judgement!=1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0))::numeric, 1) AS fail_pct,
               COUNT(*) AS total
          FROM user_checksheet_answers uca
          JOIN inspections uc ON uc.id = uca.inspection_id
          JOIN audit_assignments aa ON aa.id = uc.audit_assignment_id
          JOIN chks_question_results qr ON qr.id = uca.chks_question_result_id
          JOIN chks_questions q ON q.id = qr.chks_question_id
          JOIN chks_header_data elt ON elt.id = q.chks_header_data_id
          JOIN chks_header_data cat ON cat.id = elt.chks_header_data_id
         WHERE aa.audit_id=%s AND uc.status='APPROVED'
         GROUP BY cat.name ORDER BY fail_pct DESC LIMIT 6
    """, (audit_id,)):
        print(f"    {r['category']:30s} {r['fail_pct']}%  ({r['total']} answers)")

    print("\n  Per-region failure rate:")
    for r in fetch_all("""
        SELECT r.name AS region,
               ROUND((100.0 * SUM(CASE WHEN uca.judgement!=1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0))::numeric, 1) AS fail_pct,
               COUNT(*) AS total
          FROM user_checksheet_answers uca
          JOIN inspections uc ON uc.id = uca.inspection_id
          JOIN audit_assignments aa ON aa.id = uc.audit_assignment_id
          JOIN auditee_locations al ON al.id = aa.auditee_location_id
          JOIN cities c ON c.id = al.city_id
          JOIN states s ON s.id = c.state_id
          JOIN regions r ON r.id = s.region_id
         WHERE aa.audit_id=%s AND uc.status='APPROVED'
         GROUP BY r.name ORDER BY fail_pct DESC
    """, (audit_id,)):
        print(f"    {r['region']:12s} {r['fail_pct']}%  ({r['total']} answers)")

    print("\n  Top failing checkpoints:")
    for r in fetch_all("""
        SELECT q.name AS checkpoint,
               ROUND((100.0 * SUM(CASE WHEN uca.judgement!=1 THEN 1 ELSE 0 END) / NULLIF(COUNT(*),0))::numeric, 1) AS fail_pct,
               COUNT(*) AS total
          FROM user_checksheet_answers uca
          JOIN inspections uc ON uc.id = uca.inspection_id
          JOIN audit_assignments aa ON aa.id = uc.audit_assignment_id
          JOIN chks_question_results qr ON qr.id = uca.chks_question_result_id
          JOIN chks_questions q ON q.id = qr.chks_question_id
         WHERE aa.audit_id=%s AND uc.status='APPROVED'
         GROUP BY q.name ORDER BY fail_pct DESC LIMIT 5
    """, (audit_id,)):
        print(f"    {r['checkpoint']:30s} {r['fail_pct']}%  ({r['total']} answers)")

    print("\n  Photo coverage:")
    r = fetch_one("""
        SELECT COUNT(*) AS n FROM user_checksheet_answer_files f
          JOIN user_checksheet_answers a ON a.id = f.user_checksheet_answer_id
          JOIN inspections uc ON uc.id = a.inspection_id
          JOIN audit_assignments aa ON aa.id = uc.audit_assignment_id
         WHERE aa.audit_id=%s
    """, (audit_id,))
    print(f"    photos: {r['n']}")

    print("\n  AI assessments:")
    try:
        r = fetch_one("""
            SELECT COUNT(*) AS n FROM ai_assessments aia
             WHERE aia.inspection_id IN (
                 SELECT uc.id FROM inspections uc
                   JOIN audit_assignments aa ON aa.id = uc.audit_assignment_id
                  WHERE aa.audit_id=%s)
        """, (audit_id,))
        print(f"    rows: {r['n']}")
    except Exception as e:
        print(f"    (skipped: {e})")

    return True

# ─── Smoke test ─────────────────────────────────────────────────────────────

def smoke():
    print("=== SMOKE TEST — 1 APPROVED + 1 IN_PROGRESS ===\n")
    print("[Phase 0] bootstrap")
    push_checksheet_to_approved(verbose=True)
    demo_ops = ensure_demo_operators(verbose=True)

    print("\n[Phase 1] setup")
    audit_id = find_or_create_audit(verbose=True)
    locs = pick_locations()
    print(f"  picked {len(locs)} locations")
    attach_assignments(audit_id, locs, demo_ops, verbose=True)
    assignments_by_loc = fetch_audit_assignments(audit_id)

    questions = load_checksheet_structure(verbose=True)

    # Always call the API as the operator who actually owns the assignment —
    # the backend ownership check rejects everyone else with 403.
    print("\n[Phase 2] one APPROVED")
    target = locs[0]
    a = assignments_by_loc[target["loc_id"]]
    profile_name, profile_mod = assign_profile(random.Random(target["loc_id"]))
    print(f"  loc={target['loc_id']} profile={profile_name} region={target.get('region')} as={a['operator_username']}")
    started = datetime(2025, 4, 5, 10, 0)
    seed_one_location(a["assignment_id"], target, a["operator_username"], profile_mod, "APPROVED", questions, started, verbose=True)

    print("\n[Phase 3] one IN_PROGRESS")
    target = locs[1]
    a = assignments_by_loc[target["loc_id"]]
    profile_name, profile_mod = assign_profile(random.Random(target["loc_id"]))
    print(f"  loc={target['loc_id']} profile={profile_name} region={target.get('region')} as={a['operator_username']}")
    started = datetime(2025, 5, 12, 14, 30)
    seed_one_location(a["assignment_id"], target, a["operator_username"], profile_mod, "IN_PROGRESS", questions, started, verbose=True)

    verify(audit_id)
    print("\n=== SMOKE OK ===")

# ─── Full run ───────────────────────────────────────────────────────────────

def full():
    print("[Phase 0] bootstrap")
    push_checksheet_to_approved(verbose=True)
    demo_ops = ensure_demo_operators(verbose=True)

    print("\n[Phase 1] setup")
    audit_id = find_or_create_audit(verbose=True)
    locs = pick_locations()
    print(f"  picked {len(locs)} locations")
    attach_assignments(audit_id, locs, demo_ops, verbose=True)
    assignments_by_loc = fetch_audit_assignments(audit_id)

    questions = load_checksheet_structure(verbose=True)

    rng = random.Random(123)
    rng.shuffle(locs)
    approved_locs = locs[:APPROVED_COUNT]
    in_prog_locs  = locs[APPROVED_COUNT:APPROVED_COUNT + IN_PROGRESS_COUNT]

    print(f"\n[Phase 2] generate inspections")
    print(f"  approved={len(approved_locs)} in_progress={len(in_prog_locs)} not_started={len(locs) - APPROVED_COUNT - IN_PROGRESS_COUNT}")

    base_date = datetime(2025, 4, 1)
    n = 0
    total = APPROVED_COUNT + IN_PROGRESS_COUNT

    for batch_locs, target_status in [(approved_locs, "APPROVED"), (in_prog_locs, "IN_PROGRESS")]:
        for loc in batch_locs:
            n += 1
            profile_name, profile_mod = assign_profile(random.Random(loc["loc_id"]))
            offset_days = random.Random(loc["loc_id"]).randint(0, 60)
            started = base_date + timedelta(days=offset_days, hours=random.randint(8, 17))
            assignment = assignments_by_loc.get(loc["loc_id"])
            if assignment is None:
                print(f"  [{n}/{total}] SKIP loc={loc['loc_id']}: no assignment row")
                continue
            # Call API as the operator who actually OWNS the assignment — the
            # ownership check rejects everyone else.
            operator = assignment["operator_username"]
            try:
                r = seed_one_location(assignment["assignment_id"], loc, operator, profile_mod, target_status, questions, started)
                tag = "APR" if target_status == "APPROVED" else "PRG"
                print(f"  [{n}/{total}] {tag} loc={loc['loc_id']} profile={profile_name:12s} photos={r['photos'] if r else '-'}")
            except Exception as e:
                print(f"  [{n}/{total}] ERROR loc={loc['loc_id']}: {e}")

    print("\nDone.")
    verify(audit_id)

# ─── Main ───────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--smoke", action="store_true", help="1 APPROVED + 1 IN_PROGRESS, verbose")
    parser.add_argument("--verify", action="store_true", help="run verification only")
    args = parser.parse_args()

    if args.verify:
        verify()
    elif args.smoke:
        smoke()
    else:
        full()

if __name__ == "__main__":
    main()
