from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(tags=["Analysis"])


class AnalyzeCreativeRequest(BaseModel):
    tenant_id: str
    creative_id: str
    media_url: str
    media_type: str  # "video" | "image"


@router.post("/creative")
async def analyze_creative(req: AnalyzeCreativeRequest):
    # TODO: Creative DNA extraction via Claude Vision + Whisper
    return {"status": "queued", "creative_id": req.creative_id}
