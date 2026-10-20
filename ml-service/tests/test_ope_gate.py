"""Audit fix 1.1: passes_ope_gate was imported by routers/analyze.py but never defined."""
from app.services.ope_service import passes_ope_gate


def test_pass_verdict_with_sufficient_ess_passes():
    assert passes_ope_gate({"verdict": "PASS", "ess": 250.0}, min_ess=200.0) is True


def test_fail_verdict_never_passes():
    assert passes_ope_gate({"verdict": "FAIL", "ess": 1000.0}, min_ess=200.0) is False


def test_insufficient_data_never_passes():
    assert passes_ope_gate({"verdict": "INSUFFICIENT_DATA", "ess": 1000.0}, min_ess=200.0) is False


def test_pass_verdict_below_min_ess_fails():
    assert passes_ope_gate({"verdict": "PASS", "ess": 50.0}, min_ess=200.0) is False
