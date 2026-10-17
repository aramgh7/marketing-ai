from app.services.segment_re_service import (
    compute_shrinkage_weight,
    compute_segment_effect,
    rank_atoms_by_segment,
    TAU_SQUARED,
)


def test_shrinkage_high_n():
    # Large n → weight close to 1 (trusts local estimate)
    w = compute_shrinkage_weight(1000, TAU_SQUARED)
    assert w > 0.99


def test_shrinkage_low_n():
    # Small n with tight prior (tau_sq=0.1) → heavy shrinkage toward global
    w = compute_shrinkage_weight(1, tau_squared=0.1)
    assert w < 0.2


def test_segment_effect_fallback():
    # n < min_n → returns global_mean as estimate
    result = compute_segment_effect(5, 10, global_mean=0.08, min_n=30)
    assert result["estimate"] == 0.08
    assert result["shrinkage"] == 1.0
    assert "using global prior" in result["note"]


def test_segment_effect_sufficient_data():
    # n >= min_n → shrinkage estimate between local and global
    result = compute_segment_effect(100, 1000, global_mean=0.05, min_n=30)
    local = 100 / 1000
    assert result["local_mean"] == local
    assert result["credible_lower"] <= result["estimate"] <= result["credible_upper"]
    assert 0.0 <= result["estimate"] <= 1.0
    assert result["n"] == 1000


def test_rank_atoms_by_segment_sorted():
    data = [
        {"atom_id": "A", "segment": "S1", "conversions": 50, "impressions": 500},
        {"atom_id": "B", "segment": "S1", "conversions": 10, "impressions": 500},
        {"atom_id": "C", "segment": "S2", "conversions": 80, "impressions": 500},
    ]
    result = rank_atoms_by_segment(data, global_mean=0.05)
    # Highest estimate first
    estimates = [r["estimate"] for r in result]
    assert estimates == sorted(estimates, reverse=True)


def test_rank_atoms_empty():
    assert rank_atoms_by_segment([], 0.05) == []
