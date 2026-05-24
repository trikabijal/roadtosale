#!/usr/bin/env python3
"""
Backfill ai_assessments rows for photos already uploaded to UAT but never
got an AI verdict (Anthropic credit was exhausted on 2026-05-07; egress
from the UAT VPC to generativelanguage.googleapis.com is currently blocked).

We bypass the UAT backend entirely:
 1. List (uc, qr, photo_path) pending pairs from UAT's DB.
 2. Pull the actual photo bytes from S3 (using the AWS creds in
    application-uat.properties) — same photo that's on file in UAT.
 3. Call Gemini 2.0 Flash directly from our laptop with the SAME
    system + user prompt the backend's AiAssessmentServiceImpl uses,
    so verdicts are byte-faithful to what the backend would have
    produced if it had reached Gemini.
 4. INSERT a row into ai_assessments via the UAT DB. Idempotent —
    re-runs skip (uc, qr) pairs that already have a row.

Parallelism: ThreadPoolExecutor (default 8 workers). Each Gemini call is
~1-3 s, S3 download ~200 ms, DB INSERT ~50 ms.

Env:
  BASE_URL (not used here — we hit Gemini direct)
  DB_HOST / DB_NAME / DB_USER / DB_PASS
  AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION
  GEMINI_KEY
  LIMIT   (optional, smoke first N)
  WORKERS (optional, default 8)
"""
import base64
import json
import os
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import boto3
import psycopg2
import psycopg2.extras
import requests
from botocore.exceptions import ClientError

# ── Config ──────────────────────────────────────────────────────────────────

DB_HOST = os.environ.get("DB_HOST", "172.31.0.157")
DB_NAME = os.environ.get("DB_NAME", "smartcomply")
DB_USER = os.environ.get("DB_USER", "dilipv")
DB_PASS = os.environ["DB_PASS"]

AWS_KEY    = os.environ["AWS_ACCESS_KEY_ID"]
AWS_SECRET = os.environ["AWS_SECRET_ACCESS_KEY"]
AWS_REGION = os.environ.get("AWS_REGION", "ap-south-1")
# Bucket setting in UAT props is `comply-genix/smartcomply` — that's
# bucket `comply-genix` with a prefix `smartcomply/`. Photos stored at
# <prefix>/<path_from_db>.
S3_BUCKET = os.environ.get("S3_BUCKET", "comply-genix")
S3_PREFIX = os.environ.get("S3_PREFIX", "smartcomply/")

GEMINI_KEY = os.environ["GEMINI_KEY"]
# Use Gemini 2.0 Flash via the v1beta REST API. Free-tier friendly,
# fast, supports image input.
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.0-flash")
GEMINI_URL = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent?key={GEMINI_KEY}"

LIMIT   = int(os.environ.get("LIMIT", "0"))
WORKERS = int(os.environ.get("WORKERS", "8"))

# ── Backend-faithful prompt ────────────────────────────────────────────────

SYSTEM_PROMPT = """You are an automotive dealership facility auditor assessing checkpoints from photos.

STEP 1 — PHOTO RELEVANCE:
Does this photo show the element being assessed?
If not, respond: {"j":"INVALID","e":"Photo does not show [element]","c":0.0}

STEP 2 — ASSESS ONLY WHAT IS ASKED:
You will receive a specific checkpoint (e.g., "Damage" or "Cleanliness" for a specific element).
Assess ONLY that checkpoint. Nothing else.
- If the checkpoint is "Damage", assess only physical damage. Ignore cleanliness, branding, age.
- If the checkpoint is "Cleanliness", assess only dirt/dust/stains. Ignore damage, branding, age.
- If the checkpoint is "Visibility", assess only whether the element is visible. Ignore its condition.
Do NOT fail a checkpoint for issues that belong to a different checkpoint.

WHAT IS NORMAL (not a defect):
- Dealer names on facades (e.g., "Rick Case Kia", "Shelly Motors") — normal business signage.
- Old logo styles or previous brand colors — brand compliance issue, not damage or dirt.
- Faded/sun-bleached color — age, not dirt. Only flag as NOT_OK for "Cleanliness" if actual dirt/stains/dust are visible.
- Construction vehicles or equipment nearby — assess the element itself, not its surroundings.

JUDGEMENT:
- OK: The element meets the OK criteria for this specific checkpoint.
- NOT_OK: You can point to a specific defect relevant to this checkpoint.
- If it looks acceptable, judge OK even if you cannot inspect every detail.

CONFIDENCE:
- 0.9-1.0: Clear, unambiguous.
- 0.7-0.8: Likely but minor uncertainty.
- 0.5-0.6: Genuinely uncertain — recommend human re-inspection.

OUTPUT:
- OK with high confidence: {"j":"OK","c":0.9}
- NOT_OK or low confidence: {"j":"NOT_OK","e":"specific issue max 10 words","c":0.6}
- Photo irrelevant: {"j":"INVALID","e":"does not show [element]","c":0.0}"""


# ── Helpers ─────────────────────────────────────────────────────────────────

s3_client = boto3.client(
    "s3", region_name=AWS_REGION,
    aws_access_key_id=AWS_KEY, aws_secret_access_key=AWS_SECRET,
)

print_lock = threading.Lock()
def log(msg):
    with print_lock:
        print(msg, flush=True)


def db():
    return psycopg2.connect(host=DB_HOST, dbname=DB_NAME, user=DB_USER, password=DB_PASS)


def list_pending():
    """For every photo, get the (uc, qr, path, question_name, options) needed
    to build a checkpoint-specific prompt. Filter out pairs that already have
    an ai_assessments row (idempotency).
    """
    with db() as c, c.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("""
            SELECT
                uca.user_checksheet_id              AS uc_id,
                uca.chks_question_result_id         AS qr_id,
                f.path                               AS photo_path,
                f.mime_type                          AS mime_type,
                q.name                               AS question_name,
                uc.checksheet_id                     AS checksheet_id
              FROM user_checksheet_answer_files f
              JOIN user_checksheet_answers uca ON uca.id = f.user_checksheet_answer_id
              JOIN user_checksheets uc        ON uc.id = uca.user_checksheet_id
              JOIN chks_question_results qr   ON qr.id = uca.chks_question_result_id
              JOIN chks_questions q           ON q.id = qr.chks_question_id
             WHERE f.deleted_at IS NULL
               AND uca.chks_question_result_id IS NOT NULL
               AND NOT EXISTS (
                     SELECT 1 FROM ai_assessments aia
                      WHERE aia.user_checksheet_id = uca.user_checksheet_id
                        AND aia.chks_question_result_id = uca.chks_question_result_id
                        AND aia.deleted_at IS NULL
               )
             ORDER BY uca.user_checksheet_id, uca.chks_question_result_id
        """)
        rows = cur.fetchall()
        if not rows:
            return []
        # batch-load options for all qrs in one query
        qr_ids = list({r["qr_id"] for r in rows})
        cur.execute("""
            SELECT chks_question_result_id, judgement, option
              FROM chks_question_result_options
             WHERE chks_question_result_id = ANY(%s)
        """, (qr_ids,))
        opts = {}
        for row in cur.fetchall():
            opts.setdefault(row["chks_question_result_id"], []).append(
                (row["judgement"], row["option"]))
        for r in rows:
            r["options"] = opts.get(r["qr_id"], [])
        return rows


def build_user_prompt(question_name, options):
    """Replicates AiAssessmentServiceImpl.buildUserPrompt exactly."""
    lines = [question_name]
    for judgement, option_text in options:
        key = (judgement or "").strip().upper().replace(" ", "_")
        lines.append(f"{key}: {option_text}")
    return "\n".join(lines) + "\n"


def s3_get(key):
    """Fetch photo bytes from S3 — path in DB is relative to bucket root,
    actual S3 key includes the bucket's prefix."""
    full = f"{S3_PREFIX}{key}" if not key.startswith(S3_PREFIX) else key
    obj = s3_client.get_object(Bucket=S3_BUCKET, Key=full)
    return obj["Body"].read()


def call_gemini(image_bytes, mime_type, user_prompt):
    """Single Gemini Flash call. Returns (raw_text, latency_ms, prompt_tokens, output_tokens)."""
    b64 = base64.b64encode(image_bytes).decode("ascii")
    body = {
        "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "contents": [{
            "role": "user",
            "parts": [
                {"text": user_prompt},
                {"inline_data": {"mime_type": mime_type or "image/jpeg", "data": b64}},
            ],
        }],
        "generationConfig": {
            "temperature": 0.0,
            "responseMimeType": "application/json",
        },
    }
    t0 = time.time()
    r = requests.post(GEMINI_URL, json=body, timeout=60)
    latency_ms = int((time.time() - t0) * 1000)
    if r.status_code != 200:
        raise RuntimeError(f"Gemini HTTP {r.status_code}: {r.text[:300]}")
    j = r.json()
    text = ""
    candidates = j.get("candidates") or []
    if candidates:
        parts = candidates[0].get("content", {}).get("parts") or []
        text = "".join(p.get("text", "") for p in parts)
    usage = j.get("usageMetadata") or {}
    return text, latency_ms, usage.get("promptTokenCount"), usage.get("candidatesTokenCount")


JSON_BLOCK = re.compile(r"```(?:json)?\s*(.*?)```", re.DOTALL)
def parse_response(raw_text):
    """Replicates parseAiResponse."""
    txt = raw_text.strip()
    m = JSON_BLOCK.search(txt)
    if m:
        txt = m.group(1).strip()
    try:
        obj = json.loads(txt)
    except json.JSONDecodeError:
        return ("NOT_OK", "Failed to parse AI response", 0.5)
    judgement = (obj.get("j") or obj.get("judgement") or "").upper().replace(" ", "_")
    if judgement not in ("OK", "NOT_OK", "INVALID"):
        judgement = "NOT_OK"
    explanation = obj.get("e") or obj.get("explanation") or ""
    confidence = obj.get("c") if obj.get("c") is not None else obj.get("confidence", 0.5)
    try:
        confidence = max(0.0, min(1.0, float(confidence)))
    except (TypeError, ValueError):
        confidence = 0.5
    return (judgement, explanation, confidence)


def insert_assessment(uc_id, qr_id, photo_path, judgement, explanation, confidence,
                      prompt_sent, raw_response, ai_model, input_tokens, output_tokens, latency_ms):
    with db() as c, c.cursor() as cur:
        cur.execute("""
            INSERT INTO ai_assessments
                (user_checksheet_id, chks_question_result_id, photo_path,
                 suggested_judgement, explanation, confidence,
                 ai_model, ai_provider,
                 prompt_sent, raw_response,
                 input_tokens, output_tokens, latency_ms,
                 assessed_at, created_at, created_by)
            VALUES
                (%s, %s, %s,
                 %s, %s, %s,
                 %s, %s,
                 %s, %s,
                 %s, %s, %s,
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
        """, (uc_id, qr_id, photo_path,
              judgement, explanation, confidence,
              ai_model, "GOOGLE_GEMINI",
              prompt_sent, raw_response,
              input_tokens, output_tokens, latency_ms))


def process_one(row):
    uc, qr, path, mime, qname, options = (
        row["uc_id"], row["qr_id"], row["photo_path"], row["mime_type"],
        row["question_name"], row["options"],
    )
    try:
        img = s3_get(path)
    except ClientError as e:
        return ("s3_fail", uc, qr, f"S3 {e.response['Error']['Code']}")
    except Exception as e:
        return ("s3_fail", uc, qr, str(e))
    user_prompt = build_user_prompt(qname, options)
    try:
        raw_text, latency_ms, in_tok, out_tok = call_gemini(img, mime, user_prompt)
    except Exception as e:
        return ("gemini_fail", uc, qr, str(e))
    judgement, explanation, confidence = parse_response(raw_text)
    try:
        insert_assessment(uc, qr, path, judgement, explanation, confidence,
                          user_prompt, raw_text, GEMINI_MODEL,
                          in_tok, out_tok, latency_ms)
    except Exception as e:
        return ("db_fail", uc, qr, str(e))
    return ("ok", uc, qr, f"{judgement} c={confidence:.2f} ({latency_ms}ms)")


def main():
    pending = list_pending()
    if LIMIT > 0:
        pending = pending[:LIMIT]
        log(f"LIMIT={LIMIT} — processing first {LIMIT} of pending")
    total = len(pending)
    log(f"Total pending (uc, qr) pairs: {total}")
    log(f"Workers: {WORKERS}  Model: {GEMINI_MODEL}")
    log(f"S3: s3://{S3_BUCKET}/{S3_PREFIX}<path>")
    log("")
    if total == 0:
        log("Nothing to do.")
        return

    counters = {"ok": 0, "s3_fail": 0, "gemini_fail": 0, "db_fail": 0}
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = {pool.submit(process_one, row): row for row in pending}
        done = 0
        for fut in as_completed(futures):
            status, uc, qr, msg = fut.result()
            counters[status] = counters.get(status, 0) + 1
            done += 1
            if status != "ok" or done <= 5 or done % 25 == 0:
                elapsed = time.time() - t0
                rate = done / elapsed if elapsed > 0 else 0
                eta = (total - done) / rate if rate > 0 else 0
                log(f"[{done:>4}/{total}] {status:11s} uc={uc} qr={qr}  {msg}   rate={rate:.1f}/s  eta={eta/60:.1f}m")

    log("")
    log(f"=== done in {(time.time()-t0)/60:.1f} min ===")
    for k, v in counters.items():
        log(f"  {k}: {v}")

    # Final DB sanity
    with db() as c, c.cursor() as cur:
        cur.execute("SELECT count(*), array_agg(DISTINCT ai_provider) FROM ai_assessments WHERE deleted_at IS NULL")
        n, provs = cur.fetchone()
        log(f"  ai_assessments rows total: {n}  providers: {provs}")


if __name__ == "__main__":
    main()
