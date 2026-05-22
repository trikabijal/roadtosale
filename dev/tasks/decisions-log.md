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

## User-confirmed answers (received 2026-05-22, mid-build)

The user wrote answers directly into `dev/tasks/0001-prd-voice-engine.md` Section 7. Captured here:

| OQ | Status | User answer / direction | Action |
|---|---|---|---|
| OQ1 | research | "I dont know — you will have to search and find" | Launch research agent on Apple SpeechTranscriber macOS variant + min OS versions |
| OQ2 | research | "For you to find" | Launch research agent on Argmax Pro SDK 2 Python/CLI bindings + license model |
| OQ3 | still deferred | (blank — needs ElevenLabs account + voice selection) | Synthesis stays stubbed |
| OQ4 | confirmed | "2026 only is fine for now" | No change (matches autonomous default) |
| OQ5 | **scope change** | "if you can scrape and download that will be wonderful" | New work item: build a Honda US brochure scraper instead of hand-curation for remaining 8 models. CR-V Hybrid AWD seed stays as proof-of-shape. |
| OQ6 | **scope change** | "This should be built by you" | New work item: expand `universal_workflow_cues.yaml` from 12 starter cues to a full NADA workflow inventory (~25–35 cues) |
| OQ7 | still deferred | (blank — noise track sourcing) | Noise overlay stays stubbed |
| OQ8 | still deferred | (blank) | Default holds: clean + ~10 dB SNR |
| OQ9 | user-owned | "I will do this" | Leave `voice-engine/lab/fixtures/human/` placeholder + a short recording-protocol README stub for user to fill |
| OQ10 | confirmed | "This will be awesome" | Dual-mode (with/without dealership custom vocab) per autonomous default |
| OQ11 | confirmed | "0–1 probability scale" | Per-engine linear remap to 0–1 |
| OQ12 | **reversed default** | "YouTube if we can find enough" | Script sourcing priority is now YouTube-transcribed first, hand-written as fallback. Build a YouTube transcript ingestion path. |
| OQ13 | confirmed | "This is fine" | YouTube fair-use cleared for internal evaluation |

### New work items emerging from these answers (post-current-agents)

1. **Honda US brochure scraper** (OQ5) — automated downloader + extractor for the remaining 8 Honda models (and refresher for CR-V). Probably Python under `vehicle-feature-catalog/scrapers/honda_us/`. Needs to handle PDF brochure parsing (likely `pdfplumber` or similar). Open question: brochure URL discovery — Honda's site may need scraping their model page list first.
2. **Universal workflow cue inventory expansion** (OQ6) — expand to full NADA workflow coverage. Source: `demo/auditpro-rn-showcase/src/services/audio/audioScript.ts`, `dev/docs/ROAD_TO_SALE_AUDIO_TEST_MATRIX.md`, `demo/video-packet/VOICEOVER_SCRIPT.md`. Target ~25–35 cues.
3. **YouTube transcript ingestion** (OQ12 + OQ13) — utility to fetch transcripts from public Honda dealer walkaround videos and convert into the script YAML schema. Probably `voice-engine/lab/src/voice_lab/ingestion/youtube.py`. Each script must carry source URL + timestamp + attribution.
4. **Research-driven Apple + Argmax strategy implementations** (OQ1, OQ2) — replace stubs with real code once research reports are in.

## Implementation choices logged during build

(Will be appended as the build proceeds.)

### Catalog agent decisions (logged 2026-05-22)

- **DC1** — `ValidationResult` is a mutable Python `@dataclass` (not frozen) so `format_errors()` can be a method. Consumers treat fields as read-only. Reason: API doc shows `ValidationResult` having a method; frozen dataclasses with custom methods are awkward.
- **DC2** — `list_features_for_trim` raises `NotFoundError` on unknown `trim_id`; `list_trims_with_feature` raises on unknown `feature_id`. API doc was silent. Strict behavior prevents typo-driven silent empty results.
- **DC3** — `list_trims_with_feature` excludes cells with `availability: unavailable`. Semantically a trim where a feature is explicitly unavailable shouldn't appear in "trims that have this feature."
- **DC4** — Cross-entity ID collision raises both `DuplicateIdError` at load (fail-fast) and surfaces as a `duplicate_id` validation error via `validate()`. Same semantic, two surfaces.
- **DC5** — `pyproject.toml` does not register a console-script entry; `scripts/validate.py` runs via `python3 scripts/validate.py`. `pyproject.toml` sets `pythonpath = ["src/python"]` so pytest works without `pip install -e .`. Followed up by adding a `sys.path` bootstrap inside `scripts/validate.py` so it runs standalone without an active venv install.
- **DC6** — TS facade keeps Python-style snake_case method names (`get_make`, `list_features_for_trim`) to honor "field-identical, no drift." Departs from TS camelCase convention by design.
- **DC7** — TS `list_features` takes an options object `{ brand_scope?, category? }` since TS has no kwargs. Python keeps the spec'd keyword form. Same surface, language-idiomatic shape.
- **DC8** — `build.sh` uses `python -m pip install -e .`; on Homebrew-Python with PEP 668 this requires an active venv. Documented as a v1 limitation; defer venv-agnostic shell wrapper to a follow-up if needed.

### Voice-engine agent decisions (logged 2026-05-22)

- **DC9** — TS `MockTranscriptionStrategy` emits via `queueMicrotask` so subscribers can attach before events fire. Matches realistic live-session shape.
- **DC10** — `Orchestrator` is parameter-driven with a `LabScript` dataclass (`id`, `audio_path`, `expected_cues`, `negative_cues`) so it stays catalog-agnostic. The CLI is where catalog + atoms + audio compose.
- **DC11** — `voice-lab run` is plumbing-only in v1: confirms registry, touches non-mock strategies (surfaces OQ pointers), writes an empty scaffold report. Full pipeline gated on Apple/Argmax/synth real implementations.
- **DC12** — TS facade exposes a `registerStrategy()` instance method in addition to the module-level registry function so tests/consumers can inject strategies cleanly.
- **DC13** — `bootstrapDefaultStrategies()` is async + idempotent via dynamic `import()` to avoid a TS module-init cycle between facade and mock.
- **DC14** — CueMatcher emits one detection per atom per event with longest-match-first phrase selection (most specific phrase reported).
- **DC15** — TS `UnknownStrategyError` extends `Error` (typed class, strict-mode friendly).
- **DC16** — Cross-language drift test passes JSON via stdin to `node -e` (passing via argv broke because `node -e` rewrites argv).

### Build-time fixes
- **BF1** — `vehicle-feature-catalog/scripts/validate.py` lacked `sys.path` bootstrap; standalone runs failed with `ModuleNotFoundError`. Patched to insert `src/python/` into `sys.path` before import. Validator now runs against real seed data and reports valid.
- **BF2** — `voice-engine/lab/pyproject.toml` was missing `pythonpath = ["src"]` under `[tool.pytest.ini_options]`. Added; pytest now finds `voice_lab` module without `pip install -e .`. All 24 tests pass.

### Research findings (OQ1, OQ2)

**OQ1 — Apple SpeechTranscriber: ANSWERED.** Full brief in `dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_APPLE.md`.
- SpeechTranscriber is real, shipping in iOS 26 / macOS 26 (Tahoe), WWDC25 announce.
- Same API on iOS and macOS — no separate macOS variant needed.
- Volatile + final results both first-class via async sequences (satisfies binding rule).
- **CRITICAL GAP: no custom vocabulary on SpeechTranscriber.** Hybrid required: `SpeechTranscriber` for general + `SFSpeechRecognizer.contextualStrings` for vocab-sensitive. Two strategy registrations needed in the lab.
- Free, on-device, Apple Silicon hardware on macOS.
- Lab integration: Swift CLI emitting JSONL via subprocess — recommended.
- DC17 — based on this, the lab will register TWO Apple strategies: `apple_speech_transcriber` (no vocab) and `apple_sfspeechrecognizer_vocab` (with `contextualStrings` for dealership terms). Both surface partial + final.

**OQ2 — Argmax Pro SDK 2: ANSWERED.** Full brief in `dev/docs/ROAD_TO_SALE_AUDIO_RESEARCH_ARGMAX.md`.
- Pro SDK 2 GA April 7, 2026. iOS / iPadOS / macOS Apple Silicon / Android only. **Linux/Windows NOT supported** (limits CI options).
- Confirmed + Hypothesis dual-stream confirmed (satisfies binding rule).
- 3,000 custom-vocab keywords supported — well above what dealership terms need.
- **No Python bindings yet** — Python Local Server client "coming soon."
- Lab integration: $14 self-serve trial (30 device licenses) + email `customer@argmaxinc.com` for macOS Local Server binary; then Python via Deepgram-compatible WebSocket on localhost. Fallback: Swift CLI from `argmax-sdk-swift-playground`.
- DC18 — Argmax lab integration goes through Local Server, not direct Swift CLI, to minimize Swift glue (Python uses Deepgram SDK which is mature).
- DC19 — Argmax trial purchase + Local Server binary request goes to user (requires payment + email).

### OQ6 — universal workflow cue inventory expansion (delivered)
- **DC20** — Expanded `voice-engine/lab/cue-packs/universal_workflow_cues.yaml` from 12 starter cues to **33 cues across all 13 NADA Road-to-Sale stages**. Per-stage distribution: greet (4), discovery (8), vehicle_match (1), walkaround (3), test_drive (2), trial_close (1), trade (4), pencil (3), manager_to (2), buyers_order (1), finance (2), delivery (1), follow_up (1).
- **DC21** — Kept a single file rather than splitting per category. Cues are clearly section-headered for review. Splitting per file can come if the inventory grows past ~60 cues.
- **DC22** — Phrasing drawn from `demo/auditpro-rn-showcase/src/services/audio/audioScript.ts`, `dev/docs/ROAD_TO_SALE_AUDIO_TEST_MATRIX.md`, `demo/video-packet/VOICEOVER_SCRIPT.md`, and NADA common dealer language. Same v1-seed verification header.
- **DC23** — New cues introduced beyond starter 12: customer_name_use, test_drive_offer, discovery_weekend_use, discovery_work_use, discovery_towing_cargo, discovery_budget_signal, walkaround_opening, exterior_focus, interior_focus, test_drive_opening, trial_close, trade_in_mileage, trade_in_condition_overall, pencil_numbers_intro, monthly_payment_mention, down_payment_mention, manager_voice_change, buyers_order_confirmation, three_way_intro, delivery_walkthrough, follow_up_commitment.
