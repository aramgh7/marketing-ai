"""Roadmap 9-16: AI video provider client — request builders, status normalisation, mock transport flow."""
import json

import httpx
import pytest

from app.services.ai_video_client import (
    COMPLETED,
    FAILED,
    QUEUED,
    RUNNING,
    build_generate_request,
    build_status_request,
    creative_registration,
    generate,
    next_poll_state,
    parse_generate_response,
    parse_status_response,
    poll_status,
    provider_configured,
    resolve_provider,
)
from app.services.ai_video_service import build_provider_payload

KEYS = {"HEYGEN": "hg-key", "SYNTHESIA": "sy-key", "RUNWAY": "rw-key"}


def _payload(provider):
    return build_provider_payload(provider, "Hello investors", {"voice_id": "v1", "avatar_id": "a1"},
                                  {"width": 1080, "height": 1920})["payload"]


# ─── request builders ────────────────────────────────────────────────────────

def test_heygen_generate_request_shape():
    req = build_generate_request("heygen", _payload("HEYGEN"), "hg-key")
    assert req["method"] == "POST" and req["url"] == "https://api.heygen.com/v2/video/generate"
    assert req["headers"]["X-Api-Key"] == "hg-key"
    assert req["json"]["video_inputs"][0]["voice"]["input_text"] == "Hello investors"
    st = build_status_request("HEYGEN", "vid-1", "hg-key")
    assert st["url"].endswith("/v1/video_status.get") and st["params"] == {"video_id": "vid-1"}


def test_synthesia_generate_request_shape():
    req = build_generate_request("synthesia", _payload("SYNTHESIA"), "sy-key")
    assert req["url"] == "https://api.synthesia.io/v2/videos"
    assert req["headers"]["Authorization"] == "sy-key"
    assert req["json"]["slides"][0]["avatarSettings"]["script"] == "Hello investors"
    assert build_status_request("SYNTHESIA", "abc", "sy-key")["url"].endswith("/v2/videos/abc")


def test_runway_generate_request_shape():
    req = build_generate_request("runway", _payload("RUNWAY"), "rw-key")
    assert req["url"] == "https://api.dev.runwayml.com/v1/image_to_video"
    assert req["headers"]["Authorization"] == "Bearer rw-key"
    assert req["headers"]["X-Runway-Version"] == "2024-11-06"
    assert req["json"]["promptText"] == "Hello investors"
    st = build_status_request("RUNWAY", "task-9", "rw-key")
    assert st["url"].endswith("/v1/tasks/task-9") and st["headers"]["X-Runway-Version"] == "2024-11-06"


# ─── parsers ─────────────────────────────────────────────────────────────────

def test_parse_generate_response_per_provider():
    assert parse_generate_response("HEYGEN", {"data": {"video_id": "h1"}})["provider_job_id"] == "h1"
    assert parse_generate_response("SYNTHESIA", {"id": "s1"})["provider_job_id"] == "s1"
    assert parse_generate_response("RUNWAY", {"id": "r1"})["provider_job_id"] == "r1"
    with pytest.raises(ValueError):
        parse_generate_response("HEYGEN", {"error": "bad key"})


def test_status_normalisation():
    assert parse_status_response("HEYGEN", {"data": {"status": "processing"}})["status"] == RUNNING
    done = parse_status_response("HEYGEN", {"data": {"status": "completed", "video_url": "https://h/v.mp4"}})
    assert done["status"] == COMPLETED and done["video_url"] == "https://h/v.mp4"
    assert parse_status_response("SYNTHESIA", {"status": "in_progress"})["status"] == RUNNING
    assert parse_status_response("SYNTHESIA", {"status": "complete", "download": "https://s/v.mp4"})["status"] == COMPLETED
    fail = parse_status_response("RUNWAY", {"status": "FAILED", "failure": "nsfw"})
    assert fail["status"] == FAILED and fail["error"] == "nsfw"
    assert parse_status_response("RUNWAY", {"status": "PENDING"})["status"] == QUEUED
    assert parse_status_response("RUNWAY", {"status": "SUCCEEDED", "output": ["https://r/v.mp4"]})["video_url"] == "https://r/v.mp4"


def test_completed_without_url_stays_running():
    assert parse_status_response("SYNTHESIA", {"status": "complete"})["status"] == RUNNING


# ─── provider resolution ─────────────────────────────────────────────────────

def test_resolve_provider_falls_back_to_mock_without_key():
    assert provider_configured("MOCK") is True
    assert provider_configured("HEYGEN", {}) is False
    assert resolve_provider("heygen", {}) == "MOCK"
    assert resolve_provider("heygen", KEYS) == "HEYGEN"
    with pytest.raises(ValueError):
        resolve_provider("nope", KEYS)


# ─── mock transport flow ─────────────────────────────────────────────────────

def _transport(calls):
    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        if request.url.path == "/v2/video/generate":
            assert request.headers["x-api-key"] == "hg-key"
            return httpx.Response(200, json={"data": {"video_id": "hg-42"}})
        if request.url.path == "/v1/video_status.get":
            n = sum(1 for c in calls if c.url.path == "/v1/video_status.get")
            if n < 2:
                return httpx.Response(200, json={"data": {"status": "processing"}})
            return httpx.Response(200, json={"data": {"status": "completed", "video_url": "https://h/final.mp4"}})
        return httpx.Response(404)
    return httpx.MockTransport(handler)


def test_generate_and_poll_with_mock_transport():
    calls = []
    client = httpx.Client(transport=_transport(calls))
    res = generate("HEYGEN", "Hi", {"voice_id": "v"}, {}, api_key="hg-key", client=client, job_id="job-1")
    assert res["status"] == QUEUED and res["provider_job_id"] == "hg-42" and res["requires_ai_label"] is True

    first = poll_status("HEYGEN", "job-1", "hg-42", api_key="hg-key", client=client)
    assert first["status"] == RUNNING
    second = poll_status("HEYGEN", "job-1", "hg-42", api_key="hg-key", client=client)
    assert second["status"] == COMPLETED and second["video_url"] == "https://h/final.mp4"
    assert calls[0].url.path == "/v2/video/generate"
    assert json.loads(calls[0].content)["video_inputs"][0]["voice"]["input_text"] == "Hi"


def test_http_error_raises():
    client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(401, text="bad key")))
    with pytest.raises(RuntimeError):
        generate("SYNTHESIA", "Hi", {}, {}, api_key="sy-key", client=client)


def test_missing_key_raises_for_real_provider():
    with pytest.raises(ValueError):
        generate("RUNWAY", "Hi", {}, {}, api_key="")


def test_mock_provider_completes_immediately():
    res = generate("MOCK", "Hi", {}, {}, job_id="j")
    assert res["status"] == COMPLETED and res["video_url"].endswith("/j.mp4")
    assert res["requires_ai_label"] is True and res["is_ai_generated"] is True


# ─── poll state machine + label enforcement ──────────────────────────────────

def test_next_poll_state_transitions():
    cur = {"status": QUEUED, "requires_ai_label": True}
    s1 = next_poll_state(cur, {"status": RUNNING}, 1, 5)
    assert s1["status"] == RUNNING and s1["done"] is False
    s2 = next_poll_state(s1, {"status": COMPLETED, "video_url": "u"}, 2, 5)
    assert s2["done"] is True and s2["video_url"] == "u"
    timeout = next_poll_state(s1, {"status": RUNNING}, 5, 5)
    assert timeout["status"] == FAILED and timeout["done"] is True and "timeout" in timeout["error"]
    assert timeout["requires_ai_label"] is True


def test_creative_registration_enforces_label():
    done = generate("MOCK", "Hi", {}, {}, job_id="k")
    reg = creative_registration(done, "tenant-1", "Founder clip")
    assert reg["is_ai_generated"] is True and reg["requires_ai_label"] is True
    assert reg["has_ai_disclosure"] is False and reg["ab_acceptance_required"] is True
    assert reg["file_url"] == done["video_url"]
    with pytest.raises(ValueError):
        creative_registration({"status": RUNNING}, "t", "x")
