#!/usr/bin/env python3
"""Fail if any Java -> FastAPI call path does not exist in the ML service route table.

Audit 2026-10-17 §1.4: several Java call sites targeted routes that were never defined
(/thompson/reward, /form/calibrate-value, ...). Failures were caught and logged as warn,
so the UI looked healthy. Run from repo root:  python scripts/check-ml-routes.py
"""
import glob
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ML = os.path.join(ROOT, "ml-service")
JAVA = os.path.join(ROOT, "platform-api", "src", "main", "java")


def ml_routes() -> set[str]:
    main = io.open(os.path.join(ML, "main.py"), encoding="utf-8").read()
    routes = set()
    for m in re.finditer(r'app\.include_router\((\w+)\.router(?:,\s*prefix="([^"]*)")?\)', main):
        mod, pre = m.group(1), m.group(2) or ""
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
        for m in re.finditer(r'getMlServiceUrl\(\)\s*\+\s*"(/[A-Za-z0-9_\-/{}.]+)', s):
            calls.add((m.group(1), os.path.basename(f)))
        for m in re.finditer(r'callMl\w*\(\s*"(/[^"]+)"', s):
            calls.add((m.group(1), os.path.basename(f)))
        for m in re.finditer(r'"(http://ml-service:\d+/[^"]*)"', s):
            calls.add((m.group(1), os.path.basename(f)))  # hardcoded host: always reported
    return calls


def norm(p: str) -> str:
    return re.sub(r"\{[^}]+\}", "{x}", p.rstrip("/"))


def main() -> int:
    routes = {norm(r) for r in ml_routes()}
    def known(p: str) -> bool:
        # "…/benchmark/" + tenantId  -> matches "/benchmark/{tenant_id}"
        return norm(p) in routes or (p.endswith("/") and norm(p) + "/{x}" in routes)

    missing = sorted((p, f) for p, f in java_calls() if not known(p))
    print(f"ML routes: {len(routes)}; missing Java call paths: {len(missing)}")
    for p, f in missing:
        print(f"  MISSING {p}  <- {f}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
