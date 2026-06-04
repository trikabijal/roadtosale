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

## Phase B — The Wispr brain (on-device AI cleanup)

- [ ] **B0 — Portable contract + data pack** (in `voice-engine/`, NOT in the Mac app)
  - [ ] Define the `TextCleanup` contract (language-neutral doc + TS facade types).
  - [ ] Data-pack schema (YAML/JSON): prompts per level, filler list, command grammar, vocab map, junk-phrase list, thresholds.
  - [ ] Author the `dictation` profile pack (first profile; `road-to-sale` profile deferred to RTS work).
  - [ ] Freeze the cross-platform telemetry schema (raw · cleaned · level · was_corrected · latency · confidence · frontmost_app · failure_tags).
- [ ] **B1 — TextCleanup protocol + engines** (`Shared/Sources/DictationCore/CleanupEngine.swift`), loading the FR-B0 pack — no cleanup knowledge hardcoded in Swift.
  - [ ] `protocol TextCleanup { func clean(_ text:String, level:CleanupLevel) async -> String }`.
  - [ ] `FoundationModelsCleanup` (`import FoundationModels`) with conservative prompt per level.
  - [ ] `RuleBasedCleanup` fallback (filler regex, capitalization, command-word substitution).
  - [ ] Degenerate-output guard → fall back; never throw to caller.
- [ ] **B2 — Wire into pipeline** (`DictationApp/AppState.swift`)
  - [ ] Run cleanup between `transcribe` and `clipboardPaster.writeAndPaste`.
  - [ ] Skip cleanup for very short clips.
- [ ] **B3 — Settings: intensity** (`DictationApp/SettingsView.swift`, persisted; default Full).
- [ ] **B4 — Telemetry: raw + cleaned** (`Shared/Sources/DictationCore/TelemetryStore.swift` migration v2).
- [ ] **B — tests:** cleanup unit tests incl. canonical example; rule-based fallback tests.
- [ ] **B — commit:** `feat(dictation): on-device AI cleanup via Apple Foundation Models`

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
A (sequential A1→A4)  →  B  →  C (C1–C4 parallelizable)  →  D  →  [E optional]
```

## Definition of done — see PRD 0004 §9.
