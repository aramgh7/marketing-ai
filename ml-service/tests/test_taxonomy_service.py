"""Tests for taxonomy_service.py (arch §15.10)"""
import math
import pytest

from app.services.taxonomy_service import (
    _stub_embedding, cosine_similarity, find_nearest_centroid,
    update_centroid, should_confirm, build_alignment_result,
    parse_pgvector, centroid_to_pgvector,
    EMBEDDING_DIMS, COSINE_THRESHOLD, COSINE_MERGE_THRESHOLD,
    MIN_APPEARANCES_CONFIRM, MIN_TENANTS_CONFIRM,
)


def unit_vec(dims=EMBEDDING_DIMS):
    v = [1.0 / math.sqrt(dims)] * dims
    return v


class TestStubEmbedding:
    def test_returns_correct_dims(self):
        v = _stub_embedding("hello world")
        assert len(v) == EMBEDDING_DIMS

    def test_is_unit_vector(self):
        v = _stub_embedding("test atom")
        norm = math.sqrt(sum(x * x for x in v))
        assert abs(norm - 1.0) < 1e-5

    def test_deterministic(self):
        a = _stub_embedding("same text")
        b = _stub_embedding("same text")
        assert a == b

    def test_different_texts_differ(self):
        a = _stub_embedding("text one")
        b = _stub_embedding("text two")
        assert a != b


class TestCosineSimilarity:
    def test_identical_vectors(self):
        v = unit_vec(4)
        assert abs(cosine_similarity(v, v) - 1.0) < 1e-6

    def test_orthogonal_vectors(self):
        a = [1.0, 0.0, 0.0]
        b = [0.0, 1.0, 0.0]
        assert abs(cosine_similarity(a, b)) < 1e-6

    def test_zero_vector_returns_zero(self):
        a = [0.0, 0.0, 0.0]
        b = [1.0, 0.0, 0.0]
        assert cosine_similarity(a, b) == 0.0


class TestFindNearestCentroid:
    def test_above_threshold_returns_id(self):
        emb = unit_vec(4)
        candidates = [{"id": "abc", "centroid": unit_vec(4)}]
        rid, sim = find_nearest_centroid(emb, candidates)
        assert rid == "abc"
        assert sim >= COSINE_THRESHOLD

    def test_below_threshold_returns_none(self):
        emb = [1.0, 0.0, 0.0, 0.0]
        candidates = [{"id": "abc", "centroid": [0.0, 1.0, 0.0, 0.0]}]
        rid, sim = find_nearest_centroid(emb, candidates)
        assert rid is None

    def test_empty_candidates(self):
        rid, sim = find_nearest_centroid(unit_vec(4), [])
        assert rid is None
        assert sim == 0.0


class TestUpdateCentroid:
    def test_result_is_unit_vector(self):
        old = unit_vec(EMBEDDING_DIMS)
        new = _stub_embedding("new atom")
        updated = update_centroid(old, new, 2)
        norm = math.sqrt(sum(x * x for x in updated))
        assert abs(norm - 1.0) < 1e-5

    def test_convergence_toward_new(self):
        old = [1.0, 0.0, 0.0]
        new = [0.0, 1.0, 0.0]
        updated = update_centroid(old, new, 2)
        assert updated[0] > 0 and updated[1] > 0


class TestShouldConfirm:
    def test_both_thresholds_met(self):
        assert should_confirm(MIN_APPEARANCES_CONFIRM, MIN_TENANTS_CONFIRM) is True

    def test_not_enough_appearances(self):
        assert should_confirm(MIN_APPEARANCES_CONFIRM - 1, MIN_TENANTS_CONFIRM) is False

    def test_not_enough_tenants(self):
        assert should_confirm(MIN_APPEARANCES_CONFIRM, MIN_TENANTS_CONFIRM - 1) is False


class TestBuildAlignmentResult:
    def test_new_when_no_candidates(self):
        emb = unit_vec(EMBEDDING_DIMS)
        result = build_alignment_result("HOOK", "free trial", emb, [])
        assert result["is_new"] is True
        assert result["registry_id"] is None

    def test_existing_when_high_similarity(self):
        emb = unit_vec(EMBEDDING_DIMS)
        candidates = [{"id": "existing-id", "centroid": unit_vec(EMBEDDING_DIMS)}]
        result = build_alignment_result("HOOK", "free trial", emb, candidates)
        assert result["is_new"] is False
        assert result["registry_id"] == "existing-id"
        assert result["alignment_sim"] >= COSINE_THRESHOLD


class TestPgvectorParsing:
    def test_parse_round_trip(self):
        v = [0.1, 0.2, -0.3, 0.4]
        s = centroid_to_pgvector(v)
        parsed = parse_pgvector(s)
        assert parsed is not None
        for a, b in zip(v, parsed):
            assert abs(a - b) < 1e-9

    def test_parse_none_on_empty(self):
        assert parse_pgvector(None) is None
        assert parse_pgvector("") is None
