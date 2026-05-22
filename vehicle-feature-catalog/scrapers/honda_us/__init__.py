"""Honda US 2026 brochure scraper.

Pipeline:

1. ``discover``   — find brochure PDF URLs on automobiles.honda.com
2. ``download``   — fetch PDFs into ``data-cache/brochures/honda/<year>/``
3. ``extract``    — parse PDFs with pdfplumber into a normalized matrix
4. ``emit``       — write YAML files under ``data/`` matching the catalog schema

Each step is a module with a small, testable public surface. The
``cli`` module ties them together.
"""

from __future__ import annotations

# Honda's automobiles.honda.com is fronted by Akamai bot manager. A clearly
# self-identifying UA gets 403'd unconditionally. We send a real-browser UA
# string but also send our identity in a custom header (``X-Scraper-Identity``)
# so the operator can see who we are. If/when Honda publishes a sanctioned
# data feed, we should switch back to a clearly-identifying UA.
DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)
DEFAULT_SCRAPER_IDENTITY = "roadtosale-voice-engine/v1 (contact: bijalsanghavi@gmail.com)"
DEFAULT_YEAR = 2026

DEFAULT_BROWSER_HEADERS: dict[str, str] = {
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Sec-Fetch-User": "?1",
    "Upgrade-Insecure-Requests": "1",
}

# The 8 Honda US models still missing from the catalog (CR-V Hybrid AWD is
# already hand-seeded). Slugs are the canonical model-id stem we'll use in
# the emitted YAML (e.g. honda.civic, honda.passport).
DEFAULT_MODELS: tuple[str, ...] = (
    "civic",
    "accord",
    "hr-v",
    "pilot",
    "passport",
    "odyssey",
    "ridgeline",
    "prologue",
)
