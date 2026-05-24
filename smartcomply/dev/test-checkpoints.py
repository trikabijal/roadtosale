#!/usr/bin/env python3
"""
Systematic checkpoint test: runs each audit checkpoint against matched
OK and NOT_OK photos across all LLM providers.

Usage:
    python3 dev/test-checkpoints.py [--lines 1-5] [--model claude-haiku-4-5-20251001]

Defaults: all checkpoints, all models. Use --lines to test a range.
"""

import base64, json, os, sys, time, urllib.request, urllib.error, argparse

# ─── Load .env ───────────────────────────────────────────────────────
def load_env(env_path):
    env = {}
    if os.path.exists(env_path):
        for line in open(env_path):
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                env[k.strip()] = v.strip()
    return env

# ─── Spending guard ──────────────────────────────────────────────────
MAX_SPEND_CENTS = 50.0  # 50 cents max for test run
total_spent_cents = 0.0
total_calls = 0

def check_guard():
    if total_spent_cents >= MAX_SPEND_CENTS:
        print(f"\n  SPENDING GUARD: {total_spent_cents:.2f}c >= {MAX_SPEND_CENTS:.0f}c limit")
        sys.exit(1)

def record_cost(cost_usd):
    global total_spent_cents, total_calls
    total_spent_cents += cost_usd * 100
    total_calls += 1

# ─── Checkpoints (from Kia Showroom Audit Template) ──────────────────
# Each: (line#, element, checkpoint, ok_text, notok_text, hint, ok_photos, notok_photos)
CHECKPOINTS = [
    (1, "Front ACP + Logo", "Damage",
     "No visible damage on ACP or logo",
     "Visible dents, scratches, or panel damage",
     "Check panel surface for: dents or depressions, scratch marks, cracks, peeling or bubbling coating, loose panel seams with visible gaps, bent or warped sections. Check logo for: missing or broken characters, physical damage to letters. Old branding (e.g. red Kia logo instead of current black) is brand non-compliance, not physical damage — flag it as NOT_OK only if specifically damaged.",
     ["07_kia_exterior_night_ok.jpg"],
     ["09_kia_exterior_notok_old_panels.jpg", "11_kia_exterior_notok_worn_cladding.jpg"]),

    (2, "Front ACP + Logo", "Cleanliness",
     "ACP and logo clean, no dust or streaks",
     "Dusty, stained, or streaked",
     "Dust appears as a dull matte film on a surface that should be glossy. Stains are discolored patches darker than surrounding area. Streaks are vertical lines from rain runoff. Also look for: cobwebs in corners, grime buildup around logo edges, bird droppings (white spots). Sun-bleached/faded color is age damage, not dirt — but it still indicates poor maintenance so flag as NOT_OK.",
     ["07_kia_exterior_night_ok.jpg"],
     ["10_kia_exterior_notok_faded_facade.jpg"]),

    (4, "Pylon", "Visibility",
     "Pylon fully visible from approach road",
     "Obstructed or not visible from road",
     "Kia pylon (tall freestanding sign) should be clearly visible from the main approach road. Check: Is the pylon present? Is the sign face readable? Is it obstructed by trees, signs, vehicles, or construction? A dealership with no pylon, a TO LET sign, or no active Kia branding is NOT_OK.",
     ["02_kia_pylon_signage_ok.jpg"],
     ["cp04_pylon_visibility_notok.jpg"]),

    (5, "Pylon", "Cleanliness",
     "Pylon clean, no dust or droppings",
     "Dirty, stained, or covered in droppings",
     "Check for: bird droppings (white/grey spots on top edges and horizontal surfaces), green algae or moss on lower sections, dirt reducing text legibility, rust stains from metal fixtures, cobwebs on sign face or lighting.",
     ["02_kia_pylon_signage_ok.jpg"],
     []),

    (6, "Directional Signage", "Directional clarity",
     "Signage correct, legible, and logical",
     "Missing, illegible, or misleading",
     "Directional signs (parking, service, showroom, exit) should be: readable from a moving vehicle, current Kia brand style, pointing correctly, not faded or peeling, properly mounted. Look for: faded unreadable text, handwritten or taped temporary signs, conflicting arrows, signs obscured by vegetation, rust or corrosion on sign frames.",
     ["cp06_signage_clarity_ok.jpg"],
     ["cp06_signage_clarity_notok.jpg"]),

    (10, "Facade Glass", "Damage",
     "No cracks, chips, or broken sections",
     "Cracks, chips, or broken sections",
     "Check all glass panels for: spider web or star crack patterns from impact, linear cracks along edges, chips (small missing pieces at edges), complete breaks with missing sections, temporary repairs (tape, board, plastic covering a break), glass separated from frame, milky/foggy areas in double-glazed units (seal failure).",
     ["07_kia_exterior_night_ok.jpg"],
     ["cp10_facade_glass_notok.jpg"]),

    (11, "Facade Glass", "Cleanliness",
     "Streak-free and clean",
     "Dirty, streaked, or smudged",
     "Glass should appear transparent — you should clearly see cars inside the showroom through it. Check for: hazy/cloudy appearance (dust film), circular water spots (mineral deposits), vertical streak lines (rain runoff), fingerprint clusters near door handles, cobwebs in upper corners, matte non-reflective appearance on glass that should reflect the sky. A dull non-reflective glass surface indicates a dirt film.",
     ["07_kia_exterior_night_ok.jpg"],
     ["08_kia_exterior_notok_before_refurb.jpg"]),

    (12, "Granite", "Cracks",
     "No cracks or chips",
     "Visible cracks or broken sections",
     "Check for: cracks running through tiles (not along grout — grout lines are joints), displaced or shifted tiles creating trip hazards, missing grout exposing substrate, chipped tile edges or corners near doorways, tiles that have lifted or tented upward.",
     ["cp12_granite_cracks_ok.jpg"],
     ["cp12_granite_cracks_notok.jpg"]),

    (13, "Granite", "Cleanliness",
     "Polished and clean",
     "Stained or grimy",
     "Granite should have consistent sheen/polish — dull patches indicate wear or staining. Check for: oil/grease stains (dark spots near service entrance), rust stains (orange/brown from metal furniture), water stains (white mineral deposits), grime in grout lines (should be consistent color, not darkened), tire marks or rubber scuffs.",
     ["cp12_granite_cracks_ok.jpg"],
     []),

    (14, "Pavers", "Damage",
     "All pavers intact and level",
     "Broken, sunken, or missing pavers",
     "Check for: cracked or split pavers, sunken pavers creating uneven surfaces, pavers shifted out of alignment with gaps between them, missing pavers leaving holes, weeds or grass growing through joints (indicates joint sand washed out — maintenance failure), moss or algae on paver surfaces (green/dark patches in shade). Weed growth in joints IS damage — it means the joint structure has failed.",
     ["cp14_pavers_damage_ok.jpg"],
     ["cp14_pavers_damage_notok.jpg"]),

    (15, "Pavers", "Cleanliness",
     "Clean, no stains or debris",
     "Stained, grimy, or littered",
     "Check for: oil/fluid stains from vehicles (dark spots in parking areas), tire marks (black streaks), moss or algae growth (green/dark patches in damp areas), leaf debris in joints, cigarette butts or litter, chewing gum. Clean pavers should have uniform color — dark patches indicate contamination.",
     ["cp14_pavers_damage_ok.jpg"],
     ["cp14_pavers_damage_notok.jpg"]),
]

# ─── Models ──────────────────────────────────────────────────────────
MODELS = [
    ("anthropic", "claude-haiku-4-5-20251001", "Haiku 4.5", 1.00, 5.00),
    ("gemini", "gemini-2.5-flash", "Gemini Flash", 0.30, 2.50),
]

SYSTEM_PROMPT = """You are an automotive dealership facility auditor assessing checkpoints from photos.

DECISION FRAMEWORK:
- OK: The visible area clearly meets the OK criteria. No defects, damage, dirt, or non-compliance visible.
- NOT_OK: You can see a specific defect — damage, dirt, stain, misalignment, wrong branding, missing item, or non-compliance.
- If the photo shows the area and it looks acceptable, judge OK even if you cannot inspect every detail at close range.
- Only judge NOT_OK when you can point to something specific that is wrong.
- Do NOT judge NOT_OK simply because the photo angle or resolution limits your view. A normal-looking area is OK.

CONFIDENCE:
- 0.9-1.0: Clear, unambiguous.
- 0.7-0.8: Likely but minor uncertainty.
- 0.5-0.6: Genuinely uncertain — recommend human re-inspection.
- Below 0.5: Photo unusable. Judge NOT_OK with low confidence.

OUTPUT FORMAT:
- If OK and confidence >= 0.8: {"j":"OK","c":0.9}
- If NOT_OK OR confidence < 0.8: {"j":"NOT_OK","e":"specific issue max 10 words","c":0.6}"""

# ─── API callers ─────────────────────────────────────────────────────
def call_anthropic(api_key, model, b64_image, media_type, user_prompt):
    body = json.dumps({
        "model": model, "max_tokens": 256,
        "system": [{"type": "text", "text": SYSTEM_PROMPT}],
        "messages": [{"role": "user", "content": [
            {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": b64_image}},
            {"type": "text", "text": user_prompt}
        ]}]
    }).encode()
    req = urllib.request.Request("https://api.anthropic.com/v1/messages",
        data=body, headers={"Content-Type": "application/json", "x-api-key": api_key, "anthropic-version": "2023-06-01"})
    start = time.time()
    resp = urllib.request.urlopen(req, timeout=60)
    latency = int((time.time() - start) * 1000)
    data = json.loads(resp.read())
    text = data["content"][0]["text"]
    usage = data.get("usage", {})
    return text, usage.get("input_tokens", 0), usage.get("output_tokens", 0), latency

def call_openai(api_key, model, b64_image, media_type, user_prompt):
    data_uri = f"data:{media_type};base64,{b64_image}"
    body = json.dumps({
        "model": model, "max_tokens": 256,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": data_uri}},
                {"type": "text", "text": user_prompt}
            ]}
        ]
    }).encode()
    req = urllib.request.Request("https://api.openai.com/v1/chat/completions",
        data=body, headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"})
    start = time.time()
    resp = urllib.request.urlopen(req, timeout=60)
    latency = int((time.time() - start) * 1000)
    data = json.loads(resp.read())
    text = data["choices"][0]["message"]["content"]
    usage = data.get("usage", {})
    return text, usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0), latency

def call_gemini(api_key, model, b64_image, media_type, user_prompt):
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
    body = json.dumps({
        "system_instruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "contents": [{"parts": [
            {"inline_data": {"mime_type": media_type, "data": b64_image}},
            {"text": user_prompt}
        ]}],
        "generationConfig": {"maxOutputTokens": 256}
    }).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    start = time.time()
    resp = urllib.request.urlopen(req, timeout=60)
    latency = int((time.time() - start) * 1000)
    data = json.loads(resp.read())
    text = data["candidates"][0]["content"]["parts"][0]["text"]
    usage = data.get("usageMetadata", {})
    return text, usage.get("promptTokenCount", 0), usage.get("candidatesTokenCount", 0), latency

CALLERS = {"anthropic": call_anthropic, "openai": call_openai, "gemini": call_gemini}

# Rate limit delay between calls to avoid 429s
CALL_DELAY_SEC = 3.0  # 3s between calls to avoid Gemini 429s

def parse_response(text):
    if "```json" in text: text = text.split("```json")[1].split("```")[0].strip()
    elif "```" in text: text = text.split("```")[1].split("```")[0].strip()
    else:
        s, e = text.find("{"), text.rfind("}")
        if s >= 0 and e > s: text = text[s:e+1]
    parsed = json.loads(text)
    j = parsed.get("j", parsed.get("judgement", "NOT_OK")).upper().replace(" ", "_")
    if j not in ("OK", "NOT_OK"): j = "NOT_OK"
    e = parsed.get("e", parsed.get("explanation", ""))
    c = parsed.get("c", parsed.get("confidence", 0.5))
    return j, e, float(c)

# ─── Main ────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lines", default="1-15", help="Checkpoint line range, e.g. 1-5")
    parser.add_argument("--model", default=None, help="Specific model ID to test")
    args = parser.parse_args()

    start_line, end_line = map(int, args.lines.split("-"))

    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_dir = os.path.dirname(script_dir)
    photo_dir = os.path.join(script_dir, "test-photos")

    env = load_env(os.path.join(project_dir, ".env"))
    keys = {
        "anthropic": env.get("ANTHROPIC_KEY", env.get("ANTHROPIC_API_KEY", "")),
        "openai": env.get("OPENAI_KEY", env.get("OPENAI_API_KEY", "")),
        "gemini": env.get("GEMINI_KEY", env.get("GEMINI_API_KEY", "")),
    }

    models = MODELS
    if args.model:
        models = [m for m in MODELS if m[1] == args.model]
        if not models:
            print(f"Model {args.model} not found"); sys.exit(1)

    # Filter checkpoints by line range
    checkpoints = [cp for cp in CHECKPOINTS if start_line <= cp[0] <= end_line]

    print(f"Testing checkpoints {start_line}-{end_line} ({len(checkpoints)} checkpoints)")
    print(f"Models: {', '.join(m[2] for m in models)}")
    print(f"Spending guard: {MAX_SPEND_CENTS:.0f}c max")
    print(f"{'=' * 100}")

    all_results = []

    for line_no, element, checkpoint, ok_text, notok_text, hint, ok_photos, notok_photos in checkpoints:
        print(f"\n--- Line {line_no}: {element} — {checkpoint} ---")

        user_prompt = f"{element} — {checkpoint}\nOK: {ok_text}\nNOT_OK: {notok_text}"
        if hint:
            user_prompt += f"\nLook for: {hint}"

        # Test OK photos
        for photo_file in ok_photos:
            photo_path = os.path.join(photo_dir, photo_file)
            if not os.path.exists(photo_path):
                print(f"  [SKIP] {photo_file} — not found")
                continue

            with open(photo_path, "rb") as f:
                b64 = base64.b64encode(f.read()).decode()

            for prov, model_id, display, in_cost, out_cost in models:
                key = keys.get(prov, "")
                if not key: continue
                check_guard()
                time.sleep(CALL_DELAY_SEC)

                try:
                    text, in_tok, out_tok, latency = CALLERS[prov](key, model_id, b64, "image/jpeg", user_prompt)
                    j, e, c = parse_response(text)
                    cost = (in_tok * in_cost + out_tok * out_cost) / 1_000_000
                    record_cost(cost)
                    correct = "CORRECT" if j == "OK" else "WRONG"
                    all_results.append((line_no, element, checkpoint, "OK", photo_file, display, j, c, in_tok, out_tok, cost, correct))
                    mark = "v" if correct == "CORRECT" else "X"
                    print(f"  [{mark}] OK photo | {display:<12} | {j:<7} {c:.0%} | {cost*100:.2f}c | {photo_file}")
                except Exception as ex:
                    all_results.append((line_no, element, checkpoint, "OK", photo_file, display, "ERROR", 0, 0, 0, 0, str(ex)[:50]))
                    print(f"  [!] OK photo | {display:<12} | ERROR | {str(ex)[:60]}")

        # Test NOT OK photos
        for photo_file in notok_photos:
            photo_path = os.path.join(photo_dir, photo_file)
            if not os.path.exists(photo_path):
                print(f"  [SKIP] {photo_file} — not found")
                continue

            with open(photo_path, "rb") as f:
                b64 = base64.b64encode(f.read()).decode()

            for prov, model_id, display, in_cost, out_cost in models:
                key = keys.get(prov, "")
                if not key: continue
                check_guard()
                time.sleep(CALL_DELAY_SEC)

                try:
                    text, in_tok, out_tok, latency = CALLERS[prov](key, model_id, b64, "image/jpeg", user_prompt)
                    j, e, c = parse_response(text)
                    cost = (in_tok * in_cost + out_tok * out_cost) / 1_000_000
                    record_cost(cost)
                    correct = "CORRECT" if j == "NOT_OK" else "WRONG"
                    all_results.append((line_no, element, checkpoint, "NOT_OK", photo_file, display, j, c, in_tok, out_tok, cost, correct))
                    mark = "v" if correct == "CORRECT" else "X"
                    expl = f" — {e}" if e else ""
                    print(f"  [{mark}] NOK photo | {display:<12} | {j:<7} {c:.0%} | {cost*100:.2f}c | {photo_file}{expl}")
                except Exception as ex:
                    all_results.append((line_no, element, checkpoint, "NOT_OK", photo_file, display, "ERROR", 0, 0, 0, 0, str(ex)[:50]))
                    print(f"  [!] NOK photo | {display:<12} | ERROR | {str(ex)[:60]}")

    # Summary
    print(f"\n{'=' * 100}")
    print(f"{'SUMMARY':^100}")
    print(f"{'=' * 100}")

    correct = sum(1 for r in all_results if r[11] == "CORRECT")
    wrong = sum(1 for r in all_results if r[11] == "WRONG")
    errors = sum(1 for r in all_results if r[6] == "ERROR")
    skipped = sum(1 for r in all_results if r[6] == "SKIP")
    total = correct + wrong + errors

    print(f"\n  Total calls: {total}")
    print(f"  Correct:     {correct}/{total} ({correct/total*100:.0f}%)" if total > 0 else "")
    print(f"  Wrong:       {wrong}")
    print(f"  Errors:      {errors}")
    print(f"  Total cost:  {total_spent_cents:.2f}c")

    if wrong > 0:
        print(f"\n  WRONG answers:")
        for r in all_results:
            if r[11] == "WRONG":
                print(f"    Line {r[0]} {r[1]}—{r[2]} | Expected {r[3]} | Got {r[6]} | {r[5]} | {r[4]}")

if __name__ == "__main__":
    main()
