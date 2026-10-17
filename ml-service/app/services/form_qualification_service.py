"""
Form Self-Qualification + Value Calibration (vision §19.5).
Pure logic — zero FastAPI/Pydantic imports.
"""
from __future__ import annotations
import re
from collections import defaultdict


TICKET_BUCKET_LABELS = {
    "LT25K":    "Under $25K",
    "25_50K":   "$25K – $50K",
    "50_100K":  "$50K – $100K",
    "100_250K": "$100K – $250K",
    "GT250K":   "Over $250K",
}

TICKET_BUCKET_ORDER = ["LT25K", "25_50K", "50_100K", "100_250K", "GT250K"]

K_TICKET_DEFAULT = {
    "LT25K":    0.3,
    "25_50K":   0.6,
    "50_100K":  1.0,
    "100_250K": 1.8,
    "GT250K":   3.0,
}


def classify_ticket_bucket(amount_usd: float | None) -> str | None:
    if amount_usd is None:
        return None
    if amount_usd < 25_000:
        return "LT25K"
    elif amount_usd < 50_000:
        return "25_50K"
    elif amount_usd < 100_000:
        return "50_100K"
    elif amount_usd < 250_000:
        return "100_250K"
    else:
        return "GT250K"


def validate_form_payload(payload: dict) -> dict:
    """
    Validates and normalizes a form submission payload.
    Returns {valid, errors, normalized}.
    """
    errors     = []
    normalized = {}

    email = payload.get("email", "").strip().lower()
    if not email:
        errors.append("email is required")
    elif not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", email):
        errors.append("email format invalid")
    else:
        normalized["email"] = email

    phone = payload.get("phone", "").strip()
    if phone:
        digits = re.sub(r"\D", "", phone)
        if len(digits) < 7:
            errors.append("phone too short")
        else:
            normalized["phone"] = phone

    first = payload.get("first_name", "").strip()
    last  = payload.get("last_name", "").strip()
    if not first:
        errors.append("first_name is required")
    else:
        normalized["first_name"] = first[:100]
    if last:
        normalized["last_name"] = last[:100]

    sa = payload.get("self_accredited")
    if sa is not None:
        normalized["self_accredited"] = bool(sa)

    tb = payload.get("ticket_bucket")
    if tb is not None:
        if tb in TICKET_BUCKET_ORDER:
            normalized["ticket_bucket"] = tb
        else:
            matched = False
            for k, label in TICKET_BUCKET_LABELS.items():
                if tb.lower() in label.lower():
                    normalized["ticket_bucket"] = k
                    matched = True
                    break
            if not matched:
                errors.append(f"ticket_bucket '{tb}' not recognized")

    return {
        "valid":      len(errors) == 0,
        "errors":     errors,
        "normalized": normalized,
    }


def compute_lead_score(
    self_accredited: bool | None,
    ticket_bucket: str | None,
    k_ticket: dict | None = None,
) -> dict:
    """
    Lead quality score [0,1] from self-qualification data.
    Used for coordinator routing priority — NOT for CAPI value.
    """
    kt     = k_ticket or K_TICKET_DEFAULT
    k_accr = 1.0 if self_accredited else (0.3 if self_accredited is None else 0.0)
    k_tick = kt.get(ticket_bucket, 1.0) if ticket_bucket else 0.5
    raw    = k_accr * k_tick
    score  = min(raw / 3.0, 1.0)
    tier   = "HIGH" if score >= 0.5 else ("MED" if score >= 0.2 else "LOW")
    return {
        "score":    round(score, 3),
        "tier":     tier,
        "k_accr":   k_accr,
        "k_ticket": k_tick,
    }


def calibrate_value_coefficients(
    calibration_log: list[dict],
    min_samples: int = 20,
) -> dict:
    """
    Calibrates k_ticket coefficients by comparing predicted vs actual value.
    Returns suggested coefficient updates per bucket.
    """
    import statistics

    bucket_data: dict[str, list[float]] = defaultdict(list)
    for entry in calibration_log:
        predicted = entry.get("predicted_value")
        actual    = entry.get("actual_value")
        bucket    = entry.get("ticket_bucket")
        if predicted and actual and bucket and float(predicted) > 0:
            bucket_data[bucket].append(float(actual) / float(predicted))

    suggestions = {}
    for bucket, ratios in bucket_data.items():
        if len(ratios) < min_samples:
            suggestions[bucket] = {
                "current":   K_TICKET_DEFAULT.get(bucket, 1.0),
                "suggested": None,
                "n":         len(ratios),
                "reason":    f"Insufficient data (need {min_samples}, have {len(ratios)})",
            }
        else:
            median_ratio = statistics.median(ratios)
            current      = K_TICKET_DEFAULT.get(bucket, 1.0)
            suggestions[bucket] = {
                "current":   current,
                "suggested": round(current * median_ratio, 2),
                "ratio":     round(median_ratio, 3),
                "n":         len(ratios),
                "reason":    "median predicted/actual ratio",
            }

    return {
        "suggestions":      suggestions,
        "total_samples":    len(calibration_log),
        "buckets_analyzed": list(bucket_data.keys()),
    }
