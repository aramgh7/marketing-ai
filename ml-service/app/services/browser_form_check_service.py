"""Synthetic form check in a headless browser (roadmap 3-13; vision §11.4).

The HTTP check (form_check_service) only proves the endpoint answers. A real visitor needs the
page to load, the form to render, the fields to accept input and the submit to produce a
success state. Playwright/Chromium does exactly that; it is optional at build time
(Dockerfile ARG INSTALL_PLAYWRIGHT=1) and the router falls back to the HTTP check when it is
not installed.

Pure helpers (unit-tested):  default_fill_plan(), evaluate_outcome()
I/O:                         run_browser_check()  (Playwright sync API, run in a worker thread)
"""
from __future__ import annotations

import re
import time

SYNTHETIC_EMAIL = "healthcheck@synthetic.internal"
DEFAULT_SUCCESS_PATTERN = r"thank you|thanks|you'?re registered|we'?ll be in touch|success|confirmed|check your inbox"
_UNSAFE_URL = re.compile(r"^(?!https?://)", re.I)


def playwright_available() -> bool:
    try:
        import playwright.sync_api  # noqa: F401
        return True
    except Exception:
        return False


def default_fill_plan(fields: dict | None = None) -> list[dict]:
    """[{selector, value}] — synthetic values the receiving system can recognise and discard."""
    plan = [
        {"selector": "input[name='name'], input[name='first_name'], input[name='full_name']", "value": "__synthetic_test__"},
        {"selector": "input[type='email'], input[name='email']", "value": SYNTHETIC_EMAIL},
        {"selector": "input[type='tel'], input[name='phone']", "value": "+10000000000"},
    ]
    for selector, value in (fields or {}).items():
        plan.append({"selector": selector, "value": str(value)})
    return plan


def evaluate_outcome(final_url: str, body_text: str, post_statuses: list[int],
                     js_errors: list[str], success_pattern: str | None = None,
                     start_url: str | None = None) -> dict:
    """Decide PASS/FAIL from what the browser observed after submit.

    PASS when any of: success text on the page, navigation to a /thank|/success|/confirm URL,
    or a 2xx response to a POST made by the page — AND no uncaught JS error.
    """
    pattern = re.compile(success_pattern or DEFAULT_SUCCESS_PATTERN, re.I)
    text_hit = bool(pattern.search(body_text or ""))
    url_hit = bool(re.search(r"(thank|success|confirm|registered|done)", final_url or "", re.I)) \
        or (start_url is not None and final_url and final_url.split("#")[0] != start_url.split("#")[0]
            and bool(re.search(r"(thank|success|confirm)", final_url, re.I)))
    post_ok = any(200 <= s < 300 for s in post_statuses or [])
    post_failed = bool(post_statuses) and not post_ok
    reasons = []
    if js_errors:
        reasons.append(f"js_errors={len(js_errors)}")
    if post_failed:
        reasons.append(f"post_statuses={post_statuses}")
    ok = (text_hit or url_hit or post_ok) and not js_errors and not post_failed
    return {"ok": ok, "text_hit": text_hit, "url_hit": url_hit, "post_ok": post_ok,
            "post_statuses": post_statuses, "js_errors": js_errors[:5], "reasons": reasons}


def run_browser_check(page_url: str, form_selector: str = "form", submit_selector: str | None = None,
                      fields: dict | None = None, success_pattern: str | None = None,
                      timeout_seconds: float = 30.0) -> dict:
    """Load the landing, fill the form, submit, judge the outcome. Synchronous (call via to_thread)."""
    if _UNSAFE_URL.match(page_url or ""):
        return {"ok": False, "method": "browser", "error": "page_url must be http(s)"}
    if not playwright_available():
        return {"ok": None, "method": "unavailable", "error": "playwright not installed"}

    from playwright.sync_api import sync_playwright  # type: ignore

    start = time.monotonic()
    post_statuses: list[int] = []
    js_errors: list[str] = []
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(user_agent="MarketingAI-HealthCheck/1.0 (synthetic form check)")
            page = context.new_page()
            page.set_default_timeout(timeout_seconds * 1000)
            page.on("pageerror", lambda e: js_errors.append(str(e)))
            page.on("response", lambda r: post_statuses.append(r.status) if r.request.method == "POST" else None)
            page.goto(page_url, wait_until="networkidle")
            form = page.locator(form_selector).first
            form.wait_for(state="visible")
            for step in default_fill_plan(fields):
                loc = form.locator(step["selector"]).first
                if loc.count() > 0:
                    loc.fill(step["value"])
            submit = form.locator(submit_selector or "button[type='submit'], input[type='submit']").first
            submit.click()
            page.wait_for_timeout(3000)
            try:
                page.wait_for_load_state("networkidle", timeout=10_000)
            except Exception:
                pass
            final_url = page.url
            body_text = page.inner_text("body") if page.locator("body").count() else ""
            browser.close()
        verdict = evaluate_outcome(final_url, body_text, post_statuses, js_errors, success_pattern, page_url)
        verdict.update({"method": "browser", "latency_ms": round((time.monotonic() - start) * 1000, 1),
                        "final_url": final_url, "error": None if verdict["ok"] else "; ".join(verdict["reasons"]) or "no success signal"})
        return verdict
    except Exception as exc:
        return {"ok": False, "method": "browser", "latency_ms": round((time.monotonic() - start) * 1000, 1),
                "error": str(exc)[:300], "js_errors": js_errors[:5], "post_statuses": post_statuses}
