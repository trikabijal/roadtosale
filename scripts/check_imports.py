#!/usr/bin/env python3
"""
Static import boundary checker.

Enforces the isolation guarantee between voice-engine/ and vehicle-feature-catalog/:
neither module may import from the other. Communication is via shared YAML
conventions (audio_id, cue atom schema) and the CLI / orchestrator layer that
consumes both — never a direct Python/TS import across the boundary.

Usage
-----
  python3 scripts/check_imports.py            # run from repo root
  python3 scripts/check_imports.py --verbose  # show every file checked

Exit codes
----------
  0  — no boundary violations found
  1  — one or more violations found (details printed to stdout)

CI usage
--------
Add to .github/workflows/ci.yml:

  - name: Static import boundary check
    run: python3 scripts/check_imports.py
"""

from __future__ import annotations

import argparse
import ast
import sys
from pathlib import Path


# ---------------------------------------------------------------------------
# Boundary rules
#
# Each rule is (source_root, forbidden_import_prefixes).
# A file under source_root must not import any of the forbidden prefixes.
#
# The lab (voice-engine/lab/) is the *consumer* layer — it is explicitly
# allowed to import from both voice_lab and vehicle_feature_catalog.
# The rule only guards the pure library code (voice-engine/src/ and the
# native CLIs) from importing catalog internals, and guards the catalog
# library from importing voice engine internals.
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parent.parent

RULES: list[tuple[Path, list[str]]] = [
    # vehicle-feature-catalog library must not import from voice_lab
    (
        REPO_ROOT / "vehicle-feature-catalog" / "src",
        ["voice_lab"],
    ),
    # vehicle-feature-catalog scrapers must not import from voice_lab
    (
        REPO_ROOT / "vehicle-feature-catalog" / "scrapers",
        ["voice_lab"],
    ),
    # vehicle-feature-catalog tests must not import from voice_lab
    (
        REPO_ROOT / "vehicle-feature-catalog" / "tests",
        ["voice_lab"],
    ),
    # voice-engine native Python helpers (if any) must not import catalog
    # (native/ contains Swift/JVM code; any Python glue scripts live here)
    (
        REPO_ROOT / "voice-engine" / "native",
        ["vehicle_feature_catalog"],
    ),
    # NOTE: voice-engine/lab/ is intentionally EXCLUDED from this check.
    # The lab is the consumer layer — it composes both the voice engine and
    # the vehicle catalog. Importing vehicle_feature_catalog from the lab
    # orchestrator/CLI is correct and expected (D12, DC10 in decisions-log).
]

# TS / JS import boundary checks (string-based, not AST)
# Only the pure TS library (voice-engine/src/) is checked — not tests, which
# may import fixtures from either package.
TS_RULES: list[tuple[Path, list[str]]] = [
    (
        REPO_ROOT / "voice-engine" / "src",
        ["vehicle-feature-catalog"],
    ),
    (
        REPO_ROOT / "vehicle-feature-catalog" / "src" / "ts",
        ["voice-engine"],
    ),
]


def _python_imports(source: str) -> list[str]:
    """Return all top-level module names imported by the given Python source."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []

    modules: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                modules.append(alias.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            if node.module:
                modules.append(node.module.split(".")[0])
    return modules


_SKIP_PARTS = frozenset(["__pycache__", ".build", "node_modules", "dist", ".git"])


def check_python(verbose: bool) -> tuple[list[str], int]:
    violations: list[str] = []
    checked = 0

    for source_root, forbidden in RULES:
        if not source_root.exists():
            continue
        for py_file in source_root.rglob("*.py"):
            # Skip generated/vendor directories
            if _SKIP_PARTS.intersection(py_file.parts):
                continue
            # The cross-language drift test is allowed to import both — it lives
            # outside the module boundaries (tests/fixtures/).
            if "fixtures" in py_file.parts and "drift" in py_file.name:
                continue

            rel = py_file.relative_to(REPO_ROOT)
            source = py_file.read_text(encoding="utf-8", errors="replace")
            imported = _python_imports(source)
            checked += 1

            if verbose:
                print(f"  [py] {rel}")

            for imp in imported:
                if imp in forbidden:
                    violations.append(
                        f"VIOLATION (py): {rel} imports '{imp}' "
                        f"(forbidden from {source_root.name}/)"
                    )

    return violations, checked


def check_typescript(verbose: bool) -> tuple[list[str], int]:
    violations: list[str] = []
    checked = 0

    for source_root, forbidden_fragments in TS_RULES:
        if not source_root.exists():
            continue
        for ts_file in source_root.rglob("*.ts"):
            if _SKIP_PARTS.intersection(ts_file.parts):
                continue

            rel = ts_file.relative_to(REPO_ROOT)
            source = ts_file.read_text(encoding="utf-8", errors="replace")
            checked += 1

            if verbose:
                print(f"  [ts] {rel}")

            for fragment in forbidden_fragments:
                # Match: import ... from '...vehicle-feature-catalog...'
                #    or: require('...vehicle-feature-catalog...')
                if f"'{fragment}" in source or f'"{fragment}' in source:
                    violations.append(
                        f"VIOLATION (ts): {rel} imports '{fragment}' "
                        f"(forbidden from {source_root.relative_to(REPO_ROOT)}/)"
                    )

    return violations, checked


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verbose", "-v", action="store_true", help="Show each file checked")
    args = parser.parse_args()

    if args.verbose:
        print("Checking Python files...")
    py_violations, py_count = check_python(args.verbose)

    if args.verbose:
        print("Checking TypeScript files...")
    ts_violations, ts_count = check_typescript(args.verbose)

    all_violations = py_violations + ts_violations
    total = py_count + ts_count

    if all_violations:
        print(f"\n{'='*60}")
        print(f"BOUNDARY VIOLATIONS FOUND: {len(all_violations)}")
        print(f"{'='*60}")
        for v in all_violations:
            print(f"  {v}")
        print()
        print(
            "Fix: move shared logic to the consumer layer (lab orchestrator, CLI)\n"
            "or duplicate the type definition in both packages (the F2 rule).\n"
            "Never import across voice-engine/ ↔ vehicle-feature-catalog/ directly."
        )
        return 1

    print(f"OK — no boundary violations found ({total} files checked: {py_count} py, {ts_count} ts)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
