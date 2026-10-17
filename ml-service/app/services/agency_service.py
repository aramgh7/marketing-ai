"""
Agency Measurability (vision §19.18.7; arch §16.20).
Pure logic — zero FastAPI/Pydantic imports.

Aggregates KPIs and creative-source performance across an agency's tenants.
No tenant data is mixed — only aggregate totals flow to agency layer.
"""
from __future__ import annotations
import math


def aggregate_agency_kpis(
    tenant_snapshots: list[dict],
) -> dict:
    """
    Aggregate KPIs across tenants for agency dashboard.
    """
    totals: dict[str, int] = {"lpv": 0, "held_calls": 0, "committed": 0}
    cph_values: list[float] = []

    for snap in tenant_snapshots:
        totals["lpv"]        += int(snap.get("lpv") or 0)
        totals["held_calls"] += int(snap.get("held_calls") or 0)
        totals["committed"]  += int(snap.get("committed") or 0)
        if snap.get("cph_usd"):
            cph_values.append(float(snap["cph_usd"]))

    n = len(tenant_snapshots) or 1
    avg_cph = sum(cph_values) / len(cph_values) if cph_values else None
    held = totals["held_calls"] or 1
    commit_rate = round(totals["committed"] / held, 4)

    return {
        "tenant_count":     n,
        "total_lpv":        totals["lpv"],
        "total_held_calls": totals["held_calls"],
        "total_committed":  totals["committed"],
        "avg_cph_usd":      round(avg_cph, 2) if avg_cph is not None else None,
        "commit_rate":      commit_rate,
    }


def rank_tenants_by_performance(
    tenant_snapshots: list[dict],
    metric: str = "held_calls",
) -> list[dict]:
    """
    Rank agency tenants by a given metric (descending).
    """
    ranked = sorted(
        tenant_snapshots,
        key=lambda x: float(x.get(metric) or 0),
        reverse=True,
    )
    for i, snap in enumerate(ranked):
        snap["rank"] = i + 1
    return ranked


def compare_creative_sources(
    source_rows: list[dict],
) -> dict:
    """
    P(source A better than source B) on cost per held call.
    Uses Beta posterior on held-call rate: alpha = held+1, beta = (lpv-held)+1.
    Returns pairwise comparison for the agency contractor report.
    """
    results: list[dict] = []
    for row in source_rows:
        lpv   = int(row.get("lpv") or 0)
        held  = int(row.get("held_calls") or 0)
        alpha = held + 1
        beta  = (lpv - held) + 1
        mean  = alpha / (alpha + beta)
        se    = math.sqrt(mean * (1 - mean) / max(lpv, 1))
        results.append({
            "source_id":    row.get("source_id"),
            "source_name":  row.get("source_name"),
            "kind":         row.get("kind"),
            "lpv":          lpv,
            "held_calls":   held,
            "hold_rate":    round(mean, 4),
            "ci_80_lower":  round(max(0, mean - 1.282 * se), 4),
            "ci_80_upper":  round(min(1, mean + 1.282 * se), 4),
        })
    results.sort(key=lambda x: -x["hold_rate"])

    # Pairwise P(A > B) using Beta mean difference approximation
    comparisons: list[dict] = []
    for i in range(len(results)):
        for j in range(i + 1, len(results)):
            a, b = results[i], results[j]
            # Approximate P(A > B) as Φ((mean_A - mean_B) / sqrt(se_A^2 + se_B^2))
            diff = a["hold_rate"] - b["hold_rate"]
            se_a = a["ci_80_upper"] - a["hold_rate"]  # approx se from CI
            se_b = b["ci_80_upper"] - b["hold_rate"]
            pooled_se = math.sqrt(max(se_a**2 + se_b**2, 1e-9))
            z = diff / pooled_se
            # Φ(z) approximation
            p_a_better = 0.5 * (1 + math.erf(z / math.sqrt(2)))
            comparisons.append({
                "source_a": a["source_name"],
                "source_b": b["source_name"],
                "p_a_better_than_b": round(p_a_better, 4),
            })

    return {"sources": results, "pairwise": comparisons}
