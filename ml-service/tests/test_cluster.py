"""
Tests for Behavioral Cluster Service (Phase 5-1, §7.3 Этап 1).

Fast unit tests — no FastAPI, no DB, no API calls.
Imports ONLY from app.services.cluster_service.
"""
import numpy as np
import pytest

from app.services.cluster_service import (
    N_FEATURES,
    FEATURE_NAMES,
    RULE_ARCHETYPES,
    CLUSTER_BLOCK_RULES,
    extract_features,
    fit_clusters,
    assign_cluster,
    rule_based_label,
    personalise_block_order,
    compute_ips_weight,
    validate_ips_coverage,
)


# ──────────────────────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────────────────────

def make_session(
    started="2024-01-01T10:00:00Z",
    last_event="2024-01-01T10:02:00Z",
    device="desktop",
    crm_user_id=None,
) -> dict:
    return {
        "started_at":   started,
        "last_event_at": last_event,
        "device":        device,
        "crm_user_id":   crm_user_id,
    }


def make_scroll_event(depth: float) -> dict:
    return {"event_type": "SCROLL", "event_data": {"scroll_depth": depth}}


def make_block_view(block_type: str, duration_sec: float) -> dict:
    return {
        "event_type": "BLOCK_VIEW",
        "event_data": {"block_type": block_type, "duration_sec": duration_sec},
    }


# ──────────────────────────────────────────────────────────────────────────────
# extract_features
# ──────────────────────────────────────────────────────────────────────────────

def test_extract_features_returns_correct_shape():
    feat = extract_features([], make_session())
    assert feat.shape == (N_FEATURES,)
    assert feat.dtype == np.float32


def test_extract_features_empty_events_all_zeros():
    feat = extract_features([], make_session())
    assert float(feat.sum()) == 0.0


def test_extract_features_session_duration():
    session = make_session(
        started="2024-01-01T10:00:00Z",
        last_event="2024-01-01T10:01:30Z",
    )
    feat = extract_features([make_scroll_event(0.5)], session)
    idx = FEATURE_NAMES.index("session_duration_sec")
    assert abs(feat[idx] - 90.0) < 1.0


def test_extract_features_scroll_depth():
    events = [make_scroll_event(0.3), make_scroll_event(0.8), make_scroll_event(0.5)]
    feat = extract_features(events, make_session())
    idx = FEATURE_NAMES.index("scroll_depth_max")
    assert abs(feat[idx] - 0.8) < 0.01


def test_extract_features_block_price_time():
    events = [
        make_block_view("pricing_block", 15.0),
        make_block_view("plan_block", 5.0),
    ]
    feat = extract_features(events, make_session())
    idx = FEATURE_NAMES.index("block_price_time")
    assert abs(feat[idx] - 20.0) < 0.01


def test_extract_features_block_proof_time():
    events = [make_block_view("proof_block", 30.0), make_block_view("testimonials", 10.0)]
    feat = extract_features(events, make_session())
    idx = FEATURE_NAMES.index("block_proof_time")
    assert abs(feat[idx] - 40.0) < 0.01


def test_extract_features_form_started_and_submitted():
    events = [
        {"event_type": "FORM_START",  "event_data": {}},
        {"event_type": "FORM_SUBMIT", "event_data": {}},
    ]
    feat = extract_features(events, make_session())
    assert feat[FEATURE_NAMES.index("form_started")] == 1.0
    assert feat[FEATURE_NAMES.index("form_submitted")] == 1.0


def test_extract_features_form_started_not_submitted():
    events = [{"event_type": "FORM_START", "event_data": {}}]
    feat = extract_features(events, make_session())
    assert feat[FEATURE_NAMES.index("form_started")] == 1.0
    assert feat[FEATURE_NAMES.index("form_submitted")] == 0.0


def test_extract_features_mobile_device():
    feat = extract_features([make_scroll_event(0.1)], make_session(device="mobile"))
    assert feat[FEATURE_NAMES.index("is_mobile")] == 1.0


def test_extract_features_desktop_not_mobile():
    feat = extract_features([make_scroll_event(0.1)], make_session(device="desktop"))
    assert feat[FEATURE_NAMES.index("is_mobile")] == 0.0


def test_extract_features_repeat_visit():
    session = make_session(crm_user_id="user-123")
    feat = extract_features([make_scroll_event(0.1)], session)
    assert feat[FEATURE_NAMES.index("is_repeat_visit")] == 1.0


def test_extract_features_event_count():
    events = [make_scroll_event(0.1)] * 7
    feat = extract_features(events, make_session())
    assert feat[FEATURE_NAMES.index("event_count")] == 7.0


# ──────────────────────────────────────────────────────────────────────────────
# fit_clusters
# ──────────────────────────────────────────────────────────────────────────────

def _make_feature_matrix(n: int = 50) -> np.ndarray:
    rng = np.random.default_rng(42)
    return rng.random((n, N_FEATURES)).astype(np.float32)


def test_fit_clusters_returns_correct_count():
    X = _make_feature_matrix(50)
    centroids = fit_clusters(X, n_clusters=7)
    assert len(centroids) == 7


def test_fit_clusters_centroid_has_all_features():
    X = _make_feature_matrix(50)
    centroids = fit_clusters(X, n_clusters=5)
    for c in centroids:
        for name in FEATURE_NAMES:
            assert name in c, f"Missing feature: {name}"


def test_fit_clusters_cluster_idx_present():
    X = _make_feature_matrix(50)
    centroids = fit_clusters(X, n_clusters=5)
    idxs = [c["_cluster_idx"] for c in centroids]
    assert sorted(idxs) == list(range(5))


def test_fit_clusters_session_count_sums_to_total():
    n = 50
    X = _make_feature_matrix(n)
    centroids = fit_clusters(X, n_clusters=5)
    total = sum(c["_session_count"] for c in centroids)
    assert total == n


def test_fit_clusters_raises_for_too_few_sessions():
    X = _make_feature_matrix(3)
    with pytest.raises(ValueError, match="Need at least"):
        fit_clusters(X, n_clusters=7)


def test_fit_clusters_n_clusters_bounds_5_to_8():
    X = _make_feature_matrix(50)
    for k in range(5, 9):
        result = fit_clusters(X, n_clusters=k)
        assert len(result) == k


# ──────────────────────────────────────────────────────────────────────────────
# assign_cluster
# ──────────────────────────────────────────────────────────────────────────────

def _make_centroids():
    X = _make_feature_matrix(50)
    return fit_clusters(X, n_clusters=7)


def test_assign_cluster_returns_valid_index():
    centroids = _make_centroids()
    features = extract_features([make_scroll_event(0.5)], make_session())
    idx = assign_cluster(centroids, features)
    assert 0 <= idx <= 6


def test_assign_cluster_empty_centroids_returns_zero():
    features = extract_features([], make_session())
    assert assign_cluster([], features) == 0


def test_assign_cluster_nearest_to_identical_centroid():
    """Session whose features match centroid 2 exactly should be assigned to 2."""
    centroids = [
        {**{n: float(i * 10) for n in FEATURE_NAMES}, "_cluster_idx": i, "_session_count": 5}
        for i in range(5)
    ]
    # Features identical to centroid 2
    target_feat = np.array(
        [float(2 * 10)] * N_FEATURES, dtype=np.float32
    )
    idx = assign_cluster(centroids, target_feat)
    assert idx == 2


# ──────────────────────────────────────────────────────────────────────────────
# rule_based_label
# ──────────────────────────────────────────────────────────────────────────────

def _feat_from_dict(**kwargs) -> np.ndarray:
    f = {n: 0.0 for n in FEATURE_NAMES}
    f.update(kwargs)
    return np.array([f[n] for n in FEATURE_NAMES], dtype=np.float32)


def test_rule_label_casual_bounce():
    feat = _feat_from_dict(session_duration_sec=5.0, scroll_depth_max=0.05)
    assert rule_based_label(feat) == "casual_bounce"


def test_rule_label_form_abandoner():
    feat = _feat_from_dict(
        session_duration_sec=60.0,
        form_started=1.0,
        form_submitted=0.0,
    )
    assert rule_based_label(feat) == "form_abandoner"


def test_rule_label_engaged_returner():
    feat = _feat_from_dict(
        session_duration_sec=120.0,
        is_repeat_visit=1.0,
    )
    assert rule_based_label(feat) == "engaged_returner"


def test_rule_label_price_seeker():
    feat = _feat_from_dict(
        session_duration_sec=60.0,
        block_price_time=20.0,
        block_proof_time=5.0,
        block_cta_time=2.0,
    )
    assert rule_based_label(feat) == "price_seeker"


def test_rule_label_proof_seeker():
    feat = _feat_from_dict(
        session_duration_sec=60.0,
        block_proof_time=25.0,
        block_price_time=5.0,
        block_cta_time=2.0,
    )
    assert rule_based_label(feat) == "proof_seeker"


def test_rule_label_ready_to_buy():
    feat = _feat_from_dict(
        session_duration_sec=20.0,
        scroll_depth_max=0.7,
        block_cta_time=5.0,
    )
    assert rule_based_label(feat) == "ready_to_buy"


def test_rule_label_researcher_fallback():
    feat = _feat_from_dict(
        session_duration_sec=180.0,
        scroll_depth_max=0.9,
        block_price_time=10.0,
        block_proof_time=10.0,
    )
    assert rule_based_label(feat) == "researcher"


def test_rule_labels_cover_all_archetypes():
    assert set(RULE_ARCHETYPES.keys()) == {
        "casual_bounce", "price_seeker", "proof_seeker",
        "ready_to_buy", "researcher", "form_abandoner", "engaged_returner",
    }


# ──────────────────────────────────────────────────────────────────────────────
# personalise_block_order
# ──────────────────────────────────────────────────────────────────────────────

DEFAULT_ORDER = ["hero_block", "proof_block", "pricing_block", "faq_block", "cta_block"]


def test_personalise_price_seeker_moves_pricing_first():
    result = personalise_block_order(DEFAULT_ORDER, "price_seeker")
    assert result.index("pricing_block") < result.index("proof_block")


def test_personalise_proof_seeker_moves_proof_first():
    result = personalise_block_order(DEFAULT_ORDER, "proof_seeker")
    assert result.index("proof_block") == 0


def test_personalise_ready_to_buy_cta_near_top():
    result = personalise_block_order(DEFAULT_ORDER, "ready_to_buy")
    assert result.index("cta_block") < result.index("pricing_block")


def test_personalise_unknown_label_unchanged():
    result = personalise_block_order(DEFAULT_ORDER, "unknown_label")
    assert result == DEFAULT_ORDER


def test_personalise_empty_order_unchanged():
    result = personalise_block_order([], "price_seeker")
    assert result == []


def test_personalise_same_length_as_default():
    for label in CLUSTER_BLOCK_RULES:
        result = personalise_block_order(DEFAULT_ORDER, label)
        assert len(result) == len(DEFAULT_ORDER), f"Length mismatch for {label}"


def test_personalise_no_new_blocks_added():
    result = personalise_block_order(DEFAULT_ORDER, "price_seeker")
    assert set(result) == set(DEFAULT_ORDER)


def test_personalise_with_allowed_ids_only_reorders_subset():
    allowed = ["proof_block", "pricing_block"]
    result = personalise_block_order(DEFAULT_ORDER, "price_seeker", allowed_block_ids=allowed)
    # Fixed blocks remain, only allowed blocks reordered
    for b in DEFAULT_ORDER:
        assert b in result


def test_personalise_fixed_blocks_keep_original_positions():
    allowed = ["proof_block", "pricing_block"]
    result = personalise_block_order(DEFAULT_ORDER, "price_seeker", allowed_block_ids=allowed)
    fixed = [b for b in DEFAULT_ORDER if b not in allowed]
    for fb in fixed:
        assert result.index(fb) == DEFAULT_ORDER.index(fb)


# ──────────────────────────────────────────────────────────────────────────────
# compute_ips_weight
# ──────────────────────────────────────────────────────────────────────────────

def test_ips_weight_uniform_propensity():
    # Uniform over 4 arms: p = 0.25, w = 4.0
    w = compute_ips_weight(0.25)
    assert abs(w - 4.0) < 0.01


def test_ips_weight_clips_at_max():
    w = compute_ips_weight(0.0001)  # near zero
    assert w == 10.0  # default max


def test_ips_weight_clips_at_min():
    w = compute_ips_weight(0.5)
    assert 1.9 < w < 2.1


def test_ips_weight_zero_propensity_returns_max():
    assert compute_ips_weight(0.0) == 10.0


def test_ips_weight_custom_clipping():
    w = compute_ips_weight(0.0001, clipping_max=5.0, clipping_min=0.05)
    assert w == 5.0


# ──────────────────────────────────────────────────────────────────────────────
# validate_ips_coverage
# ──────────────────────────────────────────────────────────────────────────────

def test_ips_coverage_empty_list():
    result = validate_ips_coverage([])
    assert result["ready"] is False
    assert result["effective_n"] == 0


def test_ips_coverage_sufficient_data():
    # 200 uniform weights: ESS = 200
    props = [0.25] * 200
    result = validate_ips_coverage(props, min_effective_n=100)
    assert result["ready"] is True
    assert result["effective_n"] >= 100


def test_ips_coverage_insufficient_data():
    props = [0.25] * 50
    result = validate_ips_coverage(props, min_effective_n=100)
    assert result["ready"] is False


def test_ips_coverage_returns_max_and_mean_weight():
    props = [0.5, 0.25, 0.1]
    result = validate_ips_coverage(props)
    assert result["max_weight"] >= result["mean_weight"]
    assert result["mean_weight"] > 0
