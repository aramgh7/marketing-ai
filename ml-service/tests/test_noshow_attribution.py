"""Roadmap 8-3: no-show attribution model — pure logic tests."""
import math
import random

import pytest

from app.services.noshow_attribution_service import (
    MIN_ROWS,
    atom_noshow_beta,
    encode_features,
    fit_noshow_attribution,
    no_show_drivers,
    predict_show_probability,
)


def _rows(n=400, seed=7):
    """Synthetic bookings: HOOK=shock halves the show odds, long booking distance hurts, tz_known helps."""
    rng = random.Random(seed)
    rows = []
    for _ in range(n):
        hook = rng.choice(["question", "question", "shock", "benefit"])   # "question" is the reference level
        cta = rng.choice(["book_call", "learn_more"])
        dist = rng.uniform(2, 120)
        tz = rng.random() < 0.6
        seg = rng.choice(["LT25K", "GT250K"])
        logit = 0.8 + (-1.2 if hook == "shock" else 0.0) - 0.9 * (dist / 72.0) + 0.6 * tz
        p = 1 / (1 + math.exp(-logit))
        rows.append({"atoms": {"HOOK": hook, "CTA": cta}, "booking_distance_h": dist,
                     "tz_known": tz, "segment": seg, "showed": rng.random() < p})
    return rows


def test_encode_features_shapes_and_reference_levels():
    X, y, names = encode_features(_rows(60))
    assert X.shape == (60, len(names))
    assert "HOOK=shock" in names and "booking_distance" in names and "tz_known" in names
    assert len([n for n in names if n.startswith("segment=")]) == 1      # one segment is the reference level
    assert "HOOK=question" not in names                                    # most frequent hook is the reference
    assert set(y.tolist()) <= {0.0, 1.0}


def test_too_few_rows_is_not_fitted():
    res = fit_noshow_attribution(_rows(MIN_ROWS - 1))
    assert res["fitted"] is False and res["coefficients"] == []


def test_recovers_signs_of_planted_effects():
    res = fit_noshow_attribution(_rows(600))
    assert res["fitted"] and res["converged"]
    assert atom_noshow_beta(res, "HOOK", "shock") < -0.5
    betas = {c["feature"]: c["beta"] for c in res["coefficients"]}
    assert betas["booking_distance"] < 0
    assert betas["tz_known"] > 0


def test_shock_hook_is_reported_as_driver():
    res = fit_noshow_attribution(_rows(600))
    drivers = [(d["atom_type"], d["atom_value"]) for d in res["drivers"]]
    assert ("HOOK", "shock") in drivers
    assert all(d["beta"] <= -0.3 for d in res["drivers"])


def test_drivers_exclude_segments_and_covariates():
    coefs = [{"feature": "segment=GT250K", "beta": -2.0, "z": -5.0, "odds_ratio": 0.1},
             {"feature": "booking_distance", "beta": -2.0, "z": -5.0, "odds_ratio": 0.1},
             {"feature": "CTA=learn_more", "beta": -0.5, "z": -2.0, "odds_ratio": 0.6},
             {"feature": "CTA=weak_z", "beta": -0.9, "z": -1.0, "odds_ratio": 0.4}]
    out = no_show_drivers(coefs)
    assert [d["atom_value"] for d in out] == ["learn_more"]


def test_predict_show_probability_orders_bookings():
    res = fit_noshow_attribution(_rows(600))
    good = {"atoms": {"HOOK": "benefit", "CTA": "book_call"}, "booking_distance_h": 4, "tz_known": True, "segment": "GT250K"}
    bad = {"atoms": {"HOOK": "shock", "CTA": "book_call"}, "booking_distance_h": 110, "tz_known": False, "segment": "GT250K"}
    assert predict_show_probability(res, good) > predict_show_probability(res, bad)
    assert predict_show_probability({"fitted": False}, good) is None


def test_no_outcome_variation_is_not_fitted():
    rows = [{"atoms": {"HOOK": "q"}, "booking_distance_h": 1, "tz_known": True, "showed": True} for _ in range(50)]
    assert fit_noshow_attribution(rows)["fitted"] is False
