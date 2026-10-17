"""Tests for evolution_service.py — Phase 6-4."""
import pytest
from app.services.evolution_service import (
    rank_creatives_by_cvr,
    crossover_atoms,
    mutate_atoms,
    generate_candidates,
)

ATOMS_A = [
    {"atom_type": "hook",     "label": "Hook A"},
    {"atom_type": "body",     "label": "Body A"},
    {"atom_type": "cta",      "label": "CTA A"},
]
ATOMS_B = [
    {"atom_type": "hook",     "label": "Hook B"},
    {"atom_type": "body",     "label": "Body B"},
    {"atom_type": "offer",    "label": "Offer B"},
]
POOL = [
    {"atom_type": "hook",  "label": "Pool Hook 1"},
    {"atom_type": "hook",  "label": "Pool Hook 2"},
    {"atom_type": "body",  "label": "Pool Body 1"},
    {"atom_type": "cta",   "label": "Pool CTA 1"},
    {"atom_type": "offer", "label": "Pool Offer 1"},
]


class TestRankCreativesByCvr:
    def test_sorted_descending(self):
        arms = [
            {"id": "a", "alpha": 1.0, "beta": 9.0},   # CVR=0.10
            {"id": "b", "alpha": 5.0, "beta": 5.0},   # CVR=0.50
            {"id": "c", "alpha": 8.0, "beta": 2.0},   # CVR=0.80
        ]
        ranked = rank_creatives_by_cvr(arms)
        assert [r["id"] for r in ranked] == ["c", "b", "a"]

    def test_empty_list(self):
        assert rank_creatives_by_cvr([]) == []

    def test_single_arm(self):
        arms = [{"id": "x", "alpha": 3.0, "beta": 3.0}]
        assert rank_creatives_by_cvr(arms)[0]["id"] == "x"

    def test_zero_alpha_beta_handled(self):
        arms = [{"id": "z", "alpha": 0.0, "beta": 0.0}]
        ranked = rank_creatives_by_cvr(arms)
        assert len(ranked) == 1


class TestCrossoverAtoms:
    def test_child_has_union_types(self):
        parent_a = {"atoms": ATOMS_A}
        parent_b = {"atoms": ATOMS_B}
        child = crossover_atoms(parent_a, parent_b, rng_seed=42)
        types = {a["atom_type"] for a in child}
        # All types from A∪B should be covered
        assert types == {"hook", "body", "cta", "offer"}

    def test_each_atom_comes_from_parent(self):
        parent_a = {"atoms": ATOMS_A}
        parent_b = {"atoms": ATOMS_B}
        child = crossover_atoms(parent_a, parent_b, rng_seed=0)
        all_labels = {a["label"] for a in ATOMS_A + ATOMS_B}
        for atom in child:
            assert atom["label"] in all_labels

    def test_deterministic_with_seed(self):
        pa = {"atoms": ATOMS_A}
        pb = {"atoms": ATOMS_B}
        c1 = crossover_atoms(pa, pb, rng_seed=7)
        c2 = crossover_atoms(pa, pb, rng_seed=7)
        assert c1 == c2

    def test_empty_parents_returns_empty(self):
        child = crossover_atoms({"atoms": []}, {"atoms": []})
        assert child == []

    def test_single_parent_atoms_returned(self):
        child = crossover_atoms({"atoms": ATOMS_A}, {"atoms": []}, rng_seed=0)
        assert len(child) == len(ATOMS_A)


class TestMutateAtoms:
    def test_mutation_rate_zero_returns_unchanged(self):
        mutated = mutate_atoms(ATOMS_A, POOL, mutation_rate=0.0, rng_seed=1)
        assert mutated == ATOMS_A

    def test_mutation_rate_one_replaces_all_in_pool(self):
        pool_labels = {a["label"] for a in POOL}
        mutated = mutate_atoms(ATOMS_A, POOL, mutation_rate=1.0, rng_seed=2)
        for atom in mutated:
            assert atom["label"] in pool_labels

    def test_length_preserved(self):
        mutated = mutate_atoms(ATOMS_A, POOL, mutation_rate=0.5, rng_seed=3)
        assert len(mutated) == len(ATOMS_A)

    def test_no_pool_atom_kept_unchanged(self):
        atoms = [{"atom_type": "video", "label": "Video A"}]
        mutated = mutate_atoms(atoms, POOL, mutation_rate=1.0, rng_seed=4)
        assert mutated[0]["label"] == "Video A"

    def test_deterministic_with_seed(self):
        m1 = mutate_atoms(ATOMS_A, POOL, mutation_rate=0.5, rng_seed=10)
        m2 = mutate_atoms(ATOMS_A, POOL, mutation_rate=0.5, rng_seed=10)
        assert m1 == m2


class TestGenerateCandidates:
    def test_correct_count(self):
        arms = [
            {"id": "a", "alpha": 8.0, "beta": 2.0, "atoms": ATOMS_A},
            {"id": "b", "alpha": 5.0, "beta": 5.0, "atoms": ATOMS_B},
        ]
        candidates = generate_candidates(arms, POOL, n_candidates=3)
        assert len(candidates) == 3

    def test_candidates_have_atoms(self):
        arms = [
            {"id": "a", "alpha": 8.0, "beta": 2.0, "atoms": ATOMS_A},
            {"id": "b", "alpha": 5.0, "beta": 5.0, "atoms": ATOMS_B},
        ]
        for c in generate_candidates(arms, POOL, n_candidates=2):
            assert isinstance(c["atoms"], list)

    def test_empty_arms_returns_empty(self):
        assert generate_candidates([], POOL, n_candidates=5) == []

    def test_single_arm_fallback(self):
        arms = [{"id": "a", "alpha": 8.0, "beta": 2.0, "atoms": ATOMS_A}]
        candidates = generate_candidates(arms, POOL, n_candidates=2)
        assert len(candidates) == 2

    def test_parent_ids_recorded(self):
        arms = [
            {"id": "a", "alpha": 8.0, "beta": 2.0, "atoms": ATOMS_A},
            {"id": "b", "alpha": 5.0, "beta": 5.0, "atoms": ATOMS_B},
        ]
        for c in generate_candidates(arms, POOL, n_candidates=1):
            assert "parent_ids" in c
