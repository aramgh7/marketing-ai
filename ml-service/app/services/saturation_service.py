"""Audience Saturation Detection Service (Phase 6-2, §8.2).

Detects d(Reach)/d(Spend) decline and differentiates creative fatigue
from true audience saturation.
"""
from typing import Optional


def compute_reach_efficiency(
    reach_current: float,
    reach_prev: float,
    spend_current: float,
    spend_prev: float,
) -> float:
    """Compute current reach-per-dollar relative to previous period."""
    if spend_current <= 0 or spend_prev <= 0:
        return 1.0
    eff_current = reach_current / spend_current
    eff_prev    = reach_prev / spend_prev
    if eff_prev <= 0:
        return 1.0
    return eff_current / eff_prev


def classify_saturation(
    reach_efficiency: float,
    frequency: float,
    frequency_prev: float,
    baseline_efficiency: float = 1.0,
) -> dict:
    """Classify saturation stage using efficiency drop and frequency rise."""
    eff_drop    = max(0.0, baseline_efficiency - reach_efficiency)
    eff_score   = min(1.0, eff_drop / baseline_efficiency) if baseline_efficiency > 0 else 0.0

    freq_rise   = max(0.0, frequency - frequency_prev)
    freq_score  = min(1.0, freq_rise / 5.0)

    score = 0.5 * eff_score + 0.5 * freq_score

    if score > 0.65:
        stage = "critical"
    elif score > 0.30:
        stage = "warning"
    else:
        stage = "healthy"

    return {
        "score":            round(score, 4),
        "stage":            stage,
        "reach_efficiency": round(reach_efficiency, 4),
        "eff_score":        round(eff_score, 4),
        "freq_score":       round(freq_score, 4),
    }


def differentiate_diagnosis(
    ctr_change_pct: float,
    reach_efficiency: float,
    frequency: float,
) -> str:
    """Distinguish creative fatigue from audience saturation or market shift.

    Returns one of: 'creative_fatigue' | 'audience_saturation' | 'market_shift' | 'healthy'
    """
    ctr_dropped   = ctr_change_pct < -0.15
    reach_dropped = reach_efficiency < 0.70
    high_freq     = frequency > 4.0

    if ctr_dropped and not reach_dropped:
        return "creative_fatigue"
    if reach_dropped and high_freq:
        return "audience_saturation"
    if reach_dropped and not high_freq:
        return "market_shift"
    return "healthy"
