from fastapi import APIRouter
from pydantic import BaseModel, Field
from typing import Any, Dict, List, Optional
from app.services.evolution_service import (
    generate_candidates,
    check_hook_body_coherence,
    check_graveyard_similarity,
    rank_by_glm_posterior,
    should_retry_graveyard_entry,
    apply_diversity_penalty,
)

router = APIRouter(tags=["evolve"])


class Arm(BaseModel):
    id:    Optional[str] = None
    alpha: float = Field(1.0, ge=0)
    beta:  float = Field(1.0, ge=0)
    atoms: List[Dict[str, Any]] = []


class EvolveRequest(BaseModel):
    arms:          List[Arm]
    atom_pool:     List[Dict[str, Any]]
    n_candidates:  int   = Field(5, ge=1, le=20)
    mutation_rate: float = Field(0.15, ge=0.0, le=1.0)


@router.post("/candidates")
def evolve_candidates(req: EvolveRequest):
    arms_dicts = [a.model_dump() for a in req.arms]
    candidates = generate_candidates(
        arms_dicts,
        req.atom_pool,
        req.n_candidates,
        req.mutation_rate,
    )
    return {"candidates": candidates}


class CoherenceRequest(BaseModel):
    hook_embedding: List[float]
    body_embedding: List[float]
    threshold: float = Field(0.65, ge=0.0, le=1.0)


class GraveyardCheckRequest(BaseModel):
    candidate_embedding: List[float]
    graveyard_embeddings: List[Dict[str, Any]]
    candidate_audience: str
    theta: float = Field(0.85, ge=0.0, le=1.0)


class GlmRankRequest(BaseModel):
    candidates: List[Dict[str, Any]]
    n_samples: int = Field(1000, ge=100, le=10000)


class GraveyardRetryRequest(BaseModel):
    anchor_verdict: Optional[str] = None
    fraud_flag: bool = False
    site_health_incident: bool = False


@router.post("/coherence-check")
def coherence_check(req: CoherenceRequest):
    return check_hook_body_coherence(req.hook_embedding, req.body_embedding, req.threshold)


@router.post("/graveyard-check")
def graveyard_check(req: GraveyardCheckRequest):
    return check_graveyard_similarity(
        req.candidate_embedding, req.graveyard_embeddings,
        req.candidate_audience, req.theta,
    )


@router.post("/rank-candidates")
def rank_candidates(req: GlmRankRequest):
    ranked = rank_by_glm_posterior(req.candidates, req.n_samples)
    return {"ranked_candidates": ranked}


@router.post("/graveyard-review")
def graveyard_review(req: GraveyardRetryRequest):
    return should_retry_graveyard_entry({
        "anchor_verdict":       req.anchor_verdict,
        "fraud_flag":           req.fraud_flag,
        "site_health_incident": req.site_health_incident,
    })


# ── Rank with Diversity (MMR) ─────────────────────────────────────────────────

class DiversityRankRequest(BaseModel):
    candidates: list[dict]
    lambda_: float = Field(0.3, ge=0.0, le=1.0, alias="lambda")

    model_config = {"populate_by_name": True}


@router.post("/rank-with-diversity")
def rank_with_diversity(req: DiversityRankRequest):
    scored   = rank_by_glm_posterior(req.candidates)
    reranked = apply_diversity_penalty(scored, req.lambda_)
    return {"candidates": reranked}
