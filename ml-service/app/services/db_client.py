"""Direct asyncpg access for ml-service writes.

FastAPI sets app.current_tenant within each transaction so RLS policies
(enforced by PostgreSQL) correctly scope all writes to the tenant.
"""
import asyncpg
from app.config import settings

_pool: asyncpg.Pool | None = None


async def get_pool() -> asyncpg.Pool:
    global _pool
    if _pool is None:
        # Strip SQLAlchemy driver prefix if present
        url = settings.database_url.replace("postgresql+asyncpg://", "postgresql://")
        _pool = await asyncpg.create_pool(url, min_size=2, max_size=10)
    return _pool


async def save_atoms(tenant_id: str, creative_id: str, atoms: list[dict]) -> None:
    """Insert extracted atoms, replacing any previously auto-extracted ones."""
    pool = await get_pool()
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.current_tenant', $1, true)", tenant_id)
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
    pool = await get_pool()
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.current_tenant', $1, true)", tenant_id)
            await conn.execute(
                "UPDATE creatives SET status = $1 WHERE id = $2 AND tenant_id = $3",
                status,
                creative_id,
                tenant_id,
            )
