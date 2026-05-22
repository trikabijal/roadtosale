"""Cue matcher — phrase + synonym substring matching.

Same algorithm as the TS implementation (see voice-engine/src/matcher/cue-matcher.ts).
Case-insensitive. Lightly normalized whitespace and punctuation.
One CueDetection per matched cue per triggering event (multiple cues in
one event each get their own detection; a single cue matched twice in one
event produces one detection — the longest match wins).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable, Iterator, List

from voice_lab.types import CueAtom, CueDetection, TranscriptEvent


_PUNCT_RE = re.compile(r"[^\w\s]+")
_WS_RE = re.compile(r"\s+")


def _normalize(text: str) -> str:
    lowered = text.lower()
    no_punct = _PUNCT_RE.sub(" ", lowered)
    return _WS_RE.sub(" ", no_punct).strip()


@dataclass
class _IndexedPhrase:
    atom_id: str
    original_phrase: str
    normalized: str


class CueMatcher:
    def __init__(self, atoms: List[CueAtom]) -> None:
        self._atoms = atoms
        self._index: list[_IndexedPhrase] = []
        for atom in atoms:
            for phrase in atom.cue_phrases:
                norm = _normalize(phrase)
                if norm:
                    self._index.append(_IndexedPhrase(atom.id, phrase, norm))
            for syn in atom.synonyms:
                norm = _normalize(syn)
                if norm:
                    self._index.append(_IndexedPhrase(atom.id, syn, norm))
        # Longer phrases first so we prefer the longest match within an atom.
        self._index.sort(key=lambda p: len(p.normalized), reverse=True)

    def match(self, events: Iterable[TranscriptEvent]) -> Iterator[CueDetection]:
        for event in events:
            yield from self._match_event(event)

    def _match_event(self, event: TranscriptEvent) -> Iterator[CueDetection]:
        haystack = _normalize(event.text)
        if not haystack:
            return
        seen_atoms: set[str] = set()
        for entry in self._index:
            if entry.atom_id in seen_atoms:
                continue
            if entry.normalized in haystack:
                seen_atoms.add(entry.atom_id)
                yield CueDetection(
                    cue_id=entry.atom_id,
                    matched_phrase=entry.original_phrase,
                    timestamp_ms=event.timestamp_ms,
                    confidence=event.confidence,
                    triggering_event=event,
                )


def match_cues(
    events: Iterable[TranscriptEvent], cue_atoms: List[CueAtom]
) -> Iterator[CueDetection]:
    return CueMatcher(cue_atoms).match(events)
