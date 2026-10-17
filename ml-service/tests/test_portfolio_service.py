"""Tests for portfolio_service — 7 unit tests."""
import pytest
from app.services.portfolio_service import (
    check_sufficient_history,
    compute_portfolio_allocation,
)


# ── check_sufficient_history ──────────────────────────────────────────────────

def test_history_sufficient():
    r = check_sufficient_history("2025-01-01", "2025-12-31")
    assert r["sufficient"] is True
    assert r["history_months"] >= 6


def test_history_insufficient():
    r = check_sufficient_history("2025-10-01", "2025-11-01")
    assert r["sufficient"] is False


def test_history_invalid_dates():
    r = check_sufficient_history("not-a-date", "2025-12-31")
    assert r["sufficient"] is False
    assert "error" in r


# ── compute_portfolio_allocation ─────────────────────────────────────────────

def test_portfolio_allocation_basic():
    campaigns = [
        {"id": "c1", "name": "Camp A", "is_cbo": False, "daily_returns": [0.05, 0.04, 0.06, 0.05, 0.07] * 10},
        {"id": "c2", "name": "Camp B", "is_cbo": False, "daily_returns": [0.02, 0.01, 0.03, 0.02, 0.01] * 10},
    ]
    r = compute_portfolio_allocation(campaigns, 10000)
    assert r["is_recommendation_only"] is True
    allocations = r["allocations"]
    assert len(allocations) == 2
    total = sum(a["weight"] for a in allocations)
    assert abs(total - 1.0) < 1e-6
    # Higher Sharpe campaign gets more budget
    a_budget = next(a["budget"] for a in allocations if a["id"] == "c1")
    b_budget = next(a["budget"] for a in allocations if a["id"] == "c2")
    assert a_budget >= b_budget


def test_portfolio_cbo_excluded():
    campaigns = [
        {"id": "c1", "name": "Normal", "is_cbo": False, "daily_returns": [0.05] * 20},
        {"id": "cbo1", "name": "CBO Camp", "is_cbo": True, "daily_returns": [0.10] * 20},
    ]
    r = compute_portfolio_allocation(campaigns, 5000)
    assert len(r["allocations"]) == 1
    assert r["allocations"][0]["id"] == "c1"
    assert len(r["excluded_cbo"]) == 1


def test_portfolio_floor_applied():
    campaigns = [
        {"id": "c1", "name": "A", "is_cbo": False, "daily_returns": [0.1] * 20},
        {"id": "c2", "name": "B", "is_cbo": False, "daily_returns": [0.001] * 20},
        {"id": "c3", "name": "C", "is_cbo": False, "daily_returns": [0.1] * 20},
    ]
    r = compute_portfolio_allocation(campaigns, 1000)
    for a in r["allocations"]:
        assert a["weight"] >= 0.05 - 1e-6, f"Weight {a['weight']} below 5% floor"


def test_portfolio_all_cbo_returns_empty():
    campaigns = [
        {"id": "cbo1", "is_cbo": True, "daily_returns": [0.1] * 20},
    ]
    r = compute_portfolio_allocation(campaigns, 1000)
    assert r["allocations"] == []
    assert len(r["warnings"]) > 0


def test_portfolio_zero_returns_equal_weight():
    campaigns = [
        {"id": "c1", "is_cbo": False, "daily_returns": []},
        {"id": "c2", "is_cbo": False, "daily_returns": []},
    ]
    r = compute_portfolio_allocation(campaigns, 1000)
    for a in r["allocations"]:
        assert abs(a["weight"] - 0.5) < 1e-6
