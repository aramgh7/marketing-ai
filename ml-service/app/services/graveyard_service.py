"""Creative Graveyard — Failure Mode Classification (Phase 6-5).

Classifies why a creative underperformed and generates a markdown post-mortem.
No FastAPI imports — pure logic.
"""
from typing import Dict, List, Optional

FAILURE_THRESHOLDS: Dict = {
    "cvr_min_sessions": 50,
    "cvr_poor":         0.005,
    "ctr_poor":         0.010,
}


def classify_failure_mode(
    cvr: float,
    ctr: Optional[float],
    sessions: int,
    benchmark_cvr: float,
    atom_types: List[str],
) -> str:
    """Classify why a creative failed.

    Returns one of:
      'insufficient_data' | 'hook_failure' | 'landing_failure' |
      'offer_weakness'    | 'general_underperformance'
    """
    if sessions < FAILURE_THRESHOLDS["cvr_min_sessions"]:
        return "insufficient_data"

    ctr_bad = ctr is not None and ctr < FAILURE_THRESHOLDS["ctr_poor"]
    cvr_bad = cvr < FAILURE_THRESHOLDS["cvr_poor"]

    has_hook    = any(t in ("hook", "headline", "thumbnail") for t in atom_types)
    has_landing = any(t in ("cta", "body", "offer") for t in atom_types)

    if ctr_bad and has_hook:
        return "hook_failure"
    if cvr_bad and not ctr_bad and has_landing:
        return "landing_failure"
    if cvr_bad and cvr < benchmark_cvr * 0.25:
        return "offer_weakness"
    if cvr < benchmark_cvr * 0.5:
        return "general_underperformance"
    return "general_underperformance"


def analyze_failure(
    atoms: List[Dict],
    sessions: int,
    cvr: float,
    benchmark_cvr: float = 0.02,
    ctr: Optional[float] = None,
) -> Dict:
    """Full failure analysis dict for a creative."""
    atom_types  = [a.get("atom_type", "") for a in atoms]
    failure_mode = classify_failure_mode(cvr, ctr, sessions, benchmark_cvr, atom_types)

    cvr_vs_benchmark = round((cvr - benchmark_cvr) / benchmark_cvr, 4) if benchmark_cvr > 0 else 0.0
    weak_atoms = [
        a for a in atoms
        if a.get("atom_type") in _WEAK_ATOM_TYPES.get(failure_mode, [])
    ]

    return {
        "failure_mode":      failure_mode,
        "cvr":               cvr,
        "benchmark_cvr":     benchmark_cvr,
        "cvr_vs_benchmark":  cvr_vs_benchmark,
        "sessions":          sessions,
        "ctr":               ctr,
        "weak_atom_types":   [a.get("atom_type") for a in weak_atoms],
        "recommendation":    _RECOMMENDATIONS.get(failure_mode, "Review and replace creative."),
    }


def generate_postmortem_summary(
    creative_id: str,
    failure_analysis: Dict,
    top_performing_atoms: List[Dict],
) -> str:
    """Generate a markdown post-mortem for a failed creative."""
    mode     = failure_analysis.get("failure_mode", "unknown")
    cvr      = failure_analysis.get("cvr", 0)
    bench    = failure_analysis.get("benchmark_cvr", 0)
    sessions = failure_analysis.get("sessions", 0)
    rec      = failure_analysis.get("recommendation", "")
    top_labels = [a.get("label", a.get("atom_type", "?")) for a in top_performing_atoms[:3]]

    return f"""## Creative Post-Mortem: `{creative_id}`

**Failure mode:** `{mode}`

| Metric | Value |
|--------|-------|
| CVR | {cvr:.4f} |
| Benchmark CVR | {bench:.4f} |
| Sessions | {sessions} |

**Root cause:** {_ROOT_CAUSES.get(mode, mode)}

**Recommendation:** {rec}

**Top-performing atoms to borrow:** {', '.join(top_labels) if top_labels else 'N/A'}
"""


_WEAK_ATOM_TYPES: Dict[str, List[str]] = {
    "hook_failure":               ["hook", "headline", "thumbnail"],
    "landing_failure":            ["cta", "body"],
    "offer_weakness":             ["offer"],
    "general_underperformance":   [],
}

_RECOMMENDATIONS: Dict[str, str] = {
    "insufficient_data":         "Collect more traffic before retiring — at least 50 sessions.",
    "hook_failure":               "Replace hook/headline/thumbnail from top-performer pool.",
    "landing_failure":            "Rewrite CTA and body copy; A/B test landing page.",
    "offer_weakness":             "Strengthen offer incentive or reduce friction at checkout.",
    "general_underperformance":   "Retire creative and generate new candidates via evolution.",
}

_ROOT_CAUSES: Dict[str, str] = {
    "insufficient_data":         "Too few sessions to draw a conclusion.",
    "hook_failure":               "Ad failed to capture attention — low CTR despite adequate reach.",
    "landing_failure":            "Ad drove clicks but the landing experience didn't convert.",
    "offer_weakness":             "Audience clicked but the offer didn't motivate purchase.",
    "general_underperformance":   "Overall CVR well below benchmark across all funnel stages.",
}
