# PRD 0001 — Voice Engine v1 + Vehicle Feature Catalog v1

Last updated: `2026-05-22`

Status: revision 3 (slimmed — technical detail moved to module docs)

This PRD covers the WHAT and WHY. The HOW (facade signatures, schema, file layout, code flows) lives in:

- `vehicle-feature-catalog/docs/api.md`
- `vehicle-feature-catalog/docs/architecture.md`
- `vehicle-feature-catalog/docs/flows.md`
- `voice-engine/docs/api.md`
- `voice-engine/docs/architecture.md`
- `voice-engine/docs/flows.md`

Related product docs:
- `docs/ROAD_TO_SALE_PRD.md` — top-level product PRD
- `docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md` — system-level architecture
- `docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md` — audio architecture
- `dev/docs/ROAD_TO_SALE_AUDIO_LEARNINGS.md`, `_TELEMETRY.md`, `_LANDSCAPE.md`, `_TEST_MATRIX.md`

---

## 1. Introduction / Overview

`Road to Sale by AuditPro` listens to live dealership conversations and detects spoken cues about the vehicle the rep is selling. The product behavior is concrete: when a salesperson says *"this CR-V Hybrid has wireless Apple CarPlay,"* the app must (a) understand that *Wireless Apple CarPlay* is a real feature on the *2026 CR-V Hybrid AWD Sport Touring* the rep is showing, (b) mark that feature as demonstrated, and (c) eventually filter Honda inventory by what was actually demoed.

This means the voice layer cannot be validated against generic phrases. It must be validated against real Honda model and feature data, real dealership language, and real audit-item semantics.

This PRD covers v1 of two systems built in parallel:

1. **Vehicle Feature Catalog** — a normalized, brand-extensible catalog of vehicle Makes → Models → Trims → Features and a sparse trim×feature availability matrix. Honda US first. Hand-curated from Honda US PDF brochures. YAML files in git, loaded in-memory. No database engine.
2. **Voice Engine** — strategy-based STT + cue matching layer, plus a Python comparison lab that runs the matrix of (script × accent × noise × strategy) and produces a comparison report. Brand-agnostic and product-agnostic.

The road-to-sale-app is deferred to a future PRD. Both systems above must exist before the app can do meaningful work.

## 2. Goals

| # | Goal | Measurable outcome |
|---|---|---|
| G1 | Real, brand-extensible vehicle feature catalog, Honda first | All 9 current Honda US models, all trims, all standard + optional features, sourced from Honda US brochures |
| G2 | Cue detection grounded in real Honda feature names and real dealership language | Every cue atom in the lab traces back to either a `Feature.id` in the catalog or a documented universal workflow cue |
| G3 | Pick the STT strategy that ships in the mobile app | Comparison report names a winner per platform, backed by data across the script × accent × noise matrix |
| G4 | Lock the `TranscriptionStrategy` contract before the mobile library work begins | One Python ABC + one TS interface, field-identical event shape, two real strategies hitting it |
| G5 | Cheap to add a new strategy AND cheap to add a new vehicle make | New strategy = implement interface + register; new make = add YAML files under `vehicle-feature-catalog/data/` |
| G6 | Voice engine stays reusable beyond Road to Sale | No NADA-step or Honda-specific code inside `voice-engine/`. Cue atoms are loaded from outside |

## 3. User Stories

**Engineer running the comparison.**
- As an engineer running the lab, I want one command to regenerate audio, run all strategies, and emit a report, so I don't have to hand-orchestrate every comparison.
- As an engineer adding Deepgram or Argmax-on-Android later, I want to implement one interface and register it, so I don't have to touch the scoring or reporting layers.

**Engineer maintaining the vehicle catalog.**
- As an engineer adding a new Honda model year, I want to add a YAML file per trim and edit the matrix file, so I can ship new inventory data with a single PR.
- As an engineer extending the catalog to Toyota later, I want to add `data/makes/toyota.yaml` and the rest follows the existing schema, so there's no schema rewrite.

**Mobile engineer (future consumer).**
- As the mobile engineer wiring STT into Road to Sale, I want a stable `TranscriptionStrategy` interface validated against one real engine, so I don't redesign the contract during integration.
- As the mobile engineer building the cue interpreter, I want to load the trim's eligible features from the vehicle catalog, so I can scope which cues are active given the rep's selected vehicle.

## 4. Functional Requirements

### 4.1 Vehicle Feature Catalog

1. Cover the 9 current Honda US models — Civic, Accord, CR-V (incl. Hybrid), HR-V, Pilot, Passport, Odyssey, Ridgeline, Prologue. All trims per Honda US PDF brochures.
2. Catalog data is hand-curated from Honda US PDF brochures (downloadable from `automobiles.honda.com`), cross-checked against Honda's build-and-price tool.
3. Data is stored as YAML files in git. No SQLite, no server, no migrations. Schema defined in `vehicle-feature-catalog/docs/architecture.md`.
4. Public access goes through one facade per language (`VehicleFeatureCatalog`), spec'd in `vehicle-feature-catalog/docs/api.md`. No external code imports catalog internals.
5. Catalog is immutable post-load. Updates happen by editing YAML in git, not via API.
6. Catalog ships a CI / pre-commit validator that fails on broken references, empty cue_phrases, duplicate IDs, or unattached trims.
7. Catalog is brand-extensible. A tiny second-brand fixture (1 Toyota make, 1 model, 1 trim) ships with v1 to prove the schema is not Honda-coupled.
8. Features carry their cue phrases directly. No separate cue layer. The Feature IS the cue source for feature-driven cues.
9. Universal workflow cues (hospitality, discovery, finance handoff — cues not tied to vehicle features) live OUTSIDE the catalog, in `voice-engine/lab/cue-packs/universal_workflow_cues.yaml`. Same atom shape; different home.

### 4.2 Voice Engine — Comparison Lab

10. Ship 12–15 dealership conversation scripts. Cover ≥ 5 Honda models across the set. Each script walks through multiple NADA steps. At least 3 scripts are NEGATIVE (deliberately omit specific cues to test for false-positive hallucination).
11. Script content uses real Honda feature names and dealer language. Sourcing is hybrid: hand-written against the catalog + transcribed real Honda dealer YouTube walkarounds (with source attribution per script).
12. Each script declares its target trim, expected cue list with timestamps, and negative cue list. Script schema spec'd in `voice-engine/docs/architecture.md`.
13. Audio fixtures are synthesized from scripts via ElevenLabs TTS across 5 accent profiles (neutral American, Southern, Midwest, Northeast, fast speaker), at 2 noise overlay levels (clean + moderate dealership-room noise). Target ≥ 100 clips.
14. The lab supports a small human calibration anchor set (≥ 5 real human clips across ≥ 2 scripts and ≥ 3 voices) run through the same pipeline. Anchor data flags TTS-vs-human bias for any strategy.
15. The lab runs two strategies in v1: `AppleSpeechTranscriberStrategy` (macOS Speech framework) and `ArgmaxStrategy` (Argmax Pro SDK 2). Both implement the same Python ABC.
16. Every strategy emits BOTH partial and final transcript events tagged with `stability`. A strategy that exposes only one stream is disqualified.
17. The lab computes the active cue set per clip as `(universal workflow cues) ∪ (cue atoms projected from features available on script.target_trim_id)`. This mirrors how the future mobile app composes the active set at session start.
18. The lab classifies every expected cue as `pass | partial | fail` per the rules in `dev/docs/ROAD_TO_SALE_AUDIO_TEST_MATRIX.md`. It flags any negative cue that fires as `false_positive`.
19. The lab captures latency per detected cue: time to first partial, time to final, time to cue detection. Reported per strategy at P50 / P95 / P99.
20. Each lab run emits a structured report under `voice-engine/lab/reports/run-YYYYMMDD-HHmm/`: `summary.md` (headline + named winner per platform), per-strategy CSV + JSONL transcripts, false-positives CSV.
21. Lab CLI exposes three commands: `synth` (synthesize fixtures), `run` (run comparison), `validate-catalog` (catalog integrity check).
22. Lab runs on a developer laptop. No mobile device, React Native install, or Xcode project required.

### 4.3 Voice Engine — Library Skeleton

23. Define `TranscriptionStrategy`, `Session`, and event types in TypeScript matching the lab's Python shape field-for-field. Spec in `voice-engine/docs/api.md`.
24. Ship `AppleSpeechTranscriberStrategy` (iOS) wired end-to-end through Swift native module + TS bridge, proving the contract holds against a real engine.
25. Ship `MockTranscriptionStrategy` that replays a JSONL event file (used by app-side tests and showcase mode).
26. Argmax mobile wiring is deferred until the lab tells us whether iOS native is sufficient.
27. `voice-engine/build.sh` builds the TS package + iOS native module. Android exits zero with a "not yet implemented" message.

### 4.4 Cross-cutting

28. The `TranscriptEvent`, `CueAtom`, `CueDetection`, and `SessionContext` types are field-identical between Python and TS. Hand-written in both languages; a cross-language fixture test catches drift in CI.
29. The catalog facade does NOT expose `CueAtom` projection. Consumers (lab orchestrator, future app) project `Feature → CueAtom` themselves with a small glue function. Keeps the catalog vehicle-domain-pure.
30. Voice engine has no imports from `vehicle-feature-catalog/` and vice versa. Vendor SDKs stay behind individual strategy files. CI enforces static import checks.
31. Telemetry hook call sites are present in v1; the exporter is a later PRD.
32. Strategies register in a single registry file per language. Adding a strategy is a one-file change outside the strategy itself.
33. Catalog changes and voice engine changes land in separate PRs. Conventional commits. Feature/fix branches. Never commit to main.

## 5. Non-Goals (Out of Scope for v1)

| Non-goal | Reason |
|---|---|
| Production mobile library beyond the skeleton | Lab hasn't picked a winner yet |
| Argmax wired into the mobile skeleton | Lab will decide if Argmax is needed on iOS or only Android |
| Cloud STT strategies (Deepgram, Gladia, AssemblyAI) | Dealership Wi-Fi unreliable; on-device first |
| Live cue detection inside the mobile skeleton | Skeleton emits events only; cue matching for live use lands with the road-to-sale-app PRD |
| Full red-path / miss testing of the app | v1 includes 3+ negative scripts; full red/green app-level testing is in the road-to-sale-app PRD |
| Summarization | Separate strategy, separate concern, later |
| Diarization in the live path | Async, post-session |
| Real telemetry exporter / dashboards | Hooks now, exporter later |
| Trim-level evaluation in the lab beyond model-level grouping | Catalog is trim-level; lab evaluation rolls up to model-level in v1 |
| Multiple model years | Current MY only in v1 (OQ4) |
| Vehicle data scraper / automation | Hand-curation in v1 |
| Brands beyond Honda | Schema is brand-extensible; a tiny Toyota fixture proves it, no broad coverage |
| CI / release pipeline for the modules | Comes once the library is past skeleton stage |
| Commissioned Fiverr/Upwork voice actors | Human anchor is small and self-recorded; commission later if data warrants |
| Cross-language codegen for shared types | Hand-written in v1 (F1=a). Add codegen later if type surface grows |
| `cue_atoms_for_features` on the catalog facade | Catalog stays vehicle-pure; consumer does the projection (F2=b) |

## 6. Success Metrics

For v1 to be considered done:

| Metric | Target |
|---|---|
| Honda US models covered in the catalog | 9 / 9 |
| Trims per model | All from Honda US brochures |
| Universal features defined | ≥ 30 |
| Honda-brand-specific features defined | ≥ 20 |
| TrimFeature matrix cells populated | ≥ 1000 |
| Toy second-brand fixture | 1 Make, 1 Model, 1 Trim |
| Scripts in the lab | 12–15 (incl. ≥ 3 negative) |
| Honda models covered across the script set | ≥ 5 of 9 |
| Audio fixtures generated | ≥ 100 clips across scripts × accents × noise |
| Human calibration clips | ≥ 5 across 2+ scripts and 3+ voices |
| Strategies in the lab | 2 (Apple + Argmax) |
| Strategies in the library skeleton | 1 real (Apple) + 1 mock |
| Time for a new engineer to add a new strategy (measured by a teammate) | < 1 day |
| Time for a new engineer to add a new Honda model | < 1 day |
| `summary.md` names a winner per platform with the data behind it | yes/no |

Cue accuracy and latency are **measured and reported, not gated** in v1. Thresholds get set in a follow-up PRD once we have data.

## 7. Open Questions

| # | Question | Why it matters |
|---|---|---|
| OQ1 | Apple SpeechTranscriber — confirm macOS Speech framework offers an equivalent of the iOS SpeechTranscriber API and minimum OS versions | Affects `AppleSpeechTranscriberStrategy` in the lab |
| OQ2 | Argmax Pro SDK 2 — confirm Python or CLI bindings exist; if not, lab invokes a small Swift/Kotlin harness | Affects `ArgmaxStrategy` implementation in the lab |
| OQ3 | ElevenLabs voice IDs — pick 5 specific voices for the 5 accent profiles; document them | Reproducibility |
| OQ4 | Honda model year scope — current MY (2026) only, or include 2025 inventory still on dealer lots? Default: current MY only, revisit if telemetry warrants | Catalog scope |
| OQ5 | Brochure-extraction owner — ~20–30 hours of hand-curation. Engineer time, contractor, or a former Honda salesperson on Upwork? | Schedule and budget |
| OQ6 | Universal workflow cue inventory — full list of universal cues for the NADA workflow needs to be enumerated before scripts can be written | Script writing blocked on this |
| OQ7 | Dealership-room noise track — source it (open-licensed recording or self-recorded), commit one canonical file | Reproducibility |
| OQ8 | Exact SNR levels for noise overlay — propose clean + ~10 dB SNR; confirm | Standardize the noise dimension |
| OQ9 | Human anchor recording protocol — phone vs USB mic, room conditions, who records | Comparability |
| OQ10 | Custom vocabulary support — Argmax supports it, Apple's is limited. Do we test with dealership vocab loaded? | Could materially change comparison outcome |
| OQ11 | Confidence score normalization — Apple and Argmax expose confidence differently; how do we normalize to a 0–1 scale for comparison? | Apples-to-apples |
| OQ12 | Script-sourcing split — target hand-written vs YouTube-transcribed | Schedule and authenticity |
| OQ13 | YouTube transcript attribution and fair-use boundary — confirm fair-use testing of public dealer walkaround videos is acceptable for internal evaluation | Legal / IP |

---

**Next step after this PRD is accepted:** generate the task list per `~/.claude/workflows/generate-tasks.md` — parent tasks first, wait for `Go`, then sub-tasks.
