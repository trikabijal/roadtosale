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

- [ ] **C1 — Launch at login** (`SMAppService`; Settings toggle).
- [ ] **C2 — Recording HUD** (new `DictationApp/RecordingHUD.swift`: floating always-on-top panel, live level, state).
- [ ] **C3 — Custom vocabulary** (Settings list → WhisperKit prompt bias + post-cleanup replacement map).
- [ ] **C4 — Toggle mode** (tap-to-start/stop; Settings selects hold vs toggle).
- [ ] **C5 — (optional) start/stop sound.**
- [ ] **C — commit(s):** one per sub-feature.

## Phase D — Permanent install

- [ ] **D1 — `build.sh` + `run.sh`** (Release build → `/Applications`).
- [ ] **D2 — Docs** (README + `docs/architecture.md`: cleanup engine, HUD, login item, supersede note).
- [ ] **D — commit:** `chore(dictation): build/run scripts + docs for daily-driver install`
- [ ] **D — final:** install, reboot test, then cancel Wispr Flow.

## Phase E — Optional polish (post-cancel)

- [ ] Streaming partial transcripts.
- [ ] Per-app formatting profiles.
- [ ] Dictation history search / re-paste.

---

## Order

```
A (done)  →  B0 (contracts: STT + cleanup)  →  B (cleanup brain)  →  C (C1–C4 parallelizable)  →  D  →  [E optional]
```

## Definition of done — see PRD 0004 §9.
