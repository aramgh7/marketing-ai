"""
Tests for Propensity Model Service (Phase 5-2, §7.3 Этап 2).

Fast unit tests — no FastAPI, no DB, no API calls.
Imports ONLY from app.services.propensity_service and app.services.cluster_service.
"""
import io
import numpy as np
import pytest

from app.services.cluster_service import FEATURE_NAMES, extract_features
from app.services.propensity_service import (
    N_PROPENSITY_FEATURES,
    PROPENSITY_FEATURE_NAMES,
    extract_propensity_features,
    rule_based_score,
    determine_objection,
    score_session,
    fit_propensity_model,
    compute_uplift_score,
)


# ──────────────────────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────────────────────

def zero_cluster_features() -> np.ndarray:
    return np.zeros(len(FEATURE_NAMES), dtype=np.float32)


def _set_cluster_feat(**kwargs) -> np.ndarray:
    f = dict.fromkeys(FEATURE_NAMES, 0.0)
    f.update(kwargs)
    return np.array([f[n] for n in FEATURE_NAMES], dtype=np.float32)


def _set_prop_feat(**kwargs) -> np.ndarray:
    f = dict.fromkeys(PROPENSITY_FEATURE_NAMES, 0.0)
    f.update(kwargs)
    return np.array([f[n] for n in PROPENSITY_FEATURE_NAMES], dtype=np.float32)


def _make_training_data(n: int = 80, positive_frac: float = 0.3, seed: int = 42):
    rng = np.random.default_rng(seed)
    X = rng.random((n, N_PROPENSITY_FEATURES)).astype(np.float32)
    y = (rng.random(n) < positive_frac).astype(np.int32)
    # ensure at least one of each class
    y[0] = 0
    y[1] = 1
    return X, y


# ──────────────────────────────────────────────────────────────────────────────
# extract_propensity_features
# ──────────────────────────────────────────────────────────────────────────────

def test_extract_propensity_features_shape():
    cluster_feat = zero_cluster_features()
    feat = extract_propensity_features(cluster_feat, cluster_id=0)
    assert feat.shape == (N_PROPENSITY_FEATURES,)
    assert feat.dtype == np.float32


def test_extract_propensity_features_cluster_id_mapped():
    cluster_feat = zero_cluster_features()
    feat = extract_propensity_features(cluster_feat, cluster_id=3)
    idx = PROPENSITY_FEATURE_NAMES.index("cluster_id")
    assert feat[idx] == 3.0


def test_extract_propensity_features_negative_cluster_id_clamped():
    cluster_feat = zero_cluster_features()
    feat = extract_propensity_features(cluster_feat, cluster_id=-1)
    idx = PROPENSITY_FEATURE_NAMES.index("cluster_id")
    assert feat[idx] == 0.0


def test_extract_propensity_features_carries_cluster_fields():
    cluster_feat = _set_cluster_feat(
        block_price_time=15.0,
        scroll_depth_max=0.7,
        is_mobile=1.0,
    )
    feat = extract_propensity_features(cluster_feat, cluster_id=2)
    assert feat[PROPENSITY_FEATURE_NAMES.index("block_price_time")] == pytest.approx(15.0)
    assert feat[PROPENSITY_FEATURE_NAMES.index("scroll_depth_max")] == pytest.approx(0.7)
    assert feat[PROPENSITY_FEATURE_NAMES.index("is_mobile")] == pytest.approx(1.0)


# ──────────────────────────────────────────────────────────────────────────────
# rule_based_score
# ──────────────────────────────────────────────────────────────────────────────

def test_rule_score_form_submitted_high():
    feat = _set_prop_feat(
        form_submitted=1.0, form_started=1.0,
        session_duration_sec=60.0, scroll_depth_max=0.5,
    )
    assert rule_based_score(feat) >= 0.5


def test_rule_score_form_started_not_submitted_medium():
    feat = _set_prop_feat(form_started=1.0, session_duration_sec=60.0, event_count=10.0)
    score = rule_based_score(feat)
    assert 0.15 < score < 0.6


def test_rule_score_bounce_very_low():
    feat = _set_prop_feat(session_duration_sec=5.0, scroll_depth_max=0.02)
    assert rule_based_score(feat) < 0.1


def test_rule_score_clamped_0_to_1():
    feat = _set_prop_feat(
        form_submitted=1.0, is_repeat_visit=1.0, block_cta_time=10.0
    )
    s = rule_based_score(feat)
    assert 0.0 <= s <= 1.0


def test_rule_score_zero_features_clamped_to_zero():
    feat = _set_prop_feat()
    s = rule_based_score(feat)
    # zero session_duration (<10) and zero scroll (<0.1) both deduct 0.05 each,
    # bringing 0.05 base → -0.05, clamped to 0.0
    assert s == 0.0


# ──────────────────────────────────────────────────────────────────────────────
# determine_objection
# ──────────────────────────────────────────────────────────────────────────────

def test_objection_understanding_few_events():
    feat = _set_prop_feat(event_count=2.0, session_duration_sec=30.0)
    assert determine_objection(feat) == "understanding"


def test_objection_urgency_short_no_form():
    feat = _set_prop_feat(event_count=5.0, session_duration_sec=10.0, form_started=0.0)
    assert determine_objection(feat) == "urgency"


def test_objection_price():
    feat = _set_prop_feat(
        event_count=10.0,
        session_duration_sec=60.0,
        block_price_time=25.0,
        block_proof_time=5.0,
    )
    assert determine_objection(feat) == "price"


def test_objection_trust():
    feat = _set_prop_feat(
        event_count=10.0,
        session_duration_sec=60.0,
        block_proof_time=30.0,
        block_price_time=5.0,
    )
    assert determine_objection(feat) == "trust"


def test_objection_none_engaged():
    feat = _set_prop_feat(
        event_count=20.0,
        session_duration_sec=90.0,
        block_price_time=5.0,
        block_proof_time=5.0,
        form_started=1.0,
    )
    assert determine_objection(feat) is None


# ──────────────────────────────────────────────────────────────────────────────
# score_session (rule-based path, no model)
# ──────────────────────────────────────────────────────────────────────────────

def test_score_session_returns_dict():
    feat = _set_prop_feat(session_duration_sec=60.0, event_count=10.0)
    result = score_session(feat, model_bytes=None)
    assert "p_convert" in result
    assert "main_objection" in result


def test_score_session_p_convert_in_range():
    feat = _set_prop_feat(session_duration_sec=60.0, event_count=10.0)
    result = score_session(feat)
    assert 0.0 <= result["p_convert"] <= 1.0


def test_score_session_with_trained_model():
    X, y = _make_training_data(80)
    model_bytes = fit_propensity_model(X, y)
    feat = X[0]
    result = score_session(feat, model_bytes=model_bytes)
    assert 0.0 <= result["p_convert"] <= 1.0


# ──────────────────────────────────────────────────────────────────────────────
# fit_propensity_model
# ──────────────────────────────────────────────────────────────────────────────

def test_fit_returns_non_empty_bytes():
    X, y = _make_training_data(80)
    model_bytes = fit_propensity_model(X, y)
    assert isinstance(model_bytes, bytes)
    assert len(model_bytes) > 0


def test_fit_model_is_deserializable():
    """Model bytes use a safe serialisation format and produce valid probabilities."""
    X, y = _make_training_data(80)
    model_bytes = fit_propensity_model(X, y)
    assert isinstance(model_bytes, bytes)
    assert len(model_bytes) > 5

    # Verify format marker (ONNX preferred, SAFE hash-validated pickle as fallback)
    assert model_bytes[:4] in (b"ONNX", b"SAFE"), (
        f"Expected ONNX or SAFE format marker, got {model_bytes[:4]!r}"
    )

    # Verify all 5 sample scores are in [0, 1] using the service's scoring path
    for i in range(5):
        result = score_session(X[i], model_bytes)
        assert 0.0 <= result["p_convert"] <= 1.0, (
            f"p_convert={result['p_convert']} out of [0,1] for sample {i}"
        )


def test_fit_raises_for_too_few_samples():
    X = np.random.rand(10, N_PROPENSITY_FEATURES).astype(np.float32)
    y = np.array([0, 1] + [0] * 8, dtype=np.int32)
    with pytest.raises(ValueError, match="at least 30"):
        fit_propensity_model(X, y)


def test_fit_raises_for_single_class():
    X = np.random.rand(50, N_PROPENSITY_FEATURES).astype(np.float32)
    y = np.zeros(50, dtype=np.int32)
    with pytest.raises(ValueError, match="both classes"):
        fit_propensity_model(X, y)


def test_fit_raises_for_mismatched_lengths():
    X = np.random.rand(50, N_PROPENSITY_FEATURES).astype(np.float32)
    y = np.ones(40, dtype=np.int32)
    with pytest.raises(ValueError, match="same length"):
        fit_propensity_model(X, y)


def test_fit_different_hyperparams_produce_different_models():
    X, y = _make_training_data(100)
    b1 = fit_propensity_model(X, y, n_estimators=50)
    b2 = fit_propensity_model(X, y, n_estimators=200)
    assert b1 != b2


# ──────────────────────────────────────────────────────────────────────────────
# compute_uplift_score
# ──────────────────────────────────────────────────────────────────────────────

def test_uplift_positive():
    assert compute_uplift_score(0.4, 0.2) == pytest.approx(0.2)


def test_uplift_negative():
    assert compute_uplift_score(0.1, 0.3) == pytest.approx(-0.2)


def test_uplift_clamped_upper():
    assert compute_uplift_score(1.0, 0.0) == pytest.approx(1.0)


def test_uplift_clamped_lower():
    assert compute_uplift_score(0.0, 1.0) == pytest.approx(-1.0)


def test_uplift_zero():
    assert compute_uplift_score(0.3, 0.3) == pytest.approx(0.0)
