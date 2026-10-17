import pytest
from app.services.hierarchy_prior_service import (
    build_niche_prior, build_client_prior, get_effective_prior, student_t_params,
)


def test_universal_fallback_no_niche():
    result = build_niche_prior("ctr", niche_effect_size=None)
    assert result["source"] == "UNIVERSAL"
    assert result["prior"]["distribution"] == "StudentT"


def test_niche_prior_student_t():
    result = build_niche_prior("ctr", niche_effect_size=0.5, niche_ci_lower=0.018, niche_ci_upper=0.022)
    assert result["source"] == "NICHE"
    assert result["prior"]["distribution"] == "StudentT"


def test_niche_prior_nu4():
    result = build_niche_prior("ctr", niche_effect_size=0.3, nu=4.0)
    assert result["prior"]["nu"] == 4.0


def test_niche_prior_mu_shifted():
    # With positive effect size, niche mu should be > universal mu
    result_no = build_niche_prior("ctr", niche_effect_size=None)
    result_yes = build_niche_prior("ctr", niche_effect_size=1.0)
    assert result_yes["prior"]["mu"] > result_no["prior"]["mu"]


def test_client_posterior_updates():
    niche_prior = build_niche_prior("ctr", niche_effect_size=None)
    result = build_client_prior("ctr", niche_prior, 100, client_mean=0.04, client_std=0.01)
    assert result["source"] == "CLIENT_POSTERIOR"
    assert result["prior"]["distribution"] == "Normal"


def test_client_posterior_weights_sum():
    niche_prior = build_niche_prior("ctr", niche_effect_size=None)
    result = build_client_prior("ctr", niche_prior, 100, client_mean=0.04, client_std=0.01)
    assert abs(result["niche_weight"] + result["client_weight"] - 1.0) < 0.01


def test_insufficient_client_data_returns_niche():
    niche_prior = build_niche_prior("ctr", niche_effect_size=0.2)
    result = build_client_prior("ctr", niche_prior, 10, client_mean=0.03)
    assert result["source"] == "NICHE_PRIOR"


def test_get_effective_prior_full_chain():
    niche_bm = {"effect_size": 0.3, "ci_lower": 0.018, "ci_upper": 0.022, "is_published": True}
    client_d  = {"n_observations": 200, "mean": 0.025, "std": 0.005}
    result = get_effective_prior("ctr", niche_bm, client_d)
    assert result["source"] == "CLIENT_POSTERIOR"


def test_get_effective_prior_no_client():
    niche_bm = {"effect_size": 0.3, "ci_lower": 0.018, "ci_upper": 0.022, "is_published": True}
    result = get_effective_prior("ctr", niche_bm, None)
    assert result["source"] == "NICHE"


def test_get_effective_prior_no_niche():
    result = get_effective_prior("bounce", None, None)
    assert result["source"] == "UNIVERSAL"


def test_student_t_params_fields():
    p = student_t_params(4.0, 0.03, 0.01)
    assert p["distribution"] == "StudentT"
    assert p["nu"] == 4.0
