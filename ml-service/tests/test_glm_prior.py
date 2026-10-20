"""Roadmap 9-10 / 9-11: GLM-informed Beta priors and hierarchy blending — pure logic."""
import math

from app.services.glm_prior_service import (
    N0_MAX_DEFAULT,
    blend_with_hierarchy,
    prior_for_variant,
    prior_from_predictive,
)
from app.services.hierarchy_prior_service import get_effective_prior


def _beta_moments(a, b):
    m = a / (a + b)
    sd = math.sqrt(a * b / ((a + b) ** 2 * (a + b + 1)))
    return m, sd


def test_moments_round_trip():
    a, b = 3.0, 12.0                               # n = 15 < n0_max
    m, sd = _beta_moments(a, b)
    a0, b0 = prior_from_predictive(m, sd)
    assert abs(a0 - a) < 1e-3 and abs(b0 - b) < 1e-3


def test_effective_n_capped_at_n0_max():
    a0, b0 = prior_from_predictive(0.05, 0.001)    # extremely confident → capped
    assert abs((a0 + b0) - N0_MAX_DEFAULT) < 1e-6
    assert abs(a0 / (a0 + b0) - 0.05) < 1e-6


def test_effective_n_floor_at_one_for_huge_sd():
    a0, b0 = prior_from_predictive(0.3, 0.9)
    assert abs((a0 + b0) - 1.0) < 1e-6


def test_mean_clamped():
    a0, b0 = prior_from_predictive(0.0, 0.1)
    assert a0 > 0 and b0 > 0 and a0 / (a0 + b0) >= 0.001
    a0, b0 = prior_from_predictive(1.5, 0.1)
    assert a0 / (a0 + b0) <= 0.999


def test_no_sd_gives_flat_n():
    a0, b0 = prior_from_predictive(0.2, None)
    assert abs((a0 + b0) - 1.0) < 1e-6


def _posteriors():
    return [
        {"atom_type": "HOOK", "atom_value": "question", "mean": 0.4, "sd": 0.2},
        {"atom_type": "CTA", "atom_value": "book_call", "mean": 0.2, "sd": 0.1},
        {"atom_type": "TONE", "atom_value": "fear", "mean": -0.5, "sd": 0.3},
    ]


def test_prior_for_variant_matches_atoms_and_shifts_mean():
    atoms = [{"atom_type": "hook", "atom_value": "Question"}, {"atom_type": "CTA", "atom_value": "book_call"}]
    res = prior_for_variant(_posteriors(), atoms, base_rate=0.03, base_sd=0.01)
    assert res["source"] == "GLM" and len(res["matched_atoms"]) == 2
    assert res["mean"] > 0.03                      # positive logit effects raise the rate
    assert 1.0 <= res["n0"] <= N0_MAX_DEFAULT
    assert abs(res["alpha0"] / (res["alpha0"] + res["beta0"]) - res["mean"]) < 1e-4


def test_prior_for_variant_negative_atom_lowers_mean():
    res = prior_for_variant(_posteriors(), [{"atom_type": "TONE", "atom_value": "fear"}], 0.03, 0.01)
    assert res["mean"] < 0.03


def test_prior_for_variant_fallback_flat():
    res = prior_for_variant(_posteriors(), [{"atom_type": "HOOK", "atom_value": "unknown"}], 0.03, 0.01)
    assert (res["alpha0"], res["beta0"], res["n0"]) == (1.0, 1.0, 2.0) and res["source"] == "FLAT"
    assert prior_for_variant([], [], 0.03, 0.01)["source"] == "FLAT"


def test_more_uncertain_atoms_give_weaker_prior():
    tight = [{"atom_type": "HOOK", "atom_value": "q", "mean": 0.3, "sd": 0.05}]
    loose = [{"atom_type": "HOOK", "atom_value": "q", "mean": 0.3, "sd": 1.5}]
    atoms = [{"atom_type": "HOOK", "atom_value": "q"}]
    assert prior_for_variant(tight, atoms, 0.03, 0.005)["n0"] > prior_for_variant(loose, atoms, 0.03, 0.005)["n0"]


def test_blend_weights_follow_n_hier_over_n_total():
    prior = {"alpha0": 1.0, "beta0": 9.0, "n0": 10.0, "mean": 0.1, "sd": 0.09, "matched_atoms": [], "source": "GLM"}
    hier = get_effective_prior("cvr")              # universal: mu 0.03, sigma 0.02
    res = blend_with_hierarchy(prior, hier, n_hier=10.0)
    assert abs(res["hierarchy_weight"] - 0.5) < 1e-6
    assert abs(res["mean"] - 0.065) < 1e-6
    assert res["hierarchy_source"] == "UNIVERSAL" and res["source"] == "GLM+HIER"
    assert res["n0"] <= N0_MAX_DEFAULT


def test_blend_flat_prior_takes_hierarchy_fully():
    flat = prior_for_variant([], [], 0.03, 0.01)
    hier = get_effective_prior("cvr", niche_benchmark={"effect_size": 1.0, "ci_lower": 0.5, "ci_upper": 1.5})
    res = blend_with_hierarchy(flat, hier)
    assert res["hierarchy_weight"] == 1.0 and res["source"] == "HIER"
    assert abs(res["mean"] - hier["prior"]["mu"]) < 1e-6


def test_blend_without_hierarchy_is_identity():
    prior = prior_for_variant(_posteriors(), [{"atom_type": "HOOK", "atom_value": "question"}], 0.03, 0.01)
    res = blend_with_hierarchy(prior, None)
    assert res["alpha0"] == prior["alpha0"] and res["hierarchy_weight"] == 0.0
