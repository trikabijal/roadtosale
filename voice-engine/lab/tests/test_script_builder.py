"""Tests for the transcript-to-script converter.

Focus areas:
- Cue index loads workflow + feature cues with the right category mapping.
- Step boundaries advance monotonically as the rep's dialogue moves
  through the NADA workflow.
- The built script's segments are grouped by step, with cue timestamps
  relative to the segment's start.
- The output dict shape matches the existing seed scripts
  (script-001 / script-002).
"""

from __future__ import annotations

from pathlib import Path

import yaml

from voice_lab.ingestion.script_builder import (
    BuiltScript,
    build_script,
    detect_step_boundaries,
    load_cue_index,
    match_segment_cues,
)
from voice_lab.ingestion.youtube import TranscriptSegment


_LAB_ROOT = Path(__file__).resolve().parents[1]
_WORKFLOW_CUES = _LAB_ROOT / "cue-packs" / "universal_workflow_cues.yaml"
_CATALOG_FEATURES = _LAB_ROOT.parent.parent / "vehicle-feature-catalog" / "data" / "features"
_SEED_SCRIPT_001 = _LAB_ROOT / "fixtures" / "scripts" / "script-001-crv-hybrid-walkaround.yaml"


def _feature_paths() -> list[Path]:
    if not _CATALOG_FEATURES.exists():
        return []
    paths: list[Path] = []
    for sub in _CATALOG_FEATURES.iterdir():
        if sub.is_dir():
            paths.extend(sorted(sub.glob("*.yaml")))
    return paths


def test_load_cue_index_includes_workflow_and_feature_cues():
    feature_paths = _feature_paths()
    entries = load_cue_index(_WORKFLOW_CUES, feature_paths)
    assert entries, "expected the index to contain at least workflow cues"

    cue_ids = {e.cue_id for e in entries}
    # A handful of well-known cue ids from each source must be present.
    assert "workflow.hospitality_offer" in cue_ids
    assert "workflow.finance_introduction" in cue_ids
    if feature_paths:
        assert any(cid.startswith("honda.feature.") for cid in cue_ids)
        assert any(cid.startswith("universal.feature.") for cid in cue_ids)

    # Workflow cues retain their declared category.
    hospitality = next(e for e in entries if e.cue_id == "workflow.hospitality_offer")
    assert hospitality.category == "greet"
    # Feature cues are mapped to the walkaround step by default.
    feature_entries = [e for e in entries if e.cue_id.endswith("wireless_apple_carplay")]
    if feature_entries:
        assert all(e.category == "walkaround" for e in feature_entries)


def test_match_segment_cues_fires_each_cue_once():
    entries = load_cue_index(_WORKFLOW_CUES, [])
    hits = match_segment_cues(
        "Hi Sarah, my name is Marcus and can I grab you a coffee or water?",
        entries,
        segment_start_seconds=12.5,
    )
    hit_ids = {h.cue_id for h in hits}
    assert "workflow.self_introduction" in hit_ids
    assert "workflow.hospitality_offer" in hit_ids
    # Each cue fires exactly once even if multiple phrases under it match.
    assert len(hits) == len(hit_ids)
    for h in hits:
        assert h.segment_start_seconds == 12.5


def test_detect_step_boundaries_advances_through_workflow():
    entries = load_cue_index(_WORKFLOW_CUES, [])
    transcript = [
        TranscriptSegment("Hi Sarah, welcome to Riverside Honda.", 0.0, 3.0),
        TranscriptSegment("Can I grab you a coffee or water?", 4.0, 2.0),
        TranscriptSegment("So how will you use it — school runs?", 8.0, 3.0),
        TranscriptSegment("Highway commute most mornings, yeah?", 12.0, 3.0),
        TranscriptSegment("Let me show you around the trim.", 20.0, 3.0),
        TranscriptSegment("Let me introduce you to David Chen.", 40.0, 3.0),
    ]
    assignments = detect_step_boundaries(transcript, entries)
    steps = [step for _, step, _ in assignments]
    # First two segments fire greet cues (welcome / coffee).
    assert steps[0] == "greet"
    assert steps[1] == "greet"
    # Discovery cues kick in on segments 2-3.
    assert steps[2] == "discovery"
    assert steps[3] == "discovery"
    # "Let me show you" is the walkaround cue.
    assert steps[4] == "walkaround"
    # "Let me introduce you to" with finance manager handoff lives in finance category.
    assert steps[5] in ("finance", "manager_to")


def test_detect_step_boundaries_does_not_regress():
    """Monotonicity: once we hit a later step we don't fall back to an earlier one."""
    entries = load_cue_index(_WORKFLOW_CUES, [])
    transcript = [
        TranscriptSegment("Let me introduce you to David Chen.", 0.0, 2.0),
        # Then the rep says "coffee" - should NOT regress to greet.
        TranscriptSegment("Would you like a coffee or water?", 4.0, 2.0),
    ]
    assignments = detect_step_boundaries(transcript, entries)
    steps = [s for _, s, _ in assignments]
    # First step should be finance (or manager_to), second must not go below that.
    assert steps[0] in ("finance", "manager_to")
    assert steps[1] in ("finance", "manager_to")


def test_build_script_groups_consecutive_same_step_segments():
    entries = load_cue_index(_WORKFLOW_CUES, [])
    transcript = [
        TranscriptSegment("Hi Sarah, welcome to Riverside Honda.", 0.0, 3.0),
        TranscriptSegment("Can I grab you a coffee or water?", 4.0, 2.0),
        TranscriptSegment("So tell me about school runs.", 10.0, 3.0),
        TranscriptSegment("And highway commute too?", 14.0, 2.0),
    ]
    script = build_script(
        transcript=transcript,
        cue_index=entries,
        script_id="youtube_test",
        target_trim_id="honda.cr-v-hybrid-awd.2026.sport-touring",
        title="Test",
        source={
            "type": "youtube_transcript",
            "url": "https://www.youtube.com/watch?v=test0000001",
            "video_id": "test0000001",
            "fetched_at": "2026-05-22T00:00:00Z",
            "fair_use_note": "Test.",
        },
    )
    # First two snippets share `greet`; next two share `discovery` -> 2 segments.
    assert len(script.segments) == 2
    assert script.segments[0]["step"] == "greet"
    assert script.segments[1]["step"] == "discovery"
    assert "welcome" in script.segments[0]["text"].lower()
    assert "school runs" in script.segments[1]["text"].lower()


def test_build_script_cue_timestamps_relative_to_segment_start():
    entries = load_cue_index(_WORKFLOW_CUES, [])
    transcript = [
        # greet starts at 10.0
        TranscriptSegment("Hi Sarah, welcome to Riverside Honda.", 10.0, 3.0),
        TranscriptSegment("Coffee or water?", 14.0, 2.0),
    ]
    script = build_script(
        transcript=transcript,
        cue_index=entries,
        script_id="youtube_ts_test",
        target_trim_id="honda.cr-v-hybrid-awd.2026.sport-touring",
        title="Test",
        source={
            "type": "youtube_transcript",
            "url": "https://www.youtube.com/watch?v=ts000000001",
            "video_id": "ts000000001",
            "fetched_at": "2026-05-22T00:00:00Z",
            "fair_use_note": "Test.",
        },
    )
    seg = script.segments[0]
    assert seg["step"] == "greet"
    # All expected_cues approx_ms values are non-negative.
    for cue in seg["expected_cues"]:
        assert cue["approx_ms"] >= 0
    # The hospitality cue fires off the second snippet — 14.0s - 10.0s = 4000ms.
    hosp = next(
        (c for c in seg["expected_cues"] if c["cue_id"] == "workflow.hospitality_offer"),
        None,
    )
    if hosp is not None:
        assert hosp["approx_ms"] == 4000


def test_built_script_yaml_dict_matches_seed_script_shape():
    """The auto-generated YAML must share the top-level shape with script-001."""
    entries = load_cue_index(_WORKFLOW_CUES, [])
    transcript = [
        TranscriptSegment("Hi Sarah, welcome to Riverside Honda.", 0.0, 3.0),
        TranscriptSegment("Coffee or water?", 4.0, 2.0),
    ]
    script = build_script(
        transcript=transcript,
        cue_index=entries,
        script_id="youtube_shape_test",
        target_trim_id="honda.cr-v-hybrid-awd.2026.sport-touring",
        title="Shape test",
        source={
            "type": "youtube_transcript",
            "url": "https://www.youtube.com/watch?v=shape0000001",
            "video_id": "shape0000001",
            "fetched_at": "2026-05-22T00:00:00Z",
            "fair_use_note": "Test.",
        },
    )
    built = script.to_yaml_dict()
    seed = yaml.safe_load(_SEED_SCRIPT_001.read_text())

    # Top-level keys we promise the orchestrator will be present.
    required_top_keys = {"id", "title", "target_trim_id", "source", "segments", "negative_cues"}
    assert required_top_keys.issubset(built.keys())
    assert required_top_keys.issubset(seed.keys())

    # Each segment has the same shape: step, text, expected_cues.
    for seg in built["segments"]:
        assert {"step", "text", "expected_cues"}.issubset(seg.keys())
        for cue in seg["expected_cues"]:
            assert {"cue_id", "approx_ms"}.issubset(cue.keys())


def test_empty_transcript_produces_empty_segments():
    script = build_script(
        transcript=[],
        cue_index=[],
        script_id="youtube_empty",
        target_trim_id="honda.cr-v-hybrid-awd.2026.sport-touring",
        title="Empty",
        source={
            "type": "youtube_transcript",
            "url": "https://www.youtube.com/watch?v=empty0000001",
            "video_id": "empty0000001",
            "fetched_at": "2026-05-22T00:00:00Z",
            "fair_use_note": "Test.",
        },
    )
    assert isinstance(script, BuiltScript)
    assert script.segments == []
    assert script.negative_cues == []
