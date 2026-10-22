#!/usr/bin/env bash
# Postgres backup (audit 2026-10-22, X-3): pg_dump custom format + role globals, local rotation, offsite copy to R2.
#
# Runs in two modes, same file:
#   host:      scripts/backup-db.sh                    -> `docker exec mai-postgres pg_dump` (local socket, no password)
#   container: PGHOST=postgres scripts/backup-db.sh    (the compose `db-backup` service calls it every 24 h)
#
# Output:  $BACKUP_DIR/marketingai-YYYYmmdd-HHMMSS.dump         (pg_dump -Fc, restore with scripts/restore-db.sh)
#          $BACKUP_DIR/marketingai-YYYYmmdd-HHMMSS.globals.sql   (roles mai_app / mai_ml with password hashes)
# Local rotation: keep the newest $BACKUP_KEEP (14) pairs.
# Offsite: if rclone is installed and RCLONE_REMOTE is set (e.g. "r2:marketing-ai-backups/postgres") -> rclone copy;
#          if RCLONE_REMOTE is empty but R2_ACCOUNT_ID + BACKUP_R2_BUCKET + access keys are set, rclone is configured
#          from those env vars (no rclone.conf needed); without rclone, falls back to `aws s3 cp --endpoint-url`.
#          Remote copies older than $BACKUP_REMOTE_RETENTION_DAYS (30) are pruned (rclone only; for the aws fallback
#          set a lifecycle rule on the bucket).
# Exit code != 0 means the backup did NOT complete; the loop/cron should alert on it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
BACKUP_KEEP="${BACKUP_KEEP:-14}"
BACKUP_REMOTE_RETENTION_DAYS="${BACKUP_REMOTE_RETENTION_DAYS:-30}"
PG_CONTAINER="${PG_CONTAINER:-mai-postgres}"
PGDATABASE="${PGDATABASE:-marketingai}"
PGUSER="${PGUSER:-mai}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
BASE="$BACKUP_DIR/${PGDATABASE}-$STAMP"
DUMP="$BASE.dump"
GLOBALS="$BASE.globals.sql"

log() { echo "[backup-db $(date -u +%FT%TZ)] $*"; }
mkdir -p "$BACKUP_DIR"

if [ -n "${PGHOST:-}" ]; then
  # inside the db-backup container (or a host with a reachable Postgres): PGHOST/PGUSER/PGPASSWORD/PGDATABASE from env
  dump()    { pg_dump -Fc --no-password -d "$PGDATABASE" -U "$PGUSER" -h "$PGHOST" -p "${PGPORT:-5432}"; }
  globals() { pg_dumpall --globals-only --no-password -U "$PGUSER" -h "$PGHOST" -p "${PGPORT:-5432}"; }
  listing() { pg_restore --list "$DUMP"; }
else
  docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" >/dev/null 2>&1 || { log "container $PG_CONTAINER not running"; exit 2; }
  dump()    { docker exec "$PG_CONTAINER" pg_dump -Fc -U "$PGUSER" -d "$PGDATABASE"; }
  globals() { docker exec "$PG_CONTAINER" pg_dumpall --globals-only -U "$PGUSER"; }
  listing() { docker exec -i "$PG_CONTAINER" pg_restore --list < "$DUMP"; }
fi

log "dumping $PGDATABASE -> $DUMP"
dump > "$DUMP.part"
mv "$DUMP.part" "$DUMP"
globals > "$GLOBALS.part"
mv "$GLOBALS.part" "$GLOBALS"

# sanity: the archive must be readable and contain the Flyway history table
if ! listing | grep -q flyway_schema_history; then
  log "archive is unreadable or has no flyway_schema_history — removing it"
  rm -f "$DUMP" "$GLOBALS"
  exit 3
fi
SIZE=$(du -h "$DUMP" | cut -f1)
log "ok: $DUMP ($SIZE)"

# local rotation: keep the newest $BACKUP_KEEP dumps (and their globals)
ls -1t "$BACKUP_DIR"/"${PGDATABASE}"-*.dump 2>/dev/null | tail -n +"$((BACKUP_KEEP + 1))" | while read -r old; do
  log "rotating out $(basename "$old")"
  rm -f "$old" "${old%.dump}.globals.sql"
done

# offsite copy
if [ -z "${RCLONE_REMOTE:-}" ] && [ -n "${R2_ACCOUNT_ID:-}" ] && [ -n "${BACKUP_R2_BUCKET:-}" ] && command -v rclone >/dev/null; then
  export RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare RCLONE_CONFIG_R2_REGION=auto
  export RCLONE_CONFIG_R2_ENDPOINT="https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com"
  export RCLONE_CONFIG_R2_ACCESS_KEY_ID="${BACKUP_R2_ACCESS_KEY_ID:-${R2_ACCESS_KEY_ID:-}}"
  export RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="${BACKUP_R2_SECRET_ACCESS_KEY:-${R2_SECRET_ACCESS_KEY:-}}"
  RCLONE_REMOTE="r2:${BACKUP_R2_BUCKET}/postgres"
fi

if [ -n "${RCLONE_REMOTE:-}" ] && command -v rclone >/dev/null; then
  log "uploading to $RCLONE_REMOTE"
  rclone copy --s3-no-check-bucket "$DUMP" "$RCLONE_REMOTE/"
  rclone copy --s3-no-check-bucket "$GLOBALS" "$RCLONE_REMOTE/"
  rclone delete --min-age "${BACKUP_REMOTE_RETENTION_DAYS}d" "$RCLONE_REMOTE/" || log "WARN: remote prune failed"
  log "offsite ok"
elif [ -n "${R2_ACCOUNT_ID:-}" ] && [ -n "${BACKUP_R2_BUCKET:-}" ] && command -v aws >/dev/null; then
  export AWS_ACCESS_KEY_ID="${BACKUP_R2_ACCESS_KEY_ID:-${R2_ACCESS_KEY_ID:-}}"
  export AWS_SECRET_ACCESS_KEY="${BACKUP_R2_SECRET_ACCESS_KEY:-${R2_SECRET_ACCESS_KEY:-}}"
  export AWS_DEFAULT_REGION=auto
  EP="https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com"
  log "uploading to s3://$BACKUP_R2_BUCKET/postgres/ via aws cli"
  aws s3 cp --endpoint-url "$EP" "$DUMP" "s3://$BACKUP_R2_BUCKET/postgres/" --only-show-errors
  aws s3 cp --endpoint-url "$EP" "$GLOBALS" "s3://$BACKUP_R2_BUCKET/postgres/" --only-show-errors
  log "offsite ok (remote retention: set an R2 lifecycle rule of ${BACKUP_REMOTE_RETENTION_DAYS} days on the bucket)"
else
  log "WARN: no offsite target (set RCLONE_REMOTE, or R2_ACCOUNT_ID + BACKUP_R2_BUCKET + keys); local copy only"
fi
