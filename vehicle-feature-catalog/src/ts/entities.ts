// Entity interfaces. Field shape mirrors `src/python/vehicle_feature_catalog/entities.py`.
// Do not drift.

export interface Make {
  id: string;
  name: string;
  country: string;
}

export interface Model {
  id: string;
  make_id: string;
  name: string;
  year: number;
  body_style: string | null;
}

export interface Trim {
  id: string;
  model_id: string;
  name: string;
  msrp_range: [number, number] | null;
}

export interface Feature {
  id: string;
  display_name: string;
  category: string;
  brand_scope: string;
  cue_phrases: string[];
  synonyms: string[];
}

export type Availability = 'standard' | 'optional' | 'unavailable';

export interface TrimFeature {
  trim_id: string;
  feature_id: string;
  availability: Availability;
}

export interface ValidationError {
  code: string;
  message: string;
  entity_id: string | null;
}

export interface ValidationWarning {
  code: string;
  message: string;
  entity_id: string | null;
}

export interface ValidationResult {
  is_valid: boolean;
  errors: ValidationError[];
  warnings: ValidationWarning[];
  format_errors(): string;
}
