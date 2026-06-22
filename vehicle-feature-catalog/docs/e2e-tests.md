# Vehicle Feature Catalog — Test Plan (PRIMARY spec)

Last updated: `2026-06-22`

**Scope:** the `vehicle-feature-catalog/` module — the `VehicleFeatureCatalog` facade
(Python **and** TypeScript), the integrity `validate()` contract, the Honda US seeding
pipeline (`scrapers/honda_us/`), and the two tooling bridges (`scripts/validate.py`,
`scripts/derive_vocab.py`). The catalog ↔ voice-engine **isolation boundary**
(`scripts/check_imports.py`) is in scope as a contract this module must not break.

> **Philosophy.** This document is the **primary spec**: it describes what *should* be
> tested based on the module's public surface and pipelines, **independent of the tests
> that exist today**. Tests are the *implementation* of this plan. The
> [Coverage ledger](#coverage-ledger-plan-vs-implementation) at the end computes the
> delta (covered vs. pending) against the real test files — **no test code is written or
> modified by this document.**

> Where this module sits in the monorepo: see the top-level
> [system architecture](../../docs/architecture.md). What it feeds: the
> [voice-engine](../../voice-engine/docs/) cleanup vocab — `scripts/derive_vocab.py`
> emits `voice-engine/cleanup-packs/derived/<make>.vocab.json`, merged into the
> road-to-sale lexicon (see [voice-engine model contracts](../../voice-engine/docs/model-contracts.md)).

**Black-box rule (non-negotiable):** facade and validator tests drive only the **public
contract** — `VehicleFeatureCatalog.load / get_* / list_* / validate` and the
`ValidationResult` it returns — and assert observable results (returned entities, error
classes raised, validation codes). They never import `loader`, `indexes`, or
`CatalogIndexes` for facade-level assertions. The **scraper** is a separate operator-run
boundary: its public surface is each step's documented entry point
(`extract_brochure_url`, `BrochureExtractor.extract`, `extract_from_hondanews_html`,
`CatalogEmitter.emit`, `cli.main`) plus the **observable side effect** — YAML that loads
and `validate()`s clean through the *facade*. Pure-helper unit tests (glyph mapping,
label normalization) are the component-level backstop, not the primary surface.

---

## 1. Architecture as test surface

The module is four library layers + one seeding pipeline + two bridge scripts. Each box
below is a contract under test. The Python and TS packages are **independent
reimplementations of one contract** sharing only the YAML data dir — so contract tests
are authored **once per language** with **identical fixtures and identical assertions**;
a divergence is a bug in one implementation, not in the test.

```
                         data/ (YAML, source of truth)
                                   │
   [Seeding pipeline] ──emits──►   │   ◄──reads── [derive_vocab.py] ──► voice-engine vocab JSON
   discover→download→extract       │
        / extract_hondanews        ▼
              └─emit──►   YAMLLoader ──► CatalogIndexes ──► VehicleFeatureCatalog (facade)
                                                                  │           │
                                                              validate()   list_*/get_*
                                                                  │
                                                          scripts/validate.py (CLI gate)
```

### Facade Coverage Ledger — every public boundary needs ≥1 Tier-1/Tier-2 test

| Facade / boundary | Public surface under test | Plan scenarios |
|---|---|---|
| `VehicleFeatureCatalog.load` | load fixture dir → ready instance; structural/dup errors | C-LOAD-1..4 |
| Single-entity lookups | `get_make/model/trim/feature`; `NotFoundError` | C-GET-1..2 |
| List queries | `list_makes/models/trims/features` (+ filters) | C-LIST-1..4 |
| Matrix queries | `list_features_for_trim` (availability), `list_trims_with_feature` (excl. unavailable, de-dupe) | C-MTX-1..5 |
| `validate()` | valid catalog; each error code; warnings; `format_errors` | C-VAL-1..6 |
| Loader shape-checks | missing dir / bad YAML / missing key / bad availability / msrp tuple | C-LDR-1..5 |
| Python↔TS parity | same method set → same results on same fixtures | C-PAR-1..2 |
| Scraper: discover | `extract_brochure_url` ranking; `discover` error/diagnostic paths | S-DSC-1..5 |
| Scraper: download | idempotent cache, retry, target path | S-DL-1..3 |
| Scraper: extract (PDF) | glyph→availability, label normalize, header detect, row absorb, real-PDF gate | S-EXT-1..6 |
| Scraper: extract (hondanews HTML) | table / per-trim-list / fallback paths, confidence tiers, missing file | S-HN-1..9 |
| Scraper: emit | resolve-or-create features, write model/trim YAML, append matrix, preserve seeds, dry-run, needs_review | S-EMT-1..7 |
| Scraper: cli | end-to-end `main()` per source, exit codes, unknown-slug guard, `--from-pdf`/`--dry-run` | S-CLI-1..5 |
| `scripts/validate.py` | exit 0 valid, exit 1 invalid/load-fail, warnings printed | T-VLD-1..3 |
| `scripts/derive_vocab.py` | catalog→per-make vocab JSON; dedupe; short-synonym rule; isolation-safe write | T-VOC-1..5 |
| Isolation boundary | `check_imports.py` finds no catalog↔voice-engine import in either direction | T-ISO-1..2 |

---

## 2. Test tiers

| Tier | What | When | Budget |
|---|---|---|---|
| **Tier 1 — critical path** | `load` → `list_features_for_trim` / `list_trims_with_feature` correctness; `validate()` passes on real `data/`; loader rejects malformed YAML; Python↔TS facade parity on the matrix queries | Every commit | < 30 s |
| **Tier 2 — integration** | All validator error/warning codes against an invalid fixture tree; emit → reload → validate round-trip; hondanews/PDF extraction strategies; `validate.py` + `derive_vocab.py` CLIs; the import-boundary check | Before every PR / release | 1–3 min |
| **Tier 3 — edge / stress** | Full `cli.main()` end-to-end per source; real-PDF extraction (gated); large/duplicate/low-confidence matrix handling; vocab-window scaling; cross-language drift sweep over the whole real catalog | Weekly / major release | 3–10 min |

Tests needing an artifact that can't ship in the repo (a verbatim Honda brochure PDF,
saved press-release HTML) **`skip`** when the artifact is absent rather than fail — see
[§6 Conventions](#6-conventions).

---

## 2.5 The PRIMARY scenarios — by contract

### Catalog load + lookup (`VehicleFeatureCatalog`)

- **C-LOAD-1 (Tier 1):** `load(fixtures)` returns a ready `VehicleFeatureCatalog`
  instance with no disk reads required afterward.
- **C-LOAD-2 (Tier 1):** loading the **real `data/` tree** (not just the fixture)
  succeeds — the shipped catalog is structurally loadable.
- **C-LOAD-3 (Tier 2):** a duplicate ID across *any* entity types raises
  `DuplicateIdError` at index-build time (note: surfaced via `load`, distinct from the
  `validate()` `duplicate_id` finding which is non-raising).
- **C-LOAD-4 (Tier 2):** `load` raises `LoadError` (not a bare exception) on a structurally
  broken data dir.
- **C-GET-1 (Tier 1):** `get_make/get_model/get_trim/get_feature` return the right entity
  with correct fields (`name`, `country`, `display_name`, …).
- **C-GET-2 (Tier 1):** each `get_*` raises `NotFoundError` on an unknown ID.
- **C-LIST-1 (Tier 1):** `list_makes()` returns every make.
- **C-LIST-2 (Tier 2):** `list_models(make_id)` filters by make; `list_models()` returns all.
- **C-LIST-3 (Tier 2):** `list_trims(model_id)` filters by model; unfiltered returns all.
- **C-LIST-4 (Tier 2):** `list_features(brand_scope=…, category=…)` filters by each, and
  by both together.

### Matrix queries (the core of why the catalog exists)

- **C-MTX-1 (Tier 1):** `list_features_for_trim(trim)` defaults to `standard`-only and
  returns exactly the standard features for that trim.
- **C-MTX-2 (Tier 1):** passing `availability=['standard','optional']` adds the optional
  features and nothing else.
- **C-MTX-3 (Tier 1):** `list_features_for_trim` raises `NotFoundError` on an unknown trim.
- **C-MTX-4 (Tier 1):** `list_trims_with_feature(feature)` returns every trim that has the
  feature **excluding `unavailable` cells**, de-duped by `trim_id`, across makes.
- **C-MTX-5 (Tier 2):** `list_trims_with_feature` raises `NotFoundError` on an unknown
  feature; both matrix queries de-dupe when the matrix repeats a `(trim, feature)` pair.

### Validation (`validate()` + `scripts/validate.py`)

- **C-VAL-1 (Tier 1):** `validate()` on the happy fixture (and on real `data/`) returns
  `is_valid=True` with empty `errors`.
- **C-VAL-2 (Tier 2):** the invalid fixture tree surfaces **all four error codes**:
  `matrix_unknown_feature`, `matrix_unknown_trim`, `feature_empty_cue_phrases`,
  `trim_has_no_features`.
- **C-VAL-3 (Tier 2):** `duplicate_id` is reported (incl. cross-entity-type collisions),
  as a non-raising finding.
- **C-VAL-4 (Tier 2):** `feature_empty_cue_phrases` and `trim_has_no_features` each fire
  in isolation, with the correct `entity_id`.
- **C-VAL-5 (Tier 2):** `format_errors()` lists each finding by code (human-readable).
- **C-VAL-6 (Tier 2):** parent-reference problems (`model_unknown_make`,
  `trim_unknown_model`) are **warnings**, not errors — they do not flip `is_valid`.
  *(Gap candidate: warnings path is documented but lightly asserted.)*

### Loader shape-checks (`YAMLLoader.read_all`)

- **C-LDR-1 (Tier 2):** missing `data_dir` raises `LoadError`.
- **C-LDR-2 (Tier 2):** malformed YAML raises `LoadError` (wrapped, not raw `YAMLError`).
- **C-LDR-3 (Tier 2):** a missing required key (e.g. make without `country`) raises
  `LoadError`.
- **C-LDR-4 (Tier 2):** an invalid `availability` value in a matrix cell raises `LoadError`.
- **C-LDR-5 (Tier 1):** `read_all` returns the full entity set from the fixture
  (counts/IDs), and `msrp_range` parses to a 2-tuple; `cue_phrases` parse as a list.
  *(Gap candidate: empty-file, non-mapping top-level, and `msrp_range` wrong-length are
  in the loader code but not all asserted.)*

### Python ↔ TS facade parity

- **C-PAR-1 (Tier 1):** the TS facade exposes the **same method set** as Python
  (`load/get_*/list_*/validate`), with TS `load` async and `list_features` taking an
  options object — and returns the **same IDs/values** on the **same fixtures** for the
  matrix queries (`list_features_for_trim`, `list_trims_with_feature`).
- **C-PAR-2 (Tier 2):** TS `validate()` produces the **same error codes** as Python on
  the shared invalid fixture tree.
  *(Today this parity is achieved by mirrored-but-separate suites, not a single
  cross-language assertion — see ledger.)*

### Scraper — discover (`discover.py`)

- **S-DSC-1 (Tier 2):** `extract_brochure_url` ranks a `.pdf` containing both "brochure"
  and the slug highest; returns an absolute URL.
- **S-DSC-2 (Tier 2):** falls back to anchor-text "brochure" when neither URL token matches.
- **S-DSC-3 (Tier 2):** returns `None` when no PDF link exists.
- **S-DSC-4 (Tier 2):** `BrochureDiscoverer.discover` (fake session, no network) honors
  `MODEL_PAGE_OVERRIDES` and records found vs. errors.
- **S-DSC-5 (Tier 2):** a 403/blocked page yields a clear diagnostic naming the slug,
  Akamai/blocking, and the `--from-pdf` bypass.

### Scraper — download (`download.py`)

- **S-DL-1 (Tier 2):** `target_path` is the deterministic
  `data-cache/brochures/honda/<year>/<slug>.pdf`.
- **S-DL-2 (Tier 2):** `download_all` **skips** an already-cached non-empty file
  (idempotent) and records it under `skipped_cached`.
- **S-DL-3 (Tier 3):** retry on 429/5xx then surface a clear failure; empty body is
  treated as failure (no zero-byte file left behind). *(Pure-unit with a fake session.)*

### Scraper — extract from PDF (`extract.py`)

- **S-EXT-1 (Tier 2):** `_cell_to_availability` maps the glyph/word set
  (●/S→standard, ○/O→optional, —/blank→unavailable/None).
- **S-EXT-2 (Tier 2):** `_normalize_label` collapses whitespace and strips trailing
  footnote markers (without eating a trailing ®).
- **S-EXT-3 (Tier 2):** `_detect_header_row` picks the trim-name row, not a glyph row.
- **S-EXT-4 (Tier 2):** `_absorb_rows` builds `FeatureRow`s with the right per-trim
  availability and skips blank rows.
- **S-EXT-5 (Tier 3):** real-PDF `extract()` end-to-end — **gated/skipped** when no
  fixture PDF is committed.
- **S-EXT-6 (Tier 3):** `is_useful()` gating (trims + rows + cells>0) and the text-line
  fallback when `extract_tables` finds nothing. *(Gap candidate: `is_useful`/text-fallback
  not directly asserted.)*

### Scraper — extract from hondanews HTML (`extract_hondanews.py`)

- **S-HN-1 (Tier 2):** the comparison-table strategy recovers the expected trim list.
- **S-HN-2 (Tier 2):** feature rows carry correct per-trim availability across mixed cells
  (glyph, S/O letters, literal "Standard", em-dash, double-bullet `●●`→standard).
- **S-HN-3 (Tier 2):** a rich table wins over per-trim lists and tags `high` confidence
  (≥5 cells).
- **S-HN-4 (Tier 2):** per-trim "Standard Equipment" lists become the source when there's
  no table; trim-scoped availability is correct (a Touring-only feature has no Sport cell).
- **S-HN-5 (Tier 2):** a structureless press release yields `low` confidence + a
  manual-review warning.
- **S-HN-6 (Tier 2):** a missing HTML file does **not** raise — returns empty + `low` +
  a "not found" warning (the documented operator pre-condition).
- **S-HN-7 (Tier 2):** `model_hint` is guessed from `<title>`/`<h1>`.
- **S-HN-8 (Tier 2):** `_cell_text_to_availability` / `_normalize_label` /
  `_clean_trim_name` helpers behave (drivetrain-suffix stripping, footnotes, N/A).
- **S-HN-9 (Tier 2):** `ExtractedModel` is the documented alias of
  `ExtractedHondanewsModel` (spec-name compatibility).
- *(Gap candidate S-HN-10, Tier 3):* the free-text "X is standard on A, B" fallback
  (`_extract_fallback`) is reached and emits cells — exercised only indirectly today.

### Scraper — emit (`emit.py`)

- **S-EMT-1 (Tier 2):** `emit` resolves a known label to an existing feature ID via the
  alias table / display-name / synonym / cue match.
- **S-EMT-2 (Tier 2):** an **unmapped** label mints a new `honda.feature.<slug>` file
  with a seed `cue_phrase` (the display name) and a guessed category.
- **S-EMT-3 (Tier 2):** model + trim YAML are written under the expected paths; the
  resulting dir **reloads and `validate()`s clean** through the facade (the real
  acceptance test for emit).
- **S-EMT-4 (Tier 2):** appending the matrix **preserves the hand-seeded CR-V Hybrid AWD
  entries** — never overwrites them.
- **S-EMT-5 (Tier 2):** `dry_run=True` writes **nothing** to disk yet still reports
  `matrix_cells_added`.
- **S-EMT-6 (Tier 3):** cells dedupe on `(trim, feature)` with stronger availability
  winning (standard > optional > unavailable). *(Gap candidate: asserted indirectly.)*
- **S-EMT-7 (Tier 3):** a `low`-confidence `ExtractedModel` annotates emitted matrix
  entries with `extraction_confidence: low` / `needs_review: true` (the operator's grep
  queue), and the loader still ignores those extra keys. *(Gap candidate: not asserted.)*

### Scraper — CLI (`cli.py`)

- **S-CLI-1 (Tier 3):** `main(['--source','hondanews-html', …])` end-to-end against a
  fixture HTML dir emits YAML and returns exit 0.
- **S-CLI-2 (Tier 3):** `main()` brochure-pdf path with `--from-pdf SLUG=PATH` bypasses
  discovery/download and emits.
- **S-CLI-3 (Tier 2):** an **unknown model slug** returns exit code 2 with a clear message.
- **S-CLI-4 (Tier 3):** `--dry-run` prints the run report but writes nothing; brochure
  source returns exit 1 when nothing emitted, 0 when something did.
- **S-CLI-5 (Tier 3):** hondanews source returns exit 0 even when no `<slug>.html` files
  exist (documented "no files cached yet" pre-condition). *(Gap candidate: whole CLI
  layer is currently untested.)*

### Tooling — `scripts/validate.py`

- **T-VLD-1 (Tier 2):** exits `0` and prints "valid" on the real `data/` (or happy fixture).
- **T-VLD-2 (Tier 2):** exits `1` and prints `format_errors()` to stderr on the invalid
  fixture tree.
- **T-VLD-3 (Tier 2):** exits `1` on a `LoadError` (unreadable/missing data dir).
  *(Gap candidate: the CLI wrapper is untested; only the underlying `validate()` is.)*

### Tooling — `scripts/derive_vocab.py` (catalog → STT vocab bridge)

- **T-VOC-1 (Tier 2):** `derive_make('honda')` returns a dict with `make_id`, `make_name`,
  `models`, `trims`, `features`, `terms`, and `counts` consistent with those lists.
- **T-VOC-2 (Tier 2):** terms are **deduped case-insensitively**, models+trims ordered
  before features.
- **T-VOC-3 (Tier 2):** only **short** universal-feature synonyms (≤3 words) are
  included; sentence-like `cue_phrases` are **excluded**.
- **T-VOC-4 (Tier 2):** `main()` writes `voice-engine/cleanup-packs/derived/<make>.vocab.json`
  per make, one file per make, marked "derived; do not hand-edit".
- **T-VOC-5 (Tier 3):** the write target is the voice-engine dir (file handoff, not a code
  import) — i.e. derive runs without importing voice-engine. *(Gap candidate: the entire
  `derive_vocab.py` bridge is currently untested.)*

### Isolation boundary — `scripts/check_imports.py`

- **T-ISO-1 (Tier 2):** running `check_imports.py` over the repo reports **no violations**
  (catalog `src/`, `scrapers/`, `tests/` never import `voice_lab`; voice-engine `src/`
  never imports `vehicle_feature_catalog`).
- **T-ISO-2 (Tier 3):** the checker *would* flag a planted violation (negative control),
  and the voice-engine **lab** exemption holds. *(Gap candidate: boundary check not run as
  a test in this module's suite.)*

---

## 3. Test data / fixtures

| Tree | Purpose |
|---|---|
| `tests/fixtures/` | The **valid** catalog: Honda + Toyota makes, Civic + Corolla models, three Civic/Corolla trims, four features (3 universal + 1 honda-scoped), `matrix/{honda,toyota}.yaml` (9 cells). Drives every happy-path facade/loader/validator test in both languages. |
| `tests/fixtures-invalid/` | The **invalid** catalog: `no_cues.yaml` (empty cue_phrases), an `orphan.yaml` trim with no matrix cells, and a `matrix/honda.yaml` referencing unknown trim/feature IDs — engineered to trip all four error codes at once. |
| `tests/python/scrapers/fixtures/tiny_press_release.html` | Saved-page hondanews fixture: Trim Levels list + comparison table (mixed glyph/word cells) + per-trim lists. |
| `tests/python/scrapers/fixtures/tiny_brochure.pdf` | **Not committed** — `build_tiny_brochure.py` can generate one; the real-PDF test skips when absent. |
| `data/` (real catalog) | The shipped Honda+Toyota data. Used as the acceptance input for "real catalog loads + validates" and as the emit-test base (copied into a tmp dir). |

Conventions: the same fixture dir and the same expected IDs/counts are shared **verbatim**
across the Python and TS suites — that sharing *is* the parity guarantee.

---

## 4. Black-box discipline

- Facade/validator tests import only from the package entry point
  (`from vehicle_feature_catalog import …` / `'../../src/ts/index.js'`) and assert
  returned entities, raised error classes, and `ValidationResult` codes — never index
  internals. The validator's *pure* `Validator.check` / `validateCatalog` is exercised
  directly only for the synthetic single-code cases (duplicate/empty/orphan), which is
  acceptable: it's a documented pure function, and the catalog-level path is also covered
  through `catalog.validate()`.
- Scraper tests treat each step's documented entry point as the surface and assert the
  **observable side effect**: emit's real acceptance is "the written dir loads and
  validates through the facade," not internal call counts. Pure helpers (glyph mapping,
  label normalization, URL ranking) are component backstops.
- No test reaches across the catalog ↔ voice-engine boundary by import; the vocab bridge
  is validated by its **file output**, never by importing voice-engine code.

---

## Coverage ledger (plan vs. implementation)

Computed against the real suites: **52 Python tests** (`tests/python/`) + **27 TS tests**
(`tests/ts/`). `✅ COVERED` only when a named real test asserts the item.

| Plan item | Tier | Status | Evidence / notes |
|---|---|---|---|
| C-LOAD-1 load → instance | 1 | ✅ COVERED | py `test_load_returns_catalog`; ts `loads` |
| C-LOAD-2 real `data/` loads | 1 | ⛔ PENDING | Only the **fixture** dir is loaded in tests; nothing loads the shipped `data/` tree |
| C-LOAD-3 duplicate-id at build | 2 | ⚠️ PARTIAL | `duplicate_id` tested via `Validator.check` (non-raising), but the **`DuplicateIdError` raised by `CatalogIndexes.build` during `load`** is not asserted |
| C-LOAD-4 `LoadError` on broken dir | 2 | ✅ COVERED | py `test_missing_data_dir_raises_load_error` (+ malformed/missing-key); ts mirrors |
| C-GET-1 get_* correct fields | 1 | ✅ COVERED | py `test_get_make`, `test_get_model_and_trim_and_feature`; ts `get_make…`, `get_model / get_trim / get_feature work` |
| C-GET-2 get_* NotFound | 1 | ✅ COVERED | py `test_get_make_unknown_raises`; ts `get_make throws NotFoundError…` |
| C-LIST-1 list_makes | 1 | ✅ COVERED | py `test_list_makes`; ts `list_makes returns both makes` |
| C-LIST-2 list_models filter | 2 | ✅ COVERED | py `test_list_models_filtered`; ts `list_models filters by make` |
| C-LIST-3 list_trims filter | 2 | ✅ COVERED | py `test_list_trims_filtered`; ts `list_trims filters by model` |
| C-LIST-4 list_features filters | 2 | ⚠️ PARTIAL | brand_scope + category each covered (py `test_list_features_filter_*`; ts mirrors); **combined `brand_scope AND category`** not asserted |
| C-MTX-1 features_for_trim standard | 1 | ✅ COVERED | py `test_list_features_for_trim_standard_only`; ts mirror |
| C-MTX-2 +optional | 1 | ✅ COVERED | py `test_..._includes_optional_when_requested`; ts mirror |
| C-MTX-3 unknown trim raises | 1 | ✅ COVERED | py `test_list_features_for_unknown_trim_raises`; ts mirror |
| C-MTX-4 trims_with_feature (excl. unavailable) | 1 | ✅ COVERED | py `test_list_trims_with_feature` (cross-make: honda+toyota); ts mirror |
| C-MTX-5 unknown feature raises + de-dupe | 2 | ⚠️ PARTIAL | unknown-feature raise covered (py `test_list_trims_with_unknown_feature_raises`); **explicit de-dupe of a repeated `(trim,feature)` cell** not asserted |
| C-VAL-1 valid catalog | 1 | ✅ COVERED | py `test_validate_on_happy_catalog`, `test_happy_catalog_validates`; ts `happy fixture validates` |
| C-VAL-2 four error codes | 2 | ✅ COVERED | py `test_invalid_fixture_surfaces_all_relevant_errors`; ts `invalid fixture surfaces…` |
| C-VAL-3 duplicate_id finding | 2 | ✅ COVERED | py `test_duplicate_id_is_reported`; ts `reports duplicate ids` |
| C-VAL-4 empty-cue / orphan-trim isolated | 2 | ✅ COVERED | py `test_empty_cue_phrases_alone_is_an_error`, `test_trim_without_features_is_an_error` (asserts entity_id); ts mirrors |
| C-VAL-5 format_errors | 2 | ✅ COVERED | py `test_format_errors_lists_each_finding`; ts `format_errors prints each finding` |
| C-VAL-6 parent-ref **warnings** | 2 | ⛔ PENDING | `model_unknown_make` / `trim_unknown_model` warning paths exist in `validator.py` but **no test asserts a warning** (vs. error) |
| C-LDR-1 missing dir | 2 | ✅ COVERED | py `test_missing_data_dir_raises_load_error`; ts mirror |
| C-LDR-2 malformed YAML | 2 | ✅ COVERED | py `test_malformed_yaml_raises_load_error`; ts mirror |
| C-LDR-3 missing key | 2 | ✅ COVERED | py `test_missing_required_key_raises_load_error`; ts mirror |
| C-LDR-4 bad availability | 2 | ✅ COVERED | py `test_invalid_availability_raises_load_error`; ts mirror |
| C-LDR-5 full entity set + msrp tuple | 1 | ⚠️ PARTIAL | counts/IDs + msrp tuple + cue list covered (py `test_read_all_*`, `test_msrp_range_*`, `test_feature_cue_phrases_parsed`; ts mirror). **Not** covered: empty-file, non-mapping top-level, msrp wrong-length `LoadError` branches |
| C-PAR-1 Py↔TS parity (methods+results) | 1 | ⚠️ PARTIAL | Achieved by **two mirrored suites** with identical fixtures/assertions — strong in practice, but there is **no single cross-language test** pinning them; drift is caught only by both suites being hand-kept in sync |
| C-PAR-2 Py↔TS validator parity | 2 | ⚠️ PARTIAL | same: ts `validator` suite mirrors py `test_validator`, no unified assertion |
| S-DSC-1 url ranking | 2 | ✅ COVERED | `test_extract_brochure_url_prefers_brochure_and_slug` |
| S-DSC-2 anchor-text fallback | 2 | ✅ COVERED | `test_extract_brochure_url_falls_back_to_anchor_text` |
| S-DSC-3 None when no pdf | 2 | ✅ COVERED | `test_extract_brochure_url_returns_none_when_no_pdf` |
| S-DSC-4 discover w/ overrides | 2 | ✅ COVERED | `test_discoverer_uses_model_page_overrides` (fake session) |
| S-DSC-5 403 diagnostic | 2 | ✅ COVERED | `test_discoverer_reports_403_with_diagnostic_message`, `…reports_clear_error_when_no_pdf_found` |
| S-DL-1 target_path | 2 | ⛔ PENDING | `download.py` has **no test file** |
| S-DL-2 idempotent skip | 2 | ⛔ PENDING | not tested |
| S-DL-3 retry / empty-body | 3 | ⛔ PENDING | not tested |
| S-EXT-1 glyph→availability | 2 | ✅ COVERED | `test_cell_to_availability_glyphs` |
| S-EXT-2 normalize label | 2 | ✅ COVERED | `test_normalize_label_strips_footnote_markers` |
| S-EXT-3 detect header row | 2 | ✅ COVERED | `test_detect_header_row_picks_trim_row` |
| S-EXT-4 absorb rows | 2 | ✅ COVERED | `test_absorb_rows_builds_feature_rows` |
| S-EXT-5 real PDF e2e | 3 | ⚠️ PARTIAL | `test_extract_real_pdf_skipped_when_no_fixture` — **skips** (no committed PDF), so the pdfplumber path is effectively unexercised in CI |
| S-EXT-6 is_useful / text fallback | 3 | ⛔ PENDING | neither `is_useful()` nor `_extract_from_text` is directly asserted |
| S-HN-1 table trims | 2 | ✅ COVERED | `test_extract_returns_expected_trims_from_table` |
| S-HN-2 mixed-cell availability | 2 | ✅ COVERED | `test_extract_returns_feature_rows_with_availability` |
| S-HN-3 table wins + high conf | 2 | ✅ COVERED | `test_extract_picks_table_over_per_trim_lists_when_richer` |
| S-HN-4 per-trim lists path | 2 | ✅ COVERED | `test_per_trim_lists_only_layout` |
| S-HN-5 low confidence | 2 | ✅ COVERED | `test_missing_sections_yields_low_confidence` |
| S-HN-6 missing file no-raise | 2 | ✅ COVERED | `test_missing_file_does_not_raise` |
| S-HN-7 model_hint | 2 | ✅ COVERED | `test_model_hint_guessed_from_title` |
| S-HN-8 cell/label/trim helpers | 2 | ✅ COVERED | `test_cell_text_to_availability_handles_known_values`, `test_normalize_label_*`, `test_clean_trim_name_strips_drivetrain_suffix` |
| S-HN-9 ExtractedModel alias | 2 | ✅ COVERED | `test_alias_extracted_model_matches_subclass` |
| S-HN-10 free-text fallback | 3 | ⛔ PENDING | `_extract_fallback` (`X is standard on A,B`) not exercised |
| S-EMT-1 resolve known feature | 2 | ✅ COVERED | `test_emit_produces_valid_catalog` asserts mapped universal IDs land in the matrix |
| S-EMT-2 mint new feature | 2 | ✅ COVERED | same test: "Premium Trunk Liner" → `honda.feature.*` file created |
| S-EMT-3 emit → reload → validate | 2 | ✅ COVERED | `test_emit_produces_valid_catalog` (loads tmp dir, asserts `validate().is_valid`) |
| S-EMT-4 preserve CR-V seeds | 2 | ✅ COVERED | same test asserts `honda.cr-v-hybrid-awd.2026.sport` survives |
| S-EMT-5 dry-run writes nothing | 2 | ✅ COVERED | `test_emit_dry_run_does_not_write` |
| S-EMT-6 cell dedupe stronger-wins | 3 | ⛔ PENDING | `_dedupe_cells` rank logic not directly asserted |
| S-EMT-7 needs_review annotation | 3 | ⛔ PENDING | low-confidence → `needs_review: true` on matrix entry **not tested** (only hondanews extractor's confidence tag is) |
| S-CLI-1 cli hondanews e2e | 3 | ⛔ PENDING | `cli.py` has **no test**; `main()` never invoked |
| S-CLI-2 cli `--from-pdf` | 3 | ⛔ PENDING | not tested |
| S-CLI-3 unknown slug exit 2 | 2 | ⛔ PENDING | not tested |
| S-CLI-4 `--dry-run` / exit codes | 3 | ⛔ PENDING | not tested |
| S-CLI-5 hondanews empty-dir exit 0 | 3 | ⛔ PENDING | not tested |
| T-VLD-1..3 validate.py CLI | 2 | ⛔ PENDING | `scripts/validate.py` (argparse wrapper, exit codes, stderr) is **untested**; only `catalog.validate()` underneath is |
| T-VOC-1..5 derive_vocab.py | 2/3 | ⛔ PENDING | `scripts/derive_vocab.py` is **entirely untested** — no test exercises `derive_make`, dedupe, the ≤3-word synonym rule, or the JSON write |
| T-ISO-1..2 check_imports boundary | 2/3 | ⛔ PENDING | `scripts/check_imports.py` is run in CI per its docstring, but **no test in this module** asserts "no violations" or the planted-violation negative control |

### Headline gaps (called out explicitly)

1. **`derive_vocab.py` has zero tests** — the catalog→STT-vocab bridge (the module's whole
   reason-for-being #2, feeding voice-engine) is unverified. Dedupe, the short-synonym
   rule, and the per-make JSON write are all untested.
2. **`scripts/validate.py` CLI is untested** — exit codes and stderr output (the CI/voice-lab
   gate) rely on `catalog.validate()` being right but never assert the wrapper itself.
3. **The scraper CLI (`cli.py`) is untested end-to-end** — every step *unit* is covered
   (discover, extract, emit) but `main()` wiring, `--source` branching, exit codes, and
   `--from-pdf`/`--dry-run`/unknown-slug handling are not.
4. **`download.py` is untested** — no coverage of idempotent caching, target path, or retry.
5. **Python↔TS parity is convention, not assertion** — two hand-mirrored suites, no single
   cross-language test pins them; drift is only caught by keeping both green by hand.
6. **Real `data/` is never loaded/validated by a test** — the shipped catalog could regress
   (a bad emitted YAML, a dangling matrix ref) without any suite catching it; only the tiny
   fixtures are exercised.
7. **`needs_review` / low-confidence emit annotation untested** — the operator's review-queue
   signal (`extraction_confidence: low`) round-trip through emit is not asserted.
8. **Real-PDF extraction effectively unexercised** (S-EXT-5 always skips in CI), and the
   `is_useful()` / text-line fallback / `_extract_fallback` branches are dark.

---

## Pending test backlog

Concrete tests to write, grouped by tier. **No test code has been written or modified by
this document — these are proposals only.**

### Tier 1 (add to the fast suite)
1. `test_real_data_loads_and_validates` — `VehicleFeatureCatalog.load("data")` then
   `validate().is_valid` on the **shipped** catalog (Python; mirror in TS). *(closes C-LOAD-2,
   gap #6)*
2. `test_duplicate_id_raises_on_load` — a fixture with a colliding ID across two entity
   types raises `DuplicateIdError` from `load`, not just a `validate()` finding. *(C-LOAD-3)*

### Tier 2 (PR/release gate)
3. `test_list_features_combined_filters` — `list_features(brand_scope=…, category=…)` with
   both set (py + ts). *(C-LIST-4)*
4. `test_matrix_query_dedupes_repeated_cell` — a matrix repeating a `(trim,feature)` pair
   yields one entry from both matrix queries. *(C-MTX-5)*
5. `test_validate_emits_parent_warnings` — a model with an unknown `make_id` (and a trim
   with unknown `model_id`) produces **warnings**, `is_valid` stays governed by errors only
   (py + ts). *(C-VAL-6)*
6. `test_loader_rejects_empty_and_non_mapping_and_bad_msrp` — empty file, list-at-top-level,
   and 3-element `msrp_range` each raise `LoadError` (py + ts). *(C-LDR-5)*
7. `test_validate_cli_exit_codes` — invoke `scripts/validate.py` (subprocess or `main(argv)`):
   exit 0 + "valid" on happy/`data`, exit 1 + stderr on invalid fixtures, exit 1 on bad dir.
   *(T-VLD-1..3, gap #2)*
8. `test_derive_make_shape_and_dedup` — `derive_make('honda')` returns consistent
   counts/lists, deduped case-insensitively, models+trims before features. *(T-VOC-1,2)*
9. `test_derive_excludes_long_synonyms_and_cue_phrases` — only ≤3-word universal-feature
   synonyms included; sentence-like cue_phrases excluded. *(T-VOC-3)*
10. `test_derive_writes_per_make_json` — `main(['honda'])` writes
    `voice-engine/cleanup-packs/derived/honda.vocab.json` (to a tmp/redirected OUT_DIR),
    marked "do not hand-edit". *(T-VOC-4,5, gap #1)*
11. `test_download_idempotent_and_target_path` — `target_path` is deterministic;
    `download_all` skips a pre-existing non-empty cache file (fake session). *(S-DL-1,2)*
12. `test_emit_low_confidence_sets_needs_review` — emit with a `low`-confidence
    `ExtractedModel` writes `needs_review: true`/`extraction_confidence: low` on the matrix
    entry, and the loader still ignores those keys. *(S-EMT-7, gap #7)*
13. `test_cli_unknown_slug_exits_2` — `cli.main(['--models','nope'])` returns 2 with a clear
    message. *(S-CLI-3)*
14. `test_check_imports_reports_no_violations` — run `check_imports.py` (or import its
    `check_python`/`check_typescript`) over the repo, assert zero violations. *(T-ISO-1, gap #5
    boundary)*

### Tier 3 (weekly / major release)
15. `test_cli_hondanews_end_to_end` — `cli.main(['--source','hondanews-html',
    '--hondanews-dir',<fixture>, '--data-dir',<tmp>])` emits YAML, returns 0, and the tmp
    dir reloads + validates. *(S-CLI-1, gap #3)*
16. `test_cli_from_pdf_path` — `--from-pdf civic=<tiny.pdf>` bypasses discover/download and
    emits (gated on a generated tiny PDF). *(S-CLI-2)*
17. `test_cli_dry_run_writes_nothing` — `--dry-run` prints the report, writes nothing, exit
    codes correct per source. *(S-CLI-4,5)*
18. `test_emit_dedupe_stronger_availability_wins` — `_dedupe_cells` keeps standard over
    optional over unavailable. *(S-EMT-6)*
19. `test_extract_is_useful_and_text_fallback` — `is_useful()` gating + `_extract_from_text`
    on a synthetic glyph-line page. *(S-EXT-6)*
20. `test_hondanews_free_text_fallback` — a press release with only prose ("X is standard on
    A and B") yields cells via `_extract_fallback`. *(S-HN-10)*
21. `test_real_pdf_extraction` — generate a tiny PDF via `build_tiny_brochure.py` and run
    `BrochureExtractor.extract`, asserting non-empty trims/rows (un-skips S-EXT-5). *(gap #8)*
22. `test_check_imports_flags_planted_violation` — negative control: a temp file importing
    across the boundary is flagged; the lab exemption is honored. *(T-ISO-2)*
23. `test_cross_language_parity_harness` — a shared expectations table (IDs/values per
    fixture query) asserted by **both** suites from one source, so Python/TS drift fails a
    test rather than silently. *(C-PAR-1,2, gap #5)*

### Count summary

| Tier | Pending tests |
|---|---|
| Tier 1 | 2 |
| Tier 2 | 12 |
| Tier 3 | 9 |
| **Total** | **23** |

Of **63 plan scenarios**: **~36 ✅ COVERED**, **~9 ⚠️ PARTIAL**, **~18 ⛔ PENDING** — the
covered set is the entire library facade/validator/loader contract (Python + TS) plus all
scraper *units*; the pending set is the **tooling bridges** (`derive_vocab`, `validate.py`,
`check_imports`), the **CLI/download** integration layer, **real-`data/`** acceptance, and
a **first-class Python↔TS parity** harness.
