# Just Talk — preventive test-plan checklist (project override)

Distilled from this project's own root-cause analyses. Every new/revised feature plan is **graded
against this list** (per `~/.claude/workflows/test-plan-lifecycle.md`): a class with a plausible
surface in the feature but no scenario is a **gap**. A bug found later that isn't here gets **added**,
then swept across features. Generic classes live in `~/.claude/workflows/`; this is the Just Talk
override, referenced by [README.md](README.md).

## The classes (each with its originating RCA)

| # | Class → rule | Origin (RCA) | Where it must have a scenario |
|---|---|---|---|
| C1 | **Ordered async writes** — data appended from a background thread must preserve arrival order; test concurrent appends all land in order. | F12: per-buffer `Task` reordered capture → long-recording garbage | f5 (CapturedAudioStream) ✅ |
| C2 | **Cleanup never drops content** — a cleanup/transform that shortens text must never delete a meaningful leading/trailing clause; guard + fall back. | cleanup content-drop ("So the best data would be" deleted) | f4 (content-drop guard) ✅ |
| C3 | **Model output never echoes its prompt** — LLM cleanup must not paste back injected vocab/instructions; strip + reject. | F10: vocab-echo onto clipboard | f4 (sanitizer echo strip) ✅ |
| C4 | **Preview never corrupts output** — a live/preview path (streaming pill) must never change the authoritative pasted text; assert output byte-identical with preview on/off. | streaming pill design | f2 (batch-output-unchanged seam) ⛔ add |
| C5 | **Append-only stable prefix** — a confirmed/committed prefix is never rewritten by a later pass; test tail-revision leaves confirmed intact. | LocalAgreement design | f2 (StreamingAgreement) ✅ |
| C6 | **Timeout fires even when the op ignores cancellation** — a hung model call must not hang the caller; the timeout races independently. | F8/F9 hang guard | f1 (`Timeout`) ✅ |
| C7 | **Never lose audio** — a failed/garbled transcription preserves the audio + offers retry; never paste junk, never discard. | F12 / durable-capture | f1 (session `incomplete`) ✅ unit; app-level ⛔ |
| C8 | **Requirements gate, not broken onboarding** — an unsupported machine gets a clear per-blocker message, never a stuck download; boundary values (RAM 8, disk 2, OS 14) pinned. | requirements debate | f7 ✅ (this pass) |
| C9 | **Provider gating is consistent** — a provider's availability is enforced at BOTH `isAvailable` and the factory (no path builds an unavailable engine). | Apple provider add | f3 ✅ (this pass) |
| C10 | **Suppression without over-permission** — the hotkey must suppress its key (Fn picker) on the minimum permission (Accessibility, not Input Monitoring); assert `canInstallTap` gates on Accessibility only. | Wispr parity / Input-Monitoring removal | f8 ⛔ (needs AX seam) |
| C11 | **Clipboard restore can't clobber a newer copy** — restore is guarded by `changeCount`; a back-to-back dictation or user copy is never overwritten. | F5 clipboard race | f9 ⛔ (needs target-app harness) |
| C12 | **Rename doesn't break the build/test target** — a product rename keeps the module name + TEST_HOST valid; app tests must still resolve `@testable import`. | "Just Talk" rename broke TEST_HOST + module name | build/test config ✅ (fixed; app tests pass) |

## How to use
1. When writing a feature plan, scan this table for classes with a surface in the feature.
2. Each such class must map to a ledger/unit scenario, or be an explicit deferral with a reason.
3. When a new bug's RCA reveals a class not here, **add a row** + sweep the other features for it.
