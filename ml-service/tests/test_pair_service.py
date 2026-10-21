"""
Roadmap Phase 11-5: creative → landing pairs as arms. Pure logic, no DB.
"""
import numpy as np

from app.services.pair_service import NO_CREATIVE_TITLE, summarise_pairs, win_probabilities

C1 = "8f2b1c3e-4d5a-4b6c-8d7e-9f0a1b2c3d4e"
C2 = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"
V1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
V2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"


def _row(creative_id, variant_id, decisions, rewards, title="t", code="MAI-ABC123", vname="v"):
    return {"creative_id": creative_id, "creative_title": title, "creative_code": code,
            "variant_id": variant_id, "variant_name": vname, "decisions": decisions, "rewards": rewards}


def test_summarise_beta_posterior_and_order():
    pairs = summarise_pairs([_row(C1, V1, 100, 10), _row(C1, V2, 50, 20)])
    assert [p["variant_id"] for p in pairs] == [V2, V1]
    top = pairs[0]
    assert top["alpha"] == 21.0 and top["beta"] == 31.0
    assert top["cvr"] == 0.4
    assert top["posterior_mean"] == round(21 / 52, 4)
    assert top["creative_code"] == "MAI-ABC123"


def test_summarise_merges_duplicate_keys_and_caps_rewards():
    pairs = summarise_pairs([_row(C1, V1, 10, 3), _row(C1, V1, 5, 9)])
    assert len(pairs) == 1
    assert pairs[0]["decisions"] == 15
    assert pairs[0]["rewards"] == 12  # 3 + 9 capped at decisions (15) → 12 stays


def test_summarise_min_decisions_filter():
    pairs = summarise_pairs([_row(C1, V1, 2, 1), _row(C2, V1, 30, 5)], min_decisions=10)
    assert [p["creative_id"] for p in pairs] == [C2]


def test_summarise_null_creative_row_is_labelled():
    pairs = summarise_pairs([_row(None, V1, 7, 1, title=None, code="X"), _row(None, V1, 3, 0, title=None)])
    assert len(pairs) == 1
    p = pairs[0]
    assert p["creative_id"] is None
    assert p["creative_title"] == NO_CREATIVE_TITLE
    assert p["creative_code"] is None
    assert p["decisions"] == 10


def test_summarise_skips_rows_without_variant():
    assert summarise_pairs([_row(C1, "", 5, 1), _row(C1, None, 5, 1)]) == []


def test_win_probabilities_sum_to_one_and_favour_the_better_pair():
    pairs = summarise_pairs([_row(C1, V1, 200, 100), _row(C1, V2, 200, 20), _row(C2, V1, 200, 10)])
    win_probabilities(pairs, draws=400, rng=np.random.default_rng(7))
    total = sum(p["win_probability"] for p in pairs)
    assert abs(total - 1.0) < 1e-6
    best = max(pairs, key=lambda p: p["win_probability"])
    assert best["variant_id"] == V1 and best["creative_id"] == C1
    assert best["win_probability"] > 0.9


def test_win_probabilities_single_pair_is_one_and_empty_is_noop():
    pairs = summarise_pairs([_row(C1, V1, 3, 1)])
    win_probabilities(pairs)
    assert pairs[0]["win_probability"] == 1.0
    assert win_probabilities([]) == []
