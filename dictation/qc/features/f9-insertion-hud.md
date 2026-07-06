# f9 — Insertion & HUD

**Facades:** `ClipboardPaster` (write + synthetic ⌘V + restore); `RecordingHUD` (floating pill).

## E2E facade ledger

| Facade | Behavior | Expected | Tier | Status |
|---|---|---|---|---|
| `ClipboardPaster.writeAndPaste` | insert into focused field | text at cursor of target app | 2 | ⛔ scripted target app |
| `ClipboardPaster` | prior clipboard restored; newer copy not clobbered (changeCount) | | 2 | ⛔ |
| `ClipboardPaster` | target never frontmost | refuses, leaves text on clipboard, surfaces message | 2 | ⛔ |
| `ClipboardPaster` | synthetic ⌘V not self-triggering hotkey | tagged event ignored | 2 | ⛔ |
| `RecordingHUD` | visible while recording, clears on done | | 2 | ⛔ SwiftUI |
| `RecordingHUD` | low-input warning toggles with level | | 2 | ⛔ |
| `RecordingHUD` | pill grows gradually (reveal driver), no chatter | | 3 | ⛔ visual, user-verified |
| `RecordingHUD` | off-screen saved position resets to visible default | | 3 | ⛔ multi-monitor |

## Unit inventory: ⛔ none automatable today — both are AppKit/SwiftUI adapters needing a scripted target / view harness. The reveal-driver math + attributed-text logic could be extracted to a pure helper to unit-test (future).

## Deferred (Tier 2/3, manual): all paste + HUD behaviors — need a scripted target app (XCUITest) / view harness. User-verified on hardware for the pill feel.

## Checklist grade
- **content-vs-declared / clobber** — clipboard restore guarded by `changeCount` (F5) is real code but ⛔ untested (needs a target-app harness).
