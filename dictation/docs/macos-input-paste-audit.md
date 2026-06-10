# Just Talk (macOS) — Input & Paste Subsystem Audit

**Date:** 2026-06-09
**Branch:** feat/dictation-app
**Trigger:** Four user-reported bugs in two sessions, all clustered in the keyboard-hotkey + clipboard-paste path. Per the "stop patching, audit" rule, this is a single architecture-vs-code pass. **No fixes applied during this audit.**

## Scope

- `JustTalk/HotkeyManager.swift` — CGEventTap lifecycle, watchdog, key matching
- `JustTalk/ClipboardPaster.swift` — clipboard snapshot/restore + synthetic ⌘V
- `JustTalk/AppState.swift` — paste/transcription orchestration on @MainActor
- `JustTalk/PermissionsService.swift` — permission checks/prompts
- `JustTalk/HotkeyConfig.swift`, `SettingsView.swift`, `OnboardingView.swift` — supporting

## Symptom → root-cause map

| # | Reported symptom | Findings |
|---|---|---|
| 1 | Whole-Mac keyboard froze; typed chars didn't reach Terminal; `@` wouldn't type | **F1** (+ F8) |
| 2 | Right ⌥ Option "stopped being tracked"; changing the key recovered it | **F4** (caused by F1) |
| 3 | Changing key to Right ⌘ popped ~5 permission prompts | **F2 + F3** |
| 4 | Foreign content pasted with/instead of the transcript: **text** vs **image** | **F10** (the text) + **F5** (an image, if any) |
| 5 | Same transcript text appeared twice; cleaned text contained the literal vocab list | **F10** |

Three structural root causes underlie all five: **the event tap runs on the main thread and is re-armed in a loop (F1–F4)**; **the clipboard restore is a timed race (F5)**; and **the cleanup model echoes its own prompt — vocab block + dictated text — into the output (F10)**. The first destabilizes the second. F10 is the primary explanation for the "foreign text" symptom that F5 was originally blamed for.

---

## Findings

### F1 — CRITICAL — Active CGEventTap is pumped on the main run loop

`HotkeyManager.swift:154-155`
```swift
let src = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
CFRunLoopAddSource(CFRunLoopGetMain(), src, .commonModes)
```
The tap is `.cghidEventTap` + `.defaultTap` (active) — it sits **inline** in HID event delivery: macOS will not deliver any keystroke to any app until the callback returns. The callback runs on the **main** run loop. Whenever the main thread is busy — AI cleanup (`AppState.swift:399 await cleanup.clean(...)`), transcription, or heavy SwiftUI work — the callback can't run, and **every keystroke system-wide stalls** until macOS disables the tap by timeout. This is the freeze; the partial typing (`pwd` worked, `@` didn't) is the thread briefly freeing between stalls.

**Proposed fix:** Run the tap's `CFRunLoop` on a dedicated, long-lived background `Thread` the manager owns. Keep the callback minimal (it already dispatches real work to main via `DispatchQueue.main.async`). The main thread can then block freely without ever touching global input. This is the single highest-impact fix and also removes the trigger for F4.

**UPDATE (recurrence) — the thread move was necessary but NOT sufficient.** The freeze recurred under real use. Root cause is more fundamental: the tap was created **active** (`.defaultTap`), which sits *inline* in HID delivery — every keystroke system-wide waits for our callback to be **scheduled and return**. Under heavy CPU load (exactly when transcription/cleanup runs), even a background thread can be slow to schedule, so the keyboard stalls again. **Real fix:** create the tap **`.listenOnly`** whenever the hotkey doesn't suppress (right ⌘/⌥/⌃ — `config.suppresses == false`). A listen-only tap observes the key identically but is **not in the delivery path**, so it can never freeze input regardless of load. Only Fn / function keys (which must consume the event) keep `.defaultTap`. Implemented in `startEventTap` via `let tapOptions = config.suppresses ? .defaultTap : .listenOnly`. The dedicated thread is retained for the remaining active-tap (suppressing) case.

---

### F2 — CRITICAL — Input Monitoring permission is never checked or requested

`PermissionsService.swift` handles only Microphone + Accessibility (`AXIsProcessTrusted`). There is **no** `IOHIDCheckAccess` / `CGPreflightListenEventAccess` anywhere in the app (verified by grep).

A keyboard `CGEventTap` on modern macOS requires the **Input Monitoring** privacy permission, which is *distinct* from Accessibility. `startEventTap()` gates only on Accessibility:
```swift
// HotkeyManager.swift:65
if AXIsProcessTrusted(), startEventTap() { ... }
```
With Accessibility granted but Input Monitoring not yet granted, `CGEvent.tapCreate` returns a **non-nil but disabled** tap. The app believes it succeeded. This is the precondition for F3's prompt loop.

**Proposed fix:** Add Input Monitoring to `PermissionsService` (`CGPreflightListenEventAccess()` to check, `CGRequestListenEventAccess()` to prompt once), surface it as its own onboarding card alongside Accessibility, and gate `startEventTap()` on it.

---

### F3 — HIGH — Watchdog rebuilds the tap every 2s, re-prompting each time

`startEventTap()` returns `true` whenever `tap != nil`, **without checking `CGEvent.tapIsEnabled`**:
```swift
// HotkeyManager.swift:152-159
guard let tap else { return false }
... CGEvent.tapEnable(tap: tap, enable: true)
self.eventTap = tap
return true   // reports success even if the tap never actually enabled
```
The watchdog then fires every 2s and, seeing the tap not enabled, rebuilds it:
```swift
// HotkeyManager.swift:106-112  ensureTapAlive()
if !CGEvent.tapIsEnabled(tap: tap) {
    CGEvent.tapEnable(tap: tap, enable: true)
    if !CGEvent.tapIsEnabled(tap: tap) { start() }   // → tapCreate → another prompt
}
```
When Input Monitoring is pending (F2), each rebuild re-triggers the system prompt. Five prompts ≈ 10 seconds of this loop — matching the report. (These were almost certainly Input Monitoring "receive keystrokes" prompts, easily mistaken for or mixed with a mic prompt.)

**Proposed fix:** After create+enable, verify `tapIsEnabled`; if false, treat as *not installed* (fall back to the non-suppressing monitor) instead of reporting success. In the watchdog, distinguish "disabled by timeout" (re-enable in place, never recreate) from "never authorized" (stop, prompt once, wait for the grant — do not loop). Add a backoff so rebuilds can never fire faster than the grant flow.

---

### F4 — HIGH — Key stops firing after a timeout-disable; `keyIsDown` can stick

Two parts:
1. When F1's stall triggers `.tapDisabledByTimeout`, the tap is dead until rebuilt. `handleCGEvent` re-enables in place (`HotkeyManager.swift:165-168`), but if the stall persists it's immediately disabled again. Recovery only happened when you manually changed the key (`setConfig → start()`, `HotkeyManager.swift:128`) — a full rebuild. Right ⌥ vs Right ⌘ is irrelevant; the rebuild is what recovered it.
2. `keyIsDown` is never reset when the tap is disabled. If the tap dies between a press and its release, `keyIsDown` stays `true`; the next press sees no edge and never fires:
```swift
// HotkeyManager.swift:197-205
if down && !keyIsDown { ... }      // stuck-true → this branch never runs again
```

**Proposed fix:** F1 removes the timeout cause outright. Additionally, reset `keyIsDown = false` on every `.tapDisabledBy*` event and on each re-enable, so a recovered tap always starts from a clean edge state.

---

### F5 — HIGH — Clipboard restore is a 700 ms race; prior clipboard pastes instead of the transcript

`ClipboardPaster.swift:55-67`
```swift
DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { self?.sendCmdV()
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
        self.restore(self.burstSnapshot ?? [], to: pasteboard)   // old clipboard back
    }
}
```
The transcript is on the clipboard only for the ~0.7 s between paste and restore. If the paste is delayed — frozen keyboard (F1), slow `targetApp?.activate()` (`:52`), or a late-reading app like Terminal — the target reads the pasteboard **after** restore has already put the user's previous clipboard back. Since `snapshot()` copies *all* representations including images (`:76-88`), the user pastes an old image or whatever text they'd last copied. The 700 ms is explicitly a guess in the comments.

**Scope correction:** the *text* contamination the user reported is **F10** (cleanup model echoing its prompt), not this race. F5 remains a real, independent bug, but its observable signature is narrower than first thought — a stale **image** or stale non-transcript text reappearing when the paste is delayed. Keep it; it is no longer the lead suspect for the "vocab words in my transcript" report.

**Proposed fix (needs a design choice):**
- Preferred: don't restore on a fixed timer. Confirm the paste consumed the pasteboard (watch `NSPasteboard.changeCount` / app focus) before restoring, with a bounded timeout fallback.
- Or: drop auto-restore entirely and restore the user's clipboard on the *next* dictation or on an explicit action — simpler and race-free, at the cost of leaving the transcript on the clipboard longer.
- Either way, gate restore on paste completion rather than a wall-clock delay. F1's fix shrinks the race but does not close it.

---

### F6 — MEDIUM — Per-keystroke retain leak in the tap callback

`HotkeyManager.swift:145, 170`
```swift
guard let refcon else { return Unmanaged.passRetained(event) }   // :145
let pass = Unmanaged.passRetained(event)                          // :170 (pass-through path)
```
The value returned from a CGEventTap callback is not released by the system, so `passRetained` leaks one `CGEvent` retain per passed event. With a non-suppressing modifier hotkey (Right ⌘/⌥/⌃ — `suppresses == false`), every `flagsChanged` and every pass-through event leaks. Should be `Unmanaged.passUnretained(event)`.

**Proposed fix:** Use `passUnretained(event)` for pass-through returns. Verify against current CGEventTap ownership semantics, but the asymmetry (retained on the common path) is a textbook leak.

---

### F7 — MEDIUM — Synthetic ⌘V is posted into the same tap point the app listens on

`ClipboardPaster.sendCmdV()` posts to `.cghidEventTap` (`:105, :109`); `HotkeyManager` taps `.cghidEventTap`. Currently harmless (V's keyCode/keyDown matches no modifier/function matcher) but fragile: a future ⌘-based hotkey, or modifier-flag bleed, could self-trigger recording, and the synthetic event is injected into the (possibly stalled) main-thread tap path.

**Proposed fix:** Tag the synthetic events via a dedicated `CGEventSource` user-data field and ignore tagged events in `handleCGEvent`; or post to `.cgAnnotatedSessionEventTap`. Low effort, removes a latent foot-gun.

---

### F8 — LOW (defense-in-depth) — Heavy cleanup/transcription runs on the @MainActor

`AppState` is `@MainActor`; `await cleanup.clean(...)` (`:399`) and transcription run on it. Even after F1 moves the tap off-main, model inference on the main actor will still beach-ball the UI/HUD.

**Proposed fix:** Run FoundationModels/WhisperKit inference off-main (dedicated actor or detached task); keep the main actor for published UI state only.

---

### F10 — HIGH — Cleanup model echoes its own prompt (vocab block + dictated text) into the output

`FoundationModelsCleanup.swift:77-92` builds a single user-turn prompt that concatenates the task instructions, a **"Known names and terms — … use this exact spelling: <list>"** block, and the dictated text. Small on-device models routinely echo such injected blocks back into their answer. The output guard `sanitizeOutput` (`:96-108`) only strips `<transcript>` tags and a leading `Dictated text:`/`Output:` label — it does **not** remove an echoed "Known names and terms…" sentence or a repeated copy of the dictation. So when the model echoes, it lands verbatim on the clipboard.

This is the primary cause of:
- **"exact words from my vocabulary" in the transcript** — the injected vocab list echoed back. The user observed the literal "Known names and terms…" text pasted.
- **"same transcript text came twice"** — the model echoes `Dictated text:\n{rawText}` and *also* emits the cleaned version. `isDegenerate` (`:118-125`) only rejects >3× ballooning, so a ~2× duplicate passes. Intermittent (matches "it varied"): long dictation + short vocab keeps the ratio under 3×.

Note the redundancy: `vocab` is documented as "forced spellings, applied AFTER cleanup" (`TextCleanup.swift:62`) and *is* re-applied deterministically in the post-pass (`FoundationModelsCleanup.swift:56`). Injecting it into the prompt body too is both unnecessary and the leak source.

**Proposed fix (layered):**
1. **Move term-biasing out of the user turn.** Put vocab/lexicon guidance in the session `instructions` (system role), not concatenated into the prompt content. `LanguageModelSession(instructions:)` already exists (`:37`) — send only the delimited dictated text as the turn. Systems-role guidance is far less likely to be echoed.
2. **Harden `sanitizeOutput`:** if the output contains the instruction fragment ("Known names and terms"), cut from there; detect and drop a verbatim/near-verbatim duplicate of `rawText`.
3. **Tighten `isDegenerate`:** treat output that *contains* `rawText` as a substring and is materially longer (or ~2× with high token overlap) as degenerate → fall back.
4. **Strongest option:** use FoundationModels guided generation (`@Generable` / structured response) so the result is constrained to a single `cleanedText` field and cannot carry echoed prose.

The fallback `RuleBasedCleanup` path does not build an LLM prompt, so it is unaffected — but confirm it during the fix.

**UPDATE 2 (root-cause fix) — stop feeding vocab to the model at all.** The echo kept recurring because it is **non-deterministic**: re-running the user's exact recording through the current pipeline produced NO echo, yet their live run appended "Apts, Teena". The model intermittently echoes the vocab list it's given. Every symptom-stripping fix passed its one-time test and then resurfaced. Real fix: **`FoundationModelsCleanup` no longer puts the vocab/term list in the model prompt or instructions** — the model can't echo terms it never receives. Spelling is still guaranteed by (a) the STT vocab-bias prompt and (b) the deterministic `applyMap` post-pass. The `stripTrailingTermEcho` net was also rewritten to a **detached-trailing-fragment** rule (strips a run of only-terms — including sub-words like "Apts" from "Lakshachandi Apts" — that follows a sentence-ending mark; preserves names used inside a sentence), with no count threshold.

**FINAL (per user decision): the stripper was deleted entirely.** It was a hack — it only caught echoes at the *end* of the text, and since we don't know *why* the model echoes, it could not guarantee anything mid-text. With the model no longer fed the term list, the echo source is gone, so the net is unnecessary. Empirical proof the STT bias carries the vocab (10s clip naming all 6 terms, large-v3-turbo): **no bias → 2/6** correct ("Tina", "Dipali", "Lakshachan D apps", "Whisper Flow"); **with bias → 5/6** (Teena, Deepali, Lakshachandi Apts recovered). So removing the LLM injection does not lose vocab — the STT bias makes terms correct and is kept; the exact-match `applyMap` post-pass force-fixes whole-word exact matches. Known residual: hard near-misses like "Wispr Flow" -> "Wisp of Flow" still slip; a deterministic fuzzy post-pass would close that gap but was deferred.

**UPDATE (recurrence) — the F10 fix was incomplete.** The vocab echo recurred in real use: the model appended the **bare name list** ("… all over my system. Bijal, Bijal, Meher, Bijal.") *without* the "Known names and terms" preamble, so `sanitizeOutput`'s marker missed it, and the short tail stayed under `isDegenerate`'s 1.5× threshold. **Completion:** added `CleanupOutputSanitizer.stripTrailingTermEcho(_:terms:)` — it matches a trailing run of known vocab/lexicon terms (comma/space separated, optional end punctuation, anchored to end) and removes it, but only when it clearly looks like a dumped bias list: **≥3 terms, or a repeated term**. A sentence ending in one or two distinct names (e.g. "follow up with Bijal", "loop in Bijal, Meher") is preserved verbatim, including its punctuation. Wired into `FoundationModelsCleanup.clean` after `sanitizeOutput`, before `isDegenerate`. 6 new unit tests cover strip + preserve cases.

---

### F11 — HIGH — Recording re-triggers the system mic prompt on every key press

`AppState.startRecording()` guarded only on `dictationState == .idle, engineLoaded` — **not** on microphone permission. `recordingEngine.start()` accesses the audio input node (`RecordingEngine.swift:95-131`), which makes macOS show the mic TCC prompt whenever permission is `notDetermined`. So with the mic not yet granted, every activation-key press re-shows the mic window. Once the hotkey actually worked (post-F1/F4), pressing it ~4 times to test produced 4 mic windows. (`RecordingEngine.requestPermission()` exists but is dead code — never called — so the engine's input access is the sole trigger.) This is the real source of the "permission prompt N times" report — the microphone prompt, not Input Monitoring.

**Fixed:** `startRecording()` now checks `permissions.micStatus == .granted` (live, non-prompting) before touching the engine; if not granted it surfaces onboarding and returns, so the engine never re-prompts. `requestMicrophone()` also gained an in-flight guard so rapid wizard-button taps can't stack prompts.

---

### F12 — CRITICAL — Long dictations transcribe to junk: captured audio buffers were reordered

Long (>~30s) dictations produced "a couple of junk words"; short ones worked. **First hypothesis was wrong** (assumed Whisper's 30s window needed chunking). Verified empirically with the real large-v3-turbo model on a 60s clip, through the app's exact `wk.transcribe(audioArray:)` call: **182 words without chunking, 183 with** — transcription handles long audio fine; chunking is irrelevant.

Real cause: `AppState.recordingEngine(_:didReceiveBuffer:)` did `Task { @MainActor in self.audioBuffers.append(buffer) }` — **one independent task per captured buffer**. Independent tasks are not guaranteed to execute on the actor in creation order, so under load (long recording + the live-preview loop) buffers were appended **out of temporal order**, scrambling the audio handed to WhisperKit. Short clips have few buffers and usually stayed ordered — hence "short worked, long failed." The synchronous read at stop also dropped buffers whose task hadn't run.

**Fixed:** `BufferAccumulator` — a lock-protected, order-preserving store. The audio tap delivers buffers serially on its render thread, so appending under a lock keeps temporal order with no per-buffer actor hop. Also added: a `<3 words on >10s audio` guard in `WhisperKitTranscriber` that treats a degenerate long result as a failure (preserve + retry) rather than pasting junk. Chunking change reverted as unnecessary (proven). Verified by the 60s real-model test before/after; transcription path unchanged.

**Lesson:** shipped a chunking "fix" first without testing it — it would not have worked. The empirical 60s-clip test (run on demand) is the regression check for this.

---

### F13 — MEDIUM — Quiet recordings degrade transcription / "tail truncation"

User reported a long dictation "cut at the very end." Investigated with the user's *actual saved recording* (the persistence feature, F11): the audio was **fully captured** (283.6s, matching), so not a capture truncation. RMS profile across the whole file was uniformly ~0.007 with **peak 0.10** — i.e. recorded at ~10% of full scale (normal speech peaks 0.3–0.9). On the EXACT app path: raw → 506 words, conf 0.75, tail ending in a phantom "Thank you." (a known Whisper hallucination on trailing silence). Gain-normalizing to peak ≈ 0.95 → 502 words, conf 0.79, **phantom gone** — but the word count/content was unchanged, confirming the missing tail words simply aren't clearly present in the quiet audio. Root cause: **low microphone input gain**, which degrades accuracy and provokes trailing hallucinations.

**Fixed (code):** added gain normalization in `WhisperKitTranscriber.transcribe` — boost quiet clips (peak < 0.7) so peak ≈ 0.95, amplify-only, gain capped at 12×. Verified on the user's clip: conf 0.75 → 0.79 and the phantom phrase removed. **Not a full fix** — the primary lever is the user's mic input level (a 10%-level recording can't be fully recovered in software). Recommend surfacing a low-input-level warning in the HUD as a follow-up, and the user raising System Settings → Sound → Input level.

---

### F9 — LOW — Silent paste failure has no signal

When frozen, the transcript is saved to history (`AppState.swift:435`) but never pastes, and the user gets no feedback. Consider detecting paste failure (pasteboard `changeCount` unchanged / no target focus) and surfacing the existing `failWithRetry` affordance.

---

## Implementation status (2026-06-09)

Fixed on `feat/dictation-app`:
- **F1** — tap now pumped on a dedicated `com.justtalk.hotkey-tap` thread; main thread can no longer freeze input.
- **F2** — `PermissionsService` gains Input Monitoring (`CGPreflight/RequestListenEventAccess`); onboarding has a dedicated card; tap install gated on both Accessibility + Input Monitoring.
- **F3** — `startEventTap` verifies `tapIsEnabled` before reporting success; watchdog re-enables in place and rebuilds at most once / 10 s, only when permissions allow — no more re-prompt loop.
- **F4** — `keyIsDown` reset on every tap disable/re-enable; guarded by a lock for cross-thread access.
- **F5** — paste now waits for the target app to be frontmost (polled, ≤0.6 s); restore is guarded by `changeCount` so a newer user copy is never clobbered.
- **F6** — pass-through returns use `passUnretained`.
- **F7** — synthetic ⌘V events tagged via `CGEventSource.userData`; the tap ignores them.
- **F10** — cleanup term-biasing moved to system instructions; `CleanupOutputSanitizer` strips echoed "Known names and terms" blocks and rejects input-echo duplicates; 10 new unit tests, all green.

Also fixed:
- **F8/F9 (hang guard)** — added `withTimeout` (DictationCore/Timeout.swift). The cleanup LLM call is capped by `responseTimeout(wordCount:)` and falls back to rule-based on expiry (`FoundationModelsCleanup`); each STT attempt is capped at 60 s and a timeout fails fast into the existing retry-from-box affordance (`AppState.transcribeWithRetry`). This turns a hung on-device model — the worst silent-failure mode — into a graceful fallback instead of a HUD stuck on "Cleaning…"/"Transcribing…".
  - **Cleanup timeout was too tight for long dictations.** A flat 12 s budget made the LLM fall back to rule-based on a 5-min (~760-word) transcript — measured: it completes in **12.3 s**, missing the cutoff by 0.3 s. Now scaled to ~50 ms/word (15 s floor, 90 s ceiling) so long dictations get AI cleanup while short ones still fail fast on a real hang.
  - **Verified end-to-end** on a real 293 s clip through the production path (`WhisperKitTranscriber` large-v3-turbo → `TextCleanupFactory`): transcription 762 words (conf 0.86, 52 s); cleanup 765 words via `foundationModels`, no fallback (12.2 s). Both stages handle 5-min audio.

Re-examined / deferred:
- **F8 (main-actor offload)** — the heavy calls are `async` and *suspend* the main actor rather than blocking it; inference runs on the frameworks' own queues. With F1 the tap no longer depends on the main thread at all, so moving inference off `@MainActor` is no longer on the critical path. Revisit only if profiling shows main-thread stalls.
- **F9 (paste-success detection)** — explicit "did the paste land" detection still needs a design decision (avoiding false positives); lower priority now that F1 removes the freeze and the timeouts above remove the hang.

## Recommended fix order

1. **F1** — tap off the main thread. Kills the freeze and the F4 trigger. Highest impact.
2. **F2 + F3** — add Input Monitoring permission + stop the rebuild/prompt loop. Kills the prompt storm.
3. **F10** — stop the cleanup model echoing its prompt. Kills the duplicate-text + vocab-in-transcript paste. (Independent file; unit-testable via `swift test` on DictationCore.)
4. **F5** — confirm-paste-before-restore (or drop timed restore). Kills the wrong-content (image) paste.
5. **F4** — `keyIsDown` reset on tap disable/re-enable (small, do alongside F1).
6. **F6, F7** — leak fix + synthetic-event tagging (small, low-risk).
7. **F8, F9** — hardening, schedule after the above land.

F1–F4 + F6–F7 are tightly coupled to the tap lifecycle and land as one change to `HotkeyManager` + `PermissionsService` (+ onboarding/AppState permission wiring). F5 (`ClipboardPaster`) and F10 (`FoundationModelsCleanup`) are independent files and can land in parallel.
