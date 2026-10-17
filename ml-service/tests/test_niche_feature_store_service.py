import pytest
from app.services.niche_feature_store_service import (
    compute_cohens_d, should_publish, build_benchmark_record,
)


def test_cohens_d_no_effect():
    result = compute_cohens_d(0.5, 0.1, 100, 0.5, 0.1, 100)
    assert result["reliable"] is True
    assert abs(result["effect_size"]) < 0.01


def test_cohens_d_large_effect():
    # Groups differ by exactly 1 pooled SD → d ≈ 1.0
    result = compute_cohens_d(0.6, 0.1, 100, 0.5, 0.1, 100)
    assert result["reliable"] is True
    assert abs(result["effect_size"] - 1.0) < 0.05


def test_cohens_d_insufficient_n():
    result = compute_cohens_d(0.5, 0.1, 1, 0.5, 0.1, 1)
    assert result["reliable"] is False


def test_cohens_d_ci_ordered():
    result = compute_cohens_d(0.6, 0.1, 50, 0.4, 0.1, 50)
    assert result["ci_lower"] < result["ci_upper"]


def test_should_publish_true():
    assert should_publish(5) is True


def test_should_publish_boundary():
    assert should_publish(5) is True
    assert should_publish(4) is False


def test_should_publish_false():
    assert should_publish(3) is False


def test_build_benchmark_suppressed():
    rec = build_benchmark_record(
        "B2B", "FEED", "MOBILE", "BOUNCE_RATE",
        0.55, 0.1, 200, 0.50, 0.15, 1000, n_tenants=3
    )
    assert rec["is_published"] is False
    assert rec["suppressed_reason"] is not None


def test_build_benchmark_published():
    rec = build_benchmark_record(
        "B2B", "FEED", "MOBILE", "BOUNCE_RATE",
        0.55, 0.1, 200, 0.50, 0.15, 1000, n_tenants=6
    )
    assert rec["is_published"] is True
    assert rec["suppressed_reason"] is None


def test_first_party_source_default():
    rec = build_benchmark_record(
        "B2B", "FEED", "MOBILE", "CTR",
        0.02, 0.005, 300, 0.018, 0.004, 2000, n_tenants=5
    )
    assert rec["source"] == "FIRST_PARTY"


def test_build_benchmark_structure():
    rec = build_benchmark_record(
        "B2B", "STORY", "DESKTOP", "CVR",
        0.04, 0.01, 150, 0.03, 0.01, 800, n_tenants=7
    )
    for key in ("niche", "placement", "device_type", "metric", "effect_size",
                "ci_lower", "ci_upper", "n_tenants", "n_observations", "is_published", "source"):
        assert key in rec
