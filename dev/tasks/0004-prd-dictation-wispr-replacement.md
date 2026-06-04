# PRD 0004 — Dictation: Wispr Flow Replacement (macOS daily driver)

**Status:** Approved
**Author:** Bijal Sanghavi
**Date:** 2026-06-04
**Module:** `dictation/` (macOS `DictationApp` target only)
**Supersedes:** Selected non-goals of [PRD 0003](0003-prd-dictation-app.md)

---

## 1. Purpose

PRD 0003 built the macOS Dictation app to Phase 2: a menu-bar app that records on a
held hotkey, transcribes with on-device WhisperKit, and pastes via the clipboard.
It works, but it is a prototype, not a daily driver.

This PRD takes the existing macOS app from "works in a demo" to **"good enough that
Bijal cancels his Wispr Flow subscription and uses this every day."**

The defining gap is **AI cleanup**: Wispr Flow's value is not transcription, it is
turning spoken rambling (fillers, false starts, no punctuation, literal "new
paragraph") into clean written text. We close that gap **fully on-device** using the
**Apple Foundation Models framework** (macOS 26, Apple Silicon) — no audio and no text
ever leaves the machine.

### Hard constraint (non-negotiable)

> **100% on-device. No third-party voice transcription and no third-party text
> processing.** WhisperKit (local) for speech-to-text; Apple Foundation Models
> (local) for cleanup. No OpenAI, no cloud API of any kind.

This is already satisfied for transcription; it constrains every new feature too.

---

## 2. Target environment (verified)

| Fact | Value | Implication |
|------|-------|-------------|
| macOS | 26.5 (Tahoe) | Apple Foundation Models framework available |
| Chip | Apple M4 | `large-v3-turbo` + on-device LLM are both fast |
| Transcription | WhisperKit `large-v3-turbo`, local | Keep |
| Pipeline | Batch (record → stop → transcribe once → paste) | Keep for v1; streaming deferred |

This PRD's *implementation* is **macOS-only**, but its *design* is cross-platform by
construction (see §2.5). The iOS keyboard (PRD 0003 Phase 3) is unaffected and remains
gated on a paid Apple Developer Program account.

---

## 2.5 Cross-platform portability — the real point

The Mac app is a **dogfooding lab**. The intent is: try it on myself daily, and have
the learnings flow back to iOS and Android, where Road to Sale actually ships.

**Reuse principle: code does not port — contracts, data, and learnings do.** WhisperKit
and Apple Foundation Models are Apple-only; Android uses different engines. So we never
reuse Swift on Android. We reuse three things, each authored to be portable from day one:

1. **The contract.** A stable `TextCleanup` interface —
   `clean(rawText, level, vocab, commandGrammar) → {cleanedText, opsApplied, metadata}` —
   defined once and implemented natively per platform. This is the same strategy pattern
   `voice-engine/` already uses for STT. Cleanup is another strategy behind a contract,
   **not** Mac-app code.
2. **The knowledge as data, not code.** Prompt templates per level, filler/disfluency
   lists, spoken-command grammar (`"new paragraph"` → `\n\n`), vocabulary replacement
   map, hallucination junk-phrase list, and confidence thresholds live as **git-tracked
   YAML/JSON in `voice-engine/`** (the `vehicle-feature-catalog` philosophy applied to
   text). Swift (Foundation Models) and Kotlin (Gemini Nano / MediaPipe) load the *same
   pack*.
3. **The telemetry / learnings loop.** One identical telemetry schema across all
   platforms (`raw · cleaned · level · was_corrected · latency · confidence ·
   frontmost_app · failure_tags`). Daily Mac use produces the labelled dataset that tunes
   the shared pack.

**Platform engine matrix:**

| Layer | macOS (now) | iOS (RTS) | Android (RTS) |
|-------|-------------|-----------|---------------|
| STT | WhisperKit | Apple SpeechTranscriber / WhisperKit | Argmax Pro / sherpa-onnx |
| Cleanup LLM | Apple Foundation Models | Apple Foundation Models (iOS 26+) | Gemini Nano / MediaPipe LLM |
| Contract + data pack + telemetry | shared | shared | shared |

**Key consequence:** iOS is nearly free — `DictationCore` already compiles for iOS and
Foundation Models ships on iOS 26, so the Mac cleanup engine *is* the iOS engine. Only
**Android** needs a second native engine, behind the same contract + pack.

**Profiles.** Dictation cleanup (general writing) and Road-to-Sale cue extraction want
different prompts. The pack is **profile-based**: a `dictation` profile and a
`road-to-sale` profile share the same engine + contract, different data. The engine and
contract reuse 100%; prompt data is profile-specific but same format.

**Where things live (respects facade + boundary rules):**
- `voice-engine/` — the portable `TextCleanup` contract, the data-pack schema + the
  `dictation` profile pack, and the cross-platform telemetry schema.
- `dictation/Shared/DictationCore` — the **Apple strategy** implementing the contract
  (Foundation Models + rule-based fallback). Serves macOS now, iOS later for free.
- `road-to-sale-app/` — consumes `voice-engine` (STT + cleanup) via its facade; never
  reaches into the dictation app.

---

## 3. Supersedes from PRD 0003

PRD 0003 declared these non-goals. Daily-driver use reverses them:

- ~~No spoken commands~~ → "new paragraph" / "new line" / "bullet point" are obeyed (via cleanup).
- ~~No custom vocabulary UI~~ → custom vocabulary is in scope (names/jargon biasing + replacement map).
- ~~No Accessibility workarounds~~ → still clipboard-first, but clipboard is now preserved/restored and the hotkey tap self-heals.

Unchanged non-goals: not cloud, not Android, not a full team/sharing product.

---

## 4. Goals

| Goal | Description | Acceptance |
|------|-------------|------------|
| **G1 — Trustworthy** | Never destroys the user's clipboard; hotkey never silently dies; no phantom "thank you" inserts. | 30 consecutive dictations across ≥5 apps with zero clipboard loss, zero dead-hotkey events, zero hallucinated inserts. |
| **G2 — AI cleanup (Full)** | On-device Foundation Models pass turns raw speech into clean written text by default: strip fillers, fix punctuation/caps, obey spoken commands, lightly restructure run-ons without changing meaning. | The canonical example below produces the expected clean output. |
| **G3 — Always there** | Launches at login, lives in the menu bar, shows on-screen feedback while recording so the user always knows it is listening. | Reboot → app running; recording shows a visible HUD. |
| **G4 — Adapts to me** | Custom vocabulary for names/jargon; push-to-talk **and** toggle modes. | Added terms transcribe correctly; tap-to-start/tap-to-stop works. |
| **G5 — Permanent install** | Signed Release build in `/Applications`, survives reboots, one-command build/run. | `./build.sh && ./run.sh` documented; app runs standalone with Xcode closed. |

### G2 canonical example

> **Spoken:** "um so like i think we should uh ship it monday new paragraph lets sync tomorrow ok"
>
> **Output (Full):**
> ```
> I think we should ship it Monday.
>
> Let's sync tomorrow.
> ```

---

## 5. Cleanup behavior (the headline feature)

Cleanup runs **between** transcription and paste, in a new `CleanupEngine` inside
`DictationCore`, behind a `TextCleanup` protocol so it is testable and degrades
gracefully where Foundation Models is unavailable.

**Three intensity levels** (Settings → default **Full**):

| Level | Behavior |
|-------|----------|
| **Off** | Paste WhisperKit output verbatim. No LLM pass. |
| **Light** | Fix punctuation + capitalization, remove obvious fillers (um/uh), obey "new paragraph". Preserve exact words/phrasing; no restructuring. |
| **Full** (default) | Everything in Light, plus: remove false starts, lightly restructure run-ons, obey command words (new line, new paragraph, bullet point). **Must preserve meaning — never invent content or paraphrase intent.** |

**Design rules:**
- The prompt instructs the model to **return only the cleaned text**, nothing else (no preamble, no quotes).
- A hard **fallback path**: if Foundation Models is unavailable, the model errors, or output looks degenerate (empty / far longer than input), fall back to a deterministic rule-based cleaner (filler regex + capitalization + command-word substitution) so dictation never breaks.
- Custom-vocabulary replacement map is applied **after** cleanup (so the LLM can't undo a forced spelling).
- Latency budget: cleanup adds ≤ ~1.5 s on M4 for a normal utterance; cleanup is skipped automatically for very short clips (≤ a few words) where it adds no value.

---

## 6. Functional requirements

### FR-A — Correctness (Phase A)
- **FR-A1** Clipboard preserve/restore: snapshot the full pasteboard before paste, paste the transcript, restore the prior contents after the paste lands.
- **FR-A2** Hotkey self-heal: re-enable the `CGEventTap` on `.tapDisabledByTimeout` and `.tapDisabledByUserInput`.
- **FR-A3** Hallucination filter: drop results that are empty, below a confidence floor on short audio, or match a known-junk phrase list ("thank you", "thanks for watching", etc.) when audio was effectively silent.
- **FR-A4** Model-download progress: surface WhisperKit first-run download progress (%) in the menu bar status.

### FR-B — AI cleanup (Phase B)
- **FR-B0** Portable layer (authored in `voice-engine/`): the `TextCleanup` contract, the
  data-pack schema (prompts per level, filler list, command grammar, vocab map,
  junk-phrase list, thresholds), the `dictation` profile pack, and the cross-platform
  telemetry schema. This is what iOS/Android reuse.
- **FR-B1** `TextCleanup` protocol + `FoundationModelsCleanup` + `RuleBasedCleanup` fallback in `DictationCore`, loading the data pack from FR-B0 (no cleanup knowledge hardcoded in Swift).
- **FR-B2** Cleanup wired into `AppState.performTranscription` between transcribe and paste.
- **FR-B3** Settings control for intensity (Off / Light / Full), persisted; default Full.
- **FR-B4** Telemetry stores both raw and cleaned text so cleanup quality can be reviewed.

### FR-C — Ergonomics (Phase C)
- **FR-C1** Launch at login via `SMAppService`, toggle in Settings.
- **FR-C2** Recording HUD: small always-on-top floating panel with live level/waveform + state, visible only while recording/transcribing.
- **FR-C3** Custom vocabulary: Settings text list → WhisperKit prompt biasing + post-cleanup replacement map.
- **FR-C4** Toggle mode: tap Fn to start, tap to stop, in addition to hold-to-talk. Setting selects mode.
- **FR-C5** (Optional) subtle start/stop sound.

### FR-D — Permanent install (Phase D)
- **FR-D1** `build.sh` (Release build → `/Applications`) and `run.sh`.
- **FR-D2** Updated README + `docs/architecture.md` reflecting cleanup engine, HUD, login item.

---

## 7. Non-goals (this PRD)

- Streaming / partial-result transcription (Phase E, post-cancel optimization).
- Per-app formatting profiles (Phase E).
- iOS keyboard changes.
- Any cloud or third-party processing.

---

## 8. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Foundation Models API availability/behavior differs from expectation | Protocol + rule-based fallback; cleanup is a toggle; never block paste on cleanup failure. |
| LLM changes meaning / hallucinates content | Conservative prompt ("preserve meaning, return only cleaned text"); degenerate-output guard; Off/Light escape hatches. |
| Added latency hurts the "instant" feel | Skip cleanup on short clips; measure cleanup latency in telemetry; keep transcription batch but fast (turbo on M4). |
| Synthetic ⌘V fails in some apps | Out of scope to fully solve; clipboard-first remains, and clipboard is now preserved so manual ⌘V is always available. |

---

## 9. Definition of done

- [ ] G1–G5 acceptance criteria met.
- [ ] Canonical Full-cleanup example produces expected output.
- [ ] App launches at login, survives reboot, runs with Xcode closed.
- [ ] No new third-party dependencies beyond WhisperKit, GRDB, and Apple frameworks.
- [ ] README + architecture.md updated; PRD non-goal reversals documented.
- [ ] Bijal cancels Wispr Flow. 🎉
