"""
Three-level prior hierarchy — pure logic, no FastAPI/Pydantic imports.
universal → niche → client.
Niche level uses StudentT(nu=4) robust priors (vision §6.4).
"""
from __future__ import annotations
import math
from typing import Optional

UNIVERSAL_PRIORS = {
    "ctr":          {"mu": 0.02,  "sigma": 0.01},
    "cvr":          {"mu": 0.03,  "sigma": 0.02},
    "bounce":       {"mu": 0.55,  "sigma": 0.15},
    "scroll_depth": {"mu": 0.45,  "sigma": 0.20},
}


def studentt_to_normal_moments(mu: float, sigma: float, nu: float = 4.0) -> tuple[float, float]:
    """
    Moment-match StudentT(nu, mu, sigma) to Normal for conjugate update approximation.

    Mean  = mu       (defined for nu > 1; we accept nu=4 default)
    Var   = sigma^2 * nu/(nu-2)  (defined for nu > 2)

    Returns (matched_mu, matched_sigma) suitable for Normal-Normal conjugate update.
    """
    # Approximation: StudentT moments matched to Normal (Phase 12-3).
    # Replace with PyMC sampling in Phase 7 MMM.
    if nu <= 2:
        # Undefined variance — inflate sigma as conservative fallback
        return mu, sigma * 3.0
    variance = sigma ** 2 * nu / (nu - 2)
    return mu, math.sqrt(variance)


def student_t_params(nu: float, mu: float, sigma: float) -> dict:
    """StudentT(ν, μ, σ) — robust prior for niche level."""
    return {"distribution": "StudentT", "nu": nu, "mu": mu, "sigma": sigma}


def build_niche_prior(
    metric: str,
    niche_effect_size: Optional[float] = None,
    niche_ci_lower: Optional[float] = None,
    niche_ci_upper: Optional[float] = None,
    nu: float = 4.0,
) -> dict:
    """
    Build StudentT(ν=4) niche prior from universal prior + niche effect size.
    Falls back to universal if no niche data (n_tenants < 5).
    """
    universal = UNIVERSAL_PRIORS.get(metric, {"mu": 0.0, "sigma": 0.1})

    if niche_effect_size is None:
        return {
            "source": "UNIVERSAL",
            "prior":  student_t_params(nu, universal["mu"], universal["sigma"]),
        }

    niche_mu = universal["mu"] + niche_effect_size * universal["sigma"]
    if niche_ci_lower is not None and niche_ci_upper is not None:
        ci_half = (niche_ci_upper - niche_ci_lower) / 2
        niche_sigma = max(ci_half / 1.96, universal["sigma"] * 0.5)
    else:
        niche_sigma = universal["sigma"]

    return {
        "source": "NICHE",
        "prior":  student_t_params(nu, niche_mu, niche_sigma),
    }


def build_client_prior(
    metric: str,
    niche_prior: dict,
    client_observations: int,
    client_mean: Optional[float] = None,
    client_std: Optional[float] = None,
) -> dict:
    """
    Build client-level posterior by updating niche prior with client data.
    Normal-Normal conjugate update. Full PyMC in Phase 7 MMM.
    """
    if client_observations < 30 or client_mean is None:
        return {
            "source":              "NICHE_PRIOR",
            "prior":               niche_prior["prior"],
            "client_observations": client_observations,
        }

    niche_mu    = niche_prior["prior"]["mu"]
    niche_sigma = niche_prior["prior"]["sigma"]

    # Approximation: StudentT moments matched to Normal (Phase 12-3).
    # Replace with PyMC sampling in Phase 7 MMM.
    if niche_prior["prior"].get("distribution") == "StudentT":
        nu = float(niche_prior["prior"].get("nu", 4.0))
        niche_mu, niche_sigma = studentt_to_normal_moments(niche_mu, niche_sigma, nu=nu)

    obs_sigma   = client_std if client_std and client_std > 0 else niche_sigma

    precision_prior = 1.0 / (niche_sigma ** 2)
    precision_obs   = client_observations / (obs_sigma ** 2)
    precision_post  = precision_prior + precision_obs
    mu_post         = (precision_prior * niche_mu + precision_obs * client_mean) / precision_post
    sigma_post      = math.sqrt(1.0 / precision_post)

    return {
        "source": "CLIENT_POSTERIOR",
        "prior": {"distribution": "Normal", "mu": round(mu_post, 6), "sigma": round(sigma_post, 6)},
        "client_observations": client_observations,
        "niche_weight":  round(precision_prior / precision_post, 3),
        "client_weight": round(precision_obs   / precision_post, 3),
    }


def get_effective_prior(
    metric: str,
    niche_benchmark: Optional[dict] = None,
    client_data: Optional[dict] = None,
    nu: float = 4.0,
) -> dict:
    """
    Entry point. Falls through: client → niche → universal.
    """
    niche_prior = build_niche_prior(
        metric,
        niche_benchmark.get("effect_size") if niche_benchmark else None,
        niche_benchmark.get("ci_lower")    if niche_benchmark else None,
        niche_benchmark.get("ci_upper")    if niche_benchmark else None,
        nu=nu,
    )
    if client_data and client_data.get("n_observations", 0) >= 30:
        return build_client_prior(
            metric, niche_prior,
            client_data["n_observations"],
            client_data.get("mean"),
            client_data.get("std"),
        )
    return niche_prior
