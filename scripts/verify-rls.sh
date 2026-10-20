#!/usr/bin/env bash
# Proves row-level security is enforced for the application role (audit 2026-10-17 §3.1/§3.3).
#
# Starts a throwaway Postgres, runs all migrations, then connects as mai_app and checks:
#   1. no scope            -> tenant tables return 0 rows
#   2. tenant scope        -> only that tenant's rows
#   3. system scope        -> all rows (explicit bypass used by schedulers)
#   4. tenant scope insert for another tenant -> rejected by policy
#   5. mai_app cannot UPDATE thompson_arms (single writer = ML service)
# Usage: scripts/verify-rls.sh    (needs Docker; ~1-3 min)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIG="$ROOT/platform-api/src/main/resources/db/migration"
NAME="mai-rls-check-$$"
PORT="${RLS_CHECK_PORT:-5440}"
APP_PASS="mai_app_local"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" -e POSTGRES_USER=mai -e POSTGRES_PASSWORD=mai_local -e POSTGRES_DB=marketingai \
  -p "$PORT:5432" pgvector/pgvector:pg16 >/dev/null
for _ in $(seq 1 30); do docker exec "$NAME" pg_isready -U mai -d marketingai >/dev/null 2>&1 && break; sleep 2; done

case "$(uname -s)" in MINGW*|MSYS*) MIG="$(cygpath -m "$MIG")"; export MSYS_NO_PATHCONV=1;; esac
docker run --rm -v "$MIG:/flyway/sql" flyway/flyway:10 \
  -url="jdbc:postgresql://host.docker.internal:$PORT/marketingai" -user=mai -password=mai_local \
  -placeholders.app_role_password="$APP_PASS" migrate | grep -E "Successfully|ERROR" || true

T1=11111111-1111-1111-1111-111111111111
T2=22222222-2222-2222-2222-222222222222
owner() { docker exec -i "$NAME" psql -U mai -d marketingai -v ON_ERROR_STOP=1 -qtA "$@"; }
app()   { docker exec -i -e PGPASSWORD="$APP_PASS" "$NAME" psql -U mai_app -d marketingai -qtA "$@"; }

owner <<SQL
INSERT INTO tenants (id, name, slug) VALUES ('$T1','T1','t1'), ('$T2','T2','t2');
INSERT INTO sessions (id, tenant_id) VALUES ('s1','$T1'), ('s2','$T2');
SQL

fail=0
check() { # label expected actual
  if [ "$2" = "$3" ]; then echo "PASS  $1 -> $3"; else echo "FAIL  $1 -> got '$3', expected '$2'"; fail=1; fi
}

check "no scope: sessions visible"      "0" "$(app -c "SELECT count(*) FROM sessions")"
check "no scope: tenants visible"       "0" "$(app -c "SELECT count(*) FROM tenants")"
check "tenant scope: own sessions only" "s1" "$(app -c "SELECT set_config('app.current_tenant','$T1',false); SELECT string_agg(id, ',') FROM sessions" | tail -1)"
check "tenant scope: own tenant only"   "1" "$(app -c "SELECT set_config('app.current_tenant','$T1',false); SELECT count(*) FROM tenants" | tail -1)"
check "system scope: all sessions"      "2" "$(app -c "SELECT set_config('app.rls_bypass','on',false); SELECT count(*) FROM sessions" | tail -1)"
cross=$(app -c "SELECT set_config('app.current_tenant','$T1',false); INSERT INTO sessions (id, tenant_id) VALUES ('s3','$T2')" 2>&1 | grep -c "row-level security" || true)
check "tenant scope: cross-tenant insert rejected" "1" "$cross"
arm=$(app -c "SELECT set_config('app.rls_bypass','on',false); UPDATE thompson_arms SET alpha = alpha + 1" 2>&1 | grep -c "permission denied" || true)
check "mai_app cannot write thompson_arms" "1" "$arm"

exit $fail
