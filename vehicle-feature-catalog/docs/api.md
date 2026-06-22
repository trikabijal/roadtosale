# Vehicle Feature Catalog — API Reference

Last updated: `2026-06-22`

The catalog is the single source of truth for vehicle Make / Model / Trim / Feature data and the sparse `Trim ↔ Feature` availability matrix.

This document is the binding facade contract plus the module's tooling entry points. Everything under "Facade" is public; everything not listed is internal and may NOT be imported by external code. See [`architecture.md`](./architecture.md) for how the pieces fit together and the [top-level system docs](../../docs/architecture.md) for where this module sits in the monorepo.

---

## 1. Facade — `VehicleFeatureCatalog`

| Language | Import |
|---|---|
| Python | `from vehicle_feature_catalog import VehicleFeatureCatalog` |
| TypeScript | `import { VehicleFeatureCatalog } from 'vehicle-feature-catalog'` (`dist/index.js`) |

Consumers MUST import only from the package entry point (`src/python/vehicle_feature_catalog/__init__.py` / `src/ts/index.ts`). Reaching into `loader.py`, `indexes.py`, `src/ts/internal`, etc. is forbidden and enforced by `scripts/check_imports.py`.

The Python and TypeScript facades expose the **same method set with the same names** (snake_case in both, on purpose). The only call-shape differences: TS `load()` is `async` (returns a `Promise`), and the TS `list_features` filters are passed as a single options object.

### Lifecycle — `load(data_dir)`

One-shot startup load. Reads every YAML file under `data_dir/{makes,models,trims,features,matrix}`, builds in-memory indexes, returns a ready-to-query instance.

- **Throws** `LoadError` on file-read / YAML-parse / schema-shape failures, and `DuplicateIdError` if two entities (across all types) claim the same ID.
- **No disk reads after load.** All subsequent queries are in-memory dict/`Map` lookups.
- **Immutable + read-safe** once returned. No mutation API.

> Note: `load()` itself does **not** run the integrity validator — it raises only on structural/duplicate problems. Reference-integrity checks (dangling `feature_id`/`trim_id`, empty `cue_phrases`, etc.) are surfaced by calling `validate()` (below) or running `scripts/validate.py`.

```python
from pathlib import Path
from vehicle_feature_catalog import VehicleFeatureCatalog

catalog = VehicleFeatureCatalog.load(Path("vehicle-feature-catalog/data"))
```

```ts
const catalog = await VehicleFeatureCatalog.load("vehicle-feature-catalog/data");
```

### Single-entity lookups

All raise / throw `NotFoundError` if the ID is unknown.

| Method | Returns |
|---|---|
| `get_make(make_id)` | `Make` |
| `get_model(model_id)` | `Model` |
| `get_trim(trim_id)` | `Trim` |
| `get_feature(feature_id)` | `Feature` |

### List queries

| Method | Returns | Notes |
|---|---|---|
| `list_makes()` | `Make[]` | All makes |
| `list_models(make_id=None)` | `Model[]` | Filter by make if given |
| `list_trims(model_id=None)` | `Trim[]` | Filter by model if given |
| `list_features(brand_scope=None, category=None)` | `Feature[]` | Filter by brand scope and/or category. **TS:** `list_features({ brand_scope?, category? })` |

### Matrix queries (the core of why the catalog exists)

| Method | Returns | Notes |
|---|---|---|
| `list_features_for_trim(trim_id, availability=['standard'])` | `Feature[]` | Features the rep can legitimately demo on this trim. Raises `NotFoundError` if the trim is unknown. De-dupes by `feature_id` |
| `list_trims_with_feature(feature_id)` | `Trim[]` | "Which trims have heated seats?" Raises `NotFoundError` if the feature is unknown. Excludes `unavailable` cells; de-dupes by `trim_id` |

`availability` accepts any subset of `['standard', 'optional']`. Pass both to include features the trim can be *ordered* with.

### Validation — `validate()`

Returns a `ValidationResult` describing catalog integrity (see Data types). Reported as **errors** (fail the build):

- Any `TrimFeature` references a missing `feature_id` (`matrix_unknown_feature`) or `trim_id` (`matrix_unknown_trim`)
- Any `Feature` has empty `cue_phrases` (`feature_empty_cue_phrases`)
- Any `Trim` has zero matrix cells (`trim_has_no_features`)
- Any duplicate ID across entities (`duplicate_id`)

Reported as **warnings** (non-fatal): a `Model` referencing an unknown `make_id` (`model_unknown_make`), a `Trim` referencing an unknown `model_id` (`trim_unknown_model`).

```python
result = catalog.validate()
if not result.is_valid:
    print(result.format_errors())
```

### What the facade does NOT expose

| Concern | Why not | Lives where |
|---|---|---|
| `CueAtom` projection | CueAtom is voice-engine vocabulary, not catalog vocabulary | Consumer (lab / app) projects `Feature → CueAtom` |
| Audit-item logic | Road-to-Sale-specific | `road-to-sale-app` |
| Cue matching | voice-engine's job | `voice-engine` |
| YAML paths, raw dicts, `CatalogIndexes` | Implementation detail | Internal |
| Mutation (`add_feature`, `update_trim`) | Catalog is immutable post-load; mutate by editing YAML in git | N/A |

---

## 2. Data types

Field shapes are mirrored 1:1 across `entities.py` and `entities.ts`.

### `Make`
```python
class Make:
    id: str                 # e.g. "honda"
    name: str               # e.g. "Honda"
    country: str            # e.g. "JP"
```

### `Model`
```python
class Model:
    id: str                 # e.g. "honda.civic"
    make_id: str
    name: str               # e.g. "Civic"
    year: int               # e.g. 2026
    body_style: str | None  # e.g. "compact_car"
```

### `Trim`
```python
class Trim:
    id: str                 # e.g. "honda.cr-v-hybrid-awd.2026.sport-touring"
    model_id: str
    name: str               # e.g. "Sport Touring"
    msrp_range: tuple[int, int] | None
```

### `Feature`
```python
class Feature:
    id: str                 # "universal.feature.wireless_apple_carplay"
                            #  or "honda.feature.honda_sensing_360plus"
    display_name: str
    category: str           # "connectivity", "driver_assistance", "comfort", ...
    brand_scope: str        # "universal" | "honda" | "toyota" | ...
    cue_phrases: list[str]  # rep-natural spoken variants (must be non-empty)
    synonyms: list[str]     # additional phrase variants
```

### `TrimFeature` (matrix cell)
```python
class TrimFeature:
    trim_id: str
    feature_id: str
    availability: Literal['standard', 'optional', 'unavailable']
```

`TrimFeature` is internal — consumers reach matrix data through `list_features_for_trim` / `list_trims_with_feature`, not by reading cells.

### `ValidationResult`
```python
class ValidationResult:
    is_valid: bool
    errors: list[ValidationError]      # {code, message, entity_id?}
    warnings: list[ValidationWarning]  # {code, message, entity_id?}
    def format_errors(self) -> str: ...
```

## 3. Errors

| Error | Python | TS | When |
|---|---|---|---|
| `CatalogError` | base exception | base `Error` subclass | Base class for all catalog errors |
| `NotFoundError` | exception | thrown | ID lookup fails |
| `LoadError` | exception | thrown | YAML parse / file read / schema-shape failure during `load` |
| `DuplicateIdError` | exception | thrown | Two entities claim the same ID |
| `ValidationError` | dataclass in `ValidationResult` | object in result | Integrity check fails (one per finding; not raised) |

---

## 4. Tooling entry points

These are CLIs/scripts, not part of the importable facade. They live under `scripts/` and `scrapers/`.

### `scripts/validate.py` — catalog validator CLI

```bash
python scripts/validate.py --data-dir vehicle-feature-catalog/data
```

Loads the catalog and runs `validate()`. Exits `0` if valid (prints warnings, if any), `1` otherwise (prints `format_errors()` to stderr). This is the script the **voice-lab CLI invokes** to gate the catalog in CI / pre-commit.

### `scripts/derive_vocab.py` — catalog → STT-vocab bridge

```bash
python3 scripts/derive_vocab.py [make_id ...]   # defaults to every make in data/makes/
```

Reads catalog YAML and writes one per-make term list to `voice-engine/cleanup-packs/derived/<make>.vocab.json` (model names + trim names + universal-feature display names and short synonyms, deduped). Consumed by the voice pipeline's road-to-sale lexicon. The output is **generated — regenerate, don't hand-edit**. See [voice-engine model contracts](../../voice-engine/docs/model-contracts.md) for how the vocab feeds the cleanup stage.

### `scrapers/honda_us/cli.py` — Honda US seeding pipeline

```bash
pip install -e '.[scrapers]'                       # one-time: pulls requests, beautifulsoup4, pdfplumber
python -m scrapers.honda_us.cli --verbose          # default: all 8 models, brochure-pdf source
python -m scrapers.honda_us.cli --source hondanews-html --verbose
```

Discovers/ingests Honda brochure PDFs or saved press-release HTML and emits catalog YAML under `data/`. Full flag reference and operator workflow: [`scrapers/honda_us/README.md`](../scrapers/honda_us/README.md). End-to-end trace: [`flows.md`](./flows.md), Flow 2.
