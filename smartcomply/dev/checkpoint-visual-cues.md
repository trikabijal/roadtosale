# Checkpoint Visual Cues — Compiled from AI Test Runs

What the AI sees, what it misses, and what the brand should specify.
Each checkpoint has: the current criteria from the Excel, what we learned from testing, and a proposed enhanced hint.

---

## Cross-Cutting Patterns

These visual cues appear across multiple checkpoints. Candidates for the system prompt or for a shared hint library.

| Pattern | Applies To | What We Learned |
|---------|-----------|-----------------|
| **Dust/dirt film** | Lines 2, 5, 11, 13, 15 | Models detect heavy dust but miss light film. Specify: "visible dust when viewed from 3m distance" |
| **Water stain streaks** | Lines 2, 11, 13 | Vertical streak lines below window frames or edges from rain runoff. Models catch this when told to look for it. |
| **Bird droppings** | Lines 2, 5, 13, 15 | White spots on surfaces. Easy for AI to detect when mentioned in criteria. |
| **Moss/algae/weed growth** | Lines 5, 13, 14, 15 | Green organic growth in joints, on shaded surfaces. Haiku catches it, Gemini misses it. Key maintenance indicator. |
| **Assess ONLY what is asked** | ALL | The #1 source of errors. If the checkpoint is "Damage", ONLY assess damage. Ignore cleanliness, branding, age. If "Cleanliness", ONLY assess dirt. Don't fail a Damage checkpoint because branding is old. Don't fail a Cleanliness checkpoint because paint is faded. Each checkpoint is independent. |
| **Faded/sun-bleached ≠ dirty** | Lines 1, 2, 11 | Sun-bleached/faded color is age/UV damage, not dirt. For "Cleanliness" checkpoints, only actual dirt/dust/stains matter. Fading is a separate concern (brand compliance or paint maintenance). |
| **Old branding ≠ damage** | Lines 1, 2 | Old Kia red logo is NOT damage — it's brand non-compliance. For "Damage" checkpoints, only physical defects matter: dents, cracks, peeling, broken panels. Brand compliance needs its own checkpoint. |
| **Dealer branding is normal** | Lines 1, 2, 6 | Dealer names on facades (Rick Case Kia, Shelly Motors, Sutton Park) are intentional business signage. Not damage, not graffiti, not a defect. |
| **Photo must show the element** | ALL | If a photo doesn't show the element being checked, reject as INVALID. Don't guess OK/NOT_OK on an irrelevant photo. |
| **Surroundings don't affect the element** | ALL | Construction vans, cones, equipment, or activity near the element don't make the element itself dirty or damaged. Assess the element, not its surroundings. |
| **Night lighting hides flaws** | Lines 1, 2, 10, 11 | Well-lit night photos make everything look pristine. Specify: "inspect in daylight." |
| **Distance affects detection** | Lines 11, 14, 15 | From 10m, slightly grimy glass looks fine. From 3m, it's obviously dirty. Specify inspection distance. |

---

## Per-Checkpoint Detail

### Line 1: Front ACP + Logo — Damage

**Current Excel criteria:**
- OK: No visible damage on ACP or logo
- NOT_OK: Visible dents, scratches, or panel damage

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| 07_kia_exterior_night_ok.jpg | OK 90% | OK 90% | OK |
| 09_kia_exterior_notok_old_panels.jpg | OK 90% | OK 90% | NOT_OK* |
| 11_kia_exterior_notok_worn_cladding.jpg | OK 90% | OK 90% | NOT_OK* |

*These photos show old branding/refurbishment in progress, not physical damage. The AI is arguably correct.

**What we learned:** The AI correctly identifies physical damage (dents, cracks, scratches) but does NOT flag old/outdated branding as "damage." Brand compliance is a separate concern.

**Proposed hint:**
> Check the ACP panel surface for: dents or depressions, scratch marks, cracks, peeling or bubbling coating, loose panel seams with visible gaps, bent or warped sections. Check the logo for: missing or broken characters, fading that makes text unreadable, physical damage to individual letters. Note: old branding (e.g., red Kia logo instead of current black) is a brand compliance issue, not physical damage.

---

### Line 2: Front ACP + Logo — Cleanliness

**Current Excel criteria:**
- OK: ACP and logo clean, no dust or streaks
- NOT_OK: Dusty, stained, or streaked

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| 07_kia_exterior_night_ok.jpg | OK 90% | OK 90% | OK |
| 10_kia_exterior_notok_faded_facade.jpg | OK 90% | - | NOT_OK* |

*Faded facade — AI sees "old" not "dirty." Correct — fading is UV damage, not dirt.

**What we learned:** "Dusty, stained, or streaked" is too vague. The AI needs to know what dust looks like on an ACP panel from a photo.

**Proposed hint:**
> Dust appears as a dull, matte film on what should be a glossy panel surface. Stains appear as discolored patches, often darker than the surrounding area. Streaks appear as vertical lines, typically from rain runoff carrying dirt, visible as lighter or darker lines on the panel. Also check for: cobwebs in corners where ACP meets the building, grime buildup in the groove where the logo meets the panel, bird droppings (white spots).

---

### Line 4: Pylon — Visibility

**Current Excel criteria:**
- OK: Pylon fully visible from approach road
- NOT_OK: Obstructed or not visible from road

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| 02_kia_pylon_signage_ok.jpg | OK 95% | - | OK |
| cp04_pylon_visibility_notok.jpg | ERROR | - | NOT_OK |

**What we learned:** This is a binary check — either the pylon is visible or it isn't. The error on the NOT_OK photo was a 400 Bad Request (file issue), not an accuracy problem.

**Proposed hint:**
> The Kia pylon (tall freestanding sign) should be clearly visible when approaching the dealership from the main road. Check: Is the pylon present? Is the sign face readable? Is it obstructed by trees, other signs, parked vehicles, or construction? Is the pylon illuminated (if night photo)? A dealership with no pylon sign or with a "TO LET/FOR SALE" sign instead of the Kia brand is NOT_OK.

---

### Line 5: Pylon — Cleanliness

**Current Excel criteria:**
- OK: Pylon clean, no dust or droppings
- NOT_OK: Dirty, stained, or covered in droppings

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| 02_kia_pylon_signage_ok.jpg | OK 90% | - | OK |

**What we learned:** No NOT_OK photos tested yet. Need photos of dirty pylons.

**Proposed hint:**
> Pylon surfaces should be free of: bird droppings (white/grey spots, common on top edges and horizontal surfaces), green algae or moss (especially on lower sections in humid areas), dirt accumulation reducing text legibility, rust stains from metal fixtures, cobwebs on sign face or lighting fixtures. The illumination panels (if present) should be clear, not yellowed or clouded.

---

### Line 6: Directional Signage — Clarity

**Current Excel criteria:**
- OK: Signage correct, legible, and logical
- NOT_OK: Missing, illegible, or misleading

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| cp06_signage_clarity_ok.jpg | OK 90% | - | OK |
| cp06_signage_clarity_notok.jpg | NOT_OK 80% "deteriorated wall, rust stains" | - | NOT_OK |

**What we learned:** Haiku correctly identifies degraded signage.

**Proposed hint:**
> Directional signs (arrows to parking, service, showroom, exit) should be: clearly readable from a moving vehicle, using current Kia brand fonts and colors, pointing in the correct direction, free from fading or peeling, properly mounted (not leaning, fallen, or taped). Look for: faded text that's hard to read, handwritten or temporary signs replacing permanent ones, conflicting arrows, signs obscured by vegetation, signs referencing departments or areas that no longer exist.

---

### Line 7: Valet Parking — Signage visibility
### Line 8: Security Guard — Uniform
### Line 9: Security Guard — Grooming

**Not tested yet.** Lines 8-9 require people photos (privacy considerations for testing).

---

### Line 10: Facade Glass — Damage

**Current Excel criteria:**
- OK: No cracks, chips, or broken sections
- NOT_OK: Cracks, chips, or broken sections

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| 07_kia_exterior_night_ok.jpg | OK 90% | OK 90% | OK |
| cp10_facade_glass_notok.jpg | NOT_OK 95% "spider web crack" | NOT_OK 100% "impact crack with radiating fractures" | NOT_OK |

**100% accuracy.** Both models nail this.

**Proposed hint:**
> Check all glass panels for: spider web or star crack patterns from impact, linear cracks along edges or corners, chips (small missing pieces, often at edges), complete breaks with missing sections. Also check for: temporary repairs (tape, board, plastic sheeting covering a break), glass that has separated from the frame, milky/foggy areas in sealed double-glazed units (seal failure). Minor surface scratches visible only at certain angles are acceptable.

---

### Line 11: Facade Glass — Cleanliness

**Current Excel criteria:**
- OK: Streak-free and clean
- NOT_OK: Dirty, streaked, or smudged

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| 07_kia_exterior_night_ok.jpg | OK 90% | OK 90% | OK |
| 08_kia_exterior_notok_before_refurb.jpg | NOT_OK 80% (first run), OK 85% (second run) | OK 90% | NOT_OK |

**Inconsistent.** Haiku catches it sometimes. Gemini misses it. The photo shows general aging/grime, not dramatic dirt.

**What we learned:** "Clean glass" is relative. A dealership that's been open 10 years will have glass that looks "fine" from a distance but grimy up close. The brand needs to specify the standard.

**Proposed hint:**
> The showroom glass should appear transparent — you should be able to clearly see the cars inside the showroom through the glass. Check for: hazy/cloudy appearance (dust film), circular water spots (mineral deposits from sprinklers or rain), vertical streak lines (rain runoff carrying dirt down the glass), fingerprint clusters near door handles and push plates, cobwebs in window corners (especially upper corners), interior side of glass with handprints or display tape residue. The glass should reflect the sky/surroundings clearly — a matte, non-reflective appearance indicates a dirt film.

---

### Line 12: Granite — Cracks

**Current Excel criteria:**
- OK: No cracks or chips
- NOT_OK: Visible cracks or broken sections

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| cp12_granite_cracks_ok.jpg | OK 90% | OK 90% | OK |
| cp12_granite_cracks_notok.jpg | NOT_OK 95% "cracks in granite pavers" | NOT_OK 90% "uneven gaps in paving" | NOT_OK |

**100% accuracy.**

**Proposed hint:**
> Check granite/stone flooring for: cracks running through individual tiles (not along grout lines — along grout is normal joints), displaced or shifted tiles creating lips/trip hazards, missing grout exposing substrate underneath, chipped tile edges or corners (especially near doorways and heavy traffic areas), tiles that have lifted or "tented" upward, water pooling in low spots indicating uneven settlement. Hairline cracks in natural stone can be acceptable if they don't compromise the surface — use judgment on severity.

---

### Line 13: Granite — Cleanliness

**Current Excel criteria:**
- OK: Polished and clean
- NOT_OK: Stained or grimy

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| cp12_granite_cracks_ok.jpg | OK 95% | OK 90% | OK |

**No NOT_OK photos tested yet.**

**Proposed hint:**
> Granite should have a consistent sheen/polish — dull patches indicate wear or staining. Check for: oil/grease stains (dark spots, common near service area entrances), rust stains (orange/brown, from metal furniture legs or planters), water stains (white mineral deposits), chewing gum residue, tire marks or rubber scuffs from vehicles, general grime in grout lines (grout should be consistent color, not darkened). In wet conditions, surface should not have standing puddles (indicates level issues, not cleanliness).

---

### Line 14: Pavers — Damage

**Current Excel criteria:**
- OK: All pavers intact and level
- NOT_OK: Broken, sunken, or missing pavers

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| cp14_pavers_damage_ok.jpg | OK 90% | OK 90% | OK |
| cp14_pavers_damage_notok.jpg | NOT_OK 90% "moss/algae, weeds, neglected" | OK 90% (missed) | NOT_OK |

**Haiku catches it, Gemini misses.** The photo shows weeds/moss in joints, not broken pavers. Gemini focused on "broken" literally.

**What we learned:** "Damage" to pavers includes joint degradation that allows weed growth — this needs to be spelled out.

**Proposed hint:**
> Check pavers for: cracked or split individual pavers, sunken pavers creating uneven surfaces or trip hazards, pavers shifted out of alignment (gaps between them), missing pavers leaving holes, weeds or grass growing through joints (indicates joint sand has washed out — maintenance issue), moss or algae growth on paver surfaces (green/dark patches, common in shaded areas), efflorescence (white powdery deposits on paver surfaces). A level surface where all pavers are flush with their neighbors is OK even if the pavers show minor wear.

---

### Line 15: Pavers — Cleanliness

**Current Excel criteria:**
- OK: Clean, no stains or debris
- NOT_OK: Stained, grimy, or littered

**Test results:**
| Photo | Haiku 4.5 | Gemini Flash | Expected |
|-------|:---------:|:------------:|:--------:|
| cp14_pavers_damage_ok.jpg | OK 90% | - | OK |

**Proposed hint:**
> Check paver surfaces for: oil/fluid stains from vehicles (dark spots, typically in parking areas and driveways), tire marks or rubber residue (black streaks), moss or algae growth (green/dark patches, especially in shaded or damp areas), accumulated leaf debris or dirt in paver joints, cigarette butts or litter, chewing gum, standing water with visible contamination (oil sheen). Clean pavers should have uniform color — dark patches or staining indicates contamination.

---

## What to Promote to System Prompt

Based on the patterns above, these visual cues are so common across checkpoints that they should be in the system-level instructions rather than repeated per checkpoint:

1. **Organic growth = maintenance failure.** Moss, algae, weeds, bird droppings anywhere on the facility indicate neglected maintenance. Always flag as NOT_OK.

2. **Inspection standard = daylight, 3 meters.** Judge cleanliness based on what's visible in normal daylight from approximately 3 meters distance. Night photos and extreme close-ups distort the assessment.

3. **Age vs defect.** Faded paint, weathered surfaces, and outdated branding are NOT the same as damage or dirt. Distinguish between:
   - Physical damage (dents, cracks, breaks) — always NOT_OK
   - Cleanliness (dirt, stains, droppings) — always NOT_OK
   - Brand compliance (old logo, wrong colors) — only NOT_OK if the checkpoint specifically checks brand compliance

4. **Surface sheen indicates cleanliness.** Glass, granite, and painted surfaces should reflect light. A matte/dull appearance on a surface that should be glossy indicates a dirt film.
