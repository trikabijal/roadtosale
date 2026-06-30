# Vehicle Feature Catalog — Build, Test, Run, Deploy (local)

This module is a **library** (no server). It ships two parallel implementations of
the same contract — a Python package (`vehicle_feature_catalog`) and a TypeScript
package — plus two operator CLIs and a one-shot scraper pipeline. This doc takes a
fresh clone from zero to "built, tested, and importable" with no prior knowledge needed.

Everything is driven by four shell scripts at the module root:

| Script | Purpose |
|---|---|
| `./build.sh` | One command from a fresh clone: venv + editable install + TS build |
| `./test.sh` | Run the full suite (52 Python pytest + 27 TS vitest) |
| `./run.sh` | Run the operator CLIs (catalog validator, vocab deriver) |
| `./deploy-local.sh` | "Local deploy" — make the library importable by local consumers |

> The scripts are committed without the execute bit on some clones. If `./build.sh`
> reports "Permission denied", either `chmod +x *.sh` once or call them as
> `bash build.sh`.

---

## Prerequisites

| Tool | Minimum version | Why |
|---|---|---|
| **Python** | 3.11 | Core library + scrapers + pytest. `pyproject.toml` requires `>=3.11`. |
| **Node.js** | 18+ (any current LTS) | TypeScript build (`tsc`) + vitest. |
| **npm** | ships with Node | Installs TS deps, runs build/test. |

### macOS install hints

```bash
brew install python@3.11      # or download from https://python.org
brew install node             # or use nvm: https://github.com/nvm-sh/nvm
```

### Linux install hints

```bash
sudo apt-get install python3.11 python3.11-venv python3-pip
# Node: https://nodejs.org/en/download/package-manager  (or use nvm)
```

`build.sh` checks all three up front. If anything is missing or too old, it
prints these hints and exits non-zero — it never half-builds without telling you.

---

## Build

```bash
./build.sh
```

What it does, idempotently (safe to re-run):

1. Verifies `python3 >= 3.11`, `node`, `npm`.
2. Creates `./.venv` (if absent) and activates it.
3. Upgrades `pip`, then `pip install -e '.[scrapers,dev]'`:
   - core library (`PyYAML`),
   - `[scrapers]` extra (`requests`, `beautifulsoup4`, `pdfplumber`) for the
     Honda seeding pipeline,
   - `[dev]` extra (`pytest`, `mypy`).
4. Installs Node deps (`npm ci` when `package-lock.json` exists, else `npm install`).
5. Builds the TypeScript output (`npm run build` → `tsc -p tsconfig.json`).

### Where artifacts land

| Path | Contents | Tracked? |
|---|---|---|
| `./.venv/` | Local Python virtualenv | gitignored |
| `./dist/` | Compiled TS (`index.js`, `.d.ts`, source maps) | gitignored |
| `./node_modules/` | Node dependencies | gitignored |

Nothing is written outside the module, and nothing dirties git.

---

## Test

```bash
./test.sh
```

- Activates `./.venv` (run `./build.sh` first if it isn't there).
- Runs `pytest tests/python` — **52 tests** (loader, validator, facade, scrapers).
- Runs `npm test` → `vitest run` over `tests/ts/` — **27 tests**.
- **Both suites always run** so you see every failure in one pass; the script
  exits non-zero if either suite fails.

The Python and TS suites are independent — they share the YAML data dir under
`data/` but no code.

---

## Run (operator CLIs)

```bash
./run.sh                       # prints usage
```

### Validate the catalog

Loads every YAML under `data/` and runs integrity checks (referential integrity,
duplicate IDs, dangling matrix references, etc.).

```bash
./run.sh validate                    # validates the bundled ./data
./run.sh validate --data-dir PATH    # validates a different data dir
```

Exits 0 when the catalog is valid (warnings are printed but non-fatal), 1 otherwise.
Under the hood this is `python scripts/validate.py --data-dir ./data`.

### Derive STT vocab

Regenerates the per-make speech vocabulary the voice engine consumes:

```bash
./run.sh derive-vocab                # all makes found under data/makes
./run.sh derive-vocab honda toyota   # specific make(s)
```

> **Note:** `derive-vocab` writes to `../voice-engine/cleanup-packs/derived/<make>.vocab.json`
> — i.e. *outside* this module, into the voice-engine. That is the documented
> catalog → STT-vocab bridge (see `architecture.md`). The output JSON is marked
> "derived; do not hand-edit" — regenerate it by re-running this command.

### Scraper pipeline (not in run.sh)

The Honda seeding scraper is an operator tool with its own flags and is run
directly, not via `run.sh`:

```bash
.venv/bin/python -m scrapers.honda_us.cli --help
```

See [`scrapers/honda_us/README.md`](../scrapers/honda_us/README.md) for the full
operator workflow.

---

## Deploy (local)

```bash
./deploy-local.sh
```

**There is no server and no cloud.** For a library, "local deploy" means making
it importable by local consumers (e.g. the voice-engine lab). The script:

1. Editable-installs `vehicle_feature_catalog` into the active `./.venv`.
2. Builds the TS `dist/` so TS consumers can import the package.
3. Verifies the import works: `python -c "import vehicle_feature_catalog"`.

After this:

- **Python consumers** `import vehicle_feature_catalog` from this environment.
- **TS consumers** import from `dist/index.js`.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `python3 not found` / version too old | Install per the hints above. `build.sh` needs `>=3.11`. |
| `.venv not found. Run ./build.sh first.` (from `test.sh`/`deploy-local.sh`) | Run `./build.sh` once. |
| `node_modules not found` (from `test.sh`) | Run `./build.sh` (it runs `npm ci`/`npm install`). |
| `Permission denied: ./build.sh` | `chmod +x *.sh`, or invoke as `bash build.sh`. |
| `tsc` errors after pulling new TS | Delete `dist/` and re-run `./build.sh`. |
| Stale Python install after dependency changes | Re-run `./build.sh` (editable install + `pip install` are idempotent). |
| Want a clean slate | `rm -rf .venv dist node_modules` then `./build.sh`. All three are gitignored. |
| `npm audit` warnings during build | Informational only — they don't fail the build. Address with `npm audit fix` if desired. |
| `validate` reports errors | The catalog YAML under `data/` is invalid; read the printed error codes and fix the offending file. |
