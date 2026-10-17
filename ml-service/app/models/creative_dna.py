"""Creative DNA extraction: turns raw creative metadata into atoms."""
from app.services import llm_client

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif"}
CONFIDENCE_VISION = 0.80
CONFIDENCE_TRANSCRIPT = 0.75
CONFIDENCE_METADATA = 1.0


def _is_image_url(url: str) -> bool:
    lower = url.lower().split("?")[0]
    return any(lower.endswith(ext) for ext in IMAGE_EXTENSIONS)


def _duration_label(sec: float) -> str:
    if sec <= 16:
        return "15s"
    if sec <= 31:
        return "30s"
    if sec <= 61:
        return "60s"
    return "60s_plus"


async def extract(
    media_type: str,
    file_url: str | None,
    thumbnail_url: str | None,
    transcript: str | None,
    duration_sec: float | None,
) -> list[dict]:
    """
    Returns list of atom dicts: {type, value, confidence, extracted_by}

    Phase 1 capabilities:
    - IMAGE: Vision analysis via Claude
    - VIDEO + image URL (thumbnail or .jpg file_url): Vision analysis
    - VIDEO + transcript: Text analysis via Claude
    - DURATION: from metadata
    - Whisper (auto-transcript) and FFmpeg (frame extract) → Phase 4
    """
    atoms: list[dict] = []

    # 1. Duration (pure metadata — always reliable)
    if duration_sec and duration_sec > 0:
        atoms.append({
            "type": "DURATION",
            "value": _duration_label(duration_sec),
            "confidence": CONFIDENCE_METADATA,
            "extracted_by": "metadata",
        })

    # 2. Decide which URL to use for vision analysis
    vision_url: str | None = None
    if media_type == "IMAGE" and file_url:
        vision_url = file_url
    elif thumbnail_url:
        vision_url = thumbnail_url
    elif file_url and _is_image_url(file_url):
        vision_url = file_url

    # 3. Vision analysis
    if vision_url:
        vision_atoms = await llm_client.analyze_image_url(vision_url)
        for atom_type, atom_value in vision_atoms.items():
            if atom_value and atom_value != "unknown":
                atoms.append({
                    "type": atom_type.upper(),
                    "value": atom_value,
                    "confidence": CONFIDENCE_VISION,
                    "extracted_by": "claude-vision",
                })

    # 4. Transcript analysis (VIDEO only — adds structural atoms)
    if transcript and transcript.strip() and media_type == "VIDEO":
        text_atoms = await llm_client.analyze_transcript(transcript)
        existing_types = {a["type"] for a in atoms}
        for atom_type, atom_value in text_atoms.items():
            if atom_value and atom_type.upper() not in existing_types:
                atoms.append({
                    "type": atom_type.upper(),
                    "value": atom_value,
                    "confidence": CONFIDENCE_TRANSCRIPT,
                    "extracted_by": "claude-text",
                })

    return atoms
