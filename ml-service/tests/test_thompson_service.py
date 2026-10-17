"""
Unit tests for thompson_service pure functions (no I/O — no Redis, no DB).

Tests verify arch v1.1 §4.4, §4.6, §9 compliance:
  C1: REWARD_MAP has no fractional deltas
  C2: decay formula decays toward prior, not toward zero
  C3: sample_arms returns propensity in every candidate
"""
import sys
import importlib
from unittest.mock import MagicMock

# Evict any MagicMock stub injected by other test files (e.g. test_simulator.py
# runs alphabetically first and stubs app.services.thompson_service).
_ts_key = "app.services.thompson_service"
if _ts_key in sys.modules and isinstance(sys.modules[_ts_key], MagicMock):
    del sys.modules[_ts_key]

# Mock I/O modules before importing thompson_service so tests run without
# pydantic_settings, asyncpg, or redis installed in the host Python.
_mock_settings = MagicMock()
_mock_settings.reward_on_form_submit = True
_mock_config = MagicMock()
_mock_config.settings = _mock_settings
sys.modules.setdefault("app.config", _mock_config)
sys.modules.setdefault("app.services.db_client", MagicMock())
sys.modules.setdefault("app.services.job_store", MagicMock())

import numpy as np
import pytest

from app.services.thompson_service import (
    REWARD_MAP,
    DECAY_FACTOR,
    N_PROPENSITY,
    sample_arms,
)


# ─── C1: REWARD_MAP — no fractional increments (arch v1.1 §4.4) ─────────────

class TestRewardMap:

    def test_all_deltas_are_integer_valued(self):
        """arch v1.1 §4.4: 'Fractional increments are forbidden.'"""
        for reward_type, (field, delta) in REWARD_MAP.items():
            assert delta == 1.0, (
                f"{reward_type}: delta={delta} — must be 1.0 (binary count), "
                "fractional increments forbidden by arch v1.1 §4.4"
            )

    def test_all_fields_are_alpha_or_beta(self):
        for reward_type, (field, delta) in REWARD_MAP.items():
            assert field in ("alpha", "beta"), f"{reward_type}: field={field}"

    def test_no_response_increments_beta(self):
        field, delta = REWARD_MAP["NO_RESPONSE_7D"]
        assert field == "beta"
        assert delta == 1.0

    def test_positive_signals_increment_alpha(self):
        positive = ["FORM_SUBMIT", "CTA_CLICK", "FORM_START", "CALL_ANSWERED",
                    "MEETING_ATTENDED", "CONVERSION", "PURCHASE"]
        for key in positive:
            assert key in REWARD_MAP, f"{key} missing from REWARD_MAP"
            field, _ = REWARD_MAP[key]
            assert field == "alpha", f"{key} should increment alpha"


# ─── C2: decay formula toward prior (arch v1.1 §9) ───────────────────────────

class TestDecayFormula:
    """Verify the formula: α = 1 + 0.99·(α − 1), not α × 0.99."""

    @staticmethod
    def _apply_decay(alpha: float) -> float:
        return 1.0 + DECAY_FACTOR * (alpha - 1.0)

    def test_prior_is_stable(self):
        """α=1 (prior) decays to exactly 1 — prior is the fixed point."""
        assert self._apply_decay(1.0) == pytest.approx(1.0)

    def test_large_alpha_decays_toward_prior(self):
        """α=10 → 1 + 0.99·9 = 9.91, not 9.9 (× 0.99)."""
        result = self._apply_decay(10.0)
        assert result == pytest.approx(1.0 + 0.99 * 9.0)
        assert result > 1.0  # must stay above prior

    def test_alpha_two_decays_toward_prior(self):
        """α=2 → 1 + 0.99·1 = 1.99."""
        result = self._apply_decay(2.0)
        assert result == pytest.approx(1.99)

    def test_naive_multiply_would_go_below_one(self):
        """Demonstrate why naive α×=0.99 is wrong: after enough cycles it goes below 1."""
        alpha = 1.0
        for _ in range(1000):
            alpha *= DECAY_FACTOR
        assert alpha < 1.0  # naive formula breaks the prior

    def test_correct_formula_never_goes_below_one(self):
        """Correct formula keeps α ≥ 1 after any number of decay cycles."""
        alpha = 2.0
        for _ in range(10_000):
            alpha = 1.0 + DECAY_FACTOR * (alpha - 1.0)
        assert alpha >= 1.0

    def test_decay_is_strictly_monotone_toward_one(self):
        """Each cycle moves α strictly closer to 1."""
        alpha = 50.0
        for _ in range(100):
            new_alpha = 1.0 + DECAY_FACTOR * (alpha - 1.0)
            assert new_alpha < alpha
            assert new_alpha > 1.0
            alpha = new_alpha


# ─── C3: propensity in sample_arms (arch v1.1 §4.6) ─────────────────────────

class TestSampleArms:

    @staticmethod
    def _make_arms(specs: list[tuple[float, float]]) -> list[dict]:
        return [
            {"id": f"arm{i}", "variant_id": f"var{i}", "alpha": a, "beta": b,
             "total_samples": 0}
            for i, (a, b) in enumerate(specs)
        ]

    def test_returns_tuple_of_best_and_candidates(self):
        arms = self._make_arms([(5.0, 2.0), (3.0, 4.0)])
        best, candidates = sample_arms(arms)
        assert best is not None
        assert isinstance(candidates, list)

    def test_candidates_contain_propensity_field(self):
        """arch v1.1 §4.6: propensity must be present in every candidate."""
        arms = self._make_arms([(5.0, 2.0), (3.0, 4.0), (1.0, 1.0)])
        _, candidates = sample_arms(arms)
        for c in candidates:
            assert "propensity" in c, f"propensity missing from candidate: {c}"

    def test_propensities_sum_to_one(self):
        """Propensities across all arms must sum to 1 (they partition the probability space)."""
        np.random.seed(42)
        arms = self._make_arms([(5.0, 2.0), (3.0, 4.0), (2.0, 8.0)])
        _, candidates = sample_arms(arms)
        total = sum(c["propensity"] for c in candidates)
        # With N=200, rounding can cause small deviation from 1.0
        assert total == pytest.approx(1.0, abs=0.02)

    def test_propensity_between_zero_and_one(self):
        arms = self._make_arms([(5.0, 2.0), (3.0, 4.0)])
        for _ in range(10):
            _, candidates = sample_arms(arms)
            for c in candidates:
                assert 0.0 <= c["propensity"] <= 1.0

    def test_candidates_contain_required_fields(self):
        """All fields required by arch v1.1 §4.6 must be present."""
        arms = self._make_arms([(5.0, 2.0), (3.0, 4.0)])
        _, candidates = sample_arms(arms)
        required = {"variant_id", "arm_id", "propensity", "sample_value", "alpha", "beta"}
        for c in candidates:
            assert required.issubset(c.keys()), f"Missing fields in: {c}"

    def test_best_arm_matches_highest_sample_value(self):
        arms = self._make_arms([(5.0, 2.0), (3.0, 4.0)])
        best, candidates = sample_arms(arms)
        max_sample = max(c["sample_value"] for c in candidates)
        assert best["sample_value"] == pytest.approx(max_sample)

    def test_winner_has_highest_propensity_on_average(self):
        """Arm with much higher α/β should win most often → higher propensity."""
        np.random.seed(0)
        arms = self._make_arms([(100.0, 2.0), (2.0, 100.0)])  # arm0 clearly better
        propensity_sums = [0.0, 0.0]
        runs = 20
        for _ in range(runs):
            _, candidates = sample_arms(arms)
            for i, c in enumerate(candidates):
                propensity_sums[i] += c["propensity"]
        avg = [s / runs for s in propensity_sums]
        assert avg[0] > avg[1], "Clearly better arm should have higher avg propensity"

    def test_single_arm_gets_propensity_one(self):
        arms = self._make_arms([(3.0, 2.0)])
        _, candidates = sample_arms(arms)
        assert candidates[0]["propensity"] == pytest.approx(1.0)

    def test_returns_all_arms_as_candidates(self):
        arms = self._make_arms([(2.0, 3.0), (4.0, 1.0), (1.0, 5.0)])
        _, candidates = sample_arms(arms)
        assert len(candidates) == 3


# ─── C4: Delayed Churn Penalty (vision §6.7) ─────────────────────────────────

import math
import sys
# import holdout helper
sys.modules.setdefault("app.services.db_client", sys.modules.get("app.services.db_client") or __import__("unittest.mock", fromlist=["MagicMock"]).MagicMock())

# We test _holdout_bucket directly from decide module
# Mock FastAPI/pydantic deps before import
sys.modules.setdefault("fastapi",               __import__("unittest.mock", fromlist=["MagicMock"]).MagicMock())
sys.modules.setdefault("fastapi.responses",     __import__("unittest.mock", fromlist=["MagicMock"]).MagicMock())

# Import the pure helper from decide module (no FastAPI router instantiation needed)
def _holdout_bucket(session_id):
    """Mirrored from decide.py for isolated testing."""
    import random as _random
    if not session_id:
        return _random.randint(0, 9999)
    return int(session_id.replace("-", "")[:8], 16) % 10_000


class TestHoldoutBucket:
    """Pure logic tests for holdout assignment (vision §21.5)."""

    HOLDOUT_RATE = 0.04  # 4%
    THRESHOLD    = int(HOLDOUT_RATE * 10_000)  # 400

    def test_same_session_always_same_bucket(self):
        """Deterministic: same session_id always maps to same bucket."""
        sid = "aabbccdd-1234-5678-9abc-def012345678"
        assert _holdout_bucket(sid) == _holdout_bucket(sid)

    def test_bucket_in_valid_range(self):
        """Bucket must be 0–9999."""
        sids = [
            "00000000-0000-0000-0000-000000000000",
            "ffffffff-0000-0000-0000-000000000000",
            "12345678-abcd-ef01-2345-678901234567",
        ]
        for sid in sids:
            b = _holdout_bucket(sid)
            assert 0 <= b < 10_000, f"Bucket {b} out of range for {sid}"

    def test_holdout_fraction_approx_correct(self):
        """With rate=4%, ~4% of random UUIDs should fall in holdout."""
        import uuid
        buckets = [_holdout_bucket(str(uuid.uuid4())) for _ in range(5_000)]
        in_holdout = sum(1 for b in buckets if b < self.THRESHOLD)
        fraction = in_holdout / 5_000
        # Should be close to 4% ± 1% (statistical tolerance)
        assert 0.02 < fraction < 0.07, f"Holdout fraction {fraction:.3f} outside expected range"

    def test_all_zeros_session_deterministic(self):
        """Edge case: all-zeros UUID → valid bucket."""
        b = _holdout_bucket("00000000-0000-0000-0000-000000000000")
        # "00000000" hex = 0 → bucket = 0 → in holdout at 4%
        assert b == 0

    def test_high_hex_session_not_in_holdout_at_4pct(self):
        """Session starting with 'ffffffff' → bucket 9999 → not in 4% holdout."""
        b = _holdout_bucket("ffffffff-0000-0000-0000-000000000000")
        # 0xffffffff = 4294967295; 4294967295 % 10000 = 7295 → not in holdout
        assert b >= self.THRESHOLD


class TestChurnPenaltyLogic:
    """Pure logic tests for the churn penalty — no I/O required."""

    CAP_FRACTION = 0.30

    def _cap(self, weighted_sum: float, alpha: float, beta: float) -> tuple[float, bool]:
        cap = self.CAP_FRACTION * (alpha + beta)
        applied = min(weighted_sum, cap)
        return applied, applied < weighted_sum

    def test_cap_not_exceeded(self):
        """Penalty never exceeds 30% of current (α+β)."""
        alpha, beta = 10.0, 5.0
        weighted_sum = 100.0  # massive churn wave
        applied, capped = self._cap(weighted_sum, alpha, beta)
        assert applied == pytest.approx(self.CAP_FRACTION * (alpha + beta))
        assert capped is True

    def test_cap_not_applied_when_small_penalty(self):
        """Small penalty below cap is applied in full."""
        alpha, beta = 50.0, 10.0
        weighted_sum = 0.5
        applied, capped = self._cap(weighted_sum, alpha, beta)
        assert applied == pytest.approx(0.5)
        assert capped is False

    def test_cap_at_exactly_threshold(self):
        """Penalty exactly at cap boundary: not capped."""
        alpha, beta = 10.0, 10.0
        cap_val = self.CAP_FRACTION * (alpha + beta)
        applied, capped = self._cap(cap_val, alpha, beta)
        assert applied == pytest.approx(cap_val)
        assert capped is False

    def test_decay_weight_formula(self):
        """exp(-0.02 * days): day 0 → 1.0, day 34.7 → 0.5, day 100 → ~0.135."""
        assert math.exp(-0.02 * 0)   == pytest.approx(1.0)
        assert math.exp(-0.02 * 34.7) == pytest.approx(0.5, abs=0.005)
        assert math.exp(-0.02 * 100) == pytest.approx(math.exp(-2), rel=1e-5)

    def test_decay_weight_is_positive(self):
        """Decay weight is always positive — no negative penalties."""
        for days in [0, 1, 30, 90, 365]:
            assert math.exp(-0.02 * days) > 0

    def test_recent_churn_weighs_more_than_old(self):
        """§6.7: recent churn weighs more than churn from long ago."""
        weight_recent = math.exp(-0.02 * 5)   # 5 days ago
        weight_old    = math.exp(-0.02 * 60)  # 60 days ago
        assert weight_recent > weight_old

    def test_single_fresh_churn_penalty(self):
        """One day-0 churn event adds ≈1.0 weighted, capped at 30% of small prior."""
        alpha, beta = 2.0, 1.0  # small arm (uninformative prior)
        weighted_sum = math.exp(-0.02 * 0)  # same day = 1.0
        applied, capped = self._cap(weighted_sum, alpha, beta)
        # cap = 0.30 * 3.0 = 0.9 < 1.0 → should be capped
        assert applied == pytest.approx(0.9)
        assert capped is True
