"""Atom column normalisation for the Bayesian GLM (audit 6.10) — pure pandas, no I/O.

creative_atoms.atom_type values are upper-case nouns (HOOK, CTA, FACE, ...). prepare_dataset in
models/bayesian_glm.py reads hook_type / cta_type / has_face. Without this mapping the GLM trained
with a single "unknown" hook/CTA label and ignored faces entirely.
"""
from __future__ import annotations

import pandas as pd

ATOM_COLUMN_MAP = {
    "HOOK": "hook_type",
    "CTA": "cta_type",
    "FACE": "has_face",
    "OFFER": "offer_type",
}

_NO_FACE = {"", "none", "no", "false", "0", "unknown", "absent", "n/a"}


def _face_flag(value) -> float:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return 0.0
    return 0.0 if str(value).strip().lower() in _NO_FACE else 1.0


def normalise_atom_columns(wide: pd.DataFrame) -> pd.DataFrame:
    """Rename pivoted atom columns to the GLM feature names and binarise has_face."""
    renamed = wide.rename(columns={k: v for k, v in ATOM_COLUMN_MAP.items() if k in wide.columns})
    if "has_face" in renamed.columns:
        renamed["has_face"] = renamed["has_face"].map(_face_flag).astype(float)
    # any remaining upper-case atom types become lower-case to stay predictable
    renamed.columns = [c.lower() if isinstance(c, str) and c.isupper() else c for c in renamed.columns]
    return renamed
