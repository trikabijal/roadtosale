# Tasks — PRD 0004: Dictation Wispr Flow Replacement (macOS)

Branch: `feat/dictation-app` · one phase per group · tests/build green before each commit.

All paths relative to `dictation/`.

---

## Phase A — Trustworthy (correctness foundation)

- [x] **A1 — Clipboard preserve/restore** (`DictationApp/ClipboardPaster.swift`)
  - [x] Snapshot existing pasteboard items before writing the transcript.
  - [x] Write transcript, fire ⌘V, then restore the prior contents after the paste settles (delay tuned so paste lands first).
  - [x] Handle the no-prior-content and non-string-content cases.
- [x] **A2 — Hotkey self-heal** (`DictationApp/HotkeyManager.swift`)
  - [x] In the tap callback, handle `.tapDisabledByTimeout` and `.tapDisabledByUserInput` by re-enabling the tap.
  - [ ] Verify recovery after display sleep / heavy load. *(needs on-device runtime test)*
- [x] **A3 — Hallucination filter** (`Shared/Sources/DictationCore/TranscriptionEngine.swift`)
  - [x] Known-junk phrase list ("thank you", "thanks for watching", etc.) — flagged for migration to FR-B0 pack.
  - [x] Skip transcription when peak amplitude < silence floor; drop junk phrases on short/low-confidence clips.
  - [ ] Unit test the filter on representative inputs *(deferred — no DictationCore test target yet)*.
- [x] **A4 — Model-download progress** (`TranscriptionEngine.swift` + `AppState`)
  - [x] Split one-shot init into `WhisperKit.download(...progressCallback:)` → load; surface % in the status message.
- [x] **A — commit:** `fix(dictation): preserve clipboard, self-heal hotkey, filter hallucinations, show model progress`

## Phase B0 — Pluggable contracts (model-agnostic STT + Cleanup)

- [x] **S1 — `SpeechTranscriber` protocol** — `SpeechTranscriber.swift` (protocol + provider/config/factory + `MockTranscriber` + `UnavailableTranscriber`); WhisperKit moved to `WhisperKitTranscriber.swift`. Result is now provider-agnostic (`provider` + `model`).
- [x] **S2 — STT provider config + factory** — `STTProvider`, `STTConfig {provider, model}` persisted (legacy `modelTier` migrated); `SpeechTranscriberFactory.make`; `AppState` composes via factory + `setSTTConfig`.
- [x] **S3 — Settings: STT provider + model pickers** (WhisperKit + tier live; appleSpeech shown "coming soon").
- [x] **B0 — Portable contracts** (in `voice-engine/`)
  - [x] `TextCleanup` contract authored: `src/cleanup/{types,base,registry,rule-based}.ts` + exports + 6 tests; `docs/model-contracts.md` documents both STT + cleanup contracts, `{provider,model}` config, platform matrix, telemetry schema.
  - [x] `rule-based` reference fallback implementation (cross-platform behavior spec).
  - [x] Cleanup data-pack JSON authored: `voice-engine/cleanup-packs/dictation.json`, vendored as `DictationCore/Resources/dictation-cleanup-pack.json`.
  - [x] `dictation` profile pack done. Telemetry schema documented + frozen in B4.

## Phase B — The Wispr brain (on-device AI cleanup)

- [x] **B1 — TextCleanup protocol + engines + provider config**
  - [x] `TextCleanup.swift`: protocol + `CleanupLevel`/`CleanupProvider`/`CleanupConfig` (persisted) + `CleanupPack`/loader + `TextCleanupFactory`.
  - [x] `FoundationModelsCleanup.swift` (Apple FM, availability-gated) + `RuleBasedCleanup.swift` fallback, loading the pack — no cleanup knowledge hardcoded.
  - [x] Degenerate-output guard (empty / ballooned / collapsed) → fall back; `clean` never throws.
- [x] **B2 — Wire into pipeline** (`AppState.swift`) — cleanup runs between transcribe and paste ("Cleaning…"); skipped when off or word count < pack threshold.
- [x] **B3 — Settings: level + engine pickers** (default Full; on-device note).
- [x] **B4 — Telemetry: raw + cleaned** (migration v2: `raw_text`, `cleanup_level`, `cleanup_provider`; fixed camelCase↔snake_case column mapping).
- [x] **B — tests:** rule-based reference + 6 cleanup tests in voice-engine (21 pass). Swift unit tests + LLM canonical-example validation deferred to on-device run (no DictationCore test target; FM needs the on-device model).
- [x] **B — commit:** `feat(dictation): on-device AI cleanup via Apple Foundation Models`

## Phase C — Daily-driver ergonomics

- [x] **C1 — Launch at login** (`LoginItem.swift` / `SMAppService`; Startup toggle).
- [x] **C2 — Recording HUD** (`RecordingHUD.swift`: floating non-activating panel, live level meter, phase label; driven by new `RecordingEngine` level delegate).
- [x] **C3 — Custom vocabulary** (Settings `VocabularyEditor` → WhisperKit `promptTokens` bias + post-cleanup forced-spelling vocab map).
- [x] **C4 — Toggle mode** (hold vs tap-to-start/stop; Settings picker; `HotkeyMode`).
- [x] **C5 — Start/stop sounds** (NSSound, Settings toggle, default off).
- [x] **C — commit:** `feat(dictation): daily-driver ergonomics — login item, HUD, vocabulary, toggle, sounds`

## Phase D — Permanent install

- [x] **D1 — `build.sh` + `run.sh`** (Release build → `./build`; `install` → `/Applications`; optional `DEVELOPMENT_TEAM` for persistent permissions).
- [x] **D2 — Docs** (README + `docs/architecture.md`: contracts, cleanup engine, HUD, login item, data flow).
- [x] **D — commit:** folded into the C/D commit.
- [ ] **D — final:** install, reboot test, then cancel Wispr Flow. *(on-device, user)*

## Phase E — Optional polish (post-cancel)

- [ ] **E1 — Streaming partial transcripts** — DEFERRED pending decision: reworks the core
  batch pipeline (can't be validated without an on-device run); benefit is mostly live HUD
  feedback since cleanup needs the full utterance. Awaiting user choice of approach.
- [x] **E2 — Per-app cleanup profiles** — `AppCleanupProfile` (bundleId→level), persisted;
  `effectiveLevel(forBundleId:)` resolves per-app override over global at transcribe time;
  Settings `AppProfilesEditor` (add running app, level picker, remove).
- [x] **E3 — History search / re-paste** — `TelemetryStore.search(matching:)`;
  `HistoryView` Window (searchable list, copy-to-clipboard re-paste); "History" menu-bar button.
- [x] **E (E2+E3) — commit:** `feat(dictation): per-app cleanup profiles + searchable history`

---

## Order

```
A (done)  →  B0 (contracts: STT + cleanup)  →  B (cleanup brain)  →  C (C1–C4 parallelizable)  →  D  →  [E optional]
```

## Definition of done — see PRD 0004 §9.
