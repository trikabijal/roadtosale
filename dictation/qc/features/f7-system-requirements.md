# f7 — System requirements gate

**Facades:** `SystemPreflight.check()` → `SystemCapabilities` (DictationCore); `RequirementsView`
(JustTalk adapter). **Why it matters:** the promise is *never lose a user to a broken onboarding* —
an unsupported Mac must be told clearly (update / not powerful enough), not left with a stuck model
download. Requirements are vendor-sourced (Argmax + Apple): **Apple Silicon (M1+) + 8 GB RAM +
macOS 14+**; Apple SpeechAnalyzer recommended on macOS 26, else WhisperKit Large Turbo.

## E2E facade ledger

| Facade | Behavior | Inputs/State | Expected | Tier | Notes |
|---|---|---|---|---|---|
| `SystemPreflight.check()` | capable Mac | Apple Silicon, macOS 26, 16 GB, 200 GB free | `canRun`; recommend `.appleSpeech` | 1 | this Mac |
| `SystemPreflight.check()` | Apple Silicon, older OS | AS, macOS 14, 16 GB | `canRun`; recommend `.whisperKit` | 1 | multilingual path |
| `SystemPreflight.check()` | Intel | not AS | blocked `.notAppleSilicon` | 1 | vendor floor |
| `SystemPreflight.check()` | OS too old | macOS 13 | blocked `.osBelow(14,…)` | 1 | |
| `SystemPreflight.check()` | low RAM | 4 GB | blocked `.lowRAM(8,4)` | 1 | model can't fit |
| `SystemPreflight.check()` | low disk | 1 GB free | blocked `.lowDisk(2,1)` | 1 | model download |
| `SystemPreflight.check()` | multiple blockers | Intel + 4 GB | both blockers listed | 1 | |
| `SystemPreflight.check()` | 8 GB exactly (Neo) | AS, 8 GB | `canRun` (margin) | 1 | cheapest Mac passes |
| `RequirementsView` | shows blockers | `!canRun` | one message per blocker + Quit | 2 | adapter, manual |
| `AppState.setup()` | gates on preflight | `!canRun` | requirements window shown; onboarding NOT started | 2 | orchestrator, manual |

## Unit module inventory

| Module | Public interface | Priority | Notes |
|---|---|---|---|
| `SystemCapabilities` / `SystemPreflight` | `check()`, `canRun`, `recommendedProvider`, `isAppleSilicon()`, `physicalRAMGB()`, `freeDiskGB()` | **Critical** | new; the gate. `check()` reads the real machine → **needs a pure `decide(...)` seam for deterministic tests** |

### Testability refactor (required)
`check()` mixes environment reads (arch/OS/RAM/disk) with the decision. Extract a **pure**
`SystemCapabilities.decide(isAppleSilicon:osMajor:freeDiskGB:ramGB:appleAvailable:) -> SystemCapabilities`;
`check()` becomes "read the machine, then `decide(...)`". Unit-test `decide` with synthetic inputs.

### Unit scenarios — `decide(...)`

| Scenario | Input | Expected |
|---|---|---|
| capable + macOS 26 | AS, os 26, 16 GB, 200 GB, appleAvail true | `blockers == []`, recommend `.appleSpeech` |
| capable + macOS 14 | AS, os 14, 16 GB, 200 GB, appleAvail false | `[]`, recommend `.whisperKit` |
| Intel | not AS | `[.notAppleSilicon]`, recommend `.whisperKit` |
| OS 13 | os 13 | contains `.osBelow(minMajor:14,…)` |
| RAM 4 GB | ram 4 | contains `.lowRAM(neededGB:8, actualGB:4)` |
| RAM 8 GB (Neo) | ram 8 | no `.lowRAM` (margin lets 8 pass) |
| RAM 7 GB | ram 7 | contains `.lowRAM` |
| disk 1 GB | free 1 | contains `.lowDisk(neededGB:2, freeGB:1)` |
| disk 2 GB | free 2 | no `.lowDisk` |
| Intel + 4 GB | not AS, ram 4 | both `.notAppleSilicon` and `.lowRAM` |
| appleAvail but Intel | not AS, appleAvail true | recommend `.whisperKit` (AS required for Apple) |

## Deferred

| Item | Reason | Tier | Tracking |
|---|---|---|---|
| `RequirementsView` renders correct messages | SwiftUI view, manual | 2 | manual |
| `AppState.setup()` blocks onboarding when `!canRun` | orchestrator seam needed | 2 | backlog |

## Checklist grade
- **error-surfacing** ✅ — each blocker is a distinct, user-readable reason (no masking onto one message).
- **boundary values** ✅ — 8 GB exact, 2 GB exact, OS 14 exact all pinned.
