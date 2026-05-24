# AI Prompt Hints — Compiled from Test Runs

These are the visual cues extracted from actual AI assessments of Kia dealership photos.
Each entry becomes the `ai_prompt_hint` field for the corresponding checkpoint option.
Updated as we test more photos and learn what the models catch or miss.

---

## Line 1: Front ACP + Logo — Damage

**What the AI catches well:** Obvious physical damage — dents, cracks, impact marks on panels.

**What it misses:** Old/faded branding treated as "no damage" because the panel itself is physically intact. The AI distinguishes between physical damage and brand non-compliance — these need separate checkpoints.

**Hint for OK:**
> ACP panels should be smooth, flat, and free of dents, cracks, scratches, or impact marks. Panel seams should be tight with no gaps. Logo lettering should be intact with no missing or broken characters.

**Hint for NOT_OK:**
> Look for: dents or depressions in the panel surface, scratch marks, cracks, peeling coating, loose or separated panel seams, bent or warped sections, missing or broken logo characters, impact damage marks.

---

## Line 2: Front ACP + Logo — Cleanliness

**What the AI catches well:** Dust accumulation, visible dirt on surfaces.

**What it misses:** Faded/sun-bleached panels — the AI reads "faded" as "old" not "dirty." Color inconsistency from sun damage is NOT the same as dirt.

**Hint for OK:**
> Panel surface should have uniform color with no visible dust, dirt, water stain marks, bird droppings, or streak lines. Logo should be clean and clearly readable.

**Hint for NOT_OK:**
> Look for: dust accumulation visible as a dull film on panels, water runoff stain lines (vertical streaks below window frames), bird droppings (white spots), cobwebs in panel corners or around the logo, grime buildup around the logo edges or panel joints.

---

## Line 4: Pylon — Visibility

**What the AI catches well:** Presence or absence of the pylon sign.

**What it misses:** N/A — this is binary (visible or not).

**Hint for OK:**
> Kia pylon sign should be clearly visible from the approach road, unobstructed by trees, other structures, or vehicles. The sign face should be fully readable.

**Hint for NOT_OK:**
> Look for: pylon partially hidden by tree branches or foliage, other signs or structures blocking the view, pylon lights off (at night), pylon face turned away from approach road, missing pylon entirely.

---

## Line 5: Pylon — Cleanliness

**Hint for OK:**
> Pylon surfaces clean, no visible dirt, bird droppings, or algae/moss growth. Illumination panels clear and unobscured.

**Hint for NOT_OK:**
> Look for: bird droppings on pylon face or frame, algae/moss growth on lower sections, dirt accumulation on the sign face reducing legibility, faded or discolored lighting panels.

---

## Line 6: Directional Signage — Clarity

**Not yet tested with sufficient photos. Need dealership-specific directional signage images.**

**Hint for OK:**
> All directional signs legible, arrows pointing correctly, text clear and readable from driving distance, signs not obscured by vegetation or other objects.

**Hint for NOT_OK:**
> Look for: faded text, missing signs, incorrect arrows, signs obscured by trees or structures, handwritten or temporary replacements for permanent signage.

---

## Line 10: Facade Glass — Damage

**What the AI catches well:** Cracks, impact marks, shattered glass patterns — 100% accuracy from both Haiku and Gemini.

**Observations from AI:**
- "Large spider web crack pattern in glass" (Haiku)
- "Facade glass has a large impact crack with radiating fractures" (Gemini)

**Hint for OK:**
> All glass panels intact with no cracks, chips, or fracture lines. Glass edges should be seated properly in frames with no gaps.

**Hint for NOT_OK:**
> Look for: spider web crack patterns from impact, linear cracks along edges, chips or missing pieces at corners, star fractures, glass separated from frame, temporary tape or board covering a broken section.

---

## Line 11: Facade Glass — Cleanliness

**What the AI catches well:** Haiku detected "Glass doors/windows show visible dirt and streaks" on the old Kia dealership photo.

**What Gemini missed:** Same photo — said OK. The dirt was subtle (general grime, not dramatic).

**Learning:** The brand needs to specify what "dirty glass" looks like from a distance — because from a wide shot, slightly grimy glass can look acceptable.

**Hint for OK:**
> Glass should appear transparent and reflective with no visible smudges, fingerprints, water spots, or dust film. Interior showroom should be clearly visible through the glass.

**Hint for NOT_OK:**
> Look for: hazy/cloudy appearance indicating dust film, water spot circles or mineral deposits, vertical streak lines from rain runoff, fingerprint clusters near door handles, interior barely visible through dirty glass, cobwebs in window corners.

---

## Line 12: Granite — Cracks

**What the AI catches well:** 100% accuracy on both models. Clear detection of:
- "Visible cracks in granite pavers and grout lines" (Haiku)
- "Visible cracks and uneven gaps in paving" (Gemini)

**Hint for OK:**
> Granite/stone flooring should have tight, even joints with intact grout. No visible cracks running through tiles. All tiles level and flush.

**Hint for NOT_OK:**
> Look for: cracks running through individual tiles, displaced or shifted tiles creating uneven surfaces, missing grout between tiles exposing the substrate, chipped tile edges or corners, tiles that have lifted or tented.

---

## Line 14: Pavers — Damage

**What the AI catches well:** Haiku detected "Moss and weeds growth between pavers, poor maintenance."

**What Gemini missed:** Same photo (moss/weeds in joints) — said OK. The weeds were relatively small and the pavers themselves were intact. Gemini focused on physical damage (broken pavers) and missed the maintenance issue.

**Learning:** "Damage" to pavers includes not just broken pavers but also joint degradation that allows weed growth. The criteria should be explicit about this.

**Hint for OK:**
> All pavers intact, level, and properly seated. Joints filled with sand or mortar with no gaps. No vegetation growing through joints.

**Hint for NOT_OK:**
> Look for: broken or cracked individual pavers, sunken pavers creating trip hazards, pavers shifted out of alignment, weeds or moss growing through joints (indicates joint degradation), missing pavers leaving gaps, standing water pooling on uneven sections.

---

## Line 15: Pavers — Cleanliness

**Hint for OK:**
> Paver surface free of oil stains, litter, debris, moss, or algae growth. Joints clean and uniform color.

**Hint for NOT_OK:**
> Look for: oil/fluid stains (dark spots, typically in parking areas), moss or algae (green/dark growth, especially in shaded or damp areas), accumulated leaf debris in joints, cigarette butts or litter, tire marks or rubber residue.

---

## Key Patterns Learned

1. **Physical damage vs brand compliance are different checkpoints.** Old branding ≠ physical damage. The AI correctly distinguishes these — don't conflate them in test expectations.

2. **Subtle cleanliness issues need distance context.** "Slightly grimy from 10 meters away" looks OK. The brand should specify: "When viewed from 3 meters, should appear clean."

3. **Vegetation/organic growth signals maintenance failure.** Moss in paver joints, algae on stone, bird droppings on pylons — these are NOT just cleanliness issues, they indicate neglected maintenance. The hints should explicitly call out organic growth.

4. **Night vs day photos change everything.** Well-lit night photos make the dealership look pristine. Day photos reveal flaws. The hint should specify inspection timing if relevant.

5. **The richer the English criteria, the cheaper the model can be.** Gemini Flash at 0.02c missed subtle issues. Haiku at 0.19c caught them. But with detailed hints, Gemini Flash would likely catch them too — because the prompt would tell it exactly what to look for.

6. **Gemini Flash has aggressive rate limits on free tier.** Running 40 calls in sequence triggers 429s after ~8 calls. Need billing enabled or longer delays. For demo, use Haiku as primary.

---

## Test Results Summary (as of 2026-04-25)

### Haiku 4.5 (primary, $1/$5 per M tokens)
- Lines 10-15 (with proper defect photos): **100% accuracy**
- Lines 1-2 (old branding photos, not actual defects): Correctly said OK — the panels aren't damaged, they're just old branding
- Facade glass cleanliness: Missed — the "before refurb" photo shows general age, not obvious dirt
- **Conclusion:** Haiku is excellent at detecting specific, visible defects. Struggles with "this looks old/neglected" which is a judgment call, not a defect.

### Gemini 2.5 Flash (budget, $0.30/$2.50 per M tokens)
- Rate-limited heavily on free tier — most calls failed with 429
- When it works: catches obvious defects, misses subtle cleanliness issues
- JSON output sometimes malformed (unescaped characters)
- **Conclusion:** Needs billing enabled. With better hints, likely comparable to Haiku.

### Key Insight for Kia Demo
The "wrong" answers on lines 1-2 are actually **correct from the AI's perspective** — those photos show old branding, not physical damage. The AI is right: the ACP isn't damaged, it's just not the new brand.

For Kia to get value from this, they need to write criteria that match what they actually want flagged:
- "Damage" = physical defects (dents, cracks, peeling)
- "Brand Compliance" = correct logo, correct color scheme, current branding
- "Cleanliness" = dust, stains, streaks visible at inspection distance

These are three different checkpoints, not one. The current template conflates them.
