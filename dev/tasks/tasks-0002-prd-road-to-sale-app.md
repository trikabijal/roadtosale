# Task List — 0002 PRD Road to Sale App (Mobile v1)

Generated: `2026-05-24`
PRD: `dev/tasks/0002-prd-road-to-sale-app.md`
Status: active

Upstream modules already shipped:
- `vehicle-feature-catalog/` — Honda 2026 full lineup, TS facade (`VehicleFeatureCatalog`)
- `voice-engine/src/` — TS facade (`VoiceEngine`), `TranscriptEvent`, `CueDetection` types
- `voice-engine/lab/cue-packs/` — workflow + feature cue YAML packs
- `demo/auditpro-rn-showcase/` — UI prototype (visual reference only, not product code)

Key decisions baked in:
- DC54: Audit template is fetched from SmartComply API at runtime and cached locally
- DC55: Template ID (`road-to-sale-v1`) is the audit type — no new SmartComply schema field required
- DC57: Cue-to-template-question binding lives in Road to Sale cue pack YAML (`template_question_id` field per cue atom)
- DC58: Road to Sale self-hosts its own SmartComply instance (`smartcomply/` deployable); schema extensions are owned here first, proposed upstream later

**Dependency order:** Task 3 (SmartComply instance) must be complete before tasks 4, 5, and 8 begin. Task 5 (ChecklistEngine + cue pack) must be complete before task 6 (Active Session UI). All other tasks can proceed in parallel once task 1 (scaffold) is done.

---

## Relevant Files

**`smartcomply/` deployable**
- `smartcomply/` — imported SmartComply source, schema, API, and build config
- `smartcomply/schema/extensions/road_to_sale.sql` — Road to Sale schema extensions
- `smartcomply/docs/road-to-sale-extensions.md` — extension field docs + upstream proposal notes
- `smartcomply/build.sh` — one-command build and local run
- `smartcomply/.env.example` — required env vars for the instance

**`road-to-sale-app/` deployable**
- `road-to-sale-app/package.json`
- `road-to-sale-app/app.json` — Expo config (permissions, background audio modes, bundle ID)
- `road-to-sale-app/build.sh` — catalog compile + Expo prebuild in one command
- `road-to-sale-app/.env.example`
- `road-to-sale-app/src/navigation/RootNavigator.tsx`
- `road-to-sale-app/src/navigation/types.ts` — typed route params
- `road-to-sale-app/src/theme/colors.ts` — dark/light colour tokens
- `road-to-sale-app/src/theme/typography.ts`
- `road-to-sale-app/src/screens/AuthScreen.tsx`
- `road-to-sale-app/src/screens/HomeScreen.tsx`
- `road-to-sale-app/src/screens/SessionSetupScreen.tsx`
- `road-to-sale-app/src/screens/ActiveSessionScreen.tsx`
- `road-to-sale-app/src/screens/TradeInScreen.tsx`
- `road-to-sale-app/src/screens/SessionSummaryScreen.tsx`
- `road-to-sale-app/src/screens/HistoryScreen.tsx`
- `road-to-sale-app/src/components/MicIndicator.tsx`
- `road-to-sale-app/src/components/ChecklistItem.tsx`
- `road-to-sale-app/src/components/CueSubPanel.tsx`
- `road-to-sale-app/src/components/FeatureCoveragePanel.tsx`
- `road-to-sale-app/src/components/CameraGuide.tsx`
- `road-to-sale-app/src/crm/types.ts` — `CrmAppointmentProvider` interface + `Appointment` type
- `road-to-sale-app/src/crm/AuditProCrmProvider.ts`
- `road-to-sale-app/src/crm/AuditProCrmProvider.test.ts`
- `road-to-sale-app/src/api/smartcomply.ts` — SmartComply REST client
- `road-to-sale-app/src/api/smartcomply.test.ts`
- `road-to-sale-app/src/catalog/build-catalog.ts` — build-time YAML→JSON compiler (run by build.sh)
- `road-to-sale-app/src/catalog/loader.ts` — loads bundled JSON at startup
- `road-to-sale-app/src/catalog/loader.test.ts`
- `road-to-sale-app/src/catalog/bundle.json` — generated at build, gitignored
- `road-to-sale-app/src/cue-packs/road-to-sale-v1.yaml` — Road to Sale cue pack with `template_question_id` bindings
- `road-to-sale-app/src/session/SessionEngine.ts` — session lifecycle state machine
- `road-to-sale-app/src/session/SessionEngine.test.ts`
- `road-to-sale-app/src/session/ChecklistEngine.ts` — joins template questions ↔ cue atoms; drives live checklist state
- `road-to-sale-app/src/session/ChecklistEngine.test.ts`
- `road-to-sale-app/src/voice/NativeVoiceModule.ts` — JS bridge to native module
- `road-to-sale-app/ios/VoiceModule/RtsVoiceModule.swift`
- `road-to-sale-app/ios/VoiceModule/RtsVoiceModule.m` — ObjC bridge header
- `road-to-sale-app/android/app/src/main/kotlin/com/trika/roadtosale/voice/RtsVoiceModule.kt`
- `road-to-sale-app/android/app/src/main/kotlin/com/trika/roadtosale/voice/RtsVoiceForegroundService.kt`
- `road-to-sale-app/src/db/schema.ts` — SQLite table definitions
- `road-to-sale-app/src/db/SessionRepository.ts`
- `road-to-sale-app/src/db/SessionRepository.test.ts`
- `road-to-sale-app/docs/smartcomply-contract.md` — write schema, extension fields, retry policy

### Notes

- Unit tests are co-located with source (`Foo.ts` / `Foo.test.ts` in the same directory).
- Run JS unit tests: `cd road-to-sale-app && npx jest`
- iOS native module: test via Xcode unit test target
- Android native module: `cd road-to-sale-app/android && ./gradlew test`
- SmartComply instance: follow its own test script in `smartcomply/`
- `bundle.json` and all `data/`, `dist/`, `build/` outputs are gitignored — run `build.sh` to regenerate locally

---

## Tasks

- [ ] 1.0 App scaffold and navigation
  - [ ] 1.1 Initialise Expo managed React Native project in `road-to-sale-app/` with TypeScript template (`npx create-expo-app road-to-sale-app --template expo-template-blank-typescript`). Confirm it runs on iOS simulator before proceeding.
  - [ ] 1.2 Set up folder structure: `src/screens/`, `src/components/`, `src/navigation/`, `src/theme/`, `src/api/`, `src/crm/`, `src/session/`, `src/voice/`, `src/catalog/`, `src/cue-packs/`, `src/db/`, `docs/`, `tests/`, `ios/VoiceModule/`, `android/…/voice/`.
  - [ ] 1.3 Install and configure React Navigation (`@react-navigation/native`, `@react-navigation/native-stack`). Define the root stack in `src/navigation/RootNavigator.tsx` with typed params in `src/navigation/types.ts`. Routes: Auth, Home, SessionSetup, ActiveSession, TradeIn, SessionSummary, History.
  - [ ] 1.4 Create the theme layer: `src/theme/colors.ts` (dark + light token sets), `src/theme/typography.ts`. Wire into a `ThemeContext` with `useColorScheme()` defaulting to dark mode on first launch.
  - [ ] 1.5 Create `build.sh` at `road-to-sale-app/build.sh`: runs catalog compile (step 5.2) then `expo prebuild`. Create `run.sh` and `test.sh` stubs. Add `.env.example` documenting `SMARTCOMPLY_API_URL`, `SMARTCOMPLY_TEMPLATE_ID`.
  - [ ] 1.6 Add gitignore rules for `road-to-sale-app/`: `node_modules/`, `dist/`, `.expo/`, `ios/build/`, `android/build/`, `src/catalog/bundle.json`.
  - [ ] 1.7 Create placeholder screen files (empty `View` + screen title `Text`) for all 7 screens so navigation can be wired and tested end-to-end before any real UI is built.

- [ ] 2.0 Authentication
  - [ ] 2.1 Build `AuthScreen.tsx`: username + password fields, "Sign In" button, error message area. Match the visual style from `demo/auditpro-rn-showcase/` (colours, font, logo placement). One-thumb operable — inputs large enough for showroom use.
  - [ ] 2.2 Implement the SmartComply login API call in `src/api/smartcomply.ts`: `POST /auth/login` → returns JWT access token + refresh token. Handle 401, network error, and timeout with user-facing error messages.
  - [ ] 2.3 Store tokens securely using `expo-secure-store` (iOS Keychain / Android Keystore). Keys: `rts_access_token`, `rts_refresh_token`. Never store in AsyncStorage or SQLite.
  - [ ] 2.4 Implement silent token refresh: wrap every SmartComply API call in a retry interceptor that calls `POST /auth/refresh` on 401, updates stored tokens, and replays the original request. If refresh fails, navigate to Auth screen and clear stored tokens.
  - [ ] 2.5 On app launch, check for a stored access token. If present and not expired, navigate directly to Home; skip Auth screen. If absent or expired without a valid refresh token, show Auth screen.
  - [ ] 2.6 Implement logout: clear both tokens from SecureStore, clear SQLite session cache, navigate to Auth screen.
  - [ ] 2.7 Write unit tests for the token interceptor (mock fetch): verify (a) 401 triggers refresh + replay, (b) failed refresh navigates to Auth, (c) successful request skips refresh.

- [ ] 3.0 SmartComply instance setup and Road to Sale schema extensions
  - [ ] 3.1 Import the SmartComply source, schema, API, and deployable into `smartcomply/` at the repo root. Follow the same folder structure as other deployables (`src/`, `docs/`, `tests/`, `build.sh`). Do not modify any SmartComply source files in this step — import only.
  - [ ] 3.2 Audit the existing SmartComply schema: document the core tables relevant to Road to Sale (audit templates, template questions/checklist items, inspection instances, inspection checklist item outcomes, evidence items). Write a brief schema summary in `smartcomply/docs/road-to-sale-extensions.md` as the baseline before any extensions.
  - [ ] 3.3 Design the Road to Sale schema extensions. For each extension, document: table/column name, type, nullable, why it can't fit in an existing field, and the proposed upstream field name for the SmartComply team. Extensions needed at minimum:
    - `evidence_items.cue_id` — voice engine cue atom ID that triggered this evidence item
    - `evidence_items.cue_source` — `'feature'` or `'workflow'`
    - `evidence_items.transcript_snippet` — short text excerpt from the transcript (≤ 200 chars)
    - `evidence_items.cue_confidence` — float 0–1, matching engine confidence
    - `inspection_checklist_items.voice_auto_completed` — bool, true if completed by voice detection (not manual override)
    - Trade-in photo storage: evaluate whether existing evidence attachment fields cover binary/URL references; add a `trade_photos` table if not.
  - [ ] 3.4 Write `smartcomply/schema/extensions/road_to_sale.sql` applying the extensions (ALTER TABLE or CREATE TABLE statements). Include a rollback script at `smartcomply/schema/extensions/road_to_sale_rollback.sql`.
  - [ ] 3.5 Apply the extension SQL to the local SmartComply dev instance. Confirm the instance starts and the extended schema is queryable. Update `smartcomply/build.sh` to run the extension SQL as part of `./build.sh dev`.
  - [ ] 3.6 Create the `road-to-sale-v1` audit template record in the local SmartComply instance. The template must include all 10 NADA steps as template questions with stable IDs (use placeholder IDs now; these will be replaced once the production SmartComply instance is available per OQ1). Document the placeholder IDs in `smartcomply/docs/road-to-sale-extensions.md`.
  - [ ] 3.7 Confirm the SmartComply Audit Template API endpoint returns the `road-to-sale-v1` template with all 10 questions. Confirm the Inspection create API accepts a `template_id` and creates an inspection instance. Log both request/response shapes — these become the source of truth for `road-to-sale-app/docs/smartcomply-contract.md`.
  - [ ] 3.8 Write `smartcomply/docs/road-to-sale-extensions.md`: full extension field spec, rationale for each field, placeholder-vs-production ID note, and a section "Proposing upstream" describing how to PR the extensions back to the SmartComply team once stable.

- [ ] 4.0 Home screen and CRM layer
  - [ ] 4.1 Define the `CrmAppointmentProvider` interface and `Appointment` type in `src/crm/types.ts` exactly as specified in PRD §7.2. This file is the normative contract — do not add any implementation code here.
  - [ ] 4.2 Implement `AuditProCrmProvider` in `src/crm/AuditProCrmProvider.ts`: calls the SmartComply API to fetch today's appointments for the logged-in rep (`GET /appointments?date=today&repId=…`). Returns `Appointment[]`. On API error, throws with a typed error so the caller can distinguish network failure from auth failure.
  - [ ] 4.3 Build `HomeScreen.tsx`: shows a scrollable list of today's appointments (customer name, scheduled time, vehicle shortlist if available), each as a tappable card. A prominent "New Walk-In" button is always visible at the bottom (never hidden by the appointment list).
  - [ ] 4.4 Implement offline cache for appointments: after each successful API fetch, write the result to a SQLite `appointments_cache` table (schema in task 9.1) keyed by date + rep ID. On next launch, load from cache immediately, then revalidate in background. Show a "Data as of [time]" stale badge if the last fetch was >15 minutes ago or the API is unreachable.
  - [ ] 4.5 Handle the walk-in path: tapping "New Walk-In" navigates to `SessionSetupScreen` with no pre-populated appointment data. This path must work fully offline (does not call any API before session starts).
  - [ ] 4.6 Handle tapping an appointment card: navigate to `SessionSetupScreen` with the appointment's customer and vehicle data pre-populated.
  - [ ] 4.7 Write unit tests for `AuditProCrmProvider`: (a) successful fetch returns typed `Appointment[]`, (b) 401 throws auth error, (c) network timeout throws network error. Use a mocked fetch.

- [ ] 5.0 Session setup, catalog bundling, and audit template + cue pack binding
  - [ ] 5.1 Build `SessionSetupScreen.tsx`: two modes — (a) walk-in: first name required, last name + phone optional; (b) appointment: customer fields pre-populated and editable. Both modes end with a "Choose Vehicle" step.
  - [ ] 5.2 Write the catalog build script `src/catalog/build-catalog.ts`. It reads `vehicle-feature-catalog/data/` (the existing YAML files), compiles all makes, models, trims, and features into a single `src/catalog/bundle.json`. Hook this into `build.sh` so it runs automatically on every build. The app never fetches catalog data at runtime.
  - [ ] 5.3 Write `src/catalog/loader.ts`: imports `bundle.json` at module load time and exposes the same API surface as `VehicleFeatureCatalog` from the catalog TS facade (`list_makes()`, `list_models(make_id)`, `list_trims(model_id)`, `list_features_for_trim(trim_id)`). This is a thin wrapper — do not duplicate catalog logic.
  - [ ] 5.4 Build the vehicle picker UI (part of `SessionSetupScreen.tsx`): cascading pickers — Make → Model → Trim. Each selection filters the next list. Use the catalog loader from 5.3. Wrap in a `FlatList` so the full Honda lineup (9 models, many trims) is scrollable without layout issues.
  - [ ] 5.5 Build the trim feature confirmation screen (still within `SessionSetupScreen.tsx` flow): once a trim is selected, show the feature list for that trim with a brief description of each. The rep confirms "Yes, this is the car" or goes back to change trim. Do not auto-proceed.
  - [ ] 5.6 Implement audit template fetch in `src/api/smartcomply.ts`: `GET /audit-templates/{templateId}`. Parse the response into a typed `AuditTemplate` interface (questions array, each question has `id`, `text`, `required: boolean`, `order: number`). Cache the result in SQLite (task 9.1 `template_cache` table) with the response ETag.
  - [ ] 5.7 Implement template cache-first loading: on session start, serve the cached template immediately (fast path, avoids UI block), then revalidate in background using the ETag. If no cache exists and the API is unreachable, show an error and block session start with a clear message explaining why ("Checklist template unavailable — connect to the internet to start a session").
  - [ ] 5.8 Write `src/cue-packs/road-to-sale-v1.yaml`. This file is the join point between the voice engine and SmartComply. Format: for each cue atom (referencing existing cue pack IDs from `voice-engine/lab/cue-packs/`), add a `template_question_id` field matching the placeholder question IDs created in task 3.6. Cover all 10 NADA steps. Example entry:
    ```yaml
    - cue_id: workflow.discovery_budget_signal
      template_question_id: rts-q-discovery-requirements
      required: true
    - cue_id: workflow.discovery_highway_commute
      template_question_id: rts-q-discovery-requirements
      required: false
    ```
  - [ ] 5.9 Write `src/session/ChecklistEngine.ts`. At session start, it takes the fetched `AuditTemplate` and the loaded `road-to-sale-v1` cue pack and builds a runtime map: `questionId → { question, cueAtoms[], detectedCues[] }`. Exposes a `processCueDetection(detection: CueDetection)` method that updates the map and emits an `onChecklistUpdate` event. This is pure logic — no UI, no React.
  - [ ] 5.10 Write unit tests for `ChecklistEngine`: (a) template + cue pack produces correct question→cues map, (b) `processCueDetection` marks the right question as partially/fully covered, (c) required vs optional cues correctly gate step completion.
  - [ ] 5.11 Write unit tests for `catalog/loader.ts`: (a) `bundle.json` loads without error, (b) `list_makes()` returns at least Honda, (c) `list_trims('honda.crv_hybrid')` returns the expected trims.

- [ ] 6.0 Voice engine native bridge and active session UI
  - [ ] 6.1 **iOS native module — project setup:** Add a new Swift file group `VoiceModule/` inside the Expo bare iOS project (`road-to-sale-app/ios/`). Create `RtsVoiceModule.swift` and the Objective-C bridge header `RtsVoiceModule.m`. Register the module with the RN bridge using `RCT_EXTERN_MODULE`.
  - [ ] 6.2 **iOS native module — SpeechTranscriber integration:** In `RtsVoiceModule.swift`, implement `startListening()` and `stopListening()` using `AVAudioEngine` + `SFSpeechAudioBufferRecognitionRequest` (Apple SpeechTranscriber). On each partial and final result, serialize a `TranscriptEvent` JSON object (fields: `text`, `stability`, `timestamp_ms`, `confidence`, `latency_ms_from_audio_start`, `engine_metadata`) and emit it to the RN event emitter as `onTranscriptEvent`.
  - [ ] 6.3 **iOS permissions + background audio:** Add `NSMicrophoneUsageDescription` and `NSSpeechRecognitionUsageDescription` to `Info.plist`. Add `UIBackgroundModes: [audio]`. Request mic and speech recognition permissions at session start (not app launch). If denied, emit a `onPermissionDenied` event with instructions to open Settings.
  - [ ] 6.4 **Android native module — project setup:** Create `RtsVoiceModule.kt` and `RtsVoicePackage.kt` in `android/app/src/main/kotlin/com/trika/roadtosale/voice/`. Register the package in `MainApplication.kt`. Create `RtsVoiceForegroundService.kt` for always-on background audio.
  - [ ] 6.5 **Android native module — SherpaOnnx integration:** In `RtsVoiceModule.kt`, integrate the SherpaOnnx Silero VAD + OfflineRecognizer stack from `voice-engine/native/android/SherpaOnnxSTT/` (already validated in the lab). Run in VAD mode (512-sample frames, Silero VAD segments, OfflineRecognizer per segment). Emit `TranscriptEvent` JSON to RN via `sendEvent("onTranscriptEvent", ...)` — same field schema as iOS.
  - [ ] 6.6 **Android permissions + foreground service:** Add `RECORD_AUDIO` and `FOREGROUND_SERVICE` to `AndroidManifest.xml`. Request `RECORD_AUDIO` at session start. Start `RtsVoiceForegroundService` when listening begins; show a persistent notification ("Road to Sale — mic active") per Android requirements. Stop the service on session end.
  - [ ] 6.7 **JS bridge (`NativeVoiceModule.ts`):** Wrap the native module using `NativeModules` and `NativeEventEmitter`. Expose: `startListening(): Promise<void>`, `stopListening(): Promise<void>`, `mute(): void`, `unmute(): void`, `onTranscriptEvent(listener)`, `onPermissionDenied(listener)`. Convert the raw JSON event payload to the `TranscriptEvent` TypeScript type from `voice-engine/src/types/`.
  - [ ] 6.8 **Session lifecycle (`SessionEngine.ts`):** Implement the session state machine with states: `idle → setup → active → ending → ended`. Transitions: `startSession(customer, vehicle, template)` → `active`; `endSession()` → `ending` (waits for final cue flush) → `ended`. On `active` entry: call `NativeVoiceModule.startListening()`. On `ending`: call `stopListening()`. Expose `onSessionStateChange(listener)`.
  - [ ] 6.9 **Wire ChecklistEngine into SessionEngine:** In the `active` state, pipe `onTranscriptEvent` events through `VoiceEngine.matchCues()` (existing TS facade), then feed each `CueDetection` into `ChecklistEngine.processCueDetection()`. The checklist state updates flow out via `ChecklistEngine.onChecklistUpdate`.
  - [ ] 6.10 **Active session UI — checklist:** Build `ActiveSessionScreen.tsx`. Main content: a `FlatList` of NADA checklist items driven by `ChecklistEngine` state. Each `ChecklistItem` component shows: question text, green tick (detected) / grey circle (pending), and — when at least one cue is detected — an expandable `CueSubPanel` listing detected cues with their transcript snippets. Auto-scroll to keep the current active step visible.
  - [ ] 6.11 **Active session UI — mic indicator and mute:** Persistent `MicIndicator` component pinned at the top of the screen. Green pulsing waveform = active; solid red dot + "Muted" label = muted. Tap anywhere on the indicator to toggle mute/unmute. This must be impossible to miss at a glance.
  - [ ] 6.12 **Active session UI — manual override:** Tapping a checklist item (any state) opens a bottom sheet with two options: "Mark complete" (manual override) and "Add note". Manual override records the step as `override: true` in session state and turns the item green. Add note attaches a text note to that step without changing completion state.
  - [ ] 6.13 **Active session UI — feature coverage panel:** A collapsible panel accessible from a "Features" toggle button. Lists all features for the selected trim with a green/grey indicator per feature (detected vs not mentioned). Updates in real time from the same cue detection stream. Collapsed by default so the checklist is the primary surface.
  - [ ] 6.14 **Active session UI — Trade-In entry point:** A "Trade-In" button in the header. Tapping it navigates to `TradeInScreen` without pausing the session. Mic stays on. A small active-session indicator remains visible in the Trade-In screen header so the rep knows the session is still running.
  - [ ] 6.15 Write unit tests for `SessionEngine`: (a) `startSession()` transitions to `active`, (b) `endSession()` transitions through `ending` → `ended`, (c) calling `endSession()` in `idle` throws. Write unit tests for `NativeVoiceModule.ts` using a mocked `NativeModule` and `EventEmitter`.

- [ ] 7.0 Trade-in capture
  - [ ] 7.1 Build `TradeInScreen.tsx` with two side-by-side areas: (a) camera guide panel showing required shots and status, (b) spoken notes transcript panel showing live voice detections for trade cues.
  - [ ] 7.2 Implement the camera guide: a visual shot list (exterior front-left, front-right, rear-left, rear-right, interior, odometer, VIN). Each shot slot shows "needed" (empty outline) or "captured" (green thumbnail). The rep taps a slot to take that photo.
  - [ ] 7.3 Integrate `expo-camera`: request camera permission at first use. On each photo capture, store the image to the device's app-private storage (not the camera roll — raw audio privacy design applies to photos too). Record the local file path in the session's trade-in data. Display a thumbnail in the shot slot after capture.
  - [ ] 7.4 Wire the trade-in spoken notes panel: the voice engine is already running. Subscribe to `ChecklistEngine.onChecklistUpdate` filtered for trade-cue detections (cues with `template_question_id` matching trade-appraisal questions: `workflow.trade_in_dent`, `workflow.trade_in_tires`, etc.). Display each detected cue's transcript snippet in a chronological list.
  - [ ] 7.5 Add a typed note input (multi-line text field) at the bottom of the spoken notes panel. The rep can type at any time. Typed notes are stored separately from voice detections and shown in the session summary.
  - [ ] 7.6 Track trade-in completeness: once all required shots are captured, show a green "Trade-In Complete" state in the header. The rep can still add more photos or notes after this state.
  - [ ] 7.7 Persist trade-in state to the in-progress session record in SQLite (task 9.1) so it survives an app crash mid-capture.

- [ ] 8.0 Session summary and SmartComply write
  - [ ] 8.1 Write `road-to-sale-app/docs/smartcomply-contract.md` before writing any API code. Document: (a) Inspection create endpoint + request/response shape (using findings from task 3.7), (b) checklist item outcome write shape, (c) evidence item write shape (including all Road to Sale extension fields from task 3.3), (d) trade-in photo attachment approach, (e) HTTP error codes and their meaning, (f) retry policy (exponential backoff, max 3 retries, then queue to SQLite).
  - [ ] 8.2 Implement the End Session flow: "End Session" button in the active session header. Tapping it shows a single confirmation dialog ("End this session?") before proceeding. On confirm, call `SessionEngine.endSession()`, wait for the final cue flush, then navigate to `SessionSummaryScreen`.
  - [ ] 8.3 Build `SessionSummaryScreen.tsx` with four sections:
    - **Covered** — list of NADA steps that reached full completion, each with detected cue snippets as evidence
    - **Missed** — list of steps with no or partial detection, each flagged as a coaching opportunity (show what was missing)
    - **Override log** — list of steps the rep manually confirmed, with any notes they added
    - **Trade-in** — photo count (e.g., "6/7 photos captured"), spoken condition summary (list of detected trade cues), typed notes
    A "Session note" free-text field sits below all four sections. An "AuditPro status" indicator at the top shows the write state (pending / success / failed).
  - [ ] 8.4 Implement `src/api/smartcomply.ts` — SmartComply write operations, following `smartcomply-contract.md`:
    - `createInspection(templateId, sessionMeta)` → returns `inspectionId`
    - `writeChecklistOutcome(inspectionId, questionId, outcome)` — outcome: `{ status: 'complete' | 'missed' | 'overridden', voiceAutoCompleted: boolean }`
    - `writeEvidenceItem(inspectionId, questionId, cueEvent)` — includes extension fields (cue_id, cue_source, transcript_snippet, cue_confidence)
    - `writeTradePhotos(inspectionId, photoPaths)` — uploads or links photos per approach agreed in OQ7
    - `writeSessionNote(inspectionId, note)`
    All operations accept an `inspectionId` and are idempotent (safe to retry on network failure).
  - [ ] 8.5 Implement the automatic SmartComply write on session end: call the operations in 8.4 in sequence after `SessionSummaryScreen` mounts. Show a loading indicator. On success, update the UI status to "Saved to SmartComply ✓". On failure, queue to the retry table (task 9.2) and show "Will sync when online."
  - [ ] 8.6 Implement the offline retry queue consumer: on app foreground and on connectivity change, check the `pending_writes` SQLite table (task 9.2). For each queued write, attempt it in order. On success, delete the queue entry. On failure, increment retry count; after 5 failures, mark as `failed` and surface a badge to the rep ("1 session not synced — tap to retry"). Update the home screen badge.
  - [ ] 8.7 Implement PDF export: on `SessionSummaryScreen`, a "Share / Export PDF" button generates a PDF with: session date, rep name, customer name, vehicle, covered/missed steps with snippets, trade-in summary, session note. Use `react-native-pdf-lib` or `expo-print` + `expo-sharing`. PDF is written to a temp file and opened in the system share sheet.
  - [ ] 8.8 Write unit tests for SmartComply write operations (mock fetch): (a) `createInspection` on success returns inspection ID, (b) `writeEvidenceItem` includes extension fields in request body, (c) 503 response triggers retry logic, (d) offline detection routes write to `pending_writes`.

- [ ] 9.0 Local persistence and session history
  - [ ] 9.1 Design and create the SQLite schema in `src/db/schema.ts` using `expo-sqlite`. Tables:
    - `sessions` — session_id (PK), customer JSON, vehicle JSON, template_id, started_at, ended_at, status (`active` | `ended` | `crashed`)
    - `checklist_states` — session_id (FK), question_id, status, voice_auto_completed, notes, updated_at
    - `cue_events` — id (PK), session_id (FK), cue_id, cue_source, transcript_snippet, confidence, detected_at_ms
    - `trade_photos` — id (PK), session_id (FK), slot (e.g., `front_left`), local_path, captured_at
    - `pending_writes` — id (PK), session_id (FK), retry_count, last_attempted_at, status (`queued` | `failed`), payload JSON
    - `template_cache` — template_id (PK), etag, fetched_at, template JSON
    - `appointments_cache` — date (PK), rep_id (PK), fetched_at, appointments JSON
    Run `CREATE TABLE IF NOT EXISTS` on app startup.
  - [ ] 9.2 Implement `SessionRepository.ts` with methods: `createSession()`, `updateChecklistState()`, `insertCueEvent()`, `insertTradePhoto()`, `getSession(id)`, `getAllSessions()`, `markSessionEnded()`, `queuePendingWrite()`, `getPendingWrites()`, `deletePendingWrite(id)`, `updatePendingWriteRetry(id)`. All methods are async and use `expo-sqlite` prepared statements.
  - [ ] 9.3 Implement crash recovery: on app launch, query `sessions` for any row with `status = 'active'`. If found, show a recovery dialog: "You have an unfinished session with [customer]. Resume or discard?" Resume loads the session state and returns to `ActiveSessionScreen`. Discard marks the session as `crashed` and returns to Home.
  - [ ] 9.4 Build `HistoryScreen.tsx`: a `FlatList` of past sessions (status = `ended` or `crashed`), sorted newest first. Each row shows: customer name, vehicle, date/time, and a completion indicator (percentage of NADA steps covered). Tapping a row navigates to `SessionSummaryScreen` in read-only mode (no write trigger, no End Session button, no PDF export button in read-only header — re-export is available via a menu).
  - [ ] 9.5 Wire the pending sync badge: on every app foreground, query `pending_writes` for rows where `status = 'failed'`. If any exist, show a badge count on the History icon in the navigation bar. Tapping the badge navigates to a "Pending syncs" list with a manual "Retry all" button.
  - [ ] 9.6 Write unit tests for `SessionRepository`: (a) `createSession` + `getSession` round-trips correctly, (b) `queuePendingWrite` + `getPendingWrites` returns the queued entry, (c) `markSessionEnded` updates status and ended_at. Use an in-memory SQLite instance for tests.
