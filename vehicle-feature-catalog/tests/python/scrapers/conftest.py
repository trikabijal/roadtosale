"""Make the ``scrapers`` package importable from these tests.

Pytest's ``pythonpath`` config doesn't always pick up relative entries when
the test file is collected, so we add the catalog root explicitly here.
"""

from __future__ import annotations

import sys
from pathlib import Path

_CATALOG_ROOT = Path(__file__).resolve().parents[3]
if str(_CATALOG_ROOT) not in sys.path:
    sys.path.insert(0, str(_CATALOG_ROOT))
