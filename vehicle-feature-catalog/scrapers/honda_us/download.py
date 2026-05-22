"""Download Honda brochure PDFs into the local cache.

Idempotent: skips files that already exist on disk and are non-empty.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping

import requests

from . import (
    DEFAULT_BROWSER_HEADERS,
    DEFAULT_SCRAPER_IDENTITY,
    DEFAULT_USER_AGENT,
    DEFAULT_YEAR,
)

log = logging.getLogger(__name__)


@dataclass
class DownloadResult:
    """Result of running download() across one or more URLs."""

    downloaded: dict[str, Path] = field(default_factory=dict)
    """model_slug -> local file path (newly downloaded OR already cached)"""

    errors: dict[str, str] = field(default_factory=dict)
    """model_slug -> human-readable failure reason"""

    skipped_cached: set[str] = field(default_factory=set)
    """model slugs whose PDF was already cached"""


class BrochureDownloader:
    """Fetch PDFs and store them under a deterministic cache path."""

    def __init__(
        self,
        cache_root: Path,
        session: requests.Session | None = None,
        user_agent: str = DEFAULT_USER_AGENT,
        year: int = DEFAULT_YEAR,
        request_timeout: float = 60.0,
        retry_count: int = 2,
        retry_backoff_sec: float = 2.0,
        chunk_size: int = 64 * 1024,
    ) -> None:
        self.cache_root = Path(cache_root)
        self.session = session or requests.Session()
        self.session.headers.update({"User-Agent": user_agent})
        # For PDF responses we want Accept: */* so Akamai/CDN behavior matches
        # what a browser does when clicking a brochure link.
        self.session.headers.update(
            {k: v for k, v in DEFAULT_BROWSER_HEADERS.items() if k != "Accept"}
        )
        self.session.headers["Accept"] = "application/pdf,*/*;q=0.8"
        self.session.headers.setdefault("X-Scraper-Identity", DEFAULT_SCRAPER_IDENTITY)
        self.year = year
        self.timeout = request_timeout
        self.retry_count = retry_count
        self.retry_backoff_sec = retry_backoff_sec
        self.chunk_size = chunk_size

    def target_path(self, model_slug: str) -> Path:
        return self.cache_root / "brochures" / "honda" / str(self.year) / f"{model_slug}.pdf"

    def download_all(self, urls_by_model: Mapping[str, str]) -> DownloadResult:
        result = DownloadResult()
        for slug, url in urls_by_model.items():
            try:
                target = self.target_path(slug)
                if target.exists() and target.stat().st_size > 0:
                    log.info("Cached PDF for %s already at %s — skipping", slug, target)
                    result.downloaded[slug] = target
                    result.skipped_cached.add(slug)
                    continue
                self._download_one(url, target)
                result.downloaded[slug] = target
            except Exception as exc:  # noqa: BLE001
                result.errors[slug] = f"Download failed for {url}: {exc}"
        return result

    def _download_one(self, url: str, target: Path) -> None:
        target.parent.mkdir(parents=True, exist_ok=True)
        last_exc: Exception | None = None
        for attempt in range(self.retry_count + 1):
            try:
                with self.session.get(url, timeout=self.timeout, stream=True) as resp:
                    if resp.status_code != 200:
                        last_exc = RuntimeError(f"HTTP {resp.status_code} from {url}")
                        time.sleep(self.retry_backoff_sec * (attempt + 1))
                        continue
                    tmp = target.with_suffix(target.suffix + ".part")
                    with tmp.open("wb") as fh:
                        for chunk in resp.iter_content(chunk_size=self.chunk_size):
                            if chunk:
                                fh.write(chunk)
                    if tmp.stat().st_size == 0:
                        tmp.unlink(missing_ok=True)
                        last_exc = RuntimeError(f"Empty body from {url}")
                        time.sleep(self.retry_backoff_sec * (attempt + 1))
                        continue
                    tmp.replace(target)
                    log.info("Downloaded %s -> %s (%d bytes)", url, target, target.stat().st_size)
                    return
            except requests.RequestException as exc:
                last_exc = exc
                time.sleep(self.retry_backoff_sec * (attempt + 1))
        raise RuntimeError(
            f"Failed after {self.retry_count + 1} attempts: {last_exc}"
        )
