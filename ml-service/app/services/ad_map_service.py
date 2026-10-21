"""
Meta ad → creative resolution (roadmap Phase 11-1, `.ai/creative-publishing.md`).

The landing URL carries `mai_c={{ad.id}}` (Meta substitutes the ad id at click time). The platform
keeps `meta_ad_map(tenant_id, meta_ad_id, creative_id, ...)` (V136, written by Spring on publish or
by the hourly reconcile job). `/decide/landing` resolves the ad id to our creative so the decision
is logged against the creative→landing pair.

Pure helpers (`is_uuid`, `AdMapCache`) have no I/O; `resolve_creative_id` uses the tenant pool and
never raises — an unknown ad or a DB error simply yields None (the decision must not fail).
"""
from __future__ import annotations

import logging
import time
import uuid
from typing import Awaitable, Callable, Optional

logger = logging.getLogger(__name__)

CACHE_TTL_SECONDS = 60.0
_SQL = (
    "SELECT creative_id::text AS creative_id FROM meta_ad_map "
    "WHERE tenant_id = $1::uuid AND meta_ad_id = $2 AND creative_id IS NOT NULL"
)


def is_uuid(value: Optional[str]) -> bool:
    """True when the string is a well-formed UUID (our creative ids); Meta ad ids are numeric strings."""
    if not value:
        return False
    try:
        uuid.UUID(str(value))
        return True
    except (ValueError, AttributeError, TypeError):
        return False


class AdMapCache:
    """Tiny in-process TTL cache keyed by (tenant_id, meta_ad_id). Negative results are cached too."""

    def __init__(self, ttl: float = CACHE_TTL_SECONDS, clock: Callable[[], float] = time.monotonic):
        self._ttl = ttl
        self._clock = clock
        self._items: dict[tuple[str, str], tuple[float, Optional[str]]] = {}

    def get(self, tenant_id: str, meta_ad_id: str) -> tuple[bool, Optional[str]]:
        """Returns (hit, value)."""
        entry = self._items.get((tenant_id, meta_ad_id))
        if entry is None:
            return False, None
        expires_at, value = entry
        if self._clock() >= expires_at:
            self._items.pop((tenant_id, meta_ad_id), None)
            return False, None
        return True, value

    def put(self, tenant_id: str, meta_ad_id: str, value: Optional[str]) -> None:
        self._items[(tenant_id, meta_ad_id)] = (self._clock() + self._ttl, value)

    def clear(self) -> None:
        self._items.clear()


_cache = AdMapCache()


async def _fetch_from_db(tenant_id: str, meta_ad_id: str) -> Optional[str]:
    from app.services.db_client import tenant_pool  # local import keeps the module importable without asyncpg

    pool = await tenant_pool(tenant_id)
    row = await pool.fetchrow(_SQL, tenant_id, meta_ad_id)
    return row["creative_id"] if row else None


async def resolve_creative_id(
    tenant_id: str,
    meta_ad_id: Optional[str],
    *,
    fetch: Optional[Callable[[str, str], Awaitable[Optional[str]]]] = None,
    cache: Optional[AdMapCache] = None,
) -> Optional[str]:
    """Map a Meta ad id to our creative id via meta_ad_map. None when unknown or on any error."""
    if not tenant_id or not meta_ad_id:
        return None
    meta_ad_id = str(meta_ad_id).strip()
    if not meta_ad_id:
        return None
    cache = cache if cache is not None else _cache
    hit, value = cache.get(tenant_id, meta_ad_id)
    if hit:
        return value
    fetch = fetch or _fetch_from_db
    try:
        value = await fetch(tenant_id, meta_ad_id)
    except Exception as exc:  # noqa: BLE001 — the decision must never fail because of the map
        logger.warning("meta_ad_map lookup failed tenant=%s ad=%s: %s", tenant_id, meta_ad_id, exc)
        return None
    cache.put(tenant_id, meta_ad_id, value)
    return value


def classify_creative(creative_id: Optional[str], context: dict) -> tuple[Optional[str], Optional[str], str]:
    """Split the request's creative hint into (creative_id, meta_ad_id, source_hint).

    - a UUID `creative_id` is ours → source "request"
    - a non-UUID `creative_id` is treated as a Meta ad id
    - otherwise `context.meta_ad_id` is used
    Returns source_hint "request" | "meta_ad_map" | "none" (meta_ad_map still needs resolution).
    """
    meta_ad_id = context.get("meta_ad_id") if isinstance(context, dict) else None
    if creative_id and is_uuid(creative_id):
        return str(creative_id), (str(meta_ad_id) if meta_ad_id else None), "request"
    if creative_id and not is_uuid(creative_id):
        meta_ad_id = str(creative_id)
    if meta_ad_id:
        return None, str(meta_ad_id), "meta_ad_map"
    return None, None, "none"
