# f8 — Activation, hotkey & permissions

**Facades:** `HotkeyManager` (CGEventTap), `HotkeyConfig`, `HotkeyConflict`; `PermissionsService`;
onboarding. **Permission footprint (verified vs Wispr):** Mic + Accessibility only — the active
`.defaultTap` needs no Input Monitoring, and suppresses the Fn emoji picker.

## E2E facade ledger

| Facade | Behavior | Expected | Tier | Status |
|---|---|---|---|---|
| `HotkeyConfig` | persistence round-trip; default Fn; Fn-only flag | | 1 | ✅ (9 `HotkeyConfigTests`) |
| `HotkeyConfig.match` | Fn via fn-modifier; right-mod keycodes distinct; F-keys | | 1 | ✅ |
| `HotkeyConfig` | display names unique | | 1 | ✅ |
| `HotkeyConflict` | appleFnUsage label covers all; osClaimsFn false for non-Fn | | 1 | ✅ |
| `HotkeyManager.canInstallTap` | Accessibility only (not Input Monitoring) | true when Accessibility; Input Monitoring ignored | 1 | ✅ pure seam + `HotkeyPermissionTests` (C10) |
| `HotkeyManager` live tap | start/stop signal; Fn suppressed; no self-trigger; no dup sessions | | 3 | ⛔ hardware (user-verified) |
| `HotkeyManager` | no global-input freeze under load | | 3 | ⛔ hardware (user-verified) |
| `PermissionsService` | non-prompting status reads; request prompts once | | 2 | ⛔ TCC — manual |
| onboarding | Done disabled until key pressed; gates on Mic+Accessibility | | 2 | ⛔ UI — manual |

## Unit inventory: ✅ `HotkeyConfigTests` (9 — config/conflict layer). ⛔ live `CGEventTap`, `PermissionsService` (TCC), onboarding UI.

## Deferred (Tier 3 / manual): live tap behavior, permission gating, onboarding flow — hardware + TCC, user-verified.

## Checklist grade
- **authorization / scope** — permission gating is real but ⛔ (TCC can't be unit-tested); config-layer conflict detection ✅.
- **consistent gating** ✅ — `canInstallTap` gates on Accessibility everywhere (start + watchdog).
