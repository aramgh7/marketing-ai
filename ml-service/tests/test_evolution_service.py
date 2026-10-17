"""Tests for evolution_service.py — Block E v1.1 additions (vision §14.2, §13.2)."""
import pytest
from app.services.evolution_service import (
    _cosine,
    check_hook_body_coherence,
    check_graveyard_similarity,
    rank_by_glm_posterior,
    should_retry_graveyard_entry,
    rank_creatives_by_cvr,
    generate_candidates,
)


# ── _cosine ───────────────────────────────────────────────────────────────────

def test_cosine_identical_vectors():
    v = [1.0, 0.0, 0.0]
    assert abs(_cosine(v, v) - 1.0) < 1e-9


def test_cosine_orthogonal_vectors():
    assert abs(_cosine([1.0, 0.0], [0.0, 1.0])) < 1e-9


def test_cosine_zero_vector_returns_zero():
    assert _cosine([0.0, 0.0], [1.0, 0.0]) == 0.0


# ── check_hook_body_coherence ─────────────────────────────────────────────────

def test_coherence_above_threshold():
    v = [1.0, 0.0, 0.0]
    result = check_hook_body_coherence(v, v, threshold=0.65)
    assert result["coherent"] is True
    assert result["action"] == "PASS"


def test_coherence_below_threshold():
    result = check_hook_body_coherence([1.0, 0.0], [0.0, 1.0], threshold=0.65)
    assert result["coherent"] is False
    assert result["action"] == "DROP_OR_EDIT"


def test_coherence_returns_similarity():
    v = [1.0, 1.0]
    result = check_hook_body_coherence(v, v)
    assert abs(result["similarity"] - 1.0) < 1e-4


# ── check_graveyard_similarity ────────────────────────────────────────────────

def test_graveyard_empty_no_flag():
    result = check_graveyard_similarity([1.0, 0.0], [], "BROAD")
    assert result["flagged"] is False


def test_graveyard_same_audience_above_theta():
    candidate = [1.0, 0.0, 0.0]
    graveyard  = [{"audience": "BROAD", "embedding": [1.0, 0.0, 0.0], "atom_combo": "X"}]
    result = check_graveyard_similarity(candidate, graveyard, "BROAD", theta=0.85)
    assert result["flagged"] is True
    assert len(result["matches"]) == 1


def test_graveyard_different_audience_no_flag():
    candidate = [1.0, 0.0, 0.0]
    graveyard  = [{"audience": "RETARGETING", "embedding": [1.0, 0.0, 0.0], "atom_combo": "X"}]
    result = check_graveyard_similarity(candidate, graveyard, "BROAD", theta=0.85)
    assert result["flagged"] is False


def test_graveyard_below_theta_no_flag():
    candidate = [1.0, 0.0]
    graveyard  = [{"audience": "BROAD", "embedding": [0.0, 1.0], "atom_combo": "X"}]
    result = check_graveyard_similarity(candidate, graveyard, "BROAD", theta=0.85)
    assert result["flagged"] is False


# ── rank_by_glm_posterior ─────────────────────────────────────────────────────

def test_rank_empty_returns_empty():
    assert rank_by_glm_posterior([]) == []


def test_rank_orders_by_score_descending():
    candidates = [
        {"id": "A", "posterior_mean": 0.1, "posterior_std": 0.01},
        {"id": "B", "posterior_mean": 0.5, "posterior_std": 0.01},
        {"id": "C", "posterior_mean": 0.3, "posterior_std": 0.01},
    ]
    ranked = rank_by_glm_posterior(candidates)
    assert ranked[0]["id"] == "B"
    assert ranked[-1]["id"] == "A"


def test_rank_adds_ranking_score():
    candidates = [{"posterior_mean": 0.2, "posterior_std": 0.01}]
    result = rank_by_glm_posterior(candidates)
    assert "ranking_score" in result[0]


# ── should_retry_graveyard_entry ──────────────────────────────────────────────

def test_retry_market_verdict():
    result = should_retry_graveyard_entry({"anchor_verdict": "MARKET"})
    assert result["retry_candidate"] is True


def test_retry_fraud_flag():
    result = should_retry_graveyard_entry({"fraud_flag": True})
    assert result["retry_candidate"] is True


def test_no_retry_intrinsic():
    result = should_retry_graveyard_entry({"anchor_verdict": "NONE", "fraud_flag": False})
    assert result["retry_candidate"] is False
