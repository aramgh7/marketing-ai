"""
Roadmap Phase 11-1: Meta ad id → creative id resolution for /decide/landing.
Pure logic only — the DB fetch is injected.
"""
import asyncio

import pytest

from app.services.ad_map_service import AdMapCache, classify_creative, is_uuid, resolve_creative_id

CREATIVE = "8f2b1c3e-4d5a-4b6c-8d7e-9f0a1b2c3d4e"
TENANT = "11111111-2222-4333-8444-555555555555"


# One loop for the module, left as the current loop so later tests that call
# asyncio.get_event_loop() (e.g. test_db_scope) still find one.
_LOOP = asyncio.new_event_loop()
asyncio.set_event_loop(_LOOP)


def run(coro):
    return _LOOP.run_until_complete(coro)


# ─── is_uuid / classify_creative ─────────────────────────────────────────────

def test_is_uuid_accepts_uuid_and_rejects_meta_ad_id():
    assert is_uuid(CREATIVE)
    assert not is_uuid("120210000000000")
    assert not is_uuid("")
    assert not is_uuid(None)


def test_classify_uuid_creative_is_request_source():
    cid, ad, src = classify_creative(CREATIVE, {"meta_ad_id": "123"})
    assert (cid, ad, src) == (CREATIVE, "123", "request")


def test_classify_non_uuid_creative_is_treated_as_meta_ad_id():
    cid, ad, src = classify_creative("120210000000000", {})
    assert cid is None and ad == "120210000000000" and src == "meta_ad_map"


def test_classify_context_meta_ad_id():
    cid, ad, src = classify_creative(None, {"meta_ad_id": "999", "placement": "feed"})
    assert cid is None and ad == "999" and src == "meta_ad_map"


def test_classify_nothing_is_none():
    assert classify_creative(None, {}) == (None, None, "none")
    assert classify_creative("", {"utm_source": "meta"}) == (None, None, "none")


# ─── resolve_creative_id with a fake fetch ───────────────────────────────────

class FakeClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


def _fetch_factory(result, calls):
    async def fetch(tenant_id, meta_ad_id):
        calls.append((tenant_id, meta_ad_id))
        if isinstance(result, Exception):
            raise result
        return result
    return fetch


def test_resolve_hits_db_once_then_cache():
    calls = []
    cache = AdMapCache(ttl=60, clock=FakeClock())
    fetch = _fetch_factory(CREATIVE, calls)
    assert run(resolve_creative_id(TENANT, "123", fetch=fetch, cache=cache)) == CREATIVE
    assert run(resolve_creative_id(TENANT, "123", fetch=fetch, cache=cache)) == CREATIVE
    assert len(calls) == 1


def test_resolve_negative_result_is_cached_too():
    calls = []
    cache = AdMapCache(ttl=60, clock=FakeClock())
    fetch = _fetch_factory(None, calls)
    assert run(resolve_creative_id(TENANT, "777", fetch=fetch, cache=cache)) is None
    assert run(resolve_creative_id(TENANT, "777", fetch=fetch, cache=cache)) is None
    assert len(calls) == 1


def test_resolve_cache_expires_after_ttl():
    calls = []
    clock = FakeClock()
    cache = AdMapCache(ttl=60, clock=clock)
    fetch = _fetch_factory(CREATIVE, calls)
    run(resolve_creative_id(TENANT, "123", fetch=fetch, cache=cache))
    clock.t = 61.0
    run(resolve_creative_id(TENANT, "123", fetch=fetch, cache=cache))
    assert len(calls) == 2


def test_resolve_db_error_returns_none_and_is_not_cached():
    calls = []
    cache = AdMapCache(ttl=60, clock=FakeClock())
    fetch = _fetch_factory(RuntimeError("db down"), calls)
    assert run(resolve_creative_id(TENANT, "123", fetch=fetch, cache=cache)) is None
    assert run(resolve_creative_id(TENANT, "123", fetch=fetch, cache=cache)) is None
    assert len(calls) == 2  # errors are retried, not cached


def test_resolve_blank_inputs_short_circuit():
    calls = []
    fetch = _fetch_factory(CREATIVE, calls)
    assert run(resolve_creative_id(TENANT, None, fetch=fetch, cache=AdMapCache())) is None
    assert run(resolve_creative_id(TENANT, "   ", fetch=fetch, cache=AdMapCache())) is None
    assert run(resolve_creative_id("", "123", fetch=fetch, cache=AdMapCache())) is None
    assert calls == []


def test_cache_is_per_tenant():
    calls = []
    cache = AdMapCache(ttl=60, clock=FakeClock())
    fetch = _fetch_factory(CREATIVE, calls)
    run(resolve_creative_id(TENANT, "123", fetch=fetch, cache=cache))
    run(resolve_creative_id("22222222-2222-4222-8222-222222222222", "123", fetch=fetch, cache=cache))
    assert len(calls) == 2
