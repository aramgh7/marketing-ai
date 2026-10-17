"""
Bayesian GLM endpoints (internal service — tenant_id in request body).

POST /glm/fit                — trigger a fit run for a tenant
GET  /glm/posteriors         — read current posterior summaries
GET  /glm/fit-runs           — list recent fit runs
POST /glm/predict            — compute expected CTR/CVR/AOV for a creative spec
"""
from __future__ import annotations

import logging
from typing import Optional

import numpy as np
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from app.services.db_client import get_pool
from app.services.model_updater import run_fit
from app.services.glm_interactions_service import rank_interactions_by_bf

log = logging.getLogger(__name__)
router = APIRouter(tags=["Bayesian GLM"])


# ─── Schemas ────────────────────────────────────────────────────────────────

class FitRequest(BaseModel):
    tenant_id: str
    draws: int = 1000
    tune: int = 1000
    chains: int = 2


class PosteriorEntry(BaseModel):
    model_stage: str
    atom_type: str
    atom_value: str
    mean: float
    std: float
    hdi_low: Optional[float]
    hdi_high: Optional[float]
    n_obs: int


class FitRunEntry(BaseModel):
    id: str
    status: str
    n_obs_ctr: Optional[int]
    n_obs_cvr: Optional[int]
    n_obs_aov: Optional[int]
    r_hat_max: Optional[float]
    error_msg: Optional[str]
    started_at: str
    finished_at: Optional[str]


class PredictRequest(BaseModel):
    tenant_id: str
    hook_type: Optional[str] = None
    cta_type: Optional[str] = None
    has_face: bool = False
    match_score: float = 0.5
    offer_type: Optional[str] = None
    has_urgency: bool = False
    n_samples: int = 2000


class PredictResponse(BaseModel):
    ctr_mean: float
    ctr_std: float
    cvr_mean: float
    cvr_std: float
    aov_mean: float
    aov_std: float
    revenue_per_impression_mean: float
    revenue_per_impression_std: float
    revenue_per_impression_p05: float
    revenue_per_impression_p95: float


# ─── Helper ──────────────────────────────────────────────────────────────────

async def _load_posteriors(tenant_id: str, stage: str | None = None) -> list[dict]:
    pool = await get_pool()
    if stage:
        rows = await pool.fetch(
            """
            SELECT model_stage, atom_type, atom_value, mean, std, hdi_low, hdi_high, n_obs
            FROM   glm_posteriors
            WHERE  tenant_id = $1::uuid AND model_stage = $2
            ORDER  BY model_stage, atom_type, atom_value
            """,
            tenant_id, stage,
        )
    else:
        rows = await pool.fetch(
            """
            SELECT model_stage, atom_type, atom_value, mean, std, hdi_low, hdi_high, n_obs
            FROM   glm_posteriors
            WHERE  tenant_id = $1::uuid
            ORDER  BY model_stage, atom_type, atom_value
            """,
            tenant_id,
        )
    return [dict(r) for r in rows]


def _scalar(posteriors: list[dict], stage: str, param: str) -> dict | None:
    for p in posteriors:
        if p["model_stage"] == stage and p["atom_type"] == param and p["atom_value"] == "__scalar__":
            return p
    return None


def _label_entry(posteriors: list[dict], stage: str, atom_type: str, label: str | None) -> dict | None:
    if label is None:
        return None
    for p in posteriors:
        if p["model_stage"] == stage and p["atom_type"] == atom_type and p["atom_value"] == label:
            return p
    return None


# ─── Endpoints ───────────────────────────────────────────────────────────────

@router.post("/fit")
async def trigger_fit(req: FitRequest) -> dict:
    """
    Trigger a Bayesian GLM fit run for the given tenant.
    Blocks until the fit completes (PyMC runs in a thread executor).
    For large datasets this may take several minutes.
    """
    return await run_fit(req.tenant_id, draws=req.draws, tune=req.tune, chains=req.chains)


@router.get("/posteriors", response_model=list[PosteriorEntry])
async def get_posteriors(
    tenant_id: str,
    stage: Optional[str] = Query(None, description="Filter by CTR, CVR, or AOV"),
) -> list[dict]:
    """Return all current posterior summaries for this tenant."""
    return await _load_posteriors(tenant_id, stage)


@router.get("/fit-runs", response_model=list[FitRunEntry])
async def list_fit_runs(
    tenant_id: str,
    limit: int = Query(10, ge=1, le=100),
) -> list[dict]:
    pool = await get_pool()
    rows = await pool.fetch(
        """
        SELECT id::text, status, n_obs_ctr, n_obs_cvr, n_obs_aov,
               r_hat_max, error_msg,
               started_at::text, finished_at::text
        FROM   glm_fit_runs
        WHERE  tenant_id = $1::uuid
        ORDER  BY started_at DESC
        LIMIT  $2
        """,
        tenant_id, limit,
    )
    return [dict(r) for r in rows]


@router.post("/predict", response_model=PredictResponse)
async def predict(req: PredictRequest) -> PredictResponse:
    """
    Predict expected revenue per impression for a creative spec.

    Samples from Normal approximations to posterior distributions,
    propagates uncertainty through the hurdle model:
        E[Revenue / impression] = CTR × CVR × AOV
    """
    posteriors = await _load_posteriors(req.tenant_id)
    if not posteriors:
        raise HTTPException(
            status_code=404,
            detail="No GLM posteriors found for this tenant. Run POST /glm/fit first.",
        )

    rng = np.random.default_rng()
    n = req.n_samples

    def _samp(entry: dict | None) -> np.ndarray:
        if entry is None:
            return np.zeros(n)
        return rng.normal(entry["mean"], max(entry["std"], 1e-6), size=n)

    # ── CTR samples ──────────────────────────────────────────────────
    logit_ctr = (
        _samp(_scalar(posteriors, "CTR", "mu_ctr"))
        + _samp(_label_entry(posteriors, "CTR", "β_hook", req.hook_type))
        + _samp(_label_entry(posteriors, "CTR", "β_cta",  req.cta_type))
        + _samp(_scalar(posteriors, "CTR", "β_face"))       * float(req.has_face)
        + _samp(_scalar(posteriors, "CTR", "β_match_ctr"))  * req.match_score
    )
    ctr_s = 1.0 / (1.0 + np.exp(-logit_ctr))

    # ── CVR samples ──────────────────────────────────────────────────
    logit_cvr = (
        _samp(_scalar(posteriors, "CVR", "mu_cvr"))
        + _samp(_label_entry(posteriors, "CVR", "β_offer", req.offer_type))
        + _samp(_scalar(posteriors, "CVR", "β_urgency"))    * float(req.has_urgency)
        + _samp(_scalar(posteriors, "CVR", "β_match_cvr"))  * req.match_score
    )
    cvr_s = 1.0 / (1.0 + np.exp(-logit_cvr))

    # ── AOV samples ───────────────────────────────────────────────────
    mu_entry  = _scalar(posteriors, "AOV", "mu_aov")
    sig_entry = _scalar(posteriors, "AOV", "σ_aov")
    if mu_entry and sig_entry:
        mu_s  = rng.normal(mu_entry["mean"],  max(mu_entry["std"],  1e-6), size=n)
        sig_s = np.abs(rng.normal(sig_entry["mean"], max(sig_entry["std"], 1e-6), size=n))
        aov_s = np.exp(mu_s + rng.normal(0.0, sig_s, size=n))
    else:
        aov_s = np.full(n, 50.0)  # fallback when AOV model hasn't been fit yet

    # ── Revenue per impression ────────────────────────────────────────
    rev_s = ctr_s * cvr_s * aov_s

    return PredictResponse(
        ctr_mean=float(np.mean(ctr_s)),
        ctr_std=float(np.std(ctr_s)),
        cvr_mean=float(np.mean(cvr_s)),
        cvr_std=float(np.std(cvr_s)),
        aov_mean=float(np.mean(aov_s)),
        aov_std=float(np.std(aov_s)),
        revenue_per_impression_mean=float(np.mean(rev_s)),
        revenue_per_impression_std=float(np.std(rev_s)),
        revenue_per_impression_p05=float(np.percentile(rev_s, 5)),
        revenue_per_impression_p95=float(np.percentile(rev_s, 95)),
    )


class InteractionCandidate(BaseModel):
    term: str
    r2_full: float = Field(..., ge=0.0, le=1.0)
    r2_restricted: float = Field(..., ge=0.0, le=1.0)
    n: int = Field(..., ge=3)


class InteractionsRequest(BaseModel):
    candidates: list[InteractionCandidate]


@router.post("/interactions")
async def rank_interactions(req: InteractionsRequest) -> dict:
    """
    Rank GLM interaction terms by Bayes factor (BIC approximation).
    BF > 3 → INCLUDE; BF < 1/3 → EXCLUDE. vision §15.8 task 1.
    """
    result = rank_interactions_by_bf([c.model_dump() for c in req.candidates])
    return {"ranked_interactions": result}
