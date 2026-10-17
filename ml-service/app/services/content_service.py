"""
Content generation service — no FastAPI/Pydantic imports.
Generates landing page slot content using LLM with RLHA few-shots.
Computes message match score between creative hook and landing content.
"""
from __future__ import annotations

import hashlib
import json
import re
from typing import Any


def _build_generation_prompt(
    block_type: str,
    slots: list[str],
    product_facts: list[str],
    audience: str,
    hook_text: str,
    best_atoms: list[dict],
    rlha_few_shots: list[dict],
    style_profile: str,
) -> str:
    facts_str = "\n".join(f"- {f}" for f in product_facts) if product_facts else "Not provided"
    atoms_str = (
        "\n".join(
            f"- {a.get('atom_type', '')}: {a.get('atom_value', '')} (CVR rank: {a.get('rank', '?')})"
            for a in best_atoms
        )
        if best_atoms
        else "No atom performance data yet"
    )

    few_shots_str = ""
    if rlha_few_shots:
        examples = "\n\n".join(
            f"Generated: {s.get('generated_text', '')}\nApproved: {s.get('approved_text', '')}"
            for s in rlha_few_shots[:8]
        )
        few_shots_str = f"\n\nExamples of approved edits (learn the preferred style):\n{examples}"

    return (
        f"You are writing landing page content for a {block_type} block.\n\n"
        f"Product facts (only use these; no invented claims):\n{facts_str}\n\n"
        f"Target audience: {audience or 'General'}\n"
        f"Creative hook used in the ad: {hook_text or 'Not provided'}\n"
        f"Style profile: {style_profile or 'Professional, direct, benefit-focused'}\n\n"
        f"Top-performing content patterns:\n{atoms_str}"
        f"{few_shots_str}\n\n"
        f"Write ONLY the content for these slots (respond in JSON):\n"
        f"Slots needed: {json.dumps(slots)}\n\n"
        "Rules:\n"
        "- Every factual claim must be supported by the product facts above\n"
        "- Match the tone from the creative hook (message match)\n"
        "- Use concrete language, avoid vague claims\n"
        "- Keep it concise\n"
        "- Respond ONLY with a JSON object mapping each slot to its content text"
    )


def generate_content(
    block_type: str,
    slots: list[str],
    product_facts: list[str],
    audience: str,
    hook_text: str,
    best_atoms: list[dict],
    rlha_few_shots: list[dict],
    style_profile: str,
    llm_client: Any,
    model: str = "claude-sonnet-5-5",
) -> dict:
    """
    Generate slot content for a landing block.
    Returns {slots: {slot: text}, generation_id, model_version, stub}.
    """
    prompt = _build_generation_prompt(
        block_type, slots, product_facts, audience,
        hook_text, best_atoms, rlha_few_shots, style_profile,
    )
    prompt_hash = hashlib.sha256(prompt.encode()).hexdigest()[:16]

    if llm_client is None:
        return {
            "slots": {s: f"[Draft {block_type} content for {s}]" for s in slots},
            "generation_id": f"stub_{prompt_hash}",
            "model_version": "stub",
            "stub": True,
        }

    response_text = llm_client.complete(prompt, model=model)

    try:
        slot_values = json.loads(response_text)
    except Exception:
        match = re.search(r'\{[^{}]*\}', response_text, re.DOTALL)
        if match:
            try:
                slot_values = json.loads(match.group())
            except Exception:
                slot_values = {s: response_text for s in slots}
        else:
            slot_values = {s: response_text for s in slots}

    return {
        "slots": slot_values,
        "generation_id": prompt_hash,
        "model_version": model,
        "stub": False,
    }


def compute_match_score(
    creative_hook: str,
    landing_headline: str,
    landing_offer: str,
    llm_client: Any,
    model: str = "claude-sonnet-5-5",
) -> dict:
    """
    Compute message match score: how well the landing echoes the creative's promise.
    Returns {score: float|None, rationale: str, stub: bool}
    Wide prior / low weight until calibrated (vision §6.3a).
    """
    if llm_client is None or not creative_hook or not landing_headline:
        return {
            "score": None,
            "rationale": "Insufficient data for scoring",
            "stub": True,
        }

    prompt = (
        "Rate the message match between this ad and landing page on a scale of 0.0 to 1.0.\n\n"
        f"Ad hook/promise:\n{creative_hook}\n\n"
        f"Landing headline:\n{landing_headline}\n\n"
        f"Landing offer:\n{landing_offer or 'Not provided'}\n\n"
        "Message match means: does the landing deliver on the specific promise made in the ad?\n"
        "A score of 1.0 means the landing perfectly continues the ad's message.\n"
        "A score of 0.0 means there is no connection.\n\n"
        'Respond ONLY with JSON: {"score": 0.75, "rationale": "One sentence explanation"}'
    )

    try:
        response = llm_client.complete(prompt, model=model)
        result = json.loads(response)
        score = float(result.get("score", 0.5))
        score = max(0.0, min(1.0, score))
        return {
            "score": score,
            "rationale": result.get("rationale", ""),
            "stub": False,
        }
    except Exception:
        return {
            "score": 0.5,
            "rationale": "Could not parse LLM response",
            "stub": True,
        }


def classify_rlha_delta(
    generated_text: str,
    approved_text: str,
    llm_client: Any,
    model: str = "claude-haiku-5-5",
) -> list[str]:
    """
    Classify what changed between generated and approved text.
    Returns list of delta tags: TONE, LENGTH, FORBIDDEN_WORD, STRUCTURE, BRAND_CONTEXT.
    """
    if llm_client is None:
        return []

    prompt = (
        "Classify the type of edit made to this marketing text.\n\n"
        f"Original:\n{generated_text}\n\n"
        f"Approved version:\n{approved_text}\n\n"
        "Choose all that apply from: TONE, LENGTH, FORBIDDEN_WORD, STRUCTURE, BRAND_CONTEXT\n\n"
        'Respond ONLY with a JSON array: ["TONE", "LENGTH"]'
    )
    try:
        response = llm_client.complete(prompt, model=model)
        tags = json.loads(response)
        valid = {"TONE", "LENGTH", "FORBIDDEN_WORD", "STRUCTURE", "BRAND_CONTEXT"}
        return [t for t in tags if t in valid]
    except Exception:
        return []
