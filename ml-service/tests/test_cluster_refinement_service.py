"""Tests for cluster_refinement_service — Block F §2."""
import pytest
from app.services.cluster_refinement_service import (
    MIN_CLUSTER_SIZE,
    SIMILARITY_THRESHOLD,
    find_thin_clusters,
    find_merge_target,
    build_refinement_plan,
    apply_refinement,
)


CENTROIDS = {
    "researcher":  [0.9, 0.1, 0.0],
    "buyer":       [0.1, 0.9, 0.1],
    "browser":     [0.5, 0.5, 0.1],
    "tiny":        [0.51, 0.49, 0.1],   # similar to browser
    "orphan":      [0.0, 0.0, 1.0],     # far from all
}

SIZES = {
    "researcher": 200,
    "buyer":      150,
    "browser":    120,
    "tiny":       5,   # below MIN_CLUSTER_SIZE
    "orphan":     3,   # below MIN_CLUSTER_SIZE, no close neighbour
}


def test_find_thin_clusters():
    thin = find_thin_clusters(SIZES, min_size=MIN_CLUSTER_SIZE)
    assert "tiny" in thin
    assert "orphan" in thin
    assert "researcher" not in thin


def test_find_merge_target_finds_similar():
    target = find_merge_target("tiny", CENTROIDS)
    # "tiny" centroid [0.51, 0.49, 0.1] should be close to "browser" [0.5, 0.5, 0.1]
    assert target == "browser"


def test_find_merge_target_no_target_for_distant():
    # "orphan" [0.0, 0.0, 1.0] is far from all others — no match above threshold
    target = find_merge_target("orphan", CENTROIDS)
    assert target is None


def test_build_refinement_plan_merge_and_suppress():
    plan = build_refinement_plan(SIZES, CENTROIDS)
    actions = {p["from_label"]: p["action"] for p in plan}
    assert actions["tiny"] == "MERGE"
    assert actions["orphan"] == "SUPPRESS"


def test_build_refinement_plan_merge_target_correct():
    plan = build_refinement_plan(SIZES, CENTROIDS)
    merge = next(p for p in plan if p["from_label"] == "tiny")
    assert merge["to_label"] == "browser"
    assert merge["similarity"] is not None


def test_apply_refinement_relabels_sessions():
    plan = [
        {"from_label": "tiny",   "to_label": "browser", "action": "MERGE"},
        {"from_label": "orphan", "to_label": None,       "action": "SUPPRESS"},
    ]
    assignments = [
        {"session_id": "s1", "cluster_label": "tiny"},
        {"session_id": "s2", "cluster_label": "orphan"},
        {"session_id": "s3", "cluster_label": "researcher"},
    ]
    result = apply_refinement(assignments, plan)
    labels = {r["session_id"]: r["cluster_label"] for r in result}
    assert labels["s1"] == "browser"
    assert labels["s2"] == "UNASSIGNED"
    assert labels["s3"] == "researcher"   # unchanged


def test_apply_refinement_marks_refined_flag():
    plan = [{"from_label": "tiny", "to_label": "browser", "action": "MERGE"}]
    assignments = [
        {"session_id": "s1", "cluster_label": "tiny"},
        {"session_id": "s2", "cluster_label": "browser"},
    ]
    result = apply_refinement(assignments, plan)
    refined = {r["session_id"]: r["refined"] for r in result}
    assert refined["s1"] is True
    assert refined["s2"] is False


def test_empty_assignments():
    plan = build_refinement_plan(SIZES, CENTROIDS)
    result = apply_refinement([], plan)
    assert result == []


def test_all_clusters_large():
    large = {"a": 100, "b": 200, "c": 150}
    thin = find_thin_clusters(large, min_size=MIN_CLUSTER_SIZE)
    assert thin == []
