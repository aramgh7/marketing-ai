"""
Diagnostic analytics endpoint (§3.2).

Phase 1: basic event-based diagnostics (bounce, CVR, funnel drop-off).
Phase 3: will add hook rate, CTR, spend efficiency, anchor comparison,
         power calculator — all requiring Meta API data.
"""
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Header, HTTPException, Query

from app.config import settings
from app.services.db_client import get_pool  # shared pool — P1-1 fix

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Diagnostics"])


def _require_internal_key(x_internal_api_key: str = Header(default="")) -> None:
    """P1-3: protect internal endpoint from public access."""
    if x_internal_api_key != settings.internal_api_key:
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-API-Key")


@router.get("/{tenant_id}")
async def diagnostics(
    tenant_id: str,
    days: int = Query(default=30, ge=1, le=365),
    x_internal_api_key: str = Header(default=""),
):
    """
    Diagnostic card for a tenant (§3.2).
    Protected by X-Internal-API-Key (P1-3 hardening).
    """
    _require_internal_key(x_internal_api_key)

    pool = await get_pool()  # shared pool (P1-1)

    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SET LOCAL app.current_tenant = $1", tenant_id)

            funnel = await conn.fetch(
                """
                SELECT event_type, COUNT(DISTINCT session_id) AS cnt
                FROM events
                WHERE is_suspicious = false
                  AND event_time >= NOW() - $1 * INTERVAL '1 day'
                  AND event_type IN ('PAGE_VIEW','SCROLL','FORM_START','FORM_SUBMIT','CONVERSION','PURCHASE')
                GROUP BY event_type
                """,
                days,
            )

            # P1-2: unified bounce logic — matches AnalyticsService.java:
            # bounced = single-event sessions (event_count = 1) with no scroll.
            # A multi-event session (user scrolled several pages but no scroll event) is NOT bounced.
            bounce = await conn.fetchrow(
                """
                SELECT
                    COUNT(DISTINCT s.session_id)
                        FILTER (WHERE s.event_count = 1 AND NOT s.has_scroll) AS bounced,
                    COUNT(DISTINCT s.session_id)                               AS total
                FROM (
                    SELECT
                        session_id,
                        COUNT(*)                          AS event_count,
                        BOOL_OR(event_type = 'SCROLL')   AS has_scroll
                    FROM events
                    WHERE is_suspicious = false
                      AND event_time >= NOW() - $1 * INTERVAL '1 day'
                    GROUP BY session_id
                ) s
                """,
                days,
            )

            atom_coverage = await conn.fetchrow(
                """
                SELECT
                    COUNT(*)                             AS total_creatives,
                    COUNT(*) FILTER (WHERE status = 'ACTIVE') AS analyzed
                FROM creatives
                WHERE status != 'ARCHIVED'
                """,
            )

    counts = {row["event_type"]: row["cnt"] for row in funnel}
    page_views  = counts.get("PAGE_VIEW",  0)
    scrolls     = counts.get("SCROLL",     0)
    form_starts = counts.get("FORM_START", 0)
    form_subs   = counts.get("FORM_SUBMIT", 0)
    conversions = counts.get("CONVERSION", 0) + counts.get("PURCHASE", 0)

    total_sess  = bounce["total"] or 0
    bounced     = bounce["bounced"] or 0
    bounce_rate = bounced / total_sess if total_sess else 0.0
    cvr         = conversions / total_sess if total_sess else 0.0

    symptoms = []

    symptoms.append({
        "code": "HIGH_BOUNCE",
        "detected": page_views > 50 and bounce_rate > 0.60,
        "value": f"{bounce_rate:.0%}",
        "diagnosis": "Message mismatch: creative promise ≠ landing reality (§3.2)",
        "action": "Sync landing headline with creative hook atom",
        "data_source": "first-party events",
    })

    symptoms.append({
        "code": "HIGH_FORM_ABANDON",
        "detected": form_starts > 20 and form_subs < form_starts * 0.5,
        "value": f"{form_subs / form_starts:.0%} complete" if form_starts else "n/a",
        "diagnosis": "Form friction: too many fields or steps",
        "action": "Reduce form fields, add progress indicator",
        "data_source": "first-party events",
    })

    symptoms.append({
        "code": "LOW_CVR",
        "detected": total_sess > 100 and cvr < 0.01,
        "value": f"{cvr:.2%}",
        "diagnosis": "Offer or trust issue: visitors scroll but don't convert",
        "action": "Review offer atoms (proof_type, cta) and landing blocks",
        "data_source": "first-party events",
    })

    symptoms.append({
        "code": "WEAK_CTA",
        "detected": scrolls > 50 and form_starts < scrolls * 0.20,
        "value": f"{form_starts / scrolls:.0%} reach form" if scrolls else "n/a",
        "diagnosis": "Weak CTA: users scroll but don't start the form",
        "action": "Test CTA atom variants; strengthen offer",
        "data_source": "first-party events",
    })

    # Phase 3 placeholders — require Meta API
    for code, diag, action in [
        ("LOW_HOOK_RATE",
         "Hook doesn't grab attention in first 3 seconds",
         "Rotate hook atom — requires Meta 3-sec view data"),
        ("LOW_CTR",
         "Weak CTA or offer in creative",
         "Test CTA and offer sub-atoms — requires Meta CTR data"),
        ("CREATIVE_FATIGUE",
         "Creative frequency rising, hook rate dropping",
         "Rotate creative — requires Meta frequency + hook rate data"),
    ]:
        symptoms.append({
            "code": code,
            "detected": False,
            "value": "n/a — needs Meta API (Phase 3)",
            "diagnosis": diag,
            "action": action,
            "data_source": "Meta API (Phase 3)",
        })

    total_creatives = atom_coverage["total_creatives"] or 0
    analyzed        = atom_coverage["analyzed"] or 0

    return {
        "tenant_id": tenant_id,
        "period_days": days,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "symptoms_detected": sum(1 for s in symptoms if s["detected"]),
        "symptoms": symptoms,
        "creative_coverage": {
            "total": total_creatives,
            "analyzed": analyzed,
            "pending": total_creatives - analyzed,
        },
        "note": (
            "Phase 1 diagnostics: based on first-party event data. "
            "Hook Rate, CTR, and spend metrics require Meta API integration (Phase 3)."
        ),
    }
