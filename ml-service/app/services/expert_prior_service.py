"""Expert prior shift (roadmap 9-9; arch §15.13; vision §12.4) — pure logic.

The marketer's 1–5 rating of a creative becomes the Beta prior a new Thompson arm starts from:
    1 → Beta(1,3)   2 → Beta(1,2)   3 → Beta(1,1)   4 → Beta(2,1)   5 → Beta(3,1)
Veto → Beta(1,5). Spring stores the pair in creatives.exploration_prior_alpha/beta (ExpertPriorMath);
`combine_priors` merges it with the GLM-informed prior when both exist, weighting by effective n.
"""
from __future__ import annotations

from typing import Optional

_TABLE = {1: (1.0, 3.0), 2: (1.0, 2.0), 3: (1.0, 1.0), 4: (2.0, 1.0), 5: (3.0, 1.0)}


def expert_prior(rating: Optional[int], veto: bool = False) -> tuple[float, float]:
    if veto:
        return 1.0, 5.0
    if rating is None:
        return 1.0, 1.0
    r = max(1, min(5, int(rating)))
    return _TABLE[r]


def combine_priors(glm: Optional[tuple[float, float]], expert: Optional[tuple[float, float]]) -> tuple[float, float]:
    """Average two Beta priors by their effective sample size n = α + β.

    The combined mean is the n-weighted mean of the two means and the combined n is the mean of the
    two n's (not the sum: the two sources are not independent evidence). Either side may be None.
    """
    if glm is None and expert is None:
        return 1.0, 1.0
    if glm is None:
        return float(expert[0]), float(expert[1])
    if expert is None:
        return float(glm[0]), float(glm[1])
    n_g, n_e = glm[0] + glm[1], expert[0] + expert[1]
    mean = (glm[0] + expert[0]) / (n_g + n_e)            # n-weighted mean of α/(α+β)
    n = (n_g + n_e) / 2.0
    alpha = max(0.5, round(mean * n, 4))
    beta = max(0.5, round((1 - mean) * n, 4))
    return alpha, beta


def prior_for_arm(glm_prior: Optional[dict], expert_rating: Optional[int], veto: bool = False,
                  stored: Optional[tuple[float, float]] = None) -> dict:
    """Resolve the starting Beta for a new arm.

    glm_prior:  dict from glm_prior_service.prior_for_variant (uses alpha0/beta0 when matched_atoms)
    stored:     creatives.exploration_prior_alpha/beta when Spring already computed them
    """
    glm = None
    if glm_prior and glm_prior.get("matched_atoms"):
        glm = (float(glm_prior["alpha0"]), float(glm_prior["beta0"]))
    exp = stored if stored and stored[0] is not None else (expert_prior(expert_rating, veto) if (expert_rating or veto) else None)
    alpha, beta = combine_priors(glm, exp)
    source = "GLM+EXPERT" if glm and exp else "GLM" if glm else "EXPERT" if exp else "FLAT"
    return {"alpha0": alpha, "beta0": beta, "source": source}
