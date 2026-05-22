# Decisions Log — Voice Engine v1 + Vehicle Feature Catalog v1

Last updated: `2026-05-22`

Running log of every non-trivial decision made during v1 build. Each entry: what was decided, why, by whom, and what to revisit. Reviewable at end of day.

## Already-locked decisions (from PRD revisions in conversation)

| ID | Decision | Rationale | Revisit when |
|---|---|---|---|
| D1 | TTS dropped as the test audio source; lab uses ElevenLabs *only* for accent breadth + dealership noise overlay + a small human anchor for calibration. | TTS audio is too clean to validate STT under real conditions; human anchor catches over-optimistic TTS scores. | Real STT runs show TTS-vs-human delta > 15% on any strategy → invest in commissioning actors. |
| D2 | v1 STT strategies in lab = Apple SpeechTranscriber + Argmax. | One free native, one cross-platform vendor. Cloud (Deepgram/Gladia) deferred — dealership Wi-Fi risk. | Lab reports tell us if either is insufficient. |
| D3 | v1 STT in library skeleton = Apple SpeechTranscriber (iOS) + Mock. Argmax mobile wiring deferred. | Skeleton proves the contract holds against a real engine; lab decides if Argmax mobile is needed. | Lab winner is not Apple on iOS → wire Argmax mobile. |
| D4 | Cue atoms = features (the Feature in the catalog carries its own `cue_phrases`; no separate cue-atom layer). | One source of truth, no duplication, simpler authoring. | If a feature needs multiple distinct cue identities (rare). |
| D5 | Cue pack architecture = computed at runtime as `(universal workflow cues) ∪ (cue atoms projected from features available on target_trim)`. | Mirrors the product behavior; one composition rule for both lab and future app. | If audit-item granularity demands per-item packs (downstream concern). |
| D6 | Cue context = audit item ID (not NADA step). | Steps bundle multiple audit items; using step-level loses precision. | If audit ledger structure changes materially. |
| D7 | Vehicle Feature Catalog is **NOT** a database engine. YAML files in git + in-memory loaders + helper API. No SQLite, no migrations. | Hand-curation, PR review, small scale. | Catalog grows past ~10k entities or perf becomes a problem. |
| D8 | Catalog ships brand-extensible from day one. Honda is one make; toy Toyota fixture proves the schema isn't Honda-coupled. | Avoids late-stage rewrite when second brand lands. | N/A — design goal. |
| D9 | Catalog storage = YAML (not JSON, not SQLite). | Hand-edit, PR-review, comments allowed. | If parse speed becomes an issue → JSON build step from YAML. |
| D10 | Module name = `vehicle-feature-catalog/` (not `-database`). | "Database" carried runtime-engine connotation that was misleading. | N/A. |
| D11 | F1 = hand-written Python dataclasses + TS interfaces (no codegen for shared types in v1). | Surface is ~4 types; codegen infra cost > benefit. Drift caught by cross-language fixture test. | Surface exceeds ~8 types or drift bugs appear. |
| D12 | F2 = `cue_atoms_for_features` NOT on the catalog facade. Consumer (lab orchestrator, future app) projects `Feature → CueAtom` itself. | Keeps catalog vehicle-domain-pure; voice-engine vocabulary doesn't leak into catalog. | If two consumers duplicate the projection identically (unlikely). |
| D13 | Honda US v1 = 9 current models (Civic, Accord, CR-V incl. Hybrid, HR-V, Pilot, Passport, Odyssey, Ridgeline, Prologue). | Full lineup needed for credible coverage. | New Honda model lands. |
| D14 | Script sourcing = hybrid hand-written + transcribed real Honda YouTube walkarounds, with attribution per script. | Authentic dealer language without commissioning actors. | If YouTube fair-use blocked (OQ13) → fall back to fully hand-written + commissioned. |
| D15 | Q4: scope is harness + skeleton library (option `b`). | Lab is 90% of v1 effort; skeleton catches the design and prevents drift. | N/A. |
| D16 | Q5: lab v1 = STT + cue validation only. Red-path / full app testing deferred to road-to-sale-app PRD. Lab still includes ≥ 3 negative scripts for false-positive detection. | Voice-engine scope; app testing is separate concern. | N/A. |
| D17 | Trim-level data in the catalog (not model-level only). | Brochure structure; "heated seats" cuts across many models, must know which trims. | N/A. |
| D18 | One PRD covers both modules. Module docs (`api.md`, `architecture.md`, `flows.md`) per global standards. PRD covers WHAT/WHY only; technical detail in module docs. | Avoids PRD bloat; matches user's global standards. | N/A. |

## Decisions for previously-open questions (taken autonomously per user authorization)

| ID | Open Question | Decision | Rationale | Revisit when |
|---|---|---|---|---|
| OQ4 | Honda model year scope | **2026 only** in v1 | Newest data; dealer inventory shifts toward current MY. | Lab data shows previous-MY inventory still common on real lots. |
| OQ8 | Noise overlay SNR levels | **Clean (no overlay) + ~10 dB SNR** for v1 | One overlay level is enough to break the "clean TTS" optimism bias; second level adds combinatoric cost. | If clean-vs-10dB delta isn't enough signal. |
| OQ10 | Custom vocabulary support | **Test BOTH with and without dealership vocabulary loaded.** Two run modes per strategy in the lab. | Custom vocab is a real product knob; we need the delta. | N/A — keep both modes. |
| OQ11 | Confidence normalization across engines | **Linear remap to 0–1 per engine; the per-engine mapping is documented in `voice-engine/docs/architecture.md`.** Strategies that emit no confidence (Apple in some configs) use `null`, not synthetic values. | Honest about engine differences; comparison reports show per-engine + normalized columns side-by-side. | If a vendor exposes calibrated probabilities (rare). |
| OQ12 | Script sourcing split | **Target 60% hand-written + 40% YouTube-transcribed** in the full set (12–15 scripts). v1 build delivers 2 hand-written exemplars. | Authentic dealer language without over-relying on either source. | If hand-written sounds inauthentic vs YouTube → tilt toward more transcript. |

## Decisions still deferred to the user (in `dev/tasks/0001-prd-voice-engine.md` open questions)

These are blocking for downstream work, not v1 scaffolding. Logged here for end-of-day review.

- **OQ1** — Apple SpeechTranscriber macOS variant: needs API verification + minimum OS version pinning before real implementation lands. v1 ships a stub that raises `NotImplementedError`.
- **OQ2** — Argmax Pro SDK 2 Python/CLI bindings: needs vendor research + license. v1 ships a stub.
- **OQ3** — ElevenLabs voice IDs: needs ElevenLabs account + voice selection. v1 ships a synthesis stub.
- **OQ5** — Brochure-extraction owner for the remaining 8 Honda models. v1 seeds one model (CR-V Hybrid AWD 2026) to prove the schema.
- **OQ6** — Universal workflow cue inventory acceptance. v1 drafts a starter inventory; user reviews.
- **OQ7** — Dealership-room noise track source. v1 ships a noise overlay stub.
- **OQ9** — Human anchor recording protocol. v1 ships placeholder directory.
- **OQ13** — YouTube transcript fair-use boundary. v1 doesn't transcribe; sample scripts are hand-written.

## Implementation choices logged during build

(Will be appended as the build proceeds.)

- **DC1 ()** — placeholder
