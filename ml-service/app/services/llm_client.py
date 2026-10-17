"""Claude API wrapper for Creative DNA extraction and synchronous text completions."""
import base64
import json
import re
import httpx
import anthropic
from app.config import settings


class LLMClient:
    """Synchronous text completion client for pure-logic services."""

    def complete(self, prompt: str, model: str = "claude-haiku-5-5") -> str:
        client = anthropic.Anthropic(api_key=settings.anthropic_api_key)
        message = client.messages.create(
            model=model,
            max_tokens=1024,
            messages=[{"role": "user", "content": prompt}],
        )
        return message.content[0].text

_client: anthropic.AsyncAnthropic | None = None


def get_client() -> anthropic.AsyncAnthropic:
    global _client
    if _client is None:
        _client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
    return _client


IMAGE_SYSTEM = """You are a Creative DNA extraction system for a marketing AI platform.
Analyze advertising creatives and extract atoms (key attributes) that describe the creative.
Always respond with valid JSON only, no markdown."""

IMAGE_PROMPT = """Analyze this advertising creative and extract Creative DNA atoms.

For each atom, output the most appropriate value from the options given.
If you cannot determine an atom from the visual, use "unknown".

Required atoms and valid values:
- HOOK: question | shock | social_proof | direct_address | curiosity | benefit | problem_agitate | unknown
- FACE: yes_speaking_head | yes_broll | no_face
- SUBTITLES: yes_prominent | yes_subtle | no
- TONE: urgency | calm | humor | authoritative | warm | inspirational
- PROOF_TYPE: testimonial | statistics | demo | authority | none
- CTA: yes_text | yes_button | no
- PRODUCTION_STYLE: studio | ugc_polished | ugc_raw | screen_recording | animation

Respond with JSON only:
{"HOOK": "...", "FACE": "...", "SUBTITLES": "...", "TONE": "...", "PROOF_TYPE": "...", "CTA": "...", "PRODUCTION_STYLE": "..."}"""

TRANSCRIPT_SYSTEM = """You are a Creative DNA extraction system for a marketing AI platform.
Analyze video ad transcripts and extract structural and tonal atoms.
Always respond with valid JSON only, no markdown."""

TRANSCRIPT_PROMPT = """Analyze this video ad transcript and extract Creative DNA atoms.

Transcript:
{transcript}

Required atoms and valid values:
- HOOK: question | shock | social_proof | direct_address | curiosity | benefit | problem_agitate
- STRUCTURE: hook_problem_solution_proof_cta | testimonial | demo | educational | before_after
- TONE: urgency | calm | humor | authoritative | warm | inspirational
- PROOF_TYPE: testimonial | statistics | demo | authority | none
- CTA: direct_offer | soft_ask | question | none

Respond with JSON only:
{"HOOK": "...", "STRUCTURE": "...", "TONE": "...", "PROOF_TYPE": "...", "CTA": "..."}"""


async def analyze_image_url(image_url: str) -> dict[str, str]:
    """Fetch image from URL and analyze with Claude Vision."""
    image_data, media_type = await _fetch_image(image_url)
    if not image_data:
        return {}

    client = get_client()
    message = await client.messages.create(
        model="claude-opus-4-5",
        max_tokens=512,
        system=IMAGE_SYSTEM,
        messages=[{
            "role": "user",
            "content": [
                {
                    "type": "image",
                    "source": {
                        "type": "base64",
                        "media_type": media_type,
                        "data": image_data,
                    },
                },
                {"type": "text", "text": IMAGE_PROMPT},
            ],
        }],
    )
    return _parse_json(message.content[0].text)


async def analyze_transcript(transcript: str) -> dict[str, str]:
    """Analyze transcript text with Claude."""
    client = get_client()
    message = await client.messages.create(
        model="claude-opus-4-5",
        max_tokens=512,
        system=TRANSCRIPT_SYSTEM,
        messages=[{
            "role": "user",
            "content": TRANSCRIPT_PROMPT.format(transcript=transcript[:4000]),
        }],
    )
    return _parse_json(message.content[0].text)


async def _fetch_image(url: str) -> tuple[str, str]:
    """Download image and return (base64_data, media_type). Returns ('', '') on failure."""
    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            content_type = resp.headers.get("content-type", "image/jpeg").split(";")[0].strip()
            if not content_type.startswith("image/"):
                return "", ""
            return base64.standard_b64encode(resp.content).decode(), content_type
    except Exception:
        return "", ""


def _parse_json(text: str) -> dict[str, str]:
    """Extract JSON object from Claude response."""
    try:
        # Strip markdown code fences if present
        cleaned = re.sub(r"```(?:json)?\s*|\s*```", "", text).strip()
        return json.loads(cleaned)
    except Exception:
        return {}
