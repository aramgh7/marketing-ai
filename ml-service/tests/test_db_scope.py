"""Roadmap 9-4: ScopedPool sets the RLS GUC per acquired connection and clears it on release."""
import asyncio
import sys
from contextlib import asynccontextmanager
from unittest.mock import MagicMock

import pytest

# Host Python has no asyncpg; other test files stub app.services.db_client with a MagicMock —
# evict that stub and provide a fake asyncpg so the real module under test is imported.
sys.modules.setdefault("asyncpg", MagicMock())
sys.modules.setdefault("app.config", MagicMock())
if isinstance(sys.modules.get("app.services.db_client"), MagicMock):
    del sys.modules["app.services.db_client"]

from app.services.db_client import ScopedPool, scope_statements  # noqa: E402


class FakeConn:
    def __init__(self, log):
        self.log = log

    async def execute(self, sql, *args):
        self.log.append(("execute", sql, args))
        return "OK"

    async def fetch(self, sql, *args):
        self.log.append(("fetch", sql, args))
        return [{"n": 1}]

    async def fetchrow(self, sql, *args):
        self.log.append(("fetchrow", sql, args))
        return {"n": 1}

    async def fetchval(self, sql, *args):
        self.log.append(("fetchval", sql, args))
        return 1


class FakePool:
    def __init__(self):
        self.log = []

    @asynccontextmanager
    async def acquire(self):
        yield FakeConn(self.log)


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


def test_scope_statements_tenant_and_system():
    assert scope_statements("t1", False) == [("SELECT set_config('app.current_tenant', $1, false)", ("t1",))]
    assert scope_statements(None, True) == [("SELECT set_config('app.rls_bypass', 'on', false)", ())]
    with pytest.raises(ValueError):
        scope_statements(None, False)


def test_tenant_pool_sets_guc_before_query_and_resets_after():
    fake = FakePool()
    pool = ScopedPool(fake, tenant_id="11111111-1111-1111-1111-111111111111")
    rows = _run(pool.fetch("SELECT 1 FROM sessions WHERE tenant_id = $1", "x"))
    assert rows == [{"n": 1}]
    kinds = [(k, sql.split("(")[0]) for k, sql, _ in fake.log]
    assert kinds[0] == ("execute", "SELECT set_config")           # scope first
    assert fake.log[0][2] == ("11111111-1111-1111-1111-111111111111",)
    assert fake.log[1][0] == "fetch"
    assert "app.rls_bypass', ''" in fake.log[-1][1]                # reset last


def test_system_pool_sets_bypass():
    fake = FakePool()
    pool = ScopedPool(fake, system=True)
    _run(pool.execute("DELETE FROM x"))
    assert "app.rls_bypass', 'on'" in fake.log[0][1]
    assert fake.log[1][1] == "DELETE FROM x"


def test_acquire_context_manager_scopes_once_per_connection():
    fake = FakePool()
    pool = ScopedPool(fake, tenant_id="t")

    async def body():
        async with pool.acquire() as conn:
            await conn.fetchval("SELECT 1")
            await conn.fetchval("SELECT 2")

    _run(body())
    sets = [e for e in fake.log if "app.current_tenant', $1" in e[1]]
    assert len(sets) == 1
    assert fake.log[-1][1].startswith("SELECT set_config('app.current_tenant', ''")
