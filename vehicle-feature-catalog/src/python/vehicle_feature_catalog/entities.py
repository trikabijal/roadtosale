"""Entity dataclasses for the vehicle feature catalog.

Field shape is mirrored 1:1 in `src/ts/entities.ts`. Do not drift.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal


Availability = Literal["standard", "optional", "unavailable"]


@dataclass(frozen=True)
class Make:
    id: str
    name: str
    country: str


@dataclass(frozen=True)
class Model:
    id: str
    make_id: str
    name: str
    year: int
    body_style: str | None = None


@dataclass(frozen=True)
class Trim:
    id: str
    model_id: str
    name: str
    msrp_range: tuple[int, int] | None = None


@dataclass(frozen=True)
class Feature:
    id: str
    display_name: str
    category: str
    brand_scope: str
    cue_phrases: list[str] = field(default_factory=list)
    synonyms: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class TrimFeature:
    trim_id: str
    feature_id: str
    availability: Availability


@dataclass(frozen=True)
class ValidationError:
    code: str
    message: str
    entity_id: str | None = None


@dataclass(frozen=True)
class ValidationWarning:
    code: str
    message: str
    entity_id: str | None = None


@dataclass
class ValidationResult:
    is_valid: bool
    errors: list[ValidationError]
    warnings: list[ValidationWarning]

    def format_errors(self) -> str:
        if self.is_valid and not self.errors:
            return "Catalog is valid."
        lines = [f"Catalog validation failed with {len(self.errors)} error(s):"]
        for err in self.errors:
            tag = f"[{err.code}]"
            entity = f" ({err.entity_id})" if err.entity_id else ""
            lines.append(f"  {tag}{entity} {err.message}")
        if self.warnings:
            lines.append(f"Warnings ({len(self.warnings)}):")
            for warn in self.warnings:
                tag = f"[{warn.code}]"
                entity = f" ({warn.entity_id})" if warn.entity_id else ""
                lines.append(f"  {tag}{entity} {warn.message}")
        return "\n".join(lines)
