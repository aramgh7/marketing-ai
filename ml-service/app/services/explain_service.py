"""
Explainability service — no FastAPI/Pydantic imports.
Generates human-readable explanations from model statistics ONLY.
LLM may rephrase but cannot add or change any number (arch §15.16, vision §21.2–21.3).
"""
from __future__ import annotations

import json
import math
from typing import Any

VARIANT_TEMPLATE = (
    "Variant performance summary:\n"
    "- Estimated conversion rate: {cvr_mean:.1%} (95% CI: {cvr_ci_low:.1%} – {cvr_ci_high:.1%})\n"
    "- Observations: {n} sessions\n"
    "- P(best in test): {p_best:.1%}\n"
    "- Quality score: {quality_score}\n"
    "- Compared to anchor: {anchor_comparison}\n"
    "- External context: {external_context}\n"
    "Note: intervals are binomial lower bounds until the Phase 3 overdispersed model is live."
)

CREATIVE_TEMPLATE = (
    "Creative performance summary:\n"
    "- Estimated conversion rate: {cvr_mean:.1%} (95% CI: {cvr_ci_low:.1%} – {cvr_ci_high:.1%})\n"
    "- Observations: {n} sessions\n"
    "- Frequency vs burnout threshold: {frequency_vs_threshold}\n"
    "- Quality score: {quality_score}\n"
    "- External context on last observation date: {external_context}\n"
    "Note: intervals are binomial lower bounds until the Phase 3 overdispersed model is live."
)

CAMPAIGN_TEMPLATE = (
    "Campaign weekly summary:\n"
    "- Sessions: {sessions}\n"
    "- Conversions: {conversions}\n"
    "- Conversion rate: {cvr_pct}\n"
    "- Revenue: {revenue}\n"
    "- Diagnostic symptoms detected: {symptoms_detected}\n"
    "Note: numbers are direct observations, no model inference applied."
)

TEMPLATES = {"variant": VARIANT_TEMPLATE, "creative": CREATIVE_TEMPLATE, "campaign": CAMPAIGN_TEMPLATE}


def _wilson_ci(successes: float, n: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson score confidence interval."""
    if n < 1:
        return 0.0, 1.0
    p_hat = max(0.0, successes) / n
    denominator = 1 + z**2 / n
    center = (p_hat + z**2 / (2 * n)) / denominator
    margin = z * math.sqrt(p_hat * (1 - p_hat) / n + z**2 / (4 * n**2)) / denominator
    return max(0.0, center - margin), min(1.0, center + margin)


def _format_external_ctx(ctx: dict | None) -> str:
    if not ctx:
        return "No external context recorded"
    parts = []
    if ctx.get("cpm_index") is not None:
        parts.append(f"CPM index: {float(ctx['cpm_index']):.2f}")
    if ctx.get("is_holiday"):
        parts.append("Holiday period")
    if ctx.get("weather_bucket"):
        parts.append(f"Weather: {ctx['weather_bucket']}")
    if ctx.get("news_sentiment") is not None:
        s = float(ctx["news_sentiment"])
        label = "positive" if s > 0.2 else ("negative" if s < -0.2 else "neutral")
        parts.append(f"News sentiment: {label}")
    return "; ".join(parts) if parts else "Normal conditions"


def build_explanation_context(entity_type: str, stats: dict) -> dict:
    """
    Build context dict for explanation from raw model statistics.
    Only numbers verified to exist in stats are included.
    """
    alpha = float(stats.get("alpha", 1.0))
    beta = float(stats.get("beta", 1.0))
    n = int(stats.get("n", 0))

    cvr_mean = alpha / (alpha + beta)
    successes = alpha - 1.0  # remove prior
    cvr_ci_low, cvr_ci_high = _wilson_ci(successes, n)

    p_best = float(stats.get("p_best", 0.0))
    qs = stats.get("quality_score")
    quality_score_str = f"{float(qs):.2f}" if qs is not None else "not yet computed"

    context: dict = {
        "cvr_mean": cvr_mean,
        "cvr_ci_low": cvr_ci_low,
        "cvr_ci_high": cvr_ci_high,
        "n": n,
        "p_best": p_best,
        "quality_score": quality_score_str,
    }

    if entity_type == "variant":
        anchor_cvr = stats.get("anchor_cvr")
        if anchor_cvr is not None:
            delta = cvr_mean - float(anchor_cvr)
            sign = "+" if delta >= 0 else ""
            context["anchor_comparison"] = f"{sign}{delta:.1%} vs anchor ({float(anchor_cvr):.1%})"
        else:
            context["anchor_comparison"] = "Anchor comparison unavailable"
        context["external_context"] = _format_external_ctx(stats.get("external_ctx"))

    elif entity_type == "creative":
        freq = stats.get("frequency")
        threshold = stats.get("burnout_threshold")
        if freq is not None and threshold is not None:
            context["frequency_vs_threshold"] = (
                f"{float(freq):.1f} (threshold: {float(threshold):.1f})"
            )
        else:
            context["frequency_vs_threshold"] = "Frequency data unavailable"
        context["external_context"] = _format_external_ctx(stats.get("external_ctx"))

    elif entity_type == "campaign":
        sessions    = int(stats.get("sessions", 0))
        conversions = int(stats.get("conversions", 0))
        cvr         = float(stats.get("cvr") or 0.0)
        revenue     = float(stats.get("revenue") or 0.0)
        symptoms    = int(stats.get("symptoms_detected", 0))
        context = {
            "sessions":          sessions,
            "conversions":       conversions,
            "cvr_pct":           f"{cvr * 100:.2f}%" if cvr else "n/a",
            "revenue":           f"${revenue:,.2f}",
            "symptoms_detected": symptoms,
        }

    return context


def format_explanation(
    entity_type: str,
    context: dict,
    llm_client: Any = None,
    model: str = "claude-haiku-5-5",
) -> str:
    """
    Format the explanation. LLM may rephrase but receives ONLY the numbers from context.
    """
    template = TEMPLATES.get(entity_type)
    if not template:
        return json.dumps(context, indent=2, default=str)

    try:
        base_text = template.format(**context)
    except KeyError as e:
        return f"Explanation unavailable: missing field {e}"

    if llm_client is None:
        return base_text

    prompt = (
        "Rephrase this performance summary in clear business language.\n"
        "You MUST use ONLY the numbers provided — do not add, invent, or change any metric.\n"
        "Do not remove the final 'Note' line.\n\n"
        f"{base_text}\n\n"
        "Return the rephrased text only, no commentary."
    )
    try:
        return llm_client.complete(prompt, model=model)
    except Exception:
        return base_text
