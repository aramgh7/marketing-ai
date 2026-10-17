"""
Reward Ladder for landing variant Thompson Sampling (vision §19.3).
Pure logic — zero FastAPI/Pydantic imports.

CRITICAL: thompson_arms uses BINARY increments (1.0 only).
reward_weight in funnel_stages is for ANALYTICS CALIBRATION only.
The quality gate stage triggers the actual thompson_arms reward.
"""
from __future__ import annotations
import math


def compute_stage_reward(
    stage_key: str,
    is_quality_stage: bool,
    funnel_stages: list[dict],
) -> dict:
    """
    Returns thompson_increment (0.0 or 1.0), micro_reward, should_update_thompson.
    thompson_increment is 1.0 ONLY when stage is the quality gate stage.
    """
    stage = next((s for s in funnel_stages if s["stage_key"] == stage_key), None)
    if stage is None:
        return {
            "thompson_increment":     0.0,
            "micro_reward":           0.0,
            "should_update_thompson": False,
        }

    is_quality = stage.get("is_quality_key", False) or is_quality_stage
    micro      = float(stage.get("micro_reward_weight", 0.0))

    return {
        "thompson_increment":     1.0 if is_quality else 0.0,
        "micro_reward":           micro,
        "should_update_thompson": is_quality,
    }


def compute_funnel_progress_score(
    reached_stages: list[str],
    funnel_stages: list[dict],
) -> dict:
    """
    Normalized progress score [0,1] — how far a session got through the funnel.
    NOT used for thompson_arms.
    """
    if not funnel_stages:
        return {"score": 0.0, "deepest_stage": None, "stages_reached": 0}

    ordered     = sorted(funnel_stages, key=lambda s: s.get("ordinal", 0))
    max_ordinal = ordered[-1].get("ordinal", 1)

    deepest_ordinal = 0
    deepest_key     = None
    for stage in ordered:
        if stage["stage_key"] in reached_stages:
            if stage.get("ordinal", 0) > deepest_ordinal:
                deepest_ordinal = stage.get("ordinal", 0)
                deepest_key     = stage["stage_key"]

    score = deepest_ordinal / max(max_ordinal, 1)
    return {
        "score":           round(score, 4),
        "deepest_stage":   deepest_key,
        "stages_reached":  len(reached_stages),
        "max_ordinal":     max_ordinal,
        "deepest_ordinal": deepest_ordinal,
    }


def estimate_quality_rate(
    reward_events: list[dict],
    variant_id: str,
    days: int = 30,
) -> dict:
    """
    Beta(quality_hits+1, noshow+1) posterior for show-rate of a landing variant.
    """
    from datetime import datetime, timezone, timedelta
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)

    recent = []
    for e in reward_events:
        if e.get("variant_id") != variant_id:
            continue
        rat = e.get("rewarded_at")
        if rat is None:
            recent.append(e)
        else:
            try:
                dt = datetime.fromisoformat(str(rat).replace("Z", "+00:00"))
                if dt >= cutoff:
                    recent.append(e)
            except Exception:
                recent.append(e)

    quality_hits = sum(1 for e in recent if e.get("is_quality_stage"))
    total        = len(recent)
    alpha        = quality_hits + 1
    beta         = (total - quality_hits) + 1
    mean         = alpha / (alpha + beta)
    z            = 1.282
    se           = math.sqrt(mean * (1 - mean) / max(total, 1))

    return {
        "variant_id":   variant_id,
        "quality_hits": quality_hits,
        "total":        total,
        "alpha":        alpha,
        "beta":         beta,
        "mean":         round(mean, 4),
        "ci_80_lower":  round(max(0.0, mean - z * se), 4),
        "ci_80_upper":  round(min(1.0, mean + z * se), 4),
    }


# ── Show-Rate Corrector (roadmap 8-3; vision §19.10) ──────────────────────

def compute_show_rate_corrector(
    booked_count:     int,
    held_count:       int,
    prior_alpha:      float = 3.0,
    prior_beta:       float = 2.0,
    shrinkage_weight: float = 0.3,
) -> dict:
    """
    Weekly show-rate corrector with Beta shrinkage (roadmap 8-3; vision §19.10).
    Returns multiplier for L1/L2 quality gate thresholds.

    multiplier < 1.0 → penalty (high no-show rate)
    multiplier = 1.0 → neutral (at prior expectation)
    multiplier > 1.0 → bonus (better show rate than prior)
    Capped [0.5, 1.5] — never halve or more-than-double the gate.
    """
    if booked_count <= 0:
        return {
            "multiplier":     1.0,
            "show_rate_obs":  None,
            "show_rate_post": round(prior_alpha / (prior_alpha + prior_beta), 4),
            "note":           "No bookings — using pure prior",
        }

    post_alpha = prior_alpha + held_count
    post_beta  = prior_beta  + (booked_count - held_count)
    post_mean  = post_alpha / (post_alpha + post_beta)
    obs_rate   = held_count / booked_count
    prior_mean = prior_alpha / (prior_alpha + prior_beta)

    blended    = (1 - shrinkage_weight) * post_mean + shrinkage_weight * prior_mean
    multiplier = blended / prior_mean if prior_mean > 0 else 1.0
    multiplier = max(0.5, min(1.5, multiplier))

    return {
        "multiplier":      round(multiplier, 4),
        "show_rate_obs":   round(obs_rate, 4),
        "show_rate_post":  round(post_mean, 4),
        "show_rate_blend": round(blended, 4),
        "booked":          booked_count,
        "held":            held_count,
        "post_alpha":      round(post_alpha, 2),
        "post_beta":       round(post_beta, 2),
        "prior_mean":      round(prior_mean, 4),
    }


def apply_show_rate_to_gates(
    l1_threshold: float,
    l2_threshold: float,
    multiplier: float,
) -> dict:
    """
    Gate threshold adjustment: bad show rate tightens gates (higher threshold needed).
    """
    adjusted_l1 = l1_threshold / multiplier if multiplier > 0 else l1_threshold
    adjusted_l2 = l2_threshold / multiplier if multiplier > 0 else l2_threshold
    return {
        "l1_original":  l1_threshold,
        "l2_original":  l2_threshold,
        "l1_adjusted":  round(adjusted_l1, 4),
        "l2_adjusted":  round(adjusted_l2, 4),
        "multiplier":   round(multiplier, 4),
    }
