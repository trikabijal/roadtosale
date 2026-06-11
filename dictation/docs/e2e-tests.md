# Just Talk — Test Plan (cross-platform)

**Scope:** Just Talk, the on-device dictation app, across **macOS, iOS, and Android** (and any future
platform). This is a *plan*, not test code — it defines what to test, at which tier, and through
which public surface. Road to Sale is explicitly **out of scope**.

**Black-box rule (non-negotiable):** every test here drives a **public facade** — a contract method,
a platform adapter's public API, or a user-facing flow — and asserts an **observable** result
(inserted text, persisted file, emitted status, permission state). No test reaches into private
methods or asserts internal structure. Internal logic is covered by unit tests, not this plan.

---

## 1. Architecture as test surface

Just Talk is one pipeline of platform-neutral **stages**, each behind a contract. The contract is
identical on every platform; only the *adapter* behind it changes. Tests are written **once against
the contract** (run on every platform that implements it) plus **once per platform adapter**.

```
[Activation] → [Capture] → [Transcription] → [Cleanup] → [Insertion]
                   ↘ [Persistence: recordings + telemetry] ↙
            [Permissions]            [Live feedback / HUD]
```

| Stage | Neutral contract | macOS adapter | iOS adapter | Android adapter |
|---|---|---|---|---|
| Activation | "start/stop dictation" signal | `HotkeyManager` (global key tap) | keyboard-extension mic key | IME mic key / button |
| Capture | 16 kHz mono PCM float, **in order** | `RecordingEngine` (AVAudioEngine) | `RecordingEngine` (AVAudioEngine) | AudioRecord |
| Transcription | `SpeechTranscriber` (`load`, `transcribe`, `setVocabularyBias`) | WhisperKit | Apple SpeechTranscriber / WhisperKit-lite | Argmax |
| Cleanup | `TextCleanup` (`clean`, `prewarm`) | Foundation Models → rule-based | Foundation Models → rule-based | on-device LLM → rule-based |
| Insertion | "insert text into the focused field" | clipboard write + synthetic ⌘V (`ClipboardPaster`) | `UITextDocumentProxy.insertText` | `InputConnection.commitText` |
| Persistence | `RecordingStore` (save/recent/load), telemetry store | files + GRDB SQLite | App Group container + SQLite | app-private dir + SQLite/Room |
| Permissions | mic + "observe/inject input" + open-access | Mic, Accessibility, Input Monitoring | Mic, keyboard Full Access | RECORD_AUDIO, IME enable |

**Facade Coverage Ledger** — every public boundary must have ≥1 Tier-1 or Tier-2 test:

| Facade / boundary | Public surface under test | Covered by (test IDs) |
|---|---|---|
| `SpeechTranscriber` | load, transcribe, setVocabularyBias | T-STT-1..6 |
| `TextCleanup` | clean (levels, fallback), prewarm | T-CLN-1..7 |
| `RecordingStore` | save, recent (retention), loadSamples | T-PERSIST-1..4 |
| Telemetry store | save record, fetch stats, app-group access | T-TEL-1..3 |
| Capture (`RecordingEngine`) | start/stop, ordered buffers, level/VAD | T-CAP-1..4 |
| Insertion adapter | insert into focused field, restore clipboard | T-INS-1..4 |
| Activation adapter | start/stop signal, suppress, no self-trigger | T-ACT-1..4 |
| Permissions adapter | status read (non-prompting), request, persistence | T-PERM-1..4 |
| App orchestrator (lifecycle) | dictate→insert happy path, retry, timeout | T-FLOW-1..6 |
| Live feedback / HUD | shows while active, low-input warning, position | T-HUD-1..3 |

---

## 2. Test tiers

| Tier | What | When | Budget |
|---|---|---|---|
| **Tier 1 — critical path** | The dictate→transcribe→clean→insert happy path; ordered capture; timeout fallback; never-lose-audio | Every commit | < 30 s |
| **Tier 2 — integration** | Permissions/onboarding, model switching, clipboard restore, retry preservation, persistence + re-transcribe, vocab biasing, per-context cleanup, low-input warning | Before every PR / release | 2–5 min |
| **Tier 3 — edge / stress / quality** | Very long audio, rapid model switching, multilingual (Hinglish/Gujarati) accuracy, keyboard-extension memory ceiling, privacy/retention, boundary cases | Weekly / major release | 10–30 min |

Model-dependent tests (real STT/LLM inference) are gated so they **skip** when the model/runtime is
unavailable (CI without Apple Intelligence, no downloaded weights) rather than fail — see §6.

---

## 2.5 End-to-end user journeys — the PRIMARY suite

These are written as **user behavior**, not API calls: each journey drives the app the way a person
does (activate → speak → see text), across the *whole* pipeline, and asserts only what the user can
observe. They are the primary tests; the per-contract cases in §3 are the component-level backstop
that a failing journey is triaged down into. Every journey is **platform-neutral** in description —
"activate dictation" and "text appears in the focused field" map to each platform's adapter (macOS
hotkey + paste; iOS keyboard mic key + commit; Android IME).

> Driving model audio in a journey: feed a **fixture clip** through the capture boundary (a test
> seam that injects PCM in place of the live mic) so journeys are deterministic and CI-able, while
> still exercising transcription → cleanup → insertion for real.

- **J1 — First run / setup (Tier 1).** Fresh install → launch. *Expect:* onboarding appears; granting mic + (platform input perms) ticks each step; the **Done button stays disabled until the activation key is actually pressed and detected**; after that, completing setup leaves the app showing "Ready".
- **J2 — Dictate a sentence into another app (Tier 1, core).** Focus a text field in a *different* app → activate → speak a one-sentence fixture → end. *Expect:* within the platform's stop→insert budget, the **cleaned sentence appears at the cursor**; the HUD showed listening→processing then disappeared; the user's prior clipboard is intact (macOS).
- **J3 — Long paragraph (Tier 2).** Dictate a ~1–2 min fixture. *Expect:* the **entire** paragraph is inserted — no dropped words at the start or end, not a few junk words.
- **J4 — Hinglish (Tier 3).** With a multilingual model selected, dictate a mixed English/Hindi/Gujarati fixture. *Expect:* all three languages transcribe sensibly; no garbage; an English-only model is never silently used for this.
- **J5 — Too quiet (Tier 2).** Dictate a low-gain fixture. *Expect:* the HUD shows a "speak up / can't hear you" warning **during** recording; a normal-level fixture shows no warning.
- **J6 — Recover a bad dictation (Tier 1).** Force a garbled/empty transcription. *Expect:* the app does **not** paste junk; it preserves the audio and offers Retry; Retry (or "Re-transcribe last recording") recovers the dictation. Nothing the user said is lost.
- **J7 — Model hang doesn't brick the app (Tier 1).** Inject a transcribe/clean that never returns. *Expect:* within the deadline the app falls back / surfaces retry; the HUD never sticks on "Transcribing…/Cleaning…"; **global typing stays responsive the whole time** (no keyboard freeze).
- **J8 — Change model in Settings (Tier 2).** Switch the model; then try to pick an unavailable/"coming soon" provider. *Expect:* the real switch downloads (progress) then loads and is used; the unavailable pick **snaps back with a message and dictation keeps working** (no self-brick).
- **J9 — Mark a miss (Tier 2).** Dictate into another app; within 5 s press the correction shortcut. *Expect:* that dictation is flagged corrected in stats — **even though Just Talk was not the focused app**.
- **J10 — Per-context cleanup (Tier 2).** Set cleanup Off for a code editor. *Expect:* dictating there inserts raw text (no rewrite); dictating elsewhere inserts cleaned text — based on the app focused when recording started.
- **J11 — Reuse a past transcript (Tier 2).** Open history/menu → Copy a past transcript. *Expect:* the button confirms (✓) and pasting elsewhere yields that text.
- **J12 — Clear my data (Tier 3, pending feature).** Invoke "clear all data". *Expect:* stored transcripts and saved recordings are gone. (Fails until the retention feature lands.)
- **J13 — Sustained real-world use (Tier 1, the trust case).** Dictate many times back-to-back while the machine is under load. *Expect:* every dictation inserts; the keyboard never freezes; the HUD stays correct and visible.

**Journey → tier rule:** J1, J2, J6, J7, J13 are Tier 1 (run every commit, fixture-driven, model-mockable where needed). The rest are Tier 2/3.

## 3. Component coverage by contract (backstop for the journeys)

These are the narrower, contract-level cases a failing journey decomposes into — same black-box rule.

### Transcription — `SpeechTranscriber`
- **T-STT-1 (Tier 1):** `load()` then `transcribe()` of a short fixture clip returns non-empty text with plausible word count and confidence in [0,1].
- **T-STT-2 (Tier 1):** **ordered, complete output** — a multi-sentence fixture transcribes to the full text with no dropped head/tail words (regression for the buffer-reordering + tail-truncation bugs).
- **T-STT-3 (Tier 2):** **long audio** (>30 s, beyond Whisper's window) transcribes fully, not a few junk words.
- **T-STT-4 (Tier 2):** `setVocabularyBias([terms])` measurably improves exact-spelling rate of those terms vs no bias, on a clip containing them.
- **T-STT-5 (Tier 2):** silence / near-silent input yields empty (not a hallucinated phantom phrase like "thank you for watching").
- **T-STT-6 (Tier 3):** **multilingual** — a mixed-language (Hinglish, incl. Gujarati) clip transcribes correctly on a multilingual tier; English-only tiers are *not* offered as multilingual.
- *Adapter notes:* run against each platform's engine (WhisperKit / Apple Speech / Argmax) with the **same fixtures + same assertions**. Gain normalization of quiet input is a contract-level behavior (assert a low-level clip still transcribes).

### Cleanup — `TextCleanup`
- **T-CLN-1 (Tier 1):** `clean(level:.full)` removes fillers, fixes capitalization/punctuation, returns non-degenerate text.
- **T-CLN-2 (Tier 1, critical):** a **question/command is cleaned, never answered** ("what time is it" → "What time is it?", not an answer).
- **T-CLN-3 (Tier 1):** **no vocab echo** — the model is never given the term list; output never contains an appended term list (e.g. "…Apts, Teena"), at end or mid-text.
- **T-CLN-4 (Tier 2):** on model unavailable/timeout, **falls back to rule-based** and still returns the full cleaned text (`usedFallback` true); paste is never blocked.
- **T-CLN-5 (Tier 2):** `level:.off` returns raw text unchanged; `.light` preserves wording.
- **T-CLN-6 (Tier 2):** forced vocab spelling applied deterministically in the post-pass for exact matches.
- **T-CLN-7 (Tier 3):** long transcript (~5 min worth) cleans within the scaled timeout (or falls back), output not collapsed/ballooned.

### Capture — `RecordingEngine`
- **T-CAP-1 (Tier 1):** start→feed→stop yields buffers whose total duration ≈ recording duration (no loss) and in **temporal order**.
- **T-CAP-2 (Tier 2):** stop discards no more than the final partial frame; tail content survives.
- **T-CAP-3 (Tier 2):** live level (RMS) is emitted while recording; silence detected after the configured gap.
- **T-CAP-4 (Tier 3):** sustained low-gain input is flagged (drives the low-input warning).

### Insertion (platform adapter)
- **T-INS-1 (Tier 1):** dictated text is inserted into the focused field of the target app.
- **T-INS-2 (Tier 2):** the user's prior clipboard is **restored** after paste (macOS), and a newer user copy is never clobbered (changeCount guard).
- **T-INS-3 (Tier 2):** insertion fires into the correct/focused target even if focus moved during transcription.
- **T-INS-4 (Tier 3):** the app's own synthetic insertion events never re-trigger activation (no self-loop).

### Activation (platform adapter)
- **T-ACT-1 (Tier 1):** the activation signal starts/stops a dictation; toggle and hold modes behave per setting.
- **T-ACT-2 (Tier 2):** activation **does not freeze global input** under heavy load (macOS: listen-only / off-main-thread tap).
- **T-ACT-3 (Tier 2):** activation key state can't get stuck across engine disable/re-enable.
- **T-ACT-4 (Tier 3):** rapid repeated activation doesn't spawn duplicate sessions.

### Persistence — `RecordingStore` + telemetry
- **T-PERSIST-1 (Tier 1):** `save` then `loadSamples` round-trips PCM samples and sample rate.
- **T-PERSIST-2 (Tier 2):** retention — saving more than the cap keeps only the **N newest**, prunes the rest.
- **T-PERSIST-3 (Tier 2):** "re-transcribe last recording" recovers a prior dictation from disk.
- **T-TEL-1 (Tier 2):** a completed dictation persists a telemetry record; weekly stats aggregate it.
- **T-TEL-2 (Tier 2, iOS/Android):** the **shared store** (App Group / shared dir) is reachable from both the host app and the keyboard/IME; records written by one are read by the other.
- **T-TEL-3 (Tier 3):** retention/clear-data — "clear all data" wipes transcripts + saved recordings (see privacy task).

### Permissions (platform adapter)
- **T-PERM-1 (Tier 1):** status reads are **non-prompting** (polling never pops system UI).
- **T-PERM-2 (Tier 2):** the app does not start capture without mic permission (no repeated system prompts); onboarding gates correctly.
- **T-PERM-3 (Tier 2):** all required permissions present → activation installs and works; missing → graceful degrade + clear guidance.
- **T-PERM-4 (Tier 2):** granted permissions **persist** across relaunch (requires stable signing — platform packaging concern).

### App lifecycle (orchestrator)
- **T-FLOW-1 (Tier 1):** **happy path** — activate → speak (fixture) → text appears in target, cleaned.
- **T-FLOW-2 (Tier 1):** **never lose audio** — a failed/garbled transcription preserves the audio and surfaces retry instead of pasting junk.
- **T-FLOW-3 (Tier 1):** **timeout fallback** — a hung transcribe/clean does not hang the UI; it falls back / surfaces retry within the deadline.
- **T-FLOW-4 (Tier 2):** model switching mid-session loads the selected model and never leaves the wrong one active.
- **T-FLOW-5 (Tier 2):** per-context cleanup override (e.g. cleanup off in a terminal/code editor) is honored based on the target at record start.
- **T-FLOW-6 (Tier 3):** prewarm — cleanup latency at stop is materially lower than cold.

### Live feedback / HUD (platform UI)
- **T-HUD-1 (Tier 2):** feedback is visible while dictating and clears on completion.
- **T-HUD-2 (Tier 2):** low-input warning appears when the mic level stays too low, clears when it rises.
- **T-HUD-3 (Tier 3):** a stale/off-screen saved position resets to a visible default (multi-monitor).

---

## 4. Platform matrix — what runs where

- **Contract tests** (`SpeechTranscriber`, `TextCleanup`, `RecordingStore`, capture-ordering, telemetry): authored once per language runtime (Swift for macOS/iOS, Kotlin for Android) but with **identical fixtures and identical assertions** mirrored from this plan. A divergence is a bug in the platform, not the test.
- **Adapter tests** (activation, insertion, permissions, HUD): platform-specific harnesses —
  - macOS: XCTest + a scripted target app for insertion; permission state via the permissions facade.
  - iOS: XCUITest driving the keyboard extension in a host app; verify text commit + App Group telemetry + the keyboard-extension **memory ceiling** (must load without the heavy STT/LLM deps).
  - Android: instrumented tests (IME service) + Espresso for the host app.
- **Quality/accuracy tests** (T-STT-6 multilingual, vocab) run on real devices with the model present; skipped in CI without it.

---

## 5. Test data / fixtures
- Short English clip (~10 s) with known transcript + embedded vocab terms.
- Multi-sentence clip (~30 s) to assert no head/tail word loss.
- Long clip (~5 min) for long-audio + cleanup-timeout.
- Near-silent / low-gain clip for hallucination + low-input.
- Mixed-language (Hinglish incl. Gujarati) clip for multilingual.
- Fixtures live under `dictation/<platform-tests>/fixtures/`, shared by filename across platforms.

## 6. Conventions
- Model-backed tests **`skip`** (not fail) when the engine/runtime/weights are unavailable, so CI without Apple Intelligence or downloaded models stays green; they run in the device/release lane.
- Heavy model-loading tests are excluded from the Tier-1 fast suite (they're Tier 2/3).
- Latency budgets are assertions, not just logs: capture < real-time factor per tier model; stop→insert end-to-end target documented per platform.
- Privacy: no test writes real user audio/text into the repo; fixtures are synthetic or public-domain.

## 7. Known gaps to close (tracked separately)
- iOS keyboard extension is currently a diagnostic stub in the working tree — adapter tests (T-INS iOS, T-TEL-2) can't pass until the real extension is restored.
- No data-retention/clear-data controls yet (T-TEL-3 will fail) — see the privacy task.
