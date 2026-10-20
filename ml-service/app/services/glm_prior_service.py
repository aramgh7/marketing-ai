"""GLM-informed Beta priors for new Thompson arms — pure logic (roadmap 9-10 / 9-11; vision §15.4–15.6).

A new landing variant starts today from Beta(1,1). The GLM already knows how its atoms performed
(atom posteriors, logit scale) and the hierarchy service knows the niche base rate. This module turns
that knowledge into an informative but weak Beta(α0, β0) prior (effective sample size n0 ≤ 20), so
the first real sessions still dominate quickly.

  prior_from_predictive(mean, sd)              method of moments on the Beta
  prior_for_variant(atom_posteriors, atoms,    logit-additive atom effects → predictive mean/sd
                    base_rate, base_sd)
  blend_with_hierarchy(prior, hierarchy_prior) shrink toward the niche/client prior of
                                               hierarchy_prior_service.get_effective_prior
"""
from __future__ import annotations

import math

N0_MAX_DEFAULT = 20.0
_MEAN_LO, _MEAN_HI = 0.001, 0.999


def _logit(p: float) -> float:
    p = min(max(p, _MEAN_LO), _MEAN_HI)
    return math.log(p / (1.0 - p))


def _sigmoid(x: float) -> float:
    if x >= 0:
        return 1.0 / (1.0 + math.exp(-x))
    e = math.exp(x)
    return e / (1.0 + e)


def prior_from_predictive(mean_cvr: float, sd_cvr: float, n0_max: float = N0_MAX_DEFAULT) -> tuple[float, float]:
    """Beta(α0, β0) whose mean is `mean_cvr` and whose spread matches `sd_cvr`.

    Method of moments: n = m(1−m)/sd² − 1, clamped to [1, n0_max]; α0 = m·n, β0 = (1−m)·n.
    A huge sd (no information) collapses to n = 1 → nearly flat; a tiny sd is capped at n0_max so the
    prior can never outweigh ~20 real sessions.
    """
    m = min(max(float(mean_cvr), _MEAN_LO), _MEAN_HI)
    sd = float(sd_cvr) if sd_cvr is not None and sd_cvr > 0 else None
    if sd is None:
        n = 1.0
    else:
        n = m * (1.0 - m) / (sd * sd) - 1.0
        if not math.isfinite(n):
            n = 1.0
        n = min(max(n, 1.0), float(n0_max))
    return round(m * n, 6), round((1.0 - m) * n, 6)


def prior_for_variant(
    atom_posteriors: list[dict],
    variant_atoms: list[dict],
    base_rate: float,
    base_sd: float,
    n0_max: float = N0_MAX_DEFAULT,
) -> dict:
    """Combine matching atom effects on the logit scale.

    atom_posteriors rows: {atom_type, atom_value, mean, sd} — GLM coefficients (logit units).
    variant_atoms rows:   {atom_type, atom_value}.
    predictive logit = logit(base_rate) + Σ mean_i ; predictive var (logit) = var(base) + Σ sd_i²
    then mapped back through the sigmoid (delta method for the sd).
    Nothing matched → Beta(1,1) with n0 = 2 and matched_atoms = [].
    """
    index = {}
    for row in atom_posteriors or []:
        key = (str(row.get("atom_type", "")).upper(), str(row.get("atom_value", "")).lower())
        index[key] = row
    matched: list[dict] = []
    for a in variant_atoms or []:
        key = (str(a.get("atom_type", "")).upper(), str(a.get("atom_value", "")).lower())
        row = index.get(key)
        if row is not None:
            matched.append({"atom_type": key[0], "atom_value": key[1],
                            "mean": float(row.get("mean", 0.0)), "sd": float(row.get("sd", 0.0) or 0.0)})

    if not matched:
        return {"alpha0": 1.0, "beta0": 1.0, "n0": 2.0, "mean": None, "sd": None,
                "matched_atoms": [], "source": "FLAT"}

    base = min(max(float(base_rate), _MEAN_LO), _MEAN_HI)
    logit_mean = _logit(base) + sum(m["mean"] for m in matched)
    # base sd on the probability scale → logit scale via delta method
    base_logit_sd = float(base_sd) / (base * (1.0 - base)) if base_sd and base_sd > 0 else 0.0
    logit_var = base_logit_sd ** 2 + sum(m["sd"] ** 2 for m in matched)

    p = _sigmoid(logit_mean)
    sd_p = math.sqrt(logit_var) * p * (1.0 - p) if logit_var > 0 else 0.0
    if sd_p <= 0:
        sd_p = math.sqrt(p * (1.0 - p) / (n0_max + 1))   # cap at n0_max worth of certainty

    a0, b0 = prior_from_predictive(p, sd_p, n0_max)
    return {"alpha0": a0, "beta0": b0, "n0": round(a0 + b0, 6), "mean": round(p, 6),
            "sd": round(sd_p, 6), "matched_atoms": matched, "source": "GLM"}


def blend_with_hierarchy(prior: dict, hierarchy_prior: dict | None, n_hier: float | None = None,
                         n0_max: float = N0_MAX_DEFAULT) -> dict:
    """Shrink a variant prior toward the hierarchy prior (niche / client level).

    hierarchy_prior is the dict returned by hierarchy_prior_service.get_effective_prior:
    {"source": ..., "prior": {"distribution", "mu", "sigma", ...}, "client_observations"?}; mu/sigma
    are on the rate scale (UNIVERSAL_PRIORS units). n_hier defaults to the Beta effective size implied
    by (mu, sigma), capped at n0_max. weight = n_hier / (n_hier + n0).
    """
    if not hierarchy_prior or not hierarchy_prior.get("prior"):
        return {**prior, "hierarchy_weight": 0.0}
    hp = hierarchy_prior["prior"]
    mu = float(hp.get("mu", 0.0))
    sigma = float(hp.get("sigma", 0.0) or 0.0)
    if not (0.0 < mu < 1.0):
        return {**prior, "hierarchy_weight": 0.0}
    ha, hb = prior_from_predictive(mu, sigma if sigma > 0 else None, n0_max)
    nh = float(n_hier) if n_hier is not None else (ha + hb)
    nh = min(max(nh, 0.0), float(n0_max))

    p_mean = prior.get("mean")
    n0 = float(prior.get("n0", 2.0))
    if p_mean is None:                       # flat variant prior → take the hierarchy prior fully
        w = 1.0
        mean = mu
    else:
        w = nh / (nh + n0) if (nh + n0) > 0 else 0.0
        mean = w * mu + (1.0 - w) * float(p_mean)
    n_total = min(max(n0 + nh, 1.0), float(n0_max))
    a0, b0 = round(mean * n_total, 6), round((1.0 - mean) * n_total, 6)
    return {**prior, "alpha0": a0, "beta0": b0, "n0": round(n_total, 6), "mean": round(mean, 6),
            "hierarchy_weight": round(w, 4), "hierarchy_source": hierarchy_prior.get("source"),
            "source": "GLM+HIER" if p_mean is not None else "HIER"}
