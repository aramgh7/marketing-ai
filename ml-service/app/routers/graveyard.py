from fastapi import APIRouter
from pydantic import BaseModel, Field
from typing import Any, Dict, List, Optional
from app.services.graveyard_service import analyze_failure, generate_postmortem_summary

router = APIRouter(tags=["graveyard"])


class PostmortemRequest(BaseModel):
    creative_id:          str
    atoms:                List[Dict[str, Any]]
    sessions:             int   = Field(..., ge=0)
    cvr:                  float = Field(..., ge=0)
    benchmark_cvr:        float = Field(0.02, ge=0)
    ctr:                  Optional[float] = None
    top_performing_atoms: List[Dict[str, Any]] = []


@router.post("/postmortem")
def postmortem(req: PostmortemRequest):
    analysis  = analyze_failure(
        req.atoms, req.sessions, req.cvr, req.benchmark_cvr, req.ctr
    )
    summary   = generate_postmortem_summary(
        req.creative_id, analysis, req.top_performing_atoms
    )
    return {"analysis": analysis, "postmortem_md": summary}
