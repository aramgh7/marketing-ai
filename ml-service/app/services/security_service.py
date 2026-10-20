"""Pure security helpers — no FastAPI imports, so they are unit-testable without the web stack.

Used by app/security.py (the FastAPI-facing layer). See that module for the audit context.
"""
import hashlib
import hmac
import ipaddress
import socket
from typing import Optional
from urllib.parse import urlparse


class SecurityError(ValueError):
    """Raised with a short, user-safe message; app/security.py maps it to an HTTP status."""


# ─── Internal API key ────────────────────────────────────────────────────────

def internal_key_ok(provided: Optional[str], expected: Optional[str]) -> bool:
    """Fail closed: an empty configured key refuses everything."""
    if not expected or not provided:
        return False
    return hmac.compare_digest(str(provided), str(expected))


# ─── Model byte signatures ───────────────────────────────────────────────────

def sign_model_bytes(model_bytes: bytes, key: str) -> str:
    return hmac.new((key or "").encode("utf-8"), model_bytes, hashlib.sha256).hexdigest()


def verify_model_signature(model_bytes: bytes, signature: Optional[str], key: Optional[str]) -> bool:
    if not signature or not key:
        return False
    return hmac.compare_digest(sign_model_bytes(model_bytes, key), signature)


# ─── SSRF guard ──────────────────────────────────────────────────────────────

_BLOCKED_HOSTS = {"localhost", "metadata", "metadata.google.internal"}


def _parse_ip(host: str):
    try:
        return ipaddress.ip_address(host)
    except ValueError:
        return None  # not an IP literal


def _is_private_ip(ip: str) -> bool:
    addr = ipaddress.ip_address(ip)
    return (
        addr.is_private or addr.is_loopback or addr.is_link_local or addr.is_multicast
        or addr.is_reserved or addr.is_unspecified
    )


def validate_public_http_url(url: str, resolve: bool = True) -> None:
    """Raise SecurityError unless url is http(s) to a public host.

    With resolve=True every A/AAAA record of the hostname is checked, so a DNS name pointing at
    169.254.169.254 or 10.x is refused as well as a literal address.
    """
    try:
        parsed = urlparse(url)
    except ValueError:
        raise SecurityError("invalid url")
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise SecurityError("url must be http(s) with a host")
    host = parsed.hostname.lower().rstrip(".")
    if host in _BLOCKED_HOSTS or host.endswith(".internal") or host.endswith(".local"):
        raise SecurityError("url host not allowed")
    literal = _parse_ip(host)
    if literal is not None:
        if _is_private_ip(host):
            raise SecurityError("url host not allowed")
        return  # literal public IP
    if not resolve:
        return
    try:
        infos = socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80),
                                   proto=socket.IPPROTO_TCP)
    except socket.gaierror:
        raise SecurityError("url host does not resolve")
    for info in infos:
        if _is_private_ip(info[4][0]):
            raise SecurityError("url host resolves to a private address")
