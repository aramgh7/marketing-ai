"""
Synthetic form check (vision §11.4) — HTTP-based health check for landing forms.
Uses HTTP POST instead of Playwright (Playwright deferred; requires separate infra).
Pure logic, zero FastAPI/Pydantic imports.
"""
from __future__ import annotations
import json
import time
import urllib.error
import urllib.request


def check_form_health(
    form_endpoint_url: str,
    test_payload: dict | None = None,
    timeout_seconds: float = 5.0,
) -> dict:
    """
    Sends a synthetic POST to the form endpoint.
    Expects 200/201/204 response. Records latency_ms.
    """
    payload = test_payload or {
        "name":       "__synthetic_test__",
        "email":      "healthcheck@synthetic.internal",
        "phone":      "+10000000000",
        "_synthetic": True,
    }

    start = time.monotonic()
    try:
        data = json.dumps(payload).encode()
        req  = urllib.request.Request(
            form_endpoint_url,
            data=data,
            headers={
                "Content-Type":     "application/json",
                "X-Synthetic-Check": "1",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout_seconds) as resp:
            latency_ms = (time.monotonic() - start) * 1000
            return {
                "ok":         resp.status in (200, 201, 204),
                "status":     resp.status,
                "latency_ms": round(latency_ms, 1),
                "error":      None,
            }
    except urllib.error.HTTPError as e:
        return {
            "ok":         False,
            "status":     e.code,
            "latency_ms": round((time.monotonic() - start) * 1000, 1),
            "error":      str(e),
        }
    except Exception as e:
        return {
            "ok":         False,
            "status":     None,
            "latency_ms": round((time.monotonic() - start) * 1000, 1),
            "error":      str(e),
        }


def should_resume_after_passes(consecutive_passes: int, required_passes: int = 3) -> bool:
    """Returns True when marketer should be prompted to confirm resume (vision §11.4)."""
    return consecutive_passes >= required_passes
