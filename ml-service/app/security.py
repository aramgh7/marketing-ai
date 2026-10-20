"""Internal-service security — FastAPI-facing layer (audit 2026-10-17 §3.4).

- Internal API key: ONE implementation, fail-closed, read from settings (which reads .env), used as a
  router-level dependency in main.py for every router except /health. Twenty routers used to carry
  their own copy reading os.getenv with three different defaults and `if KEY and ...` (fail-open).
- Model signatures: /propensity/score unpickles caller-supplied joblib bytes; unpickling arbitrary
  bytes is remote code execution. Bytes are now only accepted with an HMAC produced by /fit.
- Public-URL guard: /analyze/form-health POSTs to a tenant-configured URL from inside the ML host;
  private, loopback, link-local and cloud-metadata targets are refused (SSRF).

Pure logic lives in app/services/security_service.py (unit-tested without FastAPI).
"""
from typing import Optional

from fastapi import Header, HTTPException

from app.config import settings
from app.services import security_service as _svc


def check_internal_key(key: Optional[str]) -> None:
    """Raise 401 unless `key` equals settings.internal_api_key. An empty configured key refuses everything."""
    if not _svc.internal_key_ok(key, settings.internal_api_key):
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-API-Key")


async def require_internal_key(
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
) -> None:
    """FastAPI dependency: `dependencies=[Depends(require_internal_key)]` on include_router."""
    check_internal_key(x_internal_api_key)


def sign_model_bytes(model_bytes: bytes) -> str:
    return _svc.sign_model_bytes(model_bytes, settings.internal_api_key)


def verify_model_signature(model_bytes: bytes, signature: Optional[str]) -> bool:
    return _svc.verify_model_signature(model_bytes, signature, settings.internal_api_key)


def assert_public_http_url(url: str, resolve: bool = True) -> None:
    try:
        _svc.validate_public_http_url(url, resolve=resolve)
    except _svc.SecurityError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
