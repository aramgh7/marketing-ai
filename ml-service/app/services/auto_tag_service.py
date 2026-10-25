"""
Phase 14, §19.4: Hybrid auto-tagging pipeline.
Calls Claude Vision to suggest L1/L2/L3 tags for a media asset.
Human confirms/overrides; diffs go to tag_rlha_log.
"""
from __future__ import annotations

# Tag vocabularies (must stay in sync with AssetService.java constants)
L1_VALUES = [
    "founder_portrait", "team_photo", "property_aerial", "property_interior",
    "chart_graph", "document", "abstract_visual", "product_shot",
]
L2_VALUES = [
    "hero_background", "hero_face", "proof_visual", "social_proof_avatar",
    "materials_thumbnail", "ad_creative_primary", "ad_creative_secondary",
]
L3_VALUES = [
    "professional", "warm_approachable", "aspirational",
    "technical_data_driven", "urgent", "calm_trustworthy",
]

_ALL = {"L1": L1_VALUES, "L2": L2_VALUES, "L3": L3_VALUES}


def build_auto_tag_prompt(mime_type: str) -> str:
    """Return the system prompt sent to Claude Vision for asset tagging."""
    return (
        "You are a media asset tagger for a marketing platform. "
        "Analyze the image and return JSON with exactly three keys: "
        '"tags_l1" (object type), "tags_l2" (role/slot), "tags_l3" (mood/tone). '
        "Each key maps to a list of matching values from the allowed sets below. "
        "Return only values from these allowed sets; return an empty list if nothing fits.\n\n"
        f"L1 allowed: {L1_VALUES}\n"
        f"L2 allowed: {L2_VALUES}\n"
        f"L3 allowed: {L3_VALUES}\n\n"
        "Return ONLY valid JSON, no prose."
    )


def parse_tag_response(raw: str) -> dict:
    """
    Parse LLM JSON response into {tags_l1, tags_l2, tags_l3, confidence}.
    Filters each list to only allowed values; never raises.
    """
    import json
    import re

    result = {"tags_l1": [], "tags_l2": [], "tags_l3": [], "confidence": {}}
    try:
        cleaned = re.sub(r"^```[a-z]*\n?|```$", "", raw.strip(), flags=re.MULTILINE).strip()
        data = json.loads(cleaned)
    except Exception:
        return result

    for level_key, allowed in [("tags_l1", L1_VALUES), ("tags_l2", L2_VALUES), ("tags_l3", L3_VALUES)]:
        raw_tags = data.get(level_key, [])
        if isinstance(raw_tags, list):
            valid = [t for t in raw_tags if t in allowed]
            result[level_key] = valid
        conf_key = level_key.replace("tags_", "")
        all_raw = raw_tags if isinstance(raw_tags, list) else []
        result["confidence"][conf_key] = {t: 1.0 for t in result[level_key]}
        for t in all_raw:
            if t not in allowed:
                result["confidence"].setdefault(conf_key, {})[t] = 0.0

    return result


def suggest_tags(image_url_or_b64: str, mime_type: str, client=None) -> dict:
    """
    Call Claude Vision with the asset and return parsed tag suggestions.
    Falls back to empty suggestions on any error (never blocks the upload flow).

    Returns: {tags_l1, tags_l2, tags_l3, confidence, error?}
    """
    try:
        import anthropic  # lazy import

        api_client = client or anthropic.Anthropic()
        prompt = build_auto_tag_prompt(mime_type)

        if image_url_or_b64.startswith("http"):
            image_content: dict = {"type": "image", "source": {"type": "url", "url": image_url_or_b64}}
        else:
            image_content = {
                "type": "image",
                "source": {"type": "base64", "media_type": mime_type, "data": image_url_or_b64},
            }

        msg = api_client.messages.create(
            model="claude-opus-4-5",
            max_tokens=256,
            system=prompt,
            messages=[{"role": "user", "content": [image_content, {"type": "text", "text": "Tag this asset."}]}],
        )
        raw = msg.content[0].text if msg.content else ""
        return parse_tag_response(raw)
    except Exception as exc:
        return {"tags_l1": [], "tags_l2": [], "tags_l3": [], "confidence": {}, "error": str(exc)}


def tag_correction_rate(rlha_rows: list[dict]) -> dict[str, float]:
    """
    Compute correction rate per tag level from a list of tag_rlha_log rows.
    Each row: {tag_level, was_corrected}.
    Returns {"L1": 0.0–1.0, "L2": ..., "L3": ...} — missing level absent from result.
    """
    counts: dict[str, list[bool]] = {}
    for row in rlha_rows:
        level = row.get("tag_level", "")
        corrected = bool(row.get("was_corrected", False))
        counts.setdefault(level, []).append(corrected)

    result: dict[str, float] = {}
    for level in ["L1", "L2", "L3"]:
        vals = counts.get(level)
        if vals:
            result[level] = sum(vals) / len(vals)
    return result
