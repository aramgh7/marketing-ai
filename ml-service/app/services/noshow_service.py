"""
No-show Propensity Model (vision §19.18.2; arch §16.15).
Pure logic — zero FastAPI/Pydantic imports.

Rule-based model until holdout AUC >= 0.70.
"""
from __future__ import annotations

BOOKING_DISTANCE_COEF = 0.012
# Seed constants — no empirical basis yet (Phase 12-13).
# Replace with fitted LogisticRegression coefficients once ≥200 labelled leads available.
# Source: expert judgment from noshow literature (Gupta et al. 2018, B2B webinar context).
TZ_UNKNOWN_PENALTY    = 0.10
INTERNATIONAL_PENALTY = 0.08
LOW_TICKET_PENALTY    = 0.06
UNACCREDITED_PENALTY  = 0.05
MOBILE_PENALTY        = 0.03
BASE_NOSHOW_RATE      = 0.30


def predict_noshow(
    booking_distance_h: float | None,
    lead_tz_known:       bool,
    ticket_bucket:       str | None,
    self_accredited:     bool | None,
    device_type:         str | None,
    is_international:    bool,
    model_version:       str = "rule_based_v1",
) -> dict:
    """Rule-based no-show probability, clipped [0.05, 0.90]."""
    score = BASE_NOSHOW_RATE

    if booking_distance_h is not None:
        if booking_distance_h < 2:
            score += 0.08
        else:
            score += min(BOOKING_DISTANCE_COEF * booking_distance_h, 0.20)

    if not lead_tz_known:
        score += TZ_UNKNOWN_PENALTY
    if is_international:
        score += INTERNATIONAL_PENALTY
    if ticket_bucket in ("LT25K", None):
        score += LOW_TICKET_PENALTY
    if self_accredited is False:
        score += UNACCREDITED_PENALTY
    if device_type == "MOBILE":
        score += MOBILE_PENALTY

    noshow_prob = max(0.05, min(0.90, score))
    risk_tier   = "HIGH" if noshow_prob >= 0.45 else ("MED" if noshow_prob >= 0.32 else "LOW")

    return {
        "noshow_prob":   round(noshow_prob, 4),
        "risk_tier":     risk_tier,
        "model_version": model_version,
        "auc_note":      "Rule-based; ML activates when holdout AUC >= 0.70",
        "features_used": {
            "booking_distance_h": booking_distance_h,
            "lead_tz_known":      lead_tz_known,
            "ticket_bucket":      ticket_bucket,
            "self_accredited":    self_accredited,
            "device_type":        device_type,
            "is_international":   is_international,
        },
    }


def get_followup_audience_override(risk_tier: str) -> dict:
    """HIGH-risk: coordinator call ≤ 15 min + nearer slot."""
    overrides = {
        "HIGH": {
            "audience_tag":       "HIGH_RISK_NOSHOW",
            "ghl_tag":            "high_risk_noshow",
            "max_touches":        6,
            "cadence_note":       "Coordinator call within 15 min + offer nearer slot",
            "preferred_channels": ["CALL", "SMS"],
        },
        "MED": {
            "audience_tag":       "MED_RISK_NOSHOW",
            "ghl_tag":            "med_risk_noshow",
            "max_touches":        4,
            "preferred_channels": ["SMS", "EMAIL"],
        },
        "LOW": {
            "audience_tag":       None,
            "ghl_tag":            None,
            "max_touches":        3,
            "preferred_channels": ["EMAIL"],
        },
    }
    return overrides.get(risk_tier, overrides["MED"])


def compute_slot_fill_probability(
    booked_count: int,
    noshow_probs: list[float],
) -> dict:
    """P(at least one shows) = 1 − Π(noshow_prob_i)."""
    if not noshow_probs:
        return {"p_no_one_shows": 0.0, "p_at_least_one_shows": 1.0, "booked": booked_count}

    # Independence assumption (Phase 12-14): each booking's no-show probability is treated
    # as independent. Valid for solo bookings; underestimates P(no_one) for paired/group slots
    # where correlated no-show behaviour is expected. Track group_booking flag when available.
    p_no_one = 1.0
    for p in noshow_probs:
        p_no_one *= p

    return {
        "p_no_one_shows":       round(p_no_one, 4),
        "p_at_least_one_shows": round(1 - p_no_one, 4),
        "booked":               booked_count,
        "expected_attendees":   round(sum(1 - p for p in noshow_probs), 2),
    }
