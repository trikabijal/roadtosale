# Road to Sale Audio Test Matrix

## Objective

Validate that the audio system reliably captures dealership intent cues under realistic speaking variation and background noise.

The purpose of this plan is not to prove perfect transcription. The purpose is to prove that AuditPro can consistently detect the cues that drive the Road to the Sale flow.

## What We Care About

We care about:
- intent capture
- cue detection
- timing to detection
- low-friction fallback when confidence is low

We do not care about:
- perfect word-for-word transcript fidelity
- full lexical accuracy on every sentence
- absolute silence in the room

## Core Intent Categories

The test scripts should cover these categories:
- greet and hospitality
- discovery and needs analysis
- vehicle recommendation logic
- parked feature demo
- live driving feature demo
- trade-in condition mention
- manager introduction / handoff

## Suggested Script Variants

Use 5 to 10 variants with the same intent but different wording.

### Script A
- Hospitality: “Before we head out, can I grab you a coffee or water?”
- Discovery: “Mostly school runs and highway commuting.”
- Recommendation: “That’s why I picked this one.”
- Parked feature: “Here’s wireless Apple CarPlay.”
- Drive feature: “Notice how Lane Assist is working right now.”
- Trade-in: “There’s a small dent on the rear quarter.”
- Handoff: “Let me introduce you to our finance manager.”

### Script B
- Hospitality: “Would you like some water or coffee before we take a look?”
- Discovery: “We’re in the car a lot during the week.”
- Recommendation: “This fits what you told me best.”
- Parked feature: “The power tailgate makes daily use easier.”
- Drive feature: “You can see the blind spot warning right now.”
- Trade-in: “The front tires are getting worn.”
- Handoff: “I’d like you to meet David, our finance manager.”

### Script C
- Hospitality: “Can I get you anything to drink before we go out?”
- Discovery: “It needs to work for kids and commuting.”
- Recommendation: “Because you mentioned those needs, this is the right fit.”
- Parked feature: “This cargo space is the reason it works.”
- Drive feature: “That lane assist is on now.”
- Trade-in: “I’m seeing a scratch on the rear quarter.”
- Handoff: “Let me walk you over to finance.”

### Script D
- Hospitality: “Water or coffee before we head outside?”
- Discovery: “We want something with better mileage than the RAV4.”
- Recommendation: “That’s why I’m showing you the CR-V Hybrid.”
- Parked feature: “This is the wireless phone connection.”
- Drive feature: “Right now you can feel how lane keeping helps.”
- Trade-in: “The front tires will need attention.”
- Handoff: “This is David Chen, our finance manager.”

### Script E
- Hospitality: “Let me grab you a drink before we start.”
- Discovery: “Mostly family driving with some highway.”
- Recommendation: “Because of your commute, this is the best match.”
- Parked feature: “The tailgate is one of the convenience features.”
- Drive feature: “Notice how the blind spot alert lights up right now.”
- Trade-in: “There’s a dent back here.”
- Handoff: “I want to introduce you to finance.”

### Additional Variants
Create 5 more variants by changing:
- sentence order
- filler words
- phrasing around the same intent
- use of contractions
- level of directness

## Accent and Delivery Matrix

Test each script with these delivery styles:
- neutral American
- Southern American
- Midwest American
- Northeast American
- fast speaker
- slightly mumbled speaker

If time is limited, prioritize:
1. neutral American
2. Midwest American
3. Southern American
4. Northeast American

## Noise Matrix

Test each script under these conditions:
- quiet room
- moderate background chatter
- louder chatter with a second voice in the room
- HVAC or traffic noise from a speaker
- phone on desk
- phone held in hand
- phone placed farther away than ideal

## Feature Cue Set

Use a fixed set of target cues so results are comparable across runs:
- coffee / water
- school runs
- highway commuting
- better mileage
- that’s why I picked this one
- wireless Apple CarPlay
- power tailgate
- Lane Assist right now
- blind spot warning right now
- small dent
- front tires
- finance manager introduction

## Scoring

Score each cue as one of:
- `pass`
- `partial`
- `fail`

Suggested outcome notes:
- `pass`: cue was detected and the correct step changed
- `partial`: intent was right but exact words were incomplete
- `fail`: wrong cue, no cue, or false trigger

## Recommended Run Order

1. Start with quiet-room baseline tests.
2. Repeat the same scripts in moderate noise.
3. Repeat only the worst-performing scripts in loud noise.
4. Compare accents against the same cue set.
5. Note which cues are fragile and which are stable.
6. Keep the product design intent-first, not transcript-first.

## Acceptance Threshold

For demo readiness, the system should:
- reliably capture the core sales-process intent
- fail gracefully when confidence is low
- allow fast manual confirmation
- remain usable in a noisy showroom-like room

If the app consistently detects the cue and marks the correct step, that is enough for the product direction.
