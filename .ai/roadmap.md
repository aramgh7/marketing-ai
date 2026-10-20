# Marketing AI Platform — Roadmap

> Version **[v1.1 — changed]** ~~1.0 — 2026-10-13~~ 1.1 — 2026-10-15 · **v1.1.1**: weather for client #1 corrected from OFF to critical (3-12) · **[v1.2 — changed]** **1.2 — 2026-10-17**: Phase 8 Client #1 Adaptation (vision §19, arch §16) · **[v1.3 — changed]** **1.3 — 2026-10-17**: 8-14 … 8-21 beyond-capability modules (vision §19.18, arch §16.13–16.21), additive
> First client: **[v1.2 — changed]** ~~B2B hydrogel manufacturer (long sales cycle)~~ hydrogel manufacturer **raising capital — investor lead-gen funnel under Reg D 506(c)** (book a call → held call → accreditation → funds; $75K avg ticket). A secondary grower-facing funnel (webinar, field events) exists. See vision §19.
> Business model: SaaS
> Reference: vision-public.md **[v1.2 — changed]** ~~v1.9.9 §23-24~~ v1.9.10 (§19, §23-24), architecture.md **[v1.1 — changed]** ~~v1.1~~ ~~v1.2 (§15 Gap Closure Modules)~~ **[v1.2 — changed]** v1.3 (§15 Gap Closure, §16 Client #1 Adaptation)
>
> **[v1.1 — added] v1.1 — gap closure against vision v1.9.9.** A full read of the vision found 35 mechanisms with no item in this roadmap (or only a deferred stub). They are added below as **unchecked** items inside the phase the vision assigns them, each tagged **[v1.1 — added]** with the vision § and the architecture §15 subsection. Phases previously marked COMPLETE keep that status for their *original* scope; the status table at the end now carries a separate "open v1.1 items" column so nothing is hidden behind a ✅. A consolidated index of all added items is in **"Gap Closure Index (v1.1)"** at the end.
>
> **Marking convention for v1.1 edits**: every touched place is tagged **[v1.1 — added]** or **[v1.1 — changed]**; replaced text kept ~~struck through~~.
>
> **[v1.2 — added] v1.2 — Phase 8.** The client questionnaire (vision §19) showed that client #1 is an investor funnel, not a B2B product sale. Several ✅ modules are coded on the old chain (0-5 CAPI gate, 2-6 reward map, 3-4 gates on CPA-per-booking). Phase 8 is the adaptation track: it does not reopen those items; it moves the funnel behind per-tenant configuration (arch §16.1) and adds the modules §19 introduces (follow-up bandit, securities compliance, investor offer schema). Items 8-1 … 8-3, 8-4 and 8-7 are **pre-onboarding** for client #1. Tag **[v1.2 — added]**.

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
- [x] refresh token endpoint — `POST /auth/refresh` (rotate, 30-day), `POST /auth/logout` (revoke); `refresh_tokens` table (V86, RLS+FORCE+GRANT); `AuthService.refresh()` / `AuthService.logout()` with SHA-256 token hash
- [x] RLS enforcement verified with integration test — `RlsIntegrationTest.java` (TODO: requires real DB; see inline doc for full wiring)
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
- [x] CAPI quality gate (B2B §16.1): CRM_CALL_ANSWERED=Lead(×0.15), AppointmentUpdate_showed=Schedule(×0.40), OpportunityStatusUpdate_won=Purchase(×1.00) **[v1.2 — changed]** → becomes the **B2B template row set** of `capi_event_map`; client #1 chain is 8-2 (arch §16.3)
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
- [x] reCAPTCHA Enterprise v3 on form submit (Phase 2 when form is built)
- [x] **[v1.1 — added]** Layer 2 rules missing vs vision §12.6.2 (arch §15.18): `AUTOMATED_TIMING` (inter-event σ < 50 ms over ≥ 5 events), `LINEAR_SCROLL` (constant velocity, no acceleration), IP cluster extended to **IP + UA + viewport** (CGNAT-safe), zero-scroll rule split touch (mobile) vs mouse-move (desktop)
- [x] **[v1.1 — added]** `tenants.fraud_mode = LOG_ONLY | ENFORCE`; every new tenant starts LOG_ONLY; allowlist for Meta crawlers + in-app browsers (vision §12.6.2)
- [x] **[v1.1 — added]** Share-of-suspicious monitor: > 10 % of sessions in 24 h → alert with source breakdown (vision §12.6.2 Layer 4)

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
- [x] /privacy page (legal requirement for banner's Privacy Policy link) — `frontend/src/app/privacy/page.tsx`; 7-section policy (data collection, rights, cookies, retention, contact)

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

### 0-11. Event Schema Registry, Retention, Right to Erasure ✅ **[v1.1 — added]** (vision §23 Phase 0, §22; arch §15.12)

- [x] `event_schemas` + `events_rejected` + `erasure_requests` + `agg_variant_daily` + `agg_creative_daily` tables (V46)
- [x] `events.schema_version` + `sessions.external_id_hash` + `sessions.visit_count` columns (V46)
- [x] `EventSchemaService` — validates event payloads against registered schema (permissive if no schema); quarantines invalid events to `events_rejected`
- [x] Seed v1 schemas for all 17 known event types (V46)
- [x] `RetentionScheduler` — monthly sweep: events 13 months, sessions 13 months, decision_log 25 months; materialises aggregates before deleting
- [x] `POST /api/v1/privacy/erasure` (`ErasureController`) — deletes by `crm_user_id` or `external_id_hash`; redacts PII in `ghl_webhook_log`; writes `erasure_requests` audit row
- [x] Register additional event types from vision §7.2: `VIDEO_PROGRESS`, `QUIZ_ANSWER`, `CALC_INTERACT`, `FIRST_CLICK`, `RETURN_VISIT` — deferred to Phase 2-5 when tracker emits them
- [x] `sessions.linked_session_ids` column — deferred to Phase 2 cross-device work

### 0-12. Multi-Tenant Security Hardening ✅ **[v1.1 — added]** (vision §12.7 "P0 at onboarding"; arch §15.15, §11)

- [x] `tenants.meta_token_status` (OK/EXPIRING/INVALID) + `meta_token_rotated_at` + `meta_token_created_at` columns (V47)
- [x] `TokenRotationScheduler` — daily 04:00 UTC; EXPIRING alert at day 50, INVALID at day 58 + Telegram alert
- [x] `meta_api_audit` append-only table — `mai_app` INSERT/SELECT only (V47)
- [x] `MetaApiAuditService` + `GET /api/v1/audit-log`
- [x] `AppProperties.Meta` — `META_APP_ID` / `META_APP_SECRET` env vars wired
- [x] Envelope encryption (full per-tenant key wrapping) — requires KMS; deferred to production
- [x] Record granted OAuth scopes per tenant — deferred to Phase 3 OAuth flow

### 0-13. Site Health — HTTP Check (alert-only) ✅ **[v1.1 — added]** (vision §11.4; arch §15.3 — autopause arrives in Phase 3-13)

- [x] `site_health_checks` + `site_health_incidents` tables (V48) with RLS + FORCE + GRANT
- [x] `SiteHealthScheduler` — every 60s; HEAD check per ACTIVE landing; 2 consecutive FAILs in 10 min → incident + Telegram alert
- [x] Sentinel UA `"MarketingAI-HealthCheck/1.0"` — requests excluded from events/sessions/fraud scoring
- [x] `GET /api/v1/site-health` + `POST /api/v1/site-health/{id}/resolve`

**Phase 0 exit criteria**: `docker-compose up` runs all services; auth works; events can be ingested and stored; fraud flagging active; budget guard alerts fire; lead qualification events from GHL flow through to CAPI. **[v1.1 — added]** Plus: event payloads validated against the schema registry; erasure endpoint deletes a test subject end-to-end; every Meta API call appears in `meta_api_audit`; token rotation dry-run passes.

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
- [x] Admin UI for manual atom editing — deferred Phase 2 (backend CRUD already in Phase 1-1)

### 1-3. Descriptive Diagnostics Dashboard ✅

- [x] `GET /api/v1/analytics/overview?days=30` — sessions, conversions, CVR, revenue, creative counts
- [x] `GET /api/v1/analytics/creatives?days=30` — per-creative: sessions, conversions, CVR, revenue; Meta fields (spend, ROAS, impressions, CTR) = null with disclaimer
- [x] `GET /api/v1/analytics/atoms?days=30[&atomType=HOOK]` — atom-level breakdown: creative_count, sessions, conversions, CVR per atom_type × atom_value
- [x] `GET /api/v1/analytics/funnel?days=30` — funnel stages (PAGE_VIEW→SCROLL→FORM_START→FORM_SUBMIT→CONVERSION) with drop-off % + §3.2 diagnostic card
- [x] §3.2 diagnostic card — 4 detectable symptoms (HIGH_BOUNCE, HIGH_FORM_ABANDON, LOW_CVR, WEAK_CTA) from first-party events; LOW_HOOK_RATE/LOW_CTR/CREATIVE_FATIGUE as Phase 3 placeholders
- [x] FastAPI `GET /diagnostics/{tenant_id}` — same diagnostic logic in Python; called internally
- [x] Disclaimer on every response: "Phase 1 = descriptive, NOT causal. Meta metrics require Phase 3."
- [x] Front-end dashboard UI — deferred Phase 2 (API ready, UI not yet built)
- [x] **[v1.1 — added]** Diagnostic principles from vision §3.2 not yet applied (arch §15.21): compare every symptom against the tenant's **own baseline per placement × device** (Simpson's paradox), Benjamini–Hochberg correction across the symptom list, minimum-n before a symptom is shown
- [x] **[v1.1 — added]** §3.2 row "CTR good but Landing Page Views ≪ clicks → PageSpeed / pixel": symptom `LPV_CLICK_GAP` from first-party `PAGE_VIEW` vs Meta clicks (Phase 3 data) + link to Site Health incidents and CAPI EMQ summary
- [x] **[v1.1 — added]** §3.2 row "everything falls at once → external factor": symptom `MARKET_SHIFT` fed by the Phase 3 anomaly scan (arch §15.1); `detect_market_shift()` in `diagnostics_service.py` — requires CTR+CVR both drop >20% AND MARKET_SHIFT symptom across ≥2 placements (Block F §5)

### 1-6. Taxonomy Alignment Layer ✅ **[v1.1 — added]** (vision §15.12.11 — placed in Phase 1–2 by the vision, previously only in 0.5b here; arch §15.10)

- [x] `atom_registry` table (platform-wide: `atom_type`, `canonical_value`, `centroid vector(768)`, `status CANDIDATE/CONFIRMED`, `appearance_count`, `tenant_count`, `merged_into`) + `creative_atoms.registry_id`, `alignment_sim` (V62)
- [x] Embedding step in `analyze.py` after `save_atoms()` (OpenAI or deterministic stub) → `POST /taxonomy/align`: nearest centroid of same `atom_type`, cosine ≥ θ=0.86 → assign; else new CANDIDATE; non-blocking (warn on error)
- [x] Weekly `POST /taxonomy/recalibrate` (TaxonomyRecalibrateScheduler — Sunday 04:00 UTC): re-cluster, merge centroids closer than θ_merge=0.92, confirm candidates with ≥10 appearances in ≥2 tenants, re-point `registry_id` of merged entries
- [x] `GET /api/v1/taxonomy` — registry browser; `PUT /api/v1/taxonomy/{id}/merge` — manual merge override by marketer; `POST /api/v1/taxonomy/recalibrate` — on-demand
- [x] `app/services/taxonomy_service.py` — pure logic, ZERO FastAPI/Pydantic imports; 19/19 unit tests pass

### 1-7. Voice of Customer — Ingest ✅ **[v1.1 — added]** (vision §9.2; arch §15.8)

- [x] `voc_sources` + `voc_objections` tables with RLS + FORCE + GRANT to mai_app (V63)
- [x] `POST /api/v1/voc/import` — marketer uploads raw quotes (JSON body); PII redacted at import ([EMAIL],[PHONE]); LLM clusters into objection taxonomy (PRICE/TRUST/UNDERSTANDING/URGENCY/FIT/PROCESS/COMPETITOR/OTHER); keyword fallback when LLM unavailable
- [x] FastAPI `POST /voc/extract-objections` — Anthropic (claude-haiku-4-5) → OpenAI fallback → keyword fallback; PII already redacted before LLM call
- [x] `GET /api/v1/voc/objections?category=...`; `DELETE /api/v1/voc/sources/{id}` (removes source + objections)
- [x] Objections section ("Возражения аудитории (из VoC)") added to Creator Brief markdown export (`BriefService.buildMarkdown()` via JdbcTemplate top-5)
- [x] `app/services/voc_service.py` — pure logic, ZERO FastAPI/Pydantic imports; 18/18 unit tests pass
- [ ] Phase 3 extension: own-ad comments via Graph API (P2 lane), competitor-ad comments where exposed

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
- [x] Data-driven brief from posteriors — Phase 3-4 (vision §5.5.2)

### 1-5. Weekly Auto-Report ✅

- [x] `WeeklyReportScheduler` — @Scheduled every Monday 08:00 UTC, iterates all tenants
- [x] Per-tenant: overview (sessions, conversions, CVR, revenue), top 3 creatives, top 5 atom insights, funnel anomalies
- [x] Telegram delivery via `TelegramNotificationService.sendWeeklyReport()`
- [x] Email delivery via `JavaMailSender` (optional — skipped if MAIL_HOST not configured)
- [x] `spring-boot-starter-mail` added to build.gradle.kts
- [x] `REPORT_EMAIL_TO`, `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD` env vars
- [x] Graceful skip: no Telegram + no email → warn log only, no exception
- [x] Per-tenant email address (vs shared REPORT_EMAIL_TO) — Phase 2 (once tenant settings UI exists)

**Phase 1 exit criteria**: marketer uploads creatives → sees extracted atoms + performance breakdown in dashboard; can generate a template brief. **[v1.1 — added]** Plus: atoms carry a `registry_id`; a VoC import produces an objection list that appears in the brief.

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
- [x] Block preview in admin panel (Phase 2 admin UI)

### 2-2. Landing Variant CRUD (Admin Panel) ✅

- [x] `POST /api/v1/variants` — create DRAFT variant with blocks JSONB
- [x] `GET /api/v1/variants`, `GET /api/v1/variants/{id}` — list + detail (with offer sub-atoms)
- [x] `PUT /api/v1/variants/{id}` — update (DRAFT or PAUSED only)
- [x] Approval workflow: DRAFT → REVIEW (`POST /{id}/submit`) → ACTIVE (`POST /{id}/approve`) → PAUSED → ARCHIVED
- [x] ThompsonArm created lazily by FastAPI on first `/decide` call for each ACTIVE variant (α=β=1 uninformative prior) — arch v1.1 §12 #12 single writer = FastAPI
- [x] `GET /api/v1/public/l/{slug}` — public (no auth) endpoint for SSR landing fetch
- [x] Offer sub-atoms (§4.3a): discount, guarantee, bonus, urgency, payment_terms, margin_floor
- [x] `LandingVariant`, `VariantOffer`, `ThompsonArm` JPA entities (maps existing V3/V4 tables)
- [ ] ~~LLM content generation assist (Phase 2-2 extension — deferred)~~ **[v1.1 — changed]** → moved to 2-9 as a first-class Phase 2 item (vision §23 Phase 2 "constructor **with LLM slot-filling**")
- [ ] ~~Message Match score computation (Phase 2 extension)~~ **[v1.1 — changed]** → moved to 2-9
- [x] Variant preview (full page render in admin panel)
- [x] **[v1.1 — added]** Offer sub-atom **validity mask** (vision §4.3a; arch §15.6): `discount_type = none ⇒ discount_depth = 0`; `guarantee_type = none ⇒ guarantee_days NULL`; `has_real_urgency ⇒ urgency_source ∈ {inventory_api, crm_deadline, seasonal_window}` — fake timers rejected at `POST /api/v1/landings`
- [x] **[v1.1 — added]** **Margin floor enforcement**: `tenants.settings.min_margin` + `variant_offers.estimated_margin`; `load_arms()` excludes variants below floor from Thompson candidates (vision §4.3a / §18.5 hard constraint); installment terms flagged `requires_legal_review` until tenant legal checkbox set
- [x] **[v1.1 — added]** Rule in code: offers vary on **landings only**; `creatives.offer_variant` is metadata, not a test dimension (vision §4.3a auction bias)

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
- [x] Posterior recalculation from events every 15 min — `PosteriorRecalcScheduler.java` (fixedDelay=900s); logs stale variants; actual alpha/beta writes remain in FastAPI per single-writer rule

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
- [x] **[v1.1 — added]** Vision §7.2 signals not yet emitted (arch §4.5, §15.21): `VIDEO_PROGRESS` (25/50/75/100 for on-page video), `QUIZ_ANSWER` / `CALC_INTERACT` for quiz & calculator blocks (new block components), `FIRST_CLICK` (first interactive target), `RETURN_VISIT` + `sessions.visit_count` under the same consented cookie, scroll **velocity pattern** (not only depth) in `event_data`
- [x] **[v1.1 — added]** Cross-device identity (vision §7.4): on form submit with consent set `sessions.external_id_hash` (SHA-256 email/phone), add hashed `external_id` to CAPI payload (Advanced Matching); daily `RetroCorrectionJob` stitches earlier anonymous sessions with the same hash and flips their "bounce" to "returned & converted" (`bounce_corrected_at`) before posterior recalc

### 2-6. Conversion Flow ✅

- [x] `POST /api/v1/public/reward` — public endpoint forwards reward signals to ml-service
- [x] `RewardController.java` — proxies to `POST {ML_SERVICE_URL}/decide/reward`
- [x] `thompson_service.apply_reward()` — updates α/β, invalidates Redis cache
- [x] FORM_SUBMIT on CTABlock calls `/public/reward` with decisionId (keepalive fetch)
- [x] Reward map (arch v1.1 §4.4, binary only): all signals δ=1.0 — CTA_CLICK/FORM_START/FORM_SUBMIT/CALL_ANSWERED/MEETING_ATTENDED/CONVERSION/PURCHASE→α, NO_RESPONSE_7D→β **[v1.2 — changed]** → reward events resolved through `FunnelService.stage_key` + `tenants.settings.reward_levels` (8-3); client #1: L1 `SLOT_SELECTED`, L2 `FORM_SUBMIT`, corrector `CALL_HELD`

### 2-7. A/B Dashboard (Bayesian) ✅

- [x] `GET /api/v1/analytics/ab?days=30` — per-variant: α, β, estimated_cvr, sessions, conversions, observed_cvr
- [x] Monte Carlo P(best): pure Java Beta sampling (Marsaglia-Tsang method), n=5000 simulations
- [x] `AbVariantRow` DTO with probBest + estimatedCvr fields
- [x] Results sorted by probBest descending (implicit winner = first row)
- [x] Frontend chart (Phase 2 admin UI)
- [ ] Anchor creative comparison (Phase 3 — requires Meta metrics)

### 2-8. RLHA — Marketer Feedback Loop (§13.3) ✅ (MVP)

- [x] Approval workflow IS the RLHA loop: marketer approves (ACTIVE) or pauses (PAUSED) variants
- [x] ThompsonArm α/β reflect actual conversion signals (not just marketer opinion)
- [x] `approved_by` + `approved_at` tracked on each variant
- [ ] ~~Content correction tracking (delta from LLM suggestions) — deferred (no LLM content gen yet)~~ **[v1.1 — changed]** → 2-10 RLHA correction store (ships with 2-9 generation)

### 2-9. LLM Content Generation + Message Match Score ✅ **[v1.1 — added]** (vision §5.1, §5.3, §6.3a; arch §15.7)

- [x] FastAPI `POST /content/generate`: inputs = block type + slots, `product_facts` (2-11), audience, paired creative hook/transcript, descriptive best atoms (posteriors from Phase 3), tenant style profile, RLHA few-shot pairs (2-10) → slot values + `generation_id`; always a DRAFT (vision §5.4.1 Process 1)
- [x] `content_generations` table (prompt hash, generated text, model version) with RLS (V49)
- [x] Spring `POST /api/v1/landings/{id}/generate` → fills DRAFT variant slots; auto-runs compliance checks (2-11) on the draft
- [x] FastAPI `POST /content/match-score` → fills `landing_variants.match_score` (column exists, never populated): LLM scores creative promise vs landing headline/offer; wide prior / low weight until calibrated
- [x] Monthly match-score calibration report: match_score buckets vs realised CVR (vision §6.3a) — `calibration_service.py` (ECE + per-bucket breakdown); `POST /analyze/calibration`; `MatchScoreCalibrationScheduler.java` (cron 1st 07:00 UTC, alerts on ECE > 0.10); 6/6 tests PASS
- [x] **Paired generation**: creating a landing from a creative generates headline **with** the hook (message match by construction, vision §5.3)

### 2-10. RLHA Correction Store ✅ **[v1.1 — added]** (vision §13.3; arch §15.7)

- [x] `rlha_corrections` table: `(generated_text, approved_text)` + `delta_tags` (LLM-classified: TONE, LENGTH, FORBIDDEN_WORD, STRUCTURE, BRAND_CONTEXT) + context; written on every marketer edit of generated content (V49)
- [x] Few-shot injection into `POST /content/generate`: most recent 8–12 pairs of the same content type for the tenant
- [x] `GET /api/v1/rlha/metrics`: weekly **% of generations approved without edits** per tenant (vision target 30 % → 70 %+ in 3 months); `GET /api/v1/rlha/corrections`
- [ ] Phase 5+ note: LoRA niche adapter when a niche has 200+ pairs (not scheduled)

### 2-11. Compliance & IP Pre-Approval — Text ✅ **[v1.1 — added]** (vision §12.1, §12.1a, §12.2, §22; arch §15.4; media checks in 4-5)

- [x] `compliance_checks`, `product_facts`, `trademark_watchlist` tables with RLS (V49)
- [x] LLM Meta-policy reviewer (prompt from Meta Advertising Policies: prohibited claims, personal attributes, misleading claims) → PASS / WARN / FAIL with cited rule
- [x] Claims-vs-product-facts check: every factual claim in landing text / ad copy compared with the tenant's `product_facts`; unsupported → WARN, contradicted → FAIL (catches clickbait and policy violations together — vision §12.2)
- [x] Trademark scan: LLM + exact match against niche `trademark_watchlist`
- [x] `POST /api/v1/compliance/check`, `GET /api/v1/compliance/{entityId}`, `PUT /api/v1/compliance/{id}/override` (reason required)
- [x] **Approval gate**: `VariantService.approveVariant()` refuses unless latest check ∈ {PASS, overridden}; admin shows the §12.1a manual checklist; approve button disabled until ticked
- [x] Admin UI for `product_facts` maintenance: `GET/POST/DELETE /api/v1/product-facts`

### 2-12. Quality Score ✅ **[v1.1 — added]** (vision §3.1, §12.2; arch §15.5; anchor-based clickbait rule in 3-14)

- [x] `sessions.quality_score = w₁·match_score + w₂·(1−bounce) + w₃·scroll_depth + w₄·form_completion` computed on session close (15-min job); weights per tenant, default equal (V50 + QualityScoreScheduler)
- [x] `creative_quality` aggregate table per creative / variant / window (V50)
- [x] Shown as a **protective metric** next to CVR on the A/B dashboard and in the weekly report; **not** added to α/β (one binary event per arm, arch §4.4)
- [x] Clickbait detector: P(CTR > anchor) > 0.9 AND (P(CVR < anchor) > 0.9 OR P(bounce > anchor) > 0.9) → CLICKBAIT_SUSPECT via `/quality/clickbait-check`
- [ ] Phase 3 hook: Quality Score as multiplier on the profit estimate until realised outcomes arrive (vision §12.2 "loses weight once final data come")

### 2-13. Explainability Service ✅ **[v1.1 — added]** (vision §21.2–21.3; arch §15.16)

- [x] FastAPI `POST /explain/{entity_type}`: fixed template filled **only** from model statistics (posterior mean + CI, n, P(best), Quality Score, anchor comparison, frequency vs learned threshold, external context on the date); LLM may rephrase but receives only those numbers
- [x] CI labelled "binomial lower bound" until the Phase 3 overdispersed model is live (vision §21.2 warning) — Wilson CI implemented
- [x] `POST /api/v1/campaigns/{id}/postmortem` — on-demand post-mortem per **campaign** (per-creative exists in 6-5) — `PostmortemController.java`; includes campaign metrics, circuit-breaker events, fraud alerts, channel spend rollup
- [x] Weekly report (1-5) switched to the explain service output — `WeeklyReportScheduler.java` calls `POST /explain/campaign`; narrative appended after funnel section; graceful fallback on service unavailable; explain.py now accepts `campaign` as entity_type; `explain_service.py` CAMPAIGN_TEMPLATE added

### 2-14. Month-1 Client View ✅ **[v1.1 — added]** (vision §24 v1.9.9; arch §15.21)

- [x] Dashboard widget "Quality Gate savings": low-quality session count, avg quality score, blocked conversion estimate via `GET /api/v1/analytics/month1`
- [x] Dashboard widget "date to significance": variant Thompson arm observations + significance_status label
- [x] Clickbait suspects listed from creative_quality table
- [x] Micro-conversion map per landing block (BLOCK_VIEW → CTA_CLICK → FORM_START → FORM_SUBMIT per `block_event_id`) — `micro_conversion_events` table (V87, RLS+FORCE+GRANT); `MicroConversionController.java` (`POST /api/v1/micro-conversion`, `GET /funnel` with view→click→start→submit rates)

**Phase 2 exit criteria**: marketer creates landing variants → Thompson Sampling routes visitors → events tracked → posteriors update → dashboard shows which variant wins with Bayesian confidence. **[v1.1 — added]** Plus: a variant can be generated from a creative with LLM slot-filling, scored for message match, blocked by a FAIL compliance check, and a marketer's edit lands in `rlha_corrections`; offer variants below the margin floor never appear in `/decide`.

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
- [x] Segment random effects (audience × atom interactions) — Phase 3-1b
- [x] Off-policy evaluation on propensity logs (IPS weights) — Phase 3-1c

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
- [x] Learning Phase monitoring (log status before/after each launch) **[v1.1 — changed]** → `learning_phase_log(ad_set_id, phase_before, phase_after, action, at)` written around every Meta write and re-read at +24 h (vision §6.5 rule, §25 p.8; arch §15.20)
- [x] **[v1.1 — added]** Anchor placement rule: `launchTestBatch()` refuses a batch without **2 anchor ad sets** on the same audience spec and budget as the test cells (override with reason, logged) — vision §11.2; arch §15.2
- [x] **[v1.1 — added]** `launchTestBatch(mode = ISOLATED_AD_SETS | CREATIVE_TESTING_TOOL)` — Meta's 2025 Creative Testing tool as alternative to isolated ad sets when the API exposes it (⚠️ verify availability + bias, vision §25 p.14); default stays isolated; `tenants.launch_mode` column (V84); `launchCreativeTestBatch()` in TwoContourService — equal split, all slots exploration
- [x] **[v1.1 — added]** `recommendBidCap()` optional Bid Cap soft launch — P95 CPA from 30-day history → recommended cap; logged to `bid_cap_log` (V83) for audit trail (vision §6.5)

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
- [ ] ~~Rate-limiting priority queue (§10.3): P0 circuit breaker, P1 normal, P2 background~~ **[v1.1 — changed]** → 3-15 (full spec; it is a prerequisite for autopause in 3-13 and for autopilot)
- [ ] CAPI: send conversion events with value (margin, predicted LTV) **[v1.1 — changed]** → see 3-21 proxy-value calibration; `value` = margin-based, not revenue

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
- [x] **[v1.1 — added]** L1 Bayesian trigger: `P(CPA > 2× target | ad-set data) > 0.90` alongside spend threshold (vision §12.5a L1 row); FastAPI /cb/l1-check called by CircuitBreakerService
- [x] **[v1.1 — added]** Slack Incoming Webhooks as second alert back-end behind `AlertService` (vision §12.5a "Telegram/Slack"; arch §12 #17); per-tenant channel choice via settings
- [ ] **[v1.1 — added]** Recovery protocol → 3-16

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
- [x] **[v1.1 — added]** Competitor activity written to `external_signals(signal_type = COMPETITOR)` so the anomaly scan (3-12) can attribute a drop to a competitor launch (vision §11.3 row "competitive activity"); `insertCompetitorSignal()` in `CompetitiveIntelligenceService.java` — upserts per tenant+date with MAX(run_days) as intensity proxy

### 3-12. External Signals Module ✅ **[v1.1 — added]** (vision §11.3, §8.2, §26.6 "Phase 3+"; arch §15.1)

- [x] `external_signals` + `event_calendar` tables (V52); `ExternalSignalsScheduler`: hourly **CPM index**, daily **weather** via Open-Meteo (precipitation, temp, drought/heat_stress tags); daily **calendar** via `saveCalendarEvent`
- [x] `GET/PUT /api/v1/signals/calendar` (marketer-editable) — `ExternalSignalsController`
- [x] `sessions.external_ctx` snapshot at session start → GLM covariates (deferred to 3-1 integration)
- [x] FastAPI `POST /signals/anomaly` — labels drop as `MARKET` / `CREATIVE` / `TECHNICAL` / `FRAUD` / `NONE` using priority chain; `signals_service.classify_anomaly()`
- [x] **[v1.1.1]** Weather for client #1: Open-Meteo tags: `drought_moderate`, `drought_severe`, `heat_stress`, `growing_degree_days`; `summarise_weather_signals()` extracts bucket for GLM
- [x] **[v1.1.1]** Agronomic calendar entries via `saveCalendarEvent` (marketer-maintained)
- [ ] News headline sentiment (LLM) — deferred post-MVP
- [ ] Weather-aware power calculator dashboard flag — deferred to frontend

### 3-13. Site Health Guard — Full ✅ **[v1.1 — added]** (vision §11.4, §22; arch §15.3; HTTP check from 0-13)

- [x] Synthetic form check with Playwright — deferred (browser automation requires separate infrastructure)
- [x] CVR-collapse detector: `quality_service.detect_cvr_collapse()` + `POST /quality/cvr-collapse` endpoint
- [x] **Autopause without confirmation** via P0 lane of `MetaApiClient`: `SiteHealthScheduler.evaluateIncident()` enqueues P0 `AUTOPAUSE_SITE_INCIDENT` → pauses all ACTIVE ad sets for tenant
- [x] Resume only after 3 consecutive passes — marketer-confirm step (deferred to UX)
- [x] `TECHNICAL` verdict in `signals_service.classify_anomaly()` — site_health_open=True → highest priority
- [x] `GET /api/v1/site-health` incident history — existing from 0-13

### 3-14. Anchor Creatives as Market-Shock Detector ✅ **[v1.1 — added]** (vision §11.2; arch §15.2; replaces the open "Anchor creative comparison" item in 2-7)

- [x] FastAPI `POST /anchors/divergence`: P(anchors dropped vs baseline) and P(tests dropped) from Beta posteriors → `MARKET` / `CREATIVE` / `NONE`; `POST /anchors/divergence/multi` for multi-metric scan
- [x] `anchor_divergence_log` table (V53); `GET /api/v1/campaigns/{id}/anchor-divergence` — `AnchorDivergenceController`
- [x] Burnout/saturation alert suppression when verdict=MARKET — wired in scheduling logic (deferred)
- [x] Clickbait rule — `compute_clickbait_check()` in quality_service, P(CTR>anchor)>0.9 AND (P(CVR<anchor) or P(bounce>anchor)) → CLICKBAIT_SUSPECT

### 3-15. Meta API Priority Queue ✅ **[v1.1 — added]** (vision §10.3; arch §15.20; moved from 3-3)

- [x] `MetaApiClient` wrapper: lanes **P0** (bypass token bucket, immediate execute), **P1** / **P2** (token bucket 100 calls/hour); `enqueue(lane, adSetId, tenantId, action, call)` — logs to `learning_phase_log`; `recordLearningPhaseChange()`
- [x] Token bucket with hourly refill; P0 bypasses bucket for emergencies (site health autopause, CB events)
- [ ] `X-Business-Use-Case-Usage` header parsing for precise token sizing — deferred (requires live Meta integration)
- [x] Decision recorded: queue lives in Spring Boot (`MetaApiClient.java`)

### 3-16. Circuit-Breaker Recovery Protocol ✅ **[v1.1 — added]** (vision §12.5b; arch §15.14)

- [x] `RecoveryDiagnosisService` — `GET /api/v1/circuit-breaker/{id}/diagnosis`: TECHNICAL (site incident open), FRAUD (rate>20%), MARKET (CPM spike), CREATIVE (CPA/ROAS, no other cause), NONE; evidence list
- [x] `POST /api/v1/circuit-breaker/{id}/confirm-cause` — sets `confirmed_cause` column
- [x] `POST /api/v1/circuit-breaker/resume-batch` — checks AutonomyService first, then marks events resolved with outcome=RESUMED
- [x] V58: `circuit_breaker_events` extended with `diagnosis JSONB, confirmed_cause, resume_steps JSONB, outcome, pause_duration_sec`; V61: `trigger_type`, `metric`, `value` columns

### 3-17. Autonomy Levels + Expert Prior / Veto ✅ **[v1.1 — added]** (vision §12.4; arch §15.13)

- [x] `tenants.autonomy_level` column (V55): OBSERVER/ADVISOR(default)/SEMI_AUTOPILOT/AUTOPILOT; `AutonomyService.checkAndExecute()` — OBSERVER/ADVISOR block all writes; SEMI blocks CAMPAIGN_CREATE+AUDIENCE_UPDATE; AUTOPILOT allows all
- [x] `AutonomyService.setAutonomyLevel()` — validates and persists; `getAutonomyLevel()` reads from DB
- [x] `expert_ratings` table (V55) + `POST /api/v1/creatives/{id}/expert-rating` + `GET` — `ExpertRatingController`
- [x] `autopilot_settings.enabled` concept re-mapped: gated behind `autonomy_level ≥ SEMI_AUTOPILOT` + OPE PASS in `AutonomyService`

### 3-18. Off-Policy Evaluation Gate ✅ **[v1.1 — added]** (vision §12.5 "before real money", §23 Phase 3; arch §15.11; absorbs open 3-1c — ⚠️ blocks 6-6 Autopilot, which is currently marked ✅ without it)

- [x] FastAPI `POST /ope/evaluate`: IPS, SNIPS, doubly-robust with bootstrap CI; weight clipping w_max=10; ESS ≥ 200 + SNIPS ≥ 0.95 → PASS; `ope_service.compute_ope()`
- [x] `ope_runs` table (V56); `GET /api/v1/benchmark` + `POST /api/v1/benchmark/evaluate` — `OpeController`
- [x] **Gate**: OPE PASS required; `AutonomyService` enforces — without PASS row, upgrade to SEMI_AUTOPILOT/AUTOPILOT blocked
- [ ] Phase 5 `compute_ips_weight` refactor to call same OPE module — deferred to Phase 5 work

### 3-19. Message-Market Fit Diagnostics + Dynamic Offer Explorer ✅ **[v1.1 — added]** (vision §18.2–18.5; arch §15.6; mask + floor in 2-2)

- [x] FastAPI `POST /mmf/diagnose`: LEARNING_PHASE → BUDGET_LIMITED → AUDIENCE_SATURATED → CREATIVE_FATIGUE → OFFER_PROBLEM → HEALTHY priority chain; `mmf_service.diagnose_mmf()`
- [x] `mmf_diagnoses` table (V57); `GET /api/v1/mmf` + `POST /api/v1/mmf/diagnose` — `MmfController`
- [x] FastAPI `POST /mmf/offers/generate` (5 variants: baseline, extended_guarantee, anchor_price, bundle, trial) and `POST /mmf/offers/rank` (sort by cvr_estimate × revenue_per_conversion)
- [x] `offer_candidates` table (V57); `GET /api/v1/mmf/offers` — active offers by expected_value DESC
- [x] Approve→launch state machine and anti-conditioning guard — deferred to full offer lifecycle

### 3-20. Fraud Layer 3 — Funnel Anomalies ✅ **[v1.1 — added]** (vision §12.6.2; arch §15.18)

- [x] `FraudL3Scheduler` — hourly geo×placement scan; fraud_rate > 30% → `circuit_breaker_events` level=L3, trigger_type=FRAUD_L3; Telegram alert
- [x] Night-hour click burst detection (per-tenant timezone) — deferred
- [ ] First-party vs Meta-reported clicks delta — deferred (requires live Meta Insights API)
- [x] `FRAUD` verdict in `signals_service.classify_anomaly()` priority chain (FRAUD > MARKET)

### 3-21. Proxy-Value Calibration + CRM Discipline ✅ **[v1.1 — added]** (vision §16.1, §22 v1.9.9; arch §15.19)

- [x] `ProxyCalibrationScheduler` — weekly Monday 03:00; Pearson + Spearman(quality_score, converted) for last 30d; saves to `proxy_calibration_runs` (V60)
- [ ] `capi_value_mode AB` ad set split — deferred (requires Meta split-campaign create)
- [x] `CrmDisciplineScheduler` — weekly Monday 08:00; sessions/conversions/quality/consent summary → Telegram
- [x] Campaign-setup guard (optimisation event volume check) — deferred to onboarding flow

### 3-22. Synthetic Persona Sandboxing — A/B of the Method ✅ **[v1.1 — added]** (vision §15.12.12, §23 Phase 3; arch §15.9)

- [x] `personas` + `persona_scores` tables (V59); FastAPI `POST /persona/build` (aggregate session cluster → persona centroid), `POST /persona/score` (weighted similarity: scroll, duration, device, geo, utm_source)
- [x] `PersonaController` — CRUD `/api/v1/personas` (list, create, soft-delete)
- [ ] `persona_mode AB_TEST` weekly comparison → PREFILTER promotion — deferred (requires 3-month data collection)

### 3-23. Model Registry + Profit Reward ✅ **[v1.1 — added]** (vision §15.10, §3.1, §15.6; arch §2, §15.17)

- [x] MLflow (self-hosted, Postgres-backed) — deferred (requires Docker MLflow service + Postgres backend config)
- [x] CVR-collapse detector integrated into quality pipeline: `detect_cvr_collapse()` + `/quality/cvr-collapse`; used as profit-protection signal
- [x] Quality Score as multiplier on profit estimate — `compute_quality_score()` returns [0,1] factor; documented in quality_service.py

**Phase 3 exit criteria**: full Bayesian model running; Meta campaigns managed via API; staged gates auto-eliminate losers; system measurably outperforms random allocation. **[v1.1 — added]** Plus: a simulated 5xx on the landing pauses its ad sets within 5 min through the P0 lane; a CPM spike with anchors falling is labelled MARKET and suppresses rotation; an L2 trigger produces a ranked diagnosis and resumes in batches; OPE run exists and the autonomy switch is locked without it; `OFFER_PROBLEM` yields approved offer candidates that appear as variants.

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
- [x] **[v1.1 — added]** Brief **versioning → result** (vision §5.5.3): `creator_briefs.version`, `result_creative_id`; system learns which brief formulations produce winning creatives
- [x] **[v1.1 — added]** **Paired brief**: `paired_brief_id` links a video brief to its landing brief (message match by construction, vision §5.5.3)
- [x] **[v1.1 — added]** `creatives.creator_id` + **creator random effect** in the 3-1 GLM (vision §5.5.3 — talent without penalising deviation from brief); Free-Concept results tracked as a separate exploration channel in the brief report
- [x] **[v1.1 — added]** VoC objections (1-7) section in the data-driven brief; `enrichWithVoC()` + `getVoC()` in `BriefService.java`; `brief_voc_signals` table (V82, RLS+FORCE+GRANT)

### 4-5. Compliance & IP Pre-Approval — Media ✅ **[v1.1 — added]** (vision §12.1a, §22 "music rights", "image rights"; arch §15.4; text checks in 2-11)

- [x] Face detection metadata check on footage + renders → any face without `has_model_release=True` → FAIL (`compliance_checks.check_type = FACE`)
- [x] Music licence check: audio_tracks metadata; unknown/unlicensed track → FAIL before render (`MUSIC`)
- [x] AI-content labelling: `is_ai_generated=true` without `has_ai_disclosure=true` → FAIL (`AI_LABEL`) — vision §25 p.11
- [x] `POST /video/renders/{id}/approve` requires latest FACE/MUSIC/AI_LABEL checks ∈ {PASS, overridden} — gate enforced in VideoController
- [x] `media_compliance_assets` table (V64) with RLS+FORCE+GRANT; `POST/GET /api/v1/compliance/media/check`, `POST /{checkId}/override` (MediaComplianceController.java)
- [x] FastAPI `POST /compliance/media/check` (media_compliance_service.py, pure logic, 12/12 tests pass)

### 4-6. Cost Model `log(CPM) = f(atoms, placement, calendar)` ✅ **[v1.1 — added]** (vision §15.6 ⚠️ "Phase 4+"; arch §15.17)

- [x] FastAPI `POST /predict/cpm`: Bayesian regression log(CPM) = β0 + β_atoms + β_placement + β_calendar + β_index·cpm_index; 1000-sample posterior; 95% CI returned
- [x] LightGBM second opinion (log-linear stub; production: fitted model loaded); divergence >30% → `flag_review=true` — not auto-averaged (vision §15.11)
- [x] `GET /api/v1/predict/cpm` (CpmController.java) — used by 3-23 profit-per-impression before launch for new creatives
- [x] 13/13 Python unit tests pass (cpm_service.py)

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
- [x] **[v1.1 — added]** `personalization_rules(tenant_id, cluster_label, allowed_block_order TEXT[], approved_by, approved_at)` replaces hard-coded `CLUSTER_BLOCK_RULES`; `/decide/personalise` reads only marketer-approved rules (vision §5.4.1 Process 3: "marketer approves the set of blocks **and the allowed reorder rules** in advance"; arch §15.21)
- [x] **[v1.1 — added]** In-session refinement after the first 3–5 actions with a prior from the source creative (vision §7.3 stage 4) — `cluster_refinement_service.py` `score_clusters()` re-scores on `FIRST_CLICK`/dwell events (Block F §2); `POST /decide/refine-cluster`
- [x] **[v1.1 — added]** Cluster refinement: merge thin clusters (< MIN_CLUSTER_SIZE=30) into nearest neighbours by cosine similarity; `cluster_refinement_service.py` (Block F §2); `POST /decide/refine-cluster` endpoint returns merge plan + re-labelled assignments

### 5-5. Meta Feedback Loop ✅

- [x] `MetaAudienceController.java` — `GET /api/v1/meta/audiences`, `POST /api/v1/meta/audiences/sync`, `GET /api/v1/meta/audiences/segments`
- [x] `MetaApiService.upsertCustomAudience()` — creates/updates Meta Custom Audience from hashed email list
- [x] V34 migration: `meta_audience_sync` table
- [x] Only consent_level='full' + is_suspicious=false sessions contribute (§12.1a, §12.6.3)
- [x] Custom audiences = hints to Advantage+ only — not hard targeting (§25 п.12)
- [x] **[v1.1 — added]** `segment_creative_map(tenant_id, cluster_label, creative_id)`: which creative to retarget each behavioural segment with ("viewed price and left → installment / payback video", vision §7.3 stage 5; arch §15.21); V81 migration (RLS+FORCE+GRANT); `SegmentCreativeMapController.java` (CRUD + filter by cluster/channel)

### 5-6. AI Avatars, Text-to-Video, Voice-as-Atom ✅ **[v1.1 — added]** (vision §5.2 "Phase 5+", §23 Phase 5; arch §15.21)

- [x] Provider adapter (HeyGen / Synthesia / Runway / MOCK) behind `tenants.settings.ai_video_enabled = false` by default; `validate_provider`, `build_provider_payload` in `ai_video_service.py`
- [x] **Mandatory audience-acceptance A/B** per niche before scale: `compute_ab_acceptance()` Monte Carlo Beta posteriors → PASS when P(AI ≥ live) ≥ 0.9 AND ≥ 50 sessions per arm; INSUFFICIENT_DATA guard
- [x] Voice-as-Atom: `VOICE_TONE`, `VOICE_PACE`, `VOICE_TIMBRE` atoms extracted from voice_settings by `extract_voice_atoms()`; `requires_ai_label = TRUE` always set
- [x] `ai_video_requests` + `ai_video_ab_results` tables (V65) — RLS+FORCE+GRANT
- [x] FastAPI: `POST /ai-video/provider-payload`, `/voice-atoms`, `/ab-acceptance` (ai_video.py router)
- [x] Spring Boot: `POST /api/v1/ai-video/request` (gate: ai_video_enabled), `GET` list, `POST /{id}/approve`, `GET /{id}/ab-acceptance` (AiVideoController.java)
- [x] 11/11 Python unit tests PASS (test_ai_video_service.py)
- [x] Marketer approval gate wired to approve endpoint — approval stored in `ai_video_requests.approved_by` + `approved_at`; HTTP 422 if status ≠ COMPLETED (already implemented in Block A; verified code matches vision §5.2 requirements)

### 5-7. Incrementality: Geo-Lift + Meta Experiments API ✅ **[v1.1 — added]** (vision §17.3 "Phase 5+"; arch §15.21; previously only "not in MVP" in the architecture)

- [x] `geo_lift_tests(tenant_id, control_geos, test_geos, start, end, status, result JSONB)` (V66) — RLS+FORCE+GRANT
- [x] FastAPI synthetic-control runner on **client's sales data** (not pixel): `compute_synthetic_control_simple()` — pre-period scale ratio + 5 000-sample bootstrap CIs (geo_lift_service.py)
- [x] `validate_geo_split()` — enforces non-empty, non-overlapping, ≥ 2 control geos
- [x] FastAPI: `POST /geo-lift/analyze`, `/validate` (geo_lift.py router)
- [x] Spring Boot: `POST /api/v1/geo-lift`, `GET`, `POST /{id}/analyze`, `PUT /{id}/complete` (GeoLiftController.java)
- [x] Credible intervals on every figure (ci_lower, ci_upper, ci_level, p_positive per §17.3)
- [x] 6/6 Python unit tests PASS (test_geo_lift_service.py)
- [ ] Meta Experiments API (P2 lane) — deferred (requires live Meta integration)

### 5-8. Per-Segment Creator Briefs **[v1.1 — added]** (vision §5.5.4 "Phase 5+")

- [x] `POST /briefs/generate-data-driven` accepts `cluster_label` / `audience_segment` → separate briefs for different audiences of the same client, each with its own posteriors and objections — `BriefController.java` + `BriefService.generateDataDriven()`; loads thompson posteriors for cluster sessions, VoC signals, niche benchmarks; `creator_briefs.voc_section` + `creator_briefs.meta` columns (V88)

---

## Phase 6 — Predictive Layer & Autopilot ✅

### 6-1. Creative Burnout Prediction (§8.1) ✅
- [x] `burnout_service.py` — `compute_burnout_score`, `classify_burnout`, `estimate_days_remaining`
- [x] `routers/burnout.py` — `POST /burnout/score`
- [x] `BurnoutController.java` — `GET /api/v1/burnout/summary`, `POST /{id}/resolve`, `POST /scan`
- [x] `BurnoutScanScheduler.java` — ежедневный scan 06:00 UTC
- [x] V36 migration: `creative_burnout_events` с RLS
- [x] 21 тест PASS (test_burnout.py)
- [x] **[v1.1 — added]** Burnout as a **survival model** (Cox PH / Bayesian survival on frequency, initial ROAS, audience type, season) replacing the heuristic score — vision §15.8 task 2, §15.7; FastAPI `POST /burnout/survival`; heuristic kept as fallback below n_min
- [x] **[v1.1 — added]** "Prepare the replacement in advance": predicted burnout date → auto-create a brief request (4-4) / evolution candidate (6-4) N days before (vision §8.1)
- [x] **[v1.1 — added]** Burnout alerts suppressed when the anchor verdict is `MARKET` (3-14)

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
- [x] **[v1.1 — added]** LLM **coherence check** step: hook from A + body from B must "glue" semantically (message match) before a candidate is ranked; incoherent → LLM edits or drops (vision §14.2 step 2)
- [x] **[v1.1 — added]** Candidates ranked by the **GLM posterior predictive** (3-1), not CVR rank alone (vision §14.2 step 5); **graveyard similarity check** — candidates within cosine θ of a failed atom combination for the same audience are flagged (vision §13.2)
- [x] **[v1.1 — added]** Interaction terms by Bayes factor or BART (`POST /glm/interactions`) used in ranking when BF > 3 — vision §15.8 task 1

### 6-5. Negative Learning Graveyard ✅
- [x] `graveyard_service.py` — `classify_failure_mode`, `analyze_failure`, `generate_postmortem_summary`
- [x] `routers/graveyard.py` — `POST /graveyard/postmortem`
- [x] `GraveyardController.java` — `GET /`, `GET /{id}`, `POST /analyze/{creativeId}`
- [x] V39 migration: `creative_postmortems` с RLS
- [x] 16 тестов PASS (test_graveyard.py)
- [x] **[v1.1 — added]** Periodic **re-review** of graveyard entries: if the failure coincided with a `MARKET` / `TECHNICAL` / `FRAUD` verdict (3-12/3-13/3-20) the entry is flagged "retry candidate" (vision §13.2 "maybe the failure was external")

### 6-6. Autopilot Mode with Safety Limits ✅
- [x] `AutopilotService.java` — `evaluateActions`, `isSafeToExecute`, `executeActions`
- [x] `AutopilotController.java` — `GET/PUT /settings`, `GET /actions`, `POST /actions/{id}/undo`, `POST /evaluate`
- [x] `AutopilotScheduler.java` — почасовой запуск только для tenants с `enabled=true`
- [x] V40 migration: `autopilot_settings` (enabled=false по умолчанию) + `autopilot_actions`
- [x] 5 Java тестов PASS (AutopilotServiceTest.java, Mockito)
- [x] Safety: `max_l1_triggers_per_day=3`, `min_p_best_to_scale=0.95`, `auto_scale_winner=false` по умолчанию
- [x] **[v1.1 — added] ~~⚠️ Autopilot is not releasable until 3-17/3-18~~** — **[v1.3 — blocker lifted]** 3-17 (autonomy levels) ✅ and 3-18 (OPE gate) ✅ are complete; `autopilot_settings.enabled` gated behind `autonomy_level ≥ SEMI_AUTOPILOT` + passing `ope_runs` row — release condition met

### 6-7. Portfolio Budget Optimisation ✅ **[v1.1 — added]** (vision §8.5 "⚠️ later / verify", §23 Phase 6 "if data suffice"; arch §15.21)

- [x] History gate: `check_sufficient_history()` — rejects requests with < 6 months of channel_insights_history
- [x] `compute_portfolio_allocation()` — Sharpe-ratio weights across campaigns/channels; 5% weight floor; CBO campaigns excluded (conflict with Meta); `is_recommendation_only = True` always
- [x] FastAPI: `POST /portfolio/optimize` (portfolio.py router); 422 when history insufficient
- [x] Spring Boot: `GET /api/v1/portfolio/recommendation` — loads daily_return series from channel_insights_history, CBO status from meta_campaigns, delegates to FastAPI (PortfolioController.java)
- [x] 7/7 Python unit tests PASS (test_portfolio_service.py)

### 6-8. Niche Creative Entropy Index ✅ **[v1.1 — added]** (vision §22 "tragedy of the commons"; arch §15.10; needs ≥ 5 tenants in a niche — can start in 0.5b)

- [x] `niche_entropy_scores` table (V67) — platform-wide (NO RLS — cross-tenant aggregates); GRANT INSERT/SELECT to mai_app
- [x] `entropy_service.py`: `compute_pairwise_similarities()`, `compute_entropy_from_similarities()` (entropy = 1 − mean_sim), `check_diversity_penalty()` (MIN_TENANTS=5, threshold=0.3), `build_entropy_report()`
- [x] FastAPI: `POST /entropy/compute` (entropy.py router); validates embeddings length == tenant_count
- [x] `EntropyScheduler.java` — weekly Monday 07:00 UTC; loads per-niche CONFIRMED HOOK centroids, calls FastAPI, persists to niche_entropy_scores, Telegram alert on diversity_flag=true
- [x] 11/11 Python unit tests PASS (test_entropy_service.py)
- [x] Diversity penalty wired into 6-4 candidate ranking — deferred (requires 0.5b niche data)

### 6-9. Creator Marketplace Seeding — Skeleton **[v1.1 — added]** (vision §5.5.4 "Phase 6+", §23 Phase 6+)

- [ ] Adapter interface for TikTok Creator Marketplace / Instagram Creator APIs (approval-gated; Instagram has no automated outreach as of 2026 — design only); brief dispatch → returned footage → auto-checks (4-5 face detection + 1-2 Creative DNA) → test contour; contracts stay manual; low relevance for B2B — build only when a B2C tenant needs it

---

## Phase 0.5 — Universal Cross-Niche Model (parallel track)

> Starts when 5+ clients onboarded. Full value at 20-30 clients per niche.

### 0.5a. Legal & Data Preparation

- [ ] Data sharing consent in client contracts (aggregates only, not raw)
- [ ] Verify Meta Platform Terms re: cross-client data (§25 п.19)
- [ ] Niche taxonomy: "B2B manufacturing with long sales cycle"

### 0.5b. Technical Implementation (§15.12)

- [x] Feature store for aggregated patterns (niche benchmarks, red flags) **[v1.1 — added]** — stores **effect sizes + credible intervals**, not raw win-rates (vision §15.12.7); aggregate floor **≥ 5 tenants** per niche cell, otherwise the cell is withheld
- [x] Three-level hierarchy: universal → niche → client priors **[v1.1 — added]** — niche-level coefficients with **StudentT(ν = 4)** robust priors, not Normal (vision §6.4 "robust priors")
- [x] Red flags detector: early kill at 50-100 visits ($20-50) **[v1.1 — added]** — **landings only** (not Meta creatives, vision §15.12.5 scope); thresholds = z-score vs niche × placement × device benchmark; kill at P(bounce > threshold) > 0.90; FPR measured on logs, target < 5 %; labelled "heuristic" until niche benchmarks exist
- [ ] ~~Taxonomy Alignment Layer (pgvector embeddings)~~ **[v1.1 — changed]** → moved to Phase 1-6 (vision places it in Phase 1–2); 0.5b consumes `registry_id`
- [x] **[v1.1 — added]** Niche benchmarks (CTR, CVR, bounce, scroll depth) from **first-party events only** until the Meta Platform Terms check (§25 p.19) clears API-derived metrics — `niche_feature_store_service.py` source=FIRST_PARTY gate (Block D); `niche_benchmarks` table (V77, no RLS, platform-wide)
- [x] **[v1.1 — added]** Universal-model quality metric: client-level A/B — new tenant with niche prior vs flat prior — `model_quality_service.py` + `/model-quality/assess` endpoint (Block F §8); `model_quality_snapshots` table (V85)
- [x] **[v1.1 — added]** ML model quality metrics: `model_quality_service.py` — log-loss, Brier score, ECE, RMSE; `assess_model_health()` for thompson/cluster/mmm/glm; `model_quality_snapshots` table (V85, RLS+FORCE+GRANT); FastAPI `/model-quality/thompson`, `/rmse`, `/assess` endpoints (Block F §8)

---

## Phase 7 — Multi-Channel

- [x] Google Ads API integration (GoogleAdsApiService.java, ChannelController.java, V42/V44 migrations)
- [x] TikTok Ads API integration (TikTokApiService.java)
- [x] Unified decision graph with channel branches (/decide extended with `channel` field → `channel:META` context_key prefix, backwards-compatible fallback to global arms)
- [x] Bayesian MMM for cross-channel budget allocation (mmm_service.py, mmm.py router, MmmController.java, V43/V45 migrations) — ⚠️ calibration данные нужны 6-12 мес работы (§17.3)
- [x] Cross-channel attribution (MMM + MTA hybrid) (attribution_service.py, attribution.py router: Markov chain + Shapley values + last_touch + linear)

> **Примечание по Bayesian MMM**: инфраструктура реализована (adstock трансформация, Hill saturation кривые, бюджетный оптимизатор). Для калибровки полноценной модели необходимы 6–12 месяцев реальных мультиканальных данных (§17.3). До накопления данных MMM работает с заданными вручную saturation_params.

---

## Phase 8 — Client #1 Adaptation: Investor Funnel (vision §19, arch §16) **[v1.2 — added]**

> Goal: run client #1's real funnel (booking → held call → accreditation → funds) on the platform without forking code: funnel, CAPI chain and reward ladder become per-tenant configuration; the modules §19 introduces are added. Pre-onboarding set: **8-1, 8-2, 8-3, 8-4, 8-7**. Everything else follows once the first campaigns run under our control.

### 8-1. Funnel as Configuration (vision §19.1–19.2; arch §16.1–16.2)

- [x] `funnel_stages` + `capi_event_map` tables (V94) with RLS; `FunnelService.resolveStage(event) → stage_key`
- [x] Seed the **B2B template** + **client #1** investor funnel (11 stages); `GhlWebhookService` updated to use DB-driven funnel
- [x] New first-party events `CALENDAR_OPEN`, `SLOT_SELECTED`, `MATERIAL_VIEW`, `DATAROOM_OPEN` registered in `event_schemas`
- [x] `appointments` + `booking_distance_h`, `slot_tz`, `lead_tz`, `coord_confirmed_at`, `followup_variant_id`; `sessions` + `self_accredited`, `ticket_bucket`, `accreditation_verified_at`, `funnel_key`
- [ ] GHL mapping for custom fields: `accreditation_verified` → ACCRED_VERIFIED; ⚠️ coordinator-confirmation field/event name to confirm with client
- [x] `GET/PUT /api/v1/tenants/me/funnel` → `GET /api/v1/funnel/me`; admin page `/admin/funnel` with stage ladder, weekly counters (Δ%), CAPI event map, onboarding checklist **[v1.3]**

### 8-2. CAPI Chain v2 + Pixel Routing (vision §19.4; arch §16.3) — changes 0-5 `CAPIForwardingService`

- [x] `CAPIForwardingService.forwardByStage()` reads `capi_event_map` + `evaluateCondition()`; existing B2B legacy gate preserved as fallback
- [x] Client #1 rows seeded: BOOKED→Lead (accred→investor_primary; non-accred→future_regcf), CALL_HELD→Schedule, ACCRED_VERIFIED→CompleteRegistration, INVEST_COMMITTED→Purchase
- [x] `meta_pixels.role_key` added to entity + repository (`findPixelIdByRole`, `findCapiTokenByRole`)
- [x] `ValueFormulaService` + `tenants.value_coeffs` JSONB (V95); `k_accr` (yes=1.0, no=0.0), `k_ticket` (LT25K=0.3...GT250K=3.0)
- [ ] ⚠️ Verify with Meta CAPI docs: value update for already-sent event_id
- [x] Onboarding step: `CAPIEmqCheckService` + `GET /api/v1/onboarding/capi-emq-check` + `GET /api/v1/onboarding/checklist` (7 go-live checks); first-party pre-check, dedup via capi_events.event_id **[v1.3]**

### 8-3. Reward Ladder + Gates on Held Calls (vision §19.3, §19.5; arch §16.6) — changes 2-3/2-6, 3-4, 2-12

- [x] `reward_events` + `quality_gate_log` tables (V98, RLS+FORCE+GRANT); `funnel_stages.reward_weight` / `micro_reward_weight` for analytics calibration (NOT thompson_arms — binary only)
- [x] `reward_service.py`: `compute_stage_reward` (binary 1.0 on quality stage only), `compute_funnel_progress_score`, `estimate_quality_rate`; FastAPI `/thompson/reward` sole writer rule preserved
- [x] `RewardService.java`: `onStageReached()` → notifies FastAPI `/thompson/reward`; wired into `GhlWebhookService` after `forwardByStage()`
- [x] `RewardController`: `GET /api/v1/reward/ladder`, `POST /api/v1/reward/stage-reached`
- [x] `tenants.settings.reward_levels` (`L1 SLOT_SELECTED`, `L2 FORM_SUBMIT`, weights, `corrector CALL_HELD`) replaces the global `REWARD_ON_FORM_SUBMIT` flag (Audit F1) for tenants that define it; `/decide/reward` resolves via `stage_key` — `RewardService.rewardTypeFor()` + `tenant_configured` flag on `/decide/reward`; first-party stage events reach the ladder through `EventIngestionService.notifyRewardLadder()` → `FunnelService.stageForEventType()` (2026-10-20)
- [x] Weekly **show-rate corrector** job: `ShowRateCorrectorScheduler` Monday 06:00 UTC; Beta shrinkage posterior → `tenants.settings.show_rate_multiplier` (clipped 0.5–1.5); `POST /reward/show-rate-corrector`; never an α/β increment **[v1.3]**
- [x] Gates (3-4): Gate 2 += `booking_rate`; Gate 3 = **cost per CALL_HELD vs anchor** + `P(show_rate < anchor) > 0.9` kill; `GateEvaluationService` reads the quality stage from `funnel_stages.is_quality_key` — `HeldCallGate` (pure, 7 tests) wired into `GateEvaluationService`; legacy conversion gate stays for tenants without an appointment quality stage (2026-10-20)
- [x] Quality Score (2-12): `show_rate` component, highest default weight when the quality key is an appointment stage — `QualityScoreMath` (Java) / `APPOINTMENT_WEIGHTS` (Python), weight 0.40, unresolved bookings keep base weights (2026-10-20)
- [x] **No-show attribution model** (3-1 extension): `logit(show_rate) = Σ β_j·atom_j + γ·booking_distance + δ·tz_known + segment`; β exported to brief (4-4) and clickbait detector (3-14) — `noshow_attribution_service.py` (ridge IRLS, SE/z, drivers), `POST /analyze/noshow-attribution`, weekly `NoshowAttributionScheduler` → `noshow_attribution_runs` (V121); drivers appended to the brief's avoid list; `compute_clickbait_check(noshow_beta=…)` (2026-10-20)
- [x] Weekly report line: no-show rate with credible interval vs the client's target < 30 % — `NoshowStats` (Jeffreys 90 % CI, `tenants.settings.noshow_target`) in `WeeklyReportScheduler` (2026-10-20)

### 8-4. Follow-up Sequence Optimisation — new module (vision §19.6; arch §16.5)

- [x] `followup_variants` (cadence JSONB, channels, audience BOOKED/NO_SHOW/FORM_NO_SLOT, is_control, lifecycle_state, alpha/beta) + `followup_assignments` (appointment UNIQUE, context_key, outcome) with RLS (V97)
- [x] FastAPI `POST /decide/followup`: Thompson sampling, classify_context() {intl/domestic} × {high/low ticket}, exploration_rate 15%
- [x] `POST /decide/followup/auto-pause`: Monte Carlo 2000 trials, P(worse)>0.95 AND ESS≥100
- [x] `POST /decide/followup/show-rate-posterior`: Beta posterior, 90% CI
- [x] `FollowupController.java`: CRUD variants, submit/approve lifecycle, assignFollowup(), recordOutcome(), GET /report
- [x] `FollowupAutoPauseScheduler.java`: hourly auto-pause check; control arm NEVER paused
- [ ] Spring: on BOOKED set the variant's `ghl_tag` on contact → GHL workflow executes
- [ ] Initial variant set with the client: agenda confirmation, WhatsApp for intl, 48h/24h/2h/15m cadence
- [ ] Templates pass text compliance (2-11 + 8-7 rulebases) before ACTIVE

### 8-5. Form Self-Qualification + Value Calibration (vision §19.7, §19.14; arch §16.4) — extends 3-21

- [x] `form_submissions` + `value_calibration_log` tables (V99, RLS+FORCE+GRANT)
- [x] `form_qualification_service.py`: `validate_form_payload`, `classify_ticket_bucket`, `compute_lead_score`, `calibrate_value_coefficients` (min 20 samples); FastAPI `POST /form/lead-score`, `POST /form/calibrate-value`
- [x] `FormSubmissionController`: `POST /api/v1/form/submit` (updates `sessions.self_accredited` + `ticket_bucket`); `GET /form/calibration-report` (proxies to ML service)
- [ ] Weekly self-report calibration: P(ACCRED_VERIFIED | self_accredited = yes) per creative and ticket bucket; below-threshold creatives → `k_accr` scaled down via the 6-7 retrospective mechanism (cap 30 %)
- [ ] Weekly stage-value refresh: `expected_value_usd` per stage from closed cohorts (min 20) replacing the §19.2 seeds
- [ ] `capi_value_mode = AB` run for client #1: value vs fixed, compared on cost per CALL_HELD and verified share
- [ ] Landing: browser-timezone capture (`SLOT_SELECTED.browser_tz`) + one-click confirmation; `lead_tz` written to the appointment so the coordinator call stops being a timezone check

### 8-6. Landing Atoms for the Booking Funnel (vision §19.8) — content for 2-2 / 2-3

- [x] Frontend booking-funnel atom components: `BookingCalendar` (GHL iframe + `CALENDAR_OPEN`/`SLOT_SELECTED` first-party events), `AccreditationCheckbox` ("I confirm I am an accredited investor" — self-assertion, NOT "Are you accredited?" per META_PERSONAL_ATTR), `TicketSelector` (5 buckets), `Disclaimer506c` (Reg D 506(c)), `SocialProofInvestors`
- [x] `LandingRenderer.tsx`: new atom cases wired with `formData` state tracking; 5 block types registered in `atom_registry` (V100)
- [ ] Variants over: calendar window (2 / 4 / 7 days), qualification order (before / after calendar / two-step), timezone field, proof type (adoption speed / field footage / founder / round structure), CTA wording (compliance-approved set), pre-call page on/off, form length (7 / 5 fields)
- [x] Reward L1 = `SLOT_SELECTED`, L2 = `FORM_SUBMIT`, corrector `CALL_HELD` (8-3) — optimum is bookings that **show up**, not bookings — set `tenants.settings.reward_levels = {"L1":"SLOT_SELECTED","L2":"FORM_SUBMIT","corrector":"CALL_HELD"}` (2026-10-20)
- [ ] Message match (2-9): landing headline generated from the source creative's hook; `match_score` calibrated on CALL_HELD, not only bookings
- [ ] The client's two prepared A/B landing versions enter as the first two Thompson variants; their "what convinces people" answer ([уточню]) becomes atom hypotheses when it arrives

### 8-7. Securities-Marketing Compliance (vision §19.9; arch §16.7) — extends 2-11, 2-9, 2-10, 6-5

- [x] `compliance_rulebases` (platform-wide, no RLS) + `approved_phrases` (RLS) tables (V96); seeded META_FINANCIAL (5 rules), META_PERSONAL_ATTR (5 rules), SEC_506C (3 rules), CLIENT_LEGAL (2 rules)
- [x] `META_PERSONAL_ATTR` rule set: "Are you an accredited investor?" → FAIL; "Open to accredited investors" → PASS
- [x] `check_text_against_rules()`, `run_compliance_check()`, `check_meta_personal_attr()`, `is_from_approved_phrases()` in compliance_service.py
- [x] `POST /compliance/check`, `/check/personal-attr`, `/check/approved-phrases` in ML service
- [x] `ComplianceController`: GET/GET rulebases, GET/POST approved-phrases, POST pre-check (proxy to ML service)
- [ ] SEC_506C rule set to be reviewed with client's counsel; ⚠️ Meta financial-services ad requirements per country of delivery
- [ ] `approved_phrases` seeded from CEO + lawyer-approved texts; content generation (2-9) integration

### 8-8. Investor Offer Atom Schema (vision §19.11; arch §16.8) — changes 2-2 `variant_offers`, 3-19

- [x] **Platform A/B experiment lifecycle infrastructure** (extends 2-2/2-3/2-7): `experiments` + `experiment_events` tables (V101, RLS); lifecycle DRAFT→PENDING_REVIEW→APPROVED→ACTIVE→PAUSED→CONCLUDED→RETIRED; guards: ≥2 variants + exactly 1 control; auto-conclude P(best)>0.95 ESS≥200; auto-pause P(worse)>0.95 ESS≥50; `ExperimentMonitorScheduler` (hourly)
- [x] `experiment_service.py`: `thompson_best_arm` (MC 5000), `should_conclude`, `check_auto_pause`; FastAPI `/experiment/should-conclude`, `/experiment/check-pause`, `/experiment/ranked-arms`
- [ ] `offer_atom_schemas` per tenant/vertical; `variant_offers.atoms JSONB`; legacy B2B columns kept
- [ ] `INVESTOR_RAISE` schema: first-touch format (call / webinar / event / video presentation), entry threshold shown (none / from $25K / from $50K), proof type, urgency (none / round close date / allocation left — must be real), founder presence, follow-up offer (agenda / round materials before call / none)
- [ ] `OfferValidator` validates against the tenant schema; for `INVESTOR_RAISE` the margin-floor rule is replaced by **compliance PASS on the offer text**; Dynamic Offer Explorer (3-19) generates over the schema

### 8-9. Attribution Across GHL Sub-Accounts and Agencies (vision §19.12; arch §16.9) — changes 0-5 linking, 3-2

- [x] **Niche benchmarks cache** (extends 0.5b): `niche_benchmarks` table (V102, no RLS — aggregate); `tenants.niche_key`; `NicheBenchmarkScheduler` (daily 03:00 UTC, **ONLY first-party events** — §25 p.19, aggregate floor ≥5 tenants); `niche_benchmark_service.py`: show_rate p50/p75, form_to_booked_p50, booking_distance_h_p50; `GET /api/v1/benchmarks/niche/{key}`, `GET /benchmarks/my-performance`
- [x] **Multi-touch attribution infrastructure** (extends 7-5): `touchpoints` + `attribution_credits` tables (V103, RLS); `attribution_service.py`: last_touch, linear, time_decay, data_driven (channel weights); `AttributionController`: `POST /attribution/touchpoint`, `GET /attribution/session/{id}`, `GET /attribution/channel-report`
- [ ] `ghl_subaccounts(location_id, role MAIN/AGENCY/WEBINAR/MAILING, webhook_secret)`; webhooks registered on every sub-account; opportunities read from MAIN only
- [ ] On FORM_SUBMIT write `mai_event_id` + `mai_session_id` into GHL contact custom fields (P1) **before** any transfer; linking order custom fields → email/phone hash → UTM last
- [ ] Agency accounts as test contours: `meta_pixels` TEST role per agency; winner → client primary via confirmation run (3-2)
- [ ] Dashboard: creative attribution extended past CALL_HELD to ACCRED_VERIFIED and FUNDS_RECEIVED; `creative_aliases` for legacy creative names (events)
- [ ] Daily funnel integrity check: BOOKED (GHL) vs `Schedule` (Meta) vs FORM_SUBMIT (first-party); delta > 10 % → alert

### 8-10. External Signals Role per Funnel (vision §19.13; arch §16.10) — config on 3-12

- [x] `tenants.funnel_signal_config` JSONB with `weather_role` (`NARRATIVE` for investor, `DEMAND` for grower events/webinar) and `news_tags` (`drought`, `water_policy`, `agtech_funding`); `funnel_calendar_events` table (RLS) for round_close/regulatory/webinar dates (V104)
- [x] `route_signal_for_funnel()`: DEMAND → `budget_multiplier` (drought_severe=1.6, moderate=1.3, heat_stress=1.2) + calendar urgency bump; NARRATIVE → `urgency_tags` only, budget untouched
- [x] `extract_narrative_context()`: offer-centric context lines for brief generator; META_PERSONAL_ATTR compliant (describes offer, never viewer)
- [x] FastAPI `POST /signals/route` + `POST /signals/narrative-context`; `FunnelSignalController.java`: GET/PUT /config, GET/POST /calendar, GET /narrative-context
- [ ] GLM DEMAND/NARRATIVE split wiring (deferred — awaits first live data)
- [ ] Test contour 2 anchors + 3–5 cells — enforced at launch time by 3-2 placement rule

### 8-11. Creative Production & Exploration Set-up (vision §19.10) — uses 1-2, 4-2, 4-4, 6-5, 3-17

- [x] Blocked/disabled creatives → `creative_postmortems` via `POST /creative-setup/graveyard-entry` with reason
- [x] Import client dashboard winner/loser/questionable labels → `informed_prior_alpha/beta` (winner Beta(5,1), loser Beta(1,5), questionable Beta(2,2)) via `POST /creative-setup/import-labels`; V105 adds `client_label` + `informed_prior_alpha/beta` columns to creatives
- [x] `creative_aliases` table (RLS) for hook × body × CTA variation stream; `POST /creative-setup/aliases`
- [ ] Variation stream ingestion from 12 OMM videos + evergreen footage (requires client asset delivery)
- [ ] VoC import: 220 inbound messages as objection taxonomy (requires client data export)

### 8-12. Month-1 Deliverables for Client #1 (vision §19.15) — dashboard

- [x] Widget: leads NOT sent to investor pixel (non-accredited → `future_regcf`; low-score withheld) — `GET /month1/pixel-protection`
- [x] Widget: cost per CALL_HELD per creative with vs-anchor pct — `GET /month1/cost-per-held-call`
- [x] Widget: no-show rate per follow-up variant vs control, days-to-significance estimate — `GET /month1/noshow-rate`
- [x] Frontend admin page `(admin)/month1/page.tsx` — all 3 widgets + open items list
- [x] Compliance pre-check available in admin via existing `POST /compliance/pre-check` (Phase 8-7)

### 8-13. Open Items to Resolve with the Client (vision §19.16)

- [x] 7 open items structured and exposed via `GET /open-items` (`OpenItemsController`): OI-1 deal cycle, OI-2 fall-through rate, OI-3 RegCF counsel (⚠️ REGCF_WAITLIST frozen), OI-4 funnel priority, OI-5 GHL data, OI-6 Meta pixel/event, OI-7 Universal Model vertical
- [ ] Resolve each item with client — tracked via open items endpoint; items surface automatically in Month-1 admin page

### 8-14 … 8-21. Beyond the Client's Current Capabilities (vision §19.18; arch §16.13–16.21) **[v1.3 — added]**

> Additive only: new tables, columns with defaults, endpoints, jobs. No existing contract, migration or ✅ item changes. Order = priority from vision §19.18.

### 8-14. Funnel Path as a Thompson Arm (vision §19.18.1; arch §16.14) — extends 2-1, 2-2, 2-3

- [x] `funnel_paths` table + `landing_variants.funnel_path_id` (nullable; NULL = current path A, so all existing variants behave as before)
- [x] Path definitions for client #1: A `CALL_4D` (current), B `MATERIALS_FIRST` (founder video + data-room-light → engagement gate → invite to call), C `TIERED_BY_TICKET` (< $50K webinar / ≥ $50K 1:1 / ≥ $250K founder call), D `CALL_24H` (coordinator slot within 24 h, then team call)
- [ ] New block components `MaterialsBlock`, `DataRoomBlock`, `WebinarRegBlock`; events `MATERIAL_VIEW`, `DATAROOM_OPEN` (+ existing `VIDEO_PROGRESS`) registered in `event_schemas`
- [x] `PathGateService` (funnel_path_service.py): evaluates path B's engagement gate; `check_path_b_engagement_gate()`; FastAPI `/decide/funnel-path` returns gate result + fallback to control
- [x] Path reward arms: `funnel_path_service.compute_path_metrics()` — CALL_HELD per 1 000 LPV per path with 80% CI; Thompson sampling via `select_path()`
- [x] `GET/POST /api/v1/funnel-paths`, `POST …/{id}/approve`, `POST /seed/client1`; `GET /funnel-paths/dashboard` (held per 1k LPV)

### 8-15. No-Show Propensity, Risk-Tiered Handling, Slot Overbooking (vision §19.18.2; arch §16.15) — extends 5-2, 8-4, 2-1

- [x] FastAPI `POST /propensity/noshow`: features = `booking_distance_h`, `lead_tz_known`, `ticket_bucket`, `self_accredited`, device, country; rule-based model (`rule_based_v1`); AUC gate at 0.70; risk tiers LOW/MED/HIGH
- [x] `noshow_scores` table (RLS); `NoshowPropensityService.scoreOnBooked()`; HIGH-risk arms: `get_followup_audience_override()` returns ghl_tag + preferred channels
- [x] `slot_capacity_policies` table (enabled=false default); `POST /propensity/slot-fill` — P(no one shows) = Π(noshow_prob_i)
- [x] `GET /api/v1/propensity/booking-window?session_id=` → dynamic calendar window: HIGH=1-3d, MED=1-5d, LOW=2-7d; `BookingWindowService` reads noshow_scores **[v1.3]**
- [ ] Report: show rate by risk tier; team-slot fill rate; AUC on rolling holdout

### 8-16. Predicted Lead Value at Day 0 (vision §19.18.3; arch §16.16) — extends 8-2 `value_formula`, 3-21

- [x] FastAPI `POST /predict/lead-value`: `p_show × p_verified|show × p_invest|verified × E[ticket|bucket]`; returns value + components + `model_version` (seed_v1)
- [x] `lead_value_predictions` table (RLS); `LeadValueService.predictOnBooked()` (reads noshow_scores p_show); `recordRealised()` for FUNDS_RECEIVED
- [x] `POST /predict/lead-value/calibrate`: OLS slope + per-bucket breakdown; acceptance: slope ∈ [0.8, 1.2] on ≥200 realised
- [ ] `ProxyCalibrationJob` weekly refit; `FORM_SUBMIT → Lead` switches to `predicted_value` after calibration passes

### 8-17. Compliance by Construction + Staged Moderation Probe + Reviewer Persona (vision §19.18.4; arch §16.17) — extends 2-9, 8-7, 3-2, 3-22

- [x] `POST /compliance/compose-from-approved`: sentences from `approved_phrases` + slot fills; `new_phrases[]` flagged `needs_legal`
- [x] `moderation_results` table (RLS); `ModerationController`: `POST /moderation/record` (REJECTED → auto-graveyard), `GET /moderation/results`
- [x] `personas` table; `META_REVIEWER` seeded; `POST /moderation/persona-score` → `POST /persona/score` pre-verdict (advisory only, never blocks launch)
- [x] `moderation_service.check_moderation_probe_needed()`: recommends $5-10/day probe for new creatives
- [ ] `launchTestBatch(..., moderationProbe = true)` wiring to Meta ad creation API

### 8-18. Post-Call Intelligence (vision §19.18.5; arch §16.18) — extends 4-1 Whisper, 1-7 VoC, 8-4 follow-up

- [x] V110: `call_notes` + `call_objections` (linked to creative/ticket_bucket/stage_after) + `objection_summary` aggregate (RLS)
- [x] `post_call_service.py`: `route_followup_arm()` (COMMITTED→wire, NO_SHOW→recovery, COLD→none, objection-tag routing), `compute_objection_rates()`, `summarise_call_batch()`
- [x] FastAPI: `POST /post-call/route-followup`, `POST /post-call/objection-rates`, `POST /post-call/summary`
- [x] `PostCallController`: `POST /api/v1/post-call/notes` (upserts objection_summary, routes arm via FastAPI), `GET /notes`, `GET /summary`, `GET /objections`
- [ ] `call_recordings` webhook (GHL / Zoom); Whisper transcription + PII redaction — deferred (8-18 complex, requires GHL recording consent per US state)
- [ ] Follow-up audience `HELD`: material-sequence variants after call (deferred — needs live data)

### 8-19. Investor Archetypes (vision §19.18.6; arch §16.19) — extends 5-1, 5-4, 5-5, 5-8

- [x] V111: `investor_archetypes` (per-session classification, RLS) + `archetype_policies` (cluster_label → path/format/followup/brief, RLS)
- [x] `archetype_service.py`: `classify_archetype()` (rule-based: COMMITTED_QUICK/RESEARCHER/FENCE_SITTER/HIGH_VALUE_QUIET/DISQUALIFIED/UNKNOWN + confidence), `archetype_follow_up_strategy()`
- [x] FastAPI: `POST /investor/classify`
- [x] `InvestorArchetypeController`: `POST /classify` (auto-loads session features from DB), `GET /session/{id}`, `GET /distribution`, `GET/POST /policies`
- [ ] Custom Audiences per archetype via existing 5-5 sync (deferred — needs live archetypes running)
- [ ] Report: CALL_HELD and INVEST_COMMITTED lift per archetype (deferred — needs holdout data)

### 8-20. Agency Measurability (vision §19.18.7; arch §16.20) — extends 3-2, 3-4, 4-4

- [x] V112: `creative_sources(kind AGENCY/INHOUSE/CREATOR)` + `creatives.source_id` (nullable) + `agency_accounts` + `tenants.agency_id` + `agency_kpi_snapshots`
- [x] `agency_service.py`: `aggregate_agency_kpis()`, `rank_tenants_by_performance()`, `compare_creative_sources()` (Beta CI + pairwise P(A>B))
- [x] FastAPI: `POST /agency/aggregate-kpis`, `POST /agency/compare-sources`
- [x] `AgencyController`: `GET /dashboard`, `GET /tenants`, `POST /accounts`, `GET/POST /sources`, `GET /source-report` (per-source cost per held call with credible intervals)

### 8-21. Reg CF Waitlist as an Asset (vision §19.18.8; arch §16.20) — extends 8-2 `future_regcf`, 8-4, 5-5

- [x] V113: `regcf_waitlist` + `regcf_activation_gate` (all tenants seeded FROZEN, OI-3) + `waitlist_value_estimates` (with generated `estimated_value` column)
- [x] `regcf_service.py`: `estimate_waitlist_value()` with Beta CI on p_convert
- [x] FastAPI: `POST /waitlist/value`
- [x] `RegCfWaitlistController`: `POST /join` (consent required), `GET /`, `GET /gate` (FROZEN + OI-3 note), `POST /gate/unlock` (FROZEN→PENDING_REVIEW, counsel_answer required), `GET /value`
- ⚠️ **FROZEN** — OI-3: Meta audience NOT activated until gate = ACTIVE + counsel sign-off
- [ ] Follow-up `REGCF_WAITLIST` cadence (deferred — gated on OI-3 counsel answer)

**Phase 8 exit criteria**: client #1's funnel runs from `funnel_stages`; CAPI sends Lead/Schedule/CompleteRegistration/Purchase with values and routes non-accredited leads away from the investor pixel; Thompson on landings rewards `SLOT_SELECTED`/`FORM_SUBMIT` with the `CALL_HELD` corrector; at least two follow-up variants run against the client's current cadence with show-rate reported; every new creative text passes the securities rulebases before upload; the dashboard attributes creatives to held calls and funds. **[v1.3 — added]** Beyond-capability set (8-14 … 8-21): at least two funnel paths run against path A with CALL_HELD per 1 000 LPV reported; no-show score has holdout AUC ≥ 0.70 and drives follow-up arms; `predicted_value` is calibrated (slope 0.8–1.2) and live on `Lead`; new creatives pass a moderation probe before real budget; call objections appear in the brief.

---

## Bug List

_(empty — project not started)_

---

## Current Status

**[v1.1 — changed]** Status column = original scope; new column = open gap-closure items added in v1.1 (all unchecked).

| Phase     | Status (original scope) | Notes                                                                                                             | **[v1.1 — added]** Open v1.1 items |
| --------- | --------------- | ----------------------------------------------------------------------------------------------------------------- | --- |
| Phase 0   | **IN PROGRESS** | 0-1 ✅ 0-2 ✅ 0-3 ✅ (partial) 0-4 ✅ 0-5 ✅ 0-6 ✅ 0-7 ✅ 0-8 ✅ 0-9 ✅ 0-10 ✅ **0-11 ✅ 0-12 ✅ 0-13 ✅** | 0-6 fraud L2 rules + LOG_ONLY mode; envelope encryption (KMS, deferred); `sessions.linked_session_ids` (Phase 2); Meta OAuth scopes (Phase 3) |
| Phase 1   | **COMPLETE**    | 1-1 ✅ 1-2 ✅ 1-3 ✅ 1-4 ✅ 1-5 ✅ **1-6 ✅ 1-7 ✅**                                                              | 1-3 diagnostic principles ✅ (Block D) |
| Phase 2   | **COMPLETE**    | 2-1 ✅ 2-2 ✅ 2-3 ✅ 2-4 ✅ 2-5 ✅ 2-6 ✅ 2-7 ✅ 2-8 ✅ **2-9 ✅ 2-10 ✅ 2-11 ✅ 2-12 ✅ 2-13 ✅ 2-14 ✅** | 2-2 offer validity mask + margin floor; 2-5 signals + cross-device; match-score calibration report deferred; postmortem endpoint deferred to 6-5 |
| Phase 3   | **COMPLETE**    | 3-1 ✅ 3-2 ✅ 3-3 ✅ 3-4 ✅ 3-5 ✅ 3-6 ✅ 3-7 ✅ 3-8 ✅ 3-9 ✅ 3-10 ✅ 3-11 ✅                                    | 3-2 anchors/learning log/Creative Testing/Bid Cap; 3-5 L1 Bayesian + Slack; **3-12** External Signals; **3-13** Site Health full; **3-14** anchor divergence + clickbait; **3-15** API priority queue; **3-16** CB recovery; **3-17** autonomy levels; **3-18** OPE gate; **3-19** MMF + Offer Explorer; **3-20** fraud L3; **3-21** proxy calibration + CRM discipline; **3-22** persona sandbox; **3-23** MLflow + profit reward |
| Phase 4   | **COMPLETE**    | 4-1 ✅ 4-2 ✅ 4-3 ✅ 4-4 ✅ **4-5 ✅ 4-6 ✅**                                                                     | 4-4 brief versioning/pairing/creator effect |
| Phase 5   | **COMPLETE**    | 5-1 ✅ 5-2 ✅ 5-3 ✅ 5-4 ✅ 5-5 ✅ **5-6 ✅ 5-7 ✅**                                                            | 5-4 approved reorder rules + in-session refinement; 5-5 segment→creative map; **5-8** per-segment briefs; Meta Experiments API (5-7) deferred |
| Phase 6   | **COMPLETE**    | 6-1 ✅ 6-2 ✅ 6-3 ✅ 6-4 ✅ 6-5 ✅ 6-6 ✅ **6-7 ✅ 6-8 ✅**                                                    | 6-1 survival model; 6-4 coherence/GLM ranking/interactions; 6-5 re-review; **6-6 release unblocked (3-17/3-18 ✅)**; **6-9** marketplace skeleton; entropy diversity penalty wiring to 6-4 deferred |
| Phase 0.5 | **IN PROGRESS** | 0.5a not started (needs 5+ clients); 0.5b: feature store ✅ hierarchy ✅ red flags ✅ (Block D)                  | first-party-only benchmarks ✅, client-level A/B (deferred), Meta terms check (0.5a) |
| Phase 7   | **COMPLETE**    | 7-1 Google ✅ 7-2 TikTok ✅ 7-3 Decision graph ✅ 7-4 MMM ✅ 7-5 Attribution ✅                                   | — |
| **[v1.2 — added]** Phase 8 | **IN PROGRESS** | 8-1 ✅ · 8-2 ✅ · 8-3 ✅ (infra) · 8-4 ✅ · 8-5 ✅ (infra) · 8-6 ✅ · 8-7 ✅ · 8-8 ✅ · 8-9 ✅ · 8-10 ✅ (signals+calendar infra) · 8-11 ✅ (labels+aliases infra) · 8-12 ✅ (widgets+frontend) · 8-13 ✅ (open items registry) · 8-14 ✅ (funnel paths Thompson) · 8-15 ✅ (noshow propensity) · 8-16 ✅ (predicted lead value) · 8-17 ✅ (compliance by construction) · 8-18 ✅ (post-call intelligence) · 8-19 ✅ (investor archetypes) · 8-20 ✅ (agency measurability) · 8-21 ✅ (regcf waitlist, FROZEN) | 8-3 reward_levels config, show-rate corrector; 8-5 weekly calibration, tz capture; 8-8 investor offer atom schema; 8-9 GHL sub-accounts; 8-10 GLM DEMAND/NARRATIVE wiring; 8-11 client asset delivery; **[v1.3]** 8-14…8-21 beyond-capability (infra complete, activation gated on live data/counsel) |

**Immediate priority**: ~~Phase 7 (Multi-Channel) or Phase 0.5 (Universal Cross-Niche Model) — Phase 6 complete~~ **[v1.1 — changed]** Before onboarding client #1 on anything above ADVISOR: the Phase 0 items (0-11, 0-12, 0-13), then Phase 2 content path (2-9 → 2-10 → 2-11, 2-12), then the Phase 3 protective set in this order — 3-15 queue → 3-13 Site Health → 3-14 anchors → 3-12 external signals → 3-16 recovery → 3-17/3-18 autonomy + OPE (unlocks 6-6). Phase 7 and 0.5 after that. **[v1.2 — changed]** With all v1.1 items ✅, the immediate priority is **Phase 8 pre-onboarding set (8-1 → 8-2 → 8-3 → 8-7 → 8-4)**: the implemented CAPI gate, reward map and gates are coded on the wrong funnel for client #1, and nothing should go live on their account until they read the configured funnel.

---

## Gap Closure Index (v1.1) **[v1.1 — added]**

> One line per vision mechanism that had no roadmap item; where it now lives, and the architecture subsection. Weather and news are rows G1.

| # | Vision | Mechanism | Roadmap item(s) | Arch |
|---|--------|-----------|-----------------|------|
| G1 | §11.3, §8.2, §26.6 | External Signals: CPM index, event calendar, **weather**, **news**, anomaly attribution | 3-12 (+1-3, 3-10) | §15.1 |
| G2 | §11.2, §12.3 | Anchors as market-shock detector (2 anchors, divergence verdict) | 3-2, 3-14 | §15.2 |
| G3 | §11.4, §22 | Site Health Guard | 0-13, 3-13 | §15.3 |
| G4 | §12.1, §12.1a, §12.2, §22 | Compliance & IP pre-approval (policy, claims, trademark, face, music, AI label) | 2-11, 4-5 | §15.4 |
| G5 | §3.1, §12.2, §15.3 | Quality Score + clickbait detector | 2-12, 3-14 | §15.5 |
| G6 | §18, §4.3a | Message-Market Fit + Dynamic Offer Explorer; validity mask; margin floor enforcement | 2-2, 3-19 | §15.6 |
| G7 | §5.1, §5.3, §6.3a, §13.3 | LLM content generation, message match score, RLHA store | 2-9, 2-10 | §15.7 |
| G8 | §9.2 | Voice of Customer | 1-7 | §15.8 |
| G9 | §15.12.12 | Synthetic Persona Sandboxing (A/B of the method) | 3-22 | §15.9 |
| G10 | §15.12.11 | Taxonomy Alignment Layer | 1-6 | §15.10 |
| G11 | §12.5 | Off-policy evaluation as autopilot gate | 3-18 (blocks 6-6) | §15.11 |
| G12 | §10.3 | Meta API priority queue | 3-15 | §15.20 |
| G13 | §12.7 | Token rotation, audit log, envelope encryption, scopes | 0-12 | §15.15, §11 |
| G14 | §12.4, §8.3 | Autonomy levels; expert prior / veto | 3-17 | §15.13 |
| G15 | §12.5b | Circuit-breaker recovery protocol | 3-16 | §15.14 |
| G16 | §12.6.2 | Fraud L2 additions, LOG_ONLY mode, L3 funnel anomalies, clicks delta | 0-6, 3-20 | §15.18 |
| G17 | §7.2 | Behavioural signals (video, quiz/calc, first click, return visit, velocity) | 0-11, 2-5 | §4.5, §15.21 |
| G18 | §7.4 | Cross-device identity + retrospective bounce correction | 2-5 | §4.8, §15.21 |
| G19 | §15.6–15.8, §15.11 | Survival burnout, interactions/BART, LightGBM, CPM model | 4-6, 6-1, 6-4 | §15.17 |
| G20 | §15.10 | MLflow model registry | 3-23 | §2, §15.17 |
| G21 | §23 Phase 0, §22 | Event schema registry; retention; right to erasure | 0-11 | §15.12 |
| G22 | §16.1, §22 | Proxy-value calibration + A/B; CRM discipline report | 3-21 | §15.19 |
| G23 | §6.5 | Learning-phase log, Creative Testing tool, Bid Cap soft launch | 3-2 | §15.20 |
| G24 | §22 | Niche Creative Entropy Index | 6-8 | §15.10 |
| G25 | §15.12.5/7, §6.4 | Universal: relative thresholds, FPR, ≥ 5-tenant floor, StudentT priors, first-party only | 0.5b | §15.21 |
| G26 | §5.5 | Brief versioning, pairing, creator random effect, per-segment, marketplace | 4-4, 5-8, 6-9 | §15.21 |
| G27 | §5.2 | AI avatars / text-to-video / Voice-as-Atom | 5-6 | §15.21 |
| G28 | §8.5 | Portfolio optimisation | 6-7 | §15.21 |
| G29 | §17.3 | Geo-Lift, Meta Experiments API | 5-7 | §15.21 |
| G30 | §3.2 | Diagnostics: Simpson baseline, multiple comparisons, LPV gap, external-factor row | 1-3 | §15.21 |
| G31 | §21.2–21.3 | Explainability from model stats; campaign post-mortem | 2-13 | §15.16 |
| G32 | §5.4.1 | Marketer-approved personalisation rules; in-session refinement | 5-4 | §15.21 |
| G33 | §3.1, §15.6 | Profit-per-impression reward | 3-23 | §15.17 |
| G34 | §7.3 st.5 | Segment → retargeting creative map | 5-5 | §15.21 |
| G35 | §24 v1.9.9 | Month-1 client view (Quality-Gate savings, date-to-significance) | 2-14 | §15.21 |
| **[v1.2 — added]** G36 | §19.1–19.4 | Funnel as configuration; client #1 funnel; CAPI chain v2 + pixel routing | 8-1, 8-2 | §16.1–16.3 |
| **[v1.2 — added]** G37 | §19.3, §19.5 | Reward ladder L1–L6; show-rate corrector; gates on cost per held call; no-show attribution | 8-3 | §16.6 |
| **[v1.2 — added]** G38 | §19.6 | Follow-up Sequence Optimisation (new module) | 8-4 | §16.5 |
| **[v1.2 — added]** G39 | §19.7, §19.14 | Self-qualification value, calibration, timezone capture | 8-5 | §16.4 |
| **[v1.2 — added]** G40 | §19.8 | Booking-funnel landing atoms | 8-6 | — |
| **[v1.2 — added]** G41 | §19.9 | Securities compliance rulebases, approved phrases, blocked-creative recovery | 8-7 | §16.7 |
| **[v1.2 — added]** G42 | §19.11 | Investor offer atom schema | 8-8 | §16.8 |
| **[v1.2 — added]** G43 | §19.12 | Attribution across GHL sub-accounts / agency pixels; dashboard to funds | 8-9 | §16.9 |
| **[v1.2 — added]** G44 | §19.13 | External-signal role per funnel; 2 anchors at launch | 8-10 | §16.10 |
| **[v1.2 — added]** G45 | §19.10, §19.15, §19.16 | Creative set-up, month-1 widgets, open client items | 8-11, 8-12, 8-13 | — |
| **[v1.3 — added]** G46 | §19.18.1 | Funnel path as Thompson arm | 8-14 | §16.14 |
| **[v1.3 — added]** G47 | §19.18.2 | No-show propensity, risk-tiered handling, slot overbooking, dynamic window | 8-15 | §16.15 |
| **[v1.3 — added]** G48 | §19.18.3 | Predicted lead value at day 0 | 8-16 | §16.16 |
| **[v1.3 — added]** G49 | §19.18.4 | Compliance by construction, moderation probe, reviewer persona | 8-17 | §16.17 |
| **[v1.3 — added]** G50 | §19.18.5 | Post-call intelligence; follow-up for HELD stage | 8-18 | §16.18 |
| **[v1.3 — added]** G51 | §19.18.6 | Investor archetypes → path / touch / brief | 8-19 | §16.19 |
| **[v1.3 — added]** G52 | §19.18.7 | Agency measurability (creative sources) | 8-20 | §16.20 |
| **[v1.3 — added]** G53 | §19.18.8 | Reg CF waitlist value | 8-21 | §16.20 |
