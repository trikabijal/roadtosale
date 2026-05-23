"""Combined cue matcher — exact phrase layer + semantic embedding layer.

Exact matching runs on ALL events (both partial and final).
  → Fast, zero false positives, fires as early as possible.
  → This is what catches cues the moment the dealer says the right words.

Semantic matching runs on FINAL events only, for cues exact didn't catch.
  → Slower, catches paraphrases ("engine shuts off at lights" → idle-stop).
  → Only fires for cues that the exact layer missed — exact always wins.

Output: a single sorted stream of CueDetection objects, each tagged with
match_method="exact" or match_method="semantic" so downstream reporting
can show which layer caught what.
"""

from __future__ import annotations

from typing import Iterable, Iterator

from voice_lab.matcher.cue_matcher import CueMatcher
from voice_lab.matcher.semantic_matcher import SemanticMatcher
from voice_lab.types import CueAtom, CueDetection, TranscriptEvent


class CombinedMatcher:
    def __init__(
        self,
        atoms: list[CueAtom],
        *,
        semantic_threshold: float = 0.55,
        model_name: str = "BAAI/bge-small-en-v1.5",
    ) -> None:
        self._exact = CueMatcher(atoms)
        self._semantic = SemanticMatcher(
            atoms, threshold=semantic_threshold, model_name=model_name
        )

    @property
    def semantic_threshold(self) -> float:
        return self._semantic.threshold

    def match(self, events: Iterable[TranscriptEvent]) -> Iterator[CueDetection]:
        events_list = list(events)

        # ── Exact layer: all events ──────────────────────────────────────────
        exact_detections = list(self._exact.match(events_list))

        # Track which cue_ids the exact layer already fired. Semantic will
        # skip these — the first (earliest) detection wins.
        exact_fired: set[str] = {d.cue_id for d in exact_detections}

        # ── Semantic layer: final events only, skipping exact hits ───────────
        semantic_detections = list(
            self._semantic.match(events_list, skip_cue_ids=exact_fired)
        )

        # ── Merge and sort by timestamp ──────────────────────────────────────
        all_detections = exact_detections + semantic_detections
        all_detections.sort(key=lambda d: d.timestamp_ms)

        return iter(all_detections)


def match_cues_combined(
    events: Iterable[TranscriptEvent],
    cue_atoms: list[CueAtom],
    *,
    semantic_threshold: float = 0.55,
) -> Iterator[CueDetection]:
    """Convenience function matching the signature of match_cues()."""
    return CombinedMatcher(cue_atoms, semantic_threshold=semantic_threshold).match(events)
