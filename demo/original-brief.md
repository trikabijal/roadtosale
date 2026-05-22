# Build prompt — AuditPro mobile app (React Native + Expo)

> **Use this prompt with Claude Code or Cursor.** Attach `AuditPro_Demo_v4.html` alongside this prompt — the agent should read the HTML directly for visual fidelity, scripted audio timing, and interaction patterns. The HTML is the source of truth for visual design and demo behavior; this document is the architectural spec.

---

## 1. Project goal

Build the **production scaffolding** for **AuditPro**, a dealership sales-process auditing mobile app. From day one, the app must support two modes that share the entire codebase except for the integration layer:

- **Demo mode** — bulletproof, runs without any backend, replicates the scripted experience in `AuditPro_Demo_v4.html`. Used for sales calls, prospect evaluations, and as the always-available fallback.
- **Production mode** — real microphone, real STT, real CV, real GPS, real backend. Comes online incrementally as real provider implementations land.

Both modes run through the same UI, the same state layer, and the same screen flow. They differ **only** at integration boundaries (audio, camera, CV, GPS, auth, backend, telemetry). The choice of mode is made at app launch and is never branched on inside business logic or UI components.

This is not a throwaway demo. Every line of code written here is production code.

---

## 2. Stack

- **React Native** with **Expo** (managed workflow), latest stable SDK
- **TypeScript**, strict mode (no `any`)
- **React Navigation** native stack (or **expo-router**)
- **Zustand** for app-wide state, with persistence middleware
- **Expo Camera** for photo capture
- **react-native-reanimated** for waveform and progress bar animations
- **@expo-google-fonts/syne** for the Syne display font
- **AsyncStorage** for lightweight persistence
- **expo-sqlite** for the local backend implementation
- **No cloud backend yet** — the backend interface has a real local-SQLite implementation; cloud comes later

Bundle id: `ai.trika.auditpro`. Display name: `AuditPro`.

---

## 3. Architecture: services and providers

This is the most important section. Get this right and everything else follows.

### Principle

Define a **service interface** at each integration boundary. Provide **two implementations** per service: one scripted (demo), one real. The UI and state layers depend only on the interfaces. A **composition root** at app launch wires up the chosen provider set.

UI components and screens **never** branch on mode. They never know if they're in demo mode. They just call the injected service.

### Services

| Interface | Demo provider | Real provider |
| --- | --- | --- |
| `AudioService` | `ScriptedAudioService` — timed events from `audioScript.ts` | `// TODO(real-audio)` — Deepgram/Whisper streaming + keyword extraction |
| `CameraService` | `FakeCameraService` — flash + emoji placeholder | `RealCameraService` — Expo Camera capture |
| `CVService` | `ScriptedCVService` — pre-canned signature/order extraction | `// TODO(real-cv)` — Claude Vision / GPT-4V |
| `GPSService` | `ScriptedGPSService` — fake route progression | `// TODO(real-gps)` — expo-location + dealership route matching |
| `BackendService` | `LocalBackendService` — SQLite-backed (real local impl) | `// TODO(cloud-backend)` — REST/GraphQL against Trika cloud |
| `AuthService` | `LocalAuthService` — single-user, no auth | `// TODO(real-auth)` — Trika SSO |
| `TelemetryService` | `NoopTelemetryService` — drops events | `// TODO(real-telemetry)` — PostHog/Mixpanel |

**Note:** `CameraService` and `BackendService` get **real** implementations now. The local backend is genuinely real — it's just local-only. The camera service has both fake (for demo mode without permission prompts) and real (for production capture).

### Composition root

```typescript
// src/services/composition.ts

type AppMode = 'demo' | 'production' | 'hybrid'

type Services = {
  audio: AudioService
  camera: CameraService
  cv: CVService
  gps: GPSService
  backend: BackendService
  auth: AuthService
  telemetry: TelemetryService
}

export function buildServices(mode: AppMode): Services {
  switch (mode) {
    case 'demo':
      return {
        audio: new ScriptedAudioService(),
        camera: new FakeCameraService(),
        cv: new ScriptedCVService(),
        gps: new ScriptedGPSService(),
        backend: new LocalBackendService({ tenant: 'demo' }),
        auth: new LocalAuthService({ user: 'demo-user' }),
        telemetry: new NoopTelemetryService(),
      }
    case 'production':
      throw new Error('Production mode not yet wired — real providers in progress')
    case 'hybrid':
      // For development: any combination via env vars / dev menu
      return buildHybridServices()
  }
}
```

Mode is selected via env var (`EXPO_PUBLIC_APP_MODE`) at build time, with a dev-menu override for runtime switching during development. Production builds default to `production`; demo builds default to `demo`.

### Service injection

Use React Context to inject services into the tree:

```typescript
// src/services/context.tsx
const ServicesContext = createContext<Services | null>(null)
export const ServicesProvider = ({ services, children }) => (
  <ServicesContext.Provider value={services}>{children}</ServicesContext.Provider>
)
export const useServices = () => {
  const ctx = useContext(ServicesContext)
  if (!ctx) throw new Error('ServicesProvider missing')
  return ctx
}
```

Screens and components consume services via `useServices()`. They never import concrete classes.

### Contract tests

For each service, write a contract test suite that **both** providers must pass:

```typescript
// src/services/audio/AudioService.contract.test.ts
function audioServiceContract(name: string, factory: () => AudioService) {
  describe(`AudioService contract: ${name}`, () => {
    it('emits onStart when listening begins', async () => { ... })
    it('emits onTranscript with text', async () => { ... })
    it('stops cleanly', async () => { ... })
  })
}

audioServiceContract('Scripted', () => new ScriptedAudioService())
// audioServiceContract('Real', () => new RealAudioService()) — uncomment when real lands
```

This is the mechanism that prevents demo drift. A new field on the real provider must also exist on the scripted one, or the contract test fails.

---

## 4. Service interfaces

### `AudioService`

```typescript
type TranscriptEvent = {
  text: string
  trigger?: string  // highlighted phrase
  confidence?: number
  speaker?: 'salesperson' | 'customer' | 'manager' | 'unknown'
}

type DetectionEvent = {
  stepId: string  // '1.2', '3.1', '4.1', etc.
  type: 'auto-confirm' | 'feature-pass' | 'feature-fail' | 'voice-detected' | 'note-extracted'
  payload?: Record<string, unknown>
}

interface AudioService {
  start(context: { screenId: string; stepId: string }): Promise<void>
  stop(): Promise<void>
  onTranscript(handler: (event: TranscriptEvent) => void): () => void
  onDetection(handler: (event: DetectionEvent) => void): () => void
}
```

`ScriptedAudioService` reads from `src/services/audio/audioScript.ts` (ported from the HTML's `AUDIO` object) and fires transcript + detection events on the scripted timeline.

### `CameraService`

```typescript
interface CameraService {
  capturePhoto(slotId: string): Promise<{ uri: string; metadata: PhotoMetadata }>
  isPermissionGranted(): Promise<boolean>
  requestPermission(): Promise<boolean>
}
```

`FakeCameraService` returns a synthetic URI (data URI placeholder gradient). `RealCameraService` opens Expo Camera.

### `CVService`

```typescript
interface CVService {
  extractBuyersOrder(imageUri: string): Promise<{
    signaturePresent: boolean
    lineItems: { label: string; value: string }[]
    capturedAt: Date
  }>
  detectVehicleCondition(imageUri: string): Promise<{
    defects: string[]
    confidence: number
  }>
}
```

`ScriptedCVService` returns hardcoded results matching the HTML's buyer's-order reveal.

### `BackendService`

```typescript
interface BackendService {
  // Deals
  createDeal(setup: DealSetup): Promise<Deal>
  updateDeal(id: string, patch: Partial<Deal>): Promise<Deal>
  getDeal(id: string): Promise<Deal>
  listDeals(filter?: DealFilter): Promise<Deal[]>

  // Audit events
  logEvent(event: AuditEvent): Promise<void>
  getAuditTrail(dealId: string): Promise<AuditEvent[]>

  // Score
  computeScore(dealId: string): Promise<AuditScore>
}
```

`LocalBackendService` uses `expo-sqlite` with a real schema (deals, events, photos, audits tables; foreign keys; indexes on dealership_id and created_at). This is fully functional, not a stub.

### Other interfaces

`GPSService`, `AuthService`, `TelemetryService` — minimal contracts now, expand as features need them.

---

## 5. Design system (extract from HTML — match exactly)

### Colors
| Token | Value |
| --- | --- |
| `primary` | `#1D52E8` |
| `primaryDark` | `#1239B8` |
| `primaryLight` | `#EEF3FF` |
| `success` | `#15A354` |
| `successLight` | `#EDFAF4` |
| `error` | `#DC2525` |
| `errorLight` | `#FEF0F0` |
| `screenBg` | `#f1f1f6` |
| `cardBg` | `#ffffff` |
| `textPrimary` | `#000000` |
| `textMuted` | `#636370` |
| `textSubtle` | `#888888` |
| `setupBg` | `#08080F` |

### Typography
- **Display:** Syne 700/800 — headlines, brand, score
- **Body:** System default
- Sizes: 28 (setup heading), 18 (screen title), 14 (brand), 13 (body), 12 (sub), 10–11 (labels), 9 (uppercase eyebrows)

### Spacing & radius
- Screen padding: 13–16
- Card radius: 10–13
- Button radius: 11–13
- Vertical gap: 8

### Animations (Reanimated)
- Audio waveform: 8 vertical bars, staggered scaleY, ~1s loop, opacity 0.35 → 0.9
- Audio dot: red pulse, 1.2s loop
- Progress bar: 450ms `cubic-bezier(.4,0,.2,1)`
- Checkbox auto-tick: green box-shadow expand-and-fade, ~500ms

---

## 6. Screens

### Screen list (in order)

| # | Screen | Key elements |
| --- | --- | --- |
| 0 | Setup | Dealership / rep / customer / vehicle inputs |
| 1 | Dashboard | Greet header, 4 stat tiles, Active Deal card, "+ New Walk-In" |
| 2 | Step 1 · Greet | Script card, audio widget, 2 Y/N (1.1 manual, 1.2 auto) |
| 3 | Step 2 · Discovery | Discovery sheet, mini-audio, 4 use-case checkboxes (first 2 auto-tick) |
| 4 | Step 3.1 · Feature Match | 3 feature cards "from VIN", audio, 1 Y/N (auto) |
| 5 | Step 3.2 · Front-Line Ready | Audio detects key-handover timing, 1 Y/N (auto), 2 photo slots |
| 6 | Step 4 · Walkaround | Audio, 4 features (3 auto-pass, 1 fails) |
| 7 | Step 5 · Test Drive | Script, simulated GPS, audio, 1 Y/N (auto) |
| 8 | Step 6 · Trade-In | Audio auto-fills condition notes, 4 photo slots, appraisal |
| 9 | Step 7.1 · First Pencil | Quote table, "Mark Pencil" → timestamp. Floating Manager T.O. button → bottom sheet with audio detecting "new voice + intro" |
| 10 | Step 7.2 · Buyer's Order | Tap to photograph → flash → reveals captured order with signature |
| 11 | Step 9 · F&I Handoff | Script, audio detects three-way intro, F&I 20-min timer |
| 12 | Audit Complete | Score circle, 15-line audit table, "Back to Dashboard" |

### Navigation
- Native stack
- Persistent in-app top header: brand mark + dealership name + linear progress bar
- Persistent bottom nav: Back / Next (hidden on Dashboard and Audit Complete)
- Drop desktop side panels (mobile-only)
- Optional hamburger drawer with step list and jump-to-step

### How screens consume services

Every screen uses `useServices()`. Example for Greet:

```typescript
function GreetScreen() {
  const { audio } = useServices()
  const setFlag = useDealStore(s => s.setFlag)

  useEffect(() => {
    audio.start({ screenId: 'greet', stepId: '1' })
    const off = audio.onDetection(event => {
      if (event.stepId === '1.2' && event.type === 'auto-confirm') {
        setFlag('g2', 'yes', { source: 'auto' })
      }
    })
    return () => { off(); audio.stop() }
  }, [])

  // ... UI rendering, agnostic of whether audio is scripted or real
}
```

This screen works identically with `ScriptedAudioService` or a real Deepgram-backed service. The UI never knows.

---

## 7. State (Zustand)

```typescript
type DealState = {
  deal: Deal | null
  currentStep: number
  flags: Record<string, { value: 'yes' | 'no' | null; source: 'manual' | 'auto' }>
  features: Record<string, 'pass' | 'fail' | null>
  useCases: Record<string, boolean>
  photos: Record<string, string | null>
  notes: { trade: string }
  timestamps: { pencil: Date | null; boSigned: Date | null; mgrTo: Date | null }
  pencilMarked: boolean
  boCaptured: boolean
  fiHandoff: boolean

  // actions write through to BackendService
  startDeal: (setup: DealSetup) => Promise<void>
  setFlag: (id: string, value: 'yes' | 'no', meta: { source: 'manual' | 'auto' }) => Promise<void>
  // ... etc
}
```

State actions write through to `BackendService.updateDeal()` — the same store works with local SQLite today and cloud backend tomorrow.

---

## 8. Audio script (port from HTML)

`src/services/audio/audioScript.ts` is a typed port of the HTML's `AUDIO` object. The `ScriptedAudioService` consumes this:

```typescript
type ScriptLine = {
  t: number  // ms offset
  txt?: string
  trigger?: string
  detectionEvent?: DetectionEvent
}

type ScreenScript = {
  screenId: string
  delay: number
  lines: ScriptLine[]
}

export const audioScript: Record<string, ScreenScript> = {
  greet: {
    screenId: 'greet',
    delay: 2500,
    lines: [
      { t: 0, txt: 'Listening...' },
      { t: 1200, txt: '"Hi Sarah, welcome to Riverside Honda..."' },
      { t: 2400, txt: '"...can I grab you a coffee or water before we head out?"', trigger: 'coffee or water' },
      { t: 3400, detectionEvent: { stepId: '1.2', type: 'auto-confirm' } },
    ],
  },
  // ... port all screens from HTML AUDIO object
}
```

Use the **exact** lines and timings from the HTML. Don't paraphrase the dialogue.

---

## 9. Score calculation

On Audit Complete:
- 15 audit line items (full list in HTML)
- Each item passes if its flag is `yes` OR auto-detection fired OR photo captured OR feature ticked
- Hardcode "Heated Seats" feature failure to mirror HTML
- Score = (passed / 15) × 100, rounded
- Default demo path = 91%
- Scoring lives in `BackendService.computeScore()` so the cloud backend can override later

---

## 10. File structure

```
auditpro/
├── app.json
├── App.tsx
├── package.json
├── README.md
├── src/
│   ├── theme.ts
│   ├── store/
│   │   ├── index.ts
│   │   └── deal.ts
│   ├── services/
│   │   ├── composition.ts
│   │   ├── context.tsx
│   │   ├── audio/
│   │   │   ├── AudioService.ts
│   │   │   ├── ScriptedAudioService.ts
│   │   │   ├── audioScript.ts
│   │   │   └── AudioService.contract.test.ts
│   │   ├── camera/
│   │   │   ├── CameraService.ts
│   │   │   ├── FakeCameraService.ts
│   │   │   ├── RealCameraService.ts
│   │   │   └── CameraService.contract.test.ts
│   │   ├── cv/
│   │   │   ├── CVService.ts
│   │   │   ├── ScriptedCVService.ts
│   │   │   └── CVService.contract.test.ts
│   │   ├── gps/...
│   │   ├── backend/
│   │   │   ├── BackendService.ts
│   │   │   ├── LocalBackendService.ts
│   │   │   ├── schema.ts
│   │   │   └── BackendService.contract.test.ts
│   │   ├── auth/...
│   │   └── telemetry/...
│   ├── data/
│   │   ├── steps.ts
│   │   └── auditItems.ts
│   ├── components/
│   │   ├── AudioWidget.tsx
│   │   ├── AudioWidgetMini.tsx
│   │   ├── ScriptCard.tsx
│   │   ├── YNCard.tsx
│   │   ├── FeatureItem.tsx
│   │   ├── CheckboxRow.tsx
│   │   ├── PhotoSlot.tsx
│   │   ├── TopHeader.tsx
│   │   ├── BottomNav.tsx
│   │   ├── ProgressBar.tsx
│   │   └── Waveform.tsx
│   └── screens/
│       ├── SetupScreen.tsx
│       ├── DashboardScreen.tsx
│       ├── GreetScreen.tsx
│       ├── DiscoveryScreen.tsx
│       ├── FeatureMatchScreen.tsx
│       ├── FrontLineReadyScreen.tsx
│       ├── WalkaroundScreen.tsx
│       ├── TestDriveScreen.tsx
│       ├── TradeInScreen.tsx
│       ├── PencilScreen.tsx
│       ├── BuyersOrderScreen.tsx
│       ├── FIHandoffScreen.tsx
│       └── AuditCompleteScreen.tsx
└── __tests__/
    └── e2e/
        └── demoFlow.test.ts
```

---

## 11. Acceptance criteria

The build is done when:

1. `npx expo start` launches cleanly with no errors
2. Loading via Expo Go on a real iPhone or Android device works in under 5 minutes from clone
3. Setup → Dashboard → all 12 audit screens → Audit Complete → back to Dashboard runs end-to-end without crashes
4. Each audio-enabled screen runs its scripted simulation with correct timing, trigger highlights, and auto-confirmations
5. Photo capture works on iOS and Android (real Expo Camera in production-camera mode, fake fallback in demo)
6. Visual fidelity to the HTML is high — colors, fonts, spacing, animations match
7. Default demo flow scores 91%
8. **Architecture criterion:** zero `if (mode === 'demo')` branches in screens or components. All mode-dependent behavior lives behind service interfaces. Searching for `'demo'` in `src/screens` and `src/components` returns no business-logic hits.
9. **Contract tests pass** for every service. Both providers (where two exist) satisfy the same contract.
10. **CI runs the demoFlow E2E test** on every PR. Demo never breaks silently.

---

## 12. Style notes for the agent

- Default to mirroring the HTML when in doubt about visuals or interactions
- Don't add unrequested features
- TypeScript strict; no `any`
- Functional components, hooks for state
- Comment generously around the `// TODO(real-...)` stubs so the production engineering team knows the exact contract to satisfy
- Service interfaces should be designed for the **real** implementation, not the scripted one. The scripted implementation must conform to a real-world-shaped interface, even if it fakes the underlying behavior.
- The local SQLite backend is real production code — design the schema thoughtfully (deals, events, photos, audits tables; foreign keys; indexes on dealership_id and created_at)
