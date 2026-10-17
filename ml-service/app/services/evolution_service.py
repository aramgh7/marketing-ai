"""Evolutionary Creative Generation Service (Phase 6-4 + v1.1).

Crossover + mutation of creative atoms from top-performing arms.
v1.1 additions: coherence check, graveyard similarity, GLM posterior ranking,
graveyard re-review (vision §14.2, §13.2, §15.8 task 1).
No FastAPI/Pydantic imports — pure logic, safe to unit-test.
"""
from __future__ import annotations
import math
import random
from typing import Dict, List, Optional


def rank_creatives_by_cvr(arms: List[Dict]) -> List[Dict]:
    """Sort arms by expected CVR = alpha / (alpha + beta), descending."""
    def expected_cvr(arm: Dict) -> float:
        a = arm.get("alpha", 1.0)
        b = arm.get("beta", 1.0)
        return a / (a + b) if (a + b) > 0 else 0.0

    return sorted(arms, key=expected_cvr, reverse=True)


def crossover_atoms(
    parent_a: Dict,
    parent_b: Dict,
    rng_seed: Optional[int] = None,
) -> List[Dict]:
    """50/50 crossover: for each unique atom_type, pick from parent_a or parent_b.

    parent_a / parent_b: {"atoms": [{"atom_type": ..., ...}, ...]}
    Returns list of child atoms.
    """
    rng = random.Random(rng_seed)
    atoms_a: List[Dict] = parent_a.get("atoms", [])
    atoms_b: List[Dict] = parent_b.get("atoms", [])

    by_type_a = {a["atom_type"]: a for a in atoms_a}
    by_type_b = {a["atom_type"]: a for a in atoms_b}
    all_types = set(by_type_a) | set(by_type_b)

    child = []
    for atom_type in sorted(all_types):
        candidates = [
            x for x in [by_type_a.get(atom_type), by_type_b.get(atom_type)]
            if x is not None
        ]
        if candidates:
            child.append(rng.choice(candidates))
    return child


def mutate_atoms(
    atoms: List[Dict],
    atom_pool: List[Dict],
    mutation_rate: float = 0.15,
    rng_seed: Optional[int] = None,
) -> List[Dict]:
    """Replace each atom independently with probability mutation_rate,
    drawing replacement from atom_pool of the same atom_type.

    Atoms with no pool entry are kept as-is.
    """
    rng = random.Random(rng_seed)
    pool_by_type: Dict[str, List[Dict]] = {}
    for p in atom_pool:
        pool_by_type.setdefault(p["atom_type"], []).append(p)

    mutated = []
    for atom in atoms:
        t = atom["atom_type"]
        if rng.random() < mutation_rate and t in pool_by_type:
            mutated.append(rng.choice(pool_by_type[t]))
        else:
            mutated.append(atom)
    return mutated


def generate_candidates(
    top_arms: List[Dict],
    atom_pool: List[Dict],
    n_candidates: int = 5,
    mutation_rate: float = 0.15,
) -> List[Dict]:
    """Generate n_candidates new creative proposals from top-2 parents.

    Each candidate: crossover of top-2, then mutate.
    Falls back to single-parent mutation if only one arm is available.
    """
    ranked = rank_creatives_by_cvr(top_arms)
    if not ranked:
        return []

    parent_a = ranked[0]
    parent_b = ranked[1] if len(ranked) > 1 else ranked[0]

    candidates = []
    for i in range(n_candidates):
        child_atoms = crossover_atoms(parent_a, parent_b, rng_seed=i)
        mutated     = mutate_atoms(child_atoms, atom_pool, mutation_rate, rng_seed=i + 1000)
        candidates.append({
            "candidate_index": i,
            "atoms":           mutated,
            "parent_ids":      [parent_a.get("id"), parent_b.get("id")],
        })
    return candidates


# ── v1.1 additions ────────────────────────────────────────────────────────────

def _cosine(a: list, b: list) -> float:
    dot    = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(x * x for x in b))
    denom  = norm_a * norm_b
    return dot / denom if denom > 0 else 0.0


def check_hook_body_coherence(
    hook_embedding: list,
    body_embedding: list,
    threshold: float = 0.65,
) -> dict:
    """
    Semantic coherence between hook and body via cosine similarity.
    Below threshold → DROP_OR_EDIT (vision §14.2 step 2).
    """
    sim = _cosine(hook_embedding, body_embedding)
    coherent = sim >= threshold
    return {
        "coherent":   coherent,
        "similarity": round(sim, 4),
        "threshold":  threshold,
        "action":     "PASS" if coherent else "DROP_OR_EDIT",
        "reason":     f"Hook-body cosine={sim:.3f} {'≥' if coherent else '<'} {threshold}",
    }


def check_graveyard_similarity(
    candidate_embedding: list,
    graveyard_embeddings: list,
    candidate_audience: str,
    theta: float = 0.85,
) -> dict:
    """
    Flag candidate if cosine similarity ≥ theta with any failed atom combination
    for the same audience (vision §13.2).
    """
    if not graveyard_embeddings:
        return {"flagged": False, "reason": "Empty graveyard", "matches": []}

    matches = []
    for entry in graveyard_embeddings:
        if entry.get("audience", "").upper() != candidate_audience.upper():
            continue
        emb = entry.get("embedding", [])
        if not emb:
            continue
        sim = _cosine(candidate_embedding, emb)
        if sim >= theta:
            matches.append({"atom_combo": entry.get("atom_combo", "UNKNOWN"), "similarity": round(sim, 4)})

    flagged = len(matches) > 0
    return {
        "flagged":  flagged,
        "theta":    theta,
        "matches":  matches,
        "reason": (f"Similar to {len(matches)} failed combo(s) at θ≥{theta}"
                   if flagged else f"No graveyard matches at θ≥{theta}"),
    }


def rank_by_glm_posterior(
    candidates: list,
    n_samples: int = 1000,
) -> list:
    """
    Rank candidates by GLM posterior predictive CVR, not raw CVR alone.
    vision §14.2 step 5.
    """
    if not candidates:
        return []

    rng = random.Random(42)
    scored = []
    for c in candidates:
        mu    = float(c.get("posterior_mean", 0.0))
        sigma = float(c.get("posterior_std", 0.01))
        samples  = [rng.gauss(mu, sigma) for _ in range(n_samples)]
        expected = sum(samples) / n_samples
        scored.append({**c, "ranking_score": round(expected, 6)})

    return sorted(scored, key=lambda x: x["ranking_score"], reverse=True)


def should_retry_graveyard_entry(failure_context: dict) -> dict:
    """
    Flag graveyard entry as retry candidate if failure was external:
    MARKET verdict, fraud, or site health incident (vision §13.2).
    """
    anchor = failure_context.get("anchor_verdict", "NONE")
    fraud  = failure_context.get("fraud_flag", False)
    health = failure_context.get("site_health_incident", False)

    external = anchor in ("MARKET", "MARKET_SHIFT") or fraud or health
    return {
        "retry_candidate": external,
        "reason": (
            f"Failure attributed to external cause: anchor={anchor}, fraud={fraud}, site_incident={health}"
            if external
            else "Failure appears intrinsic — no retry recommended"
        ),
    }


# ── DIVERSITY PENALTY — Maximum Marginal Relevance (vision §22) ───────────────

DIVERSITY_LAMBDA = 0.3   # 0 = pure relevance, 1 = pure diversity


def apply_diversity_penalty(
    candidates: list[dict],
    lambda_: float = DIVERSITY_LAMBDA,
) -> list[dict]:
    """
    MMR re-ranking: balances GLM posterior score with embedding diversity.
    Prevents top-5 list with identical hooks (vision §22).
    Each candidate: {"score": float, "embedding": list[float], ...}
    """
    if not candidates:
        return []

    selected: list[dict] = []
    remaining = list(candidates)

    while remaining:
        best: dict | None = None
        best_score = -1e9

        for cand in remaining:
            relevance = float(cand.get("score", cand.get("ranking_score", 0.0)))
            if not selected:
                diversity_bonus = 1.0
            else:
                sims = [
                    _cosine(cand.get("embedding", []), s.get("embedding", []))
                    for s in selected
                ]
                diversity_bonus = 1.0 - max(sims)

            mmr = (1.0 - lambda_) * relevance + lambda_ * diversity_bonus
            if mmr > best_score:
                best_score = mmr
                best       = cand

        if best is None:
            break
        selected.append(dict(best, mmr_score=round(best_score, 6)))
        remaining.remove(best)

    return selected
