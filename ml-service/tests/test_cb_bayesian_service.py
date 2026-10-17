import pytest
from app.services.cb_bayesian_service import compute_cpa_exceedance_probability, l1_combined_check


def test_zero_clicks_no_trigger():
    result = compute_cpa_exceedance_probability(
        clicks=0, conversions=0, total_spend=500.0, target_cpa=50.0
    )
    assert result["p_exceed"] == 0.0
    assert result["should_trigger"] is False


def test_zero_spend_no_trigger():
    result = compute_cpa_exceedance_probability(
        clicks=100, conversions=0, total_spend=0.0, target_cpa=50.0
    )
    assert result["should_trigger"] is False


def test_zero_conversions_high_spend_triggers():
    # spend=1000, target=100, threshold=200 → 1000 > 200*0.5=100 → p=1.0
    result = compute_cpa_exceedance_probability(
        clicks=500, conversions=0, total_spend=1000.0, target_cpa=100.0
    )
    assert result["should_trigger"] is True
    assert result["p_exceed"] >= 0.9


def test_many_conversions_low_cpa_no_trigger():
    # 100 conv / 1000 clicks / spend=50 → CPA=0.5 << 2×target
    result = compute_cpa_exceedance_probability(
        clicks=1000, conversions=100, total_spend=50.0, target_cpa=50.0
    )
    assert result["should_trigger"] is False
    assert result["p_exceed"] < 0.1


def test_high_cpa_triggers():
    # 1 conversion / 200 clicks / spend=500 / target=50 → observed CPA=500 >> 2×50=100
    result = compute_cpa_exceedance_probability(
        clicks=200, conversions=1, total_spend=500.0, target_cpa=50.0
    )
    assert result["should_trigger"] is True


def test_p_exceed_between_0_and_1():
    for clicks, conversions, spend, target in [
        (100, 5, 200, 30),
        (50, 0, 100, 80),
        (1000, 100, 5000, 100),
    ]:
        result = compute_cpa_exceedance_probability(clicks, conversions, spend, target)
        assert 0.0 <= result["p_exceed"] <= 1.0, f"p_exceed out of range for ({clicks},{conversions},{spend},{target})"


def test_l1_combined_spend_trigger_overrides():
    # spend >= limit → should_pause True even if CPA is fine
    result = l1_combined_check(
        spend=1001.0, spend_limit=1000.0,
        clicks=1000, conversions=100, target_cpa=50.0
    )
    assert result["spend_trigger"] is True
    assert result["should_pause"] is True


def test_l1_combined_spend_ok_cpa_triggers():
    # spend < limit, but terrible CPA
    result = l1_combined_check(
        spend=50.0, spend_limit=10000.0,
        clicks=200, conversions=1, target_cpa=10.0
    )
    # observed CPA = 50, threshold = 20; should bayesian trigger
    assert result["spend_trigger"] is False
    assert result["should_pause"] is result["bayesian_trigger"]


def test_l1_combined_both_ok():
    result = l1_combined_check(
        spend=500.0, spend_limit=1000.0,
        clicks=1000, conversions=100, target_cpa=50.0
    )
    assert result["spend_trigger"] is False
    assert result["bayesian_trigger"] is False
    assert result["should_pause"] is False


def test_l1_combined_reason_field():
    result = l1_combined_check(
        spend=100.0, spend_limit=200.0,
        clicks=50, conversions=5, target_cpa=50.0
    )
    assert "P(CPA>2×target)" in result["reason"]
    assert "limit=200.00" in result["reason"]
