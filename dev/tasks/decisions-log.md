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
