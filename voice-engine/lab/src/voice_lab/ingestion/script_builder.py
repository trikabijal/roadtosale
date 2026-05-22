"""Convert raw YouTube transcript segments into our script YAML schema.

The job:
1. Load the universal workflow cue pack and (optionally) the feature cues
   the target trim covers. These give us a phrase index keyed by
   `(cue_id, category)`.
2. Walk the transcript segments in time order. For each segment, run the
   phrase index against the segment text; record (cue_id, category,
   approx_ms_within_segment) for every hit.
3. Use the matched cues' categories to assign each transcript segment to
   one of the NADA workflow steps. We assume rep dialogue moves
   monotonically through greet -> discovery -> ... -> finance; once we
   leave a step we don't re-enter it. The first segment defaults to
   `greet`.
4. Group adjacent same-step segments into a script `segment` block,
   concatenate their text, and roll up their detected cues into
   `expected_cues` with timestamps relative to the segment-block start.

Step detection is best-effort. The output marks every step its cue set
implies; unmatched segments are appended to whatever step is currently
"open" so we don't drop dialogue.

This is pure data transformation — no YAML writing happens here. The CLI
writes the dict to disk.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

import yaml

from voice_lab.ingestion.youtube import TranscriptSegment

# NADA workflow step ordering. Matches the `step` field in script-001/002
# and the `category` field in `universal_workflow_cues.yaml`.
STEP_ORDER: tuple[str, ...] = (
    "greet",
    "discovery",
    "vehicle_match",
    "walkaround",
    "test_drive",
    "trial_close",
    "trade",
    "pencil",
    "manager_to",
    "buyers_order",
    "finance",
    "delivery",
    "follow_up",
)

# Feature-cue categories map to the walkaround step by default — feature
# callouts ("Honda Sensing 360+", "wireless CarPlay") almost always live
# in the rep's walkaround narration.
_FEATURE_CUE_DEFAULT_STEP = "walkaround"

_PUNCT_RE = re.compile(r"[^\w\s]+")
_WS_RE = re.compile(r"\s+")


def _normalize(text: str) -> str:
    return _WS_RE.sub(" ", _PUNCT_RE.sub(" ", text.lower())).strip()


@dataclass(frozen=True)
class CueIndexEntry:
    """One phrase from one cue, indexed for substring matching."""

    cue_id: str
    category: str
    original_phrase: str
    normalized: str


@dataclass
class CueHit:
    """One detection inside one transcript segment."""

    cue_id: str
    category: str
    matched_phrase: str
    segment_start_seconds: float


@dataclass
class BuiltScript:
    """A script ready to be written to YAML."""

    script_id: str
    target_trim_id: str
    title: str
    source: dict[str, Any]
    segments: list[dict[str, Any]] = field(default_factory=list)
    negative_cues: list[str] = field(default_factory=list)

    def to_yaml_dict(self) -> dict[str, Any]:
        return {
            "id": self.script_id,
            "title": self.title,
            "target_trim_id": self.target_trim_id,
            "source": self.source,
            "segments": self.segments,
            "negative_cues": self.negative_cues,
        }


def load_cue_index(
    workflow_cues_path: Path,
    feature_cue_paths: Iterable[Path] = (),
) -> list[CueIndexEntry]:
    """Build a phrase index across workflow + feature cue YAMLs.

    Workflow cues carry their own `category` field. Feature cues do not —
    they get mapped to `_FEATURE_CUE_DEFAULT_STEP` (walkaround) because
    that's where rep dialogue normally calls them out.
    """
    entries: list[CueIndexEntry] = []

    with workflow_cues_path.open("r", encoding="utf-8") as f:
        workflow_doc = yaml.safe_load(f) or {}
    for cue in workflow_doc.get("cues", []) or []:
        cue_id = cue.get("id")
        category = cue.get("category", "")
        if not cue_id:
            continue
        for phrase in (cue.get("cue_phrases") or []) + (cue.get("synonyms") or []):
            norm = _normalize(str(phrase))
            if norm:
                entries.append(
                    CueIndexEntry(
                        cue_id=cue_id,
                        category=category,
                        original_phrase=str(phrase),
                        normalized=norm,
                    )
                )

    for feature_path in feature_cue_paths:
        with feature_path.open("r", encoding="utf-8") as f:
            doc = yaml.safe_load(f) or {}
        cue_id = doc.get("id")
        if not cue_id:
            continue
        category = _FEATURE_CUE_DEFAULT_STEP
        for phrase in (doc.get("cue_phrases") or []) + (doc.get("synonyms") or []):
            norm = _normalize(str(phrase))
            if norm:
                entries.append(
                    CueIndexEntry(
                        cue_id=cue_id,
                        category=category,
                        original_phrase=str(phrase),
                        normalized=norm,
                    )
                )

    # Longer phrases first so we prefer the longest match per atom.
    entries.sort(key=lambda e: len(e.normalized), reverse=True)
    return entries


def match_segment_cues(
    segment_text: str,
    cue_index: list[CueIndexEntry],
    segment_start_seconds: float,
) -> list[CueHit]:
    """Return one hit per distinct cue_id matched in this segment."""
    haystack = _normalize(segment_text)
    if not haystack:
        return []
    seen: set[str] = set()
    hits: list[CueHit] = []
    for entry in cue_index:
        if entry.cue_id in seen:
            continue
        if entry.normalized in haystack:
            seen.add(entry.cue_id)
            hits.append(
                CueHit(
                    cue_id=entry.cue_id,
                    category=entry.category,
                    matched_phrase=entry.original_phrase,
                    segment_start_seconds=segment_start_seconds,
                )
            )
    return hits


def detect_step_boundaries(
    transcript: list[TranscriptSegment],
    cue_index: list[CueIndexEntry],
) -> list[tuple[int, str, list[CueHit]]]:
    """Assign every transcript segment to a workflow step.

    Returns one tuple per transcript segment: (segment_index, step,
    matched_hits). Step assignment is monotonic — once we leave a step
    we don't go back. Segments with no cue hits inherit the current step.
    The first segment defaults to `greet` if no cue matches.
    """
    if not transcript:
        return []

    step_rank = {step: i for i, step in enumerate(STEP_ORDER)}
    current_step = "greet"
    current_rank = 0
    out: list[tuple[int, str, list[CueHit]]] = []

    for idx, snippet in enumerate(transcript):
        hits = match_segment_cues(snippet.text, cue_index, snippet.start_seconds)
        # Of the matched cue categories, pick the latest one that is at or
        # after the current step. If a segment matches both `walkaround`
        # and `finance`, we move forward to `finance` for this segment.
        candidate_step = current_step
        candidate_rank = current_rank
        for hit in hits:
            r = step_rank.get(hit.category)
            if r is None:
                continue
            if r > candidate_rank:
                candidate_step = hit.category
                candidate_rank = r
        current_step = candidate_step
        current_rank = candidate_rank
        out.append((idx, current_step, hits))

    return out


def build_script(
    *,
    transcript: list[TranscriptSegment],
    cue_index: list[CueIndexEntry],
    script_id: str,
    target_trim_id: str,
    title: str,
    source: dict[str, Any],
) -> BuiltScript:
    """Combine transcript + cue index + metadata into a `BuiltScript`.

    The function never fails on a missing transcript — it produces a
    script with zero segments if `transcript` is empty. Callers decide
    whether to write that to disk.
    """
    assignments = detect_step_boundaries(transcript, cue_index)

    # Group consecutive same-step assignments into segment blocks.
    blocks: list[dict[str, Any]] = []
    current_block_step: str | None = None
    current_block_start: float = 0.0
    current_block_text_parts: list[str] = []
    current_block_hits: list[CueHit] = []

    def flush_block() -> None:
        if current_block_step is None:
            return
        text = " ".join(p.strip() for p in current_block_text_parts if p.strip())
        # Dedupe cue hits within the block by cue_id, keep earliest hit.
        seen_ids: dict[str, CueHit] = {}
        for hit in current_block_hits:
            if hit.cue_id not in seen_ids:
                seen_ids[hit.cue_id] = hit
        expected_cues: list[dict[str, Any]] = []
        for hit in seen_ids.values():
            approx_ms = max(
                0,
                int(round((hit.segment_start_seconds - current_block_start) * 1000)),
            )
            expected_cues.append({"cue_id": hit.cue_id, "approx_ms": approx_ms})
        # Stable order: by approx_ms.
        expected_cues.sort(key=lambda c: c["approx_ms"])
        blocks.append(
            {
                "step": current_block_step,
                "text": text,
                "expected_cues": expected_cues,
            }
        )

    for idx, step, hits in assignments:
        snippet = transcript[idx]
        if step != current_block_step:
            flush_block()
            current_block_step = step
            current_block_start = snippet.start_seconds
            current_block_text_parts = [snippet.text]
            current_block_hits = list(hits)
        else:
            current_block_text_parts.append(snippet.text)
            current_block_hits.extend(hits)

    flush_block()

    return BuiltScript(
        script_id=script_id,
        target_trim_id=target_trim_id,
        title=title,
        source=source,
        segments=blocks,
        negative_cues=[],
    )
