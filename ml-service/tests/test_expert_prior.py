"""Roadmap 9-9: expert rating → Beta prior shift and combination with the GLM prior."""
from app.services.expert_prior_service import combine_priors, expert_prior, prior_for_arm


def test_rating_table_and_veto():
    assert expert_prior(1) == (1.0, 3.0)
    assert expert_prior(3) == (1.0, 1.0)
    assert expert_prior(5) == (3.0, 1.0)
    assert expert_prior(2) == (1.0, 2.0) and expert_prior(4) == (2.0, 1.0)
    assert expert_prior(9) == (3.0, 1.0) and expert_prior(0) == (1.0, 3.0)
    assert expert_prior(5, veto=True) == (1.0, 5.0)
    assert expert_prior(None) == (1.0, 1.0)


def test_combine_handles_missing_sides():
    assert combine_priors(None, None) == (1.0, 1.0)
    assert combine_priors((4.0, 6.0), None) == (4.0, 6.0)
    assert combine_priors(None, (3.0, 1.0)) == (3.0, 1.0)


def test_combine_weights_by_effective_n():
    # GLM says 10 % with n=20; expert 5-star says 75 % with n=4 → mean pulled slightly up, n averaged
    a, b = combine_priors((2.0, 18.0), (3.0, 1.0))
    mean = a / (a + b)
    assert 0.10 < mean < 0.75
    assert abs((a + b) - 12.0) < 1e-6
    assert abs(mean - (2.0 + 3.0) / 24.0) < 1e-6


def test_prior_for_arm_sources():
    glm = {"alpha0": 2.0, "beta0": 18.0, "matched_atoms": ["HOOK:question"]}
    assert prior_for_arm(glm, None)["source"] == "GLM"
    assert prior_for_arm(None, 5)["source"] == "EXPERT"
    assert prior_for_arm({"alpha0": 1, "beta0": 1, "matched_atoms": []}, None)["source"] == "FLAT"
    both = prior_for_arm(glm, 5)
    assert both["source"] == "GLM+EXPERT" and 0.1 < both["alpha0"] / (both["alpha0"] + both["beta0"]) < 0.75
    stored = prior_for_arm(None, None, stored=(1.0, 3.0))
    assert stored["source"] == "EXPERT" and (stored["alpha0"], stored["beta0"]) == (1.0, 3.0)
