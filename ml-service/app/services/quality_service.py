"""
Quality Score service — no FastAPI/Pydantic imports.
Computes per-session quality scores and clickbait detection.
Architecture §15.5.
"""
from __future__ import annotations

import math

DEFAULT_WEIGHTS = {
    "match_score": 0.25,
    "non_bounce": 0.25,
    "scroll_depth": 0.25,
    "form_completion": 0.25,
}

# Roadmap 8-3 (2-12 change): when the tenant's quality key is an appointment stage, whether the
# booked call was actually held carries the highest weight.
APPOINTMENT_WEIGHTS = {
    "match_score": 0.15,
    "non_bounce": 0.15,
    "scroll_depth": 0.15,
    "form_completion": 0.15,
    "show_rate": 0.40,
}


def compute_quality_score(
    match_score: float | None,
    bounced: bool,
    scroll_depth: float,
    form_completion: float,
    weights: dict | None = None,
    show_rate: float | None = None,
) -> float:
    """
    Compute quality score for a session.
    quality_score = w1*match_score + w2*(1-bounce) + w3*scroll_depth_norm + w4*form_completion
                    [+ w5*show_rate when an appointment outcome exists — roadmap 8-3]
    All inputs normalized to [0, 1]. show_rate: 1.0 held, 0.0 no-show, None = no outcome yet
    (base weights apply, so unresolved bookings are never penalised).

    Quality Score is NOT added to alpha/beta (one binary event per arm, arch §4.4).
    It acts as a protective metric and a multiplier on the Phase 3 profit estimate.
    """
    base = APPOINTMENT_WEIGHTS if show_rate is not None else DEFAULT_WEIGHTS
    w = {**base, **(weights or {})}

    ms = match_score if match_score is not None else 0.5  # neutral prior if unknown
    nb = 0.0 if bounced else 1.0
    sd = max(0.0, min(1.0, scroll_depth))
    fc = max(0.0, min(1.0, form_completion))

    score = (
        w["match_score"] * ms
        + w["non_bounce"] * nb
        + w["scroll_depth"] * sd
        + w["form_completion"] * fc
    )
    if show_rate is not None:
        score += w.get("show_rate", 0.0) * max(0.0, min(1.0, show_rate))
    return round(max(0.0, min(1.0, score)), 4)


def _prob_a_greater_b(rate_a: float, n_a: int, rate_b: float, n_b: int) -> float:
    """P(rate_a > rate_b) using normal approximation of proportions."""
    if n_a < 5 or n_b < 5:
        return 0.5
    # Normal approximation requires np≥5 and n(1-p)≥5; fall back for sparse rates (e.g. CVR≈1%)
    if (rate_a * n_a < 5 or (1 - rate_a) * n_a < 5
            or rate_b * n_b < 5 or (1 - rate_b) * n_b < 5):
        return 0.5
    se = math.sqrt(
        rate_a * (1 - rate_a) / n_a + rate_b * (1 - rate_b) / n_b
    )
    if se < 1e-10:
        return 1.0 if rate_a > rate_b else 0.0
    z = (rate_a - rate_b) / se
    return 0.5 * (1 + math.erf(z / math.sqrt(2)))


def compute_clickbait_check(
    creative_ctr: float,
    anchor_ctr: float,
    creative_cvr: float,
    anchor_cvr: float,
    creative_bounce: float,
    anchor_bounce: float,
    ctr_n: int,
    cvr_n: int,
    bounce_n: int,
    noshow_beta: float | None = None,
    noshow_beta_threshold: float = -0.3,
) -> dict:
    """
    Bayesian clickbait detector (vision §15.3 pre-LKJ heuristic).
    Flags CLICKBAIT_SUSPECT if:
      P(creative_ctr > anchor_ctr) > 0.9 AND
      (P(creative_cvr < anchor_cvr) > 0.9 OR P(creative_bounce > anchor_bounce) > 0.9
       OR the creative's hook is a no-show driver: β_noshow <= threshold — roadmap 8-3)
    Uses normal approximation for probabilities.
    """
    p_ctr_higher = _prob_a_greater_b(creative_ctr, ctr_n, anchor_ctr, ctr_n)
    p_cvr_lower = 1.0 - _prob_a_greater_b(creative_cvr, cvr_n, anchor_cvr, cvr_n)
    p_bounce_higher = _prob_a_greater_b(creative_bounce, bounce_n, anchor_bounce, bounce_n)
    noshow_driver = noshow_beta is not None and noshow_beta <= noshow_beta_threshold

    is_clickbait = p_ctr_higher > 0.9 and (p_cvr_lower > 0.9 or p_bounce_higher > 0.9 or noshow_driver)

    return {
        "suspect": is_clickbait,
        "p_ctr_higher_than_anchor": round(p_ctr_higher, 3),
        "p_cvr_lower_than_anchor": round(p_cvr_lower, 3),
        "p_bounce_higher_than_anchor": round(p_bounce_higher, 3),
        "noshow_driver": noshow_driver,
        "noshow_beta": noshow_beta,
        "verdict": "CLICKBAIT_SUSPECT" if is_clickbait else "CLEAN",
    }


def detect_cvr_collapse(
    current_cvr: float,
    baseline_cvr: float,
    n_conversions: int,
    n_sessions: int,
    threshold_drop: float = 0.3,
    baseline_n: int | None = None,
) -> dict:
    """
    CVR-collapse detector for 3-13 site health full.
    collapsed=True if relative drop >= threshold AND two-proportion z-test p < 0.05.

    Audit 6.6: the z-test used n_conversions as the baseline sample size (ZeroDivisionError on a
    full collapse, huge SE otherwise). `baseline_n` is the number of baseline sessions; when the
    caller does not know it, the current window size is assumed.
    """
    relative_drop = (baseline_cvr - current_cvr) / max(baseline_cvr, 1e-9)
    n1 = n_sessions
    n2 = baseline_n if baseline_n and baseline_n > 0 else n_sessions

    # Two-proportion z-test (normal approximation)
    if n_sessions < 10 or baseline_cvr <= 0:
        return {
            "collapsed": False,
            "relative_drop": round(relative_drop, 4),
            "p_value_approx": 1.0,
            "verdict": "INSUFFICIENT_DATA",
        }

    pooled = (current_cvr * n1 + baseline_cvr * n2) / (n1 + n2)
    se = math.sqrt(max(pooled * (1 - pooled), 0.0) * (1 / n1 + 1 / n2))
    if se < 1e-10:
        z = 0.0
    else:
        z = (baseline_cvr - current_cvr) / se

    # One-tailed p-value (H1: current < baseline)
    p_value = 1.0 - 0.5 * (1.0 + math.erf(z / math.sqrt(2)))
    collapsed = relative_drop >= threshold_drop and p_value < 0.05

    return {
        "collapsed": collapsed,
        "relative_drop": round(relative_drop, 4),
        "p_value_approx": round(p_value, 4),
        "verdict": "CVR_COLLAPSE" if collapsed else "STABLE",
    }


def aggregate_quality_scores(
    sessions: list[dict],
    match_score_by_variant: dict,
    weights: dict | None = None,
) -> dict:
    """
    Aggregate quality scores across sessions grouped by creative_id and variant_id.
    Returns {"by_creative": {cid: avg_score}, "by_variant": {vid: avg_score}}.
    """
    creative_scores: dict[str, list[float]] = {}
    variant_scores: dict[str, list[float]] = {}

    for s in sessions:
        ms = match_score_by_variant.get(str(s.get("variant_id")))
        event_count = int(s.get("event_count", 0))
        has_scroll = bool(s.get("has_scroll", False))
        bounced = event_count <= 1 and not has_scroll
        scroll = float(s.get("max_scroll_depth", 0)) / 100.0

        if s.get("converted"):
            fc = 1.0
        elif s.get("form_started"):
            fc = 0.3
        else:
            fc = 0.0

        score = compute_quality_score(ms, bounced, scroll, fc, weights)

        cid = str(s.get("creative_id")) if s.get("creative_id") else None
        vid = str(s.get("variant_id")) if s.get("variant_id") else None

        if cid:
            creative_scores.setdefault(cid, []).append(score)
        if vid:
            variant_scores.setdefault(vid, []).append(score)

    return {
        "by_creative": {k: round(sum(v) / len(v), 4) for k, v in creative_scores.items()},
        "by_variant": {k: round(sum(v) / len(v), 4) for k, v in variant_scores.items()},
    }
