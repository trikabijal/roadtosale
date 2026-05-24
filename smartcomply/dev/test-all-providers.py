#!/usr/bin/env python3
"""
Test all LLM providers with a Kia showroom audit photo.
Reads API keys from ../.env, sends the same photo + prompt to each provider,
and prints a comparison table with judgement, tokens, latency, and cost.

Usage:
    python3 dev/test-all-providers.py [photo_path]

If no photo is specified, uses dev/test-photos/01_kia_showroom_interior_ok.jpg
"""

import base64, json, os, sys, time, urllib.request, urllib.error

# ─── Load .env ───────────────────────────────────────────────────────
def load_env(env_path):
    if not os.path.exists(env_path):
        print(f"ERROR: {env_path} not found"); sys.exit(1)
    env = {}
    for line in open(env_path):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            env[k.strip()] = v.strip()
    return env

# ─── Spending guard ──────────────────────────────────────────────────
MAX_SPEND_USD = 0.50  # hard cap for this test run
MAX_CALLS = 30
total_spent = 0.0
total_calls = 0

def check_guard():
    global total_spent, total_calls
    if total_spent >= MAX_SPEND_USD:
        print(f"\n⛔ SPENDING GUARD TRIPPED: ${total_spent:.4f} >= ${MAX_SPEND_USD:.2f} limit")
        sys.exit(1)
    if total_calls >= MAX_CALLS:
        print(f"\n⛔ CALL GUARD TRIPPED: {total_calls} >= {MAX_CALLS} call limit")
        sys.exit(1)

def record_cost(cost):
    global total_spent, total_calls
    total_spent += cost
    total_calls += 1

# ─── Models to test ─────────────────────────────────────────────────
MODELS = [
    # (provider, model_id, display_name, input_cost_per_M, output_cost_per_M)
    # Verified working model IDs:
    ("anthropic", "claude-haiku-4-5-20251001", "Claude Haiku 4.5", 1.00, 5.00),
    ("anthropic", "claude-sonnet-4-20250514", "Claude Sonnet 4", 3.00, 15.00),
    ("openai", "gpt-4o-mini", "GPT-4o Mini", 0.15, 0.60),
    ("openai", "gpt-4o", "GPT-4o", 2.50, 10.00),
    ("gemini", "gemini-2.5-flash", "Gemini 2.5 Flash", 0.30, 2.50),
    ("gemini", "gemini-3-flash-preview", "Gemini 3 Flash", 0.50, 3.00),
]

# ─── System + User prompts (same as Java code) ──────────────────────
SYSTEM_PROMPT = """You are an automotive dealership facility auditor assessing checkpoints from photos.

DECISION FRAMEWORK:
- OK: The visible area clearly meets the OK criteria. No defects, damage, dirt, or non-compliance visible.
- NOT_OK: You can see a specific defect — damage, dirt, stain, misalignment, wrong branding, missing item, or non-compliance.
- If the photo shows the area and it looks acceptable, judge OK even if you cannot inspect every detail at close range.
- Only judge NOT_OK when you can point to something specific that is wrong.
- Do NOT judge NOT_OK simply because the photo angle or resolution limits your view. A normal-looking area is OK.

CONFIDENCE:
- 0.9-1.0: Clear, unambiguous — obviously OK or obviously NOT_OK.
- 0.7-0.8: Likely but minor uncertainty — e.g., slight discoloration that might be lighting vs dirt.
- 0.5-0.6: Genuinely uncertain — recommend human re-inspection.
- Below 0.5: Photo is unusable for this checkpoint. Judge NOT_OK with low confidence.

Reply with ONLY this JSON:
{"j":"OK or NOT_OK","e":"max 10 words","c":0.0-1.0}"""

USER_PROMPT = """Display Cars — Cleanliness
OK: Cars clean inside and out, showroom-ready
NOT_OK: Dusty, fingerprinted, or dirty interior"""

# ─── API callers ─────────────────────────────────────────────────────
def call_anthropic(api_key, model, b64_image, media_type):
    body = json.dumps({
        "model": model,
        "max_tokens": 1024,
        "system": [{"type": "text", "text": SYSTEM_PROMPT}],
        "messages": [{"role": "user", "content": [
            {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": b64_image}},
            {"type": "text", "text": USER_PROMPT}
        ]}]
    }).encode()

    req = urllib.request.Request("https://api.anthropic.com/v1/messages",
        data=body, headers={
            "Content-Type": "application/json",
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01"
        })

    start = time.time()
    resp = urllib.request.urlopen(req, timeout=60)
    latency = int((time.time() - start) * 1000)
    data = json.loads(resp.read())

    text = data["content"][0]["text"]
    usage = data.get("usage", {})
    return text, usage.get("input_tokens", 0), usage.get("output_tokens", 0), latency

def call_openai(api_key, model, b64_image, media_type):
    data_uri = f"data:{media_type};base64,{b64_image}"
    body = json.dumps({
        "model": model,
        "max_tokens": 1024,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": data_uri}},
                {"type": "text", "text": USER_PROMPT}
            ]}
        ]
    }).encode()

    req = urllib.request.Request("https://api.openai.com/v1/chat/completions",
        data=body, headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}"
        })

    start = time.time()
    resp = urllib.request.urlopen(req, timeout=60)
    latency = int((time.time() - start) * 1000)
    data = json.loads(resp.read())

    text = data["choices"][0]["message"]["content"]
    usage = data.get("usage", {})
    return text, usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0), latency

def call_gemini(api_key, model, b64_image, media_type):
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    body = json.dumps({
        "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "contents": [{"parts": [
            {"inline_data": {"mime_type": media_type, "data": b64_image}},
            {"text": USER_PROMPT}
        ]}],
        "generationConfig": {"maxOutputTokens": 1024}
    }).encode()

    req = urllib.request.Request(url, data=body,
        headers={"Content-Type": "application/json"})

    start = time.time()
    resp = urllib.request.urlopen(req, timeout=60)
    latency = int((time.time() - start) * 1000)
    data = json.loads(resp.read())

    text = data["candidates"][0]["content"]["parts"][0]["text"]
    usage = data.get("usageMetadata", {})
    return text, usage.get("promptTokenCount", 0), usage.get("candidatesTokenCount", 0), latency

# ─── Parse AI JSON response ─────────────────────────────────────────
def parse_response(text):
    # Extract JSON from potential markdown code blocks
    if "```json" in text:
        text = text.split("```json")[1].split("```")[0].strip()
    elif "```" in text:
        text = text.split("```")[1].split("```")[0].strip()
    else:
        brace_start = text.find("{")
        brace_end = text.rfind("}")
        if brace_start >= 0 and brace_end > brace_start:
            text = text[brace_start:brace_end+1]

    parsed = json.loads(text)
    # Handle both short keys (j, e, c) and long keys (judgement, explanation, confidence)
    j = parsed.get("j", parsed.get("judgement", "NOT_OK")).upper().replace(" ", "_")
    if j not in ("OK", "NOT_OK"):
        j = "NOT_OK"
    e = parsed.get("e", parsed.get("explanation", ""))
    c = parsed.get("c", parsed.get("confidence", 0.5))
    return j, e, c

# ─── Main ────────────────────────────────────────────────────────────
def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_dir = os.path.dirname(script_dir)

    # Load keys
    env = load_env(os.path.join(project_dir, ".env"))
    keys = {
        "anthropic": env.get("ANTHROPIC_KEY", env.get("ANTHROPIC_API_KEY", "")),
        "openai": env.get("OPENAI_KEY", env.get("OPENAI_API_KEY", "")),
        "gemini": env.get("GEMINI_KEY", env.get("GEMINI_API_KEY", "")),
    }

    # Load photo
    default_photo = os.path.join(script_dir, "test-photos", "01_kia_showroom_interior_ok.jpg")
    photo_path = sys.argv[1] if len(sys.argv) > 1 else default_photo
    if not os.path.exists(photo_path):
        print(f"ERROR: Photo not found: {photo_path}"); sys.exit(1)

    with open(photo_path, "rb") as f:
        b64_image = base64.b64encode(f.read()).decode()
    media_type = "image/jpeg"

    photo_name = os.path.basename(photo_path)
    print(f"Photo: {photo_name} ({len(b64_image) * 3 // 4 // 1024} KB)")
    print(f"Checkpoint: Display Cars — Cleanliness")
    print(f"Spending guard: ${MAX_SPEND_USD:.2f} max, {MAX_CALLS} calls max")
    print(f"{'─' * 120}")

    callers = {
        "anthropic": call_anthropic,
        "openai": call_openai,
        "gemini": call_gemini,
    }

    results = []

    for provider, model_id, display_name, in_cost, out_cost in MODELS:
        key = keys.get(provider, "")
        if not key:
            results.append((display_name, model_id, "SKIPPED", "No API key", "-", 0, 0, 0, 0.0))
            continue

        check_guard()

        print(f"  Testing {display_name} ({model_id})...", end=" ", flush=True)
        try:
            text, in_tok, out_tok, latency = callers[provider](key, model_id, b64_image, media_type)
            judgement, explanation, confidence = parse_response(text)
            cost = (in_tok * in_cost / 1_000_000) + (out_tok * out_cost / 1_000_000)
            record_cost(cost)
            results.append((display_name, model_id, judgement, explanation[:60], f"{confidence:.0%}", in_tok, out_tok, latency, cost))
            print(f"✓ {judgement} ({latency}ms, ${cost:.6f})")
        except Exception as e:
            err_msg = str(e)[:80]
            results.append((display_name, model_id, "ERROR", err_msg, "-", 0, 0, 0, 0.0))
            print(f"✗ {err_msg}")

    # Standard reporting format: Model | Input Tokens | Output Tokens | Cost (¢) | Accuracy
    # Accuracy = did the model get the right answer? Determined by majority vote.
    # Cost in cents — dollar amounts with 4+ decimals are unreadable.

    # Determine expected answer by majority vote
    votes = [r[2] for r in results if r[2] in ("OK", "NOT_OK")]
    ok_count = votes.count("OK")
    nok_count = votes.count("NOT_OK")
    expected = "OK" if ok_count > nok_count else "NOT_OK"

    print(f"\n{'═' * 75}")
    header = f"{'Model':<22} {'In Tok':>8} {'Out Tok':>8} {'Cost ¢':>8} {'Accuracy':>10}"
    print(header)
    print(f"{'─' * 75}")

    for name, model_id, judgement, explanation, conf, in_tok, out_tok, latency, cost in results:
        cost_cents = cost * 100
        cost_str = f"{cost_cents:.4f}" if cost > 0 else "-"
        if judgement in ("OK", "NOT_OK"):
            accurate = "CORRECT" if judgement == expected else "WRONG"
        else:
            accurate = judgement  # ERROR or SKIPPED
        print(f"{name:<22} {in_tok:>8} {out_tok:>8} {cost_str:>8} {accurate:>10}")

    print(f"{'─' * 75}")
    total_cents = total_spent * 100
    print(f"{'TOTAL':<22} {'':>8} {'':>8} {total_cents:>8.4f}")
    print(f"\nExpected: {expected} (by {max(ok_count, nok_count)}/{len(votes)} majority vote)")
    print(f"Spending: {total_cents:.4f}¢ / {MAX_SPEND_USD * 100:.1f}¢ limit ({total_calls} calls)")

if __name__ == "__main__":
    main()
