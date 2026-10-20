"""
Diagnostics baseline + Benjamini-Hochberg correction — pure logic.
Compares symptoms against tenant's own baseline per placement×device.
No FastAPI/Pydantic imports.
"""
from __future__ import annotations
import math
from typing import Optional

MIN_N_FOR_SYMPTOM = 100

SYMPTOMS = [
    "CTR_DROP", "CVR_DROP", "BOUNCE_SPIKE", "SCROLL_DEPTH_LOW",
    "LPV_CLICK_GAP", "MARKET_SHIFT", "CREATIVE_FATIGUE",
]

# Audit 6.7: for these symptoms the bad direction is a RISE of the observed metric
# (bounce rate, fatigue index, LPV/click gap); every other symptom is a drop.
RISE_IS_BAD = {"BOUNCE_SPIKE", "CREATIVE_FATIGUE", "LPV_CLICK_GAP"}


def symptom_p_value(symptom: str, z: float) -> float:
    """One-tailed p-value in the direction that is bad for this symptom."""
    return z_to_p_value(-z) if symptom in RISE_IS_BAD else z_to_p_value(z)


def compute_z_score(observed: float, baseline: float, baseline_std: float, n: int) -> float:
    if baseline_std <= 0 or n <= 0:
        return 0.0
    se = baseline_std / math.sqrt(n)
    return (observed - baseline) / se if se > 0 else 0.0


def z_to_p_value(z: float) -> float:
    """One-tailed p-value from z-score (lower tail for drops). A&S approximation."""
    abs_z = abs(z)
    t = 1.0 / (1.0 + 0.2316419 * abs_z)
    poly = t * (0.319381530
                + t * (-0.356563782
                       + t * (1.781477937
                              + t * (-1.821255978
                                     + t * 1.330274429))))
    p_upper = (1.0 / math.sqrt(2 * math.pi)) * math.exp(-0.5 * abs_z * abs_z) * poly
    if z < 0:
        return p_upper
    return 1.0 - p_upper


def benjamini_hochberg(p_values: list[float], fdr: float = 0.10) -> list[bool]:
    """BH procedure for FDR control. Returns True = significant after correction.

    Audit 6.8: reject every hypothesis up to the LARGEST rank k with p(k) <= k·q/n. The previous
    loop stopped at the first non-significant rank, which under-rejects
    (p = [0.04, 0.05, 0.06], q = 0.10 -> BH rejects all three; the old code rejected none).
    """
    n = len(p_values)
    if n == 0:
        return []
    indexed = sorted(enumerate(p_values), key=lambda x: x[1])
    k_max = 0
    for rank, (_idx, p) in enumerate(indexed, start=1):
        if p <= fdr * rank / n:
            k_max = rank
    rejected = [False] * n
    for rank, (idx, _p) in enumerate(indexed, start=1):
        if rank <= k_max:
            rejected[idx] = True
    return rejected


def check_minimum_n(sample_size: int, min_n: int = MIN_N_FOR_SYMPTOM) -> bool:
    return sample_size >= min_n


def run_diagnostics(
    observations: list[dict],
    fdr: float = 0.10,
) -> dict:
    """
    Run full diagnostics pipeline with BH correction.
    Each symptom compared against tenant's own baseline per placement×device
    to avoid Simpson's paradox (vision §3.2).
    """
    eligible = [o for o in observations if check_minimum_n(o.get("n", 0))]

    if not eligible:
        return {
            "symptoms": [],
            "suppressed_count": len(observations),
            "note": f"All symptoms suppressed: n < {MIN_N_FOR_SYMPTOM}",
        }

    p_values = []
    for obs in eligible:
        z = compute_z_score(
            obs["observed"], obs["baseline"],
            obs.get("baseline_std", obs["baseline"] * 0.1 + 1e-6),
            obs["n"],
        )
        p_values.append(symptom_p_value(obs.get("symptom", ""), z))

    significant_flags = benjamini_hochberg(p_values, fdr)

    symptoms_out = []
    for i, obs in enumerate(eligible):
        if significant_flags[i]:
            z = compute_z_score(
                obs["observed"], obs["baseline"],
                obs.get("baseline_std", obs["baseline"] * 0.1 + 1e-6),
                obs["n"],
            )
            symptoms_out.append({
                "symptom":   obs["symptom"],
                "placement": obs.get("placement", "UNKNOWN"),
                "device":    obs.get("device", "UNKNOWN"),
                "observed":  obs["observed"],
                "baseline":  obs["baseline"],
                "z_score":   round(z, 3),
                "p_value":   round(p_values[i], 4),
                "n":         obs["n"],
            })

    return {
        "symptoms":         symptoms_out,
        "total_checked":    len(eligible),
        "suppressed_count": len(observations) - len(eligible),
        "fdr_threshold":    fdr,
        "bh_applied":       True,
    }


MARKET_SHIFT_CTR_DROP_THRESHOLD  = -0.20  # -20% CTR vs baseline
MARKET_SHIFT_CVR_DROP_THRESHOLD  = -0.20  # -20% CVR vs baseline
MARKET_SHIFT_MULTI_PLACEMENT_MIN = 2      # must appear across ≥2 placements


def detect_market_shift(
    symptom_results: list[dict],
    ctr_baseline: float,
    cvr_baseline: float,
    ctr_observed: float,
    cvr_observed: float,
) -> dict:
    """
    Market-shift signal: drops that are systematic across placements and metrics
    rather than creative-specific. Fires when BOTH CTR and CVR fall >20%
    and the MARKET_SHIFT symptom appears across ≥2 placements (vision §3.2).
    """
    if ctr_baseline <= 0 or cvr_baseline <= 0:
        return {"market_shift_detected": False, "reason": "No baseline available"}

    ctr_delta = (ctr_observed - ctr_baseline) / ctr_baseline
    cvr_delta = (cvr_observed - cvr_baseline) / cvr_baseline

    market_placements = [
        s for s in symptom_results
        if s.get("symptom") == "MARKET_SHIFT"
    ]

    ctr_drop_ok   = ctr_delta <= MARKET_SHIFT_CTR_DROP_THRESHOLD
    cvr_drop_ok   = cvr_delta <= MARKET_SHIFT_CVR_DROP_THRESHOLD
    multi_place   = len(market_placements) >= MARKET_SHIFT_MULTI_PLACEMENT_MIN

    detected = ctr_drop_ok and cvr_drop_ok and multi_place

    return {
        "market_shift_detected": detected,
        "ctr_delta_pct":         round(ctr_delta * 100, 2),
        "cvr_delta_pct":         round(cvr_delta * 100, 2),
        "placements_affected":   len(market_placements),
        "thresholds": {
            "ctr_drop_pct":      MARKET_SHIFT_CTR_DROP_THRESHOLD * 100,
            "cvr_drop_pct":      MARKET_SHIFT_CVR_DROP_THRESHOLD * 100,
            "min_placements":    MARKET_SHIFT_MULTI_PLACEMENT_MIN,
        },
        "reason": (
            "MARKET_SHIFT: systematic drop across placements"
            if detected else
            f"Not detected — ctr_drop={ctr_delta*100:.1f}%, cvr_drop={cvr_delta*100:.1f}%, "
            f"placements={len(market_placements)}"
        ),
    }


def compute_lpv_click_gap(
    page_views: int, meta_clicks: int, threshold_ratio: float = 0.5
) -> dict:
    """LPV_CLICK_GAP: first-party PAGE_VIEW vs Meta reported clicks."""
    if meta_clicks == 0:
        return {"gap_detected": False, "ratio": None, "reason": "No Meta clicks"}
    ratio = page_views / meta_clicks
    gap = ratio < threshold_ratio
    return {
        "gap_detected": gap,
        "ratio": round(ratio, 3),
        "page_views": page_views,
        "meta_clicks": meta_clicks,
        "reason": f"LPV/click ratio {ratio:.2f} {'< ' + str(threshold_ratio) + ' → gap' if gap else '≥ threshold → OK'}",
    }
