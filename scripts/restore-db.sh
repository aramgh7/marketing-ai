#!/usr/bin/env bash
# Restore a scripts/backup-db.sh dump (audit 2026-10-22, X-3) and verify it.
#
#   scripts/restore-db.sh backups/marketingai-20261022-030000.dump
#       -> restores into a scratch database `marketingai_restore_test` inside the running mai-postgres container,
#          prints Flyway version / row counts, drops the scratch DB. This is the MONTHLY RESTORE DRILL: run it,
#          read the numbers, note the date in the ops log. --keep leaves the scratch DB for inspection.
#   scripts/restore-db.sh FILE --live
#       -> stops platform-api / ml-service / frontend / db-backup, drops and recreates `marketingai`, restores,
#          starts them again. Destructive: asks for the literal word "restore" unless RESTORE_CONFIRM=yes.
# If a matching .globals.sql sits next to the dump, roles mai_app / mai_ml (with password hashes) are created
# when missing — needed when restoring onto a brand-new server.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PG_CONTAINER="${PG_CONTAINER:-mai-postgres}"
PGUSER="${PGUSER:-mai}"
LIVE_DB="${PGDATABASE:-marketingai}"
TEST_DB="${RESTORE_TEST_DB:-marketingai_restore_test}"
MODE=test; KEEP=0; FILE=""
for a in "$@"; do
  case "$a" in
    --live) MODE=live ;;
    --keep) KEEP=1 ;;
    -h|--help) sed -n 2,13p "$0"; exit 0 ;;
    *) FILE="$a" ;;
  esac
done
if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then echo "usage: $0 <dump-file> [--live] [--keep]"; exit 1; fi
GLOBALS="${FILE%.dump}.globals.sql"
log() { echo "[restore-db $(date -u +%FT%TZ)] $*"; }
psql_owner() { docker exec -i "$PG_CONTAINER" psql -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 -qtA "$@"; }

docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" >/dev/null 2>&1 || { log "container $PG_CONTAINER not running"; exit 2; }
docker exec -i "$PG_CONTAINER" pg_restore --list < "$FILE" | grep -q flyway_schema_history \
  || { log "not a platform dump (no flyway_schema_history)"; exit 3; }

if [ -f "$GLOBALS" ]; then
  # pg_dumpall --globals-only emits CREATE ROLE + ALTER ROLE ... PASSWORD; apply only for the two app roles
  log "ensuring roles from $(basename "$GLOBALS")"
  grep -E '^(CREATE|ALTER) ROLE (mai_app|mai_ml)\b' "$GLOBALS" | while read -r stmt; do
    role=$(echo "$stmt" | sed -E 's/^(CREATE|ALTER) ROLE ([a-z_]+).*/\2/')
    case "$stmt" in
      CREATE*) psql_owner -c "SELECT 1 FROM pg_roles WHERE rolname='$role'" | grep -q 1 || psql_owner -c "$stmt" ;;
      *)       psql_owner -c "$stmt" ;;
    esac
  done
fi

if [ "$MODE" = live ]; then
  if [ "${RESTORE_CONFIRM:-}" != yes ]; then
    printf "This DROPS database '%s' in %s and restores %s. Type 'restore' to continue: " "$LIVE_DB" "$PG_CONTAINER" "$FILE"
    read -r ans
    [ "$ans" = restore ] || { log "aborted"; exit 1; }
  fi
  TARGET="$LIVE_DB"
  log "stopping application services"
  (cd "$ROOT" && docker compose stop platform-api ml-service frontend db-backup >/dev/null 2>&1 || true)
  psql_owner -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$TARGET' AND pid<>pg_backend_pid()" >/dev/null
else
  TARGET="$TEST_DB"
fi

log "recreating database $TARGET"
psql_owner -c "DROP DATABASE IF EXISTS $TARGET"
psql_owner -c "CREATE DATABASE $TARGET OWNER $PGUSER"
log "restoring $(basename "$FILE") -> $TARGET"
docker exec -i "$PG_CONTAINER" pg_restore -U "$PGUSER" -d "$TARGET" --no-password --exit-on-error < "$FILE"

q() { docker exec -i "$PG_CONTAINER" psql -U "$PGUSER" -d "$TARGET" -v ON_ERROR_STOP=1 -qtA -c "$1" 2>/dev/null || echo "n/a"; }
log "verify: flyway version  = $(q "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
log "verify: tables          = $(q "SELECT count(*) FROM pg_tables WHERE schemaname='public'")"
log "verify: tenants / users = $(q "SELECT count(*) FROM tenants") / $(q "SELECT count(*) FROM users")"
log "verify: sessions/events = $(q "SELECT count(*) FROM sessions") / $(q "SELECT count(*) FROM events")"
log "verify: RLS policies    = $(q "SELECT count(*) FROM pg_policies")"
log "verify: vector ext      = $(q "SELECT extversion FROM pg_extension WHERE extname='vector'")"

if [ "$MODE" = live ]; then
  log "starting application services"
  (cd "$ROOT" && docker compose start platform-api ml-service frontend db-backup >/dev/null 2>&1 || true)
  log "live restore complete — check http://127.0.0.1:8080/api/v1/actuator/health"
elif [ "$KEEP" = 0 ]; then
  psql_owner -c "DROP DATABASE $TARGET"
  log "restore drill passed; scratch database dropped (use --keep to inspect it)"
else
  log "scratch database $TARGET kept"
fi
