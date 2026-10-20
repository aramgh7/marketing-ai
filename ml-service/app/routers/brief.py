"""
Creator Brief Generator (§5.5).

Phase 1: template-based, atom suggestions from descriptive stats.
Phase 3-4: data-driven from model posteriors + Ad Library + failure graveyard.

Endpoints:
  POST /brief/generate             — Phase 1 template brief (descriptive stats)
  POST /brief/generate-data-driven — Phase 4 full brief (posteriors + graveyard + references)
"""
import json
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.config import settings
from app.services.db_client import tenant_pool  # RLS-scoped (roadmap 9-4)
from app.security import check_internal_key

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Brief"])


def _require_internal_key(x_internal_api_key: str) -> None:
    check_internal_key(x_internal_api_key)


class GenerateBriefRequest(BaseModel):
    tenant_id: str
    creative_type: str = "VIDEO"   # VIDEO | IMAGE | CAROUSEL
    target_audience: str | None = None
    product_info: str | None = None
    days: int = 90


@router.post("/generate")
async def generate_brief(
    req: GenerateBriefRequest,
    x_internal_api_key: str = Header(default=""),
):
    """
    Generate a Creator Brief template with atom suggestions from descriptive stats.
    Protected by X-Internal-API-Key (P1-3 hardening).
    """
    _require_internal_key(x_internal_api_key)

    pool = await tenant_pool(req.tenant_id)

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.current_tenant', $1, true)", req.tenant_id)

            atoms = await conn.fetch(
                """
                SELECT
                    ca.atom_type,
                    ca.atom_value,
                    COUNT(DISTINCT ca.creative_id)                                    AS creative_count,
                    COALESCE(SUM(e.sessions), 0)                                      AS total_sessions,
                    COALESCE(SUM(e.conversions), 0)                                   AS conversions,
                    CASE WHEN COALESCE(SUM(e.sessions), 0) > 0
                         THEN COALESCE(SUM(e.conversions), 0)::FLOAT / SUM(e.sessions)
                         ELSE NULL END                                                AS cvr
                FROM creative_atoms ca
                JOIN creatives c ON c.id = ca.creative_id AND c.status != 'ARCHIVED'
                LEFT JOIN (
                    SELECT
                        creative_id,
                        COUNT(DISTINCT session_id)                                        AS sessions,
                        COUNT(*) FILTER (WHERE event_type IN ('CONVERSION','PURCHASE'))  AS conversions
                    FROM events
                    WHERE is_suspicious = false
                      AND event_time >= NOW() - $1 * INTERVAL '1 day'
                    GROUP BY creative_id
                ) e ON e.creative_id = ca.creative_id
                GROUP BY ca.atom_type, ca.atom_value
                ORDER BY cvr DESC NULLS LAST, total_sessions DESC
                """,
                req.days,
            )

            overview = await conn.fetchrow(
                """
                SELECT
                    COUNT(DISTINCT session_id) AS total_sessions,
                    COUNT(*) FILTER (WHERE event_type IN ('CONVERSION','PURCHASE')) AS conversions
                FROM events
                WHERE is_suspicious = false
                  AND event_time >= NOW() - $1 * INTERVAL '1 day'
                """,
                req.days,
            )

    total_sess = overview["total_sessions"] or 0
    total_conv = overview["conversions"] or 0
    avg_cvr    = total_conv / total_sess if total_sess else 0.0

    # Top atoms (≥2 creatives, CVR not null)
    recommended = [
        {
            "atomType": r["atom_type"],
            "atomValue": r["atom_value"],
            "cvr": f"{r['cvr']:.2%}" if r["cvr"] is not None else "n/a",
            "creativeCount": r["creative_count"],
            "rationale": "Top CVR performer in this account",
        }
        for r in atoms
        if r["creative_count"] >= 2 and r["cvr"] is not None
    ][:10]

    # Avoid: enough sessions but CVR < 50% of average
    avoid = [
        {
            "atomType": r["atom_type"],
            "atomValue": r["atom_value"],
            "cvr": f"{r['cvr']:.2%}",
            "creativeCount": r["creative_count"],
            "rationale": "Below-average CVR — avoid or test alternatives",
        }
        for r in reversed(list(atoms))
        if r["creative_count"] >= 2
           and r["cvr"] is not None
           and r["total_sessions"] >= 50
           and r["cvr"] < avg_cvr * 0.5
    ][:5]

    top_hook = next(
        (r["atom_value"] for r in atoms if r["atom_type"] == "HOOK" and r["cvr"]),
        "n/a"
    )

    return {
        "tenant_id": req.tenant_id,
        "creative_type": req.creative_type,
        "target_audience": req.target_audience,
        "product_info": req.product_info,
        "structure_template": "Hook (3s) → Problem (10s) → Solution (15s) → Proof (15s) → CTA (5s)",
        "recommended_atoms": recommended,
        "avoid_atoms": avoid,
        "benchmarks": {
            "cvr_avg": f"{avg_cvr:.2%}" if total_sess else "n/a",
            "top_hook": top_hook,
            "data_period_days": req.days,
            "note": (
                "Phase 1: descriptive stats from first-party events. "
                "Causal benchmarks available in Phase 3."
            ),
        },
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "phase_note": (
            "Phase 1 template brief. "
            "Phase 3-4: data-driven from model posteriors, Ad Library, failure graveyard."
        ),
    }


# --------------------------------------------------------------------------- #
# Phase 4: Data-Driven Creator Brief (§5.5.2 full version)
# --------------------------------------------------------------------------- #

class DataDrivenBriefRequest(BaseModel):
    tenant_id: str
    creative_type: str = "VIDEO"
    target_audience: str | None = None
    product_info: str | None = None
    days: int = 90
    include_free_concept_slot: bool = True   # §5.5.3 mandatory free concept


@router.post("/generate-data-driven")
async def generate_data_driven_brief(
    req: DataDrivenBriefRequest,
    x_internal_api_key: str = Header(default=""),
):
    """
    Phase 4 full data-driven Creator Brief (§5.5.2).

    Sources:
    - Model posteriors (Thompson alpha/beta per arm + GLM atom posteriors)
    - Failure graveyard (archived creatives with low CVR)
    - Competitive intelligence (Ad Library references)
    - Benchmarks from own model data

    Protected by X-Internal-API-Key.
    """
    _require_internal_key(x_internal_api_key)

    pool = await tenant_pool(req.tenant_id)

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.current_tenant', $1, true)", req.tenant_id)

            # 1. Top atoms from posteriors (α − β > 0 indicates positive signal)
            top_atoms = await conn.fetch(
                """
                SELECT
                    ca.atom_type,
                    ca.atom_value,
                    ROUND(AVG(ta.alpha)::numeric, 2)           AS avg_alpha,
                    ROUND(AVG(ta.beta)::numeric, 2)            AS avg_beta,
                    ROUND(AVG(ta.alpha / NULLIF(ta.alpha + ta.beta, 0))::numeric, 4) AS est_cvr,
                    COUNT(DISTINCT ta.variant_id)              AS arm_count
                FROM creative_atoms ca
                JOIN creatives c ON c.id = ca.creative_id AND c.status = 'ACTIVE'
                JOIN landing_variants lv ON lv.creative_id = c.id AND lv.status = 'ACTIVE'
                JOIN thompson_arms ta ON ta.variant_id = lv.id
                GROUP BY ca.atom_type, ca.atom_value
                HAVING COUNT(DISTINCT ta.variant_id) >= 2
                ORDER BY est_cvr DESC NULLS LAST
                LIMIT 10
                """,
            )

            # 2. Failure graveyard: archived creatives with evidence of low performance
            graveyard = await conn.fetch(
                """
                SELECT
                    c.id,
                    c.title,
                    COALESCE(ca_atoms.atoms, '[]'::jsonb) AS atoms,
                    COALESCE(s.sessions, 0)               AS sessions,
                    COALESCE(s.conversions, 0)            AS conversions,
                    CASE WHEN COALESCE(s.sessions, 0) > 0
                         THEN ROUND((s.conversions::numeric / s.sessions), 4)
                         ELSE NULL END                    AS cvr
                FROM creatives c
                LEFT JOIN (
                    SELECT
                        e.creative_id,
                        COUNT(DISTINCT e.session_id)                                    AS sessions,
                        COUNT(*) FILTER (WHERE e.event_type IN ('CONVERSION','PURCHASE')) AS conversions
                    FROM events e
                    WHERE e.is_suspicious = false
                      AND e.event_time >= NOW() - $1 * INTERVAL '1 day'
                    GROUP BY e.creative_id
                ) s ON s.creative_id = c.id
                LEFT JOIN (
                    SELECT ca.creative_id,
                           jsonb_agg(jsonb_build_object('type', ca.atom_type, 'value', ca.atom_value)) AS atoms
                    FROM creative_atoms ca
                    GROUP BY ca.creative_id
                ) ca_atoms ON ca_atoms.creative_id = c.id
                WHERE c.status = 'ARCHIVED'
                  AND COALESCE(s.sessions, 0) >= 50
                  AND (s.conversions::numeric / NULLIF(s.sessions, 0)) < 0.005
                ORDER BY s.sessions DESC
                LIMIT 5
                """,
                req.days,
            )

            # 3. Ad Library competitive references (from competitive_intel_alerts)
            ad_refs = await conn.fetch(
                """
                SELECT ca2.competitor_page_id,
                       ca2.ad_archive_id,
                       ca2.started_running_at,
                       ca2.atoms,
                       ca2.counter_hypotheses
                FROM competitor_ads ca2
                WHERE ca2.days_running >= 14
                ORDER BY ca2.days_running DESC
                LIMIT 5
                """,
            )

            # 4. Benchmark CVR from own data
            benchmark = await conn.fetchrow(
                """
                SELECT
                    ROUND(AVG(ta.alpha / NULLIF(ta.alpha + ta.beta, 0))::numeric, 4) AS avg_cvr,
                    MAX(ta.alpha / NULLIF(ta.alpha + ta.beta, 0))                    AS best_cvr,
                    COUNT(DISTINCT ta.variant_id)                                    AS active_arms
                FROM thompson_arms ta
                WHERE ta.alpha + ta.beta > 20
                """,
            )

    # --- Build recommended atoms section ---
    recommended = [
        {
            "atomType": r["atom_type"],
            "atomValue": r["atom_value"],
            "estimatedCvr": float(r["est_cvr"]) if r["est_cvr"] else None,
            "armCount": r["arm_count"],
            "alpha": float(r["avg_alpha"]),
            "beta": float(r["avg_beta"]),
            "source": "model_posteriors",
        }
        for r in top_atoms
    ]

    # --- Build failure graveyard section ---
    anti_patterns = [
        {
            "creativeTitle": r["title"],
            "sessions": r["sessions"],
            "cvr": float(r["cvr"]) if r["cvr"] else None,
            "atoms": r["atoms"],
            "reason": "High volume, near-zero CVR — avoid these atom combinations",
        }
        for r in graveyard
    ]

    # --- Build competitive references ---
    references = [
        {
            "competitorPageId": r["competitor_page_id"],
            "adArchiveId": r["ad_archive_id"],
            "daysRunning": (
                (datetime.now(timezone.utc) - r["started_running_at"]).days
                if r["started_running_at"] else None
            ),
            "extractedAtoms": r["atoms"],
            "counterHypotheses": r["counter_hypotheses"],
            "note": "Ad Library proxy: longevity ≥14d suggests profitability. No CTR/CVR data available.",
        }
        for r in ad_refs
    ] if ad_refs else []

    # --- LLM brief synthesis (if API key available) ---
    llm_brief_text = None
    if settings.anthropic_api_key and recommended:
        llm_brief_text = await _synthesize_brief_llm(req, recommended, anti_patterns, references)

    avg_cvr = float(benchmark["avg_cvr"]) if benchmark and benchmark["avg_cvr"] else None
    best_cvr = float(benchmark["best_cvr"]) if benchmark and benchmark["best_cvr"] else None

    return {
        "tenant_id": req.tenant_id,
        "creative_type": req.creative_type,
        "target_audience": req.target_audience,
        "product_info": req.product_info,
        "structure_template": "Hook (0-3s) → Problem (3-12s) → Solution (12-22s) → Proof (22-27s) → CTA (27-30s)",
        "recommended_atoms": recommended,
        "anti_patterns": anti_patterns,
        "competitive_references": references,
        "benchmarks": {
            "avg_cvr": f"{avg_cvr:.2%}" if avg_cvr else "n/a",
            "best_cvr": f"{best_cvr:.2%}" if best_cvr else "n/a",
            "active_arms": benchmark["active_arms"] if benchmark else 0,
            "data_period_days": req.days,
            "source": "model_posteriors",
        },
        "llm_brief": llm_brief_text,
        "free_concept_slot": {   # §5.5.3: mandatory free concept slot
            "instructions": (
                "This slot is for YOUR creative idea — outside the data-driven recommendations above. "
                "What angle, emotion, or format haven't we tested yet? "
                "The system tracks free-concept results separately to learn from creator intuition."
            ),
            "field": "[Creator fills in their own concept here]",
        },
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "phase": "4 — data-driven brief from posteriors + graveyard + Ad Library",
    }


async def _synthesize_brief_llm(
    req: DataDrivenBriefRequest,
    recommended: list[dict],
    anti_patterns: list[dict],
    references: list[dict],
) -> str:
    """Use Claude to write a narrative brief from structured data."""
    import anthropic
    client = anthropic.Anthropic(api_key=settings.anthropic_api_key)

    top_atoms_text = ", ".join(
        f"{a['atomType']}={a['atomValue']} (CVR~{a['estimatedCvr']:.1%})"
        for a in recommended[:5]
        if a["estimatedCvr"]
    )
    avoid_text = "; ".join(
        f"{ap['creativeTitle']} (CVR~{ap['cvr']:.2%})" if ap["cvr"] else ap["creativeTitle"]
        for ap in anti_patterns[:3]
    )

    prompt = f"""Write a concise creator brief for a {req.creative_type} ad.
Product: {req.product_info or 'not specified'}
Audience: {req.target_audience or 'not specified'}

Data from our model:
- Top-performing atoms: {top_atoms_text or 'no data yet'}
- Avoid (poor performers): {avoid_text or 'none identified yet'}
- Competitive references: {len(references)} competitor ads with 14+ day run time

Write a 200-300 word brief covering:
1. Hook (0-3s): what to say/show, why this atom works
2. Body structure (3-27s): key messages, tone, proof elements
3. CTA (27-30s): exact text suggestion
4. ❌ Don't do: top 2-3 things to avoid with data rationale
5. 📎 References: brief mention of competitor format signals

Use practical, actionable language. No fluff."""

    try:
        msg = client.messages.create(
            model="claude-sonnet-5-5",
            max_tokens=600,
            messages=[{"role": "user", "content": prompt}],
        )
        return msg.content[0].text.strip()
    except Exception:
        logger.exception("LLM brief synthesis failed")
        return None
