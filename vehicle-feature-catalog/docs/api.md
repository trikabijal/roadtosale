# Vehicle Feature Catalog — API Reference

Last updated: `2026-05-22`

The catalog is the single source of truth for vehicle Make / Model / Trim / Feature data and the sparse `Trim ↔ Feature` availability matrix.

This document is the binding facade contract. Everything listed here is public. Everything not listed is internal and may NOT be imported by external code.

## Facade entry points

| Language | Import path |
|---|---|
| Python | `from vehicle_feature_catalog import VehicleFeatureCatalog` |
| TypeScript | `import { VehicleFeatureCatalog } from 'vehicle-feature-catalog'` |

The facade is `VehicleFeatureCatalog`. Consumers MUST import only from the entry-point file. Reaching into `vehicle_feature_catalog/loader.py` or `src/ts/internal/*` is forbidden.

## Lifecycle

### `load(data_dir) → VehicleFeatureCatalog`

One-shot startup load. Reads every YAML file under `data_dir`, validates references, builds in-memory indexes, returns a ready-to-query instance.

- **Throws / rejects** on schema violations, broken references, or duplicate IDs.
- **No disk reads after load.** All subsequent queries operate against in-memory data structures.
- **Thread-safe for reads** once returned. Not designed for concurrent modification (catalogs are immutable post-load).

```python
catalog = VehicleFeatureCatalog.load(Path("vehicle-feature-catalog/data"))
```

```ts
const catalog = await VehicleFeatureCatalog.load("vehicle-feature-catalog/data");
```

## Single-entity lookups

All raise `NotFoundError` (Python) / throw `NotFoundError` (TS) if the ID is unknown.

| Method | Returns |
|---|---|
| `get_make(make_id)` | `Make` |
| `get_model(model_id)` | `Model` |
| `get_trim(trim_id)` | `Trim` |
| `get_feature(feature_id)` | `Feature` |

## List queries

| Method | Returns | Notes |
|---|---|---|
| `list_makes()` | `list[Make]` | All makes in the catalog |
| `list_models(make_id=None)` | `list[Model]` | Filter by make if given |
| `list_trims(model_id=None)` | `list[Trim]` | Filter by model if given |
| `list_features(brand_scope=None, category=None)` | `list[Feature]` | Filter by brand scope or category if given |

## Matrix queries (the core of why the catalog exists)

| Method | Returns | Notes |
|---|---|---|
| `list_features_for_trim(trim_id, availability=['standard'])` | `list[Feature]` | The feature set the salesperson can legitimately demo on this trim |
| `list_trims_with_feature(feature_id)` | `list[Trim]` | "Heated seats hits across most Honda models" → use this |

`availability` accepts any subset of `['standard', 'optional']`. Pass both to include features the trim can be ordered with.

## Validation

### `validate() → ValidationResult`

Returns a structured report of catalog integrity. Used by CI / pre-commit. Fails if:

- Any `TrimFeature` references a missing `feature_id` or `trim_id`
- Any `Feature` has empty `cue_phrases`
- Any `Trim` has zero features marked
- Any duplicate IDs across entities

```python
result = catalog.validate()
if not result.is_valid:
    sys.exit(result.format_errors())
```

## Data types

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
    id: str                 # e.g. "honda.crv-hybrid-awd"
    make_id: str
    name: str               # e.g. "CR-V Hybrid AWD"
    year: int               # e.g. 2026
    body_style: str | None  # e.g. "compact_suv"
```

### `Trim`

```python
class Trim:
    id: str                 # e.g. "honda.crv-hybrid-awd.2026.sport-touring"
    model_id: str
    name: str               # e.g. "Sport Touring"
    msrp_range: tuple[int, int] | None
```

### `Feature`

```python
class Feature:
    id: str                 # e.g. "universal.feature.wireless_apple_carplay"
                            # or  "honda.feature.honda_sensing_360plus"
    display_name: str
    category: str           # e.g. "connectivity", "driver_assistance", "comfort"
    brand_scope: str        # "universal" | "honda" | "toyota" | ...
    cue_phrases: list[str]  # e.g. ["wireless Apple CarPlay", "connects wirelessly"]
    synonyms: list[str]     # additional phrase variants
```

### `TrimFeature` (matrix cell)

```python
class TrimFeature:
    trim_id: str
    feature_id: str
    availability: Literal['standard', 'optional', 'unavailable']
```

`TrimFeature` is internal — consumers access matrix data through `list_features_for_trim` / `list_trims_with_feature` rather than reading cells directly.

### `ValidationResult`

```python
class ValidationResult:
    is_valid: bool
    errors: list[ValidationError]
    warnings: list[ValidationWarning]
    def format_errors(self) -> str: ...
```

## What this facade does NOT expose

| Concern | Why not | Lives where |
|---|---|---|
| `CueAtom` projection | CueAtom is voice-engine vocabulary, not catalog vocabulary | Consumer (lab / app) projects `Feature → CueAtom` |
| Audit-item logic | Audit items are Road-to-Sale-specific | `road-to-sale-app` |
| "Active cue set" composition (workflow ∪ feature) | Workflow cues live outside the catalog | Consumer composes |
| Cue matching | That's voice-engine's job | `voice-engine` |
| YAML file paths, raw dicts | Implementation details | Internal |
| Mutation methods (`add_feature`, `update_trim`, etc.) | Catalog is immutable post-load; mutations happen by editing YAML in git | N/A |

## Errors

| Error | Python | TS | When |
|---|---|---|---|
| `NotFoundError` | exception | thrown | ID lookup fails |
| `ValidationError` | dataclass in ValidationResult | object | Integrity check fails (one per finding) |
| `LoadError` | exception | thrown | YAML parse / file read failure during `load` |
| `DuplicateIdError` | exception | thrown | Two entities claim the same ID |
