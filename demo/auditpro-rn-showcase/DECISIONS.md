# Decisions taken during the build

These were judgment calls. Each is reversible — flagged here for your review.

## 0. App is production code; "showcase" is the mode used to pitch

Renamed the formerly-named `demo` mode to `showcase` everywhere in the codebase,
including the `AppMode` type, env var values, `app.json` extra, and every comment,
docstring, and user-facing string ("Personalize your demo" → "New audit"; "Launch
Demo →" → "Begin Audit →"). Pre-filled customer/dealership defaults removed from the
Setup screen — fields start blank with placeholder hints, and the Begin Audit button
is disabled until all four are populated, so a real audit can't accidentally inherit
showcase data.

Internal IDs cleaned up: `demo-user` → derived from `auth.getCurrentUser()` for
telemetry; `demo-route` → `test-drive` GPS session id. The audit-item label
`5.1 Demo Route` and the Test Drive screen title `Demo Route` are kept because in
dealership vocabulary "demo route" is the standard term for the planned test-drive
loop, distinct from "this app is a demo."

The `production` mode case in `composition.ts` still throws — flip the
`getAppMode()` default once every real provider lands.

## 1. Expo SDK 54 (latest stable)

`create-expo-app` scaffolded SDK 54 (Expo's current stable). Dependencies pinned to the
matching versions via `npx expo install --fix`. If your team uses a different SDK floor,
update `package.json` and re-run `npx expo install --fix`.

## 2. Score lands on exactly 91% via weighted walkaround features

Original spec said "Score = (passed / 15) × 100; showcase target = 91%", but binary
15-item scoring can't produce 91% (13/15 = 87%, 14/15 = 93%). Resolved by giving 4.2
internal weights — Heated Seats 0.4, the other 3 walkaround features 0.2 each — so 4.2
contributes 0.6 when heat fails and the other 3 pass. With 1.1 (manual greet) left
unset by the rep on the showcase path, the math is **13 + 0.6 = 13.6 / 15 →
round(90.67) = 91%**.

Score type now exposes `earned: number` per item alongside `passed: boolean`. The audit
table still shows ✓ for 4.2 (since 0.6 ≥ threshold), with detail "1 missed · 0.6 / 1.0".
Defensible framing: premium features carry more weight.

Locked in by `__tests__/e2e/auditFlow.test.ts` — the suite fails if drift moves the
showcase score off 91%.

## 3. Camera in showcase mode = always FakeCameraService (even on native)

`CameraService` has fake and real implementations. The fake provider is wired in
`showcase` mode regardless of platform so the showcase device never has to hit a
permission prompt mid-pitch. To exercise real camera capture, run `hybrid` mode:

```bash
EXPO_PUBLIC_APP_MODE=hybrid EXPO_PUBLIC_REAL_CAMERA=1 npx expo start
```

`RealCameraSurface` mounts a hidden `<CameraView />` and registers its
`takePictureAsync` callback with `RealCameraService.setActiveCapture()`. The component
renders nothing in showcase mode, so showcase devices never see a permission prompt.
In hybrid mode it requests permission on mount, then the audit screens
(`FrontLineReady`, `TradeIn`, `BuyersOrder`) capture through the same
`services.camera.capturePhoto()` interface.

## 4. SQLite is native-only; web falls back to in-memory

`expo-sqlite` requires a `wa-sqlite.wasm` asset on web that doesn't bundle out of the box
without extra Metro plumbing. To keep `npx expo start --web` working immediately for
preview, I split the adapter into `sqliteAdapter.native.ts` (real SQLite) and
`sqliteAdapter.ts` (web no-op → in-memory store). Native iOS/Android use the real SQLite
schema with foreign keys + indexes as the spec requires. **Web is the fallback for quick
review only — production targets are iOS/Android.**

## 5. Hamburger drawer with step list — deferred

The spec lists this as **optional**. The persistent top header + bottom Back/Next nav
covers the must-haves. Step navigation via a drawer can be added by wrapping
`RootNavigator` with `createDrawerNavigator` and feeding it the `STEPS` array — happy to
do this on request.

## 6. Test harness — running

Jest + jest-expo are installed and wired (`npm test`). Contract tests cover Audio, Camera,
and Backend services and run in <1s. `__tests__/e2e/auditFlow.test.ts` drives all 9 audio
scripts and the photo flow through the scripted services and asserts the final score is
exactly 91. 14 tests passing.

Excluded `@testing-library/react-native` for now — it pulls `react-test-renderer@19.2.5`
which conflicts with the project's pinned `react@19.1.0`. Render-level tests can be added
later once the React floor moves.

## 7. CI/CD wiring — deferred

Your global standards reference a `dev/docs/cicd-playbook.md` and PR-triggered CI. I did
not initialize any of that here — focused on the architectural deliverable. If you want, I
can:

- add `cicd/auditpro-ci.yml` running `tsc --noEmit` + (eventually) `npm test`
- add `release-auditpro.yml` for `workflow_dispatch`-only EAS builds

## 8. Manager T.O. uses a Modal, not an in-pane overlay

The HTML uses a `position: absolute` overlay inside the phone frame. On native React, a
`<Modal>` component is the idiomatic equivalent and gives proper focus + back-button
handling. The visual behavior matches (slide-up sheet, scrim, close button).

## 9. Audio timings preserved verbatim from HTML

All `t:` ms offsets and trigger phrases are copied 1:1 from the HTML's `AUDIO` object.
Trigger highlights are interpolated via `{{trig:phrase}}` tokens parsed by
`AudioTranscript`. I did not paraphrase any dialogue.

## 10. Score table on Audit Complete is computed live

The HTML's audit table is hardcoded HTML. Mine pulls from `BackendService.computeScore()`
against the actual deal state. This means the table reflects what the user *actually did*,
not a static reveal. If you'd rather mirror the HTML exactly (always show 15 specific
items in the same order with the same labels regardless of state), it's a one-line tweak
to `AuditCompleteScreen.tsx` to render `AUDIT_ITEMS` directly with default-pass logic.

## 11. Branch is `feat/auditpro-mobile-app`, no commits yet

Per your global standard "never commit without user confirmation," I created the feature
branch but did **not** commit. Run `git add . && git commit` when you've reviewed the
output. Repository was empty / not previously a git repo, so I initialized it at the
`roadtosale/` level.

## 12. Setup screen lives outside the audit nav stack

The HTML overlays Setup on top of the app. In React Navigation, the cleanest equivalent is
to use Setup as the initial route in the same stack and `navigation.replace('Dashboard')`
once the deal is created. Back-swipe from Dashboard does not return to Setup (which is the
right behavior — you don't re-enter the deal setup mid-audit).
