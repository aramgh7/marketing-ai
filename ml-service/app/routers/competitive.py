"""Competitive Intelligence — LLM atom extraction & counter-hypotheses (vision §9.1).

Called by Spring Boot CompetitiveIntelligenceService after Ad Library polling.
Endpoint is internal-only (X-Internal-API-Key required).

LLM model: claude-sonnet-5-5 (latest capable model — fastest for structured extraction).
If anthropic_api_key is empty, returns a stub so the pipeline stays functional without billing.
"""
import json
import logging
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.config import settings

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Competitive Intelligence"])

_ATOM_EXTRACTION_PROMPT = """\
You are a creative strategy analyst. Extract structured creative atoms from the competitor ad below.

Ad title: {title}
Ad body: {body}
Advertiser: {page_name}
Running days: {run_days}

Return a JSON object with these fields:
- hook_type: one of [question, statement, statistic, story, pain_point, social_proof, curiosity]
- hook_text: the opening hook (first sentence or phrase)
- tone: one of [urgent, warm, professional, humorous, educational, provocative]
- offer_type: one of [discount, free_trial, guarantee, bonus, installment, exclusivity, none]
- offer_detail: short description of the offer or null
- pain_points: array of strings (up to 3)
- cta_type: one of [book_call, download, buy_now, learn_more, sign_up, get_quote]
- format_signals: array of strings describing format clues from the text (e.g. "testimonial", "how-to", "before-after")
- strength_signals: why this ad may be performing well (2-3 sentences)

Return ONLY the JSON object, no explanation."""

_COUNTER_HYPOTHESIS_PROMPT = """\
You are a growth strategist. A competitor's ad has been running {run_days} days (proxy for profitability).
Their creative atoms:
{atoms_json}

Generate 3 counter-hypotheses for our client's ad creative. Each hypothesis should:
1. Differentiate from the competitor's approach (different angle, not a copy)
2. Be actionable (specific hook, offer, or format recommendation)
3. Protect our brand tone

Return a JSON array of 3 objects, each with:
- hypothesis: one-sentence description of the creative direction
- hook_suggestion: specific opening hook text
- offer_suggestion: offer or value prop adjustment
- differentiation: why this stands out vs the competitor
- confidence: one of [high, medium, low]

Return ONLY the JSON array."""


def _require_internal_key(x_internal_api_key: str) -> None:
    if x_internal_api_key != settings.internal_api_key:
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-API-Key")


class ExtractAtomsRequest(BaseModel):
    ad_title: Optional[str] = None
    ad_body: Optional[str] = None
    page_name: Optional[str] = None
    run_days: int = 0


class CounterHypothesesRequest(BaseModel):
    atoms: dict
    run_days: int
    page_name: Optional[str] = None


class ExtractAtomsResponse(BaseModel):
    atoms: dict
    counter_hypotheses: list[dict]
    llm_used: bool


async def _call_claude(prompt: str) -> Optional[str]:
    """Call Claude claude-sonnet-5-5 synchronously. Returns None if API key missing."""
    if not settings.anthropic_api_key:
        return None
    try:
        import anthropic
        client = anthropic.Anthropic(api_key=settings.anthropic_api_key)
        msg = client.messages.create(
            model="claude-sonnet-5-5",
            max_tokens=1024,
            messages=[{"role": "user", "content": prompt}],
        )
        return msg.content[0].text if msg.content else None
    except Exception as e:
        logger.error("Claude API call failed: %s", e)
        return None


def _parse_json(text: Optional[str], fallback):
    if not text:
        return fallback
    try:
        # Strip markdown code fences if present
        clean = text.strip()
        if clean.startswith("```"):
            clean = clean.split("```")[1]
            if clean.startswith("json"):
                clean = clean[4:]
        return json.loads(clean.strip())
    except Exception:
        return fallback


_STUB_ATOMS = {
    "hook_type": "statement",
    "hook_text": "(extracted from ad body)",
    "tone": "professional",
    "offer_type": "none",
    "offer_detail": None,
    "pain_points": [],
    "cta_type": "learn_more",
    "format_signals": [],
    "strength_signals": "Ad has been running 14+ days — likely profitable.",
}

_STUB_COUNTER = [
    {
        "hypothesis": "Lead with a specific pain point your competitor ignores",
        "hook_suggestion": "Still struggling with [problem]?",
        "offer_suggestion": "Add a free consultation to reduce perceived risk",
        "differentiation": "Competitor leads with features; we lead with outcome",
        "confidence": "medium",
    }
]


@router.post("/extract-atoms", response_model=ExtractAtomsResponse)
async def extract_competitor_atoms(
    req: ExtractAtomsRequest,
    x_internal_api_key: str = Header(default=""),
):
    """Extract creative atoms from a competitor ad + generate counter-hypotheses (vision §9.1).

    Uses Claude claude-sonnet-5-5. If anthropic_api_key is unset, returns a stub so the
    pipeline stays functional without billing.
    Protected by X-Internal-API-Key.
    """
    _require_internal_key(x_internal_api_key)

    atom_prompt = _ATOM_EXTRACTION_PROMPT.format(
        title=req.ad_title or "(no title)",
        body=req.ad_body or "(no body)",
        page_name=req.page_name or "unknown",
        run_days=req.run_days,
    )
    atom_text = await _call_claude(atom_prompt)
    atoms = _parse_json(atom_text, _STUB_ATOMS)
    llm_used = atom_text is not None

    counter_prompt = _COUNTER_HYPOTHESIS_PROMPT.format(
        run_days=req.run_days,
        atoms_json=json.dumps(atoms, ensure_ascii=False),
    )
    counter_text = await _call_claude(counter_prompt)
    counter_hypotheses = _parse_json(counter_text, _STUB_COUNTER)
    if not isinstance(counter_hypotheses, list):
        counter_hypotheses = _STUB_COUNTER

    return ExtractAtomsResponse(
        atoms=atoms,
        counter_hypotheses=counter_hypotheses,
        llm_used=llm_used,
    )
