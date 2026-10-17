from app.services.lead_value_service import predict_lead_value, calibrate_value_model


def test_predict_value_gt250k():
    """GT250K + high p_show → high value."""
    result = predict_lead_value(p_show=0.80, ticket_bucket="GT250K")
    assert result["predicted_value"] > 30_000
    assert result["components"]["e_ticket"] == 350_000


def test_predict_value_lt25k():
    """LT25K + low p_show → low value."""
    result = predict_lead_value(p_show=0.40, ticket_bucket="LT25K")
    assert result["predicted_value"] < 5_000
    assert result["components"]["e_ticket"] == 12_500


def test_predict_value_formula():
    """Check formula: p_show × p_verified × p_invest × e_ticket."""
    r = predict_lead_value(
        p_show=0.70, ticket_bucket="50_100K",
        p_verified_given_show=0.65, p_invest_given_verified=0.21,
    )
    expected = round(0.70 * 0.65 * 0.21 * 75_000, 2)
    assert abs(r["predicted_value"] - expected) < 1.0


def test_predict_value_unknown_bucket():
    """Unknown bucket → default e_ticket."""
    r = predict_lead_value(p_show=0.70, ticket_bucket=None)
    assert r["components"]["e_ticket"] == 75_000


def test_calibrate_insufficient():
    """<200 samples → calibrated=False."""
    predictions = [{"predicted_value": 1000.0, "realised_value": 950.0}] * 50
    result = calibrate_value_model(predictions, min_samples=200)
    assert result["calibrated"] is False
    assert result["n"] == 50


def test_calibrate_good_slope():
    """200+ samples, slope ≈ 1.0 → calibrated=True."""
    predictions = [
        {"predicted_value": float(i * 100), "realised_value": float(i * 100 + 10), "ticket_bucket": "50_100K"}
        for i in range(1, 201)
    ]
    result = calibrate_value_model(predictions, min_samples=200)
    assert result["calibrated"] is True
    assert 0.8 <= result["slope"] <= 1.2


def test_calibrate_bad_slope():
    """Slope ≈ 0.5 → calibrated=False."""
    predictions = [
        {"predicted_value": float(i * 200), "realised_value": float(i * 100)}
        for i in range(1, 201)
    ]
    result = calibrate_value_model(predictions, min_samples=200)
    assert result["calibrated"] is False
