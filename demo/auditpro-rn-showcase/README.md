# AuditPro

Mobile audit app for dealership sales-process compliance. Production code, React Native + Expo.

The integration layer is provider-driven: every external dependency (audio, camera, CV, GPS,
auth, backend, telemetry) sits behind an interface, and the app picks an implementation set
at launch. The same screens, state, and scoring run regardless of which set is chosen.

## App modes

| Mode | When to use | Provider set |
| --- | --- | --- |
| `production` | Real audits in the field | Real providers everywhere — currently throws on launch until the real audio/CV/GPS/auth/telemetry implementations land |
| `showcase` | Sales pitches and pilot walkthroughs where there's no live customer | Scripted/local providers everywhere; runs without a backend, microphone, or camera permission prompt |
| `hybrid` | Local development | Pick-and-mix via env vars (`EXPO_PUBLIC_REAL_CAMERA=1`, etc.) |

Mode is selected via `EXPO_PUBLIC_APP_MODE` (or the `extra.appMode` key in `app.json`) and
resolved in `src/services/composition.ts`. **Default is `showcase`** until production
providers are complete; flip the default in `getAppMode()` once they are.

Screens never branch on mode — they call services through a React context and the chosen
provider does the work.

## Stack

- React Native (Expo SDK 54), TypeScript strict
- React Navigation (native stack)
- Zustand for state, with write-through to `BackendService`
- Expo Camera, Expo SQLite (native only), Expo Location (planned)
- react-native-reanimated for waveform / progress / glow animations
- @expo-google-fonts/syne for the Syne display font

## Running

```bash
npm install
npx expo start
```

Then press `i` for iOS Simulator, `a` for Android emulator, or `w` for web. To load on a
real phone, install Expo Go and scan the QR code.

```bash
EXPO_PUBLIC_APP_MODE=showcase   npx expo start    # default — scripted everywhere
EXPO_PUBLIC_APP_MODE=hybrid     npx expo start    # mix providers via env vars
EXPO_PUBLIC_REAL_CAMERA=1       npx expo start    # in hybrid mode, swap camera to real Expo Camera
```

`production` mode currently throws at startup until the remaining real providers land.

## Architecture

```
App.tsx
 ├── buildServices(mode) — composition root in src/services/composition.ts
 │    returns { audio, camera, cv, gps, backend, auth, telemetry }
 ├── ServicesProvider injects them into context
 └── Zustand store binds to BackendService at boot — every action writes through

Screens consume services via useServices() and never know which provider is wired.
The scripted-mode audio dialogue lives in src/services/audio/audioScript.ts and is
consumed only by ScriptedAudioService — when the real audio service ships, the script
becomes test fixture material, not runtime data.
```

### Service contracts

| Interface | Scripted/local provider | Real provider |
| --- | --- | --- |
| `AudioService` | `ScriptedAudioService` (timed events) | Deepgram/Whisper streaming — TODO |
| `CameraService` | `FakeCameraService` | `RealCameraService` (Expo Camera) — wired via `RealCameraSurface` |
| `CVService` | `ScriptedCVService` | Claude Vision / GPT-4V — TODO |
| `GPSService` | `ScriptedGPSService` | expo-location + route matching — TODO |
| `BackendService` | `LocalBackendService` (SQLite, real) | Cloud REST/GraphQL — TODO |
| `AuthService` | `LocalAuthService` (single user) | Trika SSO — TODO |
| `TelemetryService` | `NoopTelemetryService` | PostHog/Mixpanel — TODO |

### Folder map

```
auditpro/
├── App.tsx                        — composition + provider tree, root ErrorBoundary
├── src/
│   ├── theme/                     — color, font, radii, spacing tokens
│   ├── services/
│   │   ├── composition.ts         — mode → service set
│   │   ├── context.tsx            — ServicesProvider + useServices()
│   │   ├── audio/                 — AudioService + ScriptedAudioService + audioScript
│   │   ├── camera/                — FakeCameraService + RealCameraService
│   │   ├── cv/                    — ScriptedCVService
│   │   ├── gps/                   — ScriptedGPSService
│   │   ├── backend/               — LocalBackendService (+ sqliteAdapter platform split)
│   │   ├── auth/                  — LocalAuthService
│   │   └── telemetry/             — NoopTelemetryService
│   ├── store/                     — Zustand deal store (writes through to BackendService)
│   ├── data/                      — STEPS, AUDIT_ITEMS
│   ├── components/                — AudioWidget, ScriptCard, YNCard, FeatureItem, PhotoSlot,
│   │                                ErrorBoundary, RealCameraSurface, ...
│   ├── screens/                   — 13 screens (Setup + 12 audit screens)
│   ├── navigation/                — Native stack
│   └── hooks/                     — useAudioScript
└── __tests__/e2e/                  — auditFlow.test.ts (showcase end-to-end assertion)
```

### Composition root

`src/services/composition.ts` — single switch keyed on `AppMode`. Adding a new service means:

1. Add its interface in `src/services/<name>/<Name>Service.ts`.
2. Add a scripted/local provider next to it.
3. Add it to the `Services` type and the `buildServices` cases.

That's it — UI stays untouched.

## Audit flow

Setup (dealership / rep / customer / vehicle) → Dashboard → Greet → Discovery →
Feature Match → Front-Line Ready → Walkaround → Test Drive → Trade-In →
First Pencil (+ Manager T.O. floating) → Buyer's Order → F&I Handoff → Audit Complete.
Score is computed against the 15-line `AUDIT_ITEMS` ledger; `4.2 Features` is weighted
internally (Heated Seats 0.4, others 0.2 each) so the headline figure can carry partial
credit.

## Testing

```bash
npm test
```

- **Contract tests** under each service folder. `audioServiceContract`,
  `cameraServiceContract`, and `backendServiceContract` are the suites every implementation
  must satisfy — when a real provider lands, register it next to the scripted one and the
  same suite runs against both. This is the mechanism that keeps the scripted and real
  providers aligned.
- **E2E** in `__tests__/e2e/auditFlow.test.ts` drives all 9 audio scripts and the photo
  flow through the scripted services and asserts the final score is exactly 91 — the
  number on the Audit Complete screen — with 1.1 (manual greet) the only fully-failed
  item. Wire CI to run this on every PR.
