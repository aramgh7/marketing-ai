"""
Bayesian GLM for creative performance prediction (vision §6.3a, §15.3, §15.4).

Three models:
  CTR: BetaBinomial(clicks | impressions, p_ctr)  — logit link, atom effects
  CVR: BetaBinomial(conversions | clicks, p_cvr)  — logit link, offer/urgency effects
  AOV: Lognormal(revenue)                          — log-normal, revenue distribution

Partial pooling: atom effects share a HalfNormal hyperprior (σ_atoms) so
low-data atoms shrink toward the group mean rather than over-fitting.

match_score: wide prior Normal(0, 2.0) — noisy LLM-computed feature; data calibrates.
Beta-Binomial by default (vision §6.3a): handles overdispersion from daily/placement variance.

LKJ correlated creative random effects (CTR↔CVR anti-correlation = clickbait detection)
are deferred to Phase 3-1b — three independent models first.

Call fit_all() from a thread executor; it is synchronous (PyMC/pytensor).
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

import numpy as np
import pandas as pd

# pymc and arviz are imported lazily inside functions that need them so that
# the module can be imported (and prepare_dataset / GlmDataset used) without
# PyMC installed — e.g. in unit tests for the data-preparation logic only.

log = logging.getLogger(__name__)

# Minimum data thresholds before fitting a stage
MIN_IMPRESSIONS = 50
MIN_CLICKS_CVR = 10
MIN_REVENUES = 5
MIN_ROWS = 2  # need at least 2 ad-set rows for any pooling


# ─── Data container ─────────────────────────────────────────────────────────

@dataclass
class GlmDataset:
    creative_ids: list[str]
    n_obs: int

    impressions: np.ndarray    # (n_obs,)  int
    clicks: np.ndarray         # (n_obs,)  int, clamped ≤ impressions
    cvr_mask: np.ndarray       # (n_obs,)  bool — rows with clicks >= MIN_CLICKS_CVR
    conversions: np.ndarray    # (n_obs,)  int, clamped ≤ clicks
    revenues: np.ndarray       # (n_revenues,) float — flat array from PURCHASE events

    match_scores: np.ndarray   # (n_obs,) float [0,1]
    has_face: np.ndarray       # (n_obs,) float {0,1}
    urgency: np.ndarray        # (n_obs,) float {0,1}

    hook_idx: np.ndarray       # (n_obs,) int
    cta_idx: np.ndarray        # (n_obs,) int
    offer_idx: np.ndarray      # (n_obs,) int

    hook_labels: list[str]
    cta_labels: list[str]
    offer_labels: list[str]


def prepare_dataset(df: pd.DataFrame) -> GlmDataset | None:
    """
    Build a GlmDataset from a raw DataFrame.

    Expected columns:
        creative_id, impressions, clicks, conversions,
        revenues (list[float] | None per row),
        match_score, has_face, has_urgency,
        hook_type, cta_type, offer_type
    """
    df = df[df["impressions"] >= MIN_IMPRESSIONS].copy().reset_index(drop=True)
    if len(df) < MIN_ROWS:
        log.warning("GLM: only %d rows after impression filter — skipping fit", len(df))
        return None

    def _encode(col: str, default: str) -> tuple[np.ndarray, list[str]]:
        series = df[col].fillna(default) if col in df.columns else pd.Series([default] * len(df))
        labels = sorted(series.unique().tolist())
        mapping = {v: i for i, v in enumerate(labels)}
        return series.map(mapping).values.astype(int), labels

    hook_idx, hook_labels = _encode("hook_type", "unknown")
    cta_idx, cta_labels   = _encode("cta_type",  "unknown")
    offer_idx, offer_labels = _encode("offer_type", "none")

    impressions = df["impressions"].values.astype(int)
    clicks      = np.minimum(df["clicks"].values.astype(int), impressions)
    conversions = np.minimum(df["conversions"].values.astype(int), clicks)

    # Flatten revenue observations from the list-per-row column
    revenues: list[float] = []
    if "revenues" in df.columns:
        for rv in df["revenues"]:
            if rv is not None:
                revenues.extend(r for r in rv if r and r > 0)

    def _flt(col: str, fill: float) -> np.ndarray:
        s = df[col].fillna(fill) if col in df.columns else pd.Series([fill] * len(df))
        return s.values.astype(float)

    return GlmDataset(
        creative_ids=df["creative_id"].tolist(),
        n_obs=len(df),
        impressions=impressions,
        clicks=clicks,
        cvr_mask=clicks >= MIN_CLICKS_CVR,
        conversions=conversions,
        revenues=np.array(revenues, dtype=float),
        match_scores=_flt("match_score", 0.5),
        has_face=_flt("has_face", 0.0),
        urgency=_flt("has_urgency", 0.0),
        hook_idx=hook_idx,
        cta_idx=cta_idx,
        offer_idx=offer_idx,
        hook_labels=hook_labels,
        cta_labels=cta_labels,
        offer_labels=offer_labels,
    )


# ─── Model builders ──────────────────────────────────────────────────────────

def _ctr_model(d: GlmDataset):
    """
    logit(CTR_i) = mu_ctr + β_hook[h_i] + β_cta[c_i] + β_face * face_i + β_match_ctr * ms_i
    clicks_i ~ BetaBinomial(impressions_i, p_ctr_i * κ_ctr, (1-p_ctr_i) * κ_ctr)
    """
    import pymc as pm
    with pm.Model() as model:
        σ       = pm.HalfNormal("σ_atoms", 0.5)
        mu      = pm.Normal("mu_ctr", 0.0, 1.0)
        β_hook  = pm.Normal("β_hook",      0.0, σ,   shape=len(d.hook_labels))
        β_cta   = pm.Normal("β_cta",       0.0, σ,   shape=len(d.cta_labels))
        β_face  = pm.Normal("β_face",      0.0, 0.5)
        β_match = pm.Normal("β_match_ctr", 0.0, 2.0)  # wide — noisy LLM feature

        logit_p = (mu
                   + β_hook[d.hook_idx]
                   + β_cta[d.cta_idx]
                   + β_face  * d.has_face
                   + β_match * d.match_scores)

        κ = pm.Exponential("κ_ctr", 0.5)
        p = pm.Deterministic("p_ctr", pm.math.sigmoid(logit_p))
        pm.BetaBinomial("obs_ctr",
                        alpha=p * κ, beta=(1.0 - p) * κ,
                        n=d.impressions, observed=d.clicks)
    return model


def _cvr_model(d: GlmDataset):
    """
    logit(CVR_i) = mu_cvr + β_offer[o_i] + β_urgency * urg_i + β_match_cvr * ms_i
    conversions_i ~ BetaBinomial(clicks_i, q_cvr_i * κ_cvr, (1-q_cvr_i) * κ_cvr)
    Only rows with clicks >= MIN_CLICKS_CVR are used.
    """
    import pymc as pm
    mask = d.cvr_mask
    if int(mask.sum()) < MIN_ROWS:
        log.warning("GLM CVR: only %d rows with >= %d clicks — skipping",
                    int(mask.sum()), MIN_CLICKS_CVR)
        return None

    with pm.Model() as model:
        σ         = pm.HalfNormal("σ_atoms", 0.5)
        mu        = pm.Normal("mu_cvr",       -1.0, 1.0)  # CVR typically < 50%
        β_offer   = pm.Normal("β_offer",       0.0, σ,   shape=len(d.offer_labels))
        β_urgency = pm.Normal("β_urgency",     0.0, 0.5)
        β_match   = pm.Normal("β_match_cvr",   0.0, 2.0)

        logit_q = (mu
                   + β_offer[d.offer_idx[mask]]
                   + β_urgency * d.urgency[mask]
                   + β_match   * d.match_scores[mask])

        κ = pm.Exponential("κ_cvr", 0.5)
        q = pm.Deterministic("q_cvr", pm.math.sigmoid(logit_q))
        pm.BetaBinomial("obs_cvr",
                        alpha=q * κ, beta=(1.0 - q) * κ,
                        n=d.clicks[mask], observed=d.conversions[mask])
    return model


def _aov_model(revenues: np.ndarray):
    """Lognormal AOV. Prior centred at observed log-mean."""
    import pymc as pm
    if len(revenues) < MIN_REVENUES:
        log.warning("GLM AOV: only %d revenue observations — skipping", len(revenues))
        return None

    log_mean = float(np.log(np.mean(revenues)))
    with pm.Model() as model:
        μ = pm.Normal("mu_aov", log_mean, 1.0)
        σ = pm.HalfNormal("σ_aov", 0.5)
        pm.Lognormal("obs_aov", mu=μ, sigma=σ, observed=revenues)
    return model


# ─── Sampling + posterior extraction ─────────────────────────────────────────

def _sample(model, draws: int, tune: int, chains: int):
    import pymc as pm
    with model:
        return pm.sample(
            draws=draws,
            tune=tune,
            chains=chains,
            target_accept=0.9,
            progressbar=False,
            return_inferencedata=True,
        )


def _rhat_max(idata) -> float:
    import arviz as az
    return float(np.nanmax(az.rhat(idata).to_array().values))


def _extract_vector(idata, stage: str, param: str, labels: list[str]) -> list[dict]:
    """Extract posterior summary for a 1-D parameter (one entry per label)."""
    import arviz as az
    post = idata.posterior
    if param not in post:
        return []
    arr = post[param].values  # (chains, draws, n_labels)
    flat = arr.reshape(-1, len(labels))
    results = []
    for i, label in enumerate(labels):
        samples = flat[:, i]
        hdi = az.hdi(samples, hdi_prob=0.94)
        results.append({
            "stage": stage, "param": param,
            "atom_type": param, "atom_value": label,
            "mean": float(np.mean(samples)),
            "std":  float(np.std(samples)),
            "hdi_low":  float(hdi[0]),
            "hdi_high": float(hdi[1]),
        })
    return results


def _extract_scalar(idata, stage: str, param: str) -> dict | None:
    import arviz as az
    post = idata.posterior
    if param not in post:
        return None
    samples = post[param].values.flatten()
    hdi = az.hdi(samples, hdi_prob=0.94)
    return {
        "stage": stage, "param": param,
        "atom_type": param, "atom_value": "__scalar__",
        "mean": float(np.mean(samples)),
        "std":  float(np.std(samples)),
        "hdi_low":  float(hdi[0]),
        "hdi_high": float(hdi[1]),
    }


# ─── Public API ──────────────────────────────────────────────────────────────

@dataclass
class FitResult:
    posteriors: list[dict]
    r_hat_max: float
    n_obs_ctr: int
    n_obs_cvr: int
    n_obs_aov: int
    error: str | None = None


def fit_all(
    dataset: GlmDataset,
    draws: int = 1000,
    tune: int = 1000,
    chains: int = 2,
) -> FitResult:
    """
    Fit CTR, CVR, and AOV models sequentially.
    Returns posterior summaries for all atom effects.

    Synchronous — call from asyncio.get_event_loop().run_in_executor().
    """
    posteriors: list[dict] = []
    r_hats: list[float] = []
    n_obs_cvr = int(dataset.cvr_mask.sum())
    n_obs_aov = len(dataset.revenues)

    # CTR ──────────────────────────────────────────────────────────────────
    try:
        idata_ctr = _sample(_ctr_model(dataset), draws, tune, chains)
        rh = _rhat_max(idata_ctr)
        r_hats.append(rh)
        if rh > 1.1:
            log.warning("CTR model: R-hat=%.3f (>1.1) — chains may not have converged", rh)

        posteriors += _extract_vector(idata_ctr, "CTR", "β_hook", dataset.hook_labels)
        posteriors += _extract_vector(idata_ctr, "CTR", "β_cta",  dataset.cta_labels)
        for p in ("β_face", "β_match_ctr", "mu_ctr"):
            s = _extract_scalar(idata_ctr, "CTR", p)
            if s:
                posteriors.append(s)
    except Exception:
        log.exception("CTR model fit failed")

    # CVR ──────────────────────────────────────────────────────────────────
    cvr_m = _cvr_model(dataset)
    if cvr_m is not None:
        try:
            idata_cvr = _sample(cvr_m, draws, tune, chains)
            rh = _rhat_max(idata_cvr)
            r_hats.append(rh)
            if rh > 1.1:
                log.warning("CVR model: R-hat=%.3f", rh)

            posteriors += _extract_vector(idata_cvr, "CVR", "β_offer", dataset.offer_labels)
            for p in ("β_urgency", "β_match_cvr", "mu_cvr"):
                s = _extract_scalar(idata_cvr, "CVR", p)
                if s:
                    posteriors.append(s)
        except Exception:
            log.exception("CVR model fit failed")
            n_obs_cvr = 0

    # AOV ──────────────────────────────────────────────────────────────────
    aov_m = _aov_model(dataset.revenues)
    if aov_m is not None:
        try:
            idata_aov = _sample(aov_m, draws, tune, chains)
            rh = _rhat_max(idata_aov)
            r_hats.append(rh)

            for p in ("mu_aov", "σ_aov"):
                s = _extract_scalar(idata_aov, "AOV", p)
                if s:
                    posteriors.append(s)
        except Exception:
            log.exception("AOV model fit failed")
            n_obs_aov = 0

    return FitResult(
        posteriors=posteriors,
        r_hat_max=max(r_hats) if r_hats else 0.0,
        n_obs_ctr=dataset.n_obs,
        n_obs_cvr=n_obs_cvr,
        n_obs_aov=n_obs_aov,
    )
