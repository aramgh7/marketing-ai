from app.services.archetype_service import classify_archetype, archetype_follow_up_strategy


def _classify(**kwargs):
    defaults = dict(
        ticket_bucket=None, self_accredited=None, time_to_form_h=None,
        material_views=0, dataroom_opens=0, call_outcome=None,
        objection_tags=[], noshow_risk_tier=None, touch_count=0,
    )
    defaults.update(kwargs)
    return classify_archetype(**defaults)


def test_committed_quick():
    result = _classify(call_outcome="COMMITTED", ticket_bucket="GT250K", self_accredited=True)
    assert result["archetype"] == "COMMITTED_QUICK"
    assert result["confidence"] >= 0.8


def test_committed_quick_fast_form():
    result = _classify(call_outcome="COMMITTED", time_to_form_h=1.5)
    assert result["archetype"] == "COMMITTED_QUICK"


def test_researcher():
    result = _classify(material_views=4, dataroom_opens=1, time_to_form_h=48.0)
    assert result["archetype"] == "RESEARCHER"
    assert "DATAROOM_OPENED" in result["signals"]


def test_fence_sitter():
    result = _classify(
        objection_tags=["TIMING_NOT_RIGHT", "PRICE_CONCERN"],
        touch_count=4,
        call_outcome="INTERESTED_FOLLOW",
    )
    assert result["archetype"] == "FENCE_SITTER"


def test_high_value_quiet():
    result = _classify(ticket_bucket="GT250K", material_views=0, touch_count=1)
    assert result["archetype"] == "HIGH_VALUE_QUIET"
    assert result["confidence"] >= 0.6


def test_disqualified_not_accredited():
    result = _classify(self_accredited=False)
    assert result["archetype"] == "DISQUALIFIED"
    assert "NOT_ACCREDITED" in result["signals"]


def test_disqualified_low_ticket():
    result = _classify(self_accredited=False, ticket_bucket="LT25K")
    assert result["archetype"] == "DISQUALIFIED"
    assert result["confidence"] >= 0.9


def test_unknown():
    result = _classify()  # all defaults
    assert result["archetype"] == "UNKNOWN"


def test_all_archetypes_have_strategy():
    archetypes = [
        "COMMITTED_QUICK", "RESEARCHER", "FENCE_SITTER",
        "HIGH_VALUE_QUIET", "DISQUALIFIED", "UNKNOWN",
    ]
    for archetype in archetypes:
        strategy = archetype_follow_up_strategy(archetype)
        assert "ghl_tag" in strategy
        assert "priority" in strategy
        assert "action" in strategy


def test_follow_up_committed_quick_urgent():
    strategy = archetype_follow_up_strategy("COMMITTED_QUICK")
    assert strategy["priority"] == "URGENT"
    assert strategy["sla_hours"] == 2
