"""Audit §3.4: internal key fails closed, model bytes need a signature, SSRF guard.

Tests the pure layer (app.services.security_service) so they run without FastAPI on the host.
"""
import pytest

from app.services.security_service import (
    SecurityError,
    internal_key_ok,
    sign_model_bytes,
    validate_public_http_url,
    verify_model_signature,
)


class TestInternalKey:
    def test_correct_key_passes(self):
        assert internal_key_ok("k-test", "k-test") is True

    def test_wrong_key_fails(self):
        assert internal_key_ok("nope", "k-test") is False

    def test_missing_key_fails(self):
        assert internal_key_ok(None, "k-test") is False
        assert internal_key_ok("", "k-test") is False

    def test_empty_configured_key_refuses_everything(self):
        # previously `if INTERNAL_KEY and key != INTERNAL_KEY` let everything through when unset
        assert internal_key_ok("", "") is False
        assert internal_key_ok("anything", "") is False
        assert internal_key_ok("anything", None) is False


class TestModelSignature:
    def test_roundtrip(self):
        b = b"\x80\x04joblib"
        assert verify_model_signature(b, sign_model_bytes(b, "k"), "k") is True

    def test_tampered_bytes_fail(self):
        sig = sign_model_bytes(b"model-a", "k")
        assert verify_model_signature(b"model-b", sig, "k") is False

    def test_wrong_key_fails(self):
        sig = sign_model_bytes(b"model-a", "k1")
        assert verify_model_signature(b"model-a", sig, "k2") is False

    def test_missing_signature_fails(self):
        assert verify_model_signature(b"model-a", None, "k") is False
        assert verify_model_signature(b"model-a", "", "k") is False

    def test_no_key_configured_never_verifies(self):
        assert verify_model_signature(b"m", sign_model_bytes(b"m", ""), "") is False


class TestPublicUrlGuard:
    @pytest.mark.parametrize("url", [
        "http://169.254.169.254/latest/meta-data/",
        "http://127.0.0.1:6379/",
        "http://10.0.0.5/form",
        "http://192.168.1.1/",
        "http://[::1]/",
        "http://localhost:8080/api",
        "http://metadata.google.internal/",
        "http://postgres.internal/",
        "ftp://example.com/x",
        "not a url",
    ])
    def test_blocked(self, url):
        with pytest.raises(SecurityError):
            validate_public_http_url(url, resolve=False)

    def test_public_literal_ip_allowed(self):
        validate_public_http_url("https://8.8.8.8/form", resolve=False)

    def test_public_hostname_allowed_without_resolution(self):
        validate_public_http_url("https://forms.example.com/submit", resolve=False)
