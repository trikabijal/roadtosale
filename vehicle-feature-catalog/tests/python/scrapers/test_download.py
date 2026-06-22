"""Tests for the download step (scrapers/honda_us/download.py).

Covers plan items S-DL-1..3:

* target_path is the deterministic
  data-cache/brochures/honda/<year>/<slug>.pdf                       (S-DL-1)
* download_all skips an already-cached non-empty file and records it
  under skipped_cached (idempotent)                                  (S-DL-2)
* retry on HTTP error then surface a clear failure; an empty body is
  treated as failure and leaves no zero-byte file behind            (S-DL-3)

No network: a fake requests.Session feeds canned responses. retry_backoff is
set to 0 so the suite stays fast.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

_CATALOG_ROOT = Path(__file__).resolve().parents[3]
if str(_CATALOG_ROOT) not in sys.path:
    sys.path.insert(0, str(_CATALOG_ROOT))

from scrapers.honda_us.download import BrochureDownloader  # noqa: E402


class _FakeResponse:
    """A context-manager stand-in for requests' streaming Response."""

    def __init__(self, status: int, body: bytes = b"") -> None:
        self.status_code = status
        self._body = body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def iter_content(self, chunk_size: int = 0):
        if self._body:
            yield self._body


class _FakeSession:
    def __init__(self, responses: list[_FakeResponse]) -> None:
        self._responses = list(responses)
        self.headers: dict[str, str] = {}
        self.calls: list[str] = []

    def get(self, url, timeout=None, stream=False):  # noqa: ANN001
        self.calls.append(url)
        if self._responses:
            return self._responses.pop(0)
        return _FakeResponse(500)


# ----------------------------------------------------------------- S-DL-1


def test_target_path_is_deterministic(tmp_path: Path) -> None:
    dl = BrochureDownloader(cache_root=tmp_path, year=2026)
    expected = tmp_path / "brochures" / "honda" / "2026" / "civic.pdf"
    assert dl.target_path("civic") == expected


# ----------------------------------------------------------------- S-DL-2


def test_download_all_skips_cached_non_empty_file(tmp_path: Path) -> None:
    session = _FakeSession([])  # no responses: any network call would 500
    dl = BrochureDownloader(cache_root=tmp_path, year=2026, session=session)  # type: ignore[arg-type]

    # Pre-seed the cache with a non-empty file.
    target = dl.target_path("civic")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b"%PDF-1.7 cached")

    result = dl.download_all({"civic": "https://example.com/civic.pdf"})

    assert "civic" in result.skipped_cached
    assert result.downloaded["civic"] == target
    assert "civic" not in result.errors
    # Idempotent: no network request was made.
    assert session.calls == []


def test_download_all_fetches_when_not_cached(tmp_path: Path) -> None:
    session = _FakeSession([_FakeResponse(200, b"%PDF-1.7 fresh-bytes")])
    dl = BrochureDownloader(cache_root=tmp_path, year=2026, session=session)  # type: ignore[arg-type]

    result = dl.download_all({"civic": "https://example.com/civic.pdf"})

    target = dl.target_path("civic")
    assert result.downloaded["civic"] == target
    assert "civic" not in result.skipped_cached
    assert target.exists() and target.stat().st_size > 0
    assert session.calls == ["https://example.com/civic.pdf"]


# ----------------------------------------------------------------- S-DL-3


def test_download_all_retries_then_reports_failure(tmp_path: Path) -> None:
    # Two 503s, then run out -> exhaust retries -> error, no file left behind.
    session = _FakeSession([_FakeResponse(503), _FakeResponse(503), _FakeResponse(503)])
    dl = BrochureDownloader(
        cache_root=tmp_path,
        year=2026,
        session=session,  # type: ignore[arg-type]
        retry_count=2,
        retry_backoff_sec=0.0,
    )

    result = dl.download_all({"civic": "https://example.com/civic.pdf"})

    assert "civic" not in result.downloaded
    assert "civic" in result.errors
    assert "Download failed" in result.errors["civic"]
    # Retried (initial + 2 retries = 3 attempts).
    assert len(session.calls) == 3
    # No zero-byte artifact left on disk.
    assert not dl.target_path("civic").exists()


def test_download_all_treats_empty_body_as_failure(tmp_path: Path) -> None:
    # 200 OK but empty body across all attempts -> failure, no zero-byte file.
    session = _FakeSession([_FakeResponse(200, b""), _FakeResponse(200, b"")])
    dl = BrochureDownloader(
        cache_root=tmp_path,
        year=2026,
        session=session,  # type: ignore[arg-type]
        retry_count=1,
        retry_backoff_sec=0.0,
    )

    result = dl.download_all({"civic": "https://example.com/civic.pdf"})

    assert "civic" in result.errors
    target = dl.target_path("civic")
    assert not target.exists()
    assert not target.with_suffix(target.suffix + ".part").exists()
