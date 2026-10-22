# Backup sidecar (audit 2026-10-22, X-3). Same image as the database, so pg_dump / pg_restore match the server
# version exactly; adds rclone for the offsite copy to Cloudflare R2. Scripts are bind-mounted from ./scripts.
FROM pgvector/pgvector:0.8.0-pg16
RUN apt-get update && apt-get install -y --no-install-recommends rclone ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /backups && chown postgres:postgres /backups
USER postgres
WORKDIR /backups
ENTRYPOINT ["bash", "/scripts/db-backup-loop.sh"]
