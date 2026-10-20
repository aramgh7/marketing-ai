"""
Bayesian GLM model updater — fetches training data from Postgres,
runs PyMC fit in a thread executor, and persists posteriors.

Called by:
  POST /glm/fit        (on-demand, HTTP trigger)
  GlmScheduler         (nightly cron — Phase 3-1 TODO: add FastAPI scheduler)
"""
from __future__ import annotations

import asyncio
import logging
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from uuid import uuid4

import pandas as pd

from app.models.bayesian_glm import GlmDataset, FitResult, fit_all, prepare_dataset
from app.services.db_client import tenant_pool

log = logging.getLogger(__name__)

# One executor shared across the process — PyMC is CPU-bound, limit parallelism
_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="glm-fit")


# ─── Data fetching ───────────────────────────────────────────────────────────

async def _fetch_event_aggregates(tenant_id: str, pool) -> list[dict]:
    """
    Aggregate events per creative: impressions, clicks, conversions, revenues.
    Filters to creatives with >= 50 impressions (MIN_IMPRESSIONS guard).
    """
    rows = await pool.fetch(
        """
        SELECT
            e.creative_id::text                                            AS creative_id,
            COUNT(*)        FILTER (WHERE e.event_type = 'IMPRESSION')    AS impressions,
            COUNT(*)        FILTER (WHERE e.event_type = 'CLICK')         AS clicks,
            COUNT(*)        FILTER (WHERE e.event_type IN ('CONVERSION','PURCHASE')) AS conversions,
            array_agg(e.revenue) FILTER (WHERE e.event_type = 'PURCHASE'
                                           AND e.revenue IS NOT NULL)      AS revenues,
            c.match_score
        FROM   events e
        JOIN   creatives c ON c.id = e.creative_id
        WHERE  e.tenant_id = $1::uuid
        GROUP  BY e.creative_id, c.match_score
        HAVING COUNT(*) FILTER (WHERE e.event_type = 'IMPRESSION') >= 50
        """,
        tenant_id,
    )
    return [dict(r) for r in rows]


async def _fetch_atoms(tenant_id: str, creative_ids: list[str], pool) -> list[dict]:
    """Fetch atoms for the given creatives (one dominant atom per type per creative)."""
    rows = await pool.fetch(
        """
        SELECT DISTINCT ON (creative_id, atom_type)
            creative_id::text AS creative_id,
            atom_type,
            atom_value
        FROM   creative_atoms
        WHERE  tenant_id   = $1::uuid
          AND  creative_id = ANY($2::uuid[])
        ORDER  BY creative_id, atom_type, confidence DESC NULLS LAST
        """,
        tenant_id,
        creative_ids,
    )
    return [dict(r) for r in rows]


async def _fetch_offer_data(tenant_id: str, creative_ids: list[str], pool) -> list[dict]:
    """Fetch offer sub-atoms (offer_type, urgency) via landing_variants."""
    rows = await pool.fetch(
        """
        SELECT
            c.id::text      AS creative_id,
            vo.discount_type AS offer_type,
            vo.has_real_urgency AS has_urgency
        FROM   creatives c
        JOIN   landing_variants lv ON lv.id = c.variant_id
        JOIN   variant_offers   vo ON vo.variant_id = lv.id
        WHERE  c.tenant_id = $1::uuid
          AND  c.id        = ANY($2::uuid[])
        """,
        tenant_id,
        creative_ids,
    )
    return [dict(r) for r in rows]


def _pivot_atoms(atoms: list[dict]) -> pd.DataFrame:
    """Pivot atoms from long → wide format (one row per creative, one col per atom_type)."""
    if not atoms:
        return pd.DataFrame(columns=["creative_id"])
    df = pd.DataFrame(atoms)
    wide = df.pivot(index="creative_id", columns="atom_type", values="atom_value").reset_index()
    return normalise_atom_columns(wide)


from app.services.glm_features import normalise_atom_columns  # noqa: E402


async def _build_dataframe(tenant_id: str, pool) -> pd.DataFrame | None:
    rows = await _fetch_event_aggregates(tenant_id, pool)
    if not rows:
        log.info("GLM[%s]: no event data, skipping fit", tenant_id)
        return None

    df = pd.DataFrame(rows)
    creative_ids = df["creative_id"].tolist()

    atoms_raw   = await _fetch_atoms(tenant_id, creative_ids, pool)
    offer_raw   = await _fetch_offer_data(tenant_id, creative_ids, pool)

    atoms_wide = _pivot_atoms(atoms_raw)
    if not atoms_wide.empty:
        df = df.merge(atoms_wide, on="creative_id", how="left")

    if offer_raw:
        offer_df = pd.DataFrame(offer_raw).drop_duplicates("creative_id")
        df = df.merge(offer_df, on="creative_id", how="left")

    return df


# ─── Persistence ─────────────────────────────────────────────────────────────

async def _insert_run(tenant_id: str, pool) -> str:
    row = await pool.fetchrow(
        """
        INSERT INTO glm_fit_runs (tenant_id)
        VALUES ($1::uuid)
        RETURNING id::text AS id
        """,
        tenant_id,
    )
    return row["id"]


async def _store_posteriors(tenant_id: str, run_id: str, result: FitResult, pool) -> None:
    now = datetime.now(timezone.utc)

    async with pool.acquire() as conn:
        async with conn.transaction():
            for p in result.posteriors:
                await conn.execute(
                    """
                    INSERT INTO glm_posteriors
                        (tenant_id, model_stage, atom_type, atom_value,
                         mean, std, hdi_low, hdi_high, n_obs, fitted_at)
                    VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8, $9, $10)
                    ON CONFLICT (tenant_id, model_stage, atom_type, atom_value)
                    DO UPDATE SET
                        mean       = EXCLUDED.mean,
                        std        = EXCLUDED.std,
                        hdi_low    = EXCLUDED.hdi_low,
                        hdi_high   = EXCLUDED.hdi_high,
                        n_obs      = EXCLUDED.n_obs,
                        fitted_at  = EXCLUDED.fitted_at
                    """,
                    tenant_id, p["stage"], p["atom_type"], p["atom_value"],
                    p["mean"], p["std"], p["hdi_low"], p["hdi_high"],
                    result.n_obs_ctr if p["stage"] == "CTR"
                    else result.n_obs_cvr if p["stage"] == "CVR"
                    else result.n_obs_aov,
                    now,
                )

            await conn.execute(
                """
                UPDATE glm_fit_runs
                SET status      = $1,
                    n_obs_ctr   = $2,
                    n_obs_cvr   = $3,
                    n_obs_aov   = $4,
                    r_hat_max   = $5,
                    finished_at = $6
                WHERE id = $7::uuid
                """,
                "DONE", result.n_obs_ctr, result.n_obs_cvr, result.n_obs_aov,
                result.r_hat_max, now, run_id,
            )


async def _mark_run_failed(run_id: str, error: str, pool) -> None:
    await pool.execute(
        """
        UPDATE glm_fit_runs
        SET status = 'FAILED', error_msg = $1, finished_at = $2
        WHERE id = $3::uuid
        """,
        error[:2000], datetime.now(timezone.utc), run_id,
    )


# ─── Public entry point ───────────────────────────────────────────────────────

async def run_fit(tenant_id: str, draws: int = 1000, tune: int = 1000, chains: int = 2) -> dict:
    """
    Full fit pipeline for one tenant:
      1. Fetch + join training data from Postgres
      2. Build GlmDataset
      3. Run PyMC fit in thread executor (non-blocking)
      4. Persist posteriors + run metadata
    Returns a summary dict suitable for JSON response.
    """
    pool = await tenant_pool(tenant_id)
    run_id = await _insert_run(tenant_id, pool)
    log.info("GLM fit started: tenant=%s run=%s", tenant_id, run_id)

    try:
        df = await _build_dataframe(tenant_id, pool)
        if df is None or df.empty:
            await _mark_run_failed(run_id, "No training data after joins", pool)
            return {"run_id": run_id, "status": "FAILED", "reason": "no data"}

        dataset: GlmDataset | None = prepare_dataset(df)
        if dataset is None:
            await _mark_run_failed(run_id, "prepare_dataset returned None (insufficient rows)", pool)
            return {"run_id": run_id, "status": "FAILED", "reason": "insufficient data"}

        loop = asyncio.get_event_loop()
        result: FitResult = await loop.run_in_executor(
            _executor,
            lambda: fit_all(dataset, draws=draws, tune=tune, chains=chains),
        )

        await _store_posteriors(tenant_id, run_id, result, pool)

        log.info(
            "GLM fit done: tenant=%s run=%s posteriors=%d r_hat_max=%.3f",
            tenant_id, run_id, len(result.posteriors), result.r_hat_max,
        )
        return {
            "run_id": run_id,
            "status": "DONE",
            "n_posteriors": len(result.posteriors),
            "n_obs_ctr": result.n_obs_ctr,
            "n_obs_cvr": result.n_obs_cvr,
            "n_obs_aov": result.n_obs_aov,
            "r_hat_max": round(result.r_hat_max, 4),
        }

    except Exception as e:
        log.exception("GLM fit pipeline error: tenant=%s run=%s", tenant_id, run_id)
        await _mark_run_failed(run_id, str(e), pool)
        return {"run_id": run_id, "status": "FAILED", "reason": str(e)}
