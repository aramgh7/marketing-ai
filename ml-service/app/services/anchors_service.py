"""
Anchor Creatives Market-Shock Detector — no FastAPI/Pydantic imports.
Vision §11.2; arch §15.2.

For each metric (CTR, CVR), computes:
  - P(anchors dropped vs their own 14-day baseline)
  - P(tests dropped)
Verdict: MARKET / CREATIVE / NONE
"""
from __future__ import annotations

import math
import random
from typing import Optional


def _beta_sample(alpha: float, beta: float, n: int = 5000, seed: int = 42) -> list[float]:
    rng = random.Random(seed)
    samples = []
    for _ in range(n):
        # Marsaglia-Tsang gamma method approximation
        x = sum(-math.log(max(rng.random(), 1e-300)) for _ in range(int(round(alpha)))) if alpha >= 1 else rng.betavariate(alpha, beta)
        y = sum(-math.log(max(rng.random(), 1e-300)) for _ in range(int(round(beta)))) if beta >= 1 else 1 - x
        if alpha < 1 or beta < 1:
            samples.append(rng.betavariate(alpha, beta))
        else:
            total = x + y
            samples.append(x / total if total > 0 else 0.5)
    return samples


def _prob_drop(current_alpha: float, current_beta: float,
               baseline_alpha: float, baseline_beta: float,
               threshold_drop: float = 0.05, n: int = 3000) -> float:
    """P(current_rate < baseline_rate - threshold_drop)."""
    cur_samples = [random.betavariate(current_alpha, current_beta) for _ in range(n)]
    base_samples = [random.betavariate(baseline_alpha, baseline_beta) for _ in range(n)]
    drops = sum(1 for c, b in zip(cur_samples, base_samples) if c < b - threshold_drop)
    return drops / n


def compute_divergence(
    anchor_stats: list[dict],   # [{alpha, beta, baseline_alpha, baseline_beta}]
    test_stats: list[dict],     # same shape
    threshold: float = 0.9,
    drop_threshold: float = 0.05,
) -> dict:
    """
    Returns {p_anchor_drop, p_test_drop, verdict}.
    verdict: MARKET (both sides dropped), CREATIVE (tests only), NONE.
    """
    if not anchor_stats or not test_stats:
        return {"p_anchor_drop": 0.0, "p_test_drop": 0.0, "verdict": "NONE"}

    random.seed(42)

    # Pool all anchor drops and take the max (conservative — needs all anchors to fall)
    anchor_drops = [
        _prob_drop(s["alpha"], s["beta"], s["baseline_alpha"], s["baseline_beta"], drop_threshold)
        for s in anchor_stats
    ]
    p_anchor = min(anchor_drops) if anchor_drops else 0.0   # conservative: weakest anchor

    test_drops = [
        _prob_drop(s["alpha"], s["beta"], s["baseline_alpha"], s["baseline_beta"], drop_threshold)
        for s in test_stats
    ]
    # Mean of test drops
    p_test = sum(test_drops) / len(test_drops) if test_drops else 0.0

    if p_anchor >= threshold and p_test >= threshold:
        verdict = "MARKET"
    elif p_test >= threshold:
        verdict = "CREATIVE"
    else:
        verdict = "NONE"

    return {
        "p_anchor_drop": round(p_anchor, 4),
        "p_test_drop": round(p_test, 4),
        "verdict": verdict,
    }


def run_divergence_scan(
    metrics_data: dict,  # {metric: {anchors: [...], tests: [...]}}
    threshold: float = 0.9,
) -> list[dict]:
    """
    Run divergence for multiple metrics. Returns list of per-metric results.
    """
    results = []
    for metric, data in metrics_data.items():
        anchors = data.get("anchors", [])
        tests = data.get("tests", [])
        result = compute_divergence(anchors, tests, threshold=threshold)
        result["metric"] = metric
        results.append(result)
    return results
