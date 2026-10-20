#!/usr/bin/env bash
# Runs every Flyway migration against a throwaway Postgres (pgvector/pgvector:pg16) in Docker.
# Catches "cannot migrate on a fresh database" regressions (audit 2026-10-17 §1.2).
# Usage: scripts/verify-migrations.sh    (needs Docker; takes ~1-3 min)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIG="$ROOT/platform-api/src/main/resources/db/migration"
NAME="mai-migration-check-$$"
PORT="${MIGRATION_CHECK_PORT:-5439}"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --name "$NAME" -e POSTGRES_USER=mai -e POSTGRES_PASSWORD=mai_local -e POSTGRES_DB=marketingai -p "$PORT:5432" pgvector/pgvector:pg16 >/dev/null
for _ in $(seq 1 30); do docker exec "$NAME" pg_isready -U mai -d marketingai >/dev/null 2>&1 && break; sleep 2; done
# Windows Git Bash mangles /flyway/sql unless path conversion is disabled.
case "$(uname -s)" in MINGW*|MSYS*) MIG="$(cygpath -m "$MIG")"; export MSYS_NO_PATHCONV=1;; esac
docker run --rm -v "$MIG:/flyway/sql" flyway/flyway:10 \
  -url="jdbc:postgresql://host.docker.internal:$PORT/marketingai" -user=mai -password=mai_local migrate
