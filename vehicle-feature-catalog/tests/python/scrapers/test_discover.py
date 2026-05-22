"""Tests for the discovery step. No network — uses canned HTML."""

from __future__ import annotations

import sys
from pathlib import Path

_CATALOG_ROOT = Path(__file__).resolve().parents[3]
if str(_CATALOG_ROOT) not in sys.path:
    sys.path.insert(0, str(_CATALOG_ROOT))

from scrapers.honda_us.discover import (  # noqa: E402
    BrochureDiscoverer,
    extract_brochure_url,
)


CIVIC_PAGE = """
<html>
  <body>
    <a href="/civic/specs">Specs</a>
    <a href="/-/media/Honda-Automobiles/Vehicles/2026/Civic/Brochure/2026-Civic-Brochure.pdf">
      Download the 2026 Civic Brochure
    </a>
    <a href="https://owners.honda.com/manuals">Owners manuals</a>
    <a href="/-/media/somewhere/random.pdf">Random PDF</a>
  </body>
</html>
"""


PASSPORT_PAGE_NO_BROCHURE_IN_URL = """
<html>
  <body>
    <a href="https://example.com/passport-spec.pdf">Download Brochure</a>
  </body>
</html>
"""


PAGE_WITH_NOTHING = """<html><body><p>nothing here</p></body></html>"""


def test_extract_brochure_url_prefers_brochure_and_slug():
    url = extract_brochure_url(
        CIVIC_PAGE, base_url="https://automobiles.honda.com/civic", model_slug="civic"
    )
    assert url is not None
    assert url.endswith("2026-Civic-Brochure.pdf")
    assert url.startswith("https://automobiles.honda.com/")


def test_extract_brochure_url_falls_back_to_anchor_text():
    url = extract_brochure_url(
        PASSPORT_PAGE_NO_BROCHURE_IN_URL,
        base_url="https://automobiles.honda.com/passport",
        model_slug="passport",
    )
    assert url == "https://example.com/passport-spec.pdf"


def test_extract_brochure_url_returns_none_when_no_pdf():
    url = extract_brochure_url(
        PAGE_WITH_NOTHING,
        base_url="https://automobiles.honda.com/civic",
        model_slug="civic",
    )
    assert url is None


class _FakeResponse:
    def __init__(self, status: int, text: str) -> None:
        self.status_code = status
        self.text = text


class _FakeSession:
    def __init__(self, responses: dict[str, _FakeResponse]) -> None:
        self.responses = responses
        self.headers: dict[str, str] = {}
        self.calls: list[str] = []

    def get(self, url, timeout=None, allow_redirects=True):  # noqa: D401, ANN001
        self.calls.append(url)
        return self.responses.get(url, _FakeResponse(404, ""))


def test_discoverer_uses_model_page_overrides():
    session = _FakeSession({"https://automobiles.honda.com/civic": _FakeResponse(200, CIVIC_PAGE)})
    discoverer = BrochureDiscoverer(session=session, retry_count=0)  # type: ignore[arg-type]
    result = discoverer.discover(["civic"])
    assert "civic" in result.found
    assert result.found["civic"].endswith("2026-Civic-Brochure.pdf")
    assert "civic" not in result.errors


def test_discoverer_reports_clear_error_when_no_pdf_found():
    session = _FakeSession({"https://automobiles.honda.com/civic": _FakeResponse(200, PAGE_WITH_NOTHING)})
    discoverer = BrochureDiscoverer(session=session, retry_count=0)  # type: ignore[arg-type]
    result = discoverer.discover(["civic"])
    assert "civic" not in result.found
    assert "civic" in result.errors
    assert "civic" in result.errors["civic"].lower()
    assert "https://automobiles.honda.com/civic" in result.errors["civic"]


def test_discoverer_reports_403_with_diagnostic_message():
    session = _FakeSession({"https://automobiles.honda.com/civic": _FakeResponse(403, "")})
    discoverer = BrochureDiscoverer(session=session, retry_count=0)  # type: ignore[arg-type]
    result = discoverer.discover(["civic"])
    assert "civic" in result.errors
    msg = result.errors["civic"]
    assert "403" in msg
    assert "Akamai" in msg or "blocking" in msg.lower()
    assert "--from-pdf" in msg
