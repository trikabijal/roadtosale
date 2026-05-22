# Honda US brochure scraper

Pulls Honda's published 2026 brochure PDFs, parses the trim-vs-feature matrix
out of them, and emits YAML files matching the
[Vehicle Feature Catalog schema](../../docs/architecture.md).

This is a one-shot seeding tool, not a service. The output is the YAML you
review in PRs.

## What's automated vs. manual

| Step | Status |
|---|---|
| Discover brochure URLs from `automobiles.honda.com` | Automated when network access is available. **Often blocked by Akamai bot manager from CI/dev networks.** See "Honda blocks us" below. |
| Download PDFs to `data-cache/brochures/honda/<year>/<slug>.pdf` | Automated, idempotent (skips existing files), retries on 429/5xx |
| Extract trims + features + matrix from each PDF | Automated via `pdfplumber`. **Imperfect by design** — Honda redesigns these tables yearly and bullet glyphs are unreliable. Expect partial coverage and manual review of each emitted model |
| Map extracted feature labels to existing feature IDs under `data/features/` | Automated. Uses a curated alias table + case-insensitive display-name / synonym / cue-phrase match. Anything unmatched is materialized as a brand-scoped Honda feature with a seed cue-phrase set that **must be expanded by hand before voice matching is trusted** |
| Append matrix entries to `data/matrix/honda.yaml` while preserving CR-V Hybrid AWD entries | Automated. Cells dedupe on `(trim, feature)`; stronger availability wins |
| Validate end-to-end | `python3 scripts/validate.py --data-dir data` |

Manual steps that remain after every run:

1. **Review every emitted feature file.** The seed `cue_phrases` field has one
   entry (the display name). Voice matching needs rep-natural variants —
   add them.
2. **Sanity-check the trim list per model.** PDF column detection still
   conflates drivetrain variants ("2WD" / "AWD" suffixes are stripped, but
   marketing tweaks slip through).
3. **Re-verify MSRP ranges** — the scraper deliberately omits MSRP because
   prices are not reliably parseable from the matrix page alone.

## Usage

From `vehicle-feature-catalog/`:

```bash
# Install scraper deps once
python3 -m pip install -e '.[scrapers]'

# Default: all 8 missing models, 2026
python3 -m scrapers.honda_us.cli --verbose

# A single model, dry-run (no writes to data/)
python3 -m scrapers.honda_us.cli --models civic --dry-run --verbose

# Re-extract from already-cached PDFs (skip the discover/download phase)
python3 -m scrapers.honda_us.cli --skip-discover --verbose

# Bypass discovery entirely with a local PDF
python3 -m scrapers.honda_us.cli \
    --models civic \
    --from-pdf civic=/path/to/2026-Civic-Brochure.pdf \
    --verbose
```

CLI flags:

| Flag | Purpose |
|---|---|
| `--models civic,accord,...` | Comma-separated slugs. Default: `civic,accord,hr-v,pilot,passport,odyssey,ridgeline,prologue` |
| `--year 2026` | Model year (only affects path layout / IDs; does not change the brochure URL) |
| `--data-dir <path>` | Where YAML output goes. Defaults to `../data` |
| `--cache-dir <path>` | Where PDFs are cached. Defaults to `../data-cache` |
| `--dry-run` | Run discovery + download + extract; do NOT write YAML |
| `--skip-discover` | Skip discovery; use whatever is already in the cache |
| `--from-pdf SLUG=PATH` | Override discovery+download for one model with a local PDF. Repeatable |
| `--verbose` | INFO logging |

## Honda blocks us

`automobiles.honda.com` is fronted by Akamai Bot Manager. From most
non-residential IP ranges (including this dev environment and most CI
networks), the site returns **HTTP 403** to every request, regardless of
User-Agent string, headers, or rate. There is no robots.txt rule we are
violating — Akamai just won't serve us.

Concretely, the discovery step here was tested against:

- `https://automobiles.honda.com/civic` — 403 with `server: AkamaiGHost`
- `https://www.honda.com/` — same
- `https://hondanews.com/en-US/honda-automobiles` — same
- Direct PDF URL guesses (`/-/media/Honda-Automobiles/Vehicles/2026/...`) — same

The discoverer surfaces this as a clear per-model error including the URL
pattern it tried, which is what you'll see in the CLI's "Failed models"
report.

Workarounds the operator can use:

1. **Run the scraper from a residential IP** (or via a residential proxy you
   own). Honda's blocks are network-based; the code itself is correct.
2. **Download brochures manually in a browser** to
   `vehicle-feature-catalog/data-cache/brochures/honda/2026/<slug>.pdf`,
   then run with `--skip-discover`.
3. **Use `--from-pdf SLUG=PATH`** to point the pipeline at any PDF you have
   on disk.

If/when Honda exposes a sanctioned data feed (or `automobiles.honda.com`
stops bot-blocking), the discoverer needs no changes — it already parses
the page's anchor list and ranks PDF links by `brochure` / model slug /
anchor text.

## PDF extraction caveats

This is the messiest part of the pipeline.

- Honda's matrix glyphs (●, ○) sometimes render as different Unicode points
  per page. The extractor maps a small set of glyphs **and** the letter
  fallbacks (`S` / `O` / `—`); anything unrecognized becomes "no cell"
  (which collapses to "feature not listed for this trim").
- Matrix tables routinely span multiple pages. The extractor will absorb
  follow-on tables that match the trim-count of the first one. If Honda
  changes column order mid-document, results will be wrong.
- Section header rows (all-caps category banners) are skipped.
- Trailing footnote markers (`*`, `†`, `‡`, digits) are stripped from
  feature labels before matching.

When extraction is too sparse to be useful, the model is skipped and the
CLI report names it under "Failed models" — better than emitting bogus
data into a YAML you'll review later.

## Files

```
scrapers/honda_us/
├── __init__.py           Defaults (user-agent, default model list, headers)
├── discover.py           BrochureDiscoverer + the pure extract_brochure_url helper
├── download.py           BrochureDownloader — idempotent PDF cache
├── extract.py            BrochureExtractor — pdfplumber-driven matrix recovery
├── emit.py               CatalogEmitter — feature resolution + YAML writes
├── cli.py                Command-line entry point
└── README.md             This file
```

Tests live in `vehicle-feature-catalog/tests/python/scrapers/`. The
`test_emit` test exercises the full emit path against a temp copy of the
real catalog data dir and asserts the result loads + validates through
`VehicleFeatureCatalog.load`.

To regenerate the synthetic PDF fixture used by `test_extract`:

```bash
pip install reportlab
python tests/python/scrapers/fixtures/build_tiny_brochure.py
```
