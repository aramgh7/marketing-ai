"""Audit §2 (learning loop) — pure helpers in thompson_service, no I/O.

Mocks the I/O modules exactly like test_thompson_service.py so the module imports
without asyncpg / redis / pydantic_settings on the host.
"""
import sys
from unittest.mock import MagicMock

_ts_key = "app.services.thompson_service"
if _ts_key in sys.modules and isinstance(sys.modules[_ts_key], MagicMock):
    del sys.modules[_ts_key]
_mock_settings = MagicMock()
_mock_settings.reward_on_form_submit = True
_mock_config = MagicMock()
_mock_config.settings = _mock_settings
sys.modules.setdefault("app.config", _mock_config)
sys.modules.setdefault("app.services.db_client", MagicMock())
sys.modules.setdefault("app.services.job_store", MagicMock())

import json
import pytest

from app.services.thompson_service import (
    DECAY_FACTOR,
    REWARD_MAP,
    arm_for_decision,
    churn_penalty_delta,
    decay_toward_prior,
    holdout_bucket,
)


class TestHoldoutBucket:
    def test_non_hex_session_id_does_not_crash(self):
        # audit 2.5: int(sid[:8], 16) raised ValueError for cookie strings like this
        assert 0 <= holdout_bucket("sess_zzzz-not-hex") < 10_000

    def test_deterministic(self):
        assert holdout_bucket("abc-123") == holdout_bucket("abc-123")

    def test_spread(self):
        buckets = {holdout_bucket(f"s{i}") for i in range(2000)}
        assert len(buckets) > 1500

    def test_none_is_random_but_in_range(self):
        assert 0 <= holdout_bucket(None) < 10_000


class TestDecay:
    def test_one_day_is_one_step(self):
        assert decay_toward_prior(11.0, 1) == pytest.approx(1 + DECAY_FACTOR * 10)

    def test_ten_days_is_ten_steps(self):
        # audit 6.22: previously one 0.99 step regardless of elapsed days
        assert decay_toward_prior(11.0, 10) == pytest.approx(1 + DECAY_FACTOR ** 10 * 10)

    def test_prior_is_fixed_point(self):
        assert decay_toward_prior(1.0, 30) == 1.0

    def test_never_below_prior(self):
        assert decay_toward_prior(1.5, 10_000) >= 1.0


class TestChurnPenaltyDelta:
    def test_fractional_penalty_is_skipped(self):
        # audit 2.7: 0.5 used to be applied as beta += 0.5 (forbidden by arch §4.4)
        assert churn_penalty_delta(0.5, 50.0, 10.0) == (0, False)

    def test_floor_applied(self):
        assert churn_penalty_delta(3.7, 50.0, 10.0) == (3, False)

    def test_cap_30_percent(self):
        delta, capped = churn_penalty_delta(100.0, 10.0, 5.0)   # cap = 4.5
        assert delta == 4 and capped is True

    def test_integer_type(self):
        assert isinstance(churn_penalty_delta(2.0, 1.0, 1.0)[0], int)


class TestArmForDecision:
    CANDS = [
        {"variant_id": "v1", "arm_id": "a1", "propensity": 0.3},
        {"variant_id": "v2", "arm_id": "a2", "propensity": 0.7},
    ]

    def test_resolves_from_list(self):
        assert arm_for_decision("v2", self.CANDS) == "a2"

    def test_resolves_from_json_string(self):
        assert arm_for_decision("v1", json.dumps(self.CANDS)) == "a1"

    def test_unknown_variant(self):
        assert arm_for_decision("v9", self.CANDS) is None

    def test_bad_json(self):
        assert arm_for_decision("v1", "{not json") is None


def test_reward_map_has_a_beta_entry_for_the_sweep():
    assert REWARD_MAP["NO_RESPONSE_7D"] == ("beta", 1.0)
