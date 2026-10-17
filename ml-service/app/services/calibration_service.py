"""
Match-score calibration — pure logic, zero FastAPI/Pydantic imports.
Monthly: match_score buckets vs realised CVR (vision §6.3a).
"""
from __future__ import annotations


def compute_calibration_report(
    predictions_and_actuals: list[dict],
    n_buckets: int = 10,
) -> dict:
    """
    Calibration: are predicted scores aligned with actual conversion rates?
    Groups into n_buckets, computes mean predicted vs mean actual per bucket.
    Returns ECE (Expected Calibration Error) + per-bucket breakdown.
    """
    if not predictions_and_actuals:
        return {"ece": None, "buckets": [], "n_total": 0, "note": "No data"}

    bucket_size = 1.0 / n_buckets
    buckets = [
        {"min": i * bucket_size, "max": (i + 1) * bucket_size, "predictions": [], "actuals": []}
        for i in range(n_buckets)
    ]

    for item in predictions_and_actuals:
        score  = float(item.get("score", 0.0))
        actual = 1.0 if item.get("converted", False) else 0.0
        idx    = min(int(score / bucket_size), n_buckets - 1)
        buckets[idx]["predictions"].append(score)
        buckets[idx]["actuals"].append(actual)

    results = []
    ece_sum = 0.0
    n_total = len(predictions_and_actuals)

    for b in buckets:
        n = len(b["predictions"])
        if n == 0:
            continue
        mean_pred   = sum(b["predictions"]) / n
        mean_actual = sum(b["actuals"]) / n
        gap         = abs(mean_pred - mean_actual)
        ece_sum    += gap * (n / n_total)
        results.append({
            "bucket_min":     round(b["min"], 2),
            "bucket_max":     round(b["max"], 2),
            "n":              n,
            "mean_predicted": round(mean_pred, 4),
            "mean_actual":    round(mean_actual, 4),
            "gap":            round(gap, 4),
            "calibrated":     gap < 0.05,
        })

    return {
        "ece":     round(ece_sum, 4),
        "buckets": results,
        "n_total": n_total,
        "note":    "ECE < 0.05 = well-calibrated (vision §6.3a)",
    }
