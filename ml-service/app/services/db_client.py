"""Direct asyncpg access for ml-service.

Roadmap 9-4 (arch §8 v1.5): the service connects as the non-owner role ``mai_ml`` (V127), so every
query is subject to the RLS policies of V116. Scoping is explicit and per connection:

    pool = await tenant_pool(tenant_id)   # set_config('app.current_tenant', tenant_id) on acquire
    pool = await system_pool()            # set_config('app.rls_bypass', 'on') — cross-tenant jobs only

Both return a :class:`ScopedPool` with the asyncpg pool API that the code base uses
(``acquire()``, ``fetch``, ``fetchrow``, ``fetchval``, ``execute``). The GUCs are set at session
level on the acquired connection and cleared when it goes back to the pool, mirroring Spring's
``TenantScopedConnection``; nested ``conn.transaction()`` blocks may still issue a transaction-local
``set_config(..., true)`` for a different tenant (reward sweep), which overrides the session value
inside that transaction only.

``get_pool()`` returns the raw pool and must not be used for tenant data — ``scripts`` and tests grep
for it.
"""
from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Optional

import asyncpg
from app.config import settings

_pool: asyncpg.Pool | None = None

_SET_TENANT = "SELECT set_config('app.current_tenant', $1, false)"
_SET_BYPASS = "SELECT set_config('app.rls_bypass', 'on', false)"
_RESET = "SELECT set_config('app.current_tenant', '', false), set_config('app.rls_bypass', '', false)"


async def get_pool() -> asyncpg.Pool:
    global _pool
    if _pool is None:
        # Strip SQLAlchemy driver prefix if present
        url = settings.database_url.replace("postgresql+asyncpg://", "postgresql://")
        _pool = await asyncpg.create_pool(url, min_size=2, max_size=10)
    return _pool


def scope_statements(tenant_id: Optional[str], system: bool) -> list[tuple[str, tuple]]:
    """Pure: the statements a scoped connection runs after acquire (tested without a DB)."""
    if system:
        return [(_SET_BYPASS, ())]
    if not tenant_id:
        raise ValueError("tenant_pool() needs a tenant_id; use system_pool() for cross-tenant work")
    return [(_SET_TENANT, (str(tenant_id),))]


class ScopedPool:
    """asyncpg pool facade that scopes every acquired connection to one tenant or to system scope."""

    def __init__(self, pool: Any, tenant_id: Optional[str] = None, system: bool = False):
        self._pool = pool
        self.tenant_id = str(tenant_id) if tenant_id else None
        self.system = system
        self._statements = scope_statements(self.tenant_id, system)

    @asynccontextmanager
    async def acquire(self):
        async with self._pool.acquire() as conn:
            for sql, args in self._statements:
                await conn.execute(sql, *args)
            try:
                yield conn
            finally:
                try:
                    await conn.execute(_RESET)
                except Exception:  # connection already broken — the pool will discard it
                    pass

    async def fetch(self, query: str, *args):
        async with self.acquire() as conn:
            return await conn.fetch(query, *args)

    async def fetchrow(self, query: str, *args):
        async with self.acquire() as conn:
            return await conn.fetchrow(query, *args)

    async def fetchval(self, query: str, *args):
        async with self.acquire() as conn:
            return await conn.fetchval(query, *args)

    async def execute(self, query: str, *args):
        async with self.acquire() as conn:
            return await conn.execute(query, *args)


async def tenant_pool(tenant_id: str) -> ScopedPool:
    return ScopedPool(await get_pool(), tenant_id=tenant_id)


async def system_pool() -> ScopedPool:
    return ScopedPool(await get_pool(), system=True)


async def save_atoms(tenant_id: str, creative_id: str, atoms: list[dict]) -> None:
    """Insert extracted atoms, replacing any previously auto-extracted ones."""
    pool = await tenant_pool(tenant_id)
    async with pool.acquire() as conn:
        async with conn.transaction():
            # Remove previous auto-extracted atoms (keep manual ones)
            await conn.execute(
                "DELETE FROM creative_atoms WHERE creative_id = $1 AND extracted_by != 'manual'",
                creative_id,
            )
            for atom in atoms:
                await conn.execute(
                    """
                    INSERT INTO creative_atoms
                        (creative_id, tenant_id, atom_type, atom_value, confidence, extracted_by)
                    VALUES ($1, $2, $3, $4, $5, $6)
                    """,
                    creative_id,
                    tenant_id,
                    atom["type"],
                    atom["value"],
                    atom.get("confidence"),
                    atom.get("extracted_by", "claude-vision"),
                )


async def update_creative_status(tenant_id: str, creative_id: str, status: str) -> None:
    pool = await tenant_pool(tenant_id)
    await pool.execute(
        "UPDATE creatives SET status = $1 WHERE id = $2 AND tenant_id = $3",
        status,
        creative_id,
        tenant_id,
    )


async def update_creative_transcript(tenant_id: str, creative_id: str, transcript: str) -> None:
    """Roadmap 1-2: Whisper auto-transcription on upload — stored so the marketer can edit it."""
    pool = await tenant_pool(tenant_id)
    await pool.execute(
        "UPDATE creatives SET transcript = $1 WHERE id = $2 AND tenant_id = $3 "
        "AND (transcript IS NULL OR transcript = '')",
        transcript, creative_id, tenant_id,
    )
