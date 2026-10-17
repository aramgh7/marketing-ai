"""
Cluster Router (Phase 5-1, §7.3 Этап 1).

Endpoints:
  POST /cluster/fit      — fit K-means on tenant sessions (weekly batch trigger)
  GET  /cluster/labels/{tenant_id} — return stored cluster labels
  POST /cluster/assign   — assign a single session to nearest cluster
"""
import json
from typing import Any

import numpy as np
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.services.cluster_service import (
    N_FEATURES,
    FEATURE_NAMES,
    RULE_ARCHETYPES,
    extract_features,
    fit_clusters,
    assign_cluster,
    rule_based_label,
    personalise_block_order,
    compute_ips_weight,
    validate_ips_coverage,
)

router = APIRouter(tags=["cluster"])


# ──────────────────────────────────────────────────────────────────────────────
# Request / Response models
# ──────────────────────────────────────────────────────────────────────────────

class SessionRecord(BaseModel):
    session: dict[str, Any]
    events:  list[dict[str, Any]] = []


class FitRequest(BaseModel):
    tenant_id: str
    sessions:  list[SessionRecord]       # must have consent_level=full, is_suspicious=false
    n_clusters: int = 7


class FitResponse(BaseModel):
    tenant_id:    str
    n_clusters:   int
    centroids:    list[dict[str, Any]]
    cluster_labels: list[str]            # rule-based label per centroid


class AssignRequest(BaseModel):
    tenant_id:       str
    session:         dict[str, Any]
    events:          list[dict[str, Any]] = []
    centroid_dicts:  list[dict[str, Any]]   # from FitResponse.centroids


class AssignResponse(BaseModel):
    cluster_idx:   int
    rule_label:    str
    block_order:   list[str] | None = None


class PersonaliseRequest(BaseModel):
    tenant_id:        str
    session:          dict[str, Any]
    events:           list[dict[str, Any]] = []
    centroid_dicts:   list[dict[str, Any]]
    cluster_labels:   list[str]               # labels indexed by cluster_idx
    default_block_order: list[str]
    allowed_block_ids: list[str] | None = None


class IpsCoverageRequest(BaseModel):
    propensities: list[float]
    min_effective_n: int = 100


# ──────────────────────────────────────────────────────────────────────────────
# Endpoints
# ──────────────────────────────────────────────────────────────────────────────

@router.post("/fit", response_model=FitResponse)
def fit(req: FitRequest) -> FitResponse:
    """
    Fit K-means model on pre-filtered session batch.

    Caller (Spring Boot scheduler) must pre-filter:
      - consent_level = 'full'  (§12.1a)
      - is_suspicious = false   (§12.6.3)
    """
    if not req.sessions:
        raise HTTPException(400, "sessions list is empty")
    if not (5 <= req.n_clusters <= 8):
        raise HTTPException(400, f"n_clusters must be 5–8, got {req.n_clusters}")

    feature_rows = [
        extract_features(sr.events, sr.session) for sr in req.sessions
    ]
    X = np.stack(feature_rows, axis=0)

    centroids = fit_clusters(X, n_clusters=req.n_clusters)

    # Rule-based label for each centroid (LLM labelling done in Spring Boot)
    labels = [rule_based_label(
        np.array([c.get(n, 0.0) for n in FEATURE_NAMES], dtype=np.float32)
    ) for c in centroids]

    return FitResponse(
        tenant_id=req.tenant_id,
        n_clusters=req.n_clusters,
        centroids=centroids,
        cluster_labels=labels,
    )


@router.post("/assign", response_model=AssignResponse)
def assign(req: AssignRequest) -> AssignResponse:
    """Assign a single session to nearest K-means centroid (online, real-time)."""
    features = extract_features(req.events, req.session)
    cluster_idx = assign_cluster(req.centroid_dicts, features)
    label = rule_based_label(features)
    return AssignResponse(cluster_idx=cluster_idx, rule_label=label)


@router.post("/personalise")
def personalise(req: PersonaliseRequest) -> dict:
    """
    Return personalised block order for a session.
    Only reorders blocks in allowed_block_ids (§5.4.1 — no new content generated).
    """
    features = extract_features(req.events, req.session)
    cluster_idx = assign_cluster(req.centroid_dicts, features)

    cluster_label = "researcher"  # safe default
    if 0 <= cluster_idx < len(req.cluster_labels):
        cluster_label = req.cluster_labels[cluster_idx]

    ordered = personalise_block_order(
        req.default_block_order,
        cluster_label,
        req.allowed_block_ids,
    )

    return {
        "cluster_idx":    cluster_idx,
        "cluster_label":  cluster_label,
        "block_order":    ordered,
    }


@router.post("/ips-coverage")
def ips_coverage(req: IpsCoverageRequest) -> dict:
    """Validate off-policy evaluation coverage (§12.5, §7.3 Этап 3)."""
    return validate_ips_coverage(req.propensities, req.min_effective_n)
