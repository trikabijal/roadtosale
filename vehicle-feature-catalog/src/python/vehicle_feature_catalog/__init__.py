"""Public entry point for the vehicle feature catalog."""

from .entities import (
    Feature,
    Make,
    Model,
    Trim,
    TrimFeature,
    ValidationError,
    ValidationResult,
    ValidationWarning,
)
from .errors import CatalogError, DuplicateIdError, LoadError, NotFoundError
from .facade import VehicleFeatureCatalog

__all__ = [
    "VehicleFeatureCatalog",
    "Make",
    "Model",
    "Trim",
    "Feature",
    "TrimFeature",
    "ValidationResult",
    "ValidationError",
    "ValidationWarning",
    "CatalogError",
    "NotFoundError",
    "LoadError",
    "DuplicateIdError",
]
