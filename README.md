# Marketing AI Platform

AI-powered Meta Ads optimization: Thompson Sampling, Creative DNA, Proxy Value Bidding.

## Services

| Service | Tech | Port (loopback) |
|---------|------|-----------------|
| `platform-api` | Spring Boot 3.5 / Java 21 (context path `/api/v1`) | 127.0.0.1:8080 |
| `ml-service` | FastAPI / Python 3.12 | 127.0.0.1:8000 |
| `frontend` | Next.js 15 App Router / Node 22 | 127.0.0.1:3000 |
| `postgres` | pgvector `0.8.0-pg16` | not published |
| `redis` | `7.2-alpine` (last BSD-licensed line; 7.4+ is RSALv2/SSPL — stay on 7.2.x or move to Valkey) | not published |
| `db-backup` | same Postgres image + rclone, daily `pg_dump` to `./backups` and R2 | — |

Nothing listens on a public interface: Cloudflare Tunnel on the host forwards `app.example.com -> localhost:3000`
and `api.example.com -> localhost:8080`. The pilot runs on one physical server (Ryzen 7 5800XT, 64 GB, NVMe
mirror); object storage and offsite backups are Cloudflare R2 — see `.ai/infrastructure-decision.md`.

## Запуск с нуля

Requirements: Docker Engine 24+ with Compose v2, `openssl`, `git`.

```bash
git clone <repo> && cd Marketing-AI
git config core.hooksPath scripts/githooks          # pre-commit secret scan (already set in this clone)

# 1. Environment — every ${VAR:?required} in docker-compose.yml must be present in .env
cp .env.example .env
openssl rand -base64 32    # -> JWT_SECRET, ENCRYPTION_KEY, INTERNAL_API_KEY, GHL_WEBHOOK_SECRET (one call each)
openssl rand -hex 24       # -> POSTGRES_PASSWORD, DB_APP_PASS, DB_ML_PASS, REDIS_PASSWORD (hex: used inside URLs)
# then fill NEXT_PUBLIC_API_URL, CORS_ALLOWED_ORIGINS, PLATFORM_HOSTS, LANDING_CNAME_TARGET, R2_*, BACKUP_R2_BUCKET,
# META_APP_ID/SECRET, TELEGRAM_* or SLACK_WEBHOOK_URL, ANTHROPIC_API_KEY, OPENAI_API_KEY

# 2. Build and start (prod profile by default)
docker compose up -d --build
docker compose ps                                    # wait until every service is "healthy"

# 3. Migrations run automatically (Flyway, V1–V136) when platform-api starts:
docker compose logs -f platform-api | grep -i flyway

# 4. First user — registration is open on the API (audit X-6: close it after the first admin):
curl -s -X POST http://127.0.0.1:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"<strong password>","tenantName":"My Agency","tenantSlug":"my-agency"}'
```

Health: `http://127.0.0.1:8080/api/v1/actuator/health`, `http://127.0.0.1:8000/health`, `http://127.0.0.1:3000/`.

Rules worth knowing:
- `ENCRYPTION_KEY` is mandatory: without it Meta/GHL/CAPI tokens would be stored in clear (audit X-4).
- `DB_APP_PASS` / `DB_ML_PASS` are applied by migrations V116/V127 on the first run only; to rotate later run
  `ALTER ROLE mai_app PASSWORD '…'` / `ALTER ROLE mai_ml PASSWORD '…'` and update `.env`.
- `NEXT_PUBLIC_*` values are baked into the frontend image — rebuild (`docker compose build frontend`) after changing them.
- Memory limits (`deploy.resources.limits`): JVM 6g, ML 8g, Postgres 8g, Redis 1g, frontend 1g, backup 512m.

### Local development

```bash
# infra on loopback (127.0.0.1:5432 / :6379) + platform-api on the `local` profile
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres redis

cp platform-api/.env.example platform-api/.env        # for ./gradlew bootRun / IDE
cp ml-service/.env.example ml-service/.env            # for uvicorn main:app --reload
cp frontend/.env.local.example frontend/.env.local    # for npm run dev

cd platform-api && ./gradlew bootRun
cd ml-service && python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt && uvicorn main:app --reload
cd frontend && npm install && npm run dev
```

The self-hosted landing runner (Phase 10, runs on a client's server) is `docker-compose.runner.yml` +
`frontend/.env.runner.example`.

## Бэкап и восстановление

The `db-backup` service runs `scripts/backup-db.sh` every 24 h (first run 5 min after start):
`pg_dump -Fc` + `pg_dumpall --globals-only` (roles) → `./backups/marketingai-<UTC>.dump` (+ `.globals.sql`),
keeps 14 local copies, uploads to R2 (`BACKUP_R2_BUCKET`, private bucket, 30-day prune) via rclone.
A failed backup is logged as `BACKUP FAILED`; watch `docker compose logs db-backup`.

```bash
scripts/backup-db.sh                                  # ad-hoc backup from the host (docker exec into mai-postgres)
scripts/restore-db.sh backups/marketingai-20261022-030000.dump          # restore DRILL into marketingai_restore_test
scripts/restore-db.sh backups/marketingai-20261022-030000.dump --live   # real restore: stops apps, replaces the DB
```

**Monthly restore drill (mandatory):** on the first working day of each month run `scripts/restore-db.sh` on the
newest dump (and once a quarter on a copy downloaded from R2), check that the printed Flyway version equals the
latest migration and that tenant/session counts are plausible, and record the date and numbers in the ops log.
A backup that has not been restored is not a backup.

Redis is cache + dedup only (AOF on the `redisdata` volume); it is not backed up.

## Проверки

| Check | Command | Needs |
|-------|---------|-------|
| Java unit/integration tests | `cd platform-api && ./gradlew test` | JDK 21, online Gradle |
| ML tests | `cd ml-service && pytest -q -m "not slow"` | Python 3.12, `pip install -r requirements.txt pytest` |
| Frontend types + build | `cd frontend && npx tsc --noEmit && npm run build` | Node 22 |
| SQL in Java vs schema | `python scripts/check-sql.py` | Docker (throwaway Postgres on 5441) |
| Row-level security | `bash scripts/verify-rls.sh` | Docker (5440) |
| Migrations V1–V136 on a fresh DB | `bash scripts/verify-migrations.sh` | Docker (5439) |
| Java → ML route table | `python scripts/check-ml-routes.py` | — (WARN lists ML routes with no Java caller) |
| Compose file | `docker compose config --quiet` | `.env` present |

`.github/workflows/ci.yml` runs all of them plus a gitleaks scan on every push / PR.

### Pre-commit secret scan

`git config core.hooksPath scripts/githooks` enables `scripts/githooks/pre-commit`, which refuses commits whose
staged diff contains credential-looking strings (AWS/Anthropic/OpenAI/GitHub/Slack keys, private keys,
`JWT_SECRET=`/`ENCRYPTION_KEY=` with real values). Bypass only with a deliberate `--no-verify` and say why.

## Docs

Architecture, roadmap, vision, audits and the infrastructure decision are in `.ai/`.
