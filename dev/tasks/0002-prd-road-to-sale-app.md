# PRD 0002 — Road to Sale App (Mobile v1)

Last updated: `2026-05-24`
Status: draft v1

Related docs:
- `docs/ROAD_TO_SALE_PRD.md` — top-level product PRD
- `docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md` — system-level architecture
- `docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md` — audio architecture
- `dev/tasks/0001-prd-voice-engine.md` — voice engine + catalog PRD (complete)
- `dev/tasks/decisions-log.md` — running decisions log

Upstream modules this PRD builds on (already shipped):
- `vehicle-feature-catalog/` — brand-extensible vehicle catalog, Honda 2026 full lineup
- `voice-engine/` — STT strategy library (Apple SpeechTranscriber iOS, SherpaOnnx Android)
- `voice-engine/lab/` — lab results: Apple 8.6% FNR showroom / TTFC P95 330ms

---

## 1. Introduction / Overview

`Road to Sale by AuditPro` is a mobile-first dealership sales-floor coaching and audit product. A salesperson carries their phone during a live customer conversation. The app listens, detects which NADA workflow steps and vehicle features have been covered, lights up the checklist in real time, and writes structured audit evidence back to AuditPro / SmartComply.

This PRD covers the first shippable mobile app — the product the rep actually holds on the showroom floor.

The voice engine, vehicle catalog, and AuditPro backend are pre-existing. This PRD is about wiring them together into a usable product: the session lifecycle, the rep-facing UI, always-on audio, trade-in capture, and the post-session coaching view.

---

## 2. Goals

| # | Goal | Measurable outcome |
|---|---|---|
| G1 | Rep can start a session from a CRM appointment or walk-in within 60 seconds | Session start to active listening in ≤ 60 s on a warm app |
| G2 | NADA checklist updates in real time as the rep speaks | Cue detected → step turns green within 2 s on iOS, 3 s on Android |
| G3 | Rep covers more steps with the app than without (measurable at first pilot) | Step completion rate increases vs baseline (establish baseline at pilot) |
| G4 | Audit evidence lands in AuditPro for every completed session | 100% of sessions that reach "summary" have a corresponding AuditPro record |
| G5 | Rep completes session summary in under 2 minutes | Timed at pilot |
| G6 | App does not feel like surveillance — rep accepts always-on audio | Rep opt-in, visible mic indicator at all times, no raw audio stored server-side |

---

## 3. User Stories

### Salesperson
- As a rep, I want to see my appointments for today when I open the app, so I can tap one and start without typing.
- As a rep starting a walk-in, I want to enter the customer's name and pick the vehicle we're looking at, so the app knows which features to listen for.
- As a rep on the showroom floor, I want the checklist to update itself as I talk, so I can keep my attention on the customer instead of the phone.
- As a rep doing the walkaround, I want to see which features I haven't mentioned yet, so I can fill the gap before we head back inside.
- As a rep inspecting a trade-in, I want to take photos and speak my condition notes, so I don't have to type anything mid-inspection.
- As a rep finishing up, I want a one-page summary of what I covered and what I missed, so I can self-coach between customers.

### Sales Manager (via existing AuditPro BI — no new UI in this PRD)
- As a manager, I want rep session data to appear in the AuditPro dashboard, so I can see completion trends and coaching gaps without a new tool.

---

## 4. Functional Requirements

### 4.1 Authentication

1. The app authenticates using existing AuditPro / SmartComply credentials (username + password, or SSO if AuditPro supports it).
2. Auth tokens are refreshed silently. Reps are not re-prompted during an active session.
3. The app supports a single active logged-in rep per device.

### 4.2 Home Screen — Appointments and Walk-In

4. On launch, the home screen shows today's CRM appointments fetched from the AuditPro API (`AppointmentProvider` interface — see §7.2).
5. Each appointment card shows: customer name, time, and vehicle shortlist (make / model) if available from the CRM.
6. A "New Walk-In" button is always visible. It opens the customer + vehicle setup flow.
7. If the AuditPro API is unreachable, the home screen shows a cached list (last successful fetch) with a stale-data badge. Walk-in path is always available offline.

### 4.3 Session Setup — Customer and Vehicle

8. For a walk-in: the rep enters the customer's first name (required) and optionally last name and phone number.
9. For an appointment: customer details are pre-populated from the CRM appointment. Rep can edit before confirming.
10. Vehicle selection: rep picks Make → Model → Trim from the vehicle catalog (same data as `vehicle-feature-catalog/`). The app ships the catalog bundled at build time (not server-fetched at runtime).
11. Once Make/Model/Trim is selected, the app displays the feature list for that trim so the rep can confirm it matches the actual vehicle on the lot.
12. The rep confirms and the session begins.

### 4.4 Active Session — Live Audio and Checklist

13. When the session starts, the microphone activates automatically. A persistent mic-on indicator (waveform or pulsing dot) is visible at all times while audio is active.
14. The rep can mute the mic at any time (e.g., during a private finance conversation). Muted state is clearly visible. Unmute restores detection immediately.
15. The NADA workflow checklist is **driven by the SmartComply Audit Template** for the Road to Sale inspection type. The app fetches the audit template from the SmartComply API at session start and caches it locally. The template defines the formal audit questions — the app does not hardcode them. The cached template is used offline if the API is unreachable.

16. The audit template is identified by a fixed template ID (e.g., `road-to-sale-v1`). This template ID serves as the audit type — no separate `audit_type` field is required from SmartComply. The NADA 10-step structure is the expected template content:
    - Greet / Hospitality
    - Discovery
    - Vehicle Match / Recommendation
    - Front-Line Ready
    - Walkaround
    - Test Drive
    - Trade Appraisal
    - Proposal / Pencil
    - F&I Handoff
    - Completion

17. **Template question → rep-friendly display:** Each audit template question (e.g., "Did the salesperson greet the customer within 30 seconds of arrival?") is displayed in the Road to Sale UI in its audit question form. The app renders the question text as the checklist item label. The SmartComply Audit Template is the source of truth for question text — Road to Sale does not maintain its own label text.

18. **Template question → cue atom binding:** Each template question is associated with one or more voice engine cue atoms. A cue detection event auto-answers the question (marks it green). This binding is defined in a Road to Sale-specific cue pack YAML (extending the existing `voice-engine/lab/cue-packs/`). Each cue atom entry carries a `template_question_id` field referencing the SmartComply template question it answers. The binding is a Road to Sale concern — the SmartComply schema does not carry it.

    Example: Template question `"Was a detailed customer requirement taken?"` → bound to cue atoms `workflow.discovery_budget_signal`, `workflow.discovery_highway_commute`, `workflow.discovery_school_runs`, `workflow.discovery_towing_cargo`, `workflow.discovery_better_mileage`. Any one of these cues being detected marks the question as answered; all detected = fully covered.

19. **Rich sub-panels for multi-cue questions:** For steps with multiple bound cues (e.g., Discovery, Walkaround), the checklist item expands to show a sub-panel listing each cue with its detection snippet. This gives the rep visibility into *what* was captured for that step, not just *whether* it was covered.

20. A step (audit template question group) is marked fully complete when all required cues within it are detected. Optional cues do not block step completion.

21. The current "active" step is highlighted. Tapping a step lets the rep manually mark it complete (override) or add a note.

22. A feature coverage panel (collapsible) shows all trim features and which have been mentioned. This updates in real time from the same cue detection stream.

23. The voice engine runs on-device (Apple SpeechTranscriber on iOS, SherpaOnnx VAD mode on Android). No audio is sent to an external STT server. Raw audio is never stored or uploaded.

24. Transcript snippets shown in the UI are display-only. They are stored locally as audit evidence but are not uploaded verbatim to SmartComply (only the structured cue events and snippets are).

### 4.5 Trade-In Capture

22. During an active session, the rep can tap "Trade-In" to enter the trade-in capture flow without ending the session. The mic stays on.
23. Trade-in flow: the rep takes required photos (exterior four corners, interior, odometer, VIN). The camera guide shows which shots are still needed.
24. While in the trade-in flow, the voice engine continues listening. Spoken condition notes (dents, tire wear, mechanical issues) are detected as trade-cue events and shown as a live transcript.
25. The rep can supplement spoken notes with a typed note at any time.
26. Completed trade-in photos and spoken condition evidence are attached to the session and included in the AuditPro write.

### 4.6 Session End and Summary

27. The rep taps "End Session" to close the active audio session. The app confirms (one confirmation dialog — avoids accidental ends).
28. The post-session summary screen shows:
    - **Covered**: steps and cues that were detected, each with a transcript snippet
    - **Missed**: steps and cues with no detection, flagged as coaching opportunities
    - **Override log**: anything the rep manually confirmed
    - **Trade-in**: photo count and spoken condition summary
29. The rep can add a free-text note to the session before finalising.
30. The summary is written to AuditPro as a structured audit record (see §4.7). This write happens automatically on session end; the rep does not need to manually "submit."
31. The summary screen shows a confirmation once the AuditPro write succeeds. If the write fails (offline), the app queues it and retries silently. A badge on the home screen shows pending syncs.
32. The rep can share/export the summary as a PDF (for email to the customer or manager).

### 4.7 SmartComply Write

33. Every completed session produces one SmartComply audit/inspection record. Mapping: Road to Sale session → SmartComply inspection instance (identified by audit ID); NADA step → SmartComply checklist item; cue detection → SmartComply evidence item within that checklist item.
34. The SmartComply schema used is the canonical SmartComply schema, imported into this repo as the `smartcomply/` deployable. Road to Sale operates its own instance of this database. The mobile app calls this self-hosted SmartComply API.
35. Road to Sale-specific fields (voice cue source, transcript snippet, cue confidence, trade-in photos) are added as schema extensions directly to the owned SmartComply instance. When the extensions are stable, they are proposed back to the SmartComply team as upstream contributions. No third-party approval is required to ship v1.
36. The SmartComply write includes: session metadata, step outcomes (complete / missed / overridden), cue events with timestamps and transcript snippets, trade-in photo references, and the free-text session note.
37. The app authenticates against the self-hosted SmartComply instance using AuditPro / SmartComply credentials. No separate service account.
38. The write contract (request/response shape, error codes, retry policy, schema extensions) is defined in `road-to-sale-app/docs/smartcomply-contract.md` (to be authored alongside implementation).

### 4.8 CRM Integration Contract

37. CRM appointment data is fetched via a `CrmAppointmentProvider` interface, not hard-coded to any CRM.
38. The interface returns: appointment ID, scheduled time, customer name, customer contact, vehicle shortlist (make / model / year, optional trim), and rep ID.
39. v1 ships one concrete implementation: `AuditProCrmProvider` — reads appointments from the AuditPro API, which already abstracts the underlying CRM. The app does not call any CRM API directly.
40. The interface is defined in `road-to-sale-app/src/crm/types.ts`. Future CRM adapters (CDK, VinSolutions, DealerSocket) implement this interface without touching any other app code.

---

## 5. Non-Goals (Out of Scope for v1)

| Non-goal | Rationale |
|---|---|
| Manager dashboard / supervisor UI | Managers use the existing AuditPro BI dashboard. Events land there automatically. |
| Proposal / pencil / deal structuring | Finance workflow is a future module. F&I handoff is a cue-detection step only in v1. |
| Multi-vehicle comparison within a session | One active vehicle per session in v1. |
| Direct CRM API calls (CDK, VinSolutions, etc.) | `AuditProCrmProvider` covers v1. Direct CRM adapters follow customer discovery. |
| Dealer inventory sync (live lot data) | Catalog ships bundled. Inventory filtering is a future feature. |
| Custom STT vocabulary per dealer | Engine uses the bundled cue pack. Per-dealer vocabulary tuning is post-v1. |
| Cloud STT (Deepgram, Gladia) | Dealership Wi-Fi reliability risk. On-device only in v1. |
| Video recording | Audio only. Camera is trade-in photos only. |
| Raw audio storage or replay | Privacy design principle. Only structured events and snippets stored. |
| Offline-first with full conflict resolution | Sessions require connectivity for AuditPro write. Retry queue handles transient drops. |
| CarPlay / Android Auto integration | Deferred. |

---

## 6. Design Considerations

### Screen inventory
1. **Auth** — Login (AuditPro credentials)
2. **Home** — Today's appointments + New Walk-In CTA
3. **Session Setup** — Customer info + vehicle picker (Make → Model → Trim)
4. **Active Session** — NADA checklist + mic indicator + feature panel + Trade-In entry point
5. **Trade-In** — Camera guide + spoken notes transcript
6. **Session Summary** — Covered / Missed / Override log + AuditPro write status
7. **History** — Past sessions list with tap-to-view summary

### UX principles
- The rep should be able to operate the app with one thumb. Nothing critical requires two hands.
- The checklist is the primary surface. Everything else is secondary.
- Mic status (on/muted) must be impossible to miss. Green = active, red = muted.
- The app must not feel like it's watching the rep — it's a tool helping them, not auditing them.
- Dark mode support from day one (showroom lighting varies).

### Existing design assets
- BI / manager view designs exist in the design folder (`demo/` or equivalent). Reference for data model alignment.
- The `demo/auditpro-rn-showcase/` is a UI prototype only — not product code. Reference for visual style.

---

## 7. Technical Considerations

### 7.1 Stack

| Layer | Choice | Rationale |
|---|---|---|
| Mobile framework | React Native (Expo managed → bare if needed) | Cross-platform from one codebase. iOS ships first. |
| Voice engine bridge | Native module wrapping `voice-engine/ios/` (Swift) on iOS and `voice-engine/native/android/` (Kotlin) on Android | Re-uses the validated STT strategies. Same `TranscriptEvent` JSONL contract. |
| Voice engine JS layer | `voice-engine/src/` TypeScript library | Existing facade. RN consumes it directly. |
| Vehicle catalog | `vehicle-feature-catalog/src/ts/` bundled at build time | Catalog ships with the app. No runtime fetch. JSON compiled from YAML at build. |
| SmartComply backend | Self-hosted SmartComply instance (`smartcomply/` deployable in this repo) | Road to Sale owns and operates its own SmartComply instance. Schema extensions are made here first, then proposed back to the SmartComply team. The mobile app calls this instance's API. |
| SmartComply schema extensions | Applied directly to the owned instance | Road to Sale-specific fields (cue events, transcript snippets, trade-in photos) extend the SmartComply schema in the owned instance. No waiting for third-party approval. |
| Local persistence | SQLite (via expo-sqlite or react-native-sqlite-storage) | Session data survives crashes. Pending SmartComply writes queued here. |
| SmartComply API client | REST + JWT, defined in `road-to-sale-app/src/api/smartcomply.ts` | Calls the self-hosted SmartComply instance. |
| CRM contract | `CrmAppointmentProvider` interface + `AuditProCrmProvider` implementation | See §4.8 |
| Camera | `expo-camera` or `react-native-vision-camera` | Trade-in photo capture |
| PDF export | `react-native-pdf-lib` or equivalent | Session summary export |

### 7.2 CrmAppointmentProvider interface (normative)

```typescript
// road-to-sale-app/src/crm/types.ts

export interface Appointment {
  id: string;
  scheduledAt: Date;
  customer: {
    firstName: string;
    lastName?: string;
    phone?: string;
    email?: string;
  };
  vehicleShortlist?: Array<{
    make: string;
    model: string;
    year: number;
    trim?: string;
  }>;
  repId: string;
  storeId: string;
  source: string; // e.g. "auditpro", "cdk", "vinsolutions"
}

export interface CrmAppointmentProvider {
  getTodayAppointments(storeId: string): Promise<Appointment[]>;
  getAppointment(id: string): Promise<Appointment>;
}
```

### 7.3 Audio permission model

- iOS: `NSMicrophoneUsageDescription` in Info.plist. Permission requested at session start, not at app launch.
- Android: `RECORD_AUDIO` permission. Requested at session start.
- Background audio: iOS `UIBackgroundModes: audio`. Android foreground service with a persistent notification showing mic-active status.
- The app must handle permission denied gracefully: show a clear prompt to the rep explaining why audio is needed, with a link to system settings.

### 7.4 Voice engine integration

The React Native app bridges to the native voice engine as follows:

```
[RN JS layer]  ←  voice-engine/src/  ←  NativeModule  ←  Swift (iOS) / Kotlin (Android)
                                                          ↓
                                                Apple SpeechTranscriber (iOS)
                                                SherpaOnnx VAD (Android)
```

The native module emits `TranscriptEvent` objects to JS via the RN event emitter. The JS voice engine facade (`VoiceEngine`) is already written — the native module is the missing bridge.

### 7.5 SmartComply schema alignment

The Road to Sale session data maps onto the SmartComply audit/inspection schema as follows:

| Road to Sale concept | SmartComply concept |
|---|---|
| Session | Inspection instance (identified by audit ID) |
| NADA step | Checklist item (from audit template) |
| Cue detection event | Evidence item on a checklist item |
| Audit template `road-to-sale-v1` | Audit type (template ID serves as type proxy) |
| Trade-in photos | Evidence attachments (binary or CDN URL — see OQ7) |
| Rep override + note | Manual checklist item completion + notes field |

Road to Sale-specific data not natively supported by the base SmartComply schema (cue source, transcript snippet, cue confidence) is added as schema extensions directly to the owned `smartcomply/` instance. The `RtsDataProvider` interface is not needed — the owned instance is the backend, and the schema is fully under Road to Sale's control.

### 7.6 SmartComply write contract

The SmartComply write schema (request/response shape, error codes, retry policy, `RtsDataProvider` interface definition) must be documented in `road-to-sale-app/docs/smartcomply-contract.md` before implementation. This is a task in the generated task list, not a pre-condition.

### 7.7 Audit template fetch and caching

The NADA checklist is driven by the SmartComply Audit Template, fetched at session start. Caching strategy:
- Template is fetched once per app session (not per customer session) and held in memory.
- Template is also persisted to SQLite keyed by template ID + version/ETag.
- On session start: use cached template immediately (fast path), then revalidate in background.
- If no cache and API unreachable: show error and block session start (template is required to render the checklist).

### 7.8 Catalog bundling

The YAML catalog is compiled to JSON at build time via a build script. The RN app loads the JSON bundle at startup. The catalog is never fetched at runtime. A catalog update requires an app release.

---

## 8. Success Metrics (Pilot)

| Metric | Target | Why |
|---|---|---|
| Session start time | ≤ 60 s (appointment tap to mic active) | Friction kills adoption |
| Cue-to-green latency | ≤ 2 s iOS, ≤ 3 s Android | Trust metric — rep must see it working |
| Step completion rate | Measurable increase vs pre-app baseline | Core coaching value |
| AuditPro write success rate | ≥ 99% within 60 s of session end | Data quality |
| Override rate | < 15% of completed steps | High override = engine not trusted or UI confusing |
| Session summary completion | ≥ 80% of sessions that start reach "End Session" | Rep doesn't abandon mid-session |
| Pilot rep NPS | > 7 / 10 | Product must feel useful, not like surveillance |

---

## 9. Open Questions

| # | Question | Impact | Owner |
|---|---|---|---|
| OQ1 | What is the SmartComply Audit Template API endpoint + schema? (fetch by template ID, response shape for steps and checklist items) | Blocks 4.4 — template-driven checklist | SmartComply team |
| OQ2 | What is the SmartComply inspection create/update API endpoint + write schema? (create inspection from template, write step outcomes, write evidence items) | Blocks 4.7 implementation | SmartComply team |
| OQ3 | Does AuditPro already surface CRM appointments, or does the `AuditProCrmProvider` need to be built server-side? | Blocks 4.2 | AuditPro team |
| OQ4 | What is the minimum iOS version supported? (SpeechTranscriber is iOS 17+; iOS 26 has improved APIs) | Platform minimum declaration | Product |
| OQ5 | Which trade-in photos are required vs optional? Is there a fixed shot list from NADA or dealer policy? | Blocks trade-in flow design | Product / NADA alignment |
| OQ6 | Is the vehicle catalog update flow app-release-only, or should dealers be able to push catalog updates OTA (e.g., via CodePush)? | Architecture of catalog bundling | Product |
| OQ7 | Does the base SmartComply schema support binary photo attachments on evidence items, or do trade photos need a new field? Road to Sale adds the field directly in their owned instance if not present. | Determines schema extension scope for trade-in photos | Road to Sale team (own instance) |
| OQ8 | For the F&I handoff step — is detection of the verbal handoff sufficient, or must the app launch an F&I-specific flow? | Scope of proposal/pencil deferral | Product |
