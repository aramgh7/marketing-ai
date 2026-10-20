"""Offer atom schemas (roadmap 8-8; vision §19.11; arch §16.8) — pure logic, no I/O.

A schema is a list of fields: {key, label, type: "enum"|"bool", values: [...], required, must_be_real}.
Offers are dicts of atoms {key: value}. Two operations:

  validate_offer_atoms(atoms, fields)       -> list of error strings (empty = valid)
  generate_offers_from_schema(fields, base) -> one-factor-at-a-time candidates for the Dynamic
                                               Offer Explorer (3-19), each a full atom dict

"must_be_real" fields (urgency) only accept a non-"none" value when the offer carries
`urgency_source` ∈ {crm_deadline, round_close_date, allocation_api, inventory_api} — the §18.5
rule that urgency is never invented by the generator.
"""
from __future__ import annotations

from typing import Any

INVESTOR_RAISE_SCHEMA: list[dict] = [
    {"key": "first_touch_format", "label": "First-touch format", "type": "enum", "required": True,
     "values": ["call", "webinar", "event", "video_presentation"]},
    {"key": "entry_threshold_shown", "label": "Entry threshold shown", "type": "enum", "required": True,
     "values": ["none", "from_25k", "from_50k"]},
    {"key": "proof_type", "label": "Proof type", "type": "enum", "required": True,
     "values": ["adoption_speed", "field_footage", "founder", "round_structure"]},
    {"key": "urgency", "label": "Urgency", "type": "enum", "required": True, "must_be_real": True,
     "values": ["none", "round_close_date", "allocation_left"]},
    {"key": "founder_presence", "label": "Founder present", "type": "bool", "required": False},
    {"key": "followup_offer", "label": "Follow-up offer", "type": "enum", "required": True,
     "values": ["agenda", "round_materials_before_call", "none"]},
]

REAL_URGENCY_SOURCES = {"crm_deadline", "round_close_date", "allocation_api", "inventory_api", "seasonal_window"}
NEUTRAL_VALUES = {"none", "", None}


def _as_bool(v: Any) -> bool | None:
    if isinstance(v, bool):
        return v
    if v is None:
        return None
    s = str(v).strip().lower()
    if s in ("true", "yes", "1", "on"):
        return True
    if s in ("false", "no", "0", "off"):
        return False
    return None


def validate_offer_atoms(atoms: dict, fields: list[dict], urgency_source: str | None = None) -> list[str]:
    """Return validation errors for `atoms` against `fields` (empty list = valid)."""
    errors: list[str] = []
    atoms = atoms or {}
    known = {f["key"] for f in fields}
    for f in fields:
        key = f["key"]
        value = atoms.get(key)
        # "none" is a legitimate enum value (urgency none, follow-up none); only absent/blank is missing
        if (value is None or str(value).strip() == "") and f.get("required") and f.get("type") != "bool":
            errors.append(f"{key} is required")
            continue
        if value is None:
            continue
        if f.get("type") == "enum":
            allowed = f.get("values", [])
            if value not in allowed:
                errors.append(f"{key}='{value}' is not allowed; must be one of {allowed}")
                continue
            if f.get("must_be_real") and value not in NEUTRAL_VALUES:
                src = (urgency_source or atoms.get("urgency_source") or "").strip().lower()
                if src not in REAL_URGENCY_SOURCES:
                    errors.append(f"{key}='{value}' must be backed by a real source "
                                  f"(urgency_source in {sorted(REAL_URGENCY_SOURCES)})")
        elif f.get("type") == "bool":
            if _as_bool(value) is None:
                errors.append(f"{key} must be a boolean")
    for key in atoms:
        if key not in known and key != "urgency_source":
            errors.append(f"unknown atom '{key}' for this schema")
    return errors


def generate_offers_from_schema(fields: list[dict], base_atoms: dict, max_variants: int = 8,
                                urgency_source: str | None = None) -> list[dict]:
    """One-factor-at-a-time explorer: each candidate changes exactly one atom of the base offer.

    Candidates that fail validation (e.g. invented urgency) are dropped, so the explorer can never
    propose an offer the validator would reject. The baseline is returned first with rank 0.
    """
    base = dict(base_atoms or {})
    out: list[dict] = [{"label": "baseline", "changed": None, "atoms": dict(base)}]
    for f in fields:
        key = f["key"]
        current = base.get(key)
        options: list[Any]
        if f.get("type") == "bool":
            options = [not bool(_as_bool(current))]
        else:
            options = [v for v in f.get("values", []) if v != current]
        for v in options:
            cand = dict(base)
            cand[key] = v
            if validate_offer_atoms(cand, fields, urgency_source):
                continue
            out.append({"label": f"{key}={v}", "changed": key, "atoms": cand})
            if len(out) - 1 >= max_variants:
                return out
    return out


def offer_text_from_atoms(atoms: dict, fields: list[dict]) -> str:
    """Human-readable sentence of the offer for the compliance gate (8-8: compliance replaces margin floor)."""
    labels = {f["key"]: f.get("label", f["key"]) for f in fields}
    parts = []
    for key, value in (atoms or {}).items():
        if key == "urgency_source" or value in NEUTRAL_VALUES:
            continue
        parts.append(f"{labels.get(key, key)}: {str(value).replace('_', ' ')}")
    return "; ".join(parts)
