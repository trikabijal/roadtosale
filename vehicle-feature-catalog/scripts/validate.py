#!/usr/bin/env python3
"""CLI validator for the vehicle feature catalog.

Usage:
    python scripts/validate.py --data-dir vehicle-feature-catalog/data

Exits 0 if the catalog is valid, 1 otherwise.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

_SRC = Path(__file__).resolve().parent.parent / "src" / "python"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from vehicle_feature_catalog import LoadError, VehicleFeatureCatalog


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate the vehicle feature catalog.")
    parser.add_argument(
        "--data-dir",
        required=True,
        type=Path,
        help="Path to the catalog data directory (containing makes/, models/, etc.)",
    )
    args = parser.parse_args(argv)

    try:
        catalog = VehicleFeatureCatalog.load(args.data_dir)
    except LoadError as exc:
        print(f"Load failed: {exc}", file=sys.stderr)
        return 1

    result = catalog.validate()
    if result.is_valid:
        print("Catalog is valid.")
        if result.warnings:
            print(f"({len(result.warnings)} warning(s))")
            for w in result.warnings:
                print(f"  [{w.code}] {w.message}")
        return 0

    print(result.format_errors(), file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
