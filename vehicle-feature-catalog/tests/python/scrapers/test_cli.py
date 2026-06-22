"""End-to-end tests for the scraper CLI (scrapers/honda_us/cli.py main()).

Covers plan items S-CLI-1, S-CLI-3, S-CLI-4, S-CLI-5:

* hondanews-html source end-to-end against a fixture HTML dir emits YAML,
  returns 0, and the written data dir reloads + validates                (S-CLI-1)
* an unknown model slug returns exit 2 with a clear message              (S-CLI-3)
* --dry-run writes nothing                                               (S-CLI-4)
* hondanews source returns 0 even when no <slug>.html files exist        (S-CLI-5)

Black-box: drives main(argv) and asserts the observable side effect — exit
code + the YAML that loads & validates through the facade. No network is
touched (hondanews source reads operator-saved HTML only).
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

import pytest

_CATALOG_ROOT = Path(__file__).resolve().parents[3]
if str(_CATALOG_ROOT) not in sys.path:
    sys.path.insert(0, str(_CATALOG_ROOT))
_SRC = _CATALOG_ROOT / "src" / "python"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from scrapers.honda_us import cli  # noqa: E402
from vehicle_feature_catalog import VehicleFeatureCatalog  # noqa: E402

_FIXTURE_HTML = (
    _CATALOG_ROOT / "tests" / "python" / "scrapers" / "fixtures" / "tiny_press_release.html"
)
_TINY_PDF = (
    _CATALOG_ROOT / "tests" / "python" / "scrapers" / "fixtures" / "tiny_brochure.pdf"
)
_REAL_DATA = _CATALOG_ROOT / "data"


@pytest.fixture
def tmp_data_dir(tmp_path: Path) -> Path:
    target = tmp_path / "data"
    shutil.copytree(_REAL_DATA, target)
    return target


@pytest.fixture
def hondanews_dir(tmp_path: Path) -> Path:
    """A hondanews cache dir holding the fixture press release as civic.html."""
    d = tmp_path / "hondanews"
    d.mkdir()
    shutil.copy(_FIXTURE_HTML, d / "civic.html")
    return d


# ----------------------------------------------------------------- S-CLI-3


def test_unknown_slug_exits_2(capsys) -> None:
    rc = cli.main(["--models", "definitely-not-a-model"])
    assert rc == 2
    err = capsys.readouterr().err
    assert "Unknown model slug" in err


# ----------------------------------------------------------------- S-CLI-5


def test_hondanews_missing_dir_exits_0(tmp_data_dir: Path, tmp_path: Path) -> None:
    missing = tmp_path / "no-such-hondanews"
    rc = cli.main([
        "--source", "hondanews-html",
        "--models", "civic",
        "--hondanews-dir", str(missing),
        "--data-dir", str(tmp_data_dir),
    ])
    # "no files cached yet" is the documented pre-condition, not a failure.
    assert rc == 0


def test_hondanews_empty_dir_exits_0(tmp_data_dir: Path, tmp_path: Path) -> None:
    empty = tmp_path / "empty-hondanews"
    empty.mkdir()
    rc = cli.main([
        "--source", "hondanews-html",
        "--models", "civic",
        "--hondanews-dir", str(empty),
        "--data-dir", str(tmp_data_dir),
    ])
    assert rc == 0


# ----------------------------------------------------------------- S-CLI-1


def test_hondanews_end_to_end_emits_and_validates(
    tmp_data_dir: Path, hondanews_dir: Path
) -> None:
    rc = cli.main([
        "--source", "hondanews-html",
        "--models", "civic",
        "--hondanews-dir", str(hondanews_dir),
        "--data-dir", str(tmp_data_dir),
    ])
    assert rc == 0

    # The emitted Civic trims should now be loadable, and the whole catalog
    # must still validate (the real acceptance test for emit-through-CLI).
    catalog = VehicleFeatureCatalog.load(tmp_data_dir)
    model = catalog.get_model("honda.civic")
    assert model.name == "Civic"
    result = catalog.validate()
    assert result.is_valid, result.format_errors()


# ----------------------------------------------------------------- S-CLI-2


def test_from_pdf_bypasses_discover_download_and_emits(tmp_data_dir: Path) -> None:
    """--from-pdf SLUG=PATH skips discovery/download and emits from a local PDF.

    Uses the committed tiny brochure fixture; no network. The brochure source
    returns exit 0 when something was emitted.
    """
    if not _TINY_PDF.exists():
        pytest.skip(f"No PDF fixture committed at {_TINY_PDF}")
    rc = cli.main([
        "--models", "civic",
        "--from-pdf", f"civic={_TINY_PDF}",
        "--data-dir", str(tmp_data_dir),
        "--cache-dir", str(tmp_data_dir.parent / "cache"),
    ])
    assert rc == 0
    catalog = VehicleFeatureCatalog.load(tmp_data_dir)
    assert catalog.get_model("honda.civic").name == "Civic"
    assert catalog.validate().is_valid, catalog.validate().format_errors()


# ----------------------------------------------------------------- S-CLI-4


def test_hondanews_dry_run_writes_nothing(
    tmp_data_dir: Path, hondanews_dir: Path
) -> None:
    before = {
        p.relative_to(tmp_data_dir)
        for p in tmp_data_dir.rglob("*")
        if p.is_file()
    }
    before_bytes = {p: p.read_bytes() for p in tmp_data_dir.rglob("*") if p.is_file()}

    rc = cli.main([
        "--source", "hondanews-html",
        "--models", "civic",
        "--hondanews-dir", str(hondanews_dir),
        "--data-dir", str(tmp_data_dir),
        "--dry-run",
    ])
    assert rc == 0

    after = {
        p.relative_to(tmp_data_dir)
        for p in tmp_data_dir.rglob("*")
        if p.is_file()
    }
    assert before == after, "dry-run created or removed files"
    # And no existing file content was mutated.
    for p, content in before_bytes.items():
        assert p.read_bytes() == content, f"dry-run mutated {p}"
