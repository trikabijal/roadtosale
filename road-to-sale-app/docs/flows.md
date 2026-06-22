# Road to Sale App — End-to-End Flows

Concrete UI → engine → backend walkthroughs naming the real files and methods.
Read [`architecture.md`](./architecture.md) first for the layer map, and
[`api.md`](./api.md) for the two API surfaces these flows hit.

Singletons used throughout: `getSmartComplyClient()`, `getSessionRepository()`,
`getSessionEngine()`, `getVoiceEngine()`.

---

## (a) Auth: login + silent token refresh

**Files:** `screens/AuthScreen.tsx`, `api/SmartComplyClient.ts`.

```
App launches → RootNavigator initialRoute = "Auth"
AuthScreen.useEffect:
  SmartComplyClient.hasValidToken()        // reads rts_access_token from SecureStore
    ├─ true  → navigation.replace('Home')  // skip login
    └─ false → show login form
User taps Sign In → handleSignIn():
  getSmartComplyClient().login({ username, password, deviceType: 'APP' })
    → POST /api/user/login
    → on 200: store rts_access_token / rts_refresh_token / rts_user_id (SecureStore)
    → navigation.replace('Home')
    → on 401: throw AuthError → inline error "Invalid username or password."
```

**Silent refresh** (transparent to screens), in `SmartComplyClient.request()`:

```
Any authenticated request returns 401 (and not already a retry):
  _doRefresh():
    if a refresh is already in flight → queue this caller on _refreshQueue, await it
    else POST /api/user/refreshToken { refreshToken }
       ├─ 200 → store new tokens, resolve every queued caller with new token
       └─ non-2xx → _clearTokens(), return null
  if new token → replay the original request once (isRetry = true)
  else → throw AuthError → app routes to login
```

Logout (`HomeScreen.handleLogout`) calls `logout()` → deletes the three
SecureStore keys → `navigation.replace('Auth')`. No server call.

---

## (b) Full sales session: setup → active → summary → submit

### b1. Home → SessionSetup

`HomeScreen` loads today's appointments **cache-first**: read
`getCachedAppointments()` immediately, then refresh from the network via
`AuditProCrmProvider.getTodayAppointments()` (→ `getMyAssignments`), then
`cacheAppointments()`. Network failure with cache present → offline badge
(no error). Tapping a card → `SessionSetup` with an `AppointmentBrief`;
"New Walk-In" → `SessionSetup` with no params.

### b2. SessionSetup → start the session

**File:** `screens/SessionSetupScreen.tsx` → `handleStartSession()`.

```
Resolve ids: checksheetId = appointment?.checksheetId ?? 2001 (DEFAULT_CHECKSHEET_ID)
             assignmentId  = appointment?.assignmentId  ?? 1   (WALKIN_ASSIGNMENT_ID)

1. Cache-first checksheet fetch:
   repo.getCachedTemplate(checksheetId)
   try client.getChecksheetDetail(checksheetId)   // POST /api/checksheet/getChecksheetDetail
     ├─ network ok   → repo.cacheTemplate(...); use fresh ChecksheetDTO
     ├─ network fail + cache → Alert "Offline Mode"; use cached
     └─ network fail + no cache → throw → Alert "No network and no cached template"
2. loadRtsV1CuePack()                              // cue-packs/loader.ts → CuePackEntry[]
3. Resolve make/model/trim display names           // catalog/loader.ts
4. getSessionEngine().startSession(assignmentId, customer, vehicle, checksheet, cuePack)
5. navigation.replace('ActiveSession', { sessionId })
```

Inside `SessionEngine.startSession()`:

```
client.startOrResumeSession({ auditAssignmentId: assignmentId, status: 'IN_PROGRESS', ... })
  → POST /api/userChecksheet/createOrUpdate → returns UserChecksheetDTO (its .id = smartComplyUserChecksheetId)
new ChecklistEngine().init(checksheet, cuePack)   // builds cueId→questions / question→requiredCues maps
hold Session in an in-memory Map; emit to subscribers
```

### b3. ActiveSession: live checklist + voice cue capture

**File:** `screens/ActiveSessionScreen.tsx` (mount effect). See flow (d) for the
voice plumbing detail.

```
engine.getSession(sessionId)                        // load (or alert "Session Not Found")
engine.onSessionUpdate(cb)                           // re-render on checklist changes
voice.start({ language: 'en-US', customVocabulary: [] })
voice.onTranscript(event):
   if event.stability === 'final':
     detection = transcriptToCueDetection(event)     // v1 bridge → cue_id "workflow.transcript" (DC66)
     engine.processCueDetection(sessionId, detection)
voice.onStateChange → setMicState   |   voice.onError → Alert
start a 1s session timer
```

`ChecklistEngine.processCueDetection()` maps the cue id to question ids, appends
the `DetectedCue` (deduped), recomputes question status (`pending → partial →
complete`) and step completeness, and emits — the screen re-renders the step
cards, "heard:" snippets, and the Features Confirmed panel.

**Manual override:** tapping "Mark Complete" → `engine.overrideQuestion(sessionId,
questionId, 'manual override')` → status `overridden` (never downgraded).

**Mic toggle:** `voice.mute()` / `voice.unmute()`.

### b4. End session → submit to SmartComply

`handleEndSession()` (confirm dialog) → `engine.endSession(sessionId)`:

```
status active → ending (endedAt set) → emit
if smartComplyUserChecksheetId:
   try client.submitSession(ucId)                    // POST .../createOrUpdate { id, status: 'SUBMITTED' }
   catch → console.warn (queued for retry; see offline queue)
status ending → ended → emit
navigation.replace('SessionSummary', { sessionId })
```

On unmount the screen unsubscribes all listeners and calls `voice.stop()`.

### b5. SessionSummary + offline queue / retry

**File:** `screens/SessionSummaryScreen.tsx`.

```
repo.getSession(sessionId)                            // read full session from SQLite
if !readOnly: repo.getPendingWrites() filtered to this session → pending banner
"Sync to SmartComply" (handleSync):
   for each pending write: client.submitAnswers(JSON.parse(payload))
      ├─ ok   → repo.markWriteSucceeded(id)   (deletes row)
      └─ fail → repo.incrementWriteRetry(id)
   Alert "Sync Complete" or "Partial Sync — will retry automatically"
"Share Report" → expo-print PDF (generateHtmlReport) → expo-sharing
   fallback → plain-text file via expo-file-system
```

**Background retry queue** (independent of this screen),
`db/RetryQueueConsumer.ts`:

```
useRetryQueueConsumer() (mounted in App.tsx):
  drainPendingWrites() on mount AND on every AppState 'active'
drainPendingWrites():
  for each queued/failed write:
    if retryCount >= 5 → markWriteFailed (circuit breaker), skip
    wait backoff[min(retryCount,4)] = [1s,2s,4s,8s,16s]
    dispatchWrite() by payload.type (submitAnswers | submitSession)
      ├─ ok   → markWriteSucceeded
      └─ fail → incrementWriteRetry
```

The session data lives in SQLite regardless of network state, so nothing is lost
if every retry fails.

### Crash recovery

On app start `RootNavigator.useEffect` calls `getActiveSessions()`. If a session
is still `active`, it prompts **Resume** (navigate to `ActiveSession`) or
**Discard** (`updateSession({ status: 'crashed' })`).

---

## (c) Trade-in: photo capture + background upload

**File:** `screens/TradeInScreen.tsx`. Reached from the ActiveSession footer
(`navigation.navigate('TradeIn', { sessionId })`).

```
Mount: useCameraPermissions(); subscribe voice.onTranscript → collect last 3
       finals into a rolling "Voice Notes" buffer (DetectedCue[]).
Tap a slot (7 slots) → request camera permission if needed → open CameraView modal
Capture → cameraRef.takePictureAsync({ quality: 0.7 }):
   setPhotos({ ...prev, [slotId]: uri })
   persistTradeIn(photos, voiceSnippets, note)         // repo.updateSession({ tradeIn })
   uploadPhotoInBackground(sessionId, apiSlot, uri):    // fire-and-forget, not awaited
      ucId = getSessionEngine().getSession(sid).smartComplyUserChecksheetId
      client.uploadTradePhoto(ucId, apiSlot, uri, 'image/jpeg')
         → POST /api/rts/tradePhoto/upload (multipart: file, userChecksheetId, slot)
         → failure: console.warn only (photo + state already saved locally)
Condition note: debounced 500ms persist via repo.updateSession.
Done / unmount → final persistTradeIn flush.
```

Display slot ids are mapped to the 7 API `TradePhotoSlot` enum values via the
`SLOTS` table (DC68). Trade-in state is read back in SessionSummary.

---

## (d) Voice capture: JS → native → transcript events

**Files:** `voice/NativeVoiceModule.ts`, `ios/VoiceModule/RtsVoiceModule.swift`,
`android/.../voice/RtsVoiceModule.kt`. Facade: [`api.md`](./api.md) §2.

```
Screen: voice = getVoiceEngine()
        voice.start({ language, customVocabulary })

NativeVoiceModule.start():
   if isMocked (no native module) → state = 'listening', emit nothing
   else state = 'starting'; RtsVoiceModule.startListening(language, vocab)

── iOS (RtsVoiceModule.swift) ───────────────────────────────
   SFSpeechRecognizer(locale), requiresOnDeviceRecognition = true
   AVAudioEngine mic tap → recognitionRequest.append(buffer)  (skipped while muted)
   recognitionTask callback → emit "onTranscriptEvent"
        { text, stability: isFinal?'final':'partial', timestamp_ms,
          latency_ms_from_audio_start, confidence, engine_metadata:{engine:'apple_speech_transcriber'} }
   emit "onVoiceStateChange" = 'listening'

── Android (RtsVoiceModule.kt) ──────────────────────────────
   startForegroundService() (mic foreground service)
   AudioRecord @16kHz → Silero VAD → per speech segment:
        OfflineRecognizer (Whisper small.en int8).decode → text
   emit "onTranscriptEvent" { stability:'final', engine_metadata:{engine:'sherpa_onnx_vad'} }
   emit "onVoiceStateChange" = 'listening'

── Back in JS ───────────────────────────────────────────────
NativeVoiceModule's NativeEventEmitter listeners fan events out to:
   onTranscript listeners  → screens (ActiveSession forwards finals to the engine;
                              TradeIn buffers finals as voice notes)
   onStateChange listeners → mic UI state
   onError listeners       → Alert

Mute/unmute: voice.mute()/unmute() → native drops/resumes frames → state events.
Teardown: voice.stop() → native cancels recognition (Android also stops the
          foreground service) → state 'stopped'.
```

> The emitted `TranscriptEvent` matches the `voice-engine` contract — see
> [`../../voice-engine/docs/model-contracts.md`](../../voice-engine/docs/model-contracts.md).
> In v1, `ActiveSessionScreen.transcriptToCueDetection()` is a placeholder bridge
> that tags every final with `cue_id: "workflow.transcript"` (DC66); real
> phrase→cue matching is a follow-up.

---

## Cross-references

- App architecture: [`architecture.md`](./architecture.md)
- API surfaces: [`api.md`](./api.md)
- Full backend contract: [`smartcomply-contract.md`](./smartcomply-contract.md)
- System docs (up): [`../../docs/flows.md`](../../docs/flows.md) (cross-module flows), [`../../docs/architecture.md`](../../docs/architecture.md). Deeper dives:
  [`../../docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md`](../../docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md),
  [`../../docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md`](../../docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md)
- Voice contract (across): [`../../voice-engine/docs/model-contracts.md`](../../voice-engine/docs/model-contracts.md)
</content>
