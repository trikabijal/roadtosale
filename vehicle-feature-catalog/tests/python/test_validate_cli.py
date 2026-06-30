"""Tests for scripts/validate.py — the CI boundary-gate CLI.

Covers plan items T-VLD-1..3:

* exit 0 + "valid" on the happy fixture and the real shipped data/  (T-VLD-1)
* exit 1 + format_errors() to stderr on the invalid fixture tree    (T-VLD-2)
* exit 1 on a LoadError (missing/unreadable data dir)               (T-VLD-3)

Black-box: we invoke the script as a real subprocess (the way CI / voice-lab
runs it) and assert on the process exit code and stderr — never importing the
script's internals.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

_CATALOG_ROOT = Path(__file__).resolve().parents[2]
_SCRIPT = _CATALOG_ROOT / "scripts" / "validate.py"
_FIXTURES = _CATALOG_ROOT / "tests" / "fixtures"
_FIXTURES_INVALID = _CATALOG_ROOT / "tests" / "fixtures-invalid"
_REAL_DATA = _CATALOG_ROOT / "data"


def _run(data_dir: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(_SCRIPT), "--data-dir", str(data_dir)],
        capture_output=True,
        text=True,
        cwd=str(_CATALOG_ROOT),
    )


def test_validate_cli_exits_zero_on_happy_fixture() -> None:
    proc = _run(_FIXTURES)
    assert proc.returncode == 0, proc.stderr
    assert "valid" in proc.stdout.lower()


def test_validate_cli_exits_zero_on_real_data() -> None:
    proc = _run(_REAL_DATA)
    assert proc.returncode == 0, proc.stderr
    assert "valid" in proc.stdout.lower()


def test_validate_cli_exits_one_on_invalid_tree() -> None:
    proc = _run(_FIXTURES_INVALID)
    assert proc.returncode == 1
    # format_errors() output goes to stderr; each finding code should appear.
    assert "matrix_unknown_feature" in proc.stderr
    assert "trim_has_no_features" in proc.stderr


def test_validate_cli_exits_one_on_missing_dir(tmp_path: Path) -> None:
    proc = _run(tmp_path / "does-not-exist")
    assert proc.returncode == 1
    assert "load failed" in proc.stderr.lower()
