"""
Propensity Router (Phase 5-2, §7.3 Этап 2).

Endpoints:
  POST /propensity/score — score a single session (rule-based or trained model)
  POST /propensity/fit   — fit GradientBoosting model on tenant sessions
"""
from typing import Any

import numpy as np
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.security import sign_model_bytes, verify_model_signature
from app.services.cluster_service import extract_features, FEATURE_NAMES
from app.services.propensity_service import (
    extract_propensity_features,
    score_session,
    fit_propensity_model,
    compute_uplift_score,
    N_PROPENSITY_FEATURES,
)

router = APIRouter(tags=["propensity"])


# ──────────────────────────────────────────────────────────────────────────────
# Request / Response models
# ──────────────────────────────────────────────────────────────────────────────

class ScoreRequest(BaseModel):
    tenant_id:       str
    session:         dict[str, Any]
    events:          list[dict[str, Any]] = []
    cluster_id:      int = -1
    model_b64:       str | None = None   # base64-encoded joblib bytes (optional)
    model_sig:       str | None = None   # HMAC from /fit — required when model_b64 is given


class ScoreResponse(BaseModel):
    p_convert:       float
    main_objection:  str | None = None
    uplift_score:    float = 0.0


class FitRequest(BaseModel):
    tenant_id:   str
    sessions:    list[dict[str, Any]]    # each: {session, events, cluster_id, converted}
    n_estimators: int = 200
    max_depth:    int = 4
    learning_rate: float = 0.05


class FitResponse(BaseModel):
    tenant_id:      str
    n_samples:      int
    n_positives:    int
    model_b64:      str                  # base64-encoded joblib bytes for storage
    model_sig:      str                  # HMAC-SHA256(internal key, bytes) — store next to model_b64


# ──────────────────────────────────────────────────────────────────────────────
# Endpoints
# ──────────────────────────────────────────────────────────────────────────────

@router.post("/score", response_model=ScoreResponse)
def score(req: ScoreRequest) -> ScoreResponse:
    """Score a single session — returns P(convert), main objection, uplift."""
    cluster_feats = extract_features(req.events, req.session)
    prop_feats    = extract_propensity_features(cluster_feats, req.cluster_id)

    model_bytes: bytes | None = None
    if req.model_b64:
        import base64
        model_bytes = base64.b64decode(req.model_b64)
        # Audit §3.4: unpickling caller-supplied bytes is RCE — accept only bytes this service signed.
        if not verify_model_signature(model_bytes, req.model_sig):
            raise HTTPException(status_code=400, detail="model_b64 requires a valid model_sig from /propensity/fit")

    result = score_session(prop_feats, model_bytes)

    return ScoreResponse(
        p_convert=result["p_convert"],
        main_objection=result["main_objection"],
        uplift_score=0.0,
    )


@router.post("/fit", response_model=FitResponse)
def fit(req: FitRequest) -> FitResponse:
    """
    Fit GradientBoosting propensity model on pre-filtered session batch.

    Caller must pre-filter: consent_level='full', is_suspicious=false.
    Each session in sessions must have 'converted' (0/1) label.
    """
    import base64

    if not req.sessions:
        raise HTTPException(400, "sessions list is empty")

    X_rows, y_vals = [], []
    for rec in req.sessions:
        sess      = rec.get("session", {})
        events    = rec.get("events",  [])
        clust_id  = int(rec.get("cluster_id", -1))
        converted = int(rec.get("converted",  0))

        cluster_feats = extract_features(events, sess)
        prop_feats    = extract_propensity_features(cluster_feats, clust_id)
        X_rows.append(prop_feats)
        y_vals.append(converted)

    X = np.stack(X_rows, axis=0)
    y = np.array(y_vals, dtype=np.int32)

    model_bytes = fit_propensity_model(
        X, y,
        n_estimators=req.n_estimators,
        max_depth=req.max_depth,
        learning_rate=req.learning_rate,
    )

    return FitResponse(
        tenant_id=req.tenant_id,
        n_samples=len(y),
        n_positives=int(y.sum()),
        model_b64=base64.b64encode(model_bytes).decode(),
        model_sig=sign_model_bytes(model_bytes),
    )
