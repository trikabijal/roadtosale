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

### Apple Swift CLI + Python wrappers (delivered)
- **DC24** — Used Apple's `SpeechAnalyzer(inputAudioFile:...)` convenience init in the Swift CLI rather than a manual `AVAudioFile → AnalyzerInput` pump. Apple handles streaming, format conversion, and finishing. Cleaner code, avoids `AVAudioConverter` Sendable warnings.
- **DC25** — `confidence: null` for SpeechTranscriber events (per-token attribute scale undocumented; honest about it); populated for SFSpeechRecognizer events (`SFTranscriptionSegment.confidence` is documented as 0–1 Float, averaged across non-zero segments on finals).
- **DC26** — Two Python wrappers (`apple_speech_transcriber.py`, `apple_sfspeechrecognizer.py`) share a single `_run_apple_stt` subprocess driver. Each class just builds different argv.
- **DC27** — Registry registers both Apple strategies as defaults on all hosts. Construction is side-effect free; failure surfaces at `transcribe()` time with embedded fix-it hint.
- **DC28** — Authorization gating in Swift, not Python. CLI exits 10–13 if Speech Recognition not granted. First-time setup: run from Terminal, approve in System Settings > Privacy & Security > Speech Recognition.
- **DC29** — `--partials false` still emits partials internally but filters them on the way out (safer than mid-stream `reportingOptions` changes).
- **DC30** — Vocab file format permits blank lines and `#` comments. Sourced from catalog feature display names + dealership phrases.
- **Manual verification still required (after end-of-day landing):** first-run Speech Recognition authorization grant; `supportsOnDeviceRecognition` runtime check for SFSpeechRecognizer; latency calibration against a real-time mic path (future work).
- **Build verified:** `swift build -c release` clean on macOS 26.3.1 / Swift 6.3.2 / SDK MacOSX26.5; binary 171 KB.

### Argmax wrapper (delivered, blocked on user)
- **DC31** — Used the `websockets` Python package (asyncio) against the Argmax Local Server's Deepgram-compatible WebSocket on `ws://localhost:50060/v1/listen`. Alternative would have been the Deepgram Python SDK; chose `websockets` for transparency and fewer transitive deps.
- **DC32** — `websockets` is an optional dependency under `[argmax]` extras (`pip install -e '.[argmax]'`). Lab works without it; only fails at transcribe-time with a clear hint when the package is missing.
- **DC33** — Audio format requirement: 16 kHz mono PCM-16 WAV. Other formats raise `AudioFileError` with a literal `ffmpeg` command for conversion.
- **DC34** — `is_final=true` → `stability='final'` (Argmax 'Confirmed'); `is_final=false` → `stability='partial'` (Argmax 'Hypothesis').
- **User actions still required:** $14 self-serve trial at app.argmaxinc.com (30 device licenses); email `customer@argmaxinc.com` for macOS Local Server binary; `export ARGMAX_API_KEY=ax_...`.

### Honda US brochure scraper (delivered, with finding)
- **DC35** — Real-world finding: `automobiles.honda.com` is fronted by Akamai Bot Manager. Every server-side scrape attempt 403s regardless of UA / headers / rate. Discoverer surfaces this with diagnostic messages naming the URL it tried.
- **DC36** — Workaround documented in `vehicle-feature-catalog/scrapers/honda_us/README.md`: operator downloads brochures in a real browser (which Akamai allows), drops PDFs in `data-cache/brochures/honda/2026/<slug>.pdf`, runs `python -m scrapers.honda_us.cli --skip-discover`. Pipeline tested end-to-end with a synthetic fixture PDF — 3 trims + 21 matrix cells emitted, catalog validates clean.
- **DC37** — Feature mapping uses a curated alias table in `emit.py` (`LABEL_TO_DISPLAY_NAME`) + fuzzy substring fallback. Unmatched labels become Honda-scoped features (never universal — conservative).
- **DC38** — Matrix append is merge-by-trim-id with strongest-availability-wins dedup; existing CR-V Hybrid AWD entries preserved verbatim.
- **DC39** — Catalog `pyproject.toml` `pythonpath = ["src/python", "."]` allows tests to import the `scrapers` package without pip-installing the catalog.

### YouTube transcript ingestion (delivered, with finding)
- **DC40** — YouTube anti-bot rate limit hits after ~10 sequential fetches per IP; sticky block for hours-to-days. Documented; proxy rotation flagged as the production path. Library docs (youtube-transcript-api) recommend Webshare or similar.
- **DC41** — Monotonic step-boundary detection: as segments are scanned in order, when a cue matches whose category is a "later" NADA step than the current step, the script transitions to that step. Prevents step regressions.
- **DC42** — Cue index built from `universal_workflow_cues.yaml` + all feature YAMLs in the catalog. 327 entries (33 workflow cues × ~6 phrases + 17 feature cues × ~5 phrases × synonyms).
- **DC43** — Manifest `sources.yaml` carries 10 verified video IDs. 5 non-CR-V entries (Civic, Pilot, Accord) flagged with `PLACEHOLDER TRIM` notes since the catalog only has CR-V trims today; ingestion still works (workflow cues are brand/trim-agnostic; feature-cue overlap will be undercounted for non-CR-V until those trims land).
- **DC44** — One demonstration script committed (`youtube_pxUbp7YxBtc.yaml`). Future runs of `voice-lab ingest-youtube --manifest ... --force` overwrite it with the real transcript once IP block clears.

### Test count snapshot (end of day)
- vehicle-feature-catalog: 41 Python tests + 27 TS tests = 68
- voice-engine/lab: 69 Python tests
- voice-engine/ (TS library): 15 tests
- **Total: 152 tests, all green**

### Branches and commits as of end-of-day
- `chore/repo-split-demo-product` — repo split + PRD + module docs (2 commits)
- `feat/voice-engine-catalog-v1` — implementation work:
  - feat: vehicle-feature-catalog
  - feat: voice-engine (Py lab + TS lib)
  - docs: Apple + Argmax research
  - feat: expand workflow cues to full NADA
  - feat: real Apple + Argmax STT strategies
  - feat: Honda US brochure scraper
  - feat: YouTube transcript ingestion
  - docs: this decisions-log entry

---

## Voice lab run results + architecture decisions (2026-05-24)

### DC45 — WhisperKit SNR +5 dB FNR corrected: 20.0% → 8.2%

The 20.0% figure reported in run-20260524-0829 was an artifact of 3 corrupted JSONL cache files. Each corrupted file had a truncated JSON line (~508–538 chars); `json.JSONDecodeError` caused all expected cues for those files to classify as false negatives. After deleting the three files and re-running STT, the true number is **8.2%** — near parity with Apple's 8.6% at the same noise level.

**Consequence:** The "WhisperKit recovers dramatically with DNS64 denoiser" finding from the earlier session was also an artifact of the corrupted baseline. With the correct baseline (8.2%), DNS64 at SNR +5 dB actually worsens WhisperKit (8.2% → 9.1%) and Apple (8.6% → 12.3%). DNS64 consistently hurts or is neutral at all noise levels.

### DC46 — DNS64 neural denoiser removed from the lab entirely

DNS64 (Facebook Research batch denoiser) was being used to generate `_nr.wav` files: applying the denoiser to the full WAV file offline, saving a new file, then feeding that file to STT.

**Why this is wrong:**
1. iPhone noise suppression (`AUVoiceProcessingIO`) is a hardware Audio Unit that runs at the kernel audio session layer — it operates on 16–20 ms frames before any PCM data reaches user space. It cannot be replicated by batch-processing a WAV file on macOS.
2. DNS64 runs a different algorithm (neural, offline, whole-file) at different granularity. The results it produces don't represent what the iPhone hardware does.
3. DNS64 is CPU-intensive batch processing. Running it in the fast lane of a production app would drain battery.
4. DC46 makes the lab's production target explicit: **iPhone only**. `AUVoiceProcessingIO` is always active on iPhone regardless of app configuration — it is the production noise suppressor. Zero battery cost (dedicated DSP in the SoC).

**What was deleted:**
- `voice_lab/synthesis/denoise.py` — DNS64 wrapper module
- `voice-lab denoise` CLI subcommand (removed from `cli.py`)
- `[project.optional-dependencies] denoise` in `pyproject.toml`
- 48 `_nr.wav` audio files (~1 GB)
- 96 `_nr.jsonl` transcript cache files

**What was preserved:** The `_nr` naming slot in `_collect_audio_variants()` is kept as a general-purpose processed-audio slot. It will be used for iPhone-mic-recorded fixtures (see DC49).

### DC47 — Lab FNR numbers are a conservative lower bound for iPhone production

The lab tests raw audio — white Gaussian noise is added at fixed SNR and fed directly to the STT strategy CLIs. No software noise suppression is applied.

On iPhone in production, `AUVoiceProcessingIO` is always active before any PCM frame reaches user space. The STT engine never sees raw noisy frames. The iPhone hardware noise processor will reduce the effective noise level to the STT engine compared to the lab's raw signal.

**Therefore:** Lab FNR numbers (e.g., Apple 8.6% at SNR +5 dB showroom noise) are a conservative lower bound. Production FNR at showroom noise will be lower by some amount measurable only on a real device.

**How to measure the gap:** Record the lab's noisy WAV fixtures through an actual iPhone microphone (with `AVAudioSession` configured as the production app configures it), capture those recordings, and add them as lab fixtures named with the `_nr` suffix. The reporting layer picks them up automatically.

### DC48 — Apple SpeechTranscriber is the recommended iOS production strategy

Canonical final run: `run-20260524-1142` (2 strategies × 64 fixtures × 4 noise levels, no denoised rows).

| Strategy | FNR clean | FNR +15 dB | FNR +5 dB (prod) | FNR 0 dB | TTFC P50 | TTFC P95 |
|---|---|---|---|---|---|---|
| `apple_speech_transcriber` | 4.5% | 4.5% | **8.6%** | 18.6% | 168 ms | 330 ms |
| `whisperkit` | 1.4% | 2.7% | **8.2%** | 18.6% | 338 ms | 610 ms |

At the production operating point (SNR +5 dB showroom floor), FNR is near-parity (8.6% vs 8.2%). Apple SpeechTranscriber wins on TTFC: P95 330 ms vs 610 ms — a 280 ms advantage that compounds across each workflow step. Apple is also on-device with no external dependency, no license cost, and is the native iOS 26 API.

**Decision:** Apple SpeechTranscriber is the recommended primary iOS strategy. WhisperKit remains registered as the fallback for iOS 17–25 where SpeechTranscriber is unavailable.

### DC49 — Semantic lift corrected (run-20260524-1051)

Across 64 fixtures × 2 strategies with `--semantic`:
- Apple SpeechTranscriber: **+193 semantic-only detections (14% of all detections)**
- WhisperKit: **+291 semantic-only detections (21% of all detections)**

WhisperKit benefits more from semantic matching because it paraphrases more frequently (sliding-window attention produces natural-language output; exact phrase matching misses more). With semantic matching enabled, the FNR gap between Apple and WhisperKit narrows in clean conditions.

### DC50 — TTFC metric is file-mode wall-clock time (relative ordering transfers to production)

`latency_ms_from_audio_start` is set by the Swift EventEmitter as `Date().timeIntervalSince(audioStart) * 1000` where `audioStart` is a `Date()` taken when the CLI starts processing the file.

In file mode (RTF ≈ 0.004), the CLI processes audio ~250× faster than real-time. TTFC in file mode includes engine startup overhead and buffer fills — it is NOT the same number as production TTFC (which is bounded by when the dealer says the first keyword, approximately 3–10 s into a conversation).

**What does transfer:** the relative ordering (Apple < WhisperKit) and the qualitative magnitude (Apple is ~2× faster to first cue in file mode). In production, both values will be dominated by acoustic latency, but Apple's architecture advantage (Neural Engine warm, no model load) is real and preserved.

### DC51 — iPhone device test approach for AUVoiceProcessingIO measurement

The only way to measure what `AUVoiceProcessingIO` does to cue detection is a physical device test. The procedure:

1. Play the lab's noisy WAV fixtures through a calibrated speaker in a quiet room.
2. Record through an iPhone with `AVAudioSession` configured exactly as the production app configures it (`.record` category, `.defaultToSpeaker` option off, no mixing).
3. Have the production Swift strategy code emit `TranscriptEvent` JSONL to a file.
4. AirDrop / export the JSONL files to the lab machine.
5. Drop them into `voice-engine/lab/data/transcripts/apple_speech_transcriber/{audio_id}_nr.jsonl`.
6. Run `voice-lab run --strategies apple_speech_transcriber --semantic` — all cache hits, cue matching only.
7. The `noise_comparison.md` report will show the `_nr` column alongside the raw noisy column, giving the exact hardware noise suppression delta per noise level per cue.

Infrastructure in the lab is ready. Physical test is a future task (requires an iPhone running iOS 26 and access to a calibrated speaker).

### Branches and commits as of 2026-05-24 end-of-session

- `feat/voice-engine-catalog-v1` — continued from 2026-05-22:
  - feat(catalog): Honda US 2026 full lineup (hondanews press releases)
  - feat(catalog): hondanews HTML extractor as alternative source
  - feat: WhisperKit STT strategy — open-source Argmax path, free for macOS lab
  - feat: ElevenLabs TTS synthesis + yt-dlp YouTube audio ingestion
  - feat(catalog): full Honda US 2026 lineup extracted from hondanews press releases
  - feat: wire lab CLI to orchestrator + ship first WhisperKit comparison report
  - refactor(lab): remove DNS64 denoiser — iPhone uses AUVoiceProcessingIO (hardware)
  - feat(lab): canonical final run-20260524-1142 — corrected FNR numbers, no denoised rows
  - docs(lab): 5-stage pipeline, iPhone-only architecture, corrected lab numbers across all four docs
  - chore: scripts/check_imports.py boundary guard + root README.md + decisions-log update (this entry)
  - feat(sherpa-onnx): Silero VAD sentence-level segmentation + run-20260524-1342 results

---

### DC52 — Silero VAD is required for semantic lift in sherpa-onnx

**Problem:** In batch mode (fixed 30-second chunks), `OfflineRecognizer` emits 75–130 word events. bge-small-en-v1.5 is a sentence encoder optimised for 8–30 word inputs. Cosine similarity between a 100-word paragraph and a 2-word cue phrase falls to ~0.35–0.45, below the 0.65 threshold. Result: semantic matching is effectively disabled for large chunks.

**Fix:** Silero VAD segments audio at natural speech pauses (sentence level). Each VAD segment is 2–8 seconds long, semantically coherent, and comparable to Apple/WhisperKit finals. Run: `run-20260524-1342`.

**Results vs batch (run-20260524-1221):**

| Metric | Batch | VAD | Δ |
|---|---|---|---|
| FNR clean | 2.7% | 2.3% | -0.4pp ✅ |
| FNR SNR+5 (showroom) | 13.2% | 14.1% | +0.9pp |
| FNR SNR+0 (extreme) | 30.5% | 43.2% | +12.7pp ⚠️ |
| Semantic lift | +128 (17%) | +167 (23%) | +39 ✅ |
| TTFC P50 | 1242ms | 534ms | -708ms ✅ |
| TTFC P95 | 1408ms | 1612ms | +204ms |
| Partials/Finals per file | 24 | 91 | sentence-level |
| FPR | 0.0% | 0.0% | — |

**SNR0 regression (+12.7pp) explained:** At 0 dB SNR, speech and noise have equal energy. Silero VAD probability stays below the 0.5 threshold for noise-contaminated speech frames → some utterances suppressed entirely. Batch mode is immune (unconditional 30-second chunking). In production (Android), audio goes through Android voice effects (hardware noise suppression) before reaching Silero VAD, making raw SNR0 a pessimistic floor.

**Decision:** VAD mode is the production simulation default. Batch remains available via `--mode batch` for debugging transcript coverage. The SNR0 gap motivates iPhone device testing (DC51 procedure) to measure what hardware noise suppression does before VAD.

### DC53 — sherpa-onnx Kotlin JNI field names must exactly match native dylib

When vendoring sherpa-onnx Kotlin API classes (Vad.kt, OfflineRecognizer.kt, etc.), all data class field names and types must exactly match what the native `libsherpa-onnx-jni.dylib` was compiled against. The native code uses `GetFieldID` by name — a missing field returns null and `GetObjectClass(null)` causes a SIGSEGV.

**Specific fix (2026-05-24):** `VadModelConfig` in our initial Vad.kt was missing `tenVadModelConfig: TenVadModelConfig`. The native `GetVadModelConfig` function accesses both `sileroVadModelConfig` and `tenVadModelConfig`. Missing field → SIGSEGV at `jni_GetObjectClass+0xcc` inside `sherpa_onnx::GetVadModelConfig`.

**Rule:** Always fetch the canonical `Vad.kt` from the exact sherpa-onnx release tag matching the downloaded native jars before vendoring. Diff against the existing vendored file before rebuilding.

---

## Road to Sale App decisions (PRD 0002)

### DC54 — NADA checklist sourced from SmartComply Audit Template at runtime

**Context:** The NADA 10-step workflow is not hardcoded in the Road to Sale app. It is defined as a SmartComply Audit Template (checksheet), and a customer session is a SmartComply inspection instance — an instance of running through that template.

**Decision:** The app fetches the audit template from the SmartComply API at runtime (session start), not at build time. The template is cached locally in SQLite (keyed by template ID + ETag) and served from cache on subsequent session starts. Background revalidation keeps the cache current. If no cache exists and the API is unreachable, the app blocks session start with an error — the template is required to render the checklist.

**Rationale:** Runtime fetch allows the SmartComply team to update the template (add/rename steps, adjust required cues) without an app release. Caching ensures it works on the showroom floor with spotty connectivity.

**Revisit when:** SmartComply team confirms a template versioning strategy (ETag, version field, etc.) — cache invalidation key must be pinned to that.

---

### DC55 — Template ID serves as audit type; no SmartComply schema change required

**Context:** SmartComply does not have an `audit_type` field. Road to Sale sessions need to be distinguishable from other audit types (facility inspections, F&I audits, etc.) in the SmartComply BI dashboard.

**Decision:** The audit template ID (e.g., `road-to-sale-v1`) is the audit type. Any SmartComply inspection created from the `road-to-sale-v1` template is, by definition, a Road to Sale session. No new `audit_type` field is required from the SmartComply team in v1. Filtering and reporting in the BI dashboard filters by template ID.

**Rationale:** Zero SmartComply schema changes needed for v1. Template ID is already unique and queryable. If SmartComply later adds a proper `audit_type` field, migration is straightforward — just populate it with the template ID value.

**Revisit when:** SmartComply adds a first-class audit type concept, or a third audit type is introduced that creates ambiguity with template ID alone.

---

### DC56 — Road to Sale extended data behind `RtsDataProvider` interface; concrete backend TBD

**Context:** SmartComply's inspection schema covers checklist items and evidence items, but does not natively carry Road to Sale-specific fields: voice cue source, transcript snippet, cue confidence score, trade-in photo binaries. These must live somewhere.

**Decision:** All access to this extended data goes through an `RtsDataProvider` interface defined in `road-to-sale-app/src/api/rts-data-provider.ts`. The interface is defined now (v1 contract). The concrete implementation (SmartComply evidence blob fields vs a separate Road to Sale backend service) is decided once the SmartComply team answers OQ9 (what their schema can hold).

**Rationale:** Avoids coupling implementation to a storage decision that's blocked on a third-party API conversation. The interface is swappable without touching any other app code.

**RESOLVED by DC58.** Road to Sale owns a SmartComply instance and extends the schema directly. `RtsDataProvider` abstraction is not needed — the owned instance is the backend.

---

### DC57 — Audit template questions drive the checklist UI; cue-to-question binding lives in Road to Sale cue pack

**Context:** The SmartComply Audit Template carries formal audit questions (e.g., "Did the salesperson greet the customer within 30 seconds of arrival?"). The Road to Sale app must: (a) display these questions to the rep, and (b) auto-tick them when the voice engine detects relevant speech. This requires knowing which cue atoms answer which template question.

**Decision (display):** Template question text is displayed as-is in the Road to Sale checklist UI. Road to Sale does not maintain its own label copy — the template is the source of truth. For multi-cue questions (e.g., "Was a detailed customer requirement taken?"), the checklist item expands to show a rich sub-panel listing each cue that was detected and its transcript snippet.

**Decision (binding):** Each cue atom in the Road to Sale cue pack YAML carries a `template_question_id` field (the SmartComply template question ID it answers). The `ChecklistEngine` reads the fetched template (question IDs + text), loads the cue pack (cue atoms + their `template_question_id`), and builds a live map from question → cue atoms at session start. This binding is a Road to Sale concern — neither SmartComply's template schema nor the voice engine core carries it.

**Rationale:** Keeps SmartComply schema clean (no voice-engine coupling). Keeps voice engine core generic (cues are reusable across any product, not locked to one template). Road to Sale's cue pack YAML is the single join point between the two systems.

**Consequence:** A new `road-to-sale-v1` cue pack YAML must be authored that (a) references all relevant cue atoms from existing packs, and (b) annotates each with `template_question_id`. This is a task in the Road to Sale task list.

**Revisit when:** SmartComply Audit Template API is available and OQ1 is answered — actual question IDs will replace placeholder strings in the cue pack YAML.

---

### DC58 — Road to Sale self-hosts its own SmartComply instance; schema extensions owned by Road to Sale team

**Context:** SmartComply's schema and API are owned by the SmartComply team. Road to Sale-specific fields (cue events, transcript snippets, trade-in photos, cue confidence) would require third-party approval before being added — blocking v1 implementation.

**Decision:** Road to Sale imports the full SmartComply schema, API, and deployable into this monorepo as a `smartcomply/` top-level deployable. Road to Sale operates its own database instance. Schema extensions required for Road to Sale (cue-specific fields, etc.) are applied directly to this owned instance by the Road to Sale team. Once stable, extensions are proposed back to the SmartComply team as upstream contributions — but shipping v1 does not depend on upstream acceptance.

**Rationale:** Eliminates third-party API dependency for v1. Road to Sale team controls the full data model. Schema evolution is fast — propose back to SmartComply once the design is proven.

**Consequences:**
- `smartcomply/` is a new top-level deployable in this repo (imported source + schema + build script).
- The mobile app calls the self-hosted SmartComply instance, not a third-party service.
- `RtsDataProvider` abstraction (DC56) is superseded — direct SmartComply schema access replaces the interface.
- A new parent task (SmartComply instance setup + Road to Sale schema extensions) is added to the task list before the SmartComply write tasks.

**Revisit when:** SmartComply team absorbs the Road to Sale extensions upstream → evaluate switching to the shared instance and deprecating the owned one.

---

### DC59 — SmartComply GitLab repo is an empty shell; Road to Sale builds the server from scratch

**Context:** `https://gitlab.tiez.net/Tiez/smartcomply` contains only the GitLab default README template — no source code, schema, or API exists yet.

**Decision (autonomous):** Road to Sale builds the SmartComply backend from scratch as a Node.js / Express API server with a SQLite database (`smartcomply/` deployable). The server implements the API contract defined in `road-to-sale-app/docs/smartcomply-contract.md` and includes Road to Sale schema extensions from day one. When the SmartComply team starts building their canonical version, Road to Sale's server becomes the reference implementation and the codebase can be pushed to the GitLab repo as the starting point.

**Stack chosen (autonomous):** Node.js + Express + better-sqlite3 + TypeScript. Rationale: consistent with the rest of the monorepo (TypeScript everywhere), SQLite is zero-infra for local dev and pilot, Express is the lowest-friction HTTP layer.

**Revisit when:** SmartComply team builds their own canonical server. At that point evaluate: adopt their implementation, push ours to them, or keep separate instances in sync via schema migrations.

---

### DC60 — Expo bare workflow from day one; prebuild run during scaffold

**Context:** The voice native module (Swift on iOS, Kotlin on Android) requires access to the raw `ios/` and `android/` native project directories, which only exist in the Expo bare workflow.

**Decision (autonomous):** Create the app using `create-expo-app` (managed), then immediately run `expo prebuild` to generate native project directories before any other work. All subsequent tooling targets the bare workflow. Managed Expo updates (OTA) are not used — releases go through standard App Store / Play Store flows.

**Revisit when:** Expo Modules API (the new native module approach without full bare workflow) matures enough to support always-on audio + foreground services without ejecting.

---

### DC61 — SmartComply domain mapping for Road to Sale

**Decision (autonomous):** Road to Sale rides the existing SmartComply domain model directly:

| Road to Sale concept | SmartComply entity |
|---|---|
| NADA audit template | `checksheets` (checksheet with `kind='ROAD_TO_SALE'` tag) |
| NADA step | `chks_headers` row |
| Checklist question / cue group | `chks_questions` row (type `SUBJECTIVE_CONDITION`) |
| Session (one rep + one customer) | `inspections` row (`kind='AUDIT'`) |
| Cue detection event / answer | `user_checksheet_answers` row + V1.34 extension fields |
| Rep's appointment list | `GET /api/audit/myAssignments` |

The mobile app uses `deviceType: "APP"` on login. The `auditAssignmentId` (= inspection id) is the single session identifier passed on every write.

**Walk-in sessions:** A standing "Road to Sale – Walk-In" audit campaign is seeded in the Road to Sale tenant. The app calls `POST /api/audit/addAuditAssignments` to create an ad-hoc inspection under this campaign whenever a rep starts a walk-in. The location defaults to the rep's home store.

**Revisit when:** SmartComply adds a first-class `session` concept, or the walk-in campaign approach causes BI reporting issues.

---

### DC62 — Road to Sale schema extensions go in Flyway migration V1.34

**Decision (autonomous):** Road to Sale-specific fields are added to the SmartComply schema via a new Flyway migration `V1.34__road_to_sale_extension.sql` in the copied `smartcomply/` source. Fields added to `user_checksheet_answers`:
- `rts_cue_id` VARCHAR(255) — voice engine cue atom ID
- `rts_cue_source` VARCHAR(50) — 'feature' or 'workflow'
- `rts_transcript_snippet` TEXT — short transcript excerpt (≤ 200 chars)
- `rts_cue_confidence` NUMERIC(4,3) — confidence 0.000–1.000
- `rts_voice_auto_completed` BOOLEAN DEFAULT FALSE — true = voice detection, false = manual override

New table `rts_trade_photos` for trade-in photo storage (session link + shot slot + S3 key).

All new columns are nullable and prefixed `rts_` to make them easy to identify as Road to Sale extensions when proposing upstream.

**Revisit when:** SmartComply team reviews the proposal and either absorbs the fields or requests different naming.

---

### DC63 — SmartComply copied into monorepo as `smartcomply/` deployable; development branch

**Decision (autonomous):** SmartComply source (development branch, commit at import time) is copied into `smartcomply/` at the monorepo root. The `.git` directory is excluded — `smartcomply/` becomes part of the roadtosale git history, not a submodule. Road to Sale's V1.34 migration is added inside `smartcomply/src/main/resources/db/migration/` and the Road to Sale tenant seed goes in `smartcomply/src/main/resources/db/tenants/honda/`. Local dev runs via `./mvnw spring-boot:run -Dspring-boot.run.profiles=local,tenant-data` with `TENANT_ID=honda`.

**Revisit when:** SmartComply team starts actively developing the development branch. At that point, evaluate switching back to a git submodule or subtree so upstream changes can be pulled cleanly.

---

### DC64 — SessionSetupScreen: Year comes from Model.year, not Trim.year

**Decision (autonomous):** The spec says "derive years from trims for that model." In the actual `bundle.json` catalog schema, `Trim` has no `year` field — the year lives on `Model`. The picker therefore collects unique years from `catalogLoader.list_models(makeId)`, sorts them descending, and filters models by the chosen year before showing the model chips. Selecting a year also re-validates the already-selected model: if the model's year no longer matches, the model selection is cleared. This is consistent with the intent of the spec and handles multi-year catalogs (e.g., 2025 CPO + 2026 new) correctly once they are added.

**Revisit when:** The catalog schema is extended to add `year` to `Trim` (e.g., for trim-year-specific packaging). If that happens, the year picker should switch to reading `Trim.year` and the model filter step collapses back to a simple make→model→trim cascade.

---

### DC65 — SessionSetupScreen: Walk-in defaults and picker ordering

**Decision (autonomous):**

- `DEFAULT_CHECKSHEET_ID = 2001` — the Honda RTS NADA checksheet seeded in the dev SmartComply instance (matches the seed data spec).
- `WALKIN_ASSIGNMENT_ID = 1` — the seeded bootstrap inspection id for the walk-in campaign (matches the seed data spec).
- Picker order is **Make → Year → Model → Trim**, not Make → Model → Year → Trim. Showing Year before Model matches the real-world sales rep workflow (rep knows the model year the customer wants before drilling into trims). Year chips are derived from all models for the selected make (see DC64). The model list is filtered to only models with the selected year before being shown.
- `MAX_HIGHLIGHT_FEATURES = 6` — shows top 6 standard-availability features (spec says "up to 6"). Features come from `catalogLoader.list_features_for_trim(trimId, ['standard'])`.
- Feature pills are purely informational (no tap handler). Pill background is `colors.accent` with white text, border-radius 12, padding 4 8.
- The footer button is disabled (grayed out) if firstName is blank OR no trim is selected OR loading is in progress. No separate validation error messages are shown — the disabled state is self-explanatory in a showroom context.

**Revisit when:** The catalog adds optional/package features worth surfacing; at that point the highlight panel can be extended to separate standard vs. optional features.

---

### DC64 — iOS VoiceModule requires bridging header + Expo config plugin for Xcode wiring

**Decision (autonomous):** `RtsVoiceModule.swift` inherits from `RCTEventEmitter` (React Native ObjC class). Swift cannot access this type without an Objective-C bridging header. Created `ios/VoiceModule/RtsVoiceModule-Bridging-Header.h` (imports `<React/RCTBridgeModule.h>` and `<React/RCTEventEmitter.h>`). Also created `plugins/withVoiceModule.ts` (Expo config plugin) that:
- Adds VoiceModule Swift/ObjC/header files to the Xcode app target after `expo prebuild`
- Sets the Swift Compiler Objective-C Bridging Header build setting
- Adds `sherpa-onnx-android` AAR dependency to `android/app/build.gradle`
- Registers `RtsVoicePackage` in `MainApplication.kt`
Wired via `"plugins": ["./plugins/withVoiceModule"]` in `app.json`.

**Revisit when:** `expo prebuild` is run — verify the plugin correctly wires the files into the Xcode target. SourceKit errors in the Swift file will resolve once the Xcode project context exists.

---

### DC65 — SessionEngine singleton + overrideQuestion delegation

**Decision (autonomous):** `SessionEngine` did not have a singleton export, so screens could not share state. Created `src/session/sessionEngineSingleton.ts` following the same lazy-singleton pattern as `clientSingleton.ts` and `repositorySingleton.ts`. Also added `overrideQuestion(sessionId, questionId, note?)` to `SessionEngine` that delegates to the per-session `ChecklistEngine` instance. This method was missing from the original ISessionEngine interface but required by the ActiveSessionScreen. The interface will be updated once the method is tested in Phase 4.

**Revisit when:** E2E test plan is defined — may want to add `overrideQuestion` to `ISessionEngine` formally at that time.

---

## ActiveSessionScreen decisions (2026-05-25)

### DC66 — v1 transcript→CueDetection bridge uses generic cue_id "workflow.transcript"

**Decision (autonomous):** The voice engine emits raw `TranscriptEvent` objects. Converting them to `CueDetection` requires knowledge of the cue pack phrase patterns. A dedicated `CueDetectionEngine` is a future task. For v1, all final transcripts are forwarded to `engine.processCueDetection()` with `cue_id: "workflow.transcript"`. This cue is present in the `road-to-sale-v1` cue pack but is not bound to any `templateQuestionId`, so it is a no-op for checklist state advancement. The pipeline (voice → transcript → detection → engine) is proven end-to-end; phrase matching is the next layer.

**Rationale:** Avoids implementing regex/embedding matching in the screen layer, which would duplicate logic that belongs in a CueDetectionEngine. The screen stays thin.

**Revisit when:** CueDetectionEngine is implemented. The bridge function `transcriptToCueDetection()` in `ActiveSessionScreen.tsx` is the only place to update — swap the static cue_id for the engine's output.

---

### DC67 — StepState.header.orderNo used for step numbering, not a non-existent `.sequence` field

**Decision (autonomous):** The task brief referred to `StepState.header.sequence` as the step order number. The actual `ChecksheetHeaderDTO` type (in `src/api/types.ts`) uses `orderNo: number` — no `sequence` field exists. The screen uses `step.header.orderNo` throughout. The step display label reads `"{orderNo}. {name}"` which matches the NADA convention.

**Revisit when:** API types are updated. If `sequence` or a different ordering field is added, update the display and sort logic accordingly.

---

### DC68 — FlatList used for step list; ListHeaderComponent carries feature panel + progress row

**Decision (autonomous):** The step list uses `FlatList` with `ListHeaderComponent` for the Feature Coverage Panel and progress summary row. This avoids nested `ScrollView` (which causes the standard RN layout warning and can break scroll on Android). The footer and mic indicator are positioned absolutely outside the list, with `paddingBottom` on the list's `contentContainerStyle` to reserve space.

**Revisit when:** Step count grows large enough to need recycling (10 NADA steps is well within FlatList defaults — no immediate concern).

---

### DC69 — Mic pulse animation stopped via ref to avoid memory leaks

**Decision (autonomous):** The `Animated.loop` for the mic pulse is stored in a `useRef` so the cleanup function can call `.stop()` on it explicitly. Without this, returning from a state where `micState === 'listening'` to any other state would leave the animation running after `pulseAnim.setValue(1)` — causing the next pulse to fight with the reset. The ref ensures exactly one animation loop is active at a time and is fully stopped before the next one starts.

**Revisit when:** React Native's `Animated` API changes (unlikely in RN 0.85 timeframe).

---

## TradeInScreen + HistoryScreen decisions (2026-05-25)

### DC70 — TradeIn display slots mapped to API TradePhotoSlot values

**Decision (autonomous):** The spec defines 7 display slots (`front`, `rear`, `driver_side`, `passenger_side`, `engine`, `odometer`, `vin`) but `TradePhotoSlot` in `api/types.ts` defines a different 7 values (`front_left`, `front_right`, `rear_left`, `rear_right`, `interior`, `odometer`, `vin`). Rather than changing the API type (which would cascade to `SmartComplyClient.ts` and `ISmartComplyClient.ts`), the screen carries a `PhotoSlot` interface that adds `apiSlot: TradePhotoSlot` to each display slot. Mapping chosen by semantic proximity: `front → front_left`, `rear → rear_left`, `driver_side → front_right`, `passenger_side → rear_right`, `engine → interior`. `odometer` and `vin` map 1:1.

**Rationale:** Keeps `api/types.ts` stable; the mapping is a UI concern. When the API adds an `engine` slot, update the constant table in `TradeInScreen.tsx` only.

**Revisit when:** The SmartComply API adds an `engine` slot, or when the API and display slots are reconciled in a schema review.

---

### DC71 — TradeIn condition note stored in component state only (v1)

**Decision (autonomous):** `SessionEngine.getSession()` returns the in-memory session but `SessionEngine` has no `updateTradeIn()` or `setConditionNote()` method. Persisting the typed note to SQLite would require wiring through `ISessionRepository.updateSession()` with a `tradeIn.typedNote` field — a non-trivial addition. For v1, the note lives in component state for the lifetime of the screen. The `handleDone` function logs a comment marking the exact extension point: when `SessionEngine` gains an `updateTradeIn` method, call it before `navigation.goBack()`.

**Revisit when:** `SessionEngine.updateTradeIn()` is implemented (task 9 / persistence layer work).

---

### DC72 — HistoryScreen completion chip uses TOTAL_QUESTIONS = 16 constant

**Decision (autonomous):** The NADA Road-to-Sale v1 template has 16 checklist questions (matches the spec comment "X / 16"). This is a named constant `TOTAL_QUESTIONS = 16` in both `HistoryScreen.tsx` (for the chip denominator) and in the `statusBarColor` percentage calculation. If the template is updated to add/remove questions, this constant must be updated. A more robust approach would be to derive it from `session.checklist.totalCount` — but `totalCount` is 0 for sessions loaded from SQLite before a checklist engine has run. Using the constant keeps the chip meaningful for read-only history rows.

**Revisit when:** The NADA template gains or loses questions, or `SqliteSessionRepository` is updated to serialize `checklist.totalCount` correctly for sessions in `ended` status.

---

### DC73 — HistoryScreen status bar color bucketed at 40% / 80% thresholds

**Decision (autonomous):** The spec says "green if ended with high completion %, amber if partial, grey if crashed." The thresholds (≥80% green, 40–79% amber, <40% grey) are chosen to match typical NADA scoring expectations: ≥13/16 = strong pass (green), 7–12/16 = partial (amber), <7/16 = poor/grey. Crashed sessions always use `stepPending` regardless of completion count.

**Revisit when:** Dealer operations team defines formal pass/fail thresholds for the Road-to-Sale scorecard.

---

### DC74 — HistoryScreen uses FlatList with ListEmptyComponent instead of conditional ScrollView

**Decision (autonomous):** The empty state and the list share the same `FlatList` container. `emptyListContent` style applies `flex: 1; justifyContent: 'center'` to the content container when the list is empty, which vertically centers the empty state. This avoids two separate render branches and keeps pull-to-refresh working in both states.

**Revisit when:** N/A — standard RN pattern.
