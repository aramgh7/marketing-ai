from app.services.post_call_service import (
    route_followup_arm,
    compute_objection_rates,
    summarise_call_batch,
)


def test_route_committed():
    result = route_followup_arm([], "COMMITTED")
    assert result["arm"] == "arm_wire_instructions"
    assert result["priority"] == "URGENT"


def test_route_no_show():
    result = route_followup_arm(["PRICE_CONCERN"], "NO_SHOW")
    assert result["arm"] == "arm_noshow_recovery"
    assert result["priority"] == "HIGH"


def test_route_cold():
    result = route_followup_arm(["PRICE_CONCERN"], "COLD")
    assert result["arm"] is None
    assert result["priority"] == "NONE"


def test_route_accred_unclear():
    result = route_followup_arm(["ACCRED_UNCLEAR"], "INTERESTED_FOLLOW")
    assert result["arm"] == "arm_accred_education"
    assert result["priority"] == "NORMAL"


def test_route_spouse_approval():
    result = route_followup_arm(["SPOUSE_APPROVAL"], "INTERESTED_FOLLOW")
    assert result["arm"] == "arm_couple_deck"


def test_route_default_arm():
    result = route_followup_arm([], "INTERESTED_FOLLOW")
    assert result["arm"] == "arm_standard_followup"


def test_objection_rates():
    notes = [
        {"objection_tags": ["PRICE_CONCERN", "ACCRED_UNCLEAR"], "call_outcome": "COMMITTED"},
        {"objection_tags": ["PRICE_CONCERN"], "call_outcome": "COLD"},
        {"objection_tags": ["PRICE_CONCERN"], "call_outcome": "INTERESTED_FOLLOW"},
        {"objection_tags": ["ACCRED_UNCLEAR"], "call_outcome": "COLD"},
        {"objection_tags": [], "call_outcome": "COMMITTED"},
    ]
    rates = compute_objection_rates(notes)
    price = next(r for r in rates if r["objection_tag"] == "PRICE_CONCERN")
    assert price["count_total"] == 3
    assert price["count_converted"] == 1
    assert price["conversion_rate"] == round(1/3, 4)

    accred = next(r for r in rates if r["objection_tag"] == "ACCRED_UNCLEAR")
    assert accred["count_total"] == 2
    assert accred["count_converted"] == 1


def test_objection_rates_json_string_tags():
    import json
    notes = [
        {"objection_tags": json.dumps(["RISK_CONCERN"]), "call_outcome": "COMMITTED"},
    ]
    rates = compute_objection_rates(notes)
    assert rates[0]["objection_tag"] == "RISK_CONCERN"
    assert rates[0]["count_converted"] == 1


def test_summarise_call_batch():
    notes = [
        {"call_outcome": "COMMITTED", "objection_tags": ["PRICE_CONCERN"]},
        {"call_outcome": "COMMITTED", "objection_tags": ["PRICE_CONCERN"]},
        {"call_outcome": "NO_SHOW",   "objection_tags": []},
        {"call_outcome": "COLD",      "objection_tags": ["TIMING_NOT_RIGHT"]},
        {"call_outcome": "INTERESTED_FOLLOW", "objection_tags": ["PRICE_CONCERN"]},
    ]
    summary = summarise_call_batch(notes, days=30)
    assert summary["total_calls"] == 5
    assert summary["commit_rate"] == round(2/5, 4)
    assert summary["noshow_rate"] == round(1/5, 4)
    top_tags = [t["tag"] for t in summary["top_objections"]]
    assert "PRICE_CONCERN" in top_tags
