"""Thompson Sampling service — Beta(α,β) arm selection with Redis-cached posteriors.

Reward schedule (arch v1.1 §4.4, vision v1.9.9 §24):
  ONE binary event per arm. All deltas = 1.0 (integer counts only).
  CTA_CLICK / FORM_START   → α += 1  (primary micro-conversion, hundreds/week)
  FORM_SUBMIT              → α += 1  (secondary, tens/week; skip when reward_on_form_submit=false)
  CALL_ANSWERED            → α += 1  (sparse CRM signal, used as corrector)
  MEETING_ATTENDED         → α += 1  (key quality signal)
  CONVERSION / PURCHASE    → α += 1
  NO_RESPONSE_7D           → β += 1  (lead went cold)

Fractional increments (e.g. α+=0.3) are FORBIDDEN — arch v1.1 §4.4.

Decay toward prior (arch v1.1 §9, vision v1.9.9 §6.2):
  α = 1 + 0.99·(α − 1),  β = 1 + 0.99·(β − 1)
  Naive α×=0.99 drives α below 1 → Beta(0,0) is improper. Decay toward prior is correct.

Propensity (arch v1.1 §4.6):
  Thompson's propensity has no closed form; estimated at decision time by
  Monte Carlo (N=200 draws from the cached posteriors, <1ms) and stored in candidates.

Cache: Redis key posterior:{tenant_id}:{context_key}, TTL 15 min.
"""

import asyncio
import json
from datetime import datetime, timezone
from typing import Optional

import numpy as np

from app.config import settings
from app.services.db_client import get_pool
from app.services.job_store import get_redis

CACHE_PREFIX = "posterior"
CACHE_TTL = 900  # 15 min
DECISION_TTL = 3600  # 1 h
DECAY_FACTOR = 0.99
DECAY_INTERVAL_SECS = 86_400  # 24 h
N_PROPENSITY = 200  # Monte Carlo draws for propensity estimation

_UPDATE_ALPHA = "UPDATE thompson_arms SET alpha=alpha+$1, total_samples=total_samples+1, updated_at=$2 WHERE id=$3"
_UPDATE_BETA  = "UPDATE thompson_arms SET beta=beta+$1,  total_samples=total_samples+1, updated_at=$2 WHERE id=$3"

# arch v1.1 §4.4: all deltas are 1.0 — binary counts only, no fractional increments
REWARD_MAP: dict[str, tuple[str, float]] = {
    "CTA_CLICK":        ("alpha", 1.0),  # primary micro-conversion (fast loop)
    "FORM_START":       ("alpha", 1.0),  # micro-conversion
    "FORM_SUBMIT":      ("alpha", 1.0),  # secondary (skip via reward_on_form_submit flag for B2B)
    "CALL_ANSWERED":    ("alpha", 1.0),  # sparse CRM signal
    "MEETING_ATTENDED": ("alpha", 1.0),  # key quality signal
    "CONVERSION":       ("alpha", 1.0),
    "PURCHASE":         ("alpha", 1.0),
    "NO_RESPONSE_7D":   ("beta",  1.0),  # lead went cold
}


async def load_arms(tenant_id: str, context_key: str = "global") -> list[dict]:
    """Return ACTIVE arms from Redis cache or DB. Apply 24 h decay lazily."""
    redis = get_redis()
    cache_key = f"{CACHE_PREFIX}:{tenant_id}:{context_key}"
    cached = await redis.get(cache_key)
    if cached:
        return json.loads(cached)

    pool = await get_pool()
    async with pool.acquire() as conn:
        # arch v1.1 §12 #12: single writer = FastAPI.
        # Create arms lazily for ACTIVE variants that have no arm yet (idempotent).
        await conn.execute(
            """
            INSERT INTO thompson_arms (tenant_id, variant_id, context_key)
            SELECT $1::uuid, lv.id, $2
            FROM   landing_variants lv
            WHERE  lv.tenant_id = $1::uuid
              AND  lv.status    = 'ACTIVE'
              AND  NOT EXISTS (
                  SELECT 1 FROM thompson_arms ta
                  WHERE  ta.variant_id  = lv.id
                    AND  ta.context_key = $2
              )
            ON CONFLICT DO NOTHING
            """,
            tenant_id,
            context_key,
        )
        rows = await conn.fetch(
            """
            SELECT ta.id::text            AS id,
                   ta.variant_id::text    AS variant_id,
                   ta.alpha,
                   ta.beta,
                   ta.total_samples,
                   ta.last_decayed_at,
                   lv.block_order
            FROM   thompson_arms ta
            JOIN   landing_variants lv ON lv.id = ta.variant_id
            WHERE  ta.tenant_id  = $1::uuid
              AND  ta.context_key = $2
              AND  lv.status      = 'ACTIVE'
            """,
            tenant_id,
            context_key,
        )

    if not rows:
        return []

    now = datetime.now(timezone.utc)
    arms: list[dict] = []
    to_decay: list[dict] = []

    for row in rows:
        arm = {
            "id": row["id"],
            "variant_id": row["variant_id"],
            "alpha": float(row["alpha"]),
            "beta": float(row["beta"]),
            "total_samples": row["total_samples"],
            "block_order": list(row["block_order"]) if row["block_order"] else [],
        }
        last = row["last_decayed_at"]
        if last is not None:
            # asyncpg returns timezone-aware datetime for TIMESTAMPTZ
            if last.tzinfo is None:
                last = last.replace(tzinfo=timezone.utc)
            if (now - last).total_seconds() > DECAY_INTERVAL_SECS:
                # arch v1.1 §9: decay toward prior (1,1), not toward zero.
                # α = 1 + 0.99·(α − 1). Naive α×=0.99 drives α < 1 → Beta(0,0) improper.
                arm["alpha"] = 1.0 + DECAY_FACTOR * (arm["alpha"] - 1.0)
                arm["beta"]  = 1.0 + DECAY_FACTOR * (arm["beta"]  - 1.0)
                to_decay.append(arm)
        arms.append(arm)

    if to_decay:
        asyncio.create_task(_persist_decay(to_decay, now))

    await redis.setex(cache_key, CACHE_TTL, json.dumps(arms))
    return arms


async def _persist_decay(arms: list[dict], now: datetime) -> None:
    pool = await get_pool()
    async with pool.acquire() as conn:
        for arm in arms:
            await conn.execute(
                """
                UPDATE thompson_arms
                SET alpha = $1, beta = $2, last_decayed_at = $3, updated_at = $4
                WHERE id = $5
                """,
                arm["alpha"],
                arm["beta"],
                now,
                now,
                arm["id"],
            )


def sample_arms(arms: list[dict]) -> tuple[dict, list[dict]]:
    """Beta(α,β) sampling — pure, no I/O. Returns (best_arm, all_candidates).

    candidates format (arch v1.1 §4.6):
      [{"variant_id":..., "arm_id":..., "propensity":0.63, "sample_value":0.42,
        "alpha":12, "beta":88}, ...]

    propensity = P(arm is chosen by Thompson), estimated via N=200 Monte Carlo draws.
    Required for off-policy evaluation; stored in decision_log before /decide returns.
    """
    # 1. One real sample — determines the winner this request
    sample_values: dict[str, float] = {}
    best: dict | None = None
    best_val = -1.0

    for arm in arms:
        val = float(np.random.beta(arm["alpha"], arm["beta"]))
        sample_values[arm["id"]] = val
        if val > best_val:
            best_val = val
            best = {**arm, "sample_value": val}

    # 2. Monte Carlo propensity (N=200, <1ms) — arch v1.1 §4.6
    win_counts: dict[str, int] = {arm["id"]: 0 for arm in arms}
    for _ in range(N_PROPENSITY):
        mc_best_val = -1.0
        mc_best_id: str | None = None
        for arm in arms:
            v = float(np.random.beta(arm["alpha"], arm["beta"]))
            if v > mc_best_val:
                mc_best_val = v
                mc_best_id = arm["id"]
        if mc_best_id is not None:
            win_counts[mc_best_id] += 1

    # 3. Build candidates with propensity field
    candidates: list[dict] = [
        {
            "variant_id":   arm["variant_id"],
            "arm_id":       arm["id"],
            "propensity":   round(win_counts[arm["id"]] / N_PROPENSITY, 4),
            "sample_value": round(sample_values[arm["id"]], 6),
            "alpha":        arm["alpha"],
            "beta":         arm["beta"],
        }
        for arm in arms
    ]

    return best, candidates  # type: ignore[return-value]


async def log_decision(
    tenant_id: str,
    session_id: str,
    creative_id: Optional[str],
    chosen: dict,
    candidates: list[dict],
    context: dict,
    is_holdout: bool = False,
) -> str:
    """Insert into decision_log and cache for reward lookups. Returns decision_id."""
    pool = await get_pool()
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            INSERT INTO decision_log
                (tenant_id, session_id, creative_id, chosen_variant_id,
                 candidates, context, model_version, is_holdout)
            VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, 'beta-v1', $7)
            RETURNING id::text AS id
            """,
            tenant_id,
            session_id,
            creative_id,
            chosen["variant_id"],
            json.dumps(candidates),
            json.dumps(context),
            is_holdout,
        )

    decision_id: str = row["id"]
    redis = get_redis()
    await redis.setex(
        f"decision:{decision_id}",
        DECISION_TTL,
        json.dumps(
            {
                "tenant_id": tenant_id,
                "variant_id": chosen["variant_id"],
                "arm_id": chosen["id"],
                "context_key": context.get("context_key", "global"),
            }
        ),
    )
    return decision_id


_CHURN_CAP = 0.30  # vision §6.7: max 30% of (α+β) per retroactive recalc


async def apply_churn_penalty(
    tenant_id: str,
    arm_id: str,
    context_key: str,
    weighted_sum: float,
    cohort_size: int,
    churn_count: int,
) -> dict:
    """Retroactively penalise a Thompson arm for post-conversion churn (vision §6.7).

    weighted_sum: sum of exp(-0.02 * days_since_decision) over churned sessions.
    Cap: penalty ≤ 30% of current (α+β) so a single churn wave can't kill a good arm.
    """
    pool = await get_pool()
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            SELECT id::text, variant_id::text, alpha, beta, context_key
            FROM   thompson_arms
            WHERE  id = $1::uuid
            """,
            arm_id,
        )
        if not row:
            raise ValueError(f"Arm not found: {arm_id}")

        current_alpha = float(row["alpha"])
        current_beta  = float(row["beta"])
        cap           = _CHURN_CAP * (current_alpha + current_beta)
        applied_delta = min(weighted_sum, cap)
        capped        = applied_delta < weighted_sum

        now = datetime.now(timezone.utc)
        await conn.execute(
            "UPDATE thompson_arms SET beta = beta + $1, updated_at = $2 WHERE id = $3::uuid",
            applied_delta,
            now,
            arm_id,
        )
        await conn.execute(
            """
            INSERT INTO churn_penalty_log
                (tenant_id, arm_id, variant_id, context_key,
                 beta_delta, cohort_size, churn_count, weighted_sum, capped)
            VALUES ($1::uuid, $2::uuid, $3::uuid, $4,
                    $5, $6, $7, $8, $9)
            """,
            tenant_id,
            arm_id,
            row["variant_id"],
            context_key,
            applied_delta,
            cohort_size,
            churn_count,
            weighted_sum,
            capped,
        )

    redis = get_redis()
    await redis.delete(f"{CACHE_PREFIX}:{tenant_id}:{context_key}")

    return {
        "arm_id": arm_id,
        "beta_delta": applied_delta,
        "capped": capped,
        "new_beta": current_beta + applied_delta,
    }


async def apply_reward(decision_id: str, reward_type: str) -> dict:
    """Update the α or β of the arm that was chosen in the given decision.

    vision §16.1: FORM_SUBMIT reward is disabled when reward_on_form_submit=false
    (B2B clients with GHL integration — reward comes from call_answered/meeting_attended).
    """
    if reward_type not in REWARD_MAP:
        raise ValueError(f"Unknown reward_type '{reward_type}'. Valid: {list(REWARD_MAP)}")

    if reward_type == "FORM_SUBMIT" and not settings.reward_on_form_submit:
        # Return a no-op response — the decision is logged but Thompson is not updated.
        return {"arm_id": None, "tenant_id": None, "variant_id": None,
                "field_updated": None, "delta": 0.0, "skipped": True,
                "reason": "reward_on_form_submit=false (§16.1 B2B mode)"}

    field, delta = REWARD_MAP[reward_type]

    redis = get_redis()
    raw = await redis.get(f"decision:{decision_id}")
    if not raw:
        raise ValueError(f"Decision not found or expired: {decision_id}")

    dec = json.loads(raw)
    pool = await get_pool()
    now = datetime.now(timezone.utc)

    sql = _UPDATE_ALPHA if field == "alpha" else _UPDATE_BETA
    async with pool.acquire() as conn:
        await conn.execute(sql, delta, now, dec["arm_id"])

    # Invalidate cached posteriors so next decide sees fresh values
    context_key = dec.get("context_key", "global")
    await redis.delete(f"{CACHE_PREFIX}:{dec['tenant_id']}:{context_key}")

    return {
        "arm_id": dec["arm_id"],
        "tenant_id": dec["tenant_id"],
        "variant_id": dec["variant_id"],
        "field_updated": field,
        "delta": delta,
    }


async def get_benchmark(tenant_id: str, days: int = 30) -> dict:
    """Bayesian lift: P(system CVR > holdout CVR) over the last N days.

    Treatment = Thompson-selected sessions (is_holdout=false).
    Holdout   = randomly-selected sessions (is_holdout=true).
    Lift      = (treatment_cvr - holdout_cvr) / holdout_cvr * 100.
    P(better) = Monte Carlo integral over Beta posteriors (N=10 000).
    """
    pool = await get_pool()
    async with pool.acquire() as conn:
        counts = await conn.fetchrow(
            """
            SELECT
                COUNT(*) FILTER (WHERE NOT is_holdout) AS treatment_n,
                COUNT(*) FILTER (WHERE     is_holdout) AS holdout_n
            FROM decision_log
            WHERE tenant_id = $1::uuid
              AND decided_at > now() - ($2 * INTERVAL '1 day')
            """,
            tenant_id,
            days,
        )
        convs = await conn.fetchrow(
            """
            SELECT
                COUNT(DISTINCT dl.id) FILTER (WHERE NOT dl.is_holdout AND e.id IS NOT NULL)
                    AS treatment_conv,
                COUNT(DISTINCT dl.id) FILTER (WHERE dl.is_holdout AND e.id IS NOT NULL)
                    AS holdout_conv
            FROM decision_log dl
            LEFT JOIN events e
                ON  e.session_id  = dl.session_id
                AND e.tenant_id   = dl.tenant_id
                AND e.event_type  = 'FORM_SUBMIT'
                AND e.event_time  BETWEEN dl.decided_at
                                      AND dl.decided_at + INTERVAL '7 days'
            WHERE dl.tenant_id = $1::uuid
              AND dl.decided_at > now() - ($2 * INTERVAL '1 day')
            """,
            tenant_id,
            days,
        )

    treatment_n    = int(counts["treatment_n"]  or 0)
    holdout_n      = int(counts["holdout_n"]    or 0)
    treatment_conv = int(convs["treatment_conv"] or 0)
    holdout_conv   = int(convs["holdout_conv"]   or 0)

    treatment_cvr = treatment_conv / treatment_n if treatment_n else 0.0
    holdout_cvr   = holdout_conv   / holdout_n   if holdout_n   else 0.0
    lift_pct      = ((treatment_cvr - holdout_cvr) / holdout_cvr * 100) if holdout_cvr else None

    # Bayesian P(treatment > holdout) via Monte Carlo (Beta posteriors, N=10 000)
    t_a = 1 + treatment_conv
    t_b = 1 + (treatment_n - treatment_conv)
    h_a = 1 + holdout_conv
    h_b = 1 + (holdout_n   - holdout_conv)
    t_samples = np.random.beta(t_a, t_b, 10_000)
    h_samples = np.random.beta(h_a, h_b, 10_000)
    prob_better = float(np.mean(t_samples > h_samples))

    return {
        "tenant_id":      tenant_id,
        "period_days":    days,
        "treatment_n":    treatment_n,
        "treatment_conv": treatment_conv,
        "treatment_cvr":  round(treatment_cvr, 4),
        "holdout_n":      holdout_n,
        "holdout_conv":   holdout_conv,
        "holdout_cvr":    round(holdout_cvr, 4),
        "lift_pct":       round(lift_pct, 2) if lift_pct is not None else None,
        "prob_system_better": round(prob_better, 4),
        "sufficient_data": holdout_n >= 100 and treatment_n >= 100,
    }
