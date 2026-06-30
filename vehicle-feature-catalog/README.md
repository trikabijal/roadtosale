# vehicle-feature-catalog

Brand-extensible catalog of vehicle Makes, Models, Trims, Features, and the sparse
trim-to-feature availability matrix — the single source of truth for what features
can legitimately be demonstrated on any given trim. Ships parallel **Python** and
**TypeScript** implementations of the same facade, with field shapes kept 1:1.

## Prerequisites

The build/test scripts check these at startup and fail loudly with an install hint
if anything is missing or too old.

| Tool      | Minimum version | Install (macOS)             | Install (Linux)                                            |
| --------- | --------------- | --------------------------- | --------------------------------------------------------- |
| `python3` | 3.11            | `brew install python@3.11`  | `sudo apt-get install python3 python3-venv python3-pip`    |
| `node`    | 20              | `brew install node`         | [nodejs.org/en/download](https://nodejs.org/en/download)  |
| `npm`     | 10              | ships with Node             | ships with Node                                           |

`npm` ships with Node; if it's missing or too old, reinstall/upgrade Node.

## Quickstart

After cloning the monorepo, from this directory (`vehicle-feature-catalog/`):

```bash
./build.sh     # creates ./.venv, installs Python (editable) + Node deps, builds dist/
./test.sh      # runs both suites
```

Expected result: **52 Python + 27 TS tests pass.** `build.sh` is idempotent and safe
to re-run; deps are cached after the first run, so subsequent builds/tests are fast.

Other entry points:

- `./run.sh` — operator CLIs: `./run.sh validate` (validate the bundled catalog) and
  `./run.sh derive-vocab [make ...]` (regenerate per-make STT vocab JSON). Run with no
  args for usage.
- `./deploy-local.sh` — this module is a **library**, not a server. "Local deploy" just
  editable-installs the Python package and builds the TS `dist/` so local consumers
  (e.g. the voice-engine lab) can import it from this checkout.

## Public API

```python
from vehicle_feature_catalog import VehicleFeatureCatalog

catalog = VehicleFeatureCatalog.load("vehicle-feature-catalog/data")
features = catalog.list_features_for_trim("honda.crv-hybrid-awd.2026.sport-touring")
```

```ts
import { VehicleFeatureCatalog } from 'vehicle-feature-catalog';

const catalog = await VehicleFeatureCatalog.load('vehicle-feature-catalog/data');
const features = catalog.list_features_for_trim(
  'honda.crv-hybrid-awd.2026.sport-touring',
);
```

## More detail

- [`docs/build.md`](docs/build.md) — build/test/run workflow and troubleshooting.
- [`docs/architecture.md`](docs/architecture.md) — design, components, isolation guarantees.
- [`docs/api.md`](docs/api.md) — full facade contract. [`docs/flows.md`](docs/flows.md) — end-to-end walkthroughs.
