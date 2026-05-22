# Vehicle Feature Catalog — Architecture

Last updated: `2026-05-22`

## Purpose

A normalized, brand-extensible catalog of vehicle Makes → Models → Trims → Features and the sparse `Trim ↔ Feature` availability matrix. Honda US is the first make. The catalog is a static, in-memory data layer — not a database engine.

The catalog exists so the voice engine and the future road-to-sale-app can answer one question reliably: *"For the trim the rep is selling, which features can legitimately be demonstrated?"*

## Design principles

| Principle | Consequence |
|---|---|
| Brochure-shaped data | Schema matches how dealer brochures present features: features are global, trims tick boxes |
| Brand extensibility from day one | Schema is brand-agnostic; Honda is one make, Toyota will be another with zero schema changes |
| No runtime database engine | YAML files in git + in-memory loaders. No SQLite, no Postgres, no server, no migrations |
| Immutable post-load | Catalog state is fixed after `load()`. Mutations happen by editing YAML in git, not via API |
| Hand-curated in v1 | A scraper is a later concern. v1 is brochure → YAML by hand, version-controlled |
| Facade-only access | Consumers go through `VehicleFeatureCatalog`; reaching into loader internals is forbidden |

## Component diagram

```mermaid
flowchart TD
  YAML[YAML files under data/] --> Loader
  Loader --> Indexes[(In-memory indexes)]
  Indexes --> Facade[VehicleFeatureCatalog facade]
  Facade --> Consumer1[voice-engine lab]
  Facade --> Consumer2[future road-to-sale-app]
  Facade --> Validator[validate() — CI / pre-commit]
```

## Schema

Five entities. Four are entity records; one (`TrimFeature`) is a matrix cell.

```
Make            { id, name, country }
Model           { id, make_id, name, year, body_style? }
Trim            { id, model_id, name, msrp_range? }
Feature         { id, display_name, category, brand_scope, cue_phrases[], synonyms[] }
TrimFeature     { trim_id, feature_id, availability: 'standard' | 'optional' | 'unavailable' }
```

See `api.md` for full field-level definitions.

### Why this shape

- **Features are global.** "Wireless Apple CarPlay" is one feature, defined once, referenced by every trim that has it. No per-model duplication.
- **Brand scope per feature.** Universal features cross brands ("Heated Front Seats"). Brand-specific features stay scoped (`Honda Sensing 360+`, future `Toyota Safety Sense 3.0`). The scope is on the feature, not buried in references.
- **Availability is a sparse matrix.** Only trims that have a feature appear in the matrix. Unavailable combos are implicit absence, not explicit zeros. Storage is small.

## File layout

```
vehicle-feature-catalog/
├── data/
│   ├── makes/honda.yaml
│   ├── models/honda/cr-v-hybrid-awd.yaml
│   ├── trims/honda/cr-v-hybrid-awd/2026/sport.yaml
│   ├── trims/honda/cr-v-hybrid-awd/2026/sport-l.yaml
│   ├── trims/honda/cr-v-hybrid-awd/2026/sport-touring.yaml
│   ├── features/universal/wireless_apple_carplay.yaml
│   ├── features/universal/heated_front_seats.yaml
│   ├── features/honda/honda_sensing_360plus.yaml
│   ├── features/honda/real_time_awd.yaml
│   └── matrix/honda.yaml                # ← the brochure tick-box grid
├── src/
│   ├── python/vehicle_feature_catalog/  # in-memory loader + query helpers
│   └── ts/                              # in-memory loader + query helpers
├── tests/
├── docs/
├── build.sh
└── README.md
```

One YAML file per entity. One matrix file per make. Files are hand-edited and reviewed in PRs.

### Example: trim file

```yaml
# data/trims/honda/cr-v-hybrid-awd/2026/sport-touring.yaml
id: honda.crv-hybrid-awd.2026.sport-touring
model_id: honda.crv-hybrid-awd
name: Sport Touring
year: 2026
msrp_range: [40000, 43000]
```

### Example: feature file

```yaml
# data/features/honda/honda_sensing_360plus.yaml
id: honda.feature.honda_sensing_360plus
display_name: Honda Sensing 360+
category: driver_assistance
brand_scope: honda
cue_phrases:
  - Honda Sensing 360 plus
  - Honda Sensing 360+
  - 360 plus driver assistance
synonyms:
  - 360 plus
```

### Example: matrix file (the brochure grid, flattened)

```yaml
# data/matrix/honda.yaml
entries:
  - trim_id: honda.crv-hybrid-awd.2026.sport-touring
    features:
      - { feature_id: universal.feature.wireless_apple_carplay, availability: standard }
      - { feature_id: universal.feature.heated_front_seats, availability: standard }
      - { feature_id: honda.feature.honda_sensing_360plus, availability: standard }
      - { feature_id: honda.feature.real_time_awd, availability: standard }
      - { feature_id: universal.feature.panoramic_moonroof, availability: standard }
  - trim_id: honda.crv-hybrid-awd.2026.sport
    features:
      - { feature_id: universal.feature.wireless_apple_carplay, availability: standard }
      - { feature_id: honda.feature.real_time_awd, availability: standard }
      # no heated seats, no 360+, no moonroof — Sport trim
```

## Isolation guarantees

- **No imports out.** Nothing under `vehicle-feature-catalog/src/` imports from `voice-engine/`, `road-to-sale-app/`, or `demo/`.
- **No imports in (except via facade).** External code imports `VehicleFeatureCatalog` from the entry-point file and nothing else.
- **Static import check in CI** enforces both directions.

## Brand extensibility test

The catalog ships with a tiny second-make fixture (1 Toyota Model, 1 Trim, 1 brand-specific feature, sharing universal features with Honda). This proves the schema is brand-agnostic in practice, not just in intent. Adding the fixture is a v1 success criterion.

## Open architectural questions

- **Per-feature richer metadata** (e.g. `MSRP delta when optional`, `availability_disclaimer`) — deferred until a consumer needs it.
- **Localization** (`display_name` in non-English) — deferred. Single-locale v1.
- **Photo / asset references** per feature — deferred. Could be added later as optional fields without breaking the schema.

## Decisions not made yet

See PRD open questions OQ4 (model-year scope), OQ5 (extraction owner), OQ12 (script-sourcing split), OQ13 (YouTube fair-use boundary).
