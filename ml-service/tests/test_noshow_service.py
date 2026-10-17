from app.services.noshow_service import (
    predict_noshow,
    get_followup_audience_override,
    compute_slot_fill_probability,
)


def test_predict_noshow_high_risk():
    """Far booking + unknown tz + non-accredited + low ticket → HIGH risk."""
    result = predict_noshow(
        booking_distance_h=120,
        lead_tz_known=False,
        ticket_bucket="LT25K",
        self_accredited=False,
        device_type="MOBILE",
        is_international=True,
    )
    assert result["risk_tier"] == "HIGH"
    assert result["noshow_prob"] >= 0.45


def test_predict_noshow_low_risk():
    """Very short booking + tz known + accredited + GT250K + desktop → LOW/MED risk (≤0.40)."""
    result = predict_noshow(
        booking_distance_h=1,  # <2h → 0.08 penalty but still low overall
        lead_tz_known=True,
        ticket_bucket="GT250K",
        self_accredited=True,
        device_type="DESKTOP",
        is_international=False,
    )
    assert result["noshow_prob"] < 0.42
    assert result["risk_tier"] in ("LOW", "MED")


def test_base_rate_no_features():
    """Default features only → around base noshow rate."""
    result = predict_noshow(
        booking_distance_h=None,
        lead_tz_known=True,
        ticket_bucket="50_100K",
        self_accredited=True,
        device_type="DESKTOP",
        is_international=False,
    )
    assert 0.28 <= result["noshow_prob"] <= 0.36


def test_rule_based_model_version():
    result = predict_noshow(None, True, "50_100K", True, "DESKTOP", False)
    assert result["model_version"] == "rule_based_v1"
    assert "0.70" in result["auc_note"]


def test_slot_fill_all_noshow():
    """3 leads all p=0.99 → p_at_least_one_shows ≈ 0.03."""
    result = compute_slot_fill_probability(3, [0.99, 0.99, 0.99])
    assert result["p_at_least_one_shows"] < 0.05


def test_slot_fill_likely_show():
    """1 lead, noshow_prob=0.30 → p_at_least_one=0.70."""
    result = compute_slot_fill_probability(1, [0.30])
    assert abs(result["p_at_least_one_shows"] - 0.70) < 0.01


def test_slot_fill_empty():
    """No leads → p_at_least_one=1.0 (no-show impossible)."""
    result = compute_slot_fill_probability(0, [])
    assert result["p_at_least_one_shows"] == 1.0


def test_followup_override_high():
    override = get_followup_audience_override("HIGH")
    assert override["ghl_tag"] == "high_risk_noshow"
    assert "CALL" in override["preferred_channels"]
    assert override["max_touches"] == 6


def test_followup_override_low():
    override = get_followup_audience_override("LOW")
    assert override["ghl_tag"] is None
    assert override["max_touches"] == 3
