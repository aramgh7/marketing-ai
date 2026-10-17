"""
Cluster refinement — merges thin clusters into neighbours (Block F §2).
Pure logic — ZERO FastAPI/Pydantic imports.
"""
from __future__ import annotations
from typing import Optional
import math

MIN_CLUSTER_SIZE = 30   # vision §12.6.3: clusters with fewer sessions are merged
SIMILARITY_THRESHOLD = 0.80  # cosine similarity above which two clusters are merged


def _cosine_similarity(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or not a:
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(x * x for x in b))
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return dot / (norm_a * norm_b)


def find_thin_clusters(
    cluster_sizes: dict[str, int],
    min_size: int = MIN_CLUSTER_SIZE,
) -> list[str]:
    """Return labels of clusters that fall below the minimum size threshold."""
    return [label for label, size in cluster_sizes.items() if size < min_size]


def find_merge_target(
    thin_label: str,
    centroids: dict[str, list[float]],
    exclude: Optional[set[str]] = None,
) -> Optional[str]:
    """
    Find the nearest neighbour centroid for a thin cluster.
    Returns None if no candidate has similarity >= SIMILARITY_THRESHOLD.
    """
    if thin_label not in centroids:
        return None
    thin_vec = centroids[thin_label]
    exclude = exclude or set()
    best_label: Optional[str] = None
    best_sim = SIMILARITY_THRESHOLD - 1e-9  # must exceed threshold

    for label, vec in centroids.items():
        if label == thin_label or label in exclude:
            continue
        sim = _cosine_similarity(thin_vec, vec)
        if sim > best_sim:
            best_sim = sim
            best_label = label

    return best_label


def build_refinement_plan(
    cluster_sizes: dict[str, int],
    centroids: dict[str, list[float]],
    min_size: int = MIN_CLUSTER_SIZE,
) -> list[dict]:
    """
    Build a merge plan for all thin clusters.
    Returns list of {from_label, to_label, similarity, from_size} dicts.
    Thin clusters that cannot be merged (no neighbour above threshold) are
    flagged with to_label=None and action='SUPPRESS'.
    """
    thin = find_thin_clusters(cluster_sizes, min_size)
    plan = []
    merged_into: set[str] = set()

    for label in thin:
        target = find_merge_target(label, centroids, exclude=merged_into)
        if target is None:
            plan.append({
                "from_label": label,
                "to_label":   None,
                "similarity": None,
                "from_size":  cluster_sizes.get(label, 0),
                "action":     "SUPPRESS",
            })
        else:
            sim = _cosine_similarity(centroids[label], centroids[target])
            plan.append({
                "from_label": label,
                "to_label":   target,
                "similarity": round(sim, 4),
                "from_size":  cluster_sizes.get(label, 0),
                "action":     "MERGE",
            })
            merged_into.add(label)

    return plan


def apply_refinement(
    assignments: list[dict],
    refinement_plan: list[dict],
) -> list[dict]:
    """
    Apply a merge plan to a list of session cluster assignments.
    Each assignment must have 'session_id' and 'cluster_label'.
    Suppressed clusters get cluster_label='UNASSIGNED'.
    """
    merge_map: dict[str, str] = {}
    for entry in refinement_plan:
        if entry["action"] == "MERGE" and entry["to_label"]:
            merge_map[entry["from_label"]] = entry["to_label"]
        elif entry["action"] == "SUPPRESS":
            merge_map[entry["from_label"]] = "UNASSIGNED"

    result = []
    for a in assignments:
        label = a["cluster_label"]
        new_label = merge_map.get(label, label)
        result.append({**a, "cluster_label": new_label, "refined": new_label != label})
    return result
