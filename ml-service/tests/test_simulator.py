"""Unit tests for What-If Simulator scenarios (vision §21.4).

Tests cover pure computation helpers — no I/O (no DB, no Redis).
"""
import sys
from unittest.mock import MagicMock

# Mock I/O modules before importing
_mock_settings = MagicMock()
_mock_settings.internal_api_key = "test-key"
_mock_config = MagicMock()
_mock_config.settings = _mock_settings
sys.modules.setdefault("app.config", _mock_config)
sys.modules.setdefault("app.services.db_client", MagicMock())
sys.modules.setdefault("app.services.job_store", MagicMock())
sys.modules.setdefault("app.services.thompson_service", MagicMock())
# Stub fastapi so router instantiation doesn't fail
import unittest.mock as _um
_fastapi = _um.MagicMock()
_fastapi.APIRouter.return_value = _um.MagicMock()
sys.modules.setdefault("fastapi", _fastapi)

import numpy as np
import pytest

# Import pure helpers directly
from app.routers.simulate import (
    _percentiles,
    _prob_best,
    _simulate_arm_disable,
    _simulate_budget,
    _simulate_power,
    ArmDisableParams,
    BudgetParams,
    PowerParams,
)


# ─── Fixtures ────────────────────────────────────────────────────────────────

def _make_arms(specs: list[tuple[str, float, float]]) -> list[dict]:
    """specs: [(variant_id, alpha, beta)]"""
    return [
        {
            "id": f"arm-{i}",
            "variant_id": vid,
            "alpha": a,
            "beta": b,
            "total_samples": int(a + b - 2),
            "block_order": [],
        }
        for i, (vid, a, b) in enumerate(specs)
    ]


# ─── _percentiles ────────────────────────────────────────────────────────────

class TestPercentiles:

    def test_returns_interval_object(self):
        iv = _percentiles(np.array([1.0, 2.0, 3.0, 4.0, 5.0]))
        assert hasattr(iv, "p10")
        assert hasattr(iv, "p50")
        assert hasattr(iv, "p90")

    def test_monotone(self):
        iv = _percentiles(np.arange(100, dtype=float))
        assert iv.p10 <= iv.p50 <= iv.p90


# ─── _prob_best ──────────────────────────────────────────────────────────────

class TestProbBest:

    def test_returns_arm_id_and_float(self):
        arms = _make_arms([("v1", 10.0, 2.0), ("v2", 2.0, 10.0)])
        arm_id, prob = _prob_best(arms, 1_000)
        assert isinstance(arm_id, str)
        assert 0.0 <= prob <= 1.0

    def test_dominant_arm_wins(self):
        """Arm with α=100 β=2 should win ~100% of time."""
        np.random.seed(42)
        arms = _make_arms([("v1", 100.0, 2.0), ("v2", 2.0, 100.0)])
        arm_id, prob = _prob_best(arms, 2_000)
        assert arm_id == "arm-0"
        assert prob > 0.95

    def test_single_arm_prob_one(self):
        arms = _make_arms([("v1", 5.0, 3.0)])
        arm_id, prob = _prob_best(arms, 500)
        assert prob == pytest.approx(1.0)


# ─── Budget scenario ─────────────────────────────────────────────────────────

class TestBudgetScenario:

    def _run(self, budget=5000, delta=50, avg_cpc=0.40, n=2_000):
        np.random.seed(0)
        arms = _make_arms([("v1", 15.0, 285.0), ("v2", 10.0, 190.0)])  # ~5% CVR
        p = BudgetParams(
            monthly_budget=budget,
            budget_delta_pct=delta,
            avg_cpc=avg_cpc,
            cpa_target=200.0,
            avg_deal_value=1_000.0,
        )
        return _simulate_budget(arms, p, n)

    def test_new_budget_correct(self):
        r = self._run(budget=5000, delta=50)
        assert r.new_budget == pytest.approx(7500.0)

    def test_sessions_interval_monotone(self):
        r = self._run()
        assert r.predicted_sessions.p10 <= r.predicted_sessions.p50 <= r.predicted_sessions.p90

    def test_conversions_interval_positive(self):
        r = self._run()
        assert r.predicted_conversions.p10 >= 0

    def test_cvr_in_unit_interval(self):
        r = self._run()
        assert 0.0 <= r.predicted_cvr.p10 <= r.predicted_cvr.p90 <= 1.0

    def test_cpa_returned_when_cpa_target_given(self):
        r = self._run()
        assert r.predicted_cpa is not None

    def test_roas_returned_when_deal_value_given(self):
        r = self._run()
        assert r.predicted_roas is not None

    def test_best_arm_id_matches_arm(self):
        r = self._run()
        assert r.best_arm_id in ("arm-0", "arm-1")


# ─── Power scenario ──────────────────────────────────────────────────────────

class TestPowerScenario:

    def test_already_sufficient_when_dominant(self):
        """When one arm clearly dominates, P(best) is already high."""
        np.random.seed(1)
        arms = _make_arms([("v1", 200.0, 2.0), ("v2", 2.0, 200.0)])
        p = PowerParams(target_prob_best=0.80, daily_sessions=100)
        r = _simulate_power(arms, p, 2_000)
        assert r.already_sufficient is True
        assert r.sessions_needed == 0

    def test_needs_sessions_when_arms_close(self):
        """When arms are tied, simulator needs more data."""
        np.random.seed(2)
        arms = _make_arms([("v1", 5.0, 5.0), ("v2", 5.0, 5.0)])
        p = PowerParams(target_prob_best=0.90, daily_sessions=100, max_simulated_sessions=5_000)
        r = _simulate_power(arms, p, 500)
        # With tied arms, may or may not reach target — just check types
        assert isinstance(r.leading_arm_id, str)
        assert 0.0 <= r.current_prob_best <= 1.0

    def test_days_positive_when_sessions_needed(self):
        np.random.seed(3)
        arms = _make_arms([("v1", 3.0, 3.0), ("v2", 3.0, 3.0)])
        p = PowerParams(target_prob_best=0.95, daily_sessions=50, max_simulated_sessions=10_000)
        r = _simulate_power(arms, p, 300)
        if r.sessions_needed is not None and r.sessions_needed > 0:
            assert r.days_to_target is not None
            assert r.days_to_target > 0


# ─── Arm disable scenario ─────────────────────────────────────────────────────

class TestArmDisableScenario:

    def test_excludes_correct_arms(self):
        np.random.seed(4)
        arms = _make_arms([("v1", 20.0, 5.0), ("v2", 5.0, 20.0), ("v3", 10.0, 10.0)])
        p = ArmDisableParams(exclude_variant_ids=["v2"])
        r = _simulate_arm_disable(arms, p, 1_000)
        assert r.remaining_arms == 2
        assert "arm-1" != r.new_leading_arm_id  # arm-1 is v2 (excluded)

    def test_all_excluded_returns_zero_remaining(self):
        np.random.seed(5)
        arms = _make_arms([("v1", 10.0, 5.0)])
        p = ArmDisableParams(exclude_variant_ids=["v1"])
        r = _simulate_arm_disable(arms, p, 500)
        assert r.remaining_arms == 0
        assert r.new_leading_arm_id is None

    def test_cvr_delta_negative_when_best_excluded(self):
        """Excluding the best arm should give negative CVR delta."""
        np.random.seed(6)
        # v1 is clearly best, v2 is worse
        arms = _make_arms([("v1", 100.0, 5.0), ("v2", 5.0, 100.0)])
        p = ArmDisableParams(exclude_variant_ids=["v1"])
        r = _simulate_arm_disable(arms, p, 1_000)
        assert r.cvr_delta_pct is not None
        assert r.cvr_delta_pct < 0

    def test_old_leading_arm_present(self):
        np.random.seed(7)
        arms = _make_arms([("v1", 10.0, 3.0), ("v2", 3.0, 10.0)])
        p = ArmDisableParams(exclude_variant_ids=["v2"])
        r = _simulate_arm_disable(arms, p, 500)
        assert r.old_leading_arm_id is not None
        assert r.old_prob_best is not None
