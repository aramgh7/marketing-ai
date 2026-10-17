"""
Taxonomy Alignment Layer router (arch §15.10).
POST /taxonomy/align     — align a creative atom to the registry
POST /taxonomy/recalibrate — weekly merge + confirm pass
"""
from __future__ import annotations

import logging
import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.taxonomy_service import (
    get_embedding, build_alignment_result, update_centroid,
    should_confirm, COSINE_MERGE_THRESHOLD, cosine_similarity,
    parse_pgvector, centroid_to_pgvector,
)
from app.services.db_client import get_pool

logger = logging.getLogger(__name__)
router = APIRouter(tags=["taxonomy"])

INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")


def _check_key(key: Optional[str]) -> None:
    if INTERNAL_KEY and key != INTERNAL_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


class AlignRequest(BaseModel):
    atom_type: str
    atom_value: str
    tenant_id: str


class AlignResponse(BaseModel):
    registry_id: Optional[str]
    alignment_sim: float
    is_new: bool


class RecalibrateResponse(BaseModel):
    merged: int
    confirmed: int
    candidates_reviewed: int


@router.post("/align", response_model=AlignResponse)
async def align_atom(
    req: AlignRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
) -> AlignResponse:
    """Align a creative atom to the registry; create CANDIDATE if no match."""
    _check_key(x_internal_api_key)
    pool = await get_pool()
    api_key = os.getenv("OPENAI_API_KEY")
    embedding = get_embedding(req.atom_value, api_key=api_key)

    async with pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT id::text, centroid::text FROM atom_registry "
            "WHERE atom_type = $1 AND merged_into IS NULL",
            req.atom_type,
        )
        candidates = []
        for row in rows:
            vec = parse_pgvector(row["centroid"])
            if vec:
                candidates.append({"id": row["id"], "centroid": vec})

        result = build_alignment_result(req.atom_type, req.atom_value, embedding, candidates)

        if result["is_new"]:
            emb_str = centroid_to_pgvector(embedding)
            registry_id = await conn.fetchval(
                """INSERT INTO atom_registry
                       (atom_type, canonical_value, centroid, appearance_count, tenant_count)
                   VALUES ($1, $2, $3::vector, 1, 1)
                   RETURNING id::text""",
                req.atom_type, req.atom_value, emb_str,
            )
            result["registry_id"] = registry_id
        else:
            rid = result["registry_id"]
            row_data = await conn.fetchrow(
                "SELECT appearance_count, centroid::text FROM atom_registry WHERE id = $1::uuid",
                rid,
            )
            n = (row_data["appearance_count"] or 0) + 1
            old_vec = parse_pgvector(row_data["centroid"]) or embedding
            new_centroid = update_centroid(old_vec, embedding, n)
            await conn.execute(
                """UPDATE atom_registry
                   SET centroid = $1::vector,
                       appearance_count = appearance_count + 1,
                       updated_at = now()
                   WHERE id = $2::uuid""",
                centroid_to_pgvector(new_centroid), rid,
            )
            tenant_count_row = await conn.fetchrow(
                "SELECT tenant_count FROM atom_registry WHERE id = $1::uuid", rid,
            )
            if should_confirm(n, tenant_count_row["tenant_count"] or 0):
                await conn.execute(
                    "UPDATE atom_registry SET status = 'CONFIRMED' "
                    "WHERE id = $1::uuid AND status = 'CANDIDATE'",
                    rid,
                )

    return AlignResponse(
        registry_id=result["registry_id"],
        alignment_sim=result["alignment_sim"],
        is_new=result["is_new"],
    )


@router.post("/recalibrate", response_model=RecalibrateResponse)
async def recalibrate(
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
) -> RecalibrateResponse:
    """Weekly: merge near-duplicate centroids, confirm mature candidates."""
    _check_key(x_internal_api_key)
    pool = await get_pool()
    merged = 0
    confirmed = 0

    async with pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT id::text, atom_type, canonical_value, centroid::text, "
            "appearance_count, tenant_count, status "
            "FROM atom_registry WHERE merged_into IS NULL"
        )
        entries = []
        for row in rows:
            entries.append({
                "id": row["id"],
                "atom_type": row["atom_type"],
                "centroid": parse_pgvector(row["centroid"]),
                "appearance_count": row["appearance_count"] or 0,
                "tenant_count": row["tenant_count"] or 0,
                "status": row["status"],
            })

        by_type: dict[str, list] = {}
        for e in entries:
            by_type.setdefault(e["atom_type"], []).append(e)

        merge_pairs: list[tuple[str, str]] = []
        for group in by_type.values():
            for i in range(len(group)):
                for j in range(i + 1, len(group)):
                    a, b = group[i], group[j]
                    if a["centroid"] and b["centroid"]:
                        sim = cosine_similarity(a["centroid"], b["centroid"])
                        if sim >= COSINE_MERGE_THRESHOLD:
                            # Keep higher-count; merge lower into it
                            if a["appearance_count"] >= b["appearance_count"]:
                                merge_pairs.append((b["id"], a["id"]))
                            else:
                                merge_pairs.append((a["id"], b["id"]))

        for src_id, dst_id in merge_pairs:
            await conn.execute(
                "UPDATE atom_registry SET merged_into = $1::uuid WHERE id = $2::uuid",
                dst_id, src_id,
            )
            await conn.execute(
                "UPDATE creative_atoms SET registry_id = $1::uuid WHERE registry_id = $2::uuid",
                dst_id, src_id,
            )
            merged += 1

        for e in entries:
            if e["status"] == "CANDIDATE" and should_confirm(
                e["appearance_count"], e["tenant_count"]
            ):
                await conn.execute(
                    "UPDATE atom_registry SET status = 'CONFIRMED' "
                    "WHERE id = $1::uuid AND status = 'CANDIDATE'",
                    e["id"],
                )
                confirmed += 1

    logger.info("Taxonomy recalibrate: merged=%d confirmed=%d", merged, confirmed)
    return RecalibrateResponse(
        merged=merged, confirmed=confirmed, candidates_reviewed=len(entries)
    )
