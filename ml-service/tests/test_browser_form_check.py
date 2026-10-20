"""Roadmap 3-13: headless-browser form check — pure decision logic (no browser needed)."""
from app.services.browser_form_check_service import (
    SYNTHETIC_EMAIL,
    default_fill_plan,
    evaluate_outcome,
    run_browser_check,
)


def test_fill_plan_defaults_and_extra_fields():
    plan = default_fill_plan({"select[name='ticket_bucket']": "LT25K"})
    assert any(p["value"] == SYNTHETIC_EMAIL for p in plan)
    assert plan[-1] == {"selector": "select[name='ticket_bucket']", "value": "LT25K"}


def test_success_text_passes():
    v = evaluate_outcome("https://x.com/l/a", "Thank you! We'll be in touch.", [], [])
    assert v["ok"] is True and v["text_hit"] is True


def test_navigation_to_thank_you_passes():
    v = evaluate_outcome("https://x.com/thank-you", "", [], [], start_url="https://x.com/l/a")
    assert v["ok"] is True and v["url_hit"] is True


def test_2xx_post_passes_without_text():
    v = evaluate_outcome("https://x.com/l/a", "", [204], [])
    assert v["ok"] is True and v["post_ok"] is True


def test_js_error_fails_even_with_success_text():
    v = evaluate_outcome("https://x.com/l/a", "Thanks!", [200], ["TypeError: x is undefined"])
    assert v["ok"] is False and "js_errors=1" in v["reasons"]


def test_failed_post_fails():
    v = evaluate_outcome("https://x.com/l/a", "Thanks!", [500], [])
    assert v["ok"] is False and "post_statuses=[500]" in v["reasons"]


def test_no_signal_fails():
    v = evaluate_outcome("https://x.com/l/a", "Welcome to our landing page", [], [])
    assert v["ok"] is False


def test_custom_success_pattern():
    v = evaluate_outcome("https://x.com/l/a", "Заявка принята", [], [], success_pattern="заявка принята")
    assert v["ok"] is True


def test_run_browser_check_rejects_non_http_and_reports_unavailable():
    assert run_browser_check("javascript:alert(1)")["ok"] is False
    res = run_browser_check("https://example.com/l/a")
    # on hosts without playwright the service must say so instead of pretending to pass
    assert res["method"] in ("unavailable", "browser")
    if res["method"] == "unavailable":
        assert res["ok"] is None
