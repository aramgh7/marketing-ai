from app.services.evolution_service import apply_diversity_penalty


def test_empty_candidates():
    assert apply_diversity_penalty([]) == []


def test_diversity_reranks():
    # Two candidates with same high score but different embeddings
    cands = [
        {"id": "A", "score": 0.9, "embedding": [1.0, 0.0]},
        {"id": "B", "score": 0.85, "embedding": [1.0, 0.0]},  # very similar to A
        {"id": "C", "score": 0.7,  "embedding": [0.0, 1.0]},  # different from A
    ]
    result = apply_diversity_penalty(cands, lambda_=0.5)
    ids = [r["id"] for r in result]
    # A selected first (highest score + no prior)
    assert ids[0] == "A"
    # C should be selected before B because B is too similar to A (diversity penalty)
    assert ids[1] == "C"
    assert ids[2] == "B"


def test_all_have_mmr_score():
    cands = [{"id": str(i), "score": float(i) / 10, "embedding": [float(i), 0.0]} for i in range(5)]
    result = apply_diversity_penalty(cands)
    assert all("mmr_score" in r for r in result)
    assert len(result) == 5


def test_single_candidate():
    cands = [{"id": "X", "score": 0.5, "embedding": [1.0, 0.0]}]
    result = apply_diversity_penalty(cands)
    assert len(result) == 1
    assert result[0]["id"] == "X"
