"""Expected Value of Information ranking (roadmap 9-10; vision §6.4).

POST /decide/evi  — rank arms by the expected gain from n_extra more sessions and split an
                    exploration budget proportionally (5 % floor per arm).
Registered in main.py as: app.include_router(evi.router, prefix="/decide/evi", dependencies=_internal)
"""
from __future__ import annotations

from typing import Optional

from fastapi import APIRouter
from pydantic import BaseModel

from app.services.evi_service import expected_value_of_information, rank_experiments

router = APIRouter(tags=["evi"])


class EviArm(BaseModel):
    id: Optional[str] = None
    arm_id: Optional[str] = None
    variant_id: Optional[str] = None
    alpha: float
    beta: float


class EviRequest(BaseModel):
    arms: list[EviArm]
    n_extra: int = 100
    budget_sessions: int = 0
    n_mc: int = 2000


@router.post("/")
@router.post("")
async def evi(req: EviRequest) -> dict:
    arms = []
    for a in req.arms:
        d = a.model_dump()
        d["id"] = d.get("id") or d.get("arm_id")
        arms.append(d)
    ranked = expected_value_of_information(arms, n_extra=req.n_extra, n_mc=req.n_mc)
    allocation = rank_experiments(ranked, req.budget_sessions) if req.budget_sessions > 0 else []
    return {"ranked": ranked, "allocation": allocation, "n_extra": req.n_extra}
