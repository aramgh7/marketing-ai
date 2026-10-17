"""Creative Burnout Detection Service (Phase 6-1, §8.1).

Pure-logic functions: burnout score, classification, days-remaining estimate.
No FastAPI imports — safe to unit-test without Pydantic.
"""
from typing import Optional


def compute_burnout_score(
    frequency: float,
    ctr_current: float,
    ctr_baseline: float,
    spend_current: float = 0.0,
    spend_prev: float = 0.0,
) -> dict:
    """Compute creative burnout score [0-1] from frequency and CTR drop."""
    ctr_drop_pct = (ctr_baseline - ctr_current) / ctr_baseline if ctr_baseline > 0 else 0.0
    ctr_drop_pct = max(0.0, ctr_drop_pct)
    frequency_penalty = min(1.0, max(0.0, (frequency - 1.5) / 6.5))
    score = min(1.0, max(0.0, 0.6 * ctr_drop_pct + 0.4 * frequency_penalty))
    stage = classify_burnout(score)
    signals = []
    if ctr_drop_pct > 0.3:
        signals.append(f"CTR dropped {ctr_drop_pct * 100:.0f}% from baseline")
    if frequency > 4.0:
        signals.append(f"High frequency: {frequency:.1f}")
    if frequency > 2.0 and ctr_drop_pct > 0.15:
        signals.append("Frequency rising while CTR declining")
    return {"score": round(score, 4), "stage": stage, "signals": signals}


def classify_burnout(score: float) -> str:
    """Classify burnout stage from score."""
    if score > 0.7:
        return "critical"
    if score > 0.35:
        return "warning"
    return "none"


def estimate_days_remaining(frequency: float, ctr_drop_rate_per_day: float) -> int:
    """Estimate days until creative reaches warning-stage burnout (score > 0.35)."""
    if ctr_drop_rate_per_day <= 0:
        return 90
    days = int(0.35 / ctr_drop_rate_per_day)
    return max(1, min(90, days))
