# Marketing AI Platform — Roadmap

> Version 1.0 — 2026-10-13
> First client: B2B hydrogel manufacturer (long sales cycle)
> Business model: SaaS
> Reference: vision-public.md v1.9.9 §23-24, architecture.md v1.1

---

## Phase 0 — Foundation (Scaffold + Infrastructure)

> Goal: working project skeleton, database, event pipeline, fraud/budget protection.
> All later phases depend on this.

### 0-1. Project Scaffold ✅

- [x] Spring Boot project (platform-api): build.gradle.kts, Application.java, config, security
- [x] FastAPI project (ml-service): main.py, requirements.txt, Dockerfile
- [x] Next.js project (frontend): app router, middleware.ts, package.json
- [x] docker-compose.yml (PostgreSQL + pgvector, Redis, 3 services)
- [x] Dockerfiles for each service
- [x] `docker-compose up` — postgres (pgvector) + redis start healthy; app services build in Phase 0-2

### 0-2. Database Schema (Flyway Migrations) ✅

- [x] V1 — tenants, users
- [x] V2 — creatives, creative_atoms
- [x] V3 — landing_variants, variant_offers
- [x] V4 — thompson_arms
- [x] V5 — events (partitioned 2026-10 → 2027-03), sessions + GHL fields
- [x] V6 — decision_log (propensity logging)
- [x] V7 — meta_campaigns, meta_ad_sets, budget_guards, ghl_webhook_log
- [x] V8 — meta_pixels + FK to meta_campaigns (multi-pixel support §6.8)
- [x] RLS policies on all 17 tenant-scoped tables (V9 initial, V25 fixes NULLIF cast + mai_app role + FORCE ROW LEVEL SECURITY restored — arch v1.1 §5)

### 0-3. Auth & Multi-Tenant ✅ (partial)

- [x] JWT auth (register, login) — Spring Security + JJWT 0.12.x; tested live: register+login return token+userId+tenantId
- [x] TenantFilter (JwtFilter): extract tenant from JWT → TenantContext → SET app.current_tenant per connection
- [x] TenantAwareDataSource: wraps HikariCP, propagates tenant on every getConnection()
- [x] 401 vs 403: AuthenticationEntryPoint returns 401 for unauthenticated requests
- [ ] refresh token endpoint
- [ ] RLS enforcement verified with integration test
- [x] CORS config: CorsConfigurationSource, allowed origins via CORS_ALLOWED_ORIGINS env var, tested preflight 200/403

### 0-4. Event Ingestion Pipeline ✅

- [x] `POST /api/v1/events` — batch event ingestion endpoint (Spring Boot), public (no JWT)
- [x] Event validation (@Valid, @NotBlank, @Size(max=100) per batch)
- [x] Consent filter: none=CONVERSION only, analytics_only=no behavioral (SCROLL/HOVER/BLOCK_VIEW)
- [x] JDBC batchUpdate write to partitioned events table (non-blocking per batch)
- [x] Session upsert via ON CONFLICT — create on first event, update last_event_at on subsequent
- [x] Event deduplication by `event_id` via Redis (24h TTL) — V11 adds column (renamed to `event_id` in V24, arch v1.1 §4.5)
- [x] Fraud scoring Phase 0 (log-only): PAGE_VIEW with zero interaction → is_suspicious=true
- [ ] Lead qualification events: ContactCreate, AppointmentCreate/Update, OpportunityStatusUpdate from GHL (Phase 3)

### 0-5. Server-Side Tracking (sGTM / proxy) ✅

- [x] Custom server-side proxy: Spring Boot IS the proxy (sGTM on GCP = optional external setup, not needed for MVP)
- [x] CAPI quality gate (B2B §16.1): CRM_CALL_ANSWERED=Lead(×0.15), AppointmentUpdate_showed=Schedule(×0.40), OpportunityStatusUpdate_won=Purchase(×1.00)
- [x] GHL webhook handler: POST /webhooks/ghl/{tenantId} → logs to ghl_webhook_log, links session by email, triggers CAPI pipeline
- [x] CAPIForwardingService: builds Meta CAPI payload (SHA-256 hashed PII), posts to graph.facebook.com/v20.0/{pixel_id}/events, falls back to PENDING when no credentials
- [x] `capi_events` log table (V12): tracks PENDING/SENT/FAILED/SKIPPED for every CAPI attempt
- [x] EMQ monitoring: GET /capi-events + GET /capi-events/summary (sent/pending/failed/skipped counts)
- [x] event_id deduplication field on capi_events for overlap prevention with browser pixel
- [ ] sGTM on GCP setup — external infrastructure, documented as optional (Phase 3 when scaling)

### 0-6. Fraud Protection (§12.6 — basic) ✅

- [x] Layer 1 — CF-Bot-Score header read at event ingestion: CF-Bot-Score < 30 → suspicious (Cloudflare in front sends this)
- [x] Layer 2 — Behavioral session filter (inline, on ingest):
  - NO_INTERACTION: PAGE_VIEW + no SCROLL/CLICK/HOVER → suspicious (fraud_score += 0.3)
  - BOT_USER_AGENT: known bot UA list (30+ patterns, Meta crawler allowlisted) → suspicious (fraud_score += 0.9)
  - CF_BOT_SCORE: score < 30 → suspicious (fraud_score += 0.7)
- [x] Layer 2 — Offline session review (hourly FraudReviewScheduler):
  - SESSION_TOO_SHORT: last_event_at - started_at < 2s → suspicious (fraud_score += 0.4)
  - IP_CLUSTER: 50+ sessions from same IP+UA in 1h → suspicious (fraud_score += 0.65)
- [x] V13 migration: ip_address, user_agent, fraud_score, fraud_reasons JSONB on sessions
- [x] IP extracted server-side (CF-Connecting-IP → X-Forwarded-For → REMOTE_ADDR)
- [x] Telegram alert when tenant fraud rate > 10% in last 1h (min 10 sessions)
- [x] GET /fraud/summary: suspicious counts + fraud rate (1h / 24h) + top reasons
- [x] Suspicious sessions keep is_suspicious=true in model (excluded from TS training, kept in denominator per §15.3)
- [ ] Cloudflare Bot Management WAF rules (external infrastructure — configure in CF dashboard)
- [ ] reCAPTCHA Enterprise v3 on form submit (Phase 2 when form is built)

### 0-7. Financial Circuit Breaker L1 (§12.5a) ✅

- [x] Budget guards CRUD endpoints: GET/POST/PUT/DELETE + PATCH /spend (manual Meta spend sync)
- [x] PER_CREATIVE_STOP_LOSS: guard per creative, triggers when currentSpend >= threshold
- [x] MONTHLY_CEILING: account-level, auto-resets on new billing month
- [x] Telegram Bot alert on trigger (TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT_ID env vars; skips gracefully if not configured)
- [x] CircuitBreakerScheduler: @Scheduled fixedDelay=5min, checks all non-triggered guards across all tenants
- [ ] L2/L3 (cascading pause, account-level stop) — Phase 3 with Meta Marketing API

### 0-8. CMP Integration (§12.1a) ✅

- [x] `src/lib/consent.ts` — consent detection library (Cookiebot primary, localStorage fallback)
  - Three levels: full / analytics_only / none
  - Reads window.Cookiebot.consent.{statistics,marketing} when available
  - Fires/listens to CookiebotOnAccept, CookiebotOnDecline, mai:consent events
- [x] `src/lib/tracker.ts` — consent-aware event batcher
  - SCROLL/HOVER/BLOCK_VIEW/CLICK: require consent="full"
  - FORM_SUBMIT/CONVERSION/PURCHASE/LEAD: always allowed
  - Batches every 5s + on beforeunload + visibilitychange=hidden (keepalive)
  - UTM params auto-extracted from URL and attached to every event
- [x] `src/components/CookieBanner.tsx` — fallback banner when Cookiebot not configured
  - Three buttons: Decline / Necessary Only / Accept All
  - Hidden when NEXT_PUBLIC_COOKIEBOT_DOMAIN_ID is set (Cookiebot takes over)
- [x] Cookiebot script injection in layout.tsx (beforeInteractive, blocking-mode=auto)
  - Activated by NEXT_PUBLIC_COOKIEBOT_DOMAIN_ID env var
- [x] Backend GET /consent/stats — per-tenant consent breakdown (24h / 7d)
  - Returns: total sessions, full_consent count, consent_rate_pct, full breakdown
- [x] No-consent sessions: NOT treated as bounces (vision §12.1a note — excluded from TS training, kept in denominator)
- [ ] Cookiebot subscription + domain configuration (external — client provides Domain Group ID)
- [ ] /privacy page (legal requirement for banner's Privacy Policy link)

### 0-9. Pixel Pollution Protection (§6.8) ✅

- [x] Multi-pixel support: meta_pixels table + CRUD endpoints (GET/POST/PUT/DELETE + PUT /{id}/default)
- [x] Pixel purposes: PRIMARY / TEST / PRODUCT
- [x] Quality filter JSONB per pixel (criteria for CAPI forwarding: require_crm_match, allowed_events)
- [x] Atomic set-default: JDBC unset-all → JPA save (safe under concurrent access)
- [x] Cannot delete default pixel → 409 CONFLICT (bug fixed: Spring Security /error dispatch → added /error to permitAll)
- [x] PixelResponse.isDefault serialization fixed (@JsonProperty + @JsonIgnoreProperties)
- [ ] Pixel assignment to campaigns per ad set (Phase 3 with Meta API)
- [ ] CAPI Quality Gate enforcement (Phase 3 — currently stores filter, does not enforce)
- [ ] Admin UI: manage pixels per tenant, assign to campaigns (Phase 2 UI)

### 0-10. Appointment Lifecycle ✅ (arch v1.1: Google Calendar removed)

- [x] `appointments` table (V14): tracks GHL appointment lifecycle events
- [x] `AppointmentService`: GHL AppointmentCreate → registers; AppointmentUpdate appointmentStatus=showed→COMPLETED, noshow→NO_SHOW
- [x] `AppointmentCheckScheduler`: every 30 min — 7-day timeout → NO_SHOW
- [x] Attendance set MANUALLY by sales rep in GHL CRM (appointmentStatus field); Calendar API NOT used (arch v1.1 §13.1)
- ~~Google Calendar~~: removed in arch v1.1 §2. GoogleCalendarService.java deleted. No OAuth2 credentials endpoint.

**Phase 0 exit criteria**: `docker-compose up` runs all services; auth works; events can be ingested and stored; fraud flagging active; budget guard alerts fire; lead qualification events from GHL flow through to CAPI.

---

## Phase 1 — Analytics Brain

> Goal: Creative DNA extraction, descriptive diagnostics, dashboard.
> Value: "which creatives work and why" — useful without any automation.

### 1-1. Creative Upload & Storage ✅

- [x] `POST /api/v1/creatives` — multipart/form-data upload (file or external fileUrl)
- [x] `StorageService` abstraction: `local` (dev) / `r2` (Phase 4). R2 deferred per vision §23 — Phase 4 Media Asset Pipeline
- [x] `FileController`: `GET /files/{tenantId}/{creativeId}/{filename}` serves local files
- [x] `Creative` + `CreativeAtom` JPA entities (map existing V2 tables; embedding col unmapped until pgvector Phase 1+)
- [x] `GET /api/v1/creatives`, `GET /api/v1/creatives/{id}` (with atoms), `PUT`, `DELETE` (archive), `PUT /{id}/anchor`
- [x] Manual atom CRUD: `POST/PUT/DELETE /api/v1/creatives/{id}/atoms/{atomId}`
- [x] `POST /api/v1/creatives/{id}/analyze` — stub: sets status=ANALYZING, returns jobId (Phase 1-2 implements FastAPI call)
- [x] `app.storage.*` config in application.yml; multipart max 500MB
- [ ] Thumbnail generation (FFmpeg single frame for video) — deferred Phase 4 (video rendering)
- [ ] CAPI Quality Gate enforcement per pixel (Phase 3)

### 1-2. Creative DNA Extraction (§4.2) ✅

- [x] `POST /analyze/creative` (FastAPI) — async job via BackgroundTasks; returns `{job_id, status: "queued"}` immediately
- [x] `GET /analyze/status/{job_id}` — polls Redis job store (TTL 1h); states: queued → running → completed | failed
- [x] Image: Claude Vision direct analysis — `claude-opus-4-5`, base64 encoded; extracts HOOK, FACE, SUBTITLES, TONE, PROOF_TYPE, CTA, PRODUCTION_STYLE
- [x] Video + transcript: Claude Text analysis — HOOK, STRUCTURE, TONE, PROOF_TYPE, CTA; vision used on thumbnail if available
- [x] DURATION extracted from metadata (15s / 30s / 60s / 60s_plus buckets), confidence=1.0
- [x] Atoms stored to `creative_atoms` via asyncpg with RLS (`SET LOCAL app.current_tenant`) — deletes prior auto-extracted, keeps manual
- [x] Confidence scores per atom (0.80 vision, 0.75 transcript, 1.0 metadata)
- [x] Spring Boot `MlServiceClient` → FastAPI `POST /analyze/creative` (RestTemplate); reverts status to UPLOADED if ml-service unreachable
- [x] `creative_dna.py` orchestrates: IMAGE→vision, VIDEO+thumbnail→vision, VIDEO+transcript→text
- [ ] EDIT_PACE atom (scene detection) — deferred Phase 4, requires FFmpeg
- [ ] MUSIC atom — deferred Phase 4, requires audio analysis
- [ ] Whisper auto-transcription — deferred Phase 4 (Phase 1: transcript provided manually by marketer)
- [ ] Admin UI for manual atom editing — deferred Phase 2 (backend CRUD already in Phase 1-1)

### 1-3. Descriptive Diagnostics Dashboard ✅

- [x] `GET /api/v1/analytics/overview?days=30` — sessions, conversions, CVR, revenue, creative counts
- [x] `GET /api/v1/analytics/creatives?days=30` — per-creative: sessions, conversions, CVR, revenue; Meta fields (spend, ROAS, impressions, CTR) = null with disclaimer
- [x] `GET /api/v1/analytics/atoms?days=30[&atomType=HOOK]` — atom-level breakdown: creative_count, sessions, conversions, CVR per atom_type × atom_value
- [x] `GET /api/v1/analytics/funnel?days=30` — funnel stages (PAGE_VIEW→SCROLL→FORM_START→FORM_SUBMIT→CONVERSION) with drop-off % + §3.2 diagnostic card
- [x] §3.2 diagnostic card — 4 detectable symptoms (HIGH_BOUNCE, HIGH_FORM_ABANDON, LOW_CVR, WEAK_CTA) from first-party events; LOW_HOOK_RATE/LOW_CTR/CREATIVE_FATIGUE as Phase 3 placeholders
- [x] FastAPI `GET /diagnostics/{tenant_id}` — same diagnostic logic in Python; called internally
- [x] Disclaimer on every response: "Phase 1 = descriptive, NOT causal. Meta metrics require Phase 3."
- [ ] Front-end dashboard UI — deferred Phase 2 (API ready, UI not yet built)

### 1-4. Creator Brief Skeleton (§5.5) ✅

- [x] `POST /api/v1/briefs` — create brief; auto-populates recommended/avoid atoms from last 90d descriptive stats
- [x] `GET /api/v1/briefs`, `GET /api/v1/briefs/{id}` — list + detail
- [x] `PUT /api/v1/briefs/{id}` — update (title, audience, product info, notes, status DRAFT→FINAL)
- [x] `POST /api/v1/briefs/{id}/regenerate` — re-run atom suggestions from latest stats
- [x] `GET /api/v1/briefs/{id}/export?format=markdown|json` — markdown download or JSON
- [x] Markdown export: structure template, recommended atoms (CVR + rationale), avoid atoms, benchmarks, notes
- [x] FastAPI `POST /brief/generate` — same logic for external calls (Phase 3 will add posterior data)
- [x] V15 Flyway migration: `creator_briefs` table with RLS
- [ ] PDF export — deferred Phase 4 (markdown is sufficient for Phase 1)
- [ ] Data-driven brief from posteriors — Phase 3-4 (vision §5.5.2)

### 1-5. Weekly Auto-Report ✅

- [x] `WeeklyReportScheduler` — @Scheduled every Monday 08:00 UTC, iterates all tenants
- [x] Per-tenant: overview (sessions, conversions, CVR, revenue), top 3 creatives, top 5 atom insights, funnel anomalies
- [x] Telegram delivery via `TelegramNotificationService.sendWeeklyReport()`
- [x] Email delivery via `JavaMailSender` (optional — skipped if MAIL_HOST not configured)
- [x] `spring-boot-starter-mail` added to build.gradle.kts
- [x] `REPORT_EMAIL_TO`, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD` env vars
- [x] Graceful skip: no Telegram + no email → warn log only, no exception
- [ ] Per-tenant email address (vs shared REPORT_EMAIL_TO) — Phase 2 (once tenant settings UI exists)

**Phase 1 exit criteria**: marketer uploads creatives → sees extracted atoms + performance breakdown in dashboard; can generate a template brief.

---

## Phase 2 — Landing Page Engine + Thompson Sampling on Site

> Goal: first closed loop — generate landings, test with Thompson Sampling, learn.
> This is the core MVP value proposition.

### 2-1. Landing Block Components (Next.js) ✅

- [x] HeroBlock, ProblemBlock, ProofBlock, PricingBlock, CTABlock, FAQBlock (6 components)
- [x] Each block renders `data-block-id` for analytics (IntersectionObserver fires BLOCK_VIEW)
- [x] Responsive: Tailwind CSS, mobile-first
- [x] Configurable via JSONB (text slots, images, colors, data types per block)
- [x] LandingRenderer.tsx — block array → component dispatch; IntersectionObserver for BLOCK_VIEW
- [x] `/l/[slug]/page.tsx` — SSR landing page (ISR revalidate 60s)
- [ ] Block preview in admin panel (Phase 2 admin UI)

### 2-2. Landing Variant CRUD (Admin Panel) ✅

- [x] `POST /api/v1/variants` — create DRAFT variant with blocks JSONB
- [x] `GET /api/v1/variants`, `GET /api/v1/variants/{id}` — list + detail (with offer sub-atoms)
- [x] `PUT /api/v1/variants/{id}` — update (DRAFT or PAUSED only)
- [x] Approval workflow: DRAFT → REVIEW (`POST /{id}/submit`) → ACTIVE (`POST /{id}/approve`) → PAUSED → ARCHIVED
- [x] ThompsonArm created lazily by FastAPI on first `/decide` call for each ACTIVE variant (α=β=1 uninformative prior) — arch v1.1 §12 #12 single writer = FastAPI
- [x] `GET /api/v1/public/l/{slug}` — public (no auth) endpoint for SSR landing fetch
- [x] Offer sub-atoms (§4.3a): discount, guarantee, bonus, urgency, payment_terms, margin_floor
- [x] `LandingVariant`, `VariantOffer`, `ThompsonArm` JPA entities (maps existing V3/V4 tables)
- [ ] LLM content generation assist (Phase 2-2 extension — deferred)
- [ ] Message Match score computation (Phase 2 extension)
- [ ] Variant preview (full page render in admin panel)

### 2-2b. Phase 1 Audit Fixes ✅ (закрыты в рамках Phase 2)

- [x] **P1-1**: `diagnostics.py` и `brief.py` — заменены самостоятельные пулы на `db_client.get_pool()` (shared pool)
- [x] **P1-2**: Bounce-логика в `diagnostics.py` унифицирована с `AnalyticsService.java`: `event_count = 1 AND NOT has_scroll`
- [x] **P1-3**: `/diagnostics/{tenant_id}` и `/brief/generate` защищены `X-Internal-API-Key` header validation

### 2-2c. Audit 004 — Architecture v1.1 + Vision v1.9.9 Compliance ✅ (40 fixes)

**Compliance (C1–C10)**

- [x] **C1–C3**: `tenant_id` column type aligned to `UUID` across all tables (was `BIGINT` in tests)
- [x] **C4**: `landing_variants.block_order` propagated through `load_arms()` → `/decide` response
- [x] **C5**: `landing_variants.block_order` returned in `DecideResponse` (FastAPI)
- [x] **C6**: `session_id` made optional in `/decide/landing` (`Optional[str] = None`)
- [x] **C7**: `block_order` included in `/decide/landing` response body
- [x] **C8**: GHL webhook event names aligned to real GHL names (`ContactCreate`, `ContactUpdate`, `AppointmentCreate`, `AppointmentUpdate`, `OpportunityStatusUpdate`)
- [x] **C9**: `users` table — email unique per tenant (not globally); V22 migration
- [x] **C10**: `User` entity unique constraint updated to `(tenant_id, email)`

**Integrity (I1–I11)**

- [x] **I1**: `reward_event VARCHAR(50) DEFAULT 'FORM_SUBMIT'` added to `thompson_arms` (V23)
- [x] **I2**: `ThompsonArm.java` entity field `rewardEvent` added
- [x] **I3**: `client_event_id` → `event_id` rename (V24 migration + UNIQUE index on partitioned table)
- [x] **I4**: `decision_log.candidates` JSONB comment updated to include `propensity` + `arm_id` fields
- [x] **I5–I6**: RLS policies rewritten with `NULLIF(current_setting('app.current_tenant', true), '')::uuid` on all 17 tables; `FORCE ROW LEVEL SECURITY` re-enabled; `mai_app` role created (V25)
- [x] **I7**: `/decide/landing` — `session_id: Optional[str] = None` (consent-gated, arch v1.1 §3)
- [x] **I8**: `DecideResponse` gains `block_order: list[str]`
- [x] **I9**: `consent.ts` sets `_consent` cookie so Edge middleware can check consent before sending `session_id`; `middleware.ts` reads `_consent` cookie and passes `session_id: null` when no tracking consent
- [x] **I10**: `clientEventId` → `eventId` in `EventDto.java`, `EventIngestionService.java`, `tracker.ts`
- [x] **I11**: `VariantService.approveVariant()` no longer creates `thompson_arms` — single writer = FastAPI (arch v1.1 §12 #12); arms created lazily in `load_arms()` on first `/decide`

**Deletions (D1–D10) — Google Calendar removed from MVP**

- [x] **D1**: `GoogleCalendarService.java` deleted
- [x] **D2**: `AppointmentService.java` — Calendar fallback logic removed; `registerAppointment()` signature simplified to 5 params
- [x] **D3**: `AppointmentCheckScheduler.java` — stale "Calendar fallback" comment removed
- [x] **D4**: `Appointment.java` — `calendarEventId`, `calendarId`, `calendarCheckedAt`, `attendeeEmail` fields removed
- [x] **D5**: (covered by D4)
- [x] **D6**: `Tenant.java` — `calendarCredentialsEnc` field removed; V26 drops DB columns
- [x] **D7**: `TenantService.java` — `setCalendarCredentials()` method removed
- [x] **D8**: `TenantController.java` — `PUT /me/calendar-credentials` endpoint removed
- [x] **D9**: `CalendarCredentialsRequest.java` DTO deleted
- [x] **D10**: `appointments` + `tenants` table Calendar columns dropped (V26 migration)

**Migrations (M1–M2)**

- [x] **M1**: Spring Boot `3.3.0` → `3.5.0` (LTS), dependency-management `1.1.4` → `1.1.7`
- [x] **M2**: `docker-compose.yml` — `GHL_WEBHOOK_SECRET` env var wired; `DB_APP_ROLE=mai_app` added

**Documentation (Doc1–Doc4)**

- [x] **Doc1**: `roadmap.md` — reference versions updated to arch v1.1 / vision v1.9.9
- [x] **Doc2**: `roadmap.md` — GHL event names corrected to real webhook names
- [x] **Doc3**: `roadmap.md` — CAPI value-bidding chain documented
- [x] **Doc4**: `roadmap.md` — Thompson Sampling formulas, RLS details, Google Calendar removal reflected

### 2-3. Thompson Sampling Engine ✅

- [x] `POST /decide/landing` (FastAPI) — Beta(α,β) sampling, logs full candidate set to decision_log
- [x] `POST /decide/reward` — reward signals: all binary (delta=1.0); NO_RESPONSE_7D(β+=1.0), all positive signals (α+=1.0) — arch v1.1 §4.4
- [x] Redis-cached posteriors (key: `posterior:{tenant_id}:{context_key}`, TTL 15 min)
- [x] Daily α/β decay: `α = 1 + 0.99·(α−1)` (toward prior, not toward zero) — arch v1.1 §9
- [x] Propensity logging: every `/decide/landing` logs N=200 Monte Carlo propensities to decision_log (mandatory per §23)
- [x] Decision cache in Redis (TTL 1h): `decision:{id}` → stores tenant_id, variant_id, arm_id for reward lookup
- [x] `GET /decide/power/{tenant_id}` — Monte Carlo P(best) per arm (n=10k), ready flag
- [x] `thompson_service.py` — standalone service module (load_arms, sample_arms, log_decision, apply_reward)
- [ ] Posterior recalculation from events every 15 min (currently lazy on read — Phase 2 extension)

### 2-4. Edge Middleware (Next.js) ✅

- [x] `middleware.ts` — intercepts `/l/:slug*` routes
- [x] Ensures session cookie `_sid` (UUID, 30d)
- [x] Calls Spring Boot `/public/l/{slug}` → gets tenantId
- [x] Calls FastAPI `/decide/landing` → gets variant_id + decision_id
- [x] Sets `_variant`, `_decision`, `_assigned_at` cookies (1h TTL, reassigns on stale)
- [x] Extracts UTM params + device type, passes as context to `/decide`
- [x] Non-fatal: decide failure → SSR page falls back to default variant
- [x] `API_INTERNAL_URL` for server-side Docker network calls (separate from `NEXT_PUBLIC_API_URL`)

### 2-5. Client-Side Event Tracker ✅

- [x] `tracker.ts` — consent-aware batcher (already built in Phase 0-8)
- [x] FORM_START added to ALWAYS_ALLOWED (funnel event, no consent required)
- [x] `variantId` passed in track() opts → stored on events for A/B attribution
- [x] IntersectionObserver in LandingRenderer fires BLOCK_VIEW with `blockEventId`
- [x] Form events (FORM_START, FORM_SUBMIT) tracked from CTABlock

### 2-6. Conversion Flow ✅

- [x] `POST /api/v1/public/reward` — public endpoint forwards reward signals to ml-service
- [x] `RewardController.java` — proxies to `POST {ML_SERVICE_URL}/decide/reward`
- [x] `thompson_service.apply_reward()` — updates α/β, invalidates Redis cache
- [x] FORM_SUBMIT on CTABlock calls `/public/reward` with decisionId (keepalive fetch)
- [x] Reward map (arch v1.1 §4.4, binary only): all signals δ=1.0 — CTA_CLICK/FORM_START/FORM_SUBMIT/CALL_ANSWERED/MEETING_ATTENDED/CONVERSION/PURCHASE→α, NO_RESPONSE_7D→β

### 2-7. A/B Dashboard (Bayesian) ✅

- [x] `GET /api/v1/analytics/ab?days=30` — per-variant: α, β, estimated_cvr, sessions, conversions, observed_cvr
- [x] Monte Carlo P(best): pure Java Beta sampling (Marsaglia-Tsang method), n=5000 simulations
- [x] `AbVariantRow` DTO with probBest + estimatedCvr fields
- [x] Results sorted by probBest descending (implicit winner = first row)
- [ ] Frontend chart (Phase 2 admin UI)
- [ ] Anchor creative comparison (Phase 3 — requires Meta metrics)

### 2-8. RLHA — Marketer Feedback Loop (§13.3) ✅ (MVP)

- [x] Approval workflow IS the RLHA loop: marketer approves (ACTIVE) or pauses (PAUSED) variants
- [x] ThompsonArm α/β reflect actual conversion signals (not just marketer opinion)
- [x] `approved_by` + `approved_at` tracked on each variant
- [ ] Content correction tracking (delta from LLM suggestions) — deferred (no LLM content gen yet)

**Phase 2 exit criteria**: marketer creates landing variants → Thompson Sampling routes visitors → events tracked → posteriors update → dashboard shows which variant wins with Bayesian confidence.

---

## Phase 2 — Audit Fixes (Блок 0)

### Audit F1 — FORM_SUBMIT reward ✅

- [x] Добавлен `reward_on_form_submit: bool = True` в `config.py`
- [x] `apply_reward()` пропускает FORM_SUBMIT при `reward_on_form_submit=false` — возвращает `{skipped: true}`
- [x] `RewardResponse` расширен: `skipped`, `reason` (optional fields)
- [x] `REWARD_ON_FORM_SUBMIT=${REWARD_ON_FORM_SUBMIT:-true}` в docker-compose.yml
- [x] Для B2B клиента (гидрогели): установить `REWARD_ON_FORM_SUBMIT=false` — reward придёт из GHL webhooks

### Audit F2 — block_order не маппился в Java ✅

- [x] `LandingVariant.java`: добавлено поле `blockOrder String[]` (`@Column(name = "block_order", columnDefinition = "text[]")`)
- [x] `VariantRequest.java`: добавлено поле `blockOrder String[]` (optional)
- [x] `VariantResponse.java`: добавлено поле `blockOrder String[]`
- [x] `VariantService.java`: `blockOrder` маппится при create и update

### Audit F3 — URL path ✅

- [x] `VariantController.java`: `@RequestMapping("/variants")` → `@RequestMapping("/landings")`
- [x] Фронтенд не имел ссылок на `/variants` (проверено grep)

### Tech Debt T1 — N+1 в listVariants ✅

- [x] `ThompsonArmRepository`: добавлен `findByVariantIdInAndContextKey(List<UUID>, String)` (batch)
- [x] `VariantOfferRepository`: добавлен `findByVariantIdInAndTenantId(List<UUID>, UUID)` (batch)
- [x] `VariantService.listVariants()`: один запрос на arms + один на offers (не N+1)
- [x] `toResponse()` рефакторинг: перегрузка с явным arm параметром; single-variant методы загружают arm из DB (1 запрос)

### Tech Debt T2 — X-Internal-API-Key в MlServiceClient ✅

- [x] `MlServiceClient.java`: добавлен `headers.set("X-Internal-API-Key", appProperties.getInternalApiKey())`

---

## Phase 3 — Decision Graph + Meta Integration

> Goal: full Thompson Sampling with Bayesian GLM; two-contour Meta structure; staged gates.

### 3-1. Bayesian GLM (§6.3a, §15.3) ✅

- [x] PyMC hurdle model: BetaBinomial CTR + BetaBinomial CVR + Lognormal AOV (three independent models)
- [x] Atom-level effects with partial pooling — HalfNormal hyperprior on σ_atoms (§15.4)
- [x] Beta-Binomial for overdispersion (κ parameter, collapses to Binomial when κ→∞)
- [x] match_score as calibrated feature — wide prior Normal(0, 2.0) until real data calibrates
- [x] LKJ correlated creative random effects deferred to Phase 3-1b (segment interactions)
- [x] V21 migration: glm_posteriors + glm_fit_runs tables
- [x] app/models/bayesian_glm.py — PyMC model builders + FitResult extraction
- [x] app/services/model_updater.py — async pipeline: fetch events+atoms→fit→persist posteriors
- [x] app/routers/glm.py — POST /glm/fit, GET /glm/posteriors, GET /glm/fit-runs, POST /glm/predict
- [ ] Segment random effects (audience × atom interactions) — Phase 3-1b
- [ ] Off-policy evaluation on propensity logs (IPS weights) — Phase 3-1c

### 3-2. Two-Contour Meta Structure (§6.5) ✅

- [x] V17 migration: confirmation_ad_set_id, confirmation_status on meta_campaigns; batch_id, batch_launched_at on meta_ad_sets
- [x] MetaCampaign entity: confirmationAdSetId, confirmationStatus (NONE→RUNNING→PASSED/FAILED)
- [x] MetaAdSet entity: batchId, batchLaunchedAt
- [x] TwoContourService: launchTestBatch (equal budgets, batch_id grouping per §6.5)
- [x] TwoContourService: selectWinner with James-Stein shrinkage (anti-winner's-curse per §6.5)
- [x] TwoContourService: startConfirmationRun (1.5× budget confirmation ad set)
- [x] TwoContourService: confirmAndTransfer (validates spend > 0, transfers to SCALE)
- [x] TwoContourService: failConfirmation (pauses confirmation ad set, resets to FAILED)
- [x] BudgetChangeGuard: validateBudgetStep enforces ≤20% rule (vision §6.5)
- [x] Controller endpoints: /campaigns/{id}/launch-batch, /winner, /confirmation-run, /confirm-and-transfer, /fail-confirmation
- [x] Learning phase guard: budget decisions deferred (MetaSyncScheduler tracks learningPhase)
- [ ] Learning Phase monitoring (log status before/after each launch)

### 3-3. Meta API Integration ✅

- [x] V16 migration: meta_ad_account_id on tenants, meta_ad_id on ad_sets, spend columns + indexes
- [x] MetaCampaign + MetaAdSet JPA entities
- [x] MetaCampaignRepository + MetaAdSetRepository
- [x] MetaApiService: Graph API v20.0 — createCampaign, setCampaignStatus, createAdSet, setAdSetStatus, getInsights (RestTemplate)
- [x] MetaCampaignService: createCampaign, listCampaigns, pause/activate, transferToScale (two-contour §6.5), createAdSet, listAdSets
- [x] MetaCampaignController: POST/GET /campaigns, /campaigns/{id}/pause, /campaigns/{id}/activate, /campaigns/transfer-to-scale, POST/GET /ad-sets
- [x] MetaSyncScheduler: 30-min sync for ACTIVE campaigns and ad sets (spend_today, spend_total, syncedAt)
- [x] PUT /tenants/me/meta-credentials — store access_token + ad_account_id
- [x] Tenant.metaAdAccountId field (mapped from V16)
- [ ] Rate-limiting priority queue (§10.3): P0 circuit breaker, P1 normal, P2 background
- [ ] CAPI: send conversion events with value (margin, predicted LTV)

### 3-4. Staged Gates — Sequential Elimination (§12.3) ✅

- [x] V18 migration: gate_status, kill tracking, impressions/clicks/conversions totals, is_anchor per ad set; max_spend_per_adset on campaigns
- [x] MetaAdSet entity: gateStatus (GATE_1→GATE_2→GATE_3→PASSED/KILLED), impressionsTotal, clicksTotal, conversionsTotal, isAnchor
- [x] MetaCampaign entity: maxSpendPerAdset (L1 cap), targetCpa, breakevenRoas, monthlyCeilingBudget
- [x] MetaApiService: extended getInsights() to return conversions (actions: purchase+lead)
- [x] MetaSyncScheduler: accumulates impressionsTotal, clicksTotal, conversionsTotal per sync cycle
- [x] GateEvaluationService: Bayesian gates with Marsaglia-Tsang Monte Carlo (10K samples)
  - Gate 1: ≥5K impressions + ≥2 days → P(CTR > group mean) ≥ 10%
  - Gate 2: ≥300 clicks → P(CTR > group mean) ≥ 10%
  - Gate 3: ≥50 conversions → P(CVR > anchor or group) ≥ 10%
  - L1 circuit breaker: spend > maxSpendPerAdset → kill
- [x] GateScanScheduler: hourly scan of all TEST campaigns
- [x] GateController: GET /campaigns/{id}/gate-summary, GET /ad-sets/{id}/gate-status, POST /campaigns/{id}/evaluate-gates, PUT /ad-sets/{id}/anchor

### 3-5. Full Circuit Breaker (§12.5a L2-L3) ✅

- [x] V19 migration: circuit_breaker_events table + CPM history + campaign budget thresholds
- [x] CircuitBreakerEvent entity + repository
- [x] CampaignCpmHistory entity + repository (14-day rolling for z-score spike detection)
- [x] CircuitBreakerService: L2/L3 triggers + Bayesian probability helpers + cascade rules
  - L2: P(CPA > 1.5×target) > 0.85 OR spend > 80% ceiling in 8h OR CPM z-score > 3
  - L3: P(ROAS < breakeven) > 0.90 OR monthly ceiling reached
  - Cascade: 3+ L1 in 24h → L2; L2 unresolved 48h → L3
- [x] CircuitBreakerService.resolveEvent() — marketer resolves via admin panel
- [x] CircuitBreakerScheduler: updated to run L2/L3 CB check every 30 min
- [x] CircuitBreakerController: GET /circuit-breaker, GET /circuit-breaker/history, POST /{id}/resolve, POST /check
- [x] Telegram alert on L2 (🟡 warning) and L3 (🔴 emergency pause)
- [ ] Meta Automated Rules as last-resort fallback (external — must be configured in Meta dashboard at client onboarding)

### 3-6. Exploration Budget (§6.4) ✅

- [x] V20 migration: exploration_rate on meta_campaigns; is_exploration_slot on meta_ad_sets; exploration_prior_alpha/beta on creatives
- [x] Creative entity: explorationPriorAlpha=2.0, explorationPriorBeta=1.0 (default Beta(2,1))
- [x] MetaCampaign entity: explorationRate field (default 0.15 = 15%)
- [x] MetaAdSet entity: isExplorationSlot flag
- [x] ExplorationBudgetService.recommendBatch(): splits batch into exploitation + exploration slots
  - Exploitation: Top-Two Thompson EVI ranking (5K MC samples) — prioritises arms near top with high uncertainty
  - Exploration: new creatives (< 10 obs) with optimistic prior, random order
- [x] ExplorationBudgetService.getOptimisticPrior(): reads per-creative Beta prior for new arm seeding
- [x] TwoContourService.launchTestBatch(): marks ad sets with isExplorationSlot when explorationCreativeIds provided
- [x] GET /campaigns/{id}/recommend-batch?batchSize=9 → RecommendedBatchResponse with EVI scores
- [x] TestBatchRequest: explorationCreativeIds set (marketer passes IDs from recommend-batch response)

### 3-7. Delayed Churn Penalty (§6.7) ✅

- [x] Attribution via `sessions.deal_status='lost'` → `decision_log` → `thompson_arms` (uses existing GHL session linking)
- [x] `V27__churn_penalty.sql`: `sessions.churn_penalized_at` column + `churn_penalty_log` table with RLS
- [x] `ChurnPenaltyService.java`: cohort grouping, exponential decay weight (`λ=0.02/day`), min cohort 20, 90-day force-close
- [x] `ChurnPenaltyScheduler.java`: daily at 03:00 UTC
- [x] FastAPI `apply_churn_penalty()` in `thompson_service.py`: 30% cap on (α+β), DB update + Redis cache invalidation
- [x] FastAPI `POST /decide/churn-penalty` endpoint (X-Internal-API-Key protected)
- [x] Unit tests: cap formula, decay weight, edge cases (26/26 passed)

### 3-11. Pixel Pollution Auto-Detection (§6.8 Level 3) ✅

- [x] Lead quality drift detector: quality_rate_14d = CRM_MEETING_ATTENDED / FORM_SUBMIT per UTM campaign; drifts when rate < 70% of 30d baseline AND ≥20 forms
- [x] Auto-alert on anomalous lead quality drop: `PixelPollutionScheduler` daily 04:00 UTC → `PixelPollutionService.runScan()` → CircuitBreaker L2 event + Telegram alert
- [x] Auto-pause recommendation: `GET /api/v1/pixel-pollution/scans` (scan history) + `GET /api/v1/pixel-pollution/recommendations` (campaigns with active drift, recommended_action=PAUSE_CAMPAIGN)
- [x] `V29__pixel_pollution.sql`: `pixel_pollution_scans` table with RLS + unique dedup index
- [x] Unit tests: 14 Java + 8 Python (logic tests, boundary conditions)

### 3-8. What-If Simulator (§21.4) ✅

- [x] `ml-service/app/routers/simulate.py` — new router, registered at `/simulate/`
- [x] **budget** scenario: Monte Carlo CVR × new budget → sessions/conversions/CPA/ROAS corridor (p10/p50/p90)
- [x] **power** scenario: simulates future Thompson-allocated observations until P(best) ≥ target; returns sessions needed + days at current traffic rate
- [x] **arm_disable** scenario: re-ranks remaining arms after excluding variants; CVR delta vs current best
- [x] `SimulatorController.java`: `POST /api/v1/simulate` — proxies to FastAPI, injects `tenant_id` from JWT (prevents cross-tenant queries)
- [x] Unit tests: 19/19 passed — all three scenarios, edge cases (dominant arm, all excluded, negative CVR delta)

### 3-9. System vs Baseline Benchmark (§21.5) ✅

- [x] `V28__holdout_benchmark.sql`: `tenants.holdout_enabled/holdout_rate` (default ON, 4%) + `decision_log.is_holdout`
- [x] Holdout assignment: deterministic hash of `session_id` → 0–9999 bucket; same session always in same group
- [x] `/decide/landing`: 4% of sessions get random arm (not Thompson); logged as `is_holdout=true`
- [x] `GET /decide/benchmark/{tenant_id}`: Bayesian lift — P(system > holdout) via Beta posteriors + Monte Carlo N=10 000
- [x] `BenchmarkController.java`: `GET /benchmark?days=30` proxy + `PUT /benchmark/holdout` toggle with warning on disable
- [x] `Tenant.java`: `holdoutEnabled` + `holdoutRate` fields
- [x] Unit tests: deterministic bucket, ~4% fraction, edge cases (31/31 passed)

### 3-10. Reactive Competitive Intelligence (§9.1) ✅

- [x] Ad Library polling: `CompetitiveIntelligenceScheduler` weekly Monday 05:00 UTC → `CompetitiveIntelligenceService.runWeeklyScan()` → reads `competitor_page_ids` from `tenants.settings`, calls Meta `ads_archive` API for ads running >14 days
- [x] Vision LLM extracts atoms from competitor creatives: FastAPI `POST /competitive/extract-atoms` — Claude claude-sonnet-5-5 extracts hook_type, tone, offer_type, pain_points, cta_type, format_signals; stub fallback when API key absent
- [x] Generate counter-hypotheses: Claude generates 3 differentiated counter-creative directions per competitor ad (hypothesis, hook_suggestion, offer_suggestion, differentiation, confidence)
- [x] Alert marketer with ready-to-test batch (human approval required): `competitive_intel_alerts` table + `GET /api/v1/competitive-intel` list + `POST /{id}/approve` + `POST /{id}/dismiss`; Telegram notification on new ads; auto-deploy WITHOUT human approval is FORBIDDEN
- [x] `V30__competitive_intelligence.sql`: `competitor_ads` + `competitive_intel_alerts` tables with RLS
- [x] Unit tests: 13 Python tests (JSON parsing, stub fallbacks, prompt formatting)

**Phase 3 exit criteria**: full Bayesian model running; Meta campaigns managed via API; staged gates auto-eliminate losers; system measurably outperforms random allocation.

---

## Phase 4 — Video Assembly ✅

### 4-1. Video Template Engine ✅

- [x] Upload source footage → Whisper + Vision LLM scene tagging
  - `POST /video/footage` (Spring Boot) — multipart upload → StorageService → video_footage table
  - `POST /video/footage/{id}/transcribe` → FastAPI Whisper API (stub when key absent)
  - `POST /video/footage/{id}/tag-scenes` → Claude claude-sonnet-5-5 scene tagger (stub fallback)
  - `POST /video/footage/{id}/montage-plan` → LLM director: Hook→Problem→Solution→Proof→CTA segments
- [x] FFmpeg render: 9:16 (1080×1920), 4:5 (1080×1350), 1:1 (1080×1080)
  - `POST /video/footage/{id}/render` → async FFmpeg job (list args, no shell=True — injection-safe)
  - `GET /video/render/{jobId}/status` — job poll
  - V31 migration: `video_footage` + `video_renders` tables with RLS (NULLIF/FORCE/mai_app)
  - `VideoLifecycleScheduler`: HOT→COLD after 90 days, orphaned renders FAILED after 30 days
- [x] Pure logic extracted to `app/services/video_service.py` for testability

### 4-2. Variation Generation ✅

- [x] Hook × Body × CTA combinations (3 × 1 × 3 = 9 HOOK_VARIATION creatives)
  - `POST /video/footage/{id}/generate-variations` → FastAPI variation matrix
  - `build_variations()` in `video_service.py`: Hook × Body × CTA cross-product per format
- [x] Subtitle/overlay variations (low-effort, high-variation)
  - 5 OVERLAY_VARIATION specs per request: white_bottom / yellow_bold / white_top / black_box / none
- [x] Marketer approval before launch
  - `POST /video/renders/{id}/approve` — marks COMPLETED render as approved (sets approved_by, approved_at)
  - Deploy forbidden without approval (vision §5.2)

### 4-3. Media Asset Pipeline ✅

- [x] Cloudflare R2 storage (S3-compatible, zero egress)
  - `R2StorageService.java` — AWS SDK v2, path-style access, region=auto
  - `AppProperties.Storage.R2` nested config class
  - `application.yml` wired via `STORAGE_TYPE`, `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_PUBLIC_URL`
  - `build.gradle.kts` — `software.amazon.awssdk:s3:2.25.0`
- [x] Hot → Cold lifecycle (90 days), orphaned renders killed (30 days)
  - `VideoLifecycleScheduler.java` — `@Scheduled(cron = "0 0 2 * * *")`

### 4-4. Data-Driven Creator Brief (§5.5) ✅

- [x] Brief from Thompson posteriors + failure graveyard + Ad Library references
  - `POST /brief/generate-data-driven` (FastAPI) — pulls α/β per arm, ARCHIVED low-CVR creatives, competitor_ads
- [x] Anti-patterns section — failure graveyard: ARCHIVED creatives with sessions≥50 and CVR<0.5%
- [x] Benchmarks from own model data — benchmark CVR from `thompson_arms`
- [x] Free Concept slot — mandatory §5.5.3: `include_free_concept_slot: bool = True`
- [x] Optional Claude synthesis — claude-sonnet-5-5 narrative brief (stub when key absent)

---

## Phase 5 — Funnel Personalization ✅

### 5-1. Behavioral Clusters (§7.3) ✅

- [x] `app/services/cluster_service.py` — pure logic: `extract_features`, `fit_clusters`, `assign_cluster`, `rule_based_label`, `personalise_block_order`, `compute_ips_weight`, `validate_ips_coverage`
- [x] `app/routers/cluster.py` — `POST /cluster/fit`, `POST /cluster/assign`, `POST /cluster/personalise`, `POST /cluster/ips-coverage`
- [x] `ClusterController.java` — `/api/v1/cluster/labels`, `/api/v1/cluster/assign`, `/api/v1/cluster/personalise` (consent gate + 7.5% control group)
- [x] `ClusterFitScheduler.java` — weekly K-means fit (Sunday 03:00 UTC), pre-filters consent='full' + is_suspicious=false
- [x] V32 migration: `behavior_clusters` table + `cluster_id/cluster_label` on sessions
- [x] 47 tests PASS (test_cluster.py)

### 5-2. Propensity Model ✅

- [x] `app/services/propensity_service.py` — `extract_propensity_features`, `rule_based_score`, `determine_objection`, `score_session`, `fit_propensity_model`, `compute_uplift_score`
- [x] `app/routers/propensity.py` — `POST /propensity/score`, `POST /propensity/fit`
- [x] `PropensityController.java` — `/api/v1/propensity/score`, `/api/v1/propensity/session/{id}`
- [x] V33 migration: `session_propensity` table + `personalization_group` on sessions
- [x] V35 migration: `propensity_models` table (trained model storage)
- [x] 28 tests PASS (test_propensity.py)

### 5-3. Uplift Modeling + Contextual Bandit ✅

- [x] `app/services/decide_service.py` — `derive_context_key` (cluster*id → `cluster*{N}` arms)
- [x] `/decide/landing` extended: `cluster_id` field → routes to cluster-specific Thompson arms (falls back to global if not found)
- [x] IPS weights for off-policy evaluation in `cluster_service.py`: `compute_ips_weight`, `validate_ips_coverage`
- [x] 6 tests PASS (test_decide_context.py — context_key derivation)

### 5-4. Real-Time Landing Adaptation ✅

- [x] `POST /decide/personalise` — returns personalised `block_order` by cluster_label; only reorders pre-approved blocks (§5.4.1 — no new content generated)
- [x] `CLUSTER_BLOCK_RULES` for all 7 archetypes in `cluster_service.py`
- [x] 5-10% control group enforced at Spring Boot `ClusterController.personalise()` via deterministic hash (~7.5%)
- [x] 9 tests PASS (test_decide_context.py — personalise)

### 5-5. Meta Feedback Loop ✅

- [x] `MetaAudienceController.java` — `GET /api/v1/meta/audiences`, `POST /api/v1/meta/audiences/sync`, `GET /api/v1/meta/audiences/segments`
- [x] `MetaApiService.upsertCustomAudience()` — creates/updates Meta Custom Audience from hashed email list
- [x] V34 migration: `meta_audience_sync` table
- [x] Only consent_level='full' + is_suspicious=false sessions contribute (§12.1a, §12.6.3)
- [x] Custom audiences = hints to Advantage+ only — not hard targeting (§25 п.12)

---

## Phase 6 — Predictive Layer & Autopilot ✅

### 6-1. Creative Burnout Prediction (§8.1) ✅
- [x] `burnout_service.py` — `compute_burnout_score`, `classify_burnout`, `estimate_days_remaining`
- [x] `routers/burnout.py` — `POST /burnout/score`
- [x] `BurnoutController.java` — `GET /api/v1/burnout/summary`, `POST /{id}/resolve`, `POST /scan`
- [x] `BurnoutScanScheduler.java` — ежедневный scan 06:00 UTC
- [x] V36 migration: `creative_burnout_events` с RLS
- [x] 21 тест PASS (test_burnout.py)

### 6-2. Audience Saturation Detection (§8.2) ✅
- [x] `saturation_service.py` — `compute_reach_efficiency`, `classify_saturation`, `differentiate_diagnosis`
- [x] `routers/saturation.py` — `POST /saturation/score`
- [x] `SaturationController.java` — `GET /summary`, `POST /record`
- [x] V37 migration: `meta_insights_history` — time series reach/frequency/spend
- [x] 17 тестов PASS (test_saturation.py)

### 6-3. Predictive Budget Curve (§8.4) ✅
- [x] `budget_curve_service.py` — Monte Carlo Beta(α,β) → CPA p10/p50/p90 per budget point
- [x] `routers/predict.py` — `POST /predict/budget-curve`
- [x] `PredictiveController.java` — загружает α/β из `thompson_arms` (read-only), проксирует в FastAPI
- [x] 15 тестов PASS (test_budget_curve.py)

### 6-4. Evolutionary Algorithm ✅
- [x] `evolution_service.py` — `rank_creatives_by_cvr`, `crossover_atoms`, `mutate_atoms`, `generate_candidates`
- [x] `routers/evolve.py` — `POST /evolve/candidates`
- [x] `EvolveController.java` — `POST /generate`, `GET /candidates`, `POST /{id}/approve`, `POST /{id}/reject`
- [x] V38 migration: `evolution_candidates` (PROPOSED → APPROVED → REJECTED → LAUNCHED)
- [x] 19 тестов PASS (test_evolution.py)

### 6-5. Negative Learning Graveyard ✅
- [x] `graveyard_service.py` — `classify_failure_mode`, `analyze_failure`, `generate_postmortem_summary`
- [x] `routers/graveyard.py` — `POST /graveyard/postmortem`
- [x] `GraveyardController.java` — `GET /`, `GET /{id}`, `POST /analyze/{creativeId}`
- [x] V39 migration: `creative_postmortems` с RLS
- [x] 16 тестов PASS (test_graveyard.py)

### 6-6. Autopilot Mode with Safety Limits ✅
- [x] `AutopilotService.java` — `evaluateActions`, `isSafeToExecute`, `executeActions`
- [x] `AutopilotController.java` — `GET/PUT /settings`, `GET /actions`, `POST /actions/{id}/undo`, `POST /evaluate`
- [x] `AutopilotScheduler.java` — почасовой запуск только для tenants с `enabled=true`
- [x] V40 migration: `autopilot_settings` (enabled=false по умолчанию) + `autopilot_actions`
- [x] 5 Java тестов PASS (AutopilotServiceTest.java, Mockito)
- [x] Safety: `max_l1_triggers_per_day=3`, `min_p_best_to_scale=0.95`, `auto_scale_winner=false` по умолчанию

---

## Phase 0.5 — Universal Cross-Niche Model (parallel track)

> Starts when 5+ clients onboarded. Full value at 20-30 clients per niche.

### 0.5a. Legal & Data Preparation

- [ ] Data sharing consent in client contracts (aggregates only, not raw)
- [ ] Verify Meta Platform Terms re: cross-client data (§25 п.19)
- [ ] Niche taxonomy: "B2B manufacturing with long sales cycle"

### 0.5b. Technical Implementation (§15.12)

- [ ] Feature store for aggregated patterns (niche benchmarks, red flags)
- [ ] Three-level hierarchy: universal → niche → client priors
- [ ] Red flags detector: early kill at 50-100 visits ($20-50)
- [ ] Taxonomy Alignment Layer (pgvector embeddings)

---

## Phase 7 — Multi-Channel

- [x] Google Ads API integration (GoogleAdsApiService.java, ChannelController.java, V42/V44 migrations)
- [x] TikTok Ads API integration (TikTokApiService.java)
- [x] Unified decision graph with channel branches (/decide extended with `channel` field → `channel:META` context_key prefix, backwards-compatible fallback to global arms)
- [x] Bayesian MMM for cross-channel budget allocation (mmm_service.py, mmm.py router, MmmController.java, V43/V45 migrations) — ⚠️ calibration данные нужны 6-12 мес работы (§17.3)
- [x] Cross-channel attribution (MMM + MTA hybrid) (attribution_service.py, attribution.py router: Markov chain + Shapley values + last_touch + linear)

> **Примечание по Bayesian MMM**: инфраструктура реализована (adstock трансформация, Hill saturation кривые, бюджетный оптимизатор). Для калибровки полноценной модели необходимы 6–12 месяцев реальных мультиканальных данных (§17.3). До накопления данных MMM работает с заданными вручную saturation_params.

---

## Bug List

_(empty — project not started)_

---

## Current Status

| Phase     | Status          | Notes                                                                                                             |
| --------- | --------------- | ----------------------------------------------------------------------------------------------------------------- |
| Phase 0   | **IN PROGRESS** | 0-1 ✅ 0-2 ✅ 0-3 ✅ (partial) 0-4 ✅ 0-5 ✅ 0-6 ✅ 0-7 ✅ 0-8 ✅ 0-9 ✅ 0-10 ✅ — Phase 0 complete (partial 0-3) |
| Phase 1   | **COMPLETE**    | 1-1 ✅ 1-2 ✅ 1-3 ✅ 1-4 ✅ 1-5 ✅                                                                                |
| Phase 2   | **COMPLETE**    | 2-1 ✅ 2-2 ✅ 2-3 ✅ 2-4 ✅ 2-5 ✅ 2-6 ✅ 2-7 ✅ 2-8 ✅ (MVP)                                                     |
| Phase 3   | **COMPLETE**    | 3-1 ✅ 3-2 ✅ 3-3 ✅ 3-4 ✅ 3-5 ✅ 3-6 ✅ 3-7 ✅ 3-8 ✅ 3-9 ✅ 3-10 ✅ 3-11 ✅                                    |
| Phase 4   | **COMPLETE**    | 4-1 ✅ 4-2 ✅ 4-3 ✅ 4-4 ✅                                                                                       |
| Phase 5   | **COMPLETE**    | 5-1 ✅ 5-2 ✅ 5-3 ✅ 5-4 ✅ 5-5 ✅                                                                                |
| Phase 6   | **COMPLETE**    | 6-1 ✅ 6-2 ✅ 6-3 ✅ 6-4 ✅ 6-5 ✅ 6-6 ✅                                                                       |
| Phase 0.5 | Not started     | Needs 5+ clients                                                                                                  |
| Phase 7   | **COMPLETE**    | 7-1 Google ✅ 7-2 TikTok ✅ 7-3 Decision graph ✅ 7-4 MMM ✅ 7-5 Attribution ✅                                   |

**Immediate priority**: Phase 7 (Multi-Channel) or Phase 0.5 (Universal Cross-Niche Model) — Phase 6 complete
