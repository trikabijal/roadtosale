# Road to Sale Audio Learnings

## Purpose

This document collects the external learnings that informed the `Road to Sale by AuditPro` audio architecture.

It exists so the main architecture document can stay short and implementation-oriented.

Primary architecture doc:
- `docs/ROAD_TO_SALE_AUDIO_ARCHITECTURE.md`

Last updated:
- `2026-05-22`

Important:
- This space is evolving quickly.
- Every entry below should be read with both the `source publication date` and the `date we incorporated the learning` in mind.
- Future updates should append or revise learnings with explicit dates rather than silently replacing prior assumptions.

## Core Pattern Across Sources

The strongest repeated pattern across the useful sources is:

- separate fast live output from slower stable output
- separate audio capture from speech understanding
- allow multiple engine choices underneath one product layer
- treat summarization as a separate concern from transcription
- instrument the system so architectural choices can be tested rather than guessed

## Source-by-Source Learnings

### Salesforce Engineering

Source:
- https://engineering.salesforce.com/how-salesforces-new-speech-to-text-service-uses-openai-whisper-models-for-real-time-transcriptions/

Source publication date:
- `2025-05-20`

Incorporated into our architecture thinking:
- `2026-05-22`

What problem they hit:
- real-time STT becomes weak if latency is too high
- high-quality models are not automatically real-time

How they addressed it:
- chunked audio
- partial results first
- final results later
- moving windows instead of waiting for the whole recording

What we learn:
- do not build around a single final transcript
- separate fast and stable outputs

### Argmax / WhisperKit real-time transcription

Source:
- https://app.argmaxinc.com/docs/examples/real-time-transcription

Source publication date:
- `Undated page; checked 2026-05-22`

Incorporated into our architecture thinking:
- `2026-05-22`

What problem they hit:
- one output stream forces a speed vs stability tradeoff

How they addressed it:
- `Hypothesis` text for preliminary output
- `Confirmed` text for stable output

What we learn:
- the product should explicitly model unstable and stable text separately

### Argmax Pro SDK 2

Source:
- https://www.argmaxinc.com/blog/argmax-sdk-2

Source publication date:
- `2026-04-07`

Incorporated into our architecture thinking:
- `2026-05-22`

What this adds:
- real-time STT across iOS and Android
- speaker attribution in real-time mode
- custom vocabulary at a meaningful scale

What we learn:
- a vendor like Argmax can plausibly reduce how much low-level speech engineering we need to build
- but it still does not replace our cue logic, scoring, audit evidence model, or BI event model

What it implies:
- Argmax is a serious `TranscriptionStrategy` candidate
- it should be evaluated through our normalized strategy layer, not hardwired into the whole product

### 92three mobile/offline AI writeup

Source:
- https://www.ninetwothree.co/blog/offline-ai-for-mobile

Source publication date:
- `2026-04-23`

Incorporated into our architecture thinking:
- `2026-05-22`

What problem they hit:
- different engines solve different problems
- built-in recognizers are not always good for continuous speech
- some models are fast but low quality
- some are better but too heavy or too slow

What we learn:
- one engine will not automatically satisfy live UX, quality, and portability at once
- architecture should leave room for platform-specific choices

### Mobile STT implementation references

Sources:
- https://github.com/software-mansion-labs/expo-stt-blog
- https://github.com/mybigday/whisper.rn
- https://github.com/Picovoice/react-native-voice-processor

Source publication date:
- `GitHub repositories; checked 2026-05-22`

Incorporated into our architecture thinking:
- `2026-05-22`

What they show:
- mobile STT needs explicit chunking and buffering
- speech boundaries and silence handling materially affect UX
- audio capture should be independent from downstream recognition

What we learn:
- audio capture is a first-class subsystem
- VAD / silence handling is not an implementation detail
- capture should feed multiple downstream consumers

### Newer iOS and Android model observations

Source:
- https://www.ninetwothree.co/blog/offline-ai-for-mobile

Source publication date:
- `2026-04-23`

Incorporated into our architecture thinking:
- `2026-05-22`

Key learnings:
- modern iOS may have a stronger native path than expected for real-time transcription
- Android remains more fragmented and may benefit more from a vendor path
- summarization should be treated separately from the live STT decision

What we learn:
- platform-specific strategies are likely the right design
- the app should not assume one engine across all devices forever

### pyannoteAI and async diarization

Sources:
- https://docs.pyannote.ai/tutorials/speech-to-text-diarization
- https://docs.pyannote.ai/models
- https://www.gladia.io/blog/gladia-async-api-for-meeting-transcription-integration-guide-and-best-practices

Source publication date:
- `pyannoteAI docs checked 2026-05-22`
- `Gladia async API article published last month; checked 2026-05-22`

Incorporated into our architecture thinking:
- `2026-05-22`

What problem this clarifies:
- accurate diarization is difficult to do reliably in the strict live path
- speaker attribution improves when the system has full-recording context

How this is addressed:
- use real-time transcription for live UX
- run fuller diarization asynchronously after the session ends
- reconcile transcript and speaker labels after more complete context is available

What we learn:
- diarization should not be forced into the first live rep-facing path unless a use case truly requires it
- a hybrid pattern is often more accurate:
  - live transcript now
  - async speaker attribution later

What it implies for Road to Sale:
- our current architecture is still right
- diarization, if needed, belongs naturally beside the evidence lane, not inside the fastest live lane

## Resulting Architectural Stance

Because of these learnings, the architecture should:

- use a strategy pattern for core audio and speech components
- keep a shared app-level orchestrator
- normalize outputs across engines
- collect telemetry from day one
- allow platform-specific defaults based on real evidence

## Why Telemetry Is Part Of The Architecture

Telemetry is not an afterthought here.

We need it because we expect to compare:
- iOS native vs vendor
- Android vendor A vs vendor B
- different cue packs
- different devices and OS versions

Without telemetry, we will not know:
- what actually has lower latency
- what produces fewer false triggers
- what works better in noisy conditions
- whether manual overrides are too frequent

## Primary Sources

- Salesforce Engineering:
  - https://engineering.salesforce.com/how-salesforces-new-speech-to-text-service-uses-openai-whisper-models-for-real-time-transcriptions/
- Argmax real-time transcription:
  - https://app.argmaxinc.com/docs/examples/real-time-transcription
- Argmax Pro SDK 2:
  - https://www.argmaxinc.com/blog/argmax-sdk-2
- 92three offline AI for mobile:
  - https://www.ninetwothree.co/blog/offline-ai-for-mobile
- Expo STT reference app:
  - https://github.com/software-mansion-labs/expo-stt-blog
- whisper.rn:
  - https://github.com/mybigday/whisper.rn
- Picovoice React Native Voice Processor:
  - https://github.com/Picovoice/react-native-voice-processor

## Update Rule

When this document is updated in the future:
- keep prior dated learnings unless they are clearly obsolete
- add new sources with publication date and incorporation date
- note when a prior conclusion has changed because the tooling landscape moved
