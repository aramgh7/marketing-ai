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

import hashlib
import json
import math
from datetime import datetime, timezone
from typing import Optional

import numpy as np

from app.config import settings
from app.services.db_client import system_pool, tenant_pool  # ML-26: removed unused get_pool
from app.services.glm_prior_service import prior_for_variant
from app.services.expert_prior_service import prior_for_arm
from app.services.job_store import get_redis

CACHE_PREFIX = "posterior"
CACHE_TTL = 900  # 15 min
DECISION_TTL = 3600  # 1 h
DECAY_FACTOR = 0.99
DECAY_INTERVAL_SECS = 86_400  # 24 h
N_PROPENSITY = 2_000  # Monte Carlo draws for propensity estimation

_UPDATE_ALPHA = ("UPDATE thompson_arms SET alpha=alpha+$1, total_samples=total_samples+1, updated_at=$2 "
                 "WHERE id=$3::uuid AND tenant_id=$4::uuid")
_UPDATE_BETA  = ("UPDATE thompson_arms SET beta=beta+$1,  total_samples=total_samples+1, updated_at=$2 "
                 "WHERE id=$3::uuid AND tenant_id=$4::uuid")
_SET_TENANT   = "SELECT set_config('app.current_tenant', $1, true)"  # SET LOCAL cannot take a bind parameter

NO_RESPONSE_WINDOW_DAYS = 7   # attribution window before a decision counts as a negative (beta += 1)

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


# ─── Pure helpers (no I/O) ───────────────────────────────────────────────────

def holdout_bucket(session_id: Optional[str]) -> int:
    """Deterministic 0-9999 bucket. Session ids are cookie strings, not necessarily hex/UUID."""
    if not session_id:
        return int(np.random.randint(0, 10_000))
    return int(hashlib.sha1(session_id.encode("utf-8")).hexdigest()[:8], 16) % 10_000


def decay_toward_prior(value: float, days: int) -> float:
    """arch v1.1 §9: alpha = 1 + 0.99^days * (alpha - 1). Never crosses below the Beta(1,1) prior."""
    if days <= 0:
        return value
    return 1.0 + (DECAY_FACTOR ** days) * (value - 1.0)


def churn_penalty_delta(weighted_sum: float, alpha: float, beta: float) -> tuple[int, bool]:
    """Integer beta penalty for a churn wave (vision §6.7 cap, arch §4.4 binary counts).

    Returns (applied_delta, capped). The cap is 30 % of (alpha+beta); the result is floored
    because fractional increments are forbidden - a penalty below 1 is skipped.
    """
    cap = _CHURN_CAP * (alpha + beta)
    capped = weighted_sum > cap
    return int(math.floor(min(weighted_sum, cap))), capped


def arm_for_decision(chosen_variant_id: str, candidates) -> Optional[str]:
    """Recover the chosen arm id from a decision_log row (candidates JSON)."""
    if isinstance(candidates, str):
        try:
            candidates = json.loads(candidates)
        except ValueError:
            return None
    for c in candidates or []:
        if str(c.get("variant_id")) == str(chosen_variant_id):
            return c.get("arm_id")
    return None


def _as_dict(value) -> dict:
    if isinstance(value, str):
        try:
            return json.loads(value) or {}
        except ValueError:
            return {}
    return value or {}


# ─── Arms ────────────────────────────────────────────────────────────────────

async def ensure_arms(tenant_id: str, context_key: str) -> int:
    """Create Beta(1,1) arms for every ACTIVE variant under context_key (idempotent).

    Audit 2.9: this used to run inside load_arms for *any* requested key, which created a
    cold arm set for every cluster/channel and defeated the documented fallback to the
    channel-global arms. It is now an explicit call made only for the canonical key.
    """
    pool = await tenant_pool(tenant_id)
    async with pool.acquire() as conn:
        status = await conn.execute(
            """
            INSERT INTO thompson_arms (tenant_id, variant_id, context_key)
            SELECT $1::uuid, lv.id, $2::varchar
            FROM   landing_variants lv
            WHERE  lv.tenant_id = $1::uuid
              AND  lv.status    = 'ACTIVE'
              AND  NOT EXISTS (
                  SELECT 1 FROM thompson_arms ta
                  WHERE  ta.variant_id  = lv.id
                    AND  ta.context_key = $2::varchar
              )
            ON CONFLICT DO NOTHING
            """,
            tenant_id,
            context_key,
        )
        try:
            created = int(status.split()[-1])
        except (ValueError, IndexError):
            created = 0
        if created:
            await _apply_glm_priors(conn, tenant_id, context_key)   # roadmap 9-10
    await get_redis().delete(f"{CACHE_PREFIX}:{tenant_id}:{context_key}")
    return created


GLM_PRIOR_BASE_RATE = 0.03   # fallback CVR when the tenant has no closed sessions yet
GLM_PRIOR_BASE_SD   = 0.02


async def _apply_glm_priors(conn, tenant_id: str, context_key: str) -> int:
    """Roadmap 9-10 (vision §15.6): new arms start from the GLM posterior predictive of their atoms
    instead of Beta(1,1). Only untouched arms (total_samples = 0, alpha = beta = 1) are changed, so a
    re-run never overwrites learned counts. Without GLM posteriors nothing happens."""
    try:
        posteriors = await conn.fetch(
            "SELECT atom_type, atom_value, mean, std AS sd FROM glm_posteriors "
            "WHERE tenant_id = $1::uuid AND model_stage = 'CVR'",
            tenant_id,
        )
        base = await conn.fetchrow(
            """
            SELECT COUNT(*) FILTER (WHERE converted) AS conv, COUNT(*) AS n
            FROM   sessions WHERE tenant_id = $1::uuid AND started_at > now() - INTERVAL '90 days'
            """,
            tenant_id,
        )
        n = int(base["n"] or 0) if base else 0
        base_rate = (int(base["conv"] or 0) + 1) / (n + 2) if n >= 50 else GLM_PRIOR_BASE_RATE
        base_sd = GLM_PRIOR_BASE_SD
        fresh = await conn.fetch(
            """
            SELECT ta.id::text AS id, lv.creative_id::text AS creative_id,
                   c.exploration_prior_alpha AS exp_alpha, c.exploration_prior_beta AS exp_beta
            FROM   thompson_arms ta JOIN landing_variants lv ON lv.id = ta.variant_id
            LEFT JOIN creatives c ON c.id = lv.creative_id
            WHERE  ta.tenant_id = $1::uuid AND ta.context_key = $2
              AND  ta.total_samples = 0 AND ta.alpha = 1 AND ta.beta = 1
            """,
            tenant_id, context_key,
        )
        atom_posts = [dict(r) for r in posteriors]
        updated = 0
        for arm in fresh:
            if not arm["creative_id"]:
                continue
            atoms = await conn.fetch(
                "SELECT atom_type, atom_value FROM creative_atoms WHERE creative_id = $1::uuid AND tenant_id = $2::uuid",
                arm["creative_id"], tenant_id,
            )
            prior = prior_for_variant(atom_posts, [dict(a) for a in atoms], base_rate, base_sd)
            # roadmap 9-9 / arch §15.13: expert rating prior (creatives.exploration_prior_*) combined with the GLM one
            stored = (arm["exp_alpha"], arm["exp_beta"]) if arm["exp_alpha"] is not None else None
            resolved = prior_for_arm(prior, None, stored=stored)
            if resolved["source"] == "FLAT":
                continue
            await conn.execute(
                "UPDATE thompson_arms SET alpha = $1, beta = $2, updated_at = now() "
                "WHERE id = $3::uuid AND total_samples = 0",
                float(resolved["alpha0"]), float(resolved["beta0"]), arm["id"],
            )
            updated += 1
        return updated
    except Exception as exc:  # priors are an optimisation, never a failure of /decide
        import logging
        logging.getLogger(__name__).warning("GLM priors skipped: %s", exc)
        return 0


async def load_arms(tenant_id: str, context_key: str = "global") -> list[dict]:
    """Return ACTIVE arms from Redis cache or DB (read-only apart from decay).

    Decay (arch v1.1 §9) is applied atomically in SQL for arms idle > 24 h, using
    0.99^days so a 10-day idle arm decays 10 steps, not one (audit 2.6, 6.22).
    Concurrent alpha/beta increments are never overwritten because the UPDATE is relative.
    """
    redis = get_redis()
    cache_key = f"{CACHE_PREFIX}:{tenant_id}:{context_key}"
    cached = await redis.get(cache_key)
    if cached:
        return json.loads(cached)

    pool = await tenant_pool(tenant_id)
    async with pool.acquire() as conn:
        await conn.execute(
            """
            UPDATE thompson_arms ta
            SET    alpha           = 1 + power($3::float8, d.days) * (ta.alpha - 1),
                   beta            = 1 + power($3::float8, d.days) * (ta.beta  - 1),
                   last_decayed_at = now(),
                   updated_at      = now()
            FROM (
                SELECT id,
                       floor(extract(epoch FROM (now() - last_decayed_at)) / 86400)::int AS days
                FROM   thompson_arms
                WHERE  tenant_id   = $1::uuid
                  AND  context_key = $2
                  AND  last_decayed_at < now() - INTERVAL '24 hours'
            ) d
            WHERE ta.id = d.id
            """,
            tenant_id,
            context_key,
            DECAY_FACTOR,
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
              -- roadmap 9-9 / arch §15.6: margin floor — variants without an offer row stay eligible
              -- ML-25: safe-cast min_margin; non-numeric value → NULL → COALESCE fallback avoids 500
              AND  COALESCE((SELECT MIN(vo.estimated_margin) FROM variant_offers vo WHERE vo.variant_id = lv.id), 1e9)
                   >= COALESCE((SELECT CASE WHEN t.settings->>'min_margin' ~ '^-?[0-9]+(\\.[0-9]+)?$'
                                            THEN (t.settings->>'min_margin')::float8 END
                                FROM tenants t WHERE t.id = $1::uuid), -1e9)
            """,
            tenant_id,
            context_key,
        )
        excluded = await conn.fetchval(
            """
            SELECT COUNT(*) FROM thompson_arms ta JOIN landing_variants lv ON lv.id = ta.variant_id
            WHERE ta.tenant_id = $1::uuid AND ta.context_key = $2 AND lv.status = 'ACTIVE'
            """,
            tenant_id, context_key,
        )
        if excluded is not None and int(excluded) > len(rows):
            import logging
            logging.getLogger(__name__).info(
                "load_arms: %d variant(s) excluded by min_margin for tenant %s", int(excluded) - len(rows), tenant_id)

    if not rows:
        return []

    arms: list[dict] = [
        {
            "id": row["id"],
            "variant_id": row["variant_id"],
            "alpha": float(row["alpha"]),
            "beta": float(row["beta"]),
            "total_samples": row["total_samples"],
            "block_order": list(row["block_order"]) if row["block_order"] else [],
        }
        for row in rows
    ]

    await redis.setex(cache_key, CACHE_TTL, json.dumps(arms))
    return arms


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
    pool = await tenant_pool(tenant_id)
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
    pool = await tenant_pool(tenant_id)
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            SELECT id::text, variant_id::text, alpha, beta, context_key
            FROM   thompson_arms
            WHERE  id = $1::uuid AND tenant_id = $2::uuid
            """,
            arm_id,
            tenant_id,
        )
        if not row:
            raise ValueError(f"Arm not found: {arm_id}")

        current_alpha = float(row["alpha"])
        current_beta  = float(row["beta"])
        applied_delta, capped = churn_penalty_delta(weighted_sum, current_alpha, current_beta)
        if applied_delta == 0:
            return {"arm_id": arm_id, "beta_delta": 0, "capped": capped,
                    "new_beta": current_beta, "skipped": True,
                    "reason": "penalty below 1 - fractional increments forbidden (arch §4.4)"}

        now = datetime.now(timezone.utc)
        await conn.execute(
            "UPDATE thompson_arms SET beta = beta + $1, total_samples = total_samples + $1, "
            "updated_at = $2 WHERE id = $3::uuid AND tenant_id = $4::uuid",
            applied_delta,
            now,
            arm_id,
            tenant_id,
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


async def _load_decision(decision_id: str) -> Optional[dict]:
    """Audit 2.2: the Redis decision key lives 1 h, CRM rewards arrive days later."""
    pool = await system_pool()
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            """
            SELECT tenant_id::text AS tenant_id, chosen_variant_id::text AS variant_id,
                   session_id::text AS session_id, candidates, context
            FROM   decision_log
            WHERE  id = $1::uuid
            """,
            decision_id,
        )
    if not row:
        return None
    return {
        "tenant_id":   row["tenant_id"],
        "variant_id":  row["variant_id"],
        "session_id":  row["session_id"],  # ML-5: include session_id for reward validation
        "arm_id":      arm_for_decision(row["variant_id"], row["candidates"]),
        "context_key": _as_dict(row["context"]).get("context_key", "global"),
    }


async def apply_reward(
    decision_id: str,
    reward_type: str,
    tenant_configured: bool = False,
    session_id: Optional[str] = None,  # ML-5: optional caller-supplied session for validation
) -> dict:
    """Update the alpha or beta of the arm that was chosen in the given decision.

    - Idempotent per (decision_id, reward_type) via thompson_reward_log (audit 2.8).
    - Falls back to decision_log when the Redis key has expired (audit 2.2).
    - A positive reward reverses an earlier NO_RESPONSE_7D sweep for the same decision,
      so every decision stays one Bernoulli observation.
    - vision §16.1: FORM_SUBMIT reward is disabled when reward_on_form_submit=false — unless the
      tenant defines tenants.settings.reward_levels (roadmap 8-3, Audit F1): then the caller has
      already resolved the stage through that config and passes tenant_configured=True.
    """
    if reward_type not in REWARD_MAP:
        raise ValueError(f"Unknown reward_type '{reward_type}'. Valid: {list(REWARD_MAP)}")

    if reward_type == "FORM_SUBMIT" and not settings.reward_on_form_submit and not tenant_configured:
        return {"arm_id": None, "tenant_id": None, "variant_id": None,
                "field_updated": None, "delta": 0.0, "skipped": True,
                "reason": "reward_on_form_submit=false (§16.1 B2B mode)"}

    field, delta = REWARD_MAP[reward_type]

    redis = get_redis()
    raw = await redis.get(f"decision:{decision_id}")
    dec = json.loads(raw) if raw else await _load_decision(decision_id)
    if not dec:
        raise ValueError(f"Decision not found: {decision_id}")
    if not dec.get("arm_id"):
        raise ValueError(f"Decision {decision_id} has no resolvable arm")

    # ML-5: when caller supplies session_id, verify it matches the decision —
    # prevents a browser script from replaying any decision_id it finds.
    if session_id is not None and dec.get("session_id") and dec["session_id"] != session_id:
        raise ValueError(f"session_id mismatch for decision {decision_id}")

    pool = await system_pool()
    now = datetime.now(timezone.utc)
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(_SET_TENANT, dec["tenant_id"])
            inserted = await conn.fetchval(
                """
                INSERT INTO thompson_reward_log (decision_id, reward_type, tenant_id, arm_id, field_updated)
                VALUES ($1::uuid, $2, $3::uuid, $4::uuid, $5)
                ON CONFLICT DO NOTHING
                RETURNING 1
                """,
                decision_id, reward_type, dec["tenant_id"], dec["arm_id"], field,
            )
            if not inserted:
                return {"arm_id": dec["arm_id"], "tenant_id": dec["tenant_id"],
                        "variant_id": dec["variant_id"], "field_updated": None,
                        "delta": 0.0, "skipped": True,
                        "reason": f"{reward_type} already applied for this decision"}

            if field == "alpha":
                # ML-3: ONE alpha per decision (arch §4.4 "one Bernoulli observation").
                # If a prior alpha reward already exists for this decision, skip — don't
                # let CTA_CLICK + FORM_SUBMIT + CONVERSION each increment α.
                prior_alpha = await conn.fetchval(
                    "SELECT 1 FROM thompson_reward_log "
                    "WHERE decision_id = $1::uuid AND field_updated = 'alpha' "
                    "AND reward_type != $2 LIMIT 1",
                    decision_id, reward_type,
                )
                if prior_alpha:
                    # Roll back the just-inserted row and skip the arm update
                    await conn.execute(
                        "DELETE FROM thompson_reward_log "
                        "WHERE decision_id = $1::uuid AND reward_type = $2",
                        decision_id, reward_type,
                    )
                    return {"arm_id": dec["arm_id"], "tenant_id": dec["tenant_id"],
                            "variant_id": dec["variant_id"], "field_updated": None,
                            "delta": 0.0, "skipped": True,
                            "reason": "one alpha per decision (arch §4.4 Bernoulli model)"}

                reversed_sweep = await conn.fetchval(
                    "DELETE FROM thompson_reward_log WHERE decision_id = $1::uuid "
                    "AND reward_type = 'NO_RESPONSE_7D' AND field_updated = 'beta' RETURNING 1",
                    decision_id,
                )
                if reversed_sweep:
                    await conn.execute(
                        "UPDATE thompson_arms SET beta = GREATEST(1.0, beta - 1), "
                        "total_samples = GREATEST(0, total_samples - 1), updated_at = $1 "
                        "WHERE id = $2::uuid AND tenant_id = $3::uuid",
                        now, dec["arm_id"], dec["tenant_id"],
                    )

            sql = _UPDATE_ALPHA if field == "alpha" else _UPDATE_BETA
            await conn.execute(sql, delta, now, dec["arm_id"], dec["tenant_id"])

    context_key = dec.get("context_key", "global")
    await redis.delete(f"{CACHE_PREFIX}:{dec['tenant_id']}:{context_key}")

    return {
        "arm_id": dec["arm_id"],
        "tenant_id": dec["tenant_id"],
        "variant_id": dec["variant_id"],
        "field_updated": field,
        "delta": delta,
    }


async def sweep_no_response(
    window_days: int = NO_RESPONSE_WINDOW_DAYS,
    max_age_days: int = 30,
    limit: int = 1000,
    tenant_id: Optional[str] = None,
) -> dict:
    """Audit 2.3: beta += 1 for every decision older than the attribution window with no reward.

    Without this, beta never moves and Beta(alpha,beta) degenerates into a raw success count.
    Each swept decision is logged as NO_RESPONSE_7D, so the sweep is idempotent and a
    late positive reward can reverse it (see apply_reward).
    """
    pool = await system_pool()
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT dl.id::text AS id, dl.tenant_id::text AS tenant_id,
                   dl.chosen_variant_id::text AS variant_id, dl.candidates, dl.context
            FROM   decision_log dl
            WHERE  dl.decided_at < now() - ($1 * INTERVAL '1 day')
              AND  dl.decided_at > now() - ($2 * INTERVAL '1 day')
              AND  ($4::uuid IS NULL OR dl.tenant_id = $4::uuid)
              AND  NOT EXISTS (SELECT 1 FROM thompson_reward_log r WHERE r.decision_id = dl.id)
            ORDER BY dl.decided_at
            LIMIT  $3
            """,
            window_days, max_age_days, limit, tenant_id,
        )

        swept = 0
        unresolved = 0
        touched: set[tuple[str, str]] = set()
        now = datetime.now(timezone.utc)
        for row in rows:
            arm_id = arm_for_decision(row["variant_id"], row["candidates"])
            async with conn.transaction():
                await conn.execute(_SET_TENANT, row["tenant_id"])
                inserted = await conn.fetchval(
                    """
                    INSERT INTO thompson_reward_log (decision_id, reward_type, tenant_id, arm_id, field_updated)
                    VALUES ($1::uuid, 'NO_RESPONSE_7D', $2::uuid, $3::uuid, $4)
                    ON CONFLICT DO NOTHING
                    RETURNING 1
                    """,
                    row["id"], row["tenant_id"], arm_id, "beta" if arm_id else "none",
                )
                if not inserted:
                    continue
                if not arm_id:
                    unresolved += 1
                    continue
                await conn.execute(_UPDATE_BETA, 1.0, now, arm_id, row["tenant_id"])
            swept += 1
            touched.add((row["tenant_id"], _as_dict(row["context"]).get("context_key", "global")))

    redis = get_redis()
    for t, ck in touched:
        await redis.delete(f"{CACHE_PREFIX}:{t}:{ck}")
    return {"swept": swept, "unresolved": unresolved, "scanned": len(rows),
            "window_days": window_days, "max_age_days": max_age_days}


async def apply_followup_outcome(tenant_id: str, appointment_id: str, outcome: str) -> dict:
    """Audit 2.10: the follow-up sequence bandit never learned - nothing wrote followup_variants.alpha/beta.

    SHOWED -> alpha += 1, NO_SHOW -> beta += 1 on the assigned variant. Idempotent: the
    assignment row transitions from UNKNOWN/NULL exactly once.
    """
    if outcome not in ("SHOWED", "NO_SHOW"):
        raise ValueError("outcome must be SHOWED or NO_SHOW")
    field = "alpha" if outcome == "SHOWED" else "beta"
    pool = await tenant_pool(tenant_id)
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(_SET_TENANT, tenant_id)
            variant_id = await conn.fetchval(
                """
                UPDATE followup_assignments
                SET    outcome = $1, resolved_at = now()
                WHERE  appointment_id = $2::uuid AND tenant_id = $3::uuid
                  AND  (outcome IS NULL OR outcome = 'UNKNOWN')
                RETURNING variant_id::text
                """,
                outcome, appointment_id, tenant_id,
            )
            if not variant_id:
                return {"updated": False, "variant_id": None, "field_updated": None,
                        "reason": "assignment not found or already resolved"}
            await conn.execute(
                f"UPDATE followup_variants SET {field} = {field} + 1 WHERE id = $1::uuid AND tenant_id = $2::uuid",
                variant_id, tenant_id,
            )
    return {"updated": True, "variant_id": variant_id, "field_updated": field}


async def get_benchmark(tenant_id: str, days: int = 30) -> dict:
    """Bayesian lift: P(system CVR > holdout CVR) over the last N days.

    Treatment = Thompson-selected sessions (is_holdout=false).
    Holdout   = randomly-selected sessions (is_holdout=true).
    Lift      = (treatment_cvr - holdout_cvr) / holdout_cvr * 100.
    P(better) = Monte Carlo integral over Beta posteriors (N=10 000).
    """
    pool = await tenant_pool(tenant_id)
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
