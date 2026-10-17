"""
Taxonomy Alignment Layer — pure logic, no FastAPI/Pydantic imports.
Assigns creative_atom values to registry centroids via cosine similarity.
Architecture §15.10 / vision §15.12.11
"""
from __future__ import annotations

import math
import hashlib
from typing import Optional

try:
    import numpy as np
    NUMPY_AVAILABLE = True
except ImportError:
    NUMPY_AVAILABLE = False

try:
    import openai
    OPENAI_AVAILABLE = True
except ImportError:
    OPENAI_AVAILABLE = False

EMBEDDING_DIMS = 768        # matches atom_registry.centroid vector(768)
COSINE_THRESHOLD = 0.86     # assign to existing centroid if sim >= this
COSINE_MERGE_THRESHOLD = 0.92  # merge two centroids if sim >= this
MIN_APPEARANCES_CONFIRM = 10   # min appearances to promote CANDIDATE → CONFIRMED
MIN_TENANTS_CONFIRM = 2        # min tenant_count to promote


def get_embedding(text: str, api_key: Optional[str] = None) -> list[float]:
    """Return 768-dim embedding. Falls back to deterministic stub if OpenAI unavailable."""
    if OPENAI_AVAILABLE and api_key:
        try:
            client = openai.OpenAI(api_key=api_key)
            resp = client.embeddings.create(
                model="text-embedding-3-small",
                input=text,
                dimensions=EMBEDDING_DIMS,
            )
            return resp.data[0].embedding
        except Exception:
            pass
    return _stub_embedding(text, dims=EMBEDDING_DIMS)


def _stub_embedding(text: str, dims: int = EMBEDDING_DIMS) -> list[float]:
    """Reproducible unit vector from text hash — used when OpenAI key absent."""
    seed = int(hashlib.md5(text.encode()).hexdigest(), 16)
    if NUMPY_AVAILABLE:
        rng = np.random.default_rng(seed % (2 ** 32))
        v = rng.standard_normal(dims).astype(float)
        norm = float(np.linalg.norm(v))
        return (v / norm).tolist() if norm > 0 else [0.0] * dims
    import random
    rng = random.Random(seed)
    v = [rng.gauss(0, 1) for _ in range(dims)]
    norm = math.sqrt(sum(x * x for x in v))
    return [x / norm for x in v] if norm > 0 else [0.0] * dims


def cosine_similarity(a: list[float], b: list[float]) -> float:
    """Compute cosine similarity between two equal-length vectors."""
    if NUMPY_AVAILABLE:
        va = np.array(a, dtype=float)
        vb = np.array(b, dtype=float)
        denom = float(np.linalg.norm(va) * np.linalg.norm(vb))
        return float(np.dot(va, vb) / denom) if denom > 0 else 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(x * x for x in b))
    denom = norm_a * norm_b
    return dot / denom if denom > 0 else 0.0


def find_nearest_centroid(
    embedding: list[float],
    candidates: list[dict],
) -> tuple[Optional[str], float]:
    """
    Find nearest centroid among candidates (list of {id, centroid: list[float]}).
    Returns (registry_id, sim) if sim >= COSINE_THRESHOLD, else (None, best_sim).
    """
    best_id: Optional[str] = None
    best_sim = 0.0
    for c in candidates:
        if not c.get("centroid"):
            continue
        sim = cosine_similarity(embedding, c["centroid"])
        if sim > best_sim:
            best_sim = sim
            best_id = c["id"]
    if best_sim >= COSINE_THRESHOLD:
        return best_id, best_sim
    return None, best_sim


def update_centroid(old_centroid: list[float], new_embedding: list[float], n: int) -> list[float]:
    """Online centroid update (running mean), re-normalised to unit length."""
    if NUMPY_AVAILABLE:
        oc = np.array(old_centroid, dtype=float)
        ne = np.array(new_embedding, dtype=float)
        updated = (oc * (n - 1) + ne) / n
        norm = float(np.linalg.norm(updated))
        return (updated / norm).tolist() if norm > 0 else old_centroid
    updated = [(o * (n - 1) + e) / n for o, e in zip(old_centroid, new_embedding)]
    norm = math.sqrt(sum(x * x for x in updated))
    return [x / norm for x in updated] if norm > 0 else old_centroid


def should_confirm(appearance_count: int, tenant_count: int) -> bool:
    """Promote CANDIDATE → CONFIRMED when both thresholds are met."""
    return (appearance_count >= MIN_APPEARANCES_CONFIRM
            and tenant_count >= MIN_TENANTS_CONFIRM)


def build_alignment_result(
    atom_type: str,
    atom_value: str,
    embedding: list[float],
    registry_candidates: list[dict],
) -> dict:
    """
    Core alignment logic.
    Returns {"registry_id", "alignment_sim", "is_new", "embedding"}.
    """
    registry_id, sim = find_nearest_centroid(embedding, registry_candidates)
    return {
        "registry_id": registry_id,
        "alignment_sim": round(sim, 4),
        "is_new": registry_id is None,
        "embedding": embedding,
    }


def parse_pgvector(raw: Optional[str]) -> Optional[list[float]]:
    """Parse pgvector string '[0.1,0.2,...]' to list[float]."""
    if not raw:
        return None
    try:
        return [float(x) for x in raw.strip("[]").split(",")]
    except (ValueError, AttributeError):
        return None


def centroid_to_pgvector(vec: list[float]) -> str:
    """Format list[float] as pgvector literal '[0.1,0.2,...]'."""
    return "[" + ",".join(str(x) for x in vec) + "]"
