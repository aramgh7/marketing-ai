"""Tests for visual_atoms_service — Phase 14."""
import pytest
from app.services.visual_atoms_service import (
    extract_visual_features,
    has_enough_data,
    fit_visual_atoms,
    select_best_asset,
    MIN_VARIANTS_FOR_VISUAL_FIT,
    TRACKED_SLOT_POSITIONS,
)


def _make_variant(id_: str, conversions: int, impressions: int, slots: dict) -> dict:
    return {"id": id_, "conversions": conversions, "impressions": impressions, "visual_slots": slots}


class TestExtractVisualFeatures:
    def test_basic_extraction(self):
        v = _make_variant("v1", 1, 100, {"hero": "founder_portrait", "proof": "chart_graph"})
        features = extract_visual_features([v])
        assert features["variant_ids"] == ["v1"]
        assert features["y"] == [1]
        assert features["n"] == [100]

    def test_no_conversions_gives_y_zero(self):
        v = _make_variant("v2", 0, 200, {})
        features = extract_visual_features([v])
        assert features["y"] == [0]

    def test_missing_slot_becomes_none(self):
        v = _make_variant("v3", 1, 50, {})
        features = extract_visual_features([v])
        assert features["visual_features"]["hero_l1"] == ["none"]

    def test_all_tracked_slots_present(self):
        v = _make_variant("v4", 1, 100, {
            "hero": "team_photo",
            "proof": "property_aerial",
            "background": "abstract_visual",
        })
        features = extract_visual_features([v])
        for pos in TRACKED_SLOT_POSITIONS:
            assert pos + "_l1" in features["visual_features"]

    def test_multiple_variants(self):
        variants = [
            _make_variant(f"v{i}", i % 2, 100, {"hero": "founder_portrait"})
            for i in range(5)
        ]
        features = extract_visual_features(variants)
        assert len(features["variant_ids"]) == 5
        assert len(features["y"]) == 5

    def test_empty_input(self):
        features = extract_visual_features([])
        assert features["variant_ids"] == []
        assert features["y"] == []

    def test_impressions_floor_at_one(self):
        v = _make_variant("v5", 0, 0, {})
        features = extract_visual_features([v])
        assert features["n"] == [1]


class TestHasEnoughData:
    def test_not_enough(self):
        features = {"y": [1] * 10 + [0] * 100}
        assert not has_enough_data(features)

    def test_exactly_enough(self):
        features = {"y": [1] * MIN_VARIANTS_FOR_VISUAL_FIT + [0] * 10}
        assert has_enough_data(features)

    def test_more_than_enough(self):
        features = {"y": [1] * (MIN_VARIANTS_FOR_VISUAL_FIT + 20)}
        assert has_enough_data(features)

    def test_empty(self):
        assert not has_enough_data({"y": []})

    def test_all_zeros(self):
        features = {"y": [0] * 200}
        assert not has_enough_data(features)


class TestFitVisualAtoms:
    def test_insufficient_data_returns_skip_reason(self):
        variants = [_make_variant(f"v{i}", 1, 100, {"hero": "founder_portrait"}) for i in range(10)]
        features = extract_visual_features(variants)
        result = fit_visual_atoms(features)
        assert result["visual_atoms"] == []
        assert "skipped_reason" in result

    def test_returns_correct_keys(self):
        result = fit_visual_atoms({"y": [], "n": [], "visual_features": {}})
        assert "visual_atoms" in result
        assert "n_obs" in result

    def test_skipped_reason_mentions_threshold(self):
        features = extract_visual_features([_make_variant("v1", 1, 100, {}) for _ in range(5)])
        result = fit_visual_atoms(features)
        assert str(MIN_VARIANTS_FOR_VISUAL_FIT) in result.get("skipped_reason", "")

    def test_enough_data_attempts_fit(self):
        variants = (
            [_make_variant(f"c{i}", 2, 200, {"hero": "founder_portrait", "proof": "chart_graph"}) for i in range(30)]
            + [_make_variant(f"d{i}", 1, 150, {"hero": "property_aerial", "proof": "document"}) for i in range(30)]
            + [_make_variant(f"e{i}", 0, 100, {"hero": "abstract_visual"}) for i in range(10)]
        )
        features = extract_visual_features(variants)
        result = fit_visual_atoms(features)
        assert "visual_atoms" in result
        assert isinstance(result["visual_atoms"], list)

    def test_n_obs_matches_input(self):
        variants = [_make_variant(f"v{i}", 0, 50, {}) for i in range(7)]
        features = extract_visual_features(variants)
        result = fit_visual_atoms(features)
        assert result["n_obs"] == 7


class TestSelectBestAsset:
    def _asset(self, id_: str, l1: list, l2: list):
        return {"id": id_, "tags_l1": l1, "tags_l2": l2, "tags_l3": []}

    def test_returns_none_when_no_candidates(self):
        result = select_best_asset("hero", [], [], required_l2_tag="hero_background")
        assert result is None

    def test_returns_none_when_no_l2_match(self):
        assets = [self._asset("a1", ["chart_graph"], ["proof_visual"])]
        result = select_best_asset("hero", assets, [], required_l2_tag="hero_background")
        assert result is None

    def test_filters_by_l2_tag(self):
        assets = [
            self._asset("a1", ["founder_portrait"], ["hero_background"]),
            self._asset("a2", ["chart_graph"], ["proof_visual"]),
        ]
        result = select_best_asset("hero", assets, [], required_l2_tag="hero_background")
        assert result is not None
        assert result["id"] == "a1"

    def test_ranks_by_coefficient(self):
        assets = [
            self._asset("a1", ["property_aerial"], ["hero_background"]),
            self._asset("a2", ["founder_portrait"], ["hero_background"]),
        ]
        atoms = [
            {"slot": "hero_l1", "l1_tag": "founder_portrait", "coefficient": 0.8},
            {"slot": "hero_l1", "l1_tag": "property_aerial", "coefficient": 0.2},
        ]
        result = select_best_asset("hero", assets, atoms, required_l2_tag="hero_background")
        assert result["id"] == "a2"

    def test_no_l2_filter_returns_all_candidates(self):
        assets = [
            self._asset("a1", ["founder_portrait"], ["hero_background"]),
            self._asset("a2", ["chart_graph"], ["proof_visual"]),
        ]
        result = select_best_asset("hero", assets, [], required_l2_tag=None)
        assert result is not None

    def test_empty_l1_tags_scores_zero(self):
        assets = [
            self._asset("a1", [], ["hero_background"]),
            self._asset("a2", ["founder_portrait"], ["hero_background"]),
        ]
        atoms = [{"slot": "hero_l1", "l1_tag": "founder_portrait", "coefficient": 0.5}]
        result = select_best_asset("hero", assets, atoms, required_l2_tag="hero_background")
        assert result["id"] == "a2"
