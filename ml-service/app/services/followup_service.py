"""
Follow-up sequence optimisation — Thompson Sampling bandit (vision §19.6).
No FastAPI/Pydantic imports — pure logic only.
"""
from __future__ import annotations

import random
from typing import Optional


# ─── Context classification ───────────────────────────────────────────────────

_INTL_COUNTRY_CODES = frozenset([
    "CA", "GB", "AU", "DE", "FR", "SG", "AE", "CH", "NL", "SE", "NO",
    "DK", "FI", "NZ", "HK", "JP", "KR", "IL",
])

_HIGH_TICKET_BUCKETS = frozenset(["50_100K", "100_250K", "GT250K"])


def classify_context(country_code: Optional[str], ticket_bucket: Optional[str]) -> str:
    """
    Returns one of four context keys for follow-up bandit partitioning:
      domestic:high_ticket  |  domestic:low_ticket
      intl:high_ticket      |  intl:low_ticket

    Matches arch §16.6 context keys: {intl/domestic} × {high/low ticket}.
    """
    geo = "intl" if (country_code or "").upper() in _INTL_COUNTRY_CODES else "domestic"
    ticket = "high_ticket" if (ticket_bucket or "") in _HIGH_TICKET_BUCKETS else "low_ticket"
    return f"{geo}:{ticket}"


# ─── Thompson bandit ─────────────────────────────────────────────────────────

def thompson_select_variant(
    variants: list[dict],
    context_key: str,
    exploration_rate: float = 0.15,
) -> dict:
    """
    Beta-Thompson sampling over followup_variants for a given context_key.

    Rules (vision §19.6):
    - Control arm is NEVER paused; always included.
    - exploration_rate (10-20%) forces random choice; otherwise Beta sampling.
    - Only APPROVED + is_control variants are eligible; DRAFT/PAUSED excluded.

    Returns the selected variant dict (must include id, name, alpha, beta, is_control).
    Raises ValueError if no eligible variant exists.
    """
    eligible = [
        v for v in variants
        if v.get("lifecycle_state") == "APPROVED" or v.get("is_control", False)
    ]
    if not eligible:
        raise ValueError(f"No eligible followup variants for context_key={context_key}")

    if random.random() < exploration_rate:
        return random.choice(eligible)

    best = max(eligible, key=lambda v: random.betavariate(
        max(v.get("alpha", 1.0), 0.001),
        max(v.get("beta", 1.0), 0.001),
    ))
    return best


# ─── Auto-pause guard ─────────────────────────────────────────────────────────

_N_SIMULATIONS = 2000


def should_auto_pause(
    variant_alpha: float,
    variant_beta: float,
    control_alpha: float,
    control_beta: float,
    prior_alpha: float = 1.0,
    prior_beta: float = 1.0,
    min_ess: int = 100,
) -> tuple[bool, float]:
    """
    Monte Carlo estimate of P(variant is WORSE than control).
    Auto-pause if P(worse) > 0.95 AND ESS >= min_ess.

    ESS approximation: α + β - prior_alpha - prior_beta (informed priors supported).
    Returns (should_pause, p_worse).
    """
    ess = (variant_alpha + variant_beta - prior_alpha - prior_beta)
    if ess < min_ess:
        return False, 0.0

    worse_count = 0
    for _ in range(_N_SIMULATIONS):
        v_sample = random.betavariate(
            max(variant_alpha, 0.001), max(variant_beta, 0.001))
        c_sample = random.betavariate(
            max(control_alpha, 0.001), max(control_beta, 0.001))
        if v_sample < c_sample:
            worse_count += 1

    p_worse = worse_count / _N_SIMULATIONS
    return p_worse > 0.95, round(p_worse, 4)


# ─── Show-rate posterior ─────────────────────────────────────────────────────

def compute_show_rate_posterior(
    showed: int,
    total: int,
    prior_alpha: float = 1.0,
    prior_beta: float = 1.0,
) -> dict:
    """
    Beta posterior for show-rate given binary outcome counts.
    Returns mean, credible interval, and raw posterior params.
    """
    if total < 0 or showed < 0 or showed > total:
        raise ValueError("Invalid showed/total counts")

    alpha_post = prior_alpha + showed
    beta_post = prior_beta + (total - showed)
    mean = alpha_post / (alpha_post + beta_post)

    # 90% credible interval via random.betavariate sampling
    samples = sorted(
        random.betavariate(max(alpha_post, 0.001), max(beta_post, 0.001))
        for _ in range(2000)
    )
    lo = samples[int(0.05 * 2000)]
    hi = samples[int(0.95 * 2000)]

    return {
        "alpha": alpha_post,
        "beta": beta_post,
        "mean": round(mean, 4),
        "ci_90_lo": round(lo, 4),
        "ci_90_hi": round(hi, 4),
        "ess": showed + (total - showed),
    }
