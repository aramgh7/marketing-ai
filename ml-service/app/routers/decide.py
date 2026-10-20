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
    apply_followup_outcome,
    apply_reward,
    ensure_arms,
    get_benchmark,
    holdout_bucket,
    load_arms,
    log_decision,
    sample_arms,
    sweep_no_response,
)
from app.services.followup_service import (
    classify_context,
    thompson_select_variant,
    should_auto_pause,
    compute_show_rate_posterior,
)

router = APIRouter(tags=["Thompson Sampling"])


def _holdout_bucket(session_id: Optional[str]) -> int:
    """Deterministic 0-9999 bucket from session_id so the same session stays in holdout.

    Audit 2.5: hash-based - session ids are cookie strings, not guaranteed hex.
    """
    return holdout_bucket(session_id)


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
        # No arms anywhere: create them once for the canonical channel-global key
        # (audit 2.9 - load_arms is read-only; arms are created here explicitly).
        effective_key = f"channel:{channel}"
        await ensure_arms(req.tenant_id, effective_key)
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
        skipped=bool(result.get("skipped", False)),
        reason=result.get("reason"),
    )


class SweepRequest(BaseModel):
    tenant_id: Optional[str] = None
    window_days: int = 7
    max_age_days: int = 30
    limit: int = 1000


@router.post("/reward/sweep-negatives")
async def reward_sweep_negatives(req: SweepRequest):
    """beta += 1 for decisions past the attribution window with no reward (audit 2.3).

    Called hourly by Spring RewardSweepScheduler; FastAPI stays the sole thompson_arms writer.
    """
    return await sweep_no_response(req.window_days, req.max_age_days, req.limit, req.tenant_id)


class FollowupOutcomeRequest(BaseModel):
    tenant_id: str
    appointment_id: str
    outcome: str  # SHOWED | NO_SHOW


@router.post("/followup/outcome")
async def followup_outcome(req: FollowupOutcomeRequest):
    """Record a follow-up outcome and update the assigned followup_variants alpha/beta (audit 2.10)."""
    try:
        return await apply_followup_outcome(req.tenant_id, req.appointment_id, req.outcome)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


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


# ─── Reward Ladder endpoints (8-3) ────────────────────────────────────────────

class StageRewardRequest(BaseModel):
    stage_key: str
    is_quality_stage: bool = False
    funnel_stages: list[dict] = []


@router.post("/reward/compute-stage")
def reward_compute_stage(req: StageRewardRequest):
    from app.services.reward_service import compute_stage_reward
    return compute_stage_reward(req.stage_key, req.is_quality_stage, req.funnel_stages)


class FunnelProgressRequest(BaseModel):
    reached_stages: list[str]
    funnel_stages: list[dict]


@router.post("/reward/funnel-progress")
def reward_funnel_progress(req: FunnelProgressRequest):
    from app.services.reward_service import compute_funnel_progress_score
    return compute_funnel_progress_score(req.reached_stages, req.funnel_stages)


class QualityRateRequest(BaseModel):
    reward_events: list[dict]
    variant_id: str
    days: int = 30


@router.post("/reward/quality-rate")
def reward_quality_rate(req: QualityRateRequest):
    from app.services.reward_service import estimate_quality_rate
    return estimate_quality_rate(req.reward_events, req.variant_id, req.days)


# ─── Form Self-Qualification endpoints (8-5) ──────────────────────────────────

class LeadScoreRequest(BaseModel):
    self_accredited: Optional[bool] = None
    ticket_bucket: Optional[str] = None
    k_ticket: Optional[dict] = None


@router.post("/form/lead-score")
def form_lead_score(req: LeadScoreRequest):
    from app.services.form_qualification_service import compute_lead_score
    return compute_lead_score(req.self_accredited, req.ticket_bucket, req.k_ticket)


class ValidateFormRequest(BaseModel):
    payload: dict


@router.post("/form/validate")
def form_validate(req: ValidateFormRequest):
    from app.services.form_qualification_service import validate_form_payload
    return validate_form_payload(req.payload)


class CalibrationRequest(BaseModel):
    calibration_log: list[dict]
    min_samples: int = 20


@router.post("/form/calibrate-value")
def form_calibrate_value(req: CalibrationRequest):
    from app.services.form_qualification_service import calibrate_value_coefficients
    return calibrate_value_coefficients(req.calibration_log, req.min_samples)


# ── Experiment stopping rules (8-8) ───────────────────────────────────────────

class ExperimentCheckRequest(BaseModel):
    arms: list[dict]
    min_ess: int = 200
    auto_stop_p: float = 0.95


@router.post("/experiment/should-conclude")
def experiment_should_conclude(req: ExperimentCheckRequest):
    from app.services.experiment_service import should_conclude
    return should_conclude(req.arms, req.min_ess, req.auto_stop_p)


@router.post("/experiment/check-pause")
def experiment_check_pause(req: ExperimentCheckRequest):
    from app.services.experiment_service import check_auto_pause
    return check_auto_pause(req.arms, min_ess_pause=50, p_threshold=0.95)


@router.post("/experiment/ranked-arms")
def experiment_ranked_arms(req: ExperimentCheckRequest):
    from app.services.experiment_service import thompson_best_arm
    return thompson_best_arm(req.arms)


# ── Funnel Path selection (8-14) ───────────────────────────────────────────

class FunnelPathRequest(BaseModel):
    paths: list[dict]
    ticket_bucket: Optional[str] = None
    session_events: list[dict] = []

class PathMetricsRequest(BaseModel):
    reward_events: list[dict]
    page_views: dict

@router.post("/decide/funnel-path")
def decide_funnel_path(req: FunnelPathRequest):
    from app.services.funnel_path_service import select_path, check_path_b_engagement_gate
    selected = select_path(req.paths, req.ticket_bucket)
    if selected is None:
        raise HTTPException(status_code=422, detail="No active funnel paths")

    result: dict = {"path_id": selected["path_id"], "path_key": selected["path_key"]}

    if selected.get("path_key") == "MATERIALS_FIRST" and req.session_events:
        gate = check_path_b_engagement_gate(req.session_events)
        result["gate"] = gate
        if not gate["gate_passed"]:
            control = next((p for p in req.paths if p.get("is_control")), None)
            if control:
                result["path_id"]     = control["path_id"]
                result["path_key"]    = control["path_key"]
                result["gate_fallback"] = True

    return result


@router.post("/funnel-paths/metrics")
def funnel_path_metrics(req: PathMetricsRequest):
    from app.services.funnel_path_service import compute_path_metrics
    return compute_path_metrics(req.reward_events, req.page_views)


# ── No-show propensity (8-15) ──────────────────────────────────────────────

class NoshowScoreRequest(BaseModel):
    booking_distance_h: Optional[float] = None
    lead_tz_known:      bool = False
    ticket_bucket:      Optional[str] = None
    self_accredited:    Optional[bool] = None
    device_type:        Optional[str] = None
    is_international:   bool = False

class SlotFillRequest(BaseModel):
    booked_count: int
    noshow_probs: list[float]

@router.post("/propensity/noshow")
def noshow_propensity(req: NoshowScoreRequest):
    from app.services.noshow_service import predict_noshow, get_followup_audience_override
    result = predict_noshow(
        req.booking_distance_h, req.lead_tz_known, req.ticket_bucket,
        req.self_accredited, req.device_type, req.is_international,
    )
    result["followup_override"] = get_followup_audience_override(result["risk_tier"])
    return result

@router.post("/propensity/slot-fill")
def slot_fill_probability(req: SlotFillRequest):
    from app.services.noshow_service import compute_slot_fill_probability
    return compute_slot_fill_probability(req.booked_count, req.noshow_probs)


# ── Predicted Lead Value (8-16) ────────────────────────────────────────────

class LeadValueRequest(BaseModel):
    p_show:                   float
    ticket_bucket:            Optional[str] = None
    p_verified_given_show:    float = 0.65
    p_invest_given_verified:  float = 0.21
    e_ticket_override:        Optional[float] = None

class ValueCalibrationRequest(BaseModel):
    predictions: list[dict]
    min_samples: int = 200

@router.post("/predict/lead-value")
def predict_lead_value_endpoint(req: LeadValueRequest):
    from app.services.lead_value_service import predict_lead_value
    return predict_lead_value(
        req.p_show, req.ticket_bucket,
        req.p_verified_given_show, req.p_invest_given_verified, req.e_ticket_override,
    )

@router.post("/predict/lead-value/calibrate")
def calibrate_lead_value(req: ValueCalibrationRequest):
    from app.services.lead_value_service import calibrate_value_model
    return calibrate_value_model(req.predictions, req.min_samples)


# ── Compliance by Construction + Persona (8-17) ────────────────────────────

class ComposeRequest(BaseModel):
    slots: dict
    approved_phrases: list[dict]
    context_filter: Optional[str] = None

class PersonaScoreRequest(BaseModel):
    text: str
    persona: dict
    compliance_results: dict

@router.post("/compliance/compose-from-approved")
def compose_from_approved_endpoint(req: ComposeRequest):
    from app.services.moderation_service import compose_from_approved
    return compose_from_approved(req.slots, req.approved_phrases, req.context_filter)

@router.post("/persona/score")
def persona_score(req: PersonaScoreRequest):
    from app.services.moderation_service import score_with_persona
    return score_with_persona(req.text, req.persona, req.compliance_results)
