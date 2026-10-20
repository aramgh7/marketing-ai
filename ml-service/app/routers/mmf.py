"""
Marketing Mix Funnel + Offer Explorer router (vision §10.4, §19; arch §15.12).
POST /mmf/diagnose
POST /offers/generate
POST /offers/rank
"""
from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.mmf_service import diagnose_mmf, generate_offers, rank_offers
from app.services.offer_schema_service import (
    INVESTOR_RAISE_SCHEMA, generate_offers_from_schema, offer_text_from_atoms, validate_offer_atoms,
)
from app.security import check_internal_key

router = APIRouter()



def _check_key(key: Optional[str]) -> None:
    check_internal_key(key)


class MmfDiagnoseRequest(BaseModel):
    ctr: float = 0.02
    cvr: float = 0.03
    cpc: float = 1.0
    roas: float = 2.0
    impression_share: float = 0.3
    budget_util: float = 0.5
    learning_phase: str = ""
    frequency: float = 1.0
    ctr_benchmark: float = 0.005


class GenerateOffersRequest(BaseModel):
    base_offer: dict
    context: dict = {}


class RankOffersRequest(BaseModel):
    offers: list[dict]
    scores: dict = {}


class SchemaOffersRequest(BaseModel):
    """Roadmap 8-8: Dynamic Offer Explorer over a tenant's offer atom schema."""
    fields: list[dict] = []          # empty = platform INVESTOR_RAISE schema
    base_atoms: dict = {}
    max_variants: int = 8
    urgency_source: Optional[str] = None


class SchemaValidateRequest(BaseModel):
    fields: list[dict] = []
    atoms: dict = {}
    urgency_source: Optional[str] = None


@router.post("/diagnose")
def diagnose(
    req: MmfDiagnoseRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    return diagnose_mmf(req.model_dump())


@router.post("/offers/generate")
def offers_generate(
    req: GenerateOffersRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    return generate_offers(req.base_offer, req.context)


@router.post("/offers/rank")
def offers_rank(
    req: RankOffersRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    return rank_offers(req.offers, req.scores)


@router.post("/offers/generate-from-schema")
def offers_generate_from_schema(
    req: SchemaOffersRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    fields = req.fields or INVESTOR_RAISE_SCHEMA
    cands = generate_offers_from_schema(fields, req.base_atoms, req.max_variants, req.urgency_source)
    for c in cands:
        c["offer_text"] = offer_text_from_atoms(c["atoms"], fields)
    return {"candidates": cands, "n": len(cands)}


@router.post("/offers/validate-schema")
def offers_validate_schema(
    req: SchemaValidateRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    fields = req.fields or INVESTOR_RAISE_SCHEMA
    errors = validate_offer_atoms(req.atoms, fields, req.urgency_source)
    return {"valid": not errors, "errors": errors, "offer_text": offer_text_from_atoms(req.atoms, fields)}
