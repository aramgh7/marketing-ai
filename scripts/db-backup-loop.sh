#!/usr/bin/env bash
# Entrypoint of the compose `db-backup` service (audit 2026-10-22, X-3): first dump BACKUP_INITIAL_DELAY_SEC (300)
# after start, then every BACKUP_INTERVAL_SEC (86400). A failed backup is logged and retried on the next tick;
# the container never exits on its own.
set -u
INTERVAL="${BACKUP_INTERVAL_SEC:-86400}"
sleep "${BACKUP_INITIAL_DELAY_SEC:-300}"
while true; do
  if ! bash /scripts/backup-db.sh; then
    echo "[db-backup] BACKUP FAILED at $(date -u +%FT%TZ) — check the log above and free disk space" >&2
  fi
  sleep "$INTERVAL"
done
