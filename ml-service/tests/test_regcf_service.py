from app.services.regcf_service import estimate_waitlist_value


def test_basic_estimate():
    result = estimate_waitlist_value(n_leads=100, p_convert_regcf=0.05, avg_ticket_regcf=10_000)
    assert result["estimated_value"] == 100 * 0.05 * 10_000
    assert result["ci_lower"] >= 0
    assert result["ci_upper"] >= result["estimated_value"]
    assert "frozen_note" in result
    assert "OI-3" in result["frozen_note"]


def test_zero_leads():
    result = estimate_waitlist_value(n_leads=0)
    assert result["estimated_value"] == 0.0
    assert result["n_leads"] == 0


def test_high_conversion():
    result = estimate_waitlist_value(n_leads=50, p_convert_regcf=0.20, avg_ticket_regcf=25_000)
    assert result["estimated_value"] == 50 * 0.20 * 25_000
    assert result["ci_lower"] < result["estimated_value"] < result["ci_upper"]


def test_ci_width_decreases_with_leads():
    small = estimate_waitlist_value(n_leads=10)
    large = estimate_waitlist_value(n_leads=1000)
    small_width = small["ci_upper"] - small["ci_lower"]
    large_width = large["ci_upper"] - large["ci_lower"]
    # More leads → narrower CI (relative)
    assert small_width / max(small["estimated_value"], 1) >= large_width / max(large["estimated_value"], 1)
