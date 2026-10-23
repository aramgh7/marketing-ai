"""
Geo-Lift service — no FastAPI/Pydantic imports.
Synthetic-control incrementality on client sales data (NOT pixel).
Credible intervals on all figures (vision §17.3).
Architecture §15.21, roadmap 5-7.
"""
from __future__ import annotations

import math
import numpy as np

_RNG = np.random.default_rng(seed=42)
_MC_SAMPLES = 5_000
_CI_LEVEL = 0.80  # 80% credible interval


def _block_bootstrap_sample(arr: np.ndarray, block_size: int = None) -> np.ndarray:
    """Block bootstrap preserving temporal autocorrelation."""
    n = len(arr)
    if block_size is None:
        block_size = max(7, int(np.sqrt(n)))  # weekly blocks by default
    n_blocks = int(np.ceil(n / block_size))
    starts = _RNG.integers(0, n, size=n_blocks)
    indices = np.concatenate([np.arange(s, min(s + block_size, n)) for s in starts])[:n]
    return arr[indices]


def validate_geo_split(
    control_geos: list[str],
    test_geos: list[str],
) -> dict:
    """
    Validate that geo lists are non-empty, non-overlapping, and have at least 2 geos each.
    Returns {valid: bool, errors: list[str]}.
    """
    errors = []

    if not control_geos:
        errors.append("control_geos must not be empty")
    if not test_geos:
        errors.append("test_geos must not be empty")

    overlap = set(control_geos) & set(test_geos)
    if overlap:
        errors.append(f"Overlapping geos: {sorted(overlap)}")

    if len(control_geos) < 2:
        errors.append("At least 2 control geos are needed for a reliable synthetic control")
    if len(test_geos) < 1:
        errors.append("At least 1 test geo is required")

    return {"valid": len(errors) == 0, "errors": errors}


def compute_synthetic_control_simple(
    pre_period_control: list[float],
    pre_period_test: list[float],
    post_period_control: list[float],
    post_period_test: list[float],
) -> dict:
    """
    Simple synthetic control: scale ratio from pre-period.
    scale = mean(test_pre) / mean(control_pre)
    synthetic_test_post = scale * mean(control_post)
    lift = mean(test_post) - synthetic_test_post
    lift_pct = lift / synthetic_test_post

    Credible interval via bootstrap on the pre-period scaling residuals.
    Returns {lift_estimate, lift_pct, ci_lower, ci_upper, ci_level,
             p_positive, synthetic_counterfactual, method}.
    """
    if not pre_period_control or not pre_period_test or not post_period_control or not post_period_test:
        return {
            "lift_estimate": None,
            "error": "Empty input series",
            "method": "synthetic_control_simple",
        }

    mean_control_pre  = float(np.mean(pre_period_control))
    mean_test_pre     = float(np.mean(pre_period_test))
    mean_control_post = float(np.mean(post_period_control))
    mean_test_post    = float(np.mean(post_period_test))

    if mean_control_pre == 0:
        return {
            "lift_estimate": None,
            "error": "control pre-period mean is zero; cannot compute scale ratio",
            "method": "synthetic_control_simple",
        }

    scale = mean_test_pre / mean_control_pre
    synthetic_post = scale * mean_control_post
    lift = mean_test_post - synthetic_post
    lift_pct = lift / synthetic_post if synthetic_post != 0 else 0.0

    # Bootstrap credible interval on the lift estimate
    control_pre_arr  = np.array(pre_period_control, dtype=float)
    test_pre_arr     = np.array(pre_period_test, dtype=float)
    control_post_arr = np.array(post_period_control, dtype=float)
    test_post_arr    = np.array(post_period_test, dtype=float)

    n_ctrl_pre  = len(control_pre_arr)
    n_test_pre  = len(test_pre_arr)
    n_ctrl_post = len(control_post_arr)
    n_test_post = len(test_post_arr)

    bootstrap_lifts = []
    for _ in range(_MC_SAMPLES):
        s_ctrl_pre  = _block_bootstrap_sample(control_pre_arr)
        s_test_pre  = _block_bootstrap_sample(test_pre_arr)
        s_ctrl_post = _block_bootstrap_sample(control_post_arr)
        s_test_post = _block_bootstrap_sample(test_post_arr)

        m_ctrl_pre  = s_ctrl_pre.mean()
        if m_ctrl_pre == 0:
            continue
        s_scale     = s_test_pre.mean() / m_ctrl_pre
        s_synthetic = s_scale * s_ctrl_post.mean()
        bootstrap_lifts.append(float(s_test_post.mean() - s_synthetic))

    if not bootstrap_lifts:
        ci_lower = ci_upper = lift
        p_positive = 1.0 if lift > 0 else 0.0
    else:
        alpha = (1 - _CI_LEVEL) / 2
        ci_lower  = float(np.quantile(bootstrap_lifts, alpha))
        ci_upper  = float(np.quantile(bootstrap_lifts, 1 - alpha))
        p_positive = float(np.mean(np.array(bootstrap_lifts) > 0))

    return {
        "lift_estimate":          round(lift, 4),
        "lift_pct":               round(lift_pct * 100, 2),
        "ci_lower":               round(ci_lower, 4),
        "ci_upper":               round(ci_upper, 4),
        "ci_level":               _CI_LEVEL,
        "p_positive":             round(p_positive, 4),
        "synthetic_counterfactual": round(synthetic_post, 4),
        "scale_ratio":            round(scale, 4),
        "method":                 "synthetic_control_simple",
        "note":                   "Uses client sales data, not pixel. Honest noise per §17.3.",
    }
