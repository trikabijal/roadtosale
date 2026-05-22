"""Discover Honda US brochure PDF URLs.

Strategy
========

Honda's marketing site is ``automobiles.honda.com``. Each model has a page
at ``https://automobiles.honda.com/<model-slug>`` whose HTML links to a
downloadable brochure PDF. Honda has changed paths over the years; the most
durable approach is:

1. Fetch the model index ``https://automobiles.honda.com/vehicles`` (or
   the model page directly) and parse out anchors whose ``href`` looks like
   a brochure PDF (``.pdf`` ending, containing the model slug or the
   word "brochure"/"factsheet").
2. If no such link is found, fall back to a list of *candidate* PDF URLs
   matching the patterns Honda is currently using
   (``/-/media/Honda-Automobiles/Vehicles/...``). HEAD-check each candidate.
3. Surface a clear error naming the URL pattern that failed.

This module returns a mapping of ``model_slug -> brochure_url`` and never
downloads the PDF itself — that's ``download.py``'s job.
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from typing import Iterable
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

from . import (
    DEFAULT_BROWSER_HEADERS,
    DEFAULT_SCRAPER_IDENTITY,
    DEFAULT_USER_AGENT,
    DEFAULT_YEAR,
)

log = logging.getLogger(__name__)


BASE_URL = "https://automobiles.honda.com"


# Honda labels their model pages by slug. A few aren't the obvious one
# (Honda uses "cr-v" not "crv", "hr-v" not "hrv"). Override the
# auto-derived URL here when the slug differs from the catalog slug.
MODEL_PAGE_OVERRIDES: dict[str, str] = {
    # catalog slug -> path on automobiles.honda.com
    "civic": "/civic",
    "accord": "/accord",
    "hr-v": "/hr-v",
    "pilot": "/pilot",
    "passport": "/passport",
    "odyssey": "/odyssey",
    "ridgeline": "/ridgeline",
    "prologue": "/prologue",
    "cr-v": "/cr-v",
    "cr-v-hybrid": "/cr-v-hybrid",
}


class DiscoveryError(Exception):
    """Raised when the discoverer can't reach a model page."""


@dataclass
class DiscoveryResult:
    """The result of running discover() across one or more models."""

    found: dict[str, str] = field(default_factory=dict)
    """model_slug -> absolute PDF URL"""

    errors: dict[str, str] = field(default_factory=dict)
    """model_slug -> human-readable failure reason"""


class BrochureDiscoverer:
    """Find brochure PDF URLs on automobiles.honda.com."""

    def __init__(
        self,
        session: requests.Session | None = None,
        user_agent: str = DEFAULT_USER_AGENT,
        year: int = DEFAULT_YEAR,
        request_timeout: float = 20.0,
        retry_count: int = 2,
        retry_backoff_sec: float = 1.5,
    ) -> None:
        self.session = session or requests.Session()
        self.session.headers.update({"User-Agent": user_agent})
        self.session.headers.update(DEFAULT_BROWSER_HEADERS)
        self.session.headers.setdefault("X-Scraper-Identity", DEFAULT_SCRAPER_IDENTITY)
        self.year = year
        self.timeout = request_timeout
        self.retry_count = retry_count
        self.retry_backoff_sec = retry_backoff_sec

    # ------------------------------------------------------------------ public

    def discover(self, model_slugs: Iterable[str]) -> DiscoveryResult:
        result = DiscoveryResult()
        for slug in model_slugs:
            try:
                url = self.discover_one(slug)
                if url:
                    result.found[slug] = url
                else:
                    result.errors[slug] = (
                        f"No brochure PDF link found on {self._model_page_url(slug)}. "
                        f"Expected an <a href> ending in .pdf containing 'brochure', "
                        f"'factsheet', or '{slug}'."
                    )
            except DiscoveryError as exc:
                result.errors[slug] = str(exc)
            except requests.RequestException as exc:
                result.errors[slug] = f"Network error fetching model page: {exc}"
            except Exception as exc:  # noqa: BLE001 — discoverer must keep going
                result.errors[slug] = f"Unexpected error: {exc}"
        return result

    def discover_one(self, model_slug: str) -> str | None:
        """Return the brochure PDF URL for ``model_slug`` or ``None``.

        Raises ``DiscoveryError`` if we couldn't even fetch the model page —
        the caller can distinguish that from "we fetched the page but found
        no PDF link" via the error message.
        """
        page_url = self._model_page_url(model_slug)
        log.info("Discovering brochure for %s via %s", model_slug, page_url)
        html, status = self._get_text_with_status(page_url)
        if html is None:
            raise DiscoveryError(
                f"Could not fetch model page {page_url} "
                f"(last status: {status}). Honda's site is likely blocking "
                f"this network at the Akamai bot-manager layer — try running "
                f"from a residential IP, or use --from-pdf to bypass discovery."
            )
        return self._extract_brochure_url(html, page_url, model_slug)

    # ------------------------------------------------------------------ helpers

    def _model_page_url(self, model_slug: str) -> str:
        path = MODEL_PAGE_OVERRIDES.get(model_slug, f"/{model_slug}")
        return urljoin(BASE_URL, path)

    def _get_text(self, url: str) -> str | None:
        text, _ = self._get_text_with_status(url)
        return text

    def _get_text_with_status(self, url: str) -> tuple[str | None, str]:
        last_status = "<no response>"
        for attempt in range(self.retry_count + 1):
            try:
                resp = self.session.get(url, timeout=self.timeout, allow_redirects=True)
                last_status = f"HTTP {resp.status_code}"
                if resp.status_code == 200:
                    return resp.text, last_status
                if resp.status_code in (429, 500, 502, 503, 504):
                    time.sleep(self.retry_backoff_sec * (attempt + 1))
                    continue
                log.warning("HTTP %s from %s — not retryable", resp.status_code, url)
                return None, last_status
            except requests.RequestException as exc:
                last_status = f"network error: {exc}"
                time.sleep(self.retry_backoff_sec * (attempt + 1))
        log.warning("Giving up on %s after %d attempts: %s", url, self.retry_count + 1, last_status)
        return None, last_status

    # ---- public so tests can call extraction without HTTP ----
    def _extract_brochure_url(
        self, html: str, base_url: str, model_slug: str
    ) -> str | None:
        return extract_brochure_url(html, base_url, model_slug)


def extract_brochure_url(html: str, base_url: str, model_slug: str) -> str | None:
    """Scan ``html`` for the best brochure PDF link.

    Pure function — no I/O — so tests don't need a network.

    Heuristic ranking (highest first):
      1. ``.pdf`` href containing ``brochure`` AND the model slug
      2. ``.pdf`` href containing ``brochure``
      3. ``.pdf`` href containing the model slug
      4. Any ``.pdf`` href with anchor text that contains "brochure"
    """
    soup = BeautifulSoup(html, "html.parser")
    candidates: list[tuple[int, str]] = []  # (priority, absolute_url)

    slug_norm = model_slug.lower().replace("-", "")
    for anchor in soup.find_all("a"):
        href = (anchor.get("href") or "").strip()
        if not href:
            continue
        # Honda sometimes encodes spaces in PDF URLs — strip query string before
        # the suffix check.
        href_no_query = href.split("?", 1)[0].split("#", 1)[0]
        if not href_no_query.lower().endswith(".pdf"):
            continue

        href_norm = href_no_query.lower()
        href_no_dash = href_norm.replace("-", "").replace("_", "")
        text_norm = anchor.get_text(" ", strip=True).lower()

        has_brochure_url = "brochure" in href_norm or "factsheet" in href_norm
        has_slug_url = slug_norm in href_no_dash
        has_brochure_text = "brochure" in text_norm

        priority: int | None = None
        if has_brochure_url and has_slug_url:
            priority = 0
        elif has_brochure_url:
            priority = 1
        elif has_slug_url:
            priority = 2
        elif has_brochure_text:
            priority = 3
        if priority is None:
            continue

        absolute = urljoin(base_url, href)
        # Reject obvious junk: only http(s).
        parsed = urlparse(absolute)
        if parsed.scheme not in ("http", "https"):
            continue
        candidates.append((priority, absolute))

    if not candidates:
        # Last-ditch: look for inline JSON or data-* attrs that contain a PDF URL.
        match = re.search(
            r"https?://[^\s\"'<>]+?brochure[^\s\"'<>]*?\.pdf",
            html,
            re.IGNORECASE,
        )
        if match:
            return match.group(0)
        match = re.search(
            r"https?://[^\s\"'<>]+?" + re.escape(model_slug) + r"[^\s\"'<>]*?\.pdf",
            html,
            re.IGNORECASE,
        )
        if match:
            return match.group(0)
        return None

    candidates.sort(key=lambda t: t[0])
    return candidates[0][1]
