"""Tests for scripts/derive_vocab.py — the catalog -> STT vocab bridge.

Covers plan items T-VOC-1..5:

* derive_make() output schema + counts consistency  (T-VOC-1)
* case-insensitive dedupe; models+trims ordered before features  (T-VOC-2)
* only short (<=3 word) universal-feature synonyms; sentence-like
  cue_phrases excluded  (T-VOC-3)
* main() writes one voice-engine/.../<make>.vocab.json per make, marked
  "do not hand-edit"  (T-VOC-4, T-VOC-5)

Black-box: we drive the script's public functions (derive_make / main).
The file-handoff target (a sibling voice-engine dir) is redirected to a tmp
dir via the module-level OUT_DIR so the test never imports voice-engine and
never writes outside the test sandbox.
"""

from __future__ import annotations

import importlib
import json
import sys
from pathlib import Path

import pytest

_CATALOG_ROOT = Path(__file__).resolve().parents[2]
_SCRIPTS = _CATALOG_ROOT / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

import derive_vocab as dv  # noqa: E402


@pytest.fixture
def real_data() -> Path:
    return _CATALOG_ROOT / "data"


@pytest.fixture(autouse=True)
def _reset_module():
    """Reload the module after each test so DATA/OUT_DIR monkeypatches don't leak."""
    yield
    importlib.reload(dv)


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _build_synthetic_catalog(root: Path) -> None:
    """A tiny catalog that exercises the synonym/cue/dedupe rules deterministically."""
    _write(root / "makes" / "honda.yaml", "id: honda\nname: Honda\ncountry: JP\n")
    # Two models, one with a name that case-collides with a trim (dedupe target).
    _write(root / "models" / "honda" / "civic.yaml", "id: honda.civic\nname: Civic\n")
    _write(root / "models" / "honda" / "accord.yaml", "id: honda.accord\nname: Accord\n")
    # Trims: 'Sport' twice with different casing -> must dedupe to one.
    _write(root / "trims" / "honda" / "civic" / "2026" / "sport.yaml",
           "id: honda.civic.2026.sport\nname: Sport\n")
    _write(root / "trims" / "honda" / "civic" / "2026" / "sport2.yaml",
           "id: honda.civic.2026.sport2\nname: SPORT\n")
    _write(root / "trims" / "honda" / "accord" / "2026" / "touring.yaml",
           "id: honda.accord.2026.touring\nname: Touring\n")
    # A universal feature with a short synonym (kept), a long synonym (dropped),
    # and a sentence-like cue_phrase (must NOT appear in vocab).
    _write(
        root / "features" / "universal" / "heated_front_seats.yaml",
        "id: universal.feature.heated_front_seats\n"
        "display_name: Heated Front Seats\n"
        "category: comfort\n"
        "brand_scope: universal\n"
        "cue_phrases:\n"
        "  - warm the seats up before I get in the car\n"
        "synonyms:\n"
        "  - heated seats\n"  # 2 words -> kept
        "  - seats that warm your back up nicely\n"  # 6 words -> dropped
        ,
    )


# ----------------------------------------------------------------- T-VOC-1


def test_derive_make_shape_and_counts(real_data: Path) -> None:
    vocab = dv.derive_make("honda")

    # Schema: exact key set the voice-engine consumer relies on.
    for key in ("make_id", "make_name", "source", "counts",
                "models", "trims", "features", "terms"):
        assert key in vocab, f"missing key {key!r}"

    assert vocab["make_id"] == "honda"
    assert vocab["make_name"] == "Honda"

    # counts must be self-consistent with the lists.
    counts = vocab["counts"]
    assert counts["models"] == len(vocab["models"])
    assert counts["trims"] == len(vocab["trims"])
    assert counts["features"] == len(vocab["features"])
    assert counts["terms"] == len(vocab["terms"])

    # Per-make scoping: real Honda lineup is read from the catalog.
    assert "Civic" in vocab["models"]
    assert "Accord" in vocab["models"]


def test_derive_make_is_make_scoped(real_data: Path) -> None:
    """A Honda store gets Honda words only — no Toyota models leak in."""
    honda = dv.derive_make("honda")
    assert "Camry" not in honda["models"]
    assert "Civic" in honda["models"]


# ----------------------------------------------------------------- T-VOC-2


def test_terms_dedup_case_insensitive_and_ordering(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(dv, "DATA", tmp_path)
    _build_synthetic_catalog(tmp_path)

    vocab = dv.derive_make("honda")

    # 'Sport' and 'SPORT' collapse to a single term (case-insensitive dedupe).
    lower_terms = [t.lower() for t in vocab["terms"]]
    assert lower_terms.count("sport") == 1
    # No duplicate terms at all.
    assert len(lower_terms) == len(set(lower_terms))

    # Ordering: models + trims come before features in the merged term list.
    feature_lowers = {f.lower() for f in vocab["features"]}
    model_trim_lowers = {m.lower() for m in vocab["models"]} | {
        t.lower() for t in vocab["trims"]
    }
    first_feature_pos = next(
        (i for i, t in enumerate(lower_terms) if t in feature_lowers), len(lower_terms)
    )
    last_model_trim_pos = max(
        (i for i, t in enumerate(lower_terms) if t in model_trim_lowers), default=-1
    )
    assert last_model_trim_pos < first_feature_pos


# ----------------------------------------------------------------- T-VOC-3


def test_excludes_long_synonyms_and_cue_phrases(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr(dv, "DATA", tmp_path)
    _build_synthetic_catalog(tmp_path)

    vocab = dv.derive_make("honda")
    features_lower = {f.lower() for f in vocab["features"]}

    # display_name + short synonym are kept.
    assert "heated front seats" in features_lower
    assert "heated seats" in features_lower
    # The >3-word synonym is dropped.
    assert "seats that warm your back up nicely" not in features_lower
    assert not any(len(f.split()) > 3 for f in vocab["features"]), vocab["features"]
    # The sentence-like cue_phrase is never treated as a vocab term.
    assert "warm the seats up before i get in the car" not in features_lower


def test_real_data_features_are_short(real_data: Path) -> None:
    """Sanity on the shipped catalog: no derived feature term is sentence-like."""
    vocab = dv.derive_make("honda")
    too_long = [f for f in vocab["features"] if len(f.split()) > 3]
    assert too_long == [], too_long


# ----------------------------------------------------------------- T-VOC-4 / 5


def test_main_writes_per_make_json(tmp_path, monkeypatch) -> None:
    out_dir = tmp_path / "derived"
    monkeypatch.setattr(dv, "OUT_DIR", out_dir)

    rc = dv.main(["honda"])
    assert rc == 0

    out_file = out_dir / "honda.vocab.json"
    assert out_file.exists(), "main() did not write the per-make vocab JSON"

    payload = json.loads(out_file.read_text(encoding="utf-8"))
    # Marked as derived / do-not-hand-edit (the operator contract).
    assert "do not hand-edit" in payload["source"].lower()
    assert payload["make_id"] == "honda"
    assert payload["counts"]["terms"] == len(payload["terms"])


def test_main_writes_one_file_per_make(tmp_path, monkeypatch) -> None:
    out_dir = tmp_path / "derived"
    monkeypatch.setattr(dv, "OUT_DIR", out_dir)

    rc = dv.main(["honda", "toyota"])
    assert rc == 0

    written = sorted(p.name for p in out_dir.glob("*.vocab.json"))
    assert written == ["honda.vocab.json", "toyota.vocab.json"]


def test_main_defaults_to_all_makes(tmp_path, monkeypatch) -> None:
    """No args -> every make under data/makes/ gets a file (file-handoff, no imports)."""
    out_dir = tmp_path / "derived"
    monkeypatch.setattr(dv, "OUT_DIR", out_dir)

    rc = dv.main([])
    assert rc == 0
    # The shipped catalog has at least honda + toyota.
    names = {p.stem.replace(".vocab", "") for p in out_dir.glob("*.vocab.json")}
    assert {"honda", "toyota"} <= names
