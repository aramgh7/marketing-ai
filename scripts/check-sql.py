#!/usr/bin/env python3
"""Validate every SQL statement embedded in the Java sources against the migrated schema.

Audit 2026-10-17 §5: ~17 scheduled jobs and ~20 endpoints referenced columns or tables that do
not exist. Each query raised BadSqlGrammarException at runtime, was caught, logged — and the job
silently did nothing. This tool makes that class of bug mechanical:

  1. extracts string literals / text blocks that start with SELECT/INSERT/UPDATE/DELETE/WITH
     from platform-api/src/main/java (merging "a" + "b" concatenations),
  2. replaces JDBC `?` placeholders with $1..$n,
  3. runs `PREPARE _chk AS <sql>` against a Postgres that has all Flyway migrations applied,
  4. reports every "does not exist" / "operator does not exist" / syntax error with file:line.

Dynamic SQL (string built from variables) is reported as SKIPPED, parameters whose type cannot
be inferred are reported as UNKNOWN (not failures).

Usage:
  python scripts/check-sql.py                      # starts a throwaway pgvector container
  python scripts/check-sql.py --container NAME     # reuse a running migrated container
  python scripts/check-sql.py --only scheduler     # restrict to a source sub-directory
Exit code 1 when any statement fails.
"""
import argparse
import glob
import io
import os
import re
import subprocess
import sys
import time
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "platform-api", "src", "main", "java")
MIG = os.path.join(ROOT, "platform-api", "src", "main", "resources", "db", "migration")
SQL_START = re.compile(r"^\s*(SELECT|INSERT|UPDATE|DELETE|WITH)\b", re.I)

# Java string literal | text block, plus the + operator and whitespace/comments between them
TOKEN = re.compile(
    r'"""\n(?P<block>.*?)"""'          # text block
    r'|"(?P<lit>(?:[^"\\\n]|\\.)*)"'  # ordinary literal
    r'|(?P<plus>\+)'
    r'|(?P<ws>\s+|//[^\n]*|/\*.*?\*/)'
    r'|(?P<other>.)',
    re.S,
)


def _unescape(s: str) -> str:
    return s.encode("utf-8").decode("unicode_escape")


def extract_sql(src: str):
    """Yield (line_no, sql, dynamic) for each SQL-looking string expression."""
    pos = 0
    parts, start_line, dynamic, pending_plus = [], None, False, False
    def flush():
        nonlocal parts, start_line, dynamic, pending_plus
        if parts:
            sql = "".join(parts)
            if SQL_START.match(sql):
                yield_item = (start_line, sql, dynamic)
                parts, start_line, dynamic, pending_plus = [], None, False, False
                return yield_item
        parts, start_line, dynamic, pending_plus = [], None, False, False
        return None

    for m in TOKEN.finditer(src):
        kind = m.lastgroup
        if kind in ("block", "lit"):
            text = m.group(kind) if kind == "block" else _unescape(m.group("lit"))
            if parts and not pending_plus:
                item = flush()
                if item: yield item
            if not parts:
                start_line = src.count("\n", 0, m.start()) + 1
            parts.append(text)
            pending_plus = False
        elif kind == "plus":
            if parts: pending_plus = True
        elif kind == "ws":
            continue
        else:
            if parts and pending_plus:
                # "sql" + variable + "sql" -> dynamic
                dynamic = True
                # swallow the identifier/expression until the next literal or terminator
                continue
            if parts:
                item = flush()
                if item: yield item
    item = flush()
    if item: yield item


def jdbc_to_pg(sql: str) -> str:
    n = 0
    out = []
    in_str = False
    for ch in sql:
        if ch == "'":
            in_str = not in_str
        if ch == "?" and not in_str:
            n += 1
            out.append(f"${n}")
        else:
            out.append(ch)
    return "".join(out)


def psql(container: str, sql: str) -> tuple[int, str]:
    r = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "mai", "-d", "marketingai", "-v", "ON_ERROR_STOP=1", "-qAt"],
        input=sql.encode("utf-8"), capture_output=True)
    return r.returncode, (r.stderr or b"").decode("utf-8", "replace").strip()


def start_container(port: int) -> str:
    name = f"mai-sql-check-{uuid.uuid4().hex[:6]}"
    subprocess.run(["docker", "run", "-d", "--name", name, "-e", "POSTGRES_USER=mai", "-e", "POSTGRES_PASSWORD=mai_local",
                    "-e", "POSTGRES_DB=marketingai", "-p", f"{port}:5432", "pgvector/pgvector:pg16"], check=True, capture_output=True)
    for _ in range(40):
        if subprocess.run(["docker", "exec", name, "pg_isready", "-U", "mai", "-d", "marketingai"], capture_output=True).returncode == 0:
            break
        time.sleep(2)
    mig = MIG.replace("\\", "/")
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    r = subprocess.run(["docker", "run", "--rm", "-v", f"{mig}:/flyway/sql", "flyway/flyway:10",
                        f"-url=jdbc:postgresql://host.docker.internal:{port}/marketingai", "-user=mai", "-password=mai_local",
                        "-placeholders.app_role_password=mai_app_local", "migrate"], capture_output=True, env=env, text=True)
    if "Successfully applied" not in r.stdout and "up to date" not in r.stdout:
        print(r.stdout[-2000:], r.stderr[-2000:])
        raise SystemExit("migration failed")
    return name


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--container")
    ap.add_argument("--only", default="")
    ap.add_argument("--port", type=int, default=5441)
    args = ap.parse_args()

    own = args.container is None
    container = args.container or start_container(args.port)
    failures, skipped, unknown, ok, jpql = [], 0, 0, 0, 0
    try:
        files = sorted(glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True))
        for f in files:
            rel = os.path.relpath(f, ROOT).replace("\\", "/")
            if args.only and f"/{args.only}/" not in rel:
                continue
            if "GoogleAds" in rel:
                continue   # GAQL, not SQL
            src = io.open(f, encoding="utf-8").read()
            for line, sql, dynamic in extract_sql(src):
                if dynamic or len(sql.strip()) < 15:   # "DELETE" etc. in non-SQL string lists
                    skipped += 1
                    continue
                if re.search(r"(?<!:):[A-Za-z_]", sql) or "/repository/" in rel:
                    jpql += 1          # Spring Data @Query (JPQL, :named params) — not SQL
                    continue
                stmt = jdbc_to_pg(sql.strip().rstrip(";"))
                code, err = psql(container, f"BEGIN; PREPARE _chk AS {stmt}; ROLLBACK;")
                if code == 0:
                    ok += 1
                    continue
                first = err.splitlines()[0] if err else "error"
                if "could not determine data type of parameter" in err or "indeterminate" in err:
                    unknown += 1
                    continue
                failures.append((rel, line, first.replace("ERROR:  ", "")))
    finally:
        if own:
            subprocess.run(["docker", "rm", "-f", container], capture_output=True)

    for rel, line, msg in failures:
        print(f"FAIL {rel}:{line}  {msg}")
    print(f"\nchecked: {ok + len(failures) + unknown}  ok: {ok}  failed: {len(failures)}  "
          f"param-type-unknown: {unknown}  dynamic-skipped: {skipped}  jpql-skipped: {jpql}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
