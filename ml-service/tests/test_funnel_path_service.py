from app.services.funnel_path_service import (
    select_path,
    check_path_b_engagement_gate,
    resolve_tiered_path_variant,
    compute_path_metrics,
)

PATHS = [
    {"path_id": "aaa", "path_key": "CALL_4D",           "status": "ACTIVE", "is_control": True,  "alpha": 5.0, "beta": 2.0},
    {"path_id": "bbb", "path_key": "MATERIALS_FIRST",   "status": "ACTIVE", "is_control": False, "alpha": 1.0, "beta": 1.0},
    {"path_id": "ccc", "path_key": "TIERED_BY_TICKET",  "status": "ACTIVE", "is_control": False, "alpha": 1.0, "beta": 1.0},
    {"path_id": "ddd", "path_key": "CALL_24H",          "status": "DRAFT",  "is_control": False, "alpha": 1.0, "beta": 1.0},
]


def test_select_path_thompson():
    """3 ACTIVE paths → selects one of them (not DRAFT)."""
    results = {select_path(PATHS)["path_key"] for _ in range(30)}
    assert "CALL_24H" not in results   # DRAFT excluded
    assert len(results) >= 1


def test_select_path_tiered_with_bucket():
    """TIERED path selected when ticket_bucket is known."""
    result = select_path(PATHS, ticket_bucket="GT250K")
    assert result["path_key"] == "TIERED_BY_TICKET"


def test_select_path_no_active():
    """No active paths → None."""
    paths = [{"path_id": "x", "path_key": "CALL_4D", "status": "DRAFT", "is_control": True}]
    assert select_path(paths) is None


def test_path_b_gate_pass():
    """MATERIAL_VIEW + DATAROOM_OPEN → gate_passed=True."""
    events = [{"event_type": "MATERIAL_VIEW"}, {"event_type": "DATAROOM_OPEN"}]
    result = check_path_b_engagement_gate(events)
    assert result["gate_passed"] is True
    assert result["missing"] == []


def test_path_b_gate_pass_video():
    """MATERIAL_VIEW + VIDEO_PROGRESS >= 75 → gate_passed=True."""
    events = [
        {"event_type": "MATERIAL_VIEW"},
        {"event_type": "VIDEO_PROGRESS", "payload": {"progress_pct": 80}},
    ]
    result = check_path_b_engagement_gate(events)
    assert result["gate_passed"] is True


def test_path_b_gate_fail_no_material():
    """Missing MATERIAL_VIEW → gate_passed=False."""
    events = [{"event_type": "DATAROOM_OPEN"}]
    result = check_path_b_engagement_gate(events)
    assert result["gate_passed"] is False
    assert "MATERIAL_VIEW" in result["missing"]


def test_path_b_gate_fail_no_engagement():
    """MATERIAL_VIEW but no DATAROOM_OPEN or video → gate_passed=False."""
    events = [{"event_type": "MATERIAL_VIEW"}]
    result = check_path_b_engagement_gate(events)
    assert result["gate_passed"] is False


def test_resolve_tiered_variant_founder():
    config = {"GTE250K": {"ghl_tag": "founder", "variant_key": "1on1_founder"}}
    result = resolve_tiered_path_variant("GT250K", config)
    assert result["variant_key"] == "1on1_founder"


def test_resolve_tiered_variant_webinar():
    config = {"LT50K": {"ghl_tag": "webinar", "variant_key": "webinar"}}
    result = resolve_tiered_path_variant("LT25K", config)
    assert result["variant_key"] == "webinar"


def test_compute_path_metrics():
    """2 paths, known page_views → correct held_per_1k_lpv."""
    reward_events = [
        {"path_id": "aaa", "is_quality_stage": True},
        {"path_id": "aaa", "is_quality_stage": True},
        {"path_id": "bbb", "is_quality_stage": True},
    ]
    page_views = {"aaa": 1000, "bbb": 500}
    results = compute_path_metrics(reward_events, page_views)
    aaa = next(r for r in results if r["path_id"] == "aaa")
    bbb = next(r for r in results if r["path_id"] == "bbb")
    assert aaa["held_per_1k_lpv"] == 2.0
    assert bbb["held_per_1k_lpv"] == 2.0
    # sorted descending by held_per_1k_lpv
    assert results[0]["held_per_1k_lpv"] >= results[-1]["held_per_1k_lpv"]
