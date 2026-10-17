"""Tests for entropy_service — 7 unit tests."""
import pytest
from app.services.entropy_service import (
    compute_pairwise_similarities,
    compute_entropy_from_similarities,
    check_diversity_penalty,
    build_entropy_report,
)


# ── compute_pairwise_similarities ─────────────────────────────────────────────

def test_pairwise_identical_vectors():
    v = [1.0, 0.0, 0.0]
    sims = compute_pairwise_similarities([v, v, v])
    assert len(sims) == 3  # 3*(3-1)/2
    assert all(abs(s - 1.0) < 1e-6 for s in sims)


def test_pairwise_orthogonal_vectors():
    v1 = [1.0, 0.0]
    v2 = [0.0, 1.0]
    sims = compute_pairwise_similarities([v1, v2])
    assert len(sims) == 1
    assert abs(sims[0]) < 1e-6


# ── compute_entropy_from_similarities ────────────────────────────────────────

def test_entropy_identical_hooks():
    # mean_sim = 1.0 → entropy = 0.0 (no diversity)
    sims = [1.0, 1.0, 1.0]
    assert compute_entropy_from_similarities(sims) == 0.0


def test_entropy_orthogonal_hooks():
    # mean_sim = 0.0 → entropy = 1.0 (full diversity)
    sims = [0.0, 0.0, 0.0]
    assert compute_entropy_from_similarities(sims) == 1.0


def test_entropy_empty_similarities():
    assert compute_entropy_from_similarities([]) == 1.0


# ── check_diversity_penalty ───────────────────────────────────────────────────

def test_diversity_penalty_too_few_tenants():
    r = check_diversity_penalty(0.1, 3, "b2b_manufacturing")
    assert r["diversity_flag"] is False
    assert r["diversity_insufficient_tenants"] is True


def test_diversity_penalty_flagged():
    r = check_diversity_penalty(0.1, 6, "b2b_manufacturing")
    assert r["diversity_flag"] is True
    assert "recommendation" in r


def test_diversity_penalty_healthy():
    r = check_diversity_penalty(0.8, 6, "b2b_manufacturing")
    assert r["diversity_flag"] is False


# ── build_entropy_report ──────────────────────────────────────────────────────

def test_build_entropy_report_not_enough_tenants():
    r = build_entropy_report("test_niche", [], 3)
    assert r["diversity_flag"] is False
    assert "skipped_reason" in r


def test_build_entropy_report_diverse():
    # 5 orthogonal 2-d unit vectors → high entropy
    import math
    angles = [i * math.pi / 5 for i in range(5)]
    embeddings = [[math.cos(a), math.sin(a)] for a in angles]
    r = build_entropy_report("manufacturing", embeddings, 5)
    assert r["tenant_count"] == 5
    assert r["entropy"] > 0.3
    assert r["diversity_flag"] is False
