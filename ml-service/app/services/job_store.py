"""Redis-backed job status store for async analysis jobs."""
import json
import redis.asyncio as aioredis
from app.config import settings

_pool: aioredis.Redis | None = None


def get_redis() -> aioredis.Redis:
    global _pool
    if _pool is None:
        _pool = aioredis.from_url(settings.redis_url, decode_responses=True)
    return _pool


JOB_TTL = 3600  # 1 hour


async def set_job(job_id: str, data: dict) -> None:
    r = get_redis()
    await r.setex(f"job:{job_id}", JOB_TTL, json.dumps(data))


async def get_job(job_id: str) -> dict | None:
    r = get_redis()
    raw = await r.get(f"job:{job_id}")
    return json.loads(raw) if raw else None
