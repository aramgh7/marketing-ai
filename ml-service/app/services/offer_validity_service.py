"""
Offer validity mask — pure logic, no FastAPI/Pydantic imports.
Rules from vision §4.3a: discount/guarantee/urgency consistency checks.
Offers vary on landings only — not a creative test dimension (vision §4.3a auction bias).
"""
from __future__ import annotations
from typing import Optional


VALID_URGENCY_SOURCES = {"inventory_api", "crm_deadline", "seasonal_window"}


def validate_discount(discount_type: str, discount_depth: float) -> Optional[dict]:
    """discount_type=none => discount_depth must be 0."""
    if discount_type == "none" and discount_depth != 0:
        return {
            "field": "discount_depth",
            "value_given": str(discount_depth),
            "reason": "discount_type=none requires discount_depth=0",
        }
    return None


def validate_guarantee(guarantee_type: str, guarantee_days) -> Optional[dict]:
    """guarantee_type=none => guarantee_days must be NULL/None."""
    if guarantee_type == "none" and guarantee_days is not None:
        return {
            "field": "guarantee_days",
            "value_given": str(guarantee_days),
            "reason": "guarantee_type=none requires guarantee_days=null",
        }
    return None


def validate_urgency(has_real_urgency: bool, urgency_source: Optional[str]) -> Optional[dict]:
    """has_real_urgency=true => urgency_source must be an approved source. Fake timers are rejected."""
    if not has_real_urgency:
        return None
    if not urgency_source or urgency_source not in VALID_URGENCY_SOURCES:
        return {
            "field": "urgency_source",
            "value_given": str(urgency_source),
            "reason": (
                f"has_real_urgency=true requires urgency_source in "
                f"{sorted(VALID_URGENCY_SOURCES)}. Fake timers are rejected (vision §4.3a)."
            ),
        }
    return None


def validate_margin(
    estimated_margin: Optional[float],
    min_margin: Optional[float],
) -> Optional[dict]:
    """estimated_margin must be >= tenants.settings.min_margin (vision §18.5 hard constraint)."""
    if min_margin is None or estimated_margin is None:
        return None
    if estimated_margin < min_margin:
        return {
            "field": "estimated_margin",
            "value_given": str(estimated_margin),
            "reason": (
                f"estimated_margin={estimated_margin:.4f} < min_margin={min_margin:.4f} "
                f"(tenant margin floor — vision §18.5)"
            ),
        }
    return None


def check_installment_legal(offer_data: dict) -> bool:
    """Installment terms require legal review flag."""
    return bool(
        offer_data.get("has_installment")
        or offer_data.get("payment_type") == "installment"
        or offer_data.get("payment_terms") == "installment"
    )


def run_offer_validity_checks(
    offer_data: dict,
    min_margin: Optional[float] = None,
) -> dict:
    """
    Run all offer validity checks.

    Args:
        offer_data: offer fields (discount_type, discount_depth, guarantee_type,
                    guarantee_days, has_real_urgency, urgency_source, estimated_margin,
                    has_installment, payment_type)
        min_margin: tenant-level margin floor (from tenants.settings->>'min_margin')

    Returns:
        {"valid": bool, "violations": list[dict], "requires_legal_review": bool, "note": str}

    IMPORTANT: Offers vary on landings only — creatives.offer_variant is metadata,
    not a test dimension (vision §4.3a).
    """
    violations = []

    v = validate_discount(
        offer_data.get("discount_type", "none"),
        float(offer_data.get("discount_depth", 0)),
    )
    if v:
        violations.append(v)

    v = validate_guarantee(
        offer_data.get("guarantee_type", "none"),
        offer_data.get("guarantee_days"),
    )
    if v:
        violations.append(v)

    v = validate_urgency(
        bool(offer_data.get("has_real_urgency", False)),
        offer_data.get("urgency_source"),
    )
    if v:
        violations.append(v)

    v = validate_margin(
        offer_data.get("estimated_margin"),
        min_margin,
    )
    if v:
        violations.append(v)

    requires_legal = check_installment_legal(offer_data)

    return {
        "valid": len(violations) == 0,
        "violations": violations,
        "requires_legal_review": requires_legal,
        "note": "Offers vary on landings only — not a creative test dimension (vision §4.3a).",
    }
