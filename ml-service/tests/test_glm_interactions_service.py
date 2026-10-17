"""Tests for glm_interactions_service.py — Block E (vision §15.8 task 1)."""
import pytest
from app.services.glm_interactions_service import (
    compute_bayes_factor_approx,
    rank_interactions_by_bf,
)


# ── compute_bayes_factor_approx ───────────────────────────────────────────────

def test_bf_include_when_strong_improvement():
    result = compute_bayes_factor_approx(n=200, r_squared_full=0.8, r_squared_restricted=0.4)
    assert result["decision"] == "INCLUDE"
    assert result["bayes_factor"] > 3.0


def test_bf_exclude_when_no_improvement():
    result = compute_bayes_factor_approx(n=200, r_squared_full=0.401, r_squared_restricted=0.4)
    assert result["decision"] in ("EXCLUDE", "AMBIGUOUS")


def test_bf_insufficient_data_returns_ambiguous():
    result = compute_bayes_factor_approx(n=2, r_squared_full=0.9, r_squared_restricted=0.1)
    assert result["decision"] == "AMBIGUOUS"
    assert result["reason"] == "Insufficient data"


def test_bf_result_keys():
    result = compute_bayes_factor_approx(n=100, r_squared_full=0.7, r_squared_restricted=0.5)
    expected_keys = {"bayes_factor", "decision", "delta_bic", "r_squared_full",
                     "r_squared_restricted", "threshold", "reason"}
    assert expected_keys.issubset(result.keys())


# ── rank_interactions_by_bf ───────────────────────────────────────────────────

def test_rank_empty_returns_empty():
    assert rank_interactions_by_bf([]) == []


def test_rank_ordered_descending_by_bf():
    candidates = [
        {"term": "A", "r2_full": 0.5, "r2_restricted": 0.45, "n": 100},
        {"term": "B", "r2_full": 0.9, "r2_restricted": 0.4,  "n": 200},
    ]
    ranked = rank_interactions_by_bf(candidates)
    assert ranked[0]["term"] == "B"
    assert ranked[0]["bayes_factor"] >= ranked[1]["bayes_factor"]
