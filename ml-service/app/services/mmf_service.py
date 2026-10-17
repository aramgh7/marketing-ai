"""
Marketing Mix Funnel (MMF) + Offer Explorer — no FastAPI/Pydantic imports.
Vision §10.4, §19; arch §15.12.
"""
from __future__ import annotations

import math


def diagnose_mmf(metrics: dict) -> dict:
    """
    Diagnoses funnel health from campaign metrics.

    metrics keys: ctr, cvr, cpc, roas, impression_share, budget_util,
                  learning_phase, frequency (optional), ctr_benchmark (optional)

    Returns {diagnosis, confidence, recommendations}.
    Priority: LEARNING_PHASE > BUDGET_LIMITED > AUDIENCE_SATURATED >
              CREATIVE_FATIGUE > OFFER_PROBLEM > HEALTHY
    """
    ctr = float(metrics.get("ctr", 0.02))
    cvr = float(metrics.get("cvr", 0.03))
    budget_util = float(metrics.get("budget_util", 0.5))
    impression_share = float(metrics.get("impression_share", 0.3))
    learning_phase = str(metrics.get("learning_phase", "")).upper()
    frequency = float(metrics.get("frequency", 1.0))
    ctr_benchmark = float(metrics.get("ctr_benchmark", 0.005))

    if learning_phase in ("LEARNING", "RELEARNING"):
        return {
            "diagnosis": "LEARNING_PHASE",
            "confidence": 0.95,
            "recommendations": [
                "Avoid editing the ad set — wait for learning to complete (50 optimisation events).",
                "Do not change budget by more than 20% during learning.",
            ],
        }

    if budget_util > 0.95:
        return {
            "diagnosis": "BUDGET_LIMITED",
            "confidence": round(min(1.0, 0.7 + (budget_util - 0.95) * 6), 2),
            "recommendations": [
                "Increase daily budget by 20-40%.",
                "Duplicate the ad set with a higher budget cap.",
            ],
        }

    if impression_share > 0.70:
        return {
            "diagnosis": "AUDIENCE_SATURATED",
            "confidence": round(min(1.0, 0.6 + impression_share * 0.3), 2),
            "recommendations": [
                "Expand Lookalike audience percentage (e.g. 1% → 3%).",
                "Add Advantage+ audience broadening.",
                "Test a new geo or demographic segment.",
            ],
        }

    if frequency > 4.0 or ctr < ctr_benchmark * 0.5:
        confidence = 0.6
        if frequency > 4.0 and ctr < ctr_benchmark * 0.5:
            confidence = 0.85
        return {
            "diagnosis": "CREATIVE_FATIGUE",
            "confidence": confidence,
            "recommendations": [
                "Refresh creative assets — replace top 2 fatigued ads.",
                "Introduce new hook variant.",
                "Rotate into Graveyard creatives if their fatigue has reset.",
            ],
        }

    if ctr >= ctr_benchmark and cvr < 0.01:
        return {
            "diagnosis": "OFFER_PROBLEM",
            "confidence": 0.75,
            "recommendations": [
                "Review landing page offer — price/value perception may be off.",
                "Test an alternative guarantee or bundle.",
                "Check product_facts for accuracy issues.",
            ],
        }

    return {
        "diagnosis": "HEALTHY",
        "confidence": 0.8,
        "recommendations": ["No action required — continue monitoring."],
    }


def generate_offers(base_offer: dict, context: dict) -> list[dict]:
    """
    Generates 3-5 offer variants from a base offer.

    base_offer: {price, usp, guarantee_days}
    context: {product_name, category, avg_order_value, currency}

    Returns list of offer dicts with {label, price, usp, guarantee_days, price_anchor, expected_value_multiplier}.
    """
    price = float(base_offer.get("price", 0))
    usp = str(base_offer.get("usp", ""))
    guarantee = int(base_offer.get("guarantee_days", 30))
    product = str(context.get("product_name", "Product"))
    currency = str(context.get("currency", "USD"))

    if price <= 0:
        price = float(context.get("avg_order_value", 99))

    offers = [
        {
            "label": "baseline",
            "price": round(price, 2),
            "usp": usp,
            "guarantee_days": guarantee,
            "price_anchor": None,
            "expected_value_multiplier": 1.0,
        },
        {
            "label": "extended_guarantee",
            "price": round(price, 2),
            "usp": f"{usp} + {guarantee * 2}-day guarantee",
            "guarantee_days": guarantee * 2,
            "price_anchor": None,
            "expected_value_multiplier": 1.08,
        },
        {
            "label": "anchor_price",
            "price": round(price, 2),
            "usp": f"{usp} (was {round(price * 1.4, 2)} {currency})",
            "guarantee_days": guarantee,
            "price_anchor": round(price * 1.4, 2),
            "expected_value_multiplier": 1.15,
        },
        {
            "label": "bundle",
            "price": round(price * 1.35, 2),
            "usp": f"{usp} + bonus kit",
            "guarantee_days": guarantee,
            "price_anchor": round(price * 1.75, 2),
            "expected_value_multiplier": 1.25,
        },
        {
            "label": "trial",
            "price": round(price * 0.3, 2),
            "usp": f"Try {product} for 14 days — then {round(price, 2)} {currency}",
            "guarantee_days": 14,
            "price_anchor": None,
            "expected_value_multiplier": 0.85,
        },
    ]
    return offers


def rank_offers(offers: list[dict], scores: dict) -> list[dict]:
    """
    Ranks offer variants by expected value.

    scores: {label: {cvr_estimate, revenue_per_conversion}}
    expected_value = cvr_estimate * revenue_per_conversion * price_adjustment
    """
    for offer in offers:
        label = offer.get("label", "")
        s = scores.get(label, {})
        cvr_est = float(s.get("cvr_estimate", 0.03))
        rev = float(s.get("revenue_per_conversion", offer.get("price", 0)))
        ev = cvr_est * rev
        offer["expected_value"] = round(ev, 4)

    offers.sort(key=lambda o: o.get("expected_value", 0), reverse=True)
    for i, offer in enumerate(offers):
        offer["rank"] = i + 1
    return offers
