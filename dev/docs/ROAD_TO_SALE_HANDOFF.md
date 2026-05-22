# Road to Sale by AuditPro Handoff

## Purpose

This document is the working handoff for the current project state. It summarizes what has been built, how the demo is structured, what still needs polish, and how we plan to validate the audio-driven parts before any dealership pilot.

The current product name in the demo assets is `Road to Sale by AuditPro`. `AuditPro` is the parent auditing product; `Road to Sale` is the dealership sales-floor use case.

## What We Have Built

### Core product direction

We are building a dealership-facing sales-process coaching and audit product for the U.S. market.

The goal is not a generic CRM or checklist app. The product should:
- guide the salesperson through the Road to the Sale
- listen for spoken cues and infer intent
- mark steps green when completed
- mark missed items red for coaching
- feed management analytics and interventions in a BI dashboard

### Existing React Native app (demo / showcase only)

The original React Native showcase app lives in `demo/auditpro-rn-showcase/`. It is demo material — scripted audio, no real STT — and is not the production Road to Sale app. The real product code lives in `voice-engine/` and `road-to-sale-app/`.

Key files (in `demo/auditpro-rn-showcase/`):
- `App.tsx`
- `src/navigation/RootNavigator.tsx`
- `src/services/composition.ts`
- `src/store/deal.ts`
- `src/data/auditItems.ts`
- `src/screens/*`

Architecture notes:
- provider-driven service composition
- scripted/local implementations for showcase mode
- local SQLite/in-memory backend
- Zustand store writes through to backend
- audio/camera/CV/GPS are abstracted behind interfaces

### Demo flow in the HTML prototype

The demo prototype lives in `demo/html-prototype/RoadToSale_Mobile.html`.

This file has been modified into a guided, recordable click-through demo with:
- CRM-fed appointment queue
- dealership-style discovery prompts
- vehicle recommendation screen
- model-specific feature loading
- separate parked-feature and in-drive-feature coaching
- trade-in photo capture with condition analysis
- proposal, buyer’s order, F&I, and final coaching summary

The prototype now uses the product title `Road to Sale by AuditPro` in the setup and app header.

### BI dashboard mock

The manager-facing dashboard mock lives in `demo/html-prototype/RoadToSale_BI.html`.

That dashboard is intended to show:
- active deals
- compliance gaps
- steps most missed
- rep-by-step compliance
- coaching priorities
- manager intervention opportunities

### Video production packet

To support a narrated demo video, a handoff packet was created under:
- `demo/video-packet/VIDEO_BRIEF.md`
- `demo/video-packet/VOICEOVER_SCRIPT.md`
- `demo/video-packet/SCENE_BY_SCENE_DEMO.md`
- `demo/video-packet/INTERACTION_SCRIPT.json`
- `demo/video-packet/TIMING_SHEET.csv`

The packet is meant to be handed to another AI or video workflow to generate a click-through product demo with voiceover.

### Audio test matrix

The audio validation plan now lives in:
- `dev/docs/ROAD_TO_SALE_AUDIO_TEST_MATRIX.md`

It defines the concrete experiment matrix for validating intent capture across:
- multiple script variants
- accent styles
- background noise levels
- phone placement conditions
- the fixed set of dealership cue phrases we care about

### Audio architecture document

The architecture decision record now lives in:
- `docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md`

It captures:
- the base architecture
- the strategy-pattern approach for speech-related implementations
- why we are separating audio capture from downstream speech understanding
- why we are separating fast live output from slower audit-grade evidence
- why telemetry is a first-class part of the system
- the recommended implementation sequence before the audio test matrix is run

### Road to Sale PRD

The functional PRD now lives in:
- `docs/ROAD_TO_SALE_PRD.md`

It captures:
- the product goals
- the NADA-driven workflow scope
- the AuditPro / SmartComply compatibility intent
- the core user flows
- the conceptual mapping from Road to Sale artifacts into the parent audit model

### Road to Sale technical architecture

The high-level system architecture now lives in:
- `docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md`

It captures:
- the split between Road to Sale frontend, reusable voice engine, Road to Sale backend, and AuditPro / SmartComply backend
- why the voice engine should remain reusable
- why Road to Sale should write on top of the parent audit backend rather than bypassing it
- the high-level system data flow and domain boundaries

### Audio learnings document

The source-driven learnings companion now lives in:
- `dev/docs/ROAD_TO_SALE_AUDIO_LEARNINGS.md`

It captures:
- what adjacent teams and products ran into
- how they solved those problems
- what those learnings imply for Road to Sale
- why we are using a strategy-based architecture instead of one hardcoded speech path

### Audio telemetry document

The telemetry plan now lives in:
- `dev/docs/ROAD_TO_SALE_AUDIO_TELEMETRY.md`

It captures:
- what to measure in each audio layer
- how to think about latency budgets
- what end-to-end metrics matter
- how to compare strategies across platforms and devices
- what engineering dashboards and slices we need from day one

### Audio landscape document

The market and technology landscape map now lives in:
- `dev/docs/ROAD_TO_SALE_AUDIO_LANDSCAPE.md`

It captures:
- the major companies and technologies by layer
- where Apple, Google, Argmax, Deepgram, Gladia, AssemblyAI, Speechmatics, NVIDIA Parakeet, Whisper, and related tooling sit
- which layers we likely own versus buy
- which players are most relevant for immediate evaluation

## Current Demo Story

The current demo narrative is:

1. A rep starts from a CRM-fed appointment queue.
2. The app listens for greet/hospitality cues.
3. Discovery questions capture customer needs in dealership language.
4. The app loads Honda feature knowledge and recommends a matching vehicle.
5. Static features are coached during walkaround and turn green/red.
6. Drive-time features are coached separately and turn green/red.
7. Trade-in photos feed image-based condition analysis.
8. Proposal and buyer’s order steps are logged.
9. The rep gets a coaching summary.
10. The same signals roll into the manager dashboard for broader coaching.

The demo customer currently used in the assets is:
- `Sarah Mitchell`
- trade-in: `2019 Toyota RAV4`
- recommended vehicle: `Honda CR-V Hybrid AWD`

## Current Audio Assumptions

We are not assuming perfect literal transcription.

The intended model is:
- detect intent reliably
- show the transcript for trust and visibility
- use a small set of phrase-triggered cues
- allow quick manual override when confidence is low

The product should survive normal dealership noise and speaking variability without requiring perfect word-for-word transcription.

The currently recommended architecture is:
- one orchestrator
- one mobile capture layer
- one strategy-based speech layer
- one fast live cue lane for rep-facing feedback
- one slower evidence lane for audit-grade transcript and event storage
- telemetry from day one

At the broader system level, we now explicitly separate:
- Road to Sale frontend
- reusable voice engine
- Road to Sale backend
- AuditPro / SmartComply backend

That recommendation is explained in detail in:
- `docs/ROAD_TO_SALE_PRD.md`
- `docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md`
- `docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md`
- `dev/docs/ROAD_TO_SALE_AUDIO_LEARNINGS.md`
- `dev/docs/ROAD_TO_SALE_AUDIO_TELEMETRY.md`
- `dev/docs/ROAD_TO_SALE_AUDIO_LANDSCAPE.md`

## Demo Prototype Status

The HTML prototype is already partially structured for guided playback:
- there is a manual interactive path
- there is a `Play Guided Demo` autoplay path
- it preloads image assets
- the front-line-ready screen waits long enough for imagery to appear
- trade-in photos now show one consistent vehicle across four angles
- the later flow now has extra coaching callouts on the test-drive, pencil, and F&I screens so the back half feels as intentional as the front half

There are still likely refinements needed after visual review in a browser, especially around:
- typography consistency
- spacing and wrapping on smaller screens
- timing alignment for the autoplay path
- later-screen polish on proposal, buyer’s order, and final summary

## Testing Plan

### Testing goal

The goal is to validate whether audio intent capture is strong enough for noisy dealership conditions before relying on a pilot.

### What we are testing

We are not only testing raw speech-to-text quality. We are testing whether the system can reliably detect dealership intent cues such as:
- hospitality offers
- discovery needs
- vehicle logic / recommendation language
- parked feature demonstrations
- live driving feature explanations
- trade-in condition mentions
- manager handoff language

### Test strategy

#### Layer 1: controlled local testing

Run the audio flow in a normal room before any dealership access.

Conditions:
- quiet room
- background chatter
- music or HVAC-like noise
- speaker playback instead of live speech
- microphone near the phone
- microphone farther away
- phone on desk vs in hand

#### Layer 2: script variants

Use multiple versions of the same intent, not one fixed script.

Planned variation matrix:
- 5 to 10 script variants
- Southern, Midwest, Northeast, neutral American, and other common speaking styles
- different speaking speeds
- slightly different phrasing for the same intent

#### Layer 3: cue coverage

Each script variant should exercise a few core cues:
- greet / refreshment
- discovery
- vehicle logic
- parked feature demo
- drive-time feature demo
- trade-in note
- manager introduction

#### Layer 4: scoring

For each run, record:
- did the correct intent fire
- did it fire quickly enough
- did it miss only the exact phrase but still catch the intent
- did it false-trigger on unrelated speech
- did manual override work fast enough

### Success criteria

We do not need perfect transcription.

We need:
- high confidence on intent capture
- reliable detection on the 8 to 10 most important cues
- graceful fallback when confidence is low
- no brittle dependence on a perfect script

### Why this is enough

The user experience should be driven by intent detection plus transcript visibility.
If the app can consistently detect the dealership process cue, then exact wording can vary and the product still works.

## Recommended Next Steps

1. Open `demo/html-prototype/RoadToSale_Mobile.html` in a browser and visually review the current guided flow.
2. Tighten any remaining typography, spacing, and timing issues in the HTML prototype.
3. Finalize the voiceover and timing for the recorded demo.
4. Read `docs/ROAD_TO_SALE_PRD.md`.
5. Read `docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md`.
6. Read `docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md` before building the real voice pipeline.
7. Read `dev/docs/ROAD_TO_SALE_AUDIO_LEARNINGS.md` if you need the source-driven rationale behind the architecture choices.
8. Read `dev/docs/ROAD_TO_SALE_AUDIO_TELEMETRY.md` before instrumenting the first real pipeline.
9. Read `dev/docs/ROAD_TO_SALE_AUDIO_LANDSCAPE.md` when evaluating providers and platform-specific paths.
10. Build the voice pipeline skeleton first:
   - mobile capture
   - strategy interfaces
   - rolling buffer
   - speech-activity detection
   - streaming transcription integration
   - fast live cue events
   - evidence transcript persistence
   - telemetry
11. Define the Road to Sale backend to AuditPro / SmartComply write model before deep implementation.
12. Only after that, run the audio test matrix in `dev/docs/ROAD_TO_SALE_AUDIO_TEST_MATRIX.md` with 5 to 10 script variants and multiple accent styles.
13. Keep the trade-in imagery unless a single-source CR-V image set becomes available and clearly improves realism.

## Notes for the Next Agent

The important context to preserve is:
- this is a dealership sales-floor product, not a generic demo app
- CRM-fed appointments are expected
- the app should look like it understands the store’s product line
- audio should be intent-driven, not transcript-perfect
- the mobile demo and BI dashboard are separate but connected stories
- the prototype is meant to support both live demoing and narrated video production

If you continue work tomorrow, start by reviewing:
- `docs/ROAD_TO_SALE_PRD.md`
- `docs/ROAD_TO_SALE_TECHNICAL_ARCHITECTURE.md`
- `demo/html-prototype/RoadToSale_Mobile.html`
- `demo/video-packet/VOICEOVER_SCRIPT.md`
- `demo/video-packet/SCENE_BY_SCENE_DEMO.md`
- `docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md`
- `dev/docs/ROAD_TO_SALE_AUDIO_LEARNINGS.md`
- `dev/docs/ROAD_TO_SALE_AUDIO_TELEMETRY.md`
- `dev/docs/ROAD_TO_SALE_AUDIO_LANDSCAPE.md`
- `dev/docs/ROAD_TO_SALE_AUDIO_TEST_MATRIX.md`
- `demo/html-prototype/RoadToSale_BI.html`
