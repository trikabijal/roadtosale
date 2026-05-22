# Voice Engine Lab — Script Fixtures

Hand-written dealer dialogue scripts used as input to the voice-engine lab. Each script is
a sequence of `segments` (greet, discovery, walkaround, test_drive, trade, finance, etc.)
with the rep's spoken text and the `expected_cues` that should fire when that segment is
transcribed and matched.

## Files

| File | Purpose |
|---|---|
| `script-001-crv-hybrid-walkaround.yaml` | Full positive-path walkaround of the 2026 CR-V Hybrid AWD Sport Touring. Exercises every workflow cue category and the trim's full feature surface. |
| `script-002-crv-hybrid-negative.yaml` | Negative control — same trim, but the walkaround deliberately omits three features (`heated_front_seats`, `ventilated_front_seats`, `bose_premium_audio_honda`). Used to prove the cue matcher does not hallucinate cues for features that exist on the trim but were not mentioned. |

## Schema

```
id: <script_id>
title: <human readable>
target_trim_id: <vehicle-feature-catalog trim id>
source:
  type: hand_written | youtube | other
  author: <free text>
segments:
  - step: <greet | discovery | vehicle_match | front_line_ready | walkaround | test_drive | trade | finance>
    text: <multi-line rep dialogue>
    expected_cues:
      - { cue_id: <cue id>, approx_ms: <int ms offset within segment audio> }
negative_cues:
  - <cue_id>  # cues that MUST NOT fire when this script is rendered
```

## Conventions

- `cue_id` references either a workflow cue from `voice-engine/lab/cue-packs/universal_workflow_cues.yaml`
  or a feature cue from `vehicle-feature-catalog/data/features/...`. The lab orchestrator
  resolves both namespaces.
- `approx_ms` is the rep's expected mark within the segment's rendered audio. It's a guide
  for scoring, not an exact frame-accurate target.
- The dialogue cast (`Sarah Mitchell`, `Marcus`, `David Chen`, `Riverside Honda`) is the
  established demo cast — keep it consistent across scripts.

## Adding a new script

1. Pick a `target_trim_id` that exists in the catalog.
2. Only reference features the trim actually has (check `data/matrix/<make>.yaml`).
3. Pull workflow cue ids from the universal cue pack — do not invent new ids inline.
4. Write the dialogue in natural dealer voice, not transcript shorthand.
5. Add the file under `voice-engine/lab/fixtures/scripts/` and reference it from the lab CLI.
