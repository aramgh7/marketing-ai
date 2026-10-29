"""Phase 14, §19.4: Asset auto-tagging ML endpoint."""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel

from app.services.auto_tag_service import suggest_tags

router = APIRouter(prefix="/assets", tags=["assets"])


class AutoTagRequest(BaseModel):
    image_url: str
    mime_type: str = "image/jpeg"


class AutoTagResponse(BaseModel):
    tags_l1: list[str]
    tags_l2: list[str]
    tags_l3: list[str]
    confidence: dict
    error: str | None = None


@router.post("/{asset_id}/auto-tag", response_model=AutoTagResponse)
async def auto_tag_asset(asset_id: str, req: AutoTagRequest) -> AutoTagResponse:
    """Trigger Claude Vision auto-tagging for an asset. Returns suggested L1/L2/L3 tags.
    Falls back to empty tags on any error — never blocks the upload flow."""
    result = suggest_tags(req.image_url, req.mime_type)
    return AutoTagResponse(
        tags_l1=result.get("tags_l1", []),
        tags_l2=result.get("tags_l2", []),
        tags_l3=result.get("tags_l3", []),
        confidence=result.get("confidence", {}),
        error=result.get("error"),
    )
