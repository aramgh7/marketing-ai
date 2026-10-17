"""
Tests for Phase 5-3 (Contextual Bandit context_key derivation) and
Phase 5-4 (Landing block personalisation in /decide/personalise).

Pure logic only — no FastAPI, no DB, no async.
Imports ONLY from app.routers.decide and app.services.cluster_service.
"""
import pytest

from app.services.decide_service import derive_context_key as _derive_context_key
from app.services.cluster_service import personalise_block_order, CLUSTER_BLOCK_RULES


# ──────────────────────────────────────────────────────────────────────────────
# Phase 5-3: _derive_context_key
# ──────────────────────────────────────────────────────────────────────────────

def test_derive_key_cluster_id_overrides_global():
    assert _derive_context_key("global", cluster_id=3) == "cluster_3"


def test_derive_key_cluster_id_zero():
    assert _derive_context_key("global", cluster_id=0) == "cluster_0"


def test_derive_key_no_cluster_id_stays_global():
    assert _derive_context_key("global", cluster_id=None) == "global"


def test_derive_key_explicit_key_takes_precedence():
    # If caller explicitly set a non-"global" key, cluster_id is ignored
    assert _derive_context_key("campaign_abc", cluster_id=5) == "campaign_abc"


def test_derive_key_all_clusters_produce_unique_keys():
    keys = [_derive_context_key("global", i) for i in range(7)]
    assert len(set(keys)) == 7


def test_derive_key_negative_cluster_produces_negative_key():
    # Negative cluster_id: not expected in normal flow, but should not crash
    key = _derive_context_key("global", cluster_id=-1)
    assert key == "cluster_-1"


# ──────────────────────────────────────────────────────────────────────────────
# Phase 5-4: personalise_block_order (integration with decide constraints)
# ──────────────────────────────────────────────────────────────────────────────

DEFAULT_ORDER = ["hero_block", "proof_block", "pricing_block", "faq_block", "cta_block"]


def test_personalise_produces_same_blocks_different_order():
    for label in CLUSTER_BLOCK_RULES:
        result = personalise_block_order(DEFAULT_ORDER, label)
        assert set(result) == set(DEFAULT_ORDER), f"Extra/missing blocks for {label}"


def test_personalise_never_inserts_new_blocks():
    result = personalise_block_order(
        DEFAULT_ORDER, "price_seeker", allowed_block_ids=["pricing_block", "proof_block"]
    )
    assert all(b in DEFAULT_ORDER for b in result)


def test_personalise_control_group_uses_default_order():
    # Simulates Spring Boot returning "control" — endpoint receives unchanged default
    result = personalise_block_order(DEFAULT_ORDER, "unknown_archetype")
    assert result == DEFAULT_ORDER


def test_personalise_all_seven_archetypes_produce_valid_output():
    archetypes = list(CLUSTER_BLOCK_RULES.keys())
    assert len(archetypes) == 7
    for arch in archetypes:
        result = personalise_block_order(DEFAULT_ORDER, arch)
        assert len(result) == len(DEFAULT_ORDER)


def test_price_seeker_pricing_before_proof():
    result = personalise_block_order(DEFAULT_ORDER, "price_seeker")
    assert result.index("pricing_block") < result.index("proof_block")


def test_proof_seeker_proof_first():
    result = personalise_block_order(DEFAULT_ORDER, "proof_seeker")
    assert result[0] == "proof_block"


def test_ready_to_buy_cta_before_proof():
    result = personalise_block_order(DEFAULT_ORDER, "ready_to_buy")
    assert result.index("cta_block") < result.index("proof_block")


def test_engaged_returner_cta_first():
    result = personalise_block_order(DEFAULT_ORDER, "engaged_returner")
    assert result[0] == "cta_block"


def test_allowed_block_ids_restricts_reorder_scope():
    allowed = ["pricing_block"]
    result = personalise_block_order(DEFAULT_ORDER, "price_seeker", allowed_block_ids=allowed)
    # Non-allowed blocks keep their original relative positions
    non_allowed = [b for b in DEFAULT_ORDER if b not in allowed]
    result_non_allowed = [b for b in result if b not in allowed]
    assert result_non_allowed == non_allowed
