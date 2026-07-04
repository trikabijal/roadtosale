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
| Transcription (batch) | `SpeechTranscriber` (`load`, `transcribe`, `setVocabularyBias`) | WhisperKit **+ Apple SpeechAnalyzer** | Apple SpeechTranscriber / WhisperKit-lite | Argmax |
| Transcription (streaming) | `StreamingTranscriber` (`step`/`finish`, confirmed+hypothesis) — the live pill | WhisperKit `AudioStreamTranscriber` logic **+ Apple volatile/finalized** | Apple SpeechTranscriber | Argmax streaming |
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

### Streaming (live pill) — `StreamingTranscriber` + `StreamingAgreement` (PRD 0008)
- **T-STR-1 (Tier 1):** `StreamingAgreement.integrate` confirms all-but-`requiredUnconfirmed` segments; a later pass that revises the tail **never rewrites confirmed** (append-only); `lastConfirmedEnd` is monotonic; no duplication on a whole-buffer re-decode. *(COVERED — `StreamingAgreementTests`, 8 model-free tests.)*
- **T-STR-2 (Tier 2):** the WhisperKit sanitizer strips timestamp/special tokens (`<|5.90|>`) from segment text. *(COVERED — `StreamingAgreementTests.testSanitize…`.)*
- **T-STR-3 (Tier 2, model-gated):** `WhisperKitTranscriber.makeStreamingSession()` re-decodes a growing fixture and its confirmed text grows monotonically and matches the batch transcript's prefix; the 0.5 s re-decode throttle holds. *(PENDING — needs weights.)*
- **T-STR-4 (Tier 2, model-gated, macOS 26):** `AppleSpeechTranscriber` streaming session over a fixture emits finalized+volatile text; confirmed is append-only; `finish()` returns the full transcript. *(PENDING — needs Apple assets.)*
- **T-STR-5 (Tier 1):** streaming is **preview only** — with a `MockStreamingTranscriber`, assert the pasted output still comes from batch `transcribe()` and is byte-identical regardless of `streamingPillEnabled`. *(PENDING — `AppState` seam.)*

### Apple provider — `AppleSpeechTranscriber` (macOS 26)
- **T-APL-1 (Tier 2, model-gated):** `load()` on a supported locale (en-US) installs assets and readies; `transcribe()` of an English fixture returns the sentence. *(PENDING.)*
- **T-APL-2 (Tier 1):** `STTProvider.appleSpeech.isAvailable` is false below macOS 26 and the factory returns `UnavailableTranscriber` (graceful, no crash). *(PENDING — pure, easily automatable.)*
- **T-APL-3 (Tier 2):** `load()` on an unsupported locale (e.g. `gu-IN`) throws `providerUnavailable` so `AppState` falls back to WhisperKit. *(PENDING.)*
- **T-APL-4 (Tier 3):** `AppleAudioConverter` round-trips a 16 kHz mono buffer to Apple's format without loss/crash on format mismatch. *(PENDING — pure, automatable.)*

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

> The §8 coverage ledger and §9 backlog below reconcile these gaps against the **actual** test
> code in the tree; treat them as the authoritative, file-cited view of what is and isn't
> automated today.

---

## 8. Coverage ledger (plan vs. implementation)

> Snapshot of this plan against the **real** test files on branch `docs/refresh`. **No test
> code was written or modified** to produce this section — it only maps the plan to what
> already exists. Cross-refs: [architecture.md](architecture.md) · [flows.md](flows.md) ·
> [../../voice-engine/docs/model-contracts.md](../../voice-engine/docs/model-contracts.md).

**The test files (72 DictationCore test methods + 9 hotkey-config = 81 total):**

| File | Tests | Layer |
|---|---|---|
| `Shared/Tests/DictationCoreTests/CleanupTests.swift` | 30 | Cleanup contract, hallucination filter, cleanup packs, output sanitizer, factory |
| `Shared/Tests/DictationCoreTests/StreamingAgreementTests.swift` | 8 | **LocalAgreement-2 confirmed/hypothesis logic + token sanitizer (PRD 0008)** |
| `Shared/Tests/DictationCoreTests/StreamingDictationTests.swift` | 8 | Streaming session assembly order + tail-only post-stop (mock STT/cleanup) |
| `Shared/Tests/DictationCoreTests/FoundationModelsCleanupTests.swift` | 6 | FM cleanup sanitizer/degenerate guards |
| `Shared/Tests/DictationCoreTests/CapturedAudioStreamTests.swift` | 5 | Order-preserving capture buffer |
| `Shared/Tests/DictationCoreTests/RecordingStoreTests.swift` | 4 | Recording-store contract + audio bridge |
| `Shared/Tests/DictationCoreTests/TimeoutTests.swift` | 3 | Timeout race utility |
| `Shared/Tests/DictationCoreTests/PipelineTests.swift` | 2 | Contract-level dictate→clean (mock STT) |
| `Shared/Tests/DictationCoreTests/TelemetryRetentionTests.swift` | 1 | Telemetry purge/retention |
| `JustTalkTests/HotkeyConfigTests.swift` | 9 | Hotkey config + conflict (macOS adapter, config layer only) |

**The bottom line:** the **DictationCore contract layer is well covered** by fast, deterministic,
model-free unit tests. The **§2.5 end-to-end journey suite — the stated PRIMARY suite — is
almost entirely unautomated.** What exists are contract/component backstops (§3); the real
record→transcribe→clean→**paste/insert** journeys, every platform *adapter* (insertion,
activation tap, permissions, HUD), and all model-backed STT remain **manual / PENDING**. The
iOS keyboard is a diagnostic stub on this branch (see [architecture.md](architecture.md);
real impl in commit `1554186`), so no iOS adapter test can run.

### 8.1 Facade Coverage Ledger (reconciled with §1)

| Facade / boundary | Plan IDs | Status | Cited by |
|---|---|---|---|
| `TextCleanup` (rule-based) | T-CLN-1,2,5,6 | ✅ COVERED | `CleanupTests.swift › testRemovesFillersAndCapitalizes`, `…testObeysNewParagraphCommand`, `…testVocabForcesSpellingAfterCleanup`, `…testOffPassesThrough`, `…testLightDoesNotCollapseRepeats`, `…testCollapsesRepeatedWordsAtFull`, `TextCleanupFactoryTests › testRuleBasedProviderCleans` |
| `TextCleanup` (FoundationModels fallback) | T-CLN-3,4,7 | ⚠️ PARTIAL | sanitizer guards covered (`CleanupOutputSanitizerTests › testStripsEchoedKnownNamesAndTermsBlock`, `…testStripsEchoedTermsBlockCaseInsensitively`, `…isDegenerate*`); but the **live FM path** (timeout→`usedFallback=true`, T-CLN-4; long-transcript scaled timeout, T-CLN-7) is not exercised end-to-end — no `FoundationModelsCleanup` test |
| Cleanup packs (data-as-knowledge) | (§ architecture) | ✅ COVERED | `CleanupPackTests › testBundledPackResourceIsPresent`, `…testBundledPackLoads`, `…testBundledPackMatchesFallback` (drift guard), `…testRoadToSalePackLexiconLoads`, `…testRoadToSaleLexiconAppliesInCleanup`, `…testCatalogTermsMergeIntoLexicon` |
| `SpeechTranscriber` — hallucination/junk filter | (part of T-STT-5) | ✅ COVERED | `HallucinationFilterTests › testJunkPhraseDroppedOnShortClip`, `…testJunkPhraseDroppedOnLowConfidence`, `…testRealSpeechKept`, `…testEmptyIsHallucination` |
| `SpeechTranscriber` — real transcription | T-STT-1,2,3,4,6 | ⛔ PENDING | no test loads/transcribes real audio; `MockTranscriber` only stands in for the pipeline shape (`PipelineTests`). Ordered/complete output, long audio, vocab-bias efficacy, multilingual all unautomated |
| `StreamingTranscriber` / `StreamingAgreement` (PRD 0008) | T-STR-1,2 | ✅ COVERED | `StreamingAgreementTests` — confirmed/hypothesis split, tail-revision-safe, monotonic, no-dup, token sanitizer (8 model-free tests). Live WhisperKit/Apple streaming (T-STR-3,4) + the batch-output-unchanged seam (T-STR-5) remain ⛔ PENDING |
| `AppleSpeechTranscriber` (macOS 26) | T-APL-1..4 | ⛔ PENDING | no test; `isAvailable`-gating (T-APL-2) and `AppleAudioConverter` (T-APL-4) are pure and automatable now; live load/transcribe/locale-fallback need Apple assets on device |
| `RecordingStore` | T-PERSIST-1,2 | ✅ COVERED | `RecordingStoreTests › testSaveThenLoadRoundTripsSamples`, `…testRetainsOnlyNewestFive`, `…testLoadMissingThrows`, `…testBufferBridgeRoundTrip` |
| Telemetry store | T-TEL-1 (partial) | ⚠️ PARTIAL | retention/purge covered (`TelemetryRetentionTests › testPurgeRemovesRecordsOlderThanWindow`); **save→aggregate weekly stats** (T-TEL-1) and **shared App-Group cross-process** (T-TEL-2) are not tested |
| Capture (`RecordingEngine`) | T-CAP-1..4 | ⛔ PENDING | no `RecordingEngine` test (needs an audio harness / capture seam); only the neutral `[Float]`⇄buffer bridge is exercised (`RecordingStoreTests › testBufferBridgeRoundTrip`) |
| Insertion adapter (`ClipboardPaster` / `textDocumentProxy`) | T-INS-1..4 | ⛔ PENDING | no test; clipboard restore, focus targeting, self-trigger guard all manual |
| Activation adapter (`HotkeyManager` tap) | T-ACT-1..4 | ⚠️ PARTIAL | config/conflict layer covered (`HotkeyConfigTests › testFnMatchesViaFnModifier`, `…testRightModifierKeycodesAreDistinct`, `…testFunctionKeycodes`, `…testPersistenceRoundTrip`, `…testDefaultsToFnWhenUnset`, `…testIsFnOnlyForFn`, `…testDisplayNamesAreUnique`; `HotkeyConflictTests › testAppleFnUsageLabelCoversAllValues`, `…testOsClaimsFnIsFalseForNonFnKeys`). The **live `CGEventTap` behavior** — start/stop signal, no-freeze-under-load, no self-trigger, no duplicate sessions — is NOT tested |
| Permissions adapter (`PermissionsService`) | T-PERM-1..4 | ⛔ PENDING | no test; non-prompting reads, capture-gating, persistence-across-relaunch all manual |
| App orchestrator (`AppState` lifecycle) | T-FLOW-1..6 | ⛔ PENDING | no `AppState` test; happy path, never-lose-audio, timeout fallback, model-switch, per-context override, prewarm latency all manual. (`TimeoutTests` covers the **primitive** `withTimeout` race but not `AppState`'s use of it.) |
| Live feedback / HUD | T-HUD-1..3 | ⛔ PENDING | no `RecordingHUD` test |

### 8.2 End-to-end journey ledger (§2.5 — the PRIMARY suite)

| Journey | Tier | Status | Notes |
|---|---|---|---|
| **J1** First run / setup | 1 | ⛔ PENDING | no onboarding/permission-gating UI test |
| **J2** Dictate into another app | 1 (core) | ⚠️ PARTIAL | the **dictate→clean half** runs at contract level with mock STT (`PipelineTests › testDictateThenCleanProducesPolishedText`); the paste-into-another-app half (HUD lifecycle, clipboard restore) is NOT automated |
| **J3** Long paragraph | 2 | ⛔ PENDING | needs a long fixture + real/streamed STT |
| **J4** Hinglish | 3 | ⛔ PENDING | needs multilingual model on device |
| **J5** Too quiet | 2 | ⛔ PENDING | no HUD/low-input warning test |
| **J6** Recover a bad dictation | 1 | ⚠️ PARTIAL | the **store** round-trip that recovery relies on is covered (`RecordingStoreTests`) and the junk-rejection that triggers recovery is covered (`HallucinationFilterTests`); the **`AppState` retry/preserve orchestration** itself is not |
| **J7** Model hang doesn't brick the app | 1 | ⚠️ PARTIAL | the timeout **primitive** is proven to fire even when the op ignores cancellation (`TimeoutTests › testFiresEvenWhenOperationIgnoresCancellation`); the **app-level** "HUD never sticks / typing stays responsive" assertion is not automated |
| **J8** Change model in Settings | 2 | ⛔ PENDING | no settings/model-switch test |
| **J9** Mark a miss (correction) | 2 | ⛔ PENDING | `markCorrected` not exercised; correction-window flow manual |
| **J10** Per-context cleanup | 2 | ⛔ PENDING | `effectiveLevel(forBundleId:)` override not tested |
| **J11** Reuse a past transcript | 2 | ⛔ PENDING | history/copy flow not tested |
| **J12** Clear my data | 3 (pending feature) | ⛔ PENDING | retention purge primitive exists (`TelemetryRetentionTests`) but the user-facing "clear all data" control / audio wipe does not |
| **J13** Sustained real-world use | 1 (trust case) | ⛔ PENDING | needs a soak harness; entirely manual today |

### 8.3 iOS line — blocked by the stub

Per [architecture.md](architecture.md), `DictationKeyboard/*.swift` on this branch are
**diagnostic stubs** (a bare `UIInputViewController`; the real `KeyboardViewController` +
`KeyboardViewModel` + `KeyboardView` live in commit `1554186`). Consequently **every iOS
adapter assertion is ⛔ PENDING and currently un-runnable**: T-INS (iOS), T-TEL-2 (App-Group
cross-process), the keyboard-extension memory-ceiling check (§4), and Flow 5 in
[flows.md](flows.md). These cannot be automated until the real extension is restored — restoring
it is the prerequisite, not writing the tests.

### 8.4 Summary count

- **✅ COVERED (well):** cleanup (rule-based + packs + sanitizer + factory), hallucination
  filter, recording store + audio bridge, capture buffer ordering, telemetry retention, hotkey
  config/conflict, timeout primitive, **streaming `StreamingAgreement` (LocalAgreement-2 + token
  sanitizer)**, streaming-session assembly order — **~8 contract areas, 72 DictationCore tests.**
- **⚠️ PARTIAL:** FM cleanup fallback path, telemetry save/aggregate, activation (config only,
  not the live tap), and journeys J2/J6/J7 (half automated at contract level) — **4 areas.**
- **⛔ PENDING:** real STT, capture engine, insertion, permissions, `AppState` orchestrator,
  HUD, the entire iOS adapter line, and journeys J1, J3–J5, J8–J13 — **the PRIMARY end-to-end
  suite is essentially manual.**

---

## 9. Pending test backlog (no test code written)

Concrete tests to write, grouped by tier, **reconciled with §7 "Known gaps"** (the two iOS /
retention gaps are folded in below, not duplicated). **Nothing in this section has been
implemented — it is a to-do list only.** Each item names the plan ID/journey and the public
surface it would drive.

### Tier 1 — critical path (run every commit)
1. **`AppState` happy path (T-FLOW-1 / J2).** Inject a fixture-PCM capture seam + `MockTranscriber`; assert cleaned text reaches `ClipboardPaster` and the HUD lifecycle (listening→processing→inserted) fires. *Closes the un-automated half of J2.*
2. **Never-lose-audio (T-FLOW-2 / J6).** Force a garbled/empty transcription; assert audio is persisted to `RecordingStore`, no paste fires, Retry is offered, and `reTranscribeLastRecording()` recovers it.
3. **App-level timeout fallback (T-FLOW-3 / J7).** Inject a hung transcribe/clean into `AppState`; assert it recovers within the deadline (HUD doesn't stick) — layering the app behavior on the already-proven `withTimeout` primitive.
4. **Capture ordering + no-loss (T-CAP-1).** Drive `RecordingEngine` via a fixture/seam; assert total buffer duration ≈ input and temporal order is preserved (the long-recording-scramble regression).
5. **Real STT ordered/complete output (T-STT-1,2).** A model-gated test (skips without weights) that loads WhisperKit and asserts non-empty, head/tail-complete transcription of a short fixture.

### Tier 2 — integration (before every PR/release)
6. **FM cleanup fallback (T-CLN-4).** Force `FoundationModelsCleanup` to time out; assert `usedFallback == true`, full text still returned, paste never blocked.
7. **Telemetry save→weekly stats (T-TEL-1).** Save records via `TelemetryStore`; assert `fetchWeeklyStats`/`fetchUsageTotals` aggregate them (and `markCorrected` flips `correctionRate`) — extends `TelemetryRetentionTests`.
8. **Correction window (J9).** Drive `markLastTranscriptCorrected()`; assert the row is flagged even when Just Talk wasn't focused.
9. **Per-context cleanup override (T-FLOW-5 / J10).** Set cleanup Off for a bundle id; assert `effectiveLevel(forBundleId:)` yields raw vs cleaned by record-start app.
10. **Clipboard restore + focus targeting (T-INS-2,3).** Drive `ClipboardPaster` against a scripted target; assert prior clipboard restored, newer user copy not clobbered, insertion hits the correct target.
11. **Live `HotkeyManager` tap (T-ACT-1,2).** Harness the event tap; assert start/stop signal, toggle vs hold, and no global-input freeze under load (config layer is already covered by `HotkeyConfigTests`).
12. **Permissions gating (T-PERM-1,2,3).** Assert status reads are non-prompting, capture won't start without mic, onboarding gates correctly.
13. **Low-input warning (T-CAP-3,4 / T-HUD-2 / J5).** Feed a low-gain fixture; assert the HUD "too quiet" warning toggles with level.
14. **Vocab-bias efficacy (T-STT-4, model-gated).** Assert `setVocabularyBias` measurably improves exact-spelling rate on a clip.
14a. **Apple availability gating (T-APL-2, pure — do now).** Assert `STTProvider.appleSpeech.isAvailable` matches OS and the factory returns `UnavailableTranscriber` below macOS 26 (no crash). Model-free.
14b. **`AppleAudioConverter` round-trip (T-APL-4, pure — do now).** 16 kHz mono → Apple format → non-empty buffer; handles a format mismatch without crashing.
14c. **Batch-output-unchanged seam (T-STR-5, `MockStreamingTranscriber`).** With streaming on vs off, the pasted text is byte-identical (streaming is preview only).
14d. **Live streaming sessions (T-STR-3 WhisperKit, T-STR-4 Apple, model-gated).** Growing fixture → confirmed text grows monotonically, matches batch prefix; Apple emits finalized+volatile; `finish()` returns the full transcript.
14e. **Apple locale fallback (T-APL-1,3, model-gated, macOS 26).** en-US loads+transcribes; an unsupported locale throws `providerUnavailable` so `AppState` falls back to WhisperKit.

### Tier 3 — edge / stress / quality (weekly / major release)
15. **Long-audio + cleanup timeout (T-STT-3, T-CLN-7).** ~5-min fixture; assert full transcription and that cleanup either completes within the scaled timeout or falls back without collapse/balloon.
16. **Multilingual (T-STT-6 / J4, model-gated).** Hinglish/Gujarati fixture transcribes sensibly; English-only tiers not offered as multilingual.
17. **Clear-all-data (T-TEL-3 / J12).** *Blocked on §7 retention feature.* When the control lands, assert transcripts + saved recordings are wiped.
18. **HUD off-screen reset (T-HUD-3).** A stale multi-monitor position snaps back to a visible default.
19. **Sustained soak (J13).** Many back-to-back fixture dictations under load; every one inserts, keyboard never freezes.

### iOS adapter line (blocked — §7, §8.3)
*Prerequisite: restore the real keyboard extension from commit `1554186`; these are un-runnable against the current stub.*
20. **iOS insert via `textDocumentProxy` (T-INS iOS / Flow 5).** XCUITest the keyboard committing cleaned text in a host app.
21. **App-Group cross-process telemetry (T-TEL-2).** A record written by the host app is readable by the extension and vice-versa.
22. **Keyboard-extension memory ceiling (§4).** The extension loads under its ~50 MB budget on the `tinyEn` tier without the heavy STT/LLM deps faulting it.

**Backlog count:** **27 pending tests** — 5 Tier 1, 14 Tier 2 (incl. streaming/Apple items 14a–14e), 5 Tier 3, 3 iOS-blocked. Highest-value quick wins that need no model: **14a, 14b, 14c** (Apple gating, converter, batch-output-unchanged seam) — all pure/mockable.
Implementing #1–#5 would convert the Tier-1 journeys (J2, J6, J7) and the capture/STT
contracts from ⚠️/⛔ to ✅ and is the highest-value next step. **No test code was added or
changed in this revision.**
