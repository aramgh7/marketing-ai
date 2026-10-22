#!/usr/bin/env python3
"""Fail if any Java -> FastAPI call path does not exist in the ML service route table.

Audit 2026-10-17 §1.4: several Java call sites targeted routes that were never defined
(/thompson/reward, /form/calibrate-value, ...). Failures were caught and logged as warn,
so the UI looked healthy. Run from repo root:  python scripts/check-ml-routes.py

Audit 2026-10-22 X-16: call patterns now cover every form used in platform-api —
  appProperties.getMlServiceUrl() + "/path"      mlUrl + "/path"
  mlClient.post("/path", ...) / mlClient.get("/path?...")      (MlServiceClient)
  mlPost("/path") / mlGet("/path/" + id)                       (VideoController)
  callFastApi("/path") / callFastApiGet("/path/" + id)         (AiVideo/GeoLift controllers)
  callMl*("/path")
and the reverse check lists ML routes that no Java code calls (WARN, never a failure —
some are called by the frontend/runner or are internal to the ML service).
Exit code 1 only for Java call paths that do not exist in the ML service.
"""
import glob
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ML = os.path.join(ROOT, "ml-service")
JAVA = os.path.join(ROOT, "platform-api", "src", "main", "java")

# ML routes that are legitimately not called from Java (infrastructure / called by other clients)
REVERSE_IGNORE = {"/health"}

CALL_PATTERNS = [
    # base-url concatenation: appProperties.getMlServiceUrl() + "/path", mlUrl + "/path", url + "/path"
    re.compile(r'(?:getMlServiceUrl\(\)|\bmlUrl|\bmlBase(?:Url)?|\bmlServiceUrl)\s*\+\s*"(/[^"]*)"'),
    # helper / client methods with a literal first argument
    re.compile(r'\b(?:callMl\w*|callFastApi\w*|mlPost|mlGet|mlClient\.(?:post|get)|mlServiceClient\.(?:post|get))\(\s*"(/[^"]*)"'),
]


def ml_routes() -> set[str]:
    main = io.open(os.path.join(ML, "main.py"), encoding="utf-8").read()
    routes = set()
    for m in re.finditer(r'app\.include_router\((\w+)\.router([^)]*)\)', main):
        mod = m.group(1)
        pm = re.search(r'prefix="([^"]*)"', m.group(2))
        pre = pm.group(1) if pm else ""
        src = io.open(os.path.join(ML, "app", "routers", f"{mod}.py"), encoding="utf-8").read()
        rp = re.search(r'router\s*=\s*APIRouter\((?:[^)]*prefix="([^"]*)")?', src)
        rpre = rp.group(1) if rp and rp.group(1) else ""
        for r in re.finditer(r'@router\.(?:post|get|put|delete)\(\s*"([^"]*)"', src):
            routes.add(pre + rpre + r.group(1))
    return routes


def java_calls() -> set[tuple[str, str]]:
    calls = set()
    for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
        s = io.open(f, encoding="utf-8").read()
        name = os.path.basename(f)
        for pat in CALL_PATTERNS:
            for m in pat.finditer(s):
                calls.add((m.group(1).split("?", 1)[0], name))   # drop query string
        for m in re.finditer(r'"(http://ml-service:\d+/[^"]*)"', s):
            calls.add((m.group(1), name))  # hardcoded host: always reported
    return calls


def norm(p: str) -> str:
    return re.sub(r"\{[^}]+\}", "{x}", p.rstrip("/"))


def main() -> int:
    routes = {norm(r) for r in ml_routes()}
    calls = java_calls()

    def segs_match(c: list[str], r: list[str]) -> bool:
        return len(c) == len(r) and all(rs == "{x}" or rs == cs for cs, rs in zip(c, r))

    def matches(call: str, route: str) -> bool:
        # segment-wise: "/explain/campaign" matches "/explain/{x}"; a call ending in "/" is either the route
        # itself ("/simulate/" -> POST /simulate/) or a prefix for a dynamic id ("/benchmark/" + id -> "/benchmark/{x}")
        c, r = norm(call).split("/"), route.split("/")
        return segs_match(c, r) or (call.endswith("/") and segs_match(c + ["{x}"], r))

    def known(p: str) -> bool:
        return any(matches(p, r) for r in routes)

    missing = sorted((p, f) for p, f in calls if not known(p))
    print(f"ML routes: {len(routes)}; Java call sites: {len(calls)}; missing Java call paths: {len(missing)}")
    for p, f in missing:
        print(f"  MISSING {p}  <- {f}")

    # reverse check: ML routes nobody in platform-api calls (WARN only)
    uncalled = sorted(r for r in routes
                      if r not in REVERSE_IGNORE and not any(matches(p, r) for p, _ in calls))
    print(f"WARN: {len(uncalled)} of {len(routes)} ML routes have no Java caller "
          f"(frontend/runner-called or dormant - see audit 2026-10-22 section 8.3):")
    for r in uncalled:
        print(f"  UNCALLED {r}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
