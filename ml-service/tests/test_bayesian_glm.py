"""
Unit tests for app/models/bayesian_glm.py.

Fast tests (no PyMC sampling):
  - test_prepare_dataset_*   — pure pandas, instant

Slow tests (PyMC compilation + MCMC, ~10-30s):
  - test_fit_all_*            — marked with @pytest.mark.slow
    Run with: pytest -m slow
    Skip with: pytest -m "not slow"
"""
import importlib.util
import numpy as np
import pandas as pd
import pytest

pymc_available = importlib.util.find_spec("pymc") is not None

from app.models.bayesian_glm import (
    GlmDataset,
    FitResult,
    MIN_IMPRESSIONS,
    MIN_CLICKS_CVR,
    MIN_REVENUES,
    prepare_dataset,
    fit_all,
)


# ─── Fixtures ────────────────────────────────────────────────────────────────

@pytest.fixture
def minimal_df() -> pd.DataFrame:
    """5 creatives, all above MIN_IMPRESSIONS threshold."""
    return pd.DataFrame({
        "creative_id":  ["c1", "c2", "c3", "c4", "c5"],
        "impressions":  [200,  300,  100,  500,  150],
        "clicks":       [20,   30,   5,    50,   8],
        "conversions":  [2,    3,    0,    5,    1],
        "revenues":     [
            [99.0, 49.0],   # c1: 2 purchases
            [149.0],        # c2: 1 purchase
            None,           # c3: no purchases
            [49.0, 99.0, 149.0, 199.0, 99.0],  # c4: 5 purchases
            None,           # c5: no purchases
        ],
        "match_score":  [0.8, 0.6, 0.7, 0.9, 0.5],
        "has_face":     [1,   0,   1,   0,   1],
        "has_urgency":  [0,   1,   0,   1,   0],
        "hook_type":    ["question", "story", "question", "shock", "story"],
        "cta_type":     ["buy_now", "learn_more", "buy_now", "buy_now", "learn_more"],
        "offer_type":   ["discount_30", "none", "discount_30", "bundle", "none"],
    })


@pytest.fixture
def dataset(minimal_df) -> GlmDataset:
    d = prepare_dataset(minimal_df)
    assert d is not None
    return d


# ─── prepare_dataset — fast tests ────────────────────────────────────────────

class TestPrepareDataset:

    def test_returns_dataset_for_valid_data(self, minimal_df):
        result = prepare_dataset(minimal_df)
        assert result is not None
        assert isinstance(result, GlmDataset)

    def test_filters_rows_below_min_impressions(self):
        df = pd.DataFrame({
            "creative_id": ["low", "ok1", "ok2"],
            "impressions":  [10,  200,  300],   # 10 < MIN_IMPRESSIONS → filtered out
            "clicks":       [1,   20,   30],
            "conversions":  [0,   2,    3],
        })
        result = prepare_dataset(df)
        assert result is not None
        assert result.n_obs == 2  # only ok1 + ok2 remain
        assert "low" not in result.creative_ids

    def test_returns_none_when_too_few_rows(self):
        # Only 1 row after filtering
        df = pd.DataFrame({
            "creative_id": ["only"],
            "impressions":  [300],
            "clicks":       [30],
            "conversions":  [3],
        })
        result = prepare_dataset(df)
        assert result is None

    def test_returns_none_when_all_rows_filtered(self):
        df = pd.DataFrame({
            "creative_id": ["a", "b"],
            "impressions":  [5,  10],  # both below 50
            "clicks":       [0,  1],
            "conversions":  [0,  0],
        })
        result = prepare_dataset(df)
        assert result is None

    def test_hook_labels_sorted(self, dataset):
        assert dataset.hook_labels == sorted(dataset.hook_labels)

    def test_hook_idx_within_bounds(self, dataset):
        assert np.all(dataset.hook_idx >= 0)
        assert np.all(dataset.hook_idx < len(dataset.hook_labels))

    def test_cta_idx_within_bounds(self, dataset):
        assert np.all(dataset.cta_idx >= 0)
        assert np.all(dataset.cta_idx < len(dataset.cta_labels))

    def test_clicks_clamped_to_impressions(self, minimal_df):
        # Inject bad data: clicks > impressions
        bad_df = minimal_df.copy()
        bad_df.loc[0, "clicks"] = 999  # > impressions=200
        d = prepare_dataset(bad_df)
        assert d is not None
        assert np.all(d.clicks <= d.impressions)

    def test_conversions_clamped_to_clicks(self, minimal_df):
        bad_df = minimal_df.copy()
        bad_df.loc[3, "conversions"] = 9999  # > clicks=50
        d = prepare_dataset(bad_df)
        assert d is not None
        assert np.all(d.conversions <= d.clicks)

    def test_revenues_flattened_correctly(self, dataset):
        # Total purchases: 2+1+0+5+0 = 8
        assert len(dataset.revenues) == 8
        assert all(r > 0 for r in dataset.revenues)

    def test_revenues_empty_when_no_purchases(self):
        df = pd.DataFrame({
            "creative_id": ["a", "b", "c"],
            "impressions":  [200, 300, 150],
            "clicks":       [20,  30,  10],
            "conversions":  [2,   3,   1],
        })
        d = prepare_dataset(df)
        assert d is not None
        assert len(d.revenues) == 0

    def test_cvr_mask_filters_low_click_rows(self, dataset):
        # Rows with clicks >= MIN_CLICKS_CVR: c2(30), c4(50) → clicks=[20,30,50]
        # c1=20>=10 ✓, c2=30>=10 ✓, c3=5<10 ✗, c4=50>=10 ✓, c5=8<10 ✗
        assert int(dataset.cvr_mask.sum()) == 3

    def test_match_scores_filled_with_default(self):
        df = pd.DataFrame({
            "creative_id": ["a", "b", "c"],
            "impressions":  [200, 300, 150],
            "clicks":       [20,  30,  10],
            "conversions":  [2,   3,   1],
            # no match_score column
        })
        d = prepare_dataset(df)
        assert d is not None
        assert np.all(d.match_scores == 0.5)

    def test_missing_atom_columns_filled_with_defaults(self):
        df = pd.DataFrame({
            "creative_id": ["a", "b", "c"],
            "impressions":  [200, 300, 150],
            "clicks":       [20,  30,  10],
            "conversions":  [2,   3,   1],
            # no hook_type, cta_type, offer_type
        })
        d = prepare_dataset(df)
        assert d is not None
        assert d.hook_labels == ["unknown"]
        assert d.cta_labels == ["unknown"]
        assert d.offer_labels == ["none"]


# ─── fit_all — slow integration tests (real PyMC) ────────────────────────────

@pytest.mark.slow
@pytest.mark.skipif(not pymc_available, reason="pymc not installed")
class TestFitAll:
    """
    These tests run real PyMC MCMC (50 draws, 50 tune, 1 chain).
    Skipped by default — run with: pytest -m slow
    Expected duration: ~20-60s total (pytensor compilation dominates first run).
    """

    def test_fit_all_returns_fitresult(self, dataset):
        result = fit_all(dataset, draws=50, tune=50, chains=1)
        assert isinstance(result, FitResult)

    def test_fit_all_posteriors_nonempty(self, dataset):
        result = fit_all(dataset, draws=50, tune=50, chains=1)
        assert len(result.posteriors) > 0

    def test_fit_all_ctr_posteriors_present(self, dataset):
        result = fit_all(dataset, draws=50, tune=50, chains=1)
        stages = {p["stage"] for p in result.posteriors}
        assert "CTR" in stages

    def test_fit_all_hook_effects_present(self, dataset):
        result = fit_all(dataset, draws=50, tune=50, chains=1)
        ctr_hooks = [p for p in result.posteriors if p["stage"] == "CTR" and p["param"] == "β_hook"]
        assert len(ctr_hooks) == len(dataset.hook_labels)

    def test_fit_all_posteriors_have_finite_values(self, dataset):
        result = fit_all(dataset, draws=50, tune=50, chains=1)
        for p in result.posteriors:
            assert np.isfinite(p["mean"]), f"Non-finite mean in {p}"
            assert np.isfinite(p["std"]),  f"Non-finite std in {p}"
            assert p["std"] >= 0,          f"Negative std in {p}"

    def test_fit_all_n_obs_reported_correctly(self, dataset):
        result = fit_all(dataset, draws=50, tune=50, chains=1)
        assert result.n_obs_ctr == dataset.n_obs
        assert result.n_obs_cvr == int(dataset.cvr_mask.sum())

    def test_fit_all_aov_present_when_revenues_exist(self, dataset):
        result = fit_all(dataset, draws=50, tune=50, chains=1)
        aov = [p for p in result.posteriors if p["stage"] == "AOV"]
        assert len(aov) > 0

    def test_fit_all_skips_cvr_when_no_click_data(self):
        # All rows have too few clicks for CVR model
        df = pd.DataFrame({
            "creative_id": ["a", "b", "c"],
            "impressions":  [200, 300, 150],
            "clicks":       [3,   5,   4],   # all < MIN_CLICKS_CVR
            "conversions":  [0,   0,   0],
        })
        d = prepare_dataset(df)
        assert d is not None
        result = fit_all(d, draws=50, tune=50, chains=1)
        assert result.n_obs_cvr == 0
        cvr_posteriors = [p for p in result.posteriors if p["stage"] == "CVR"]
        assert len(cvr_posteriors) == 0
