"""What-If Simulator (vision §21.4).

Three scenarios, all read-only from existing Thompson posteriors:

  budget      — "What if monthly budget changes by X%?"
                Monte Carlo CVR from posteriors × new budget → predicted
                sessions, conversions, CPA corridor.

  power       — "How many more sessions until we can call a winner?"
                Simulates future samples from posteriors until P(best) ≥ target.

  arm_disable — "What if we pause variant(s) X?"
                Re-ranks remaining arms, shows new leading arm + P(best).

Cost: low — same Beta(α,β) sampling as /decide/landing, new input params only.
"""
import random
from typing import Optional

import numpy as np
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field

from app.config import settings
from app.services.thompson_service import load_arms

router = APIRouter(tags=["What-If Simulator"])


def _require_internal_key(x_internal_api_key: str) -> None:
    if x_internal_api_key != settings.internal_api_key:
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-API-Key")


# ─── Request / Response models ────────────────────────────────────────────────

class BudgetParams(BaseModel):
    monthly_budget: float = Field(..., gt=0, description="Current monthly ad spend ($)")
    budget_delta_pct: float = Field(..., description="Budget change in % (e.g. 50 = +50%)")
    avg_cpc: float = Field(..., gt=0, description="Average cost-per-click ($)")
    cpa_target: Optional[float] = Field(None, gt=0, description="Target CPA ($) for ROAS calc")
    avg_deal_value: Optional[float] = Field(None, gt=0, description="Avg deal value ($) for ROAS")


class PowerParams(BaseModel):
    target_prob_best: float = Field(0.95, ge=0.5, le=0.999)
    daily_sessions: float = Field(..., gt=0, description="Current daily session volume")
    max_simulated_sessions: int = Field(50_000, gt=0, le=500_000)


class ArmDisableParams(BaseModel):
    exclude_variant_ids: list[str] = Field(..., min_length=1)


class SimulateRequest(BaseModel):
    tenant_id: str
    context_key: str = "global"
    scenario: str = Field(..., pattern="^(budget|power|arm_disable)$")
    budget: Optional[BudgetParams] = None
    power: Optional[PowerParams] = None
    arm_disable: Optional[ArmDisableParams] = None
    n_simulations: int = Field(5_000, ge=100, le=50_000)


class Interval(BaseModel):
    p10: float
    p50: float
    p90: float


class BudgetResult(BaseModel):
    scenario: str = "budget"
    new_budget: float
    predicted_sessions: Interval
    predicted_conversions: Interval
    predicted_cvr: Interval
    predicted_cpa: Optional[Interval]
    predicted_roas: Optional[Interval]
    best_arm_id: str
    best_arm_cvr_estimate: float


class PowerResult(BaseModel):
    scenario: str = "power"
    leading_arm_id: str
    current_prob_best: float
    target_prob_best: float
    sessions_needed: Optional[int]
    days_to_target: Optional[float]
    already_sufficient: bool


class ArmDisableResult(BaseModel):
    scenario: str = "arm_disable"
    excluded_variant_ids: list[str]
    remaining_arms: int
    new_leading_arm_id: Optional[str]
    new_prob_best: Optional[float]
    old_leading_arm_id: Optional[str]
    old_prob_best: Optional[float]
    cvr_delta_pct: Optional[float]


# ─── Helpers ──────────────────────────────────────────────────────────────────

def _percentiles(samples: np.ndarray) -> Interval:
    return Interval(
        p10=round(float(np.percentile(samples, 10)), 4),
        p50=round(float(np.percentile(samples, 50)), 4),
        p90=round(float(np.percentile(samples, 90)), 4),
    )


def _prob_best(arms: list[dict], n: int) -> tuple[str, float]:
    """Returns (best_arm_id, P(best)) via Monte Carlo."""
    counts = {arm["id"]: 0 for arm in arms}
    for _ in range(n):
        best_val, best_id = -1.0, None
        for arm in arms:
            v = float(np.random.beta(arm["alpha"], arm["beta"]))
            if v > best_val:
                best_val, best_id = v, arm["id"]
        if best_id:
            counts[best_id] += 1
    best_id = max(counts, key=counts.__getitem__)
    return best_id, round(counts[best_id] / n, 4)


# ─── Scenario implementations ─────────────────────────────────────────────────

def _simulate_budget(arms: list[dict], p: BudgetParams, n: int) -> BudgetResult:
    """Monte Carlo: sample CVR from posteriors × new budget → session/conversion/CPA distribution."""
    new_budget = p.monthly_budget * (1 + p.budget_delta_pct / 100)
    new_sessions_base = new_budget / p.avg_cpc

    # Best arm by current mean CVR
    best_arm = max(arms, key=lambda a: a["alpha"] / (a["alpha"] + a["beta"]))

    # Sample CVR from Beta posterior of best arm
    cvr_samples = np.random.beta(best_arm["alpha"], best_arm["beta"], n)

    # Sessions uncertainty: Poisson around expected value (traffic is noisy)
    session_samples = np.random.poisson(new_sessions_base, n).astype(float)

    conversion_samples = session_samples * cvr_samples

    cpa_result = None
    if p.avg_cpc and new_sessions_base > 0:
        # CPA = budget / conversions (guard div-by-zero with floor at 1)
        cpa_samples = np.where(
            conversion_samples > 0,
            new_budget / conversion_samples,
            np.inf,
        )
        finite_cpa = cpa_samples[np.isfinite(cpa_samples)]
        if len(finite_cpa) > 0:
            cpa_result = _percentiles(np.clip(finite_cpa, 0, new_budget * 10))

    roas_result = None
    if p.avg_deal_value and p.cpa_target:
        roas_samples = np.where(
            conversion_samples > 0,
            (conversion_samples * p.avg_deal_value) / new_budget,
            0.0,
        )
        roas_result = _percentiles(roas_samples)

    best_cvr = float(best_arm["alpha"] / (best_arm["alpha"] + best_arm["beta"]))
    return BudgetResult(
        new_budget=round(new_budget, 2),
        predicted_sessions=_percentiles(session_samples),
        predicted_conversions=_percentiles(conversion_samples),
        predicted_cvr=_percentiles(cvr_samples),
        predicted_cpa=cpa_result,
        predicted_roas=roas_result,
        best_arm_id=best_arm["id"],
        best_arm_cvr_estimate=round(best_cvr, 4),
    )


def _simulate_power(arms: list[dict], p: PowerParams, n_mc: int) -> PowerResult:
    """Simulate future observations until P(best) ≥ target_prob_best.

    Adds synthetic Bernoulli observations to posteriors arm-by-arm (Thompson-style),
    checking P(best) every 100 sessions.
    """
    old_best_id, current_prob = _prob_best(arms, n_mc)

    if current_prob >= p.target_prob_best:
        return PowerResult(
            leading_arm_id=old_best_id,
            current_prob_best=current_prob,
            target_prob_best=p.target_prob_best,
            sessions_needed=0,
            days_to_target=0.0,
            already_sufficient=True,
        )

    # Clone posteriors so simulation doesn't mutate real data
    sim_arms = [{**arm} for arm in arms]
    check_interval = 100
    total_added = 0
    prob = current_prob

    while total_added < p.max_simulated_sessions:
        # Add one check_interval worth of Thompson-sampled observations
        for _ in range(check_interval):
            # Pick arm via Thompson (simulate future allocation)
            best_val, chosen = -1.0, None
            for arm in sim_arms:
                v = float(np.random.beta(arm["alpha"], arm["beta"]))
                if v > best_val:
                    best_val, chosen = v, arm
            if chosen is None:
                break
            # Sample a synthetic Bernoulli outcome from that arm's true CVR
            cvr = float(np.random.beta(chosen["alpha"], chosen["beta"]))
            if random.random() < cvr:
                chosen["alpha"] += 1.0
            else:
                chosen["beta"] += 1.0
        total_added += check_interval

        _, prob = _prob_best(sim_arms, n_mc)
        if prob >= p.target_prob_best:
            break

    days = round(total_added / p.daily_sessions, 1) if p.daily_sessions > 0 else None

    return PowerResult(
        leading_arm_id=old_best_id,
        current_prob_best=current_prob,
        target_prob_best=p.target_prob_best,
        sessions_needed=total_added if prob >= p.target_prob_best else None,
        days_to_target=days if prob >= p.target_prob_best else None,
        already_sufficient=False,
    )


def _simulate_arm_disable(arms: list[dict], p: ArmDisableParams, n_mc: int) -> ArmDisableResult:
    """Re-rank arms after excluding specified variants."""
    remaining = [a for a in arms if a["variant_id"] not in p.exclude_variant_ids]

    old_best_id, old_prob = _prob_best(arms, n_mc)

    if not remaining:
        return ArmDisableResult(
            excluded_variant_ids=p.exclude_variant_ids,
            remaining_arms=0,
            new_leading_arm_id=None,
            new_prob_best=None,
            old_leading_arm_id=old_best_id,
            old_prob_best=old_prob,
            cvr_delta_pct=None,
        )

    new_best_id, new_prob = _prob_best(remaining, n_mc)

    old_best = next((a for a in arms     if a["id"] == old_best_id), None)
    new_best = next((a for a in remaining if a["id"] == new_best_id), None)

    cvr_delta = None
    if old_best and new_best:
        old_cvr = old_best["alpha"] / (old_best["alpha"] + old_best["beta"])
        new_cvr = new_best["alpha"] / (new_best["alpha"] + new_best["beta"])
        cvr_delta = round((new_cvr - old_cvr) / old_cvr * 100, 2) if old_cvr else None

    return ArmDisableResult(
        excluded_variant_ids=p.exclude_variant_ids,
        remaining_arms=len(remaining),
        new_leading_arm_id=new_best_id,
        new_prob_best=new_prob,
        old_leading_arm_id=old_best_id,
        old_prob_best=old_prob,
        cvr_delta_pct=cvr_delta,
    )


# ─── Endpoint ─────────────────────────────────────────────────────────────────

@router.post("/")
async def simulate(
    req: SimulateRequest,
    x_internal_api_key: str = Header(default=""),
):
    """What-If Simulator: scenario analysis from Thompson posteriors (vision §21.4).

    Scenarios: budget | power | arm_disable.
    Protected by X-Internal-API-Key.
    """
    _require_internal_key(x_internal_api_key)

    arms = await load_arms(req.tenant_id, req.context_key)
    if not arms:
        raise HTTPException(status_code=404, detail="No active arms for this tenant/context")

    if req.scenario == "budget":
        if not req.budget:
            raise HTTPException(status_code=422, detail="'budget' params required for budget scenario")
        return _simulate_budget(arms, req.budget, req.n_simulations)

    if req.scenario == "power":
        if not req.power:
            raise HTTPException(status_code=422, detail="'power' params required for power scenario")
        return _simulate_power(arms, req.power, req.n_simulations)

    if req.scenario == "arm_disable":
        if not req.arm_disable:
            raise HTTPException(status_code=422, detail="'arm_disable' params required")
        return _simulate_arm_disable(arms, req.arm_disable, req.n_simulations)

    raise HTTPException(status_code=422, detail=f"Unknown scenario: {req.scenario}")
