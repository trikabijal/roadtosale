"""Errors raised by the catalog facade."""

from __future__ import annotations


class CatalogError(Exception):
    """Base for all catalog-level errors."""


class NotFoundError(CatalogError):
    """Raised when an ID lookup fails."""


class LoadError(CatalogError):
    """Raised when YAML parsing / file read fails during load."""


class DuplicateIdError(CatalogError):
    """Raised when two entities claim the same ID."""
