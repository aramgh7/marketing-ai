"""
Phase 14, §19.6: Visual atoms in the GLM.
Extends the text-only GLM with image type per slot position as a categorical feature.
Min-data guard: 50 variants with at least one conversion before fitting visual coefficients.
"""
from __future__ import annotations

MIN_VARIANTS_FOR_VISUAL_FIT = 50

# Slot positions tracked as GLM features
TRACKED_SLOT_POSITIONS = ["hero", "proof", "background"]


def extract_visual_features(variants: list[dict]) -> dict[str, list]:
    """
    Given a list of landing variant dicts, each with:
      - "id": str
      - "conversions": int
      - "impressions": int
      - "visual_slots": {slot_position: l1_tag}

    Return a feature dict:
      {
        "variant_ids": [...],
        "y": [0/1 per variant, 1 if conversions > 0],
        "n": [impressions per variant],
        "visual_features": {"hero_l1": [...], "proof_l1": [...], "background_l1": [...]}
      }
    Missing visual_slots entries get "none" as placeholder.
    """
    variant_ids: list[str] = []
    y: list[int] = []
    n: list[int] = []
    feat: dict[str, list[str]] = {pos + "_l1": [] for pos in TRACKED_SLOT_POSITIONS}

    for v in variants:
        variant_ids.append(str(v.get("id", "")))
        conversions = int(v.get("conversions", 0))
        impressions = int(v.get("impressions", 0))
        y.append(1 if conversions > 0 else 0)
        n.append(max(impressions, 1))

        slots: dict = v.get("visual_slots", {}) or {}
        for pos in TRACKED_SLOT_POSITIONS:
            feat[pos + "_l1"].append(str(slots.get(pos, "none")))

    return {"variant_ids": variant_ids, "y": y, "n": n, "visual_features": feat}


def has_enough_data(features: dict) -> bool:
    """Return True if there are >= MIN_VARIANTS_FOR_VISUAL_FIT variants with conversions."""
    y = features.get("y", [])
    variants_with_conversion = sum(1 for v in y if v > 0)
    return variants_with_conversion >= MIN_VARIANTS_FOR_VISUAL_FIT


def fit_visual_atoms(features: dict) -> dict:
    """
    Fit a logistic regression with one-hot visual features.
    Returns {"visual_atoms": [{slot, l1_tag, coefficient}], "n_obs": int, "n_variants": int}.
    Returns empty list with skipped_reason when data is insufficient or sklearn unavailable.
    """
    if not has_enough_data(features):
        n_with_conv = sum(1 for v in features.get("y", []) if v > 0)
        return {
            "visual_atoms": [],
            "n_obs": len(features.get("y", [])),
            "n_variants": len(features.get("y", [])),
            "skipped_reason": (
                f"need {MIN_VARIANTS_FOR_VISUAL_FIT} variants with conversions, have {n_with_conv}"
            ),
        }

    try:
        import numpy as np  # type: ignore
        from sklearn.linear_model import LogisticRegression  # type: ignore
        from sklearn.preprocessing import OneHotEncoder  # type: ignore

        visual_feat = features.get("visual_features", {})
        slot_keys = list(visual_feat.keys())
        if not slot_keys:
            return {
                "visual_atoms": [],
                "n_obs": len(features.get("y", [])),
                "n_variants": len(features.get("y", [])),
            }

        arrays = [visual_feat[k] for k in slot_keys]
        X_raw = list(zip(*arrays))
        enc = OneHotEncoder(sparse_output=False, handle_unknown="ignore")
        X = enc.fit_transform(X_raw)
        y = np.array(features["y"])

        if X.shape[0] < 10:
            return {
                "visual_atoms": [],
                "n_obs": X.shape[0],
                "n_variants": X.shape[0],
                "skipped_reason": "too few samples",
            }

        model = LogisticRegression(max_iter=500, C=1.0)
        model.fit(X, y)
        coef = model.coef_[0]

        atoms: list[dict] = []
        feature_names = enc.get_feature_names_out(slot_keys)
        for fname, c in zip(feature_names, coef):
            parts = fname.split("_l1_", 1)
            slot = parts[0] + "_l1" if len(parts) == 2 else fname
            tag = parts[1] if len(parts) == 2 else fname
            if tag == "none":
                continue
            atoms.append({"slot": slot, "l1_tag": tag, "coefficient": round(float(c), 4)})

        atoms.sort(key=lambda x: abs(x["coefficient"]), reverse=True)
        return {"visual_atoms": atoms, "n_obs": X.shape[0], "n_variants": X.shape[0]}

    except Exception as exc:
        return {"visual_atoms": [], "n_obs": 0, "n_variants": 0, "error": str(exc)}


def select_best_asset(
    slot_position: str,
    available_assets: list[dict],
    visual_atoms: list[dict],
    required_l2_tag: str | None = None,
) -> dict | None:
    """
    Select the best asset for a slot by:
    1. Filtering to assets with the required L2 tag (if specified)
    2. Ranking by GLM visual atom coefficient for this slot position

    Returns the best asset dict, or None if no candidates qualify.
    """
    candidates = [a for a in available_assets if _qualifies(a, slot_position, required_l2_tag)]
    if not candidates:
        return None

    coef_map: dict[str, float] = {}
    for atom in visual_atoms:
        if atom.get("slot") == slot_position + "_l1":
            coef_map[atom.get("l1_tag", "")] = atom.get("coefficient", 0.0)

    def score(asset: dict) -> float:
        l1_tags = asset.get("tags_l1") or []
        l1_str = l1_tags[0] if l1_tags else "none"
        return coef_map.get(l1_str, 0.0)

    candidates.sort(key=score, reverse=True)
    return candidates[0]


def _qualifies(asset: dict, slot_position: str, required_l2: str | None) -> bool:
    if required_l2 is None:
        return True
    l2_tags = asset.get("tags_l2") or []
    return required_l2 in l2_tags
