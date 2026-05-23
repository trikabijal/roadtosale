"""Semantic cue matcher — embedding-based paraphrase detection.

Solves the vocabulary mismatch problem: a dealer says "the engine cuts out at
red lights" and our exact matcher never fires because it only knows "Idle-Stop".
The embedding matcher catches this because both phrases land near each other
on the meaning map.

How it works
------------
Setup (once at init):
  For every CueAtom, build a "representative text" from its display_name,
  cue_phrases, and synonyms. Embed that text into a vector. Store the 286
  vectors (one per cue atom) as the "cue index".

At match time (per event):
  1. Embed the event text.
  2. Compute cosine similarity between the event embedding and every cue vector.
  3. For any cue whose similarity exceeds the threshold → yield a CueDetection
     with match_method="semantic".

Why finals only
---------------
Partial events are word fragments — "Honda sens..." or "the engine". They're
too short for reliable semantic judgement and would generate false positives.
This matcher therefore only processes stability=="final" events. Exact matching
(cue_matcher.py) handles the fast partial path.

Model
-----
BAAI/bge-small-en-v1.5 via fastembed (ONNX-backed, ~33 MB, no PyTorch).
Runs at ~10 ms per event on CPU; faster on Apple Neural Engine.
Embeddings are 384-dimensional unit vectors; cosine similarity is a dot product.

Threshold guidance (bge-small-en-v1.5 on short dealer utterances)
  0.70+  Very confident paraphrase — rarely wrong
  0.55   Good default — catches most real paraphrases, few false positives
  0.45   Permissive — use only when tuning recall; watch false positive rate
"""

from __future__ import annotations

import logging
from typing import Iterable, Iterator

import numpy as np

from voice_lab.types import CueAtom, CueDetection, TranscriptEvent

logger = logging.getLogger(__name__)

_DEFAULT_MODEL = "BAAI/bge-small-en-v1.5"
_DEFAULT_THRESHOLD = 0.55

# Module-level cache so the model is loaded once per Python process.
# Key: model_name. The model is large (~33 MB ONNX graph) and slow to load
# (~3–10 s), but fast at inference (<10 ms per sentence). Caching avoids
# paying the load cost for every script in a batch run.
_MODEL_CACHE: dict[str, "TextEmbedding"] = {}  # type: ignore[name-defined]


def _get_model(model_name: str) -> "TextEmbedding":  # type: ignore[name-defined]
    if model_name not in _MODEL_CACHE:
        try:
            from fastembed import TextEmbedding  # type: ignore
        except ImportError as exc:
            raise RuntimeError(
                "fastembed is required for semantic matching. "
                "Install it with: pip install fastembed"
            ) from exc
        logger.info("SemanticMatcher: loading model %s (first use) …", model_name)
        _MODEL_CACHE[model_name] = TextEmbedding(model_name=model_name)
    return _MODEL_CACHE[model_name]


def _build_cue_text(atom: CueAtom) -> str:
    """Produce the richest possible text representation of what this cue means.

    The display_name anchors the semantics. The cue_phrases and synonyms show
    the range of ways it can be expressed. The model averages across all of this
    when embedding, giving a centroid that is equidistant from all valid phrasings.
    """
    parts = [atom.display_name]
    if atom.cue_phrases:
        parts.append(". ".join(atom.cue_phrases))
    if atom.synonyms:
        parts.append(". ".join(atom.synonyms))
    return ". ".join(p.strip() for p in parts if p.strip())


class SemanticMatcher:
    """Embedding-based cue matcher. Call match() to get detections."""

    def __init__(
        self,
        atoms: list[CueAtom],
        *,
        model_name: str = _DEFAULT_MODEL,
        threshold: float = _DEFAULT_THRESHOLD,
    ) -> None:
        self._atoms = atoms
        self._threshold = threshold
        self._model_name = model_name

        # Use the module-level cache so the model is loaded only once per process.
        self._model = _get_model(model_name)

        # Pre-compute one embedding per cue atom and stack into a matrix.
        # Shape: (n_atoms, embedding_dim). Each row is already L2-normalised
        # by fastembed, so cosine similarity = dot product.
        cue_texts = [_build_cue_text(a) for a in atoms]
        embeddings = list(self._model.embed(cue_texts))
        self._cue_matrix = np.stack(embeddings).astype(np.float32)  # (N, D)
        logger.info(
            "SemanticMatcher: indexed %d cue atoms, embedding dim=%d",
            len(atoms),
            self._cue_matrix.shape[1],
        )

    @property
    def threshold(self) -> float:
        return self._threshold

    def match(
        self,
        events: Iterable[TranscriptEvent],
        *,
        skip_cue_ids: set[str] | None = None,
    ) -> Iterator[CueDetection]:
        """Yield semantic detections for final events.

        skip_cue_ids: cue IDs already fired by the exact matcher for this
        audio file. Those are suppressed here — exact match wins.
        """
        skip = skip_cue_ids or set()

        for event in events:
            # Only run on completed utterances — partials are too short for
            # reliable semantic judgement.
            if event.stability != "final":
                continue
            if not event.text.strip():
                continue

            # Embed the event text. fastembed returns a generator; take first.
            event_vec = np.array(
                next(iter(self._model.embed([event.text]))), dtype=np.float32
            )  # shape: (D,)

            # Cosine similarities: dot product with pre-normalised cue matrix.
            sims = self._cue_matrix @ event_vec  # shape: (N,)

            seen_atoms: set[str] = set()
            # Iterate in descending similarity so the best match per atom wins.
            for idx in np.argsort(sims)[::-1]:
                score = float(sims[idx])
                if score < self._threshold:
                    break  # sorted descending — everything below is also below threshold
                atom = self._atoms[idx]
                if atom.id in seen_atoms or atom.id in skip:
                    continue
                seen_atoms.add(atom.id)
                yield CueDetection(
                    cue_id=atom.id,
                    matched_phrase=f"[semantic] {event.text[:60]}",
                    timestamp_ms=event.timestamp_ms,
                    confidence=event.confidence,
                    triggering_event=event,
                    match_method="semantic",
                    similarity_score=round(score, 4),
                )
