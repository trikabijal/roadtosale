#!/usr/bin/env python3
"""Derive a per-make speech vocabulary from the vehicle catalog.

RTS task #7. Each dealership's lineup is the highest-value, dealer-specific set of proper
nouns that generic STT mangles (model/trim/feature names). We pull them straight from the
catalog so a Honda store gets Honda words and a Toyota store gets Toyota words — no manual
typing, and scoped to ONE make so we don't overflow WhisperKit's small bias window.

Output: voice-engine/cleanup-packs/derived/<make>.vocab.json — a term list that the RTS
voice pipeline merges into the road-to-sale lexicon (the same bucket the hand-authored
dealership glossary fills, task #8).

Usage:  python3 scripts/derive_vocab.py [make_id ...]   (defaults to every make found)
"""
from __future__ import annotations
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent          # vehicle-feature-catalog/
DATA = ROOT / "data"
OUT_DIR = ROOT.parent / "voice-engine" / "cleanup-packs" / "derived"


def _load(path: Path) -> dict:
    return yaml.safe_load(path.read_text())


def _names_in(dirpath: Path, key: str) -> list[str]:
    """Collect `key` from every YAML under dirpath (recursively)."""
    out: list[str] = []
    if not dirpath.exists():
        return out
    for f in sorted(dirpath.rglob("*.yaml")):
        val = _load(f).get(key)
        if isinstance(val, str) and val.strip():
            out.append(val.strip())
    return out


def derive_make(make_id: str) -> dict:
    make = _load(DATA / "makes" / f"{make_id}.yaml")
    models = _names_in(DATA / "models" / make_id, "name")
    trims = _names_in(DATA / "trims" / make_id, "name")

    # Features are universal (shared across makes); include display names + short synonyms
    # (NOT the sentence-like cue_phrases — those aren't vocabulary terms).
    features: list[str] = []
    for f in sorted((DATA / "features" / "universal").rglob("*.yaml")):
        d = _load(f)
        if d.get("display_name"):
            features.append(str(d["display_name"]).strip())
        for syn in d.get("synonyms", []) or []:
            if isinstance(syn, str) and len(syn.split()) <= 3:   # keep it short/term-like
                features.append(syn.strip())

    def dedupe(seq):
        seen, out = set(), []
        for s in seq:
            k = s.lower()
            if k not in seen:
                seen.add(k)
                out.append(s)
        return out

    models, trims, features = dedupe(models), dedupe(trims), dedupe(features)
    # The merged bucket: models + trims first (dealer-specific, highest value), then features.
    terms = dedupe(models + trims + features)

    return {
        "make_id": make.get("id", make_id),
        "make_name": make.get("name", make_id),
        "source": "vehicle-feature-catalog (derived; do not hand-edit)",
        "counts": {"models": len(models), "trims": len(trims),
                   "features": len(features), "terms": len(terms)},
        "models": models,
        "trims": trims,
        "features": features,
        "terms": terms,
    }


def main(argv: list[str]) -> int:
    makes = argv or [p.stem for p in sorted((DATA / "makes").glob("*.yaml"))]
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for make_id in makes:
        vocab = derive_make(make_id)
        out = OUT_DIR / f"{make_id}.vocab.json"
        out.write_text(json.dumps(vocab, indent=2, ensure_ascii=False) + "\n")
        c = vocab["counts"]
        print(f"✓ {make_id}: {c['terms']} terms "
              f"({c['models']} models, {c['trims']} trims, {c['features']} features) → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
