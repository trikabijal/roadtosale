# vehicle-feature-catalog

Brand-extensible catalog of vehicle Makes, Models, Trims, Features, and the sparse trim-to-feature availability matrix. The single source of truth for what features can legitimately be demonstrated on any given trim.

This package ships parallel Python and TypeScript implementations of the same facade. Field shapes are kept 1:1 between the two.

## Layout

```
vehicle-feature-catalog/
  data/                       hand-curated YAML catalog (owned by content)
  src/python/                 Python implementation
  src/ts/                     TypeScript implementation
  scripts/validate.py         CLI: validate a catalog directory
  tests/                      pytest + vitest suites with fixtures
  docs/                       api.md, architecture.md, flows.md  (binding)
```

## Build

```bash
./build.sh
```

Runs Python install + tests, then npm install + tests + TypeScript build.

## Test

Python:

```bash
python3 -m pytest tests/python -q
```

TypeScript:

```bash
npm install
npm test
```

## Validate a catalog directory

```bash
python3 scripts/validate.py --data-dir vehicle-feature-catalog/data
```

Exits `0` if the catalog is valid, `1` otherwise.

## Public API

Python:

```python
from vehicle_feature_catalog import VehicleFeatureCatalog

catalog = VehicleFeatureCatalog.load("vehicle-feature-catalog/data")
features = catalog.list_features_for_trim("honda.crv-hybrid-awd.2026.sport-touring")
```

TypeScript:

```ts
import { VehicleFeatureCatalog } from 'vehicle-feature-catalog';

const catalog = await VehicleFeatureCatalog.load('vehicle-feature-catalog/data');
const features = catalog.list_features_for_trim(
  'honda.crv-hybrid-awd.2026.sport-touring',
);
```

See `docs/api.md` for the full facade contract, `docs/architecture.md` for design, and `docs/flows.md` for end-to-end code walkthroughs.
