import random
from typing import Optional

import numpy as np
from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel

from app.config import settings
from app.services.db_client import get_pool
from app.services.cluster_service import personalise_block_order
from app.services.decide_service import derive_context_key
from app.services.cluster_refinement_service import build_refinement_plan, apply_refinement
from app.services.thompson_service import (
    apply_churn_penalty,
    apply_reward,
    get_benchmark,
    load_arms,
    log_decision,
    sample_arms,
)
from app.services.followup_service import (
    classify_context,
    thompson_select_variant,
    should_auto_pause,
    compute_show_rate_posterior,
)

router = APIRouter(tags=["Thompson Sampling"])


def _holdout_bucket(session_id: Optional[str]) -> int:
    """Deterministic 0-9999 bucket from session_id so the same session stays in holdout."""
    if not session_id:
        return random.randint(0, 9999)
    return int(session_id.replace("-", "")[:8], 16) % 10_000


async def _get_holdout_config(tenant_id: str) -> tuple[bool, float]:
    """Return (holdout_enabled, holdout_rate) for a tenant."""
    pool = await get_pool()
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT holdout_enabled, holdout_rate FROM tenants WHERE id = $1::uuid",
            tenant_id,
        )
    if not row:
        return True, 0.04
    return bool(row["holdout_enabled"]), float(row["holdout_rate"])


class DecideRequest(BaseModel):
    tenant_id: str
    # arch v1.1 §5.2 + vision v1.9.9 §12.1a: session_id is optional —
    # first visit before consent has no persistent identifier yet.
    session_id: Optional[str] = None
    creative_id: Optional[str] = None
    context_key: str = "global"
    context: dict = {}
    # Phase 5-3: contextual bandit — when cluster_id is provided, route to
    # cluster-specific Thompson arms (context_key = "cluster_{cluster_id}")
    cluster_id: Optional[int] = None
    # Phase 7: channel branch — Thompson arms are partitioned per channel
    # so META and GOOGLE posteriors don't interfere with each other.
    channel: str = "META"


class PersonaliseRequest(BaseModel):
    tenant_id: str
    session_id: Optional[str] = None
    session: dict = {}
    events: list[dict] = []
    default_block_order: list[str] = []
    cluster_label: str = "researcher"       # from /cluster/assign
    allowed_block_ids: Optional[list[str]] = None
    # vision §5.4.1: marketer-approved block order from personalization_rules DB table.
    # Passed by Spring Boot after loading from DB. Empty = use CLUSTER_BLOCK_RULES fallback.
    approved_block_order: list[str] = []


class PersonaliseResponse(BaseModel):
    block_order: list[str]
    cluster_label: str


class DecideResponse(BaseModel):
    arm_id: str
    variant_id: str
    block_order: list[str]  # arch v1.1 §5.2: block display order for this variant
    alpha: float
    beta: float
    decision_id: str


class RewardRequest(BaseModel):
    decision_id: str
    reward_type: str


class RewardResponse(BaseModel):
    arm_id: Optional[str] = None
    field_updated: Optional[str] = None
    delta: float = 0.0
    skipped: bool = False
    reason: Optional[str] = None


_derive_context_key = derive_context_key  # local alias for readability


@router.post("/landing", response_model=DecideResponse)
async def decide_landing(req: DecideRequest):
    """Thompson Sampling: pick the best landing variant for this session.

    If tenant has holdout enabled and this session falls in the holdout bucket,
    a random arm is chosen instead (vision §21.5 micro-holdout).

    Phase 5-3: If cluster_id is provided, routes to cluster-specific arms.
    Falls back to global arms if no cluster-specific arms exist.
    """
    # Phase 7: prefix context_key with channel so each channel has its own posteriors.
    # "global" becomes "channel:META"; cluster keys become "channel:META:cluster_2".
    channel = req.channel.upper() if req.channel else "META"
    base_key = _derive_context_key(req.context_key, req.cluster_id)
    effective_key = f"channel:{channel}:{base_key}" if base_key != "global" else f"channel:{channel}"

    arms = await load_arms(req.tenant_id, effective_key)
    if not arms:
        # Fallback 1: channel-global (drop cluster suffix)
        effective_key = f"channel:{channel}"
        arms = await load_arms(req.tenant_id, effective_key)
    if not arms:
        # Fallback 2: legacy global arms (pre-Phase 7)
        effective_key = _derive_context_key(req.context_key, req.cluster_id)
        arms = await load_arms(req.tenant_id, effective_key)
    if not arms and effective_key != "global":
        # Graceful fallback to global arms when cluster arms not yet trained
        effective_key = "global"
        arms = await load_arms(req.tenant_id, effective_key)
    if not arms:
        raise HTTPException(status_code=404, detail="No active variants for this tenant")

    holdout_enabled, holdout_rate = await _get_holdout_config(req.tenant_id)
    bucket     = _holdout_bucket(req.session_id)
    is_holdout = holdout_enabled and bucket < int(holdout_rate * 10_000)

    if is_holdout:
        # Holdout: uniform random arm — no Thompson exploitation
        chosen_arm = random.choice(arms)
        chosen = {**chosen_arm, "sample_value": 0.0}
        candidates = [
            {
                "variant_id":   arm["variant_id"],
                "arm_id":       arm["id"],
                "propensity":   round(1.0 / len(arms), 4),
                "sample_value": 0.0,
                "alpha":        arm["alpha"],
                "beta":         arm["beta"],
            }
            for arm in arms
        ]
    else:
        chosen, candidates = sample_arms(arms)

    context_with_key = {**req.context, "context_key": effective_key}
    decision_id = await log_decision(
        req.tenant_id,
        req.session_id,
        req.creative_id,
        chosen,
        candidates,
        context_with_key,
        is_holdout=is_holdout,
    )
    return DecideResponse(
        arm_id=chosen["id"],
        variant_id=chosen["variant_id"],
        block_order=chosen.get("block_order", []),
        alpha=chosen["alpha"],
        beta=chosen["beta"],
        decision_id=decision_id,
    )


@router.post("/reward", response_model=RewardResponse)
async def submit_reward(req: RewardRequest):
    """Submit a reward signal — updates the chosen arm's α or β posterior."""
    try:
        result = await apply_reward(req.decision_id, req.reward_type)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return RewardResponse(
        arm_id=result["arm_id"],
        field_updated=result["field_updated"],
        delta=result["delta"],
    )


@router.get("/benchmark/{tenant_id}")
async def benchmark(
    tenant_id: str,
    days: int = Query(default=30, ge=7, le=365),
    x_internal_api_key: str = Header(default=""),
):
    """System vs Baseline Benchmark: incremental lift of Thompson vs holdout (vision §21.5).

    Protected by X-Internal-API-Key.
    """
    _require_internal_key(x_internal_api_key)
    return await get_benchmark(tenant_id, days)


class ChurnPenaltyRequest(BaseModel):
    tenant_id: str
    arm_id: str
    context_key: str = "global"
    cohort_size: int
    churn_count: int
    weighted_sum: float  # sum(exp(-0.02 * days_since_decision)) over churned sessions


class ChurnPenaltyResponse(BaseModel):
    arm_id: str
    beta_delta: float
    capped: bool
    new_beta: float


def _require_internal_key(x_internal_api_key: str = Header(default="")) -> None:
    if x_internal_api_key != settings.internal_api_key:
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-API-Key")


@router.post("/churn-penalty", response_model=ChurnPenaltyResponse)
async def churn_penalty(
    body: ChurnPenaltyRequest,
    x_internal_api_key: str = Header(default=""),
):
    """Retroactive β penalty for a Thompson arm based on post-conversion churn (vision §6.7).

    Called by the Spring Boot ChurnPenaltyScheduler. Protected by X-Internal-API-Key.
    """
    _require_internal_key(x_internal_api_key)
    try:
        result = await apply_churn_penalty(
            tenant_id=body.tenant_id,
            arm_id=body.arm_id,
            context_key=body.context_key,
            weighted_sum=body.weighted_sum,
            cohort_size=body.cohort_size,
            churn_count=body.churn_count,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return ChurnPenaltyResponse(**result)


@router.get("/power/{tenant_id}")
async def power_calculator(
    tenant_id: str,
    context_key: str = "global",
    min_samples: int = 100,
    n_simulations: int = 10_000,
):
    """
    Monte Carlo P(best) for each arm.
    Returns arms sorted by probability of being the best, plus a 'ready' flag
    when total_samples >= min_samples.
    """
    arms = await load_arms(tenant_id, context_key)
    if not arms:
        raise HTTPException(status_code=404, detail="No active arms for this tenant")

    counts = {arm["id"]: 0 for arm in arms}
    for _ in range(n_simulations):
        best_val = -1.0
        best_id = None
        for arm in arms:
            val = float(np.random.beta(arm["alpha"], arm["beta"]))
            if val > best_val:
                best_val = val
                best_id = arm["id"]
        if best_id:
            counts[best_id] += 1

    results = [
        {
            "arm_id": arm["id"],
            "variant_id": arm["variant_id"],
            "alpha": arm["alpha"],
            "beta": arm["beta"],
            "total_samples": arm.get("total_samples", 0),
            "estimated_cvr": round(arm["alpha"] / (arm["alpha"] + arm["beta"]), 4),
            "prob_best": round(counts[arm["id"]] / n_simulations, 4),
            "ready": arm.get("total_samples", 0) >= min_samples,
        }
        for arm in arms
    ]
    results.sort(key=lambda x: x["prob_best"], reverse=True)
    return {"tenant_id": tenant_id, "context_key": context_key, "arms": results}


class RefineClusterRequest(BaseModel):
    cluster_sizes: dict[str, int]
    centroids: dict[str, list[float]]
    assignments: list[dict] = []


class RefineClusterResponse(BaseModel):
    plan: list[dict]
    refined_assignments: list[dict]
    merged_count: int
    suppressed_count: int


@router.post("/refine-cluster", response_model=RefineClusterResponse)
def refine_cluster(req: RefineClusterRequest) -> RefineClusterResponse:
    """
    Block F §2: Merge thin clusters (< MIN_CLUSTER_SIZE) into nearest neighbours.
    Returns the merge plan and optionally the re-labelled session assignments.
    Sessions in suppressed clusters are marked cluster_label='UNASSIGNED'.
    """
    plan = build_refinement_plan(req.cluster_sizes, req.centroids)
    refined = apply_refinement(req.assignments, plan) if req.assignments else []
    return RefineClusterResponse(
        plan=plan,
        refined_assignments=refined,
        merged_count=sum(1 for p in plan if p["action"] == "MERGE"),
        suppressed_count=sum(1 for p in plan if p["action"] == "SUPPRESS"),
    )


@router.post("/personalise", response_model=PersonaliseResponse)
def personalise_landing(req: PersonaliseRequest) -> PersonaliseResponse:
    """
    Phase 5-4: Real-Time Landing Adaptation (§5.4.1 Process 3).

    Returns a personalised block order based on the visitor's cluster archetype.

    Constraints:
      - ONLY reorders blocks already in default_block_order (no new content generated)
      - System NEVER generates new text — changes ORDER of ready blocks only
      - Marketer sets allowed_block_ids upfront to control what may be reordered
      - 5-10% control group is enforced at Spring Boot (ClusterController) before
        this endpoint is called; this endpoint always personalises
    """
    # If Spring Boot passed an approved rule from personalization_rules DB table,
    # use it directly (marketer pre-approved — no hard-coded rules needed).
    if req.approved_block_order:
        return PersonaliseResponse(
            block_order=req.approved_block_order,
            cluster_label=req.cluster_label,
        )

    # Fallback to CLUSTER_BLOCK_RULES (built-in heuristics) until marketer configures rules.
    ordered = personalise_block_order(
        req.default_block_order,
        req.cluster_label,
        req.allowed_block_ids,
    )
    return PersonaliseResponse(
        block_order=ordered,
        cluster_label=req.cluster_label,
    )


# ─── Follow-up bandit endpoints (vision §19.6) ───────────────────────────────

class FollowupDecideRequest(BaseModel):
    tenant_id: str
    appointment_id: str
    country_code: Optional[str] = None
    ticket_bucket: Optional[str] = None
    exploration_rate: float = 0.15
    variants: list[dict]  # [{id, name, alpha, beta, is_control, lifecycle_state}]


class FollowupDecideResponse(BaseModel):
    variant_id: str
    variant_name: str
    context_key: str
    is_control: bool


@router.post("/followup", response_model=FollowupDecideResponse)
def decide_followup(req: FollowupDecideRequest):
    """
    Thompson Sampling: select follow-up variant for an appointment.

    Rules (vision §19.6):
    - Control arm never paused; always eligible.
    - exploration_rate 10-20% (default 15%).
    - Only APPROVED or is_control variants are eligible.
    """
    context_key = classify_context(req.country_code, req.ticket_bucket)
    try:
        variant = thompson_select_variant(req.variants, context_key, req.exploration_rate)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    return FollowupDecideResponse(
        variant_id=str(variant["id"]),
        variant_name=variant.get("name", ""),
        context_key=context_key,
        is_control=bool(variant.get("is_control", False)),
    )


class AutoPauseRequest(BaseModel):
    variant_alpha: float
    variant_beta: float
    control_alpha: float
    control_beta: float
    min_ess: int = 100


class AutoPauseResponse(BaseModel):
    should_pause: bool
    p_worse: float


@router.post("/followup/auto-pause", response_model=AutoPauseResponse)
def followup_auto_pause(req: AutoPauseRequest):
    """Monte Carlo: P(variant worse than control) > 0.95 AND ESS >= min_ess → pause."""
    pause, p_worse = should_auto_pause(
        req.variant_alpha, req.variant_beta,
        req.control_alpha, req.control_beta,
        req.min_ess,
    )
    return AutoPauseResponse(should_pause=pause, p_worse=p_worse)


class ShowRatePosteriorRequest(BaseModel):
    showed: int
    total: int
    prior_alpha: float = 1.0
    prior_beta: float = 1.0


@router.post("/followup/show-rate-posterior")
def followup_show_rate_posterior(req: ShowRatePosteriorRequest):
    """Beta posterior for show-rate — returns mean, 90% CI, ESS."""
    try:
        return compute_show_rate_posterior(
            req.showed, req.total, req.prior_alpha, req.prior_beta)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
