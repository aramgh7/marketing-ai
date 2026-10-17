"""
Niche Creative Entropy Index service — no FastAPI/Pydantic imports.
Prevents "tragedy of the commons" when all tenants in a niche run similar hooks.
Uses cosine similarity from atom_registry embeddings (1-6).
Architecture §15.10, roadmap 6-8.
"""
from __future__ import annotations

import math
from typing import Optional

_ENTROPY_THRESHOLD = 0.3   # below this → diversity_flag = True
_MIN_TENANTS       = 5     # need at least 5 tenants per niche before scoring


def _cosine(v1: list[float], v2: list[float]) -> float:
    """Cosine similarity between two vectors."""
    dot = sum(a * b for a, b in zip(v1, v2))
    norm1 = math.sqrt(sum(a * a for a in v1))
    norm2 = math.sqrt(sum(b * b for b in v2))
    if norm1 == 0 or norm2 == 0:
        return 0.0
    return dot / (norm1 * norm2)


def compute_pairwise_similarities(embeddings: list[list[float]]) -> list[float]:
    """
    Compute all unique pairwise cosine similarities for N embeddings.
    Returns flat list of N*(N-1)/2 similarity values.
    """
    n = len(embeddings)
    sims = []
    for i in range(n):
        for j in range(i + 1, n):
            sims.append(_cosine(embeddings[i], embeddings[j]))
    return sims


def compute_entropy_from_similarities(similarities: list[float]) -> float:
    """
    Entropy = 1 - mean_cosine_similarity.
    Range [0, 1]: 1.0 = fully diverse, 0.0 = all identical hooks.
    """
    if not similarities:
        return 1.0  # no data → assume full diversity
    mean_sim = sum(similarities) / len(similarities)
    return round(1.0 - mean_sim, 4)


def check_diversity_penalty(
    entropy: float,
    tenant_count: int,
    niche: str,
) -> dict:
    """
    Determine if a diversity penalty should be applied.
    Requirements: >= MIN_TENANTS per niche; entropy < ENTROPY_THRESHOLD.
    Returns {diversity_flag, penalty_reason, recommendation}.
    """
    if tenant_count < _MIN_TENANTS:
        return {
            "diversity_flag": False,
            "diversity_insufficient_tenants": True,
            "tenant_count": tenant_count,
            "min_tenants_required": _MIN_TENANTS,
            "recommendation": f"Need >= {_MIN_TENANTS} tenants in niche '{niche}' before scoring",
        }

    diversity_flag = entropy < _ENTROPY_THRESHOLD

    result: dict = {
        "diversity_flag":   diversity_flag,
        "entropy":          entropy,
        "threshold":        _ENTROPY_THRESHOLD,
        "tenant_count":     tenant_count,
        "niche":            niche,
    }

    if diversity_flag:
        result["penalty_reason"] = (
            f"Entropy {entropy:.3f} < threshold {_ENTROPY_THRESHOLD}: "
            "too many tenants in this niche are using semantically-similar hooks"
        )
        result["recommendation"] = (
            "Rotate hooks: prioritise candidates with low cosine similarity to current niche centroid; "
            "see 6-4 Evolutionary Algorithm diversity ranking"
        )
    else:
        result["recommendation"] = "Diversity is healthy — no rotation required"

    return result


def build_entropy_report(
    niche: str,
    embeddings: list[list[float]],
    tenant_count: int,
) -> dict:
    """
    Full entropy report for a niche.
    embeddings: one representative hook embedding per active tenant.
    """
    if tenant_count < _MIN_TENANTS:
        return {
            "niche":          niche,
            "tenant_count":   tenant_count,
            "entropy":        None,
            "mean_similarity": None,
            "diversity_flag": False,
            "skipped_reason": f"Need >= {_MIN_TENANTS} tenants (have {tenant_count})",
        }

    sims = compute_pairwise_similarities(embeddings)
    entropy = compute_entropy_from_similarities(sims)
    mean_sim = round(1.0 - entropy, 4)
    penalty = check_diversity_penalty(entropy, tenant_count, niche)

    return {
        "niche":            niche,
        "tenant_count":     tenant_count,
        "entropy":          entropy,
        "mean_similarity":  mean_sim,
        "diversity_flag":   penalty["diversity_flag"],
        "recommendation":   penalty.get("recommendation"),
        "pair_count":       len(sims),
    }
