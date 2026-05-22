"""Brochure scrapers for the vehicle feature catalog.

This package is *not* part of the catalog facade contract. It is a tool used
to seed/refresh the data files under ``vehicle-feature-catalog/data/`` from
authoritative brochure sources. The output of these scrapers is the YAML
files themselves — consumers of the catalog never import from here.
"""
