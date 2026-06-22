"""Cleanup-pack data loading tests (P-PACK-1/2/3/4).

These guard the canonical cleanup-pack JSON files that the native platforms
(Swift Foundation Models, Android Gemini Nano, rule-based fallback) all consume.
The packs are *data*, not code — nothing in this module loaded or validated them
before, so a typo in the RTS lexicon could ship silently.

The cleanup *behaviour* (P-PACK-3/4) is asserted end-to-end through the TS
``RuleBasedCleanupStrategy`` reference in ``tests/cleanup-packs.test.ts``; here we
assert the data contract: required keys, prompts, and the dealership lexicon.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest


# voice-engine/lab/tests/test_cleanup_packs.py → voice-engine/cleanup-packs/
_PACKS_DIR = Path(__file__).resolve().parents[2] / "cleanup-packs"

_REQUIRED_KEYS = {
    "profile",
    "min_words_for_cleanup",
    "command_grammar",
    "fillers",
    "junk_phrases",
    "prompts",
}


def _load(name: str) -> dict:
    path = _PACKS_DIR / name
    assert path.exists(), f"cleanup pack not found: {path}"
    return json.loads(path.read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# P-PACK-1: dictation.json
# ---------------------------------------------------------------------------


def test_dictation_pack_parses_and_has_required_keys():
    pack = _load("dictation.json")
    assert _REQUIRED_KEYS.issubset(pack.keys())
    assert pack["profile"] == "dictation"
    assert isinstance(pack["min_words_for_cleanup"], int)
    assert isinstance(pack["command_grammar"], dict)
    assert pack["command_grammar"]["new paragraph"] == "\n\n"
    assert isinstance(pack["fillers"], list) and pack["fillers"]
    assert isinstance(pack["junk_phrases"], list) and pack["junk_phrases"]


def test_dictation_pack_has_light_and_full_prompts():
    pack = _load("dictation.json")
    prompts = pack["prompts"]
    assert "light" in prompts and "full" in prompts
    assert prompts["light"].strip()
    assert prompts["full"].strip()
    # The light prompt must promise to preserve wording; the full one may
    # restructure. Both must forbid answering the content.
    assert "never answer" in prompts["light"].lower()
    assert "never answer" in prompts["full"].lower()


# ---------------------------------------------------------------------------
# P-PACK-2: road-to-sale.json + lexicon
# ---------------------------------------------------------------------------


def test_road_to_sale_pack_parses_and_has_required_keys():
    pack = _load("road-to-sale.json")
    assert _REQUIRED_KEYS.issubset(pack.keys())
    assert pack["profile"] == "road-to-sale"
    prompts = pack["prompts"]
    assert prompts["light"].strip()
    assert prompts["full"].strip()


def test_road_to_sale_pack_has_lexicon_terms_and_expansions():
    pack = _load("road-to-sale.json")
    assert "lexicon" in pack, "RTS pack must carry a dealership lexicon"
    lexicon = pack["lexicon"]

    terms = lexicon["terms"]
    assert isinstance(terms, list) and len(terms) > 0
    # A representative sample of the dealership glossary.
    for term in ["F&I", "APR", "MSRP", "trade-in", "be-back"]:
        assert term in terms, f"missing dealership term: {term}"

    expansions = lexicon["expansions"]
    assert isinstance(expansions, dict) and expansions
    # The documented spoken→written mappings.
    assert expansions["f and i"] == "F&I"
    assert expansions["trade in"] == "trade-in"
    assert expansions["a p r"] == "APR"


def test_road_to_sale_superset_of_dictation_structure():
    """The RTS pack carries everything the dictation pack has, plus lexicon."""
    dictation = _load("dictation.json")
    rts = _load("road-to-sale.json")
    assert set(dictation.keys()).issubset(rts.keys())
    assert "lexicon" not in dictation
    assert "lexicon" in rts


# ---------------------------------------------------------------------------
# P-PACK-4 (data half): expansions are well-formed spoken→written maps
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "spoken,written",
    [
        ("f and i", "F&I"),
        ("trade in", "trade-in"),
        ("be back", "be-back"),
        ("four square", "four-square"),
        ("walk around", "walk-around"),
    ],
)
def test_rts_expansions_round_trip_data(spoken, written):
    pack = _load("road-to-sale.json")
    expansions = pack["lexicon"]["expansions"]
    assert expansions[spoken] == written
    # Spoken forms are lowercased multi-word phrases (vocab keys, not cues).
    assert spoken == spoken.lower()
    assert len(spoken.split()) >= 2
