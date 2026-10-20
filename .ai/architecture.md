# Marketing AI Platform — Architecture Document

> Version 1.5 — 2026-10-20
> Scope: **[v1.2 — changed]** ~~MVP (Phase 0 + Phase 1 descriptive + Phase 2 + Thompson on site)~~ MVP core (Phase 0–2) in §1–§14; Phase 3+ modules the vision requires but v1.1 left out are specified in §15 (gap closure), with their schema, jobs and API routes
> Reference: **[v1.1 — changed]** ~~vision.md v1.9.6~~ vision.md v1.9.9
> v1.1: review round 1. §13/§6/§5.1: real GoHighLevel webhook names (`ContactCreate`, `AppointmentUpdate`, `OpportunityStatusUpdate`); Google Calendar removed as attendance source; `thompson_arms` has a single writer (FastAPI derives arms from events — Spring never increments α/β); no fractional α increments; §8 RLS fixed (`SET LOCAL`, `missing_ok`, non-owner role, FastAPI tenant GUC); §4.5 client-generated `event_id` for Pixel/CAPI dedup + idempotency; one server-side CAPI path (Spring), sGTM demoted; §7 Edge → FastAPI exposure made explicit; §9/§14 budget-guard pause needs Marketing API write in MVP + Meta Automated Rules at onboarding; §4.6 propensity stored, decision log made durable; §9 decay toward prior; smaller: Spring Boot version, Bot Management tier, `users.email` scope, `block_order` by `event_id`, `/decide` session_id
>
> **Marking convention for v1.1 edits**: every touched place is tagged **[v1.1 — changed]** or **[v1.1 — added]**; replaced text is kept ~~struck through~~ next to the new text. Inside code blocks the tag is a comment line (`-- [v1.1 — changed]`, `# [v1.1 — added]`).
>
> v1.2: gap closure against vision v1.9.9 (full read, §1–§26). New **§15 Gap Closure Modules** specifies 15 modules that the vision describes but v1.0/v1.1 did not: External Signals (§11.3 — CPM index, event calendar, weather, news), Anchor-based market-shock detector (§11.2), Site Health Guard (§11.4), Compliance & IP Pre-Approval (§12.1/§12.1a/§12.2), Quality Score & clickbait detector (§12.2/§15.3), Message-Market Fit + Dynamic Offer Explorer (§18), LLM Content Generation + Message Match + RLHA store (§5.1/§6.3a/§13.3), Voice of Customer (§9.2), Synthetic Persona Sandboxing (§15.12.12), Taxonomy Alignment Layer (§15.12.11), Off-Policy Evaluation gate (§12.5), Meta API Priority Queue (§10.3), Autonomy Levels + expert prior/veto (§12.4), Circuit-Breaker Recovery Protocol (§12.5b), Token Rotation + Audit Log (§12.7). Also: event schema registry + retention/erasure (§23 Phase 0), fraud L2/L3 additions (§12.6), behavioral signals (§7.2), cross-device retrospective correction (§7.4), MLflow registry (§15.10), profit-per-impression reward (§3.1), proxy-value calibration (§16.1), two-contour rules (§6.5), predictive-layer models (§15.7–15.8), AI video/voice (§5.2), brief versioning (§5.5), Geo-Lift (§17.3), portfolio optimisation (§8.5), Creative Entropy Index (§22). Existing sections carry a one-line **[v1.2 — added]** pointer where a module touches them.
>
> v1.3: **§16 Client #1 Adaptation** (vision §19) — the client runs an investor funnel (Reg D 506(c)); funnel/CAPI/reward move behind per-tenant configuration (`funnel_stages`, `capi_event_map`, `reward_levels`); CAPI chain v2 Lead(value) → Schedule → CompleteRegistration → Purchase with pixel routing (non-accredited → `future_regcf`); new **Follow-up Sequence Optimisation** module (`followup_variants`, `followup_assignments`, `/decide/followup`, GHL workflows as executor); show-rate corrector and no-show attribution; securities compliance rulebases + approved phrases; investor offer atom schema; attribution across GHL sub-accounts and agency pixels; external-signal role per funnel. Pointers in §13 and §14.
>
> v1.4: §16.13–16.21 — beyond-current-capability modules (vision §19.18), **additive only**: funnel paths as arms, no-show propensity + risk-tiered follow-up + slot overbooking, predicted lead value at day 0 (`predicted_value` formula token), compliance by construction + staged moderation probe + reviewer persona, post-call intelligence, investor archetypes, creative sources (agency measurability), Reg CF waitlist value. No existing table semantics, service contracts or migrations change.
>
> v1.5: **sync with the code after the 2026-10-20 conformance review** (`.ai/conformance-review-2026-10-20.md`). §5.1/§5.2 route tables now name the paths that exist (`/decide/landing`, `/capi-events`, `/analytics/creatives`, `/mmf/offers/*`, `/evolve/*`, `POST /explain/{entity_type}`, `/predict/score`, `/simulate`); the `/model/*` family is replaced by the incremental reward path (`/decide/reward`, `RewardSweepScheduler`, lazy decay in `load_arms`). §8: Spring sets the GUC per statement at session level with a reset on connection return (Hikari autocommit); ml-service role becomes `mai_ml` (roadmap 9-4). §9: Quality Score / taxonomy / proxy-calibration jobs run in Spring; jobs still missing are marked with their roadmap 9-x item. §16.3: the seeded CAPI map follows vision §19.4 (roadmap 9-7). Tags: **[v1.5 — changed]** / **[v1.5 — added]**.
>
> **Marking convention for v1.2 edits**: same as v1.1, with the tag **[v1.2 — added]** / **[v1.2 — changed]**. §15 is entirely new, so only its heading carries the tag.

---

## 1. System Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           MARKETING AI PLATFORM                             │
│                                                                             │
│  ┌──────────────┐   ┌──────────────────┐   ┌────────────────────────────┐  │
│  │   Next.js    │   │   Spring Boot    │   │      FastAPI (Python)      │  │
│  │   Frontend   │   │   Platform API   │   │      ML Service            │  │
│  │              │   │                  │   │                            │  │
│  │ • Admin UI   │──▶│ • Auth / Tenants │──▶│ • Thompson Sampling        │  │
│  │ • Dashboard  │   │ • CRUD (creative,│   │ • Creative DNA extraction  │  │
│  │ • Landing    │   │   landing, block)│   │ • Bayesian model (PyMC)    │  │
│  │   renderer   │   │ • Event ingest   │   │ • Diagnostics / analytics  │  │
│  │   (SSR)      │   │ • Meta API proxy │   │ • Power calculator         │  │
│  │ • Edge MW    │──▶│ • Webhooks       │   │ • Creator Brief generator  │  │
│  │   (TS call)  │   │ • CMP / Consent  │   │                            │  │
│  └──────────────┘   └──────────────────┘   └────────────────────────────┘  │
│         │                    │                         │                    │
│         │            ┌───────┴───────┐                 │                    │
│         └───────────▶│  PostgreSQL   │◀────────────────┘                    │
│                      │  + pgvector   │                                      │
│                      └───────┬───────┘                                      │
│                              │                                              │
│                      ┌───────┴───────┐                                      │
│                      │    Redis      │ ← cached posteriors (α/β) for fast   │
│                      │               │   Thompson Sampling (~5ms)           │
│                      └───────────────┘                                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Tech Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| **Platform API** | **[v1.1 — changed]** Java 21 + ~~Spring Boot 3.3~~ Spring Boot 3.5 LTS or 4.x (3.3 is past OSS support; starting a greenfield project on it means an upgrade before launch) | Enterprise patterns from Sales-ai; auth, multi-tenant, CRUD, Meta API integration, webhooks, event ingestion |
| **ML Service** | Python 3.12 + FastAPI | PyMC, Thompson Sampling inference, Creative DNA (LLM calls), Bayesian diagnostics — ML ecosystem lives in Python |
| **Frontend** | Next.js 15 (App Router, SSR) | Admin dashboard, landing page renderer with SSR, Edge Middleware for TS routing |
| **Database** | PostgreSQL 16 + pgvector | Events, variants, atoms, propensity logs; pgvector for taxonomy alignment (Phase 1+) |
| **Cache** | Redis 7 | Cached posteriors for <10ms Thompson Sampling decisions |
| **Object Storage** | Cloudflare R2 (S3-compat) | Video assets, rendered creatives, screenshots (Phase 4); no egress fees |
| **Server-Side Tracking** | **[v1.1 — changed]** ~~sGTM on GCP / custom proxy~~ Spring Boot `/api/v1/events` is the single server-side pipeline to CAPI (see §6 step 7). sGTM is **not** deployed in MVP — two server-side senders would double-fire events to Meta | Phase 0 requirement: bypass ATT/ad-blockers; ~~primary CAPI source~~ Spring is the only CAPI source; browser Pixel is dedup/fallback via shared `event_id` (§4.5) |
| **Bot Protection** | **[v1.1 — changed]** Cloudflare Bot Management — ⚠️ Enterprise-plan product; MVP fallback is Super Bot Fight Mode (Pro/Business) plus the behavioral session filter | Phase 0 fraud filter before session creation |
| **CMP** | Cookiebot | Consent management; determines tracking level per visitor |
| **Alerts** | Telegram Bot API | Circuit Breaker, fraud anomalies, Site Health alerts |
| **[v1.1 — changed]** ~~**Calendar**~~ **Meeting attendance** | ~~Google Calendar API~~ GHL `AppointmentUpdate` webhook, `appointmentStatus = showed / noshow` (manual field set by the sales rep) | ~~Verify meeting attendance (show/no-show) when GHL doesn't track it natively (§13, §16.1)~~ Google Calendar API only exposes invitee response (accepted/declined), not attendance; it is removed from the stack. Google Meet attendance reports (Workspace, separate API) are an optional Phase 3 enhancement, not a dependency |
| **[v1.2 — added]** **External signals** | Open-Meteo / OpenWeatherMap (weather), NewsAPI or GDELT (news) + LLM summariser, internal event calendar, Meta Insights CPM series | Vision §11.3 / §26.6: context features for the model and for anomaly attribution ("market" vs "creative"); Phase 3 (§15.1) |
| **[v1.2 — added]** **Synthetic monitoring** | Playwright (already in repo for QA) | Vision §11.4 Site Health Guard: HTTP checks + headless form submission against the live landing; §15.3 |
| **[v1.2 — added]** **Model registry** | MLflow (self-hosted, Postgres-backed) | Vision §15.10: versioned posteriors / GLM fits, parameters, metrics; `model_version` in `decision_log` references an MLflow run; §15.11 |
| **[v1.2 — added]** **Embeddings** | sentence-transformers (local) or Claude/OpenAI embeddings → pgvector | Vision §15.12.11 Taxonomy Alignment Layer; also Niche Creative Entropy Index (§22); §15.10 |
| **[v1.2 — added]** **Face / media checks** | MediaPipe (local) or AWS Rekognition; audio fingerprint lib for music | Vision §12.1a IP protection at render time; §15.4 |
| **[v1.2 — added]** **Alerts (2nd channel)** | Slack Incoming Webhooks | Vision §12.5a names Telegram **or Slack**; same `AlertService` interface, per-tenant choice |
| **CI/CD** | GitHub Actions | Build, test, deploy |
| **Containers** | Docker + docker-compose (dev), Cloud Run (prod) | |

### 2.1 Why Hybrid (Spring Boot + FastAPI)

**Spring Boot** handles:
- Authentication & authorization (Spring Security, JWT, OAuth2)
- Multi-tenant isolation (RLS patterns proven in Sales-ai)
- CRUD operations (creatives, landings, blocks, tenants)
- Meta Marketing API / CAPI integration (rate-limiting, priority queue §10.3)
- Event ingestion pipeline (high-throughput writes)
- Webhook receivers (Meta, GoHighLevel CRM)
- Admin panel API

**FastAPI** handles:
- Thompson Sampling decisions (samples from Redis-cached posteriors, ~5ms)
- Creative DNA extraction (LLM calls to Claude/GPT-4V, Whisper)
- Bayesian model training and updates (PyMC/NumPyro)
- Diagnostic analytics and explainability
- Power calculator
- Creator Brief generation (LLM orchestration)

**Communication**: Spring ↔ FastAPI via internal HTTP (docker network). Latency-critical path (landing page → Thompson decision) goes directly from Next.js Edge → FastAPI `/decide`.

---

## 3. Repository Structure

```
marketing-ai/
├── .ai/                          # Architecture docs, vision, roadmap
│   ├── architecture.md           # This file
│   ├── roadmap.md                # Phase tracking
│   ├── test-strategy.md          # Testing guidelines
│   └── vision.md                 # Full vision document
│
├── platform-api/                 # Java Spring Boot — Platform API
│   ├── src/main/java/com/marketingai/
│   │   ├── config/               # Spring config, security, Redis, CORS
│   │   ├── controller/           # REST controllers
│   │   ├── dto/                  # Request/response DTOs
│   │   ├── entity/               # JPA entities
│   │   ├── repository/           # Spring Data JPA repos
│   │   ├── security/             # JWT, tenant isolation, RLS
│   │   ├── service/              # Business logic
│   │   │   ├── meta/             # Meta API integration (Marketing API, CAPI)
│   │   │   ├── event/            # Event ingestion pipeline
│   │   │   └── webhook/          # Webhook handlers
│   │   └── MarketingAiApplication.java
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-local.yml
│   │   ├── application-prod.yml
│   │   └── db/migration/         # Flyway migrations
│   ├── build.gradle.kts
│   └── Dockerfile
│
├── ml-service/                   # Python FastAPI — ML Service
│   ├── app/
│   │   ├── main.py               # FastAPI app
│   │   ├── api/
│   │   │   ├── decide.py         # Thompson Sampling endpoint
│   │   │   ├── analyze.py        # Creative DNA extraction
│   │   │   ├── diagnostics.py    # Analytics & explainability
│   │   │   └── brief.py          # Creator Brief generator
│   │   ├── models/
│   │   │   ├── thompson.py       # Thompson Sampling (Beta, Hurdle)
│   │   │   ├── bayesian_glm.py   # PyMC model definition
│   │   │   └── creative_dna.py   # Atom extraction logic
│   │   ├── services/
│   │   │   ├── redis_cache.py    # Posterior cache read/write
│   │   │   ├── llm_client.py     # Claude/GPT-4V API wrapper
│   │   │   └── model_updater.py  # Scheduled posterior recalc
│   │   └── config.py
│   ├── tests/
│   ├── requirements.txt
│   ├── pyproject.toml
│   └── Dockerfile
│
├── frontend/                     # Next.js — Admin + Landing Renderer
│   ├── src/
│   │   ├── app/                  # App Router
│   │   │   ├── (admin)/          # Admin panel routes
│   │   │   │   ├── dashboard/
│   │   │   │   ├── creatives/
│   │   │   │   ├── landings/
│   │   │   │   └── settings/
│   │   │   └── (landing)/        # Landing page routes (SSR)
│   │   │       └── [variantSlug]/
│   │   ├── components/
│   │   │   ├── admin/            # Dashboard components
│   │   │   └── blocks/           # Landing page block components
│   │   │       ├── HeroBlock.tsx
│   │   │       ├── ProblemBlock.tsx
│   │   │       ├── ProofBlock.tsx
│   │   │       ├── PricingBlock.tsx
│   │   │       ├── CTABlock.tsx
│   │   │       └── FAQBlock.tsx
│   │   ├── lib/
│   │   │   ├── api.ts            # Platform API client
│   │   │   ├── tracker.ts        # Event tracking (scroll, click, hover)
│   │   │   └── consent.ts        # CMP integration
│   │   └── middleware.ts         # Edge Middleware → FastAPI /decide
│   ├── next.config.ts
│   ├── package.json
│   └── Dockerfile
│
├── docker-compose.yml            # Local dev: all services + postgres + redis
├── docker-compose.prod.yml       # Production overrides
└── .github/workflows/            # CI/CD
    ├── test.yml
    └── deploy.yml
```

---

## 4. Database Schema (PostgreSQL)

### 4.1 Multi-Tenant Foundation

```sql
-- Row-Level Security: every table has tenant_id, enforced via RLS policies
-- [v1.1 — changed] was: "Spring Boot sets `SET app.current_tenant = ?` on each request"
-- Both services run `SET LOCAL app.current_tenant = ?` inside each transaction,
-- connected as a NON-OWNER role (RLS is bypassed for the table owner). See §8.

CREATE TABLE tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) UNIQUE NOT NULL,
    niche           VARCHAR(100),           -- "b2b_manufacturing", "ecommerce_fashion"
    meta_app_id     VARCHAR(100),
    meta_access_token_enc BYTEA,            -- encrypted (Vault/KMS in prod, AES in MVP)
    -- Pixels stored in meta_pixels table (multi-pixel support, §6.8)
    settings        JSONB DEFAULT '{}',     -- tenant-level config
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    email           VARCHAR(255) NOT NULL,  -- [v1.1 — changed] was UNIQUE (global): one person could not belong to two tenants
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(50) DEFAULT 'MARKETER', -- ADMIN, MARKETER, VIEWER
    created_at      TIMESTAMPTZ DEFAULT now(),
    UNIQUE(tenant_id, email)                -- [v1.1 — added]
);
```

### 4.2 Creative DNA

```sql
CREATE TABLE creatives (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    external_id     VARCHAR(255),           -- Meta ad creative ID
    type            VARCHAR(20) NOT NULL,   -- VIDEO, IMAGE, CAROUSEL
    title           VARCHAR(500),
    file_url        TEXT,                    -- R2/S3 URL
    thumbnail_url   TEXT,
    transcript      TEXT,                    -- Whisper output (video)
    duration_sec    REAL,
    status          VARCHAR(20) DEFAULT 'UPLOADED', -- UPLOADED, ANALYZING, ACTIVE, ARCHIVED
    is_anchor       BOOLEAN DEFAULT FALSE,  -- anchor creative (§11.2)
    meta_ad_set_id  VARCHAR(100),
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE creative_atoms (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creative_id     UUID NOT NULL REFERENCES creatives(id) ON DELETE CASCADE,
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    atom_type       VARCHAR(50) NOT NULL,   -- HOOK, STRUCTURE, EDIT_PACE, FACE, SUBTITLES,
                                            -- TONE, PROOF_TYPE, MUSIC, DURATION, CTA, PRODUCTION_STYLE
    atom_value      VARCHAR(500) NOT NULL,  -- "question", "fast", "ugc_raw", etc.
    confidence      REAL,                   -- LLM extraction confidence [0,1]
    extracted_by    VARCHAR(50),            -- "claude-vision", "whisper+llm", "manual"
    embedding       vector(768),            -- pgvector: for taxonomy alignment (Phase 1+)
    created_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_atoms_creative ON creative_atoms(creative_id);
CREATE INDEX idx_atoms_type_val ON creative_atoms(tenant_id, atom_type, atom_value);
```

### 4.3 Landing Pages

```sql
CREATE TABLE landing_variants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    creative_id     UUID REFERENCES creatives(id),  -- paired creative (§5.3 message match)
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL,
    status          VARCHAR(20) DEFAULT 'DRAFT',    -- DRAFT, REVIEW, ACTIVE, PAUSED, ARCHIVED
    blocks          JSONB NOT NULL,                  -- array of block objects (see §5.4.3)
    block_order     TEXT[] NOT NULL,                 -- [v1.1 — changed] was ["hero","proof","pricing","cta"] (by type):
                                                     -- two blocks of the same type could not be ordered.
                                                     -- Now by event_id: ["blk_001","blk_002","blk_003"]
    match_score     REAL,                            -- LLM-computed message match (§6.3a)
    approved_by     UUID REFERENCES users(id),
    approved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    UNIQUE(tenant_id, slug)
);

-- Block content example in `blocks` JSONB:
-- [
--   {"type": "hero", "headline": "...", "subhead": "...", "event_id": "blk_001"},
--   {"type": "proof", "testimonials": [...], "event_id": "blk_002"},
--   {"type": "pricing", "plans": [...], "event_id": "blk_003"}
-- ]

-- Offer sub-atoms (§4.3a) stored per variant
CREATE TABLE variant_offers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    variant_id          UUID NOT NULL REFERENCES landing_variants(id) ON DELETE CASCADE,
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    discount_type       VARCHAR(50),    -- percent, fixed, free_shipping, gift, none
    discount_depth      REAL,           -- 0.0-1.0 (normalized)
    guarantee_type      VARCHAR(50),    -- money_back_14, money_back_30, lifetime, none
    guarantee_days      INT,
    bonus               VARCHAR(200),
    has_real_urgency    BOOLEAN DEFAULT FALSE,
    urgency_source      VARCHAR(200),   -- "inventory_api", "seasonal_deadline"
    urgency_days_left   INT,
    payment_terms       VARCHAR(50),    -- full, installment_3, installment_6, trial, freemium
    margin_floor        REAL,           -- min margin set by marketer (§4.3a hard floor)
    created_at          TIMESTAMPTZ DEFAULT now()
);
```

### 4.4 Thompson Sampling Arms

```sql
CREATE TABLE thompson_arms (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    variant_id      UUID NOT NULL REFERENCES landing_variants(id),
    context_key     VARCHAR(255) DEFAULT 'global', -- segment key, or "global"
    -- Hurdle model: Binomial(conversion) × Lognormal(revenue)
    -- [v1.1 — changed] was: "Reward logic (B2B): form_submit=wait, call_answered=α+=0.3,
    --   meeting_attended=α+=1.0, deal_won=revenue update, no_response_7d=β+=1.0"
    -- That mixed fractional pseudo-counts into a Beta that is supposed to count one binary
    -- outcome; posteriors and the P(X better) > 0.9 rules stop meaning anything.
    -- Now: ONE binary success event per arm, per vision §24 reward definition for client #1.
    --   MVP reward_event = CTA_CLICK / FORM_START (hundreds/week); FORM_SUBMIT as a second
    --   arm row (reward_event column below); meeting_attended is a weekly re-weighting
    --   corrector, not an α increment. Fractional increments are forbidden.
    -- [v1.1 — added] SINGLE WRITER: only FastAPI /model/update writes alpha/beta, derived from
    --   events + sessions. Spring Boot never touches this table (see §6 step 8, §12 #12).
    reward_event    VARCHAR(50) NOT NULL DEFAULT 'FORM_SUBMIT', -- [v1.1 — added] which event this arm counts
    alpha           REAL NOT NULL DEFAULT 1.0,      -- successes + 1 (Beta prior); integer-valued in MVP
    beta            REAL NOT NULL DEFAULT 1.0,      -- failures + 1 (Beta prior); integer-valued in MVP
    revenue_mu      REAL DEFAULT 0.0,               -- log-revenue mean
    revenue_sigma   REAL DEFAULT 1.0,               -- log-revenue std
    revenue_n       INT DEFAULT 0,                  -- number of purchases
    total_samples   INT DEFAULT 0,
    last_decayed_at TIMESTAMPTZ DEFAULT now(),       -- for daily α/β decay (§6.2)
    updated_at      TIMESTAMPTZ DEFAULT now(),
    UNIQUE(tenant_id, variant_id, context_key, reward_event)  -- [v1.1 — changed] + reward_event
);
CREATE INDEX idx_arms_tenant ON thompson_arms(tenant_id, context_key);
```

### 4.5 Events (Raw Stream)

```sql
-- High-volume table: partitioned by month, tenant_id in partition key
CREATE TABLE events (
    id              UUID DEFAULT gen_random_uuid(),
    -- [v1.1 — added] Client-generated event ID (vision §23 Phase 0 "единый ID события").
    -- Same value goes to the browser Pixel and to CAPI → Meta dedups the pair.
    -- Also the idempotency key for the batched tracker POST (retries must not double-insert).
    event_id        VARCHAR(64) NOT NULL,
    tenant_id       UUID NOT NULL,
    session_id      VARCHAR(100) NOT NULL,
    event_type      VARCHAR(50) NOT NULL,   -- IMPRESSION, CLICK, PAGE_VIEW, SCROLL, HOVER,
                                            -- BLOCK_VIEW, FORM_START, FORM_FIELD, FORM_SUBMIT,
                                            -- CONVERSION, PURCHASE, LEAD
                                            -- [v1.2 — added] vision §7.2 signals that were missing:
                                            -- VIDEO_PROGRESS (25/50/75/100 of on-page video),
                                            -- QUIZ_ANSWER / CALC_INTERACT (quiz & calculator blocks),
                                            -- FIRST_CLICK (first interactive target in session),
                                            -- RETURN_VISIT (same consented session cookie, new visit),
                                            -- CRM_* (call answered, meeting attended/no-show, deal won/lost)
    schema_version  SMALLINT NOT NULL DEFAULT 1,  -- [v1.2 — added] vision §23 Phase 0 "schema registry": payload schema per event_type is versioned in event_schemas (§15.12); readers branch on it
    creative_id     UUID,
    variant_id      UUID,
    block_event_id  VARCHAR(50),            -- blk_001, blk_002... (§3.2 granular analytics)
    event_data      JSONB,                  -- flexible payload: scroll_depth, hover_target, field_name...
    revenue         REAL,                   -- for PURCHASE events
    device          VARCHAR(20),            -- mobile, desktop, tablet
    geo             VARCHAR(10),            -- country code
    utm_source      VARCHAR(100),
    utm_medium      VARCHAR(100),
    utm_campaign    VARCHAR(100),
    consent_level   VARCHAR(20),            -- full, analytics_only, none (CMP)
    is_suspicious   BOOLEAN DEFAULT FALSE,  -- fraud filter flag (§12.6)
    event_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    received_at     TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (id, event_time)
) PARTITION BY RANGE (event_time);

-- Create partitions monthly (Flyway migration or cron)
-- CREATE TABLE events_2026_10 PARTITION OF events
--   FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE INDEX idx_events_session ON events(tenant_id, session_id, event_time);
CREATE INDEX idx_events_variant ON events(tenant_id, variant_id, event_type, event_time);
-- [v1.1 — added] idempotency; must include the partition key
CREATE UNIQUE INDEX idx_events_event_id ON events(tenant_id, event_id, event_time);
```

### 4.6 Propensity Log (§23 Phase 0, mandatory)

```sql
-- Every Thompson Sampling decision is logged with full context
CREATE TABLE decision_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    session_id      VARCHAR(100) NOT NULL,
    creative_id     UUID,
    -- The decision
    chosen_variant_id UUID NOT NULL,
    -- Full candidate set with probabilities (for off-policy evaluation)
    candidates      JSONB NOT NULL,
    -- [v1.1 — changed] was: [{"variant_id": "...", "sample_value": 0.42, "alpha": 12, "beta": 88}, ...]
    -- Off-policy evaluation needs the SELECTION PROBABILITY (propensity), not the sampled value.
    -- Thompson's propensity has no closed form; estimate it at decision time by Monte Carlo
    -- (N = 200 draws from the cached posteriors is <1ms) and store it alongside α/β so it
    -- can be recomputed later:
    -- Format: [{"variant_id": "...", "propensity": 0.63, "sample_value": 0.42, "alpha": 12, "beta": 88}, ...]
    -- [v1.1 — added] DURABILITY: this row is written BEFORE /decide returns (sync insert, or
    -- XADD to a Redis stream consumed into Postgres). "Async, non-blocking" fire-and-forget
    -- silently loses decisions and corrupts every later off-policy estimate.
    context         JSONB,                  -- device, geo, utm_*, time_of_day, etc.
    model_version   VARCHAR(50),            -- which model produced this decision
    decided_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_decisions_tenant_time ON decision_log(tenant_id, decided_at);
```

### 4.7 Meta Integration

```sql
-- Multi-Pixel support (§6.8 Pixel Pollution Protection)
CREATE TABLE meta_pixels (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    pixel_id        VARCHAR(100) NOT NULL,
    name            VARCHAR(255),
    purpose         VARCHAR(30) NOT NULL,   -- PRIMARY, TEST, PRODUCT
    product_id      UUID,
    is_default      BOOLEAN DEFAULT FALSE,
    capi_access_token_enc BYTEA,
    quality_filter  JSONB DEFAULT '{}',     -- min lead quality score to forward event to Meta
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ DEFAULT now(),
    UNIQUE(tenant_id, pixel_id)
);

CREATE TABLE meta_campaigns (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    pixel_id        UUID REFERENCES meta_pixels(id),
    meta_campaign_id VARCHAR(100) NOT NULL,
    name            VARCHAR(500),
    objective       VARCHAR(50),
    contour         VARCHAR(20) NOT NULL,   -- TEST, SCALE (§6.5 two-contour)
    daily_budget    REAL,
    status          VARCHAR(20),            -- ACTIVE, PAUSED, ARCHIVED
    learning_phase  VARCHAR(20),            -- LEARNING, ACTIVE, LEARNING_LIMITED
    synced_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE meta_ad_sets (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    campaign_id     UUID NOT NULL REFERENCES meta_campaigns(id),
    meta_ad_set_id  VARCHAR(100) NOT NULL,
    creative_id     UUID REFERENCES creatives(id),
    audience_spec   JSONB,
    daily_budget    REAL,
    status          VARCHAR(20),
    learning_phase  VARCHAR(20),
    synced_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT now()
);

-- Financial Circuit Breaker (§12.5a L1 — MVP)
CREATE TABLE budget_guards (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    guard_type      VARCHAR(30) NOT NULL,   -- PER_CREATIVE_STOP_LOSS, MONTHLY_CEILING
    reference_id    UUID,                   -- creative_id for per-creative, NULL for account-level
    threshold       REAL NOT NULL,          -- max spend in dollars
    current_spend   REAL DEFAULT 0,
    is_triggered    BOOLEAN DEFAULT FALSE,
    triggered_at    TIMESTAMPTZ,
    period_start    DATE,                   -- for monthly ceiling
    created_at      TIMESTAMPTZ DEFAULT now()
);
```

### 4.8 Sessions (Behavioral Tracking)

```sql
CREATE TABLE sessions (
    id              VARCHAR(100) PRIMARY KEY,   -- cookie-based session ID
    tenant_id       UUID NOT NULL,
    creative_id     UUID,                       -- which ad brought them
    variant_id      UUID,                       -- which landing assigned
    device          VARCHAR(20),
    geo             VARCHAR(10),
    utm_source      VARCHAR(100),
    utm_campaign    VARCHAR(100),
    consent_level   VARCHAR(20),
    crm_user_id     VARCHAR(255),               -- linked after micro-conversion (§7.4)
    is_suspicious   BOOLEAN DEFAULT FALSE,
    started_at      TIMESTAMPTZ DEFAULT now(),
    last_event_at   TIMESTAMPTZ,
    converted       BOOLEAN DEFAULT FALSE,
    conversion_type VARCHAR(50),                -- LEAD, PURCHASE, FORM_SUBMIT
    revenue         REAL,
    -- [v1.2 — added] vision §7.4 cross-device + §7.2 return pattern + §12.2 Quality Score inputs
    external_id_hash    VARCHAR(64),            -- SHA-256 of email/phone, sent to CAPI as external_id after consent
    linked_session_ids  TEXT[],                 -- earlier anonymous sessions stitched to this identity
    bounce_corrected_at TIMESTAMPTZ,            -- set by RetroCorrectionJob when a "bounce" was later a conversion on another device
    visit_count         INT DEFAULT 1,          -- repeat visits under the same consented cookie
    quality_score       REAL,                   -- §15.5: f(match_score, bounce, scroll depth, form completion), computed at session close
    external_ctx        JSONB                   -- §15.1 snapshot: {cpm_index, calendar_tags[], weather, news_sentiment} at session start
);
CREATE INDEX idx_sessions_tenant ON sessions(tenant_id, started_at);
CREATE INDEX idx_sessions_ext_id ON sessions(tenant_id, external_id_hash);   -- [v1.2 — added]
```

---

## 5. API Contracts

### 5.1 Spring Boot — Platform API (port 8080)

#### Auth
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/auth/register` | Register new user + tenant |
| POST | `/api/v1/auth/login` | JWT login |
| POST | `/api/v1/auth/refresh` | Refresh token |

#### Tenants
| Method | Path | Description |
|--------|------|-------------|
| GET | **[v1.5 — changed]** ~~`/api/v1/tenants/me`~~ `/api/v1/tenants/me/settings` | Current tenant settings (report e-mail, alert channel, holdout, …) |
| PATCH | **[v1.5 — changed]** ~~`PUT /api/v1/tenants/me`~~ `/api/v1/tenants/me/settings` | Partial update of tenant settings |
| PUT | `/api/v1/tenants/me/meta-credentials` | Set Meta API tokens (sealed with `TokenVault`, roadmap 9-1) |
| GET/PUT | **[v1.5 — added]** `/api/v1/tenants/me/autonomy` | Autonomy level; PUT above ADVISOR requires the latest `ope_runs` PASS ≤ 7 days (roadmap 9-3) |
| GET/PUT | **[v1.5 — added]** `/api/v1/tenants/me/oauth-scopes` | Minimal Meta scope record (§11) |

#### Creatives
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/creatives` | List creatives (filterable) |
| POST | `/api/v1/creatives` | Upload creative (multipart → R2) |
| GET | `/api/v1/creatives/{id}` | Creative detail + atoms |
| POST | `/api/v1/creatives/{id}/analyze` | Trigger Creative DNA extraction (→ FastAPI) |
| PUT | `/api/v1/creatives/{id}/anchor` | Mark/unmark as anchor |
| DELETE | `/api/v1/creatives/{id}` | Archive creative |

#### Landing Variants
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/landings` | List variants |
| POST | `/api/v1/landings` | Create variant (blocks JSON) |
| GET | `/api/v1/landings/{id}` | Variant detail |
| PUT | `/api/v1/landings/{id}` | Update variant (blocks, block_order) |
| POST | `/api/v1/landings/{id}/approve` | Approve for production |
| POST | `/api/v1/landings/{id}/pause` | Pause variant |

#### Events
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/events` | Ingest batch of events from landing tracker (dedup: Redis 24 h key per `event_id` + partial unique index `idx_events_tenant_event_dedup`) |
| POST | **[v1.5 — changed]** ~~`/api/v1/events/capi`~~ `/api/v1/capi-events` | Receive Meta CAPI webhook events (`CAPIEventController`) |
| POST | **[v1.5 — added]** `/api/v1/form/submit` | Investor form (self-accreditation, ticket bucket) → `form_submissions`, value log, GHL ids |

#### Dashboard
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/analytics/overview` | Tenant overview (conversions, revenue, ROAS) |
| GET | **[v1.5 — changed]** ~~`/api/v1/analytics/variants`~~ `/api/v1/analytics/creatives` | Per-creative performance (variants: `GET /api/v1/landings/arms`, `GET /api/v1/experiments/{id}/results`) |
| GET | `/api/v1/analytics/atoms` | Atom-level performance breakdown |
| GET | `/api/v1/analytics/funnel` | Funnel diagnostics (§3.2) |

#### Budget Guards (Circuit Breaker L1)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/budget-guards` | List active guards |
| POST | `/api/v1/budget-guards` | Create stop-loss / ceiling |
| PUT | `/api/v1/budget-guards/{id}` | Update threshold |

#### Pixels (Multi-Pixel Management)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/pixels` | List pixels for tenant |
| POST | `/api/v1/pixels` | Create pixel (PRIMARY, TEST, or PRODUCT) |
| PUT | `/api/v1/pixels/{id}` | Update pixel settings (quality filter, name) |
| DELETE | `/api/v1/pixels/{id}` | Deactivate pixel |

#### GoHighLevel Webhooks
| Method | Path | Description |
|--------|------|-------------|
| POST | **[v1.5 — changed]** ~~`/api/v1/webhooks/ghl`~~ `/api/v1/webhooks/ghl/{tenantId}` (header `X-Webhook-Secret`, chosen per `locationId` from `ghl_subaccounts`, §16.9) | **[v1.1 — changed]** ~~Receive GHL events: `contact.created`, `opportunity.*`, `appointment.*`, `call.completed`~~ Receive GHL events by their real names: `ContactCreate`, `ContactUpdate`, `AppointmentCreate`, `AppointmentUpdate`, `OpportunityStatusUpdate` (+ conversation/message webhooks for call outcome — ⚠️ verify exact event in GHL docs). Writes to `ghl_webhook_log` + `events`/`sessions` only — never to `thompson_arms` (§13.1) |

#### [v1.2 — added] Gap-closure routes (Spring Boot)
Full contracts in §15; summary so the surface is visible in one place:

| Area | Routes | §15 |
|------|--------|-----|
| External signals | **[v1.5 — changed]** `GET /api/v1/signals/calendar`, ~~`PUT`~~ `POST /api/v1/signals/calendar`, ~~`GET /api/v1/signals/context?date=`~~ context is snapshotted into `sessions.external_ctx` at session start (no date endpoint) | 15.1 |
| Anchors | `PUT /api/v1/ad-sets/{id}/anchor` (exists), `GET /api/v1/campaigns/{id}/anchor-divergence` | 15.2 |
| Site Health | **[v1.5 — changed]** `GET /api/v1/site-health`, ~~`POST /api/v1/site-health/checks`~~ (checks run only from `SiteHealthScheduler` / `FormHealthScheduler`), ~~`POST /api/v1/site-health/{id}/resume`~~ `POST /api/v1/site-health/{id}/confirm-resume` (3 consecutive passes **and** this confirmation) | 15.3 |
| Compliance / IP | `POST /api/v1/compliance/check` (text or creative), `GET /api/v1/compliance/{entityId}`, `PUT /api/v1/compliance/{id}/override` | 15.4 |
| Quality Score | **[v1.5 — changed]** ~~`GET /api/v1/analytics/quality-score?by=creative`~~ no read route yet — scores live in `creative_quality` and `sessions.quality_score`, computed by `QualityScoreScheduler`; the admin page in roadmap 9-5 exposes them | 15.5 |
| Message-Market Fit / Offer Explorer | **[v1.5 — changed]** `GET /api/v1/mmf` (per-audience rows in the body), `POST /api/v1/evolve/generate` (offer + atom candidates), `POST /api/v1/evolve/{id}/approve`, `GET /api/v1/offer-schemas/{key}/explore` (§16.8) | 15.6 |
| LLM content + RLHA | `POST /api/v1/landings/{id}/generate`, `POST /api/v1/landings/{id}/match-score`, `GET /api/v1/rlha/corrections`, `GET /api/v1/rlha/metrics` | 15.7 |
| Voice of Customer | `POST /api/v1/voc/import`, `GET /api/v1/voc/objections` | 15.8 |
| Autonomy | `GET/PUT /api/v1/tenants/me/autonomy` (roadmap 9-3), `POST /api/v1/creatives/{id}/expert-rating`; autopilot switches `GET/PUT /api/v1/autopilot/settings` | 15.13 |
| **[v1.5 — added]** Meta write policy | `GET /api/v1/tenants/me/meta-write`, `PUT …/stage`, `PUT …/policy`, `POST …/overrides`, `GET …/decisions` — stage-gated writes (`MetaWriteGate` is the single choke point for every Marketing API write; roadmap 9-19, `.ai/meta-api-policy.md` §6a) | 15.13, 15.20 |
| Circuit-breaker recovery | **[v1.5 — changed]** `GET /api/v1/circuit-breaker/{id}/diagnosis`, `POST /api/v1/circuit-breaker/{id}/confirm-cause`, ~~`POST /api/v1/circuit-breaker/{id}/resume-batch`~~ `POST /api/v1/circuit-breaker/resume-batch` (body carries the event id) | 15.14 |
| Security | **[v1.5 — changed]** `GET /api/v1/audit-log`; ~~`POST /api/v1/tenants/me/meta-credentials/rotate`~~ rotation is only the daily `TokenRotationScheduler` (roadmap 9-2) | 15.15 |
| Data governance | **[v1.5 — changed]** ~~`GET /api/v1/event-schemas`~~ schemas are read by `EventSchemaService` from `event_schemas` (no public route), `POST /api/v1/privacy/erasure` | 15.12 |

### 5.2 FastAPI — ML Service (port 8000)

#### Thompson Sampling
| Method | Path | Description |
|--------|------|-------------|
| POST | **[v1.5 — changed]** ~~`/decide`~~ `/decide/landing` (`/decide/followup` for follow-up arms §16.5, `/decide/reward` for reward deltas) | Choose variant for visitor. **[v1.1 — changed]** Input: `{tenant_id, creative_id, context` ~~`}`~~ `, session_id?}` — `session_id` present only after consent (sticky assignment); absent = stateless decision per vision §12.1a. Output: `{variant_id, block_order, decision_id}`. Latency target: <15ms **from FastAPI**; end-to-end budget depends on where middleware runs (§7) |
| POST | **[v1.5 — changed]** ~~`/decide/batch`~~ — not implemented; the Redis posterior cache is warmed by the first `/decide/landing` per `{tenant, context_key}` (TTL 15 min) |

#### Creative DNA
| Method | Path | Description |
|--------|------|-------------|
| POST | `/analyze/creative` | Extract atoms from creative. Input: `{creative_id, file_url, transcript}`. Async: returns job_id |
| GET | `/analyze/status/{job_id}` | Check extraction status |

#### Model
| Method | Path | Description |
|--------|------|-------------|
| POST | **[v1.5 — changed]** ~~`/model/update`~~ `/decide/reward` (`{decision_id, reward_type}`; idempotent through `thompson_reward_log`) and `/decide/reward/sweep-negatives` (`RewardSweepScheduler`: β += 1 after the 7-day no-response window) | Posteriors are updated incrementally per reward, not by a batch recalculation; `PosteriorRecalcScheduler` (15 min) only reports arms with rewards that have not been applied |
| GET | **[v1.5 — changed]** ~~`/model/posteriors/{tenant_id}`~~ `/glm/posteriors?tenant_id=` (GLM atom posteriors) and `GET /api/v1/landings/arms` (arm α/β) | Current posteriors |
| POST | **[v1.5 — changed]** ~~`/model/decay`~~ — decay `α = 1 + 0.99·(α−1)` is applied lazily inside `load_arms()` when `last_decayed_at` is older than 24 h (arms never loaded are never decayed — acceptable: they are not being served) |

#### Diagnostics
| Method | Path | Description |
|--------|------|-------------|
| GET | `/diagnostics/{tenant_id}` | Diagnostic card (§3.2): symptoms, diagnoses, actions |
| POST | **[v1.5 — changed]** ~~`GET /diagnostics/{tenant_id}/power`~~ `/simulate` with `scenario = "power"` (sessions to significance, p10/p50/p90) | Power calculator |
| POST | **[v1.5 — changed]** ~~`GET /diagnostics/{tenant_id}/anchors`~~ `/anchors/divergence`, `/anchors/divergence/multi` | Anchor creative comparison (market vs creative effect) |

#### Creator Brief
| Method | Path | Description |
|--------|------|-------------|
| POST | `/brief/generate` | Generate data-driven brief from posteriors (Phase 1: template only) |

#### [v1.2 — added] Gap-closure routes (FastAPI)
| Area | Routes | §15 |
|------|--------|-----|
| Anomaly / signals | `POST /signals/anomaly` (baseline anomaly detection on CPM/CTR/CVR series with external context) | 15.1 |
| Anchor divergence | `POST /anchors/divergence` — P(test − anchor < −X) from Beta posteriors | 15.2 |
| Quality Score / clickbait | `POST /quality/score`, `POST /quality/clickbait-check` | 15.5 |
| Message-Market Fit | `POST /mmf/diagnose` — P(any variant > breakeven) + between-variant variance | 15.6 |
| Offer Explorer | **[v1.5 — changed]** `POST /mmf/offers/generate`, `POST /mmf/offers/rank`, `POST /mmf/offers/generate-from-schema` (§16.8; COMPLIANCE_PASS replaces the margin floor for securities) | 15.6 |
| Content generation / match | `POST /content/generate` (slots from product facts + best atoms + RLHA few-shot), `POST /content/match-score` | 15.7 |
| Voice of Customer | `POST /voc/extract-objections` | 15.8 |
| Persona sandbox | `POST /persona/build`, `POST /persona/score` | 15.9 |
| Taxonomy alignment | `POST /taxonomy/align`, `POST /taxonomy/recalibrate` | 15.10 |
| Off-policy evaluation | `POST /ope/evaluate` — IPS / SNIPS / doubly-robust estimate of a candidate policy on `decision_log` | 15.11 |
| Explainability | **[v1.5 — changed]** `POST /explain/{entity_type}` (tenant id and stats in the body) — stats-only narrative (vision §21.2) | 15.16 |
| Predictive extras | **[v1.5 — changed]** `POST /predict/score` (CPM, log-linear MVP), `POST /burnout/survival` (Phase 6), `POST /glm/interactions` (Bayes-factor ranking) | 15.17 |
| **[v1.5 — added]** Beyond §15 | `/video/*` (transcribe, tag-scenes, montage-plan, render, generate-variations), `/ai-video/*`, `/propensity/*` (no-show, report), `/predict/lead-value`, `/post-call/*`, `/investor/*`, `/agency/*`, `/waitlist/value`, `/reward/*` (show-rate corrector, gate adjustment), `/portfolio/optimize`, `/geo-lift/*`, `/entropy/*`, `/cb/*`, `/offer/*` (validity), `/niche-store/*`, `/hierarchy/*`, `/red-flags/*`, `/model-quality/*`, `/analyze/noshow-attribution`, `/analyze/form-health`, `/signals/news-sentiment` — 139 routes in total, every one with a Spring caller (`scripts/check-ml-routes.py`) | — |

---

## 6. Request Flow: Visitor Clicks Ad → Landing Page

```
1. Visitor clicks Meta ad (?creative_id=video-7&utm_source=meta&...)
   │
2. Next.js Edge Middleware intercepts
   ├── Checks consent (CMP cookie)
   ├── Extracts creative_id, UTM params
   │
3. Edge → FastAPI POST /decide
   │  {tenant_id, creative_id, context: {device, geo, utm_*}}
   │
4. FastAPI samples from Redis-cached posteriors (Beta(α,β))
   │  ├── Picks variant with highest sample
   │  ├── [v1.1 — changed] was: "Logs decision to decision_log (async, non-blocking)"
   │  │   Writes decision + propensity DURABLY before responding (§4.6)
   │  └── Returns {variant_id, block_order}
   │
5. Next.js SSR renders landing page
   │  ├── Fetches variant blocks from PostgreSQL (cached in Redis)
   │  ├── Renders React block components in block_order
   │  └── Each block has data-block-id={event_id}
   │
6. Client-side tracker.ts fires events
   │  ├── page_view, scroll (depth + velocity), hover, block_view
   │  ├── form_start, form_field, form_submit
   │  └── Batched POST → Spring Boot /api/v1/events (every 5s or on leave)
   │
7. Spring Boot writes events to PostgreSQL (upsert on event_id — idempotent)
   │  ├── Marks suspicious sessions (fraud filter)
   │  └── [v1.1 — changed] was: "For CONVERSION/PURCHASE: sends to Meta CAPI"
   │      Form submit is NOT sent to CAPI (vision §6.8 Quality Gate). Spring is the ONLY
   │      server-side CAPI sender; the browser Pixel fires the same event_id for dedup.
   │
8. GoHighLevel webhook → Spring Boot /api/v1/webhooks/ghl
   │  [v1.1 — changed] whole step rewritten: real GHL event names; Spring writes
   │  events/sessions only — it never increments Thompson α/β (single writer, §4.4)
   │  ├── ContactCreate (new contact) or ContactUpdate (returning contact — ContactCreate
   │  │   will not fire for a lead already in GHL)
   │  │   → link ghl_contact_id → session_id via email/phone match; event LEAD
   │  ├── call outcome (conversation/message webhook — ⚠️ verify exact event)
   │  │   → event CRM_CALL_ANSWERED; CAPI: Lead(value = proxy_low)
   │  ├── AppointmentUpdate, appointmentStatus = showed  (manual field set by the rep)
   │  │   → event CRM_MEETING_ATTENDED; CAPI: Schedule(value = proxy_high)  [was: Purchase]
   │  ├── AppointmentUpdate, appointmentStatus = noshow / no CRM event in 7d
   │  │   → event CRM_NO_SHOW; no CAPI event
   │  └── OpportunityStatusUpdate, status = won / lost
   │      → event CRM_DEAL_WON (CAPI: Purchase, the only Purchase per lead) / CRM_DEAL_LOST
   │
9. Periodic: FastAPI /model/update recalculates posteriors
   │  [v1.1 — changed] recomputes α/β from events (reward_event per arm) + sessions;
   │  CRM events feed the weekly re-weighting corrector, not direct increments
   └── Updates Redis cache with new α/β
```

---

## 7. Service Communication

```
┌──────────┐         ┌──────────────┐         ┌──────────┐
│ Next.js  │──HTTP──▶│ Spring Boot  │──HTTP──▶│ FastAPI  │
│ Frontend │         │ Platform API │         │ ML Svc   │
└──────────┘         └──────────────┘         └──────────┘
     │                      │                       │
     │ (Edge MW)            │                       │
     └─────────── direct ──▶│     (fast /decide)    │
                            │                       │
                     ┌──────┴───────┐               │
                     │  PostgreSQL  │◀──────────────┘
                     └──────┬───────┘
                            │
                     ┌──────┴───────┐
                     │    Redis     │◀─── both services read/write
                     └──────────────┘
```

**Internal network**: all services communicate over docker network (dev) or VPC (prod). **[v1.1 — changed]** ~~No public exposure for FastAPI — only Next.js Edge and Spring Boot call it.~~ Spring Boot → FastAPI stays private. The Next.js middleware → FastAPI hop depends on where Next.js runs, and the two options are not equivalent:

| Option | Where middleware runs | FastAPI exposure | Latency to /decide | TTFB claim (vision §5.4.5: 50-100 ms) |
|--------|----------------------|------------------|--------------------|---------------------------------------|
| A — Next.js self-hosted on Cloud Run (same region as FastAPI) | Node runtime, one region | Private (VPC) | ~1-2 ms | Holds only for visitors near that region; no edge benefit |
| B — Next.js on Vercel | Vercel edge nodes worldwide | **Must be public** (behind Cloudflare, API-key + rate limit) | 50-150 ms cross-region per decision | Edge benefit is cancelled by the cross-region call |

**MVP decision: Option A.** First client's traffic is single-market; a private /decide and honest latency beat a nominal edge deployment. Revisit when traffic is multi-region (then Option B with a regional FastAPI replica + Redis read replica per region).

**Auth for internal calls**: shared API key in environment variable. No JWT overhead on internal path.

---

## 8. Multi-Tenant Architecture

Approach: **Shared database, shared schema, Row-Level Security (RLS)**.

```sql
-- Enable RLS on all tenant-scoped tables
ALTER TABLE creatives ENABLE ROW LEVEL SECURITY;
ALTER TABLE creatives FORCE ROW LEVEL SECURITY;   -- [v1.1 — added] applies even to the owner role
CREATE POLICY tenant_isolation ON creatives
    -- [v1.1 — changed] was: USING (tenant_id = current_setting('app.current_tenant')::uuid);
    -- current_setting() without missing_ok THROWS when the GUC is unset (cast of '' to uuid);
    -- NULLIF makes an unset tenant return zero rows instead of erroring.
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- [v1.1 — changed] was: "Spring Boot sets this on every connection from HikariCP
--   via a ConnectionCustomizer or @Transactional aspect"
-- With a connection pool a plain SET persists on the connection AFTER the request and
-- leaks the tenant to whoever gets that connection next. Must be transaction-scoped:
--   SET LOCAL app.current_tenant = '<uuid>';   -- inside the same transaction as the query
-- Role model: migrations run as the owner role (mai_owner); both services connect as
-- mai_app, which does NOT own the tables and has no BYPASSRLS. FORCE above is belt-and-braces.
```

**Spring Boot side**: **[v1.5 — changed]** `JwtFilter` extracts the tenant from the JWT into `TenantContext`; `TenantScopedConnection` (a JDBC proxy around every pooled connection) runs `set_config('app.current_tenant', ?, false)` before each statement and resets it to `''` when the connection returns to the pool. Session scope is used on purpose: `SET LOCAL` is silently discarded in Hikari's autocommit mode before Spring opens a transaction. Schedulers and bootstrap paths run in an explicit system scope (`TenantContext.runAsSystem`, `app.rls_bypass = on`, see V116). ~~`TenantFilter` … `SET LOCAL`~~

**FastAPI side**: **[v1.5 — changed]** tenant_id is an explicit parameter in every API call. Since roadmap 9-4 (2026-10-20) ml-service connects as `mai_ml` (LOGIN, NOBYPASSRLS, write grants on `thompson_arms` / `thompson_reward_log` / `model_posteriors` only) and `db_client.tenant_tx(tenant_id)` / `system_tx()` wrappers that run `set_config('app.current_tenant', $1, true)` or `set_config('app.rls_bypass', 'on', true)` inside the transaction, so every one of the 19 DB access sites is scoped. `mai_app` keeps no write grant on `thompson_arms` (single writer, §12 #12). ~~… connects as the same `mai_app` role … `/model/update`, `/model/decay` …~~

---

## 9. Scheduled Jobs

| Job | Frequency | Service | Description |
|-----|-----------|---------|-------------|
| Posterior decay | **[v1.5 — changed]** ~~Daily 03:00 UTC~~ lazy, inside `load_arms()` when `last_decayed_at` > 24 h | FastAPI | **[v1.1 — changed]** ~~α/β × 0.99 on all arms (§6.2)~~ `α = 1 + 0.99·(α−1)`, `β = 1 + 0.99·(β−1)` — decay toward the prior, not toward Beta(0,0) (vision §6.2 v1.9.9) |
| Posterior recalc | **[v1.5 — changed]** ~~Every 15 min~~ per reward (`/decide/reward`) + `RewardSweepScheduler` hourly (β += 1 after 7 days without response) | FastAPI (writer) / Spring (sweep trigger) | **[v1.1 — added]** Sole writer of `thompson_arms`; `PosteriorRecalcScheduler` (15 min, Spring) only logs arms with pending rewards |
| Meta sync | Every 30 min | Spring Boot | Pull campaign/ad set status, learning phase, spend |
| Budget guard check | Every 5 min | Spring Boot | Compare spend vs thresholds → trigger pause if exceeded. **[v1.1 — added]** Pausing an ad set is a **Marketing API write** (`ads_management` scope) — this is in MVP even though campaign management is not (§14). Spend data is up to 30 min stale (Meta sync) plus Meta's own reporting delay, so the in-app guard alone cannot be the last line: Meta Account Spending Limit + Automated Rules are configured at onboarding (§12 #13) |
| Fraud session review | Hourly | Spring Boot | Score suspicious sessions, exclude from model |
| GHL sync | Every 60 min | Spring Boot | Pull pipeline stages for open opportunities, update lead quality scores. **[v1.5 — added]** not implemented yet (data arrives only from webhooks) → roadmap 9-8 `GhlSyncScheduler` |
| Event partition create | Monthly | Flyway/cron | Create next month's event partition. **[v1.5 — added]** partitions are pre-created to 2027-03 in V5; automatic creation → roadmap 9-6 `EventPartitionScheduler` |
| **[v1.2 — added]** Site health check | Every 1–5 min | Spring Boot | HEAD/GET every ACTIVE landing; 5xx/timeout ×2 → P0 autopause of ad sets pointing at it (§15.3) |
| **[v1.2 — added]** Synthetic form check | Every 15–30 min | Spring Boot → Playwright | Headless submission of each ACTIVE form with a sentinel email; failure → alert + autopause (§15.3) |
| **[v1.2 — added]** CVR-collapse detector | Every 15 min | FastAPI | CTR stable and CVR→0 in < 1 h on stable traffic → autopause + urgent alert (§15.3). **[v1.5 — added]** not implemented → roadmap 9-8 `CvrCollapseScheduler` |
| **[v1.2 — added]** External signals refresh | Hourly (CPM, news), daily (weather, calendar) | Spring Boot | Pull CPM series from Insights, weather for tenant geos, news headlines → LLM sentiment; write `external_signals` (§15.1) |
| **[v1.2 — added]** Baseline anomaly scan | Hourly | **[v1.5 — changed]** Spring (`ExternalSignalsScheduler` hourly) → FastAPI `POST /signals/anomaly` | Detect simultaneous drops across anchors + tests; classify market / creative / technical / fraud (§15.1, §15.2); the news label feeds the classification (§15.1) |
| **[v1.2 — added]** Fraud funnel-anomaly scan (L3) | Hourly | Spring Boot | CTR spike without conversions per geo/placement; clicks in target-geo night hours; one ad set's CTR anomalous vs same creative elsewhere; first-party clicks vs Meta clicks delta (§15.18) |
| **[v1.2 — added]** Quality Score compute | Every 15 min (closed sessions) | **[v1.5 — changed]** Spring `QualityScoreScheduler` → FastAPI `/quality/score` | `sessions.quality_score` + per-creative/variant aggregate; clickbait rule vs anchor (§15.5); show rate weight 0.40 (§16.6) |
| **[v1.2 — added]** Message-Market Fit scan | Daily | FastAPI | Per audience: P(any variant > breakeven) and between-variant variance → "offer problem" flag (§15.6). **[v1.5 — added]** on demand only (`/mmf/diagnose`) → roadmap 9-8 `MmfScanScheduler` |
| **[v1.2 — added]** Taxonomy recalibration | Weekly | **[v1.5 — changed]** Spring `TaxonomyRecalibrateScheduler` (Sun 04:00 UTC) → FastAPI `/taxonomy/recalibrate` | Re-cluster atom embeddings, merge drifting duplicates, promote candidate clusters (§15.10) |
| **[v1.2 — added]** Retro bounce correction | Daily | Spring Boot | Sessions newly linked to an identity: flip earlier "bounce" to "returned & converted" (§7.4; feeds posterior recalc) |
| **[v1.2 — added]** Proxy-value calibration | Weekly | **[v1.5 — changed]** Spring `ProxyCalibrationScheduler` (Mon 03:00 UTC) | Compare sent proxy values with realised CRM outcomes; adjust multipliers; report (§15.19); `capi_value_mode = AB` → roadmap 9-9 |
| **[v1.2 — added]** CRM discipline report | Weekly | Spring Boot | Appointments without Showed/No-show status; alert if > 20% (§13, vision §22) |
| **[v1.2 — added]** Token rotation | Daily check, rotate at 50 days | Spring Boot | Exchange long-lived Meta tokens before the 60-day expiry; alert on failure (§15.15) |
| **[v1.2 — added]** Retention sweep | Monthly | Flyway/cron | Drop raw `events` partitions older than retention window after aggregates are materialised (§15.12) |
| **[v1.2 — added]** Off-policy evaluation | Weekly + before any autonomy upgrade | FastAPI | IPS/DR estimate of current policy vs logged policy on `decision_log`; result gates autopilot (§15.11). **[v1.5 — added]** today only on demand (`POST /ope/evaluate`) and the gate is not enforced → roadmap 9-3 (`OpeScheduler`, `PUT /tenants/me/autonomy`) |
| **[v1.2 — added]** Learning-phase snapshot | On every launch/pause/budget change, +24h | Spring Boot | Log `learning_phase` before/after each Meta write (vision §6.5 rule) (§15.20) |
| **[v1.5 — added]** Jobs that exist beyond this table | — | Spring Boot | `NoshowAttributionScheduler` (weekly), `FunnelIntegrityScheduler` (07:15 UTC), `FollowupAutoPauseScheduler` (hourly), `ShowRateCorrectorScheduler` (weekly), `ClusterFitScheduler` (Sun 03:00), `EntropyScheduler`, `NicheBenchmarkScheduler`, `NicheFeatureStoreScheduler`, `RedFlagsScanScheduler`, `RetentionScheduler`, `RetroCorrectionJob`, `CrmDisciplineScheduler`, `WeeklyReportScheduler` (Mon 08:00), `FormHealthScheduler` (30 min), `FraudL2Scheduler`, `FraudL3Scheduler`, `LearningPhaseCheckScheduler`, `MetaSyncScheduler`, `CircuitBreakerScheduler`, `TokenRotationScheduler` (04:00), `SiteHealthScheduler` (60 s) — 41 scheduler classes, all wrapped in system scope by `SchedulingConfig` |
| **[v1.5 — added]** Phase 9 jobs (shipped 2026-10-20) | 60 min / 15 min / daily / weekly | Spring Boot | `GhlSyncScheduler` (60 min), `CvrCollapseScheduler` (15 min → ML `/signals/cvr-collapse`), `MmfScanScheduler` (05:30 UTC), `RegCfWaitlistScheduler` (Mon 06:00), `SelfReportCalibrationScheduler` (Mon 06:30), `StageValueRefreshScheduler` (Mon 07:00), `SlotCapacityScheduler` (15 min), `ModerationProbeScheduler` (30 min), `OpeScheduler` (Mon 05:00, 9-3), `EventPartitionScheduler` (startup + 1st 00:10, 9-6), `PersonaPromotionScheduler` (Mon 07:30, 9-9), `CapiValueModeScheduler` (hourly + Mon 07:15, 9-9), `FormNoSlotScheduler` (hourly, 9-9), `BlockedCreativeRecoveryScheduler` (6 h, 9-9) — 55 scheduler classes in total |

---

## 10. Docker Compose (Local Development)

```yaml
version: "3.9"
services:
  postgres:
    image: pgvector/pgvector:pg16
    ports: ["5432:5432"]
    environment:
      POSTGRES_DB: marketingai
      POSTGRES_USER: mai
      POSTGRES_PASSWORD: mai_local
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  platform-api:
    build: ./platform-api
    ports: ["8080:8080"]
    environment:
      SPRING_PROFILES_ACTIVE: local
      DB_URL: jdbc:postgresql://postgres:5432/marketingai
      DB_USER: mai
      DB_PASS: mai_local
      REDIS_HOST: redis
      ML_SERVICE_URL: http://ml-service:8000
      JWT_SECRET: local-dev-secret
      # [v1.1 — changed] removed: GOOGLE_CALENDAR_CREDENTIALS (Calendar is not an attendance source, §2)
      GHL_WEBHOOK_SECRET: ${GHL_WEBHOOK_SECRET}   # [v1.1 — added] verify webhook signatures
      DB_APP_ROLE: mai_app                        # [v1.1 — added] non-owner role for RLS (§8)
    depends_on: [postgres, redis]

  ml-service:
    build: ./ml-service
    ports: ["8000:8000"]
    environment:
      DATABASE_URL: postgresql://mai:mai_local@postgres:5432/marketingai
      REDIS_URL: redis://redis:6379
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
    depends_on: [postgres, redis]

  frontend:
    build: ./frontend
    ports: ["3000:3000"]
    environment:
      NEXT_PUBLIC_API_URL: http://localhost:8080
      ML_SERVICE_URL: http://ml-service:8000  # for Edge Middleware
    depends_on: [platform-api, ml-service]

volumes:
  pgdata:
```

---

## 11. Security (MVP Scope)

| Concern | MVP Solution | Scale Solution |
|---------|-------------|----------------|
| Auth | JWT (Spring Security) | OAuth2 + refresh rotation |
| Tenant isolation | RLS on PostgreSQL | + Vault for token storage |
| Meta tokens | AES-encrypted in DB | HashiCorp Vault / AWS KMS |
| API keys (internal) | Env vars | Rotated secrets in Vault |
| HTTPS | Cloudflare / nginx | Same |
| CORS | Whitelist tenant domains | Same |
| Rate limiting | Spring Boot filter (per-tenant) | Redis-based token bucket |
| Bot protection | Cloudflare Bot Management | + behavioral scoring |
| Consent | Cookiebot integration | Same |
| **[v1.2 — added]** Token lifecycle (vision §12.7) | Rotate Meta long-lived tokens at day 50 of 60 via token exchange; alert on failure; per-tenant data-encryption key (envelope encryption, master key in env) | Vault transit engine; automatic key rotation |
| **[v1.2 — added]** Audit log (vision §12.7) | Append-only `meta_api_audit` row for **every** Marketing API / CAPI call: who (user or job), tenant, action, target id, request hash, result; no UPDATE/DELETE grants on the table | Ship to immutable store (S3 Object Lock / BigQuery) |
| **[v1.2 — added]** Minimal OAuth scope | Request only `ads_management`, `ads_read`, `business_management`; scope recorded per tenant and shown in admin | Same |
| **[v1.2 — added]** Right to erasure (vision §22, §23 Phase 0) | `POST /api/v1/privacy/erasure` by `external_id_hash` / `crm_user_id`: delete matching `sessions`, `events`, `decision_log` rows, null PII in `ghl_webhook_log`; schedule arms recalc; log the request | Same, plus automated DSAR intake |

---

## 12. Key Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Hybrid Spring + FastAPI (not monolith) | ML ecosystem = Python; enterprise API = Java. Clean separation. Internal HTTP adds ~1-2ms, acceptable |
| 2 | Shared PostgreSQL (not separate DBs) | MVP simplicity. Both services need events and arms. RLS handles isolation |
| 3 | Redis for posteriors (not in-memory) | Survives service restart; shared between FastAPI instances; <1ms reads |
| 4 | Events in PostgreSQL partitioned (not Kafka) | MVP doesn't need streaming. Monthly partitions handle volume. Kafka = Phase 4+ if needed |
| 5 | Monorepo (not separate repos) | Shared schema migrations, coordinated deploys, simpler CI |
| 6 | Next.js ~~Edge~~ middleware → FastAPI direct | **[v1.1 — changed]** Landing page latency is critical (§5.4.5). No Spring hop for TS decisions. MVP runs Next.js on Cloud Run in the same region (Option A in §7); "edge" is dropped from the name because Vercel edge would force a public, cross-region /decide |
| 7 | JSONB for blocks (not separate tables) | Blocks are always loaded as a unit with variant. JSONB = single read, flexible schema |
| 8 | Propensity logging from day 1 | Vision §23 Phase 0 mandatory. Without it, off-policy eval and causal analysis impossible later |
| 9 | Flyway migrations (not Liquibase) | Simpler, SQL-native, good enough for this schema |
| 10 | Multi-tenant from day 1 | Vision §12.7. Retrofitting tenant isolation is painful. RLS cost is minimal |
| 11 | Multi-pixel from day 1 | Vision §6.8. Client experienced pixel pollution from bad campaign. Separate test/primary pixels prevent contamination of main pixel's algorithm |
| 12 | **[v1.1 — added]** `thompson_arms` has one writer (FastAPI) | Spring incrementing α/β from webhooks while FastAPI recalculates from events every 15 min would overwrite each other. Arms are a derived view of `events`/`sessions`; CRM outcomes enter as events, not as direct increments |
| 13 | **[v1.1 — added]** Meta-side fallbacks configured at onboarding, outside this system | Account Spending Limit + Automated Rules ("CPA > X over Y h → pause") run on Meta's servers and survive our outage, rate-limit block or token revocation (vision §12.5a). Onboarding checklist item, verified before first test launch |
| 14 | **[v1.1 — added]** One server-side CAPI sender (Spring), no sGTM in MVP | Two senders (sGTM + Spring) would double-fire or require cross-system dedup. The browser Pixel + Spring CAPI share a client-generated `event_id` (§4.5); that is the whole dedup story |
| 15 | **[v1.2 — added]** Autopilot is gated by off-policy evaluation, not by a feature flag | Vision §12.5: "OPE on own logs with propensity weights **before** real money". A tenant's autonomy level (§15.13) can only be raised to Semi/Autopilot when the latest OPE run (§15.11) shows the learned policy ≥ logged policy with P > 0.9 and effective sample size above a floor. `autopilot_settings.enabled=true` alone is not sufficient |
| 16 | **[v1.2 — added]** External context is a first-class feature, not a dashboard overlay | Vision §11: the model assumes a stationary world. CPM index, calendar tags, weather and news sentiment are snapshotted onto `sessions.external_ctx` and `external_signals`, enter the GLM as covariates (Phase 3) and drive the market/creative/technical/fraud attribution in anomaly scans and circuit-breaker diagnosis. Without them a CPM spike reads as creative burnout |
| 17 | **[v1.2 — added]** One `AlertService` with Telegram and Slack back-ends | Vision names both; tenant picks channel; all protective modules (CB, fraud, Site Health, MMF, CRM discipline) publish through the same interface with a severity and a structured payload, never via ad-hoc bot calls |
| 18 | **[v1.2 — added]** Compliance/IP checks are a pre-approval pipeline stage, not a post-hoc report | Vision §12.1/§12.1a: the admin "Approve" button is disabled until `compliance_checks` for that entity is PASS or the marketer records an explicit override with reason. Checks are a pre-filter; the human stays final |

---

## 13. GoHighLevel CRM Integration

> First client uses **GoHighLevel (GHL)** as CRM. This is the primary source of post-conversion data (lead quality, deal outcome, churn) needed for Proxy Value Bidding (vision §16.1) and Delayed Churn Penalty (vision §6.7).

### 13.1 GHL Webhook Events (Spring Boot receives)

**[v1.1 — changed]** The v1.0 table used invented event names (`contact.created`, `appointment.completed`, `opportunity.won`, `call.completed`); GHL's webhook catalogue uses `ContactCreate`, `AppointmentUpdate`, `OpportunityStatusUpdate` etc., with show/no-show and won/lost as **fields** on those events, not separate events. Old rows kept struck through for traceability.

| GHL Event | Meaning | Action in system |
|-----------|---------|-----------------|
| ~~`contact.created`~~ `ContactCreate` | New contact created in GHL (form submission or manual) | Link `ghl_contact_id` → `session_id` via email/phone match; write event `LEAD` |
| **[v1.1 — added]** `ContactUpdate` + form email/phone match | Returning lead — contact already existed, so `ContactCreate` does not fire | Same linking as above; without this row returning leads are unattributed and their sessions count as non-converting |
| ~~`opportunity.status_changed`~~ `OpportunityStatusUpdate` (status ≠ won/lost) | Lead moved between pipeline stages | Log stage transition + timestamp; update lead quality score |
| ~~`opportunity.won`~~ `OpportunityStatusUpdate`, `status = won`, `monetaryValue` | Deal closed successfully | Write event `CRM_DEAL_WON` with value; CAPI `Purchase(value = deal)` — the only Purchase per lead. ~~reinforce thompson arm (α++)~~ No direct arm write (§4.4) |
| ~~`opportunity.lost`~~ `OpportunityStatusUpdate`, `status = lost` | Deal lost | Write event `CRM_DEAL_LOST`; churn penalty consumes it in Phase 3+ |
| ~~`appointment.scheduled`~~ `AppointmentCreate` | Meeting booked ~~in Google Calendar~~ via GHL calendar | Log appointment; start quality timer (7-day window) |
| ~~`appointment.completed`~~ `AppointmentUpdate`, `appointmentStatus = showed` | Lead showed up to meeting — **manual status set by the sales rep** (no automatic verification exists; see §2) | **Key quality signal**: write event `CRM_MEETING_ATTENDED`; CAPI `Schedule(value = proxy_high)` ~~send proxy_high value~~ ~~Thompson α += 1.0~~ (no direct arm write) |
| ~~`appointment.no_show`~~ `AppointmentUpdate`, `appointmentStatus = noshow` | Lead didn't show up | Write event `CRM_NO_SHOW`; do NOT send to CAPI |
| ~~`call.completed`~~ call outcome via conversation/message webhook — ⚠️ verify exact event name and status field in current GHL docs before implementing | Outbound/inbound call answered | Partial quality signal: write event `CRM_CALL_ANSWERED`; CAPI `Lead(value = proxy_low)` ~~Thompson α += 0.3~~ (no direct arm write) |

**[v1.1 — added] Operational dependency**: the key signal is a field a human sets. Onboarding must include the rule "rep sets Showed / No-show within 24 h", a weekly "appointments without status" report, and an alert when > 20% of past appointments are unmarked (vision §22). Webhook payloads are signature-verified (`GHL_WEBHOOK_SECRET`, §10) and replayable from `ghl_webhook_log`.

### 13.2 Session ↔ GHL Contact Linking

When GHL fires **[v1.1 — changed]** ~~`contact.created`~~ `ContactCreate` **or `ContactUpdate`**, Spring Boot attempts to match by:
1. **Email** (from form submission event) — primary match
2. **Phone** (if email not available) — secondary match

On match: `sessions.crm_user_id = ghl_contact_id`. This enables cross-device attribution (vision §7.4) and retrospective conversion correction.

### 13.3 Database additions for GHL

```sql
-- Add to sessions table
ALTER TABLE sessions ADD COLUMN ghl_contact_id VARCHAR(100);
ALTER TABLE sessions ADD COLUMN ghl_opportunity_id VARCHAR(100);
ALTER TABLE sessions ADD COLUMN pipeline_stage VARCHAR(100);
ALTER TABLE sessions ADD COLUMN deal_value REAL;
ALTER TABLE sessions ADD COLUMN deal_status VARCHAR(30); -- open, won, lost

-- GHL webhook log (audit + replay)
CREATE TABLE ghl_webhook_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    event_type      VARCHAR(100) NOT NULL,
    ghl_contact_id  VARCHAR(100),
    ghl_opportunity_id VARCHAR(100),
    payload         JSONB NOT NULL,
    processed       BOOLEAN DEFAULT FALSE,
    received_at     TIMESTAMPTZ DEFAULT now()
);
```

### 13.4 Proxy Value Bidding (Phase 3, vision §16.1)

**[v1.3 — added]** ⚠️ The chain below is the generic **B2B product-sale template**. Client #1 (investor funnel, vision §19) uses the configured chain in **§16.3** — form with self-qualification → booking → held call → accreditation → funds; `CAPIForwardingService` reads `capi_event_map`, not this table.

Concrete quality events → proxy value → sent to Meta CAPI as conversion value:

```
-- [v1.1 — changed] real event names; each stage is a DIFFERENT CAPI event so one lead
-- never counts as two Purchases
call answered (conversation webhook)            → CAPI Lead,     value = avg_deal_value × 0.15
AppointmentUpdate, appointmentStatus = showed   → CAPI Schedule, value = avg_deal_value × 0.40  (key quality signal)
OpportunityStatusUpdate, status = won           → CAPI Purchase, value = actual deal value
No response after 7 days                        → no CAPI event (pixel not polluted)
```

Sent to Meta CAPI as `value` parameter → Meta optimizes toward high-value leads, not just volume.

**[v1.1 — added] Two constraints from vision §16.1 v1.9.9 that the campaign setup must respect:** (1) the optimization event chosen in Ads Manager must have enough volume to exit learning — for client #1 that is `Lead` (call answered), with `Schedule`/`Purchase` carrying value, not volume; (2) `Schedule` on day 5-7 after the click partly falls outside a 7-day click attribution window, so the client's booking process should aim meetings within 3-4 days of the form, or accept the loss.

### 13.5 GHL API Access (for sync job)

GHL provides REST API for reading contacts/opportunities. Spring Boot scheduled job (every 60 min) pulls open opportunities and updates `pipeline_stage` and `deal_value` in sessions table. Requires GHL API key stored encrypted in `tenants.settings`.

---

## 14. What Is NOT in MVP

These are explicitly deferred per vision.md §24:

- Video rendering (FFmpeg/Remotion) → Phase 4
- Meta Marketing API campaign management → Phase 3. **[v1.1 — changed]** Clarified: *creating/editing* campaigns and ad sets is deferred; **reading** insights (Meta sync, §9) and **pausing an ad set** (budget guard L1, §9) are in MVP and need `ads_read` + `ads_management` scopes from day 1
- Real-time personalization (block reordering) → Phase 5
- Uplift modeling → Phase 5
- Bayesian GLM full model (PyMC) → Phase 3 (MVP uses simple Beta-Binomial)
- Universal Cross-Niche Model → Phase 0.5b
- Geo-Lift testing → Phase 5+
- Multi-channel (Google, TikTok) → Phase 7
- Delayed Churn Penalty → Phase 3
- Portfolio optimization → Phase 6+

MVP Thompson Sampling uses **simple Beta(α,β)** for conversion rate, not the full hurdle model. The hurdle model (Binomial × Lognormal) is Phase 3 when revenue data is sufficient. **[v1.1 — added]** "Conversion rate" in MVP means the per-arm `reward_event` (CTA click / form start, then form submit) — see §4.4 and vision §24 reward definition for client #1; meeting-attended and deal-won are not arm rewards in MVP.

**[v1.1 — added]** Also not in MVP: sGTM (§12 #14), Google Calendar integration (§2), Cloudflare Bot Management Enterprise (§2 — Super Bot Fight Mode instead).

**[v1.3 — added]** §16 (client #1 adaptation) is a pre-onboarding track: §16.1–16.3 (funnel config + CAPI v2), §16.5 (follow-up module) and §16.7 (securities compliance) must exist before the first campaign under our control; the rest follows the existing phase order.

**[v1.2 — added]** Of the §15 modules, three belong in MVP despite the list above, because the vision places them in Phase 0 or they protect Phase 0 data: event schema registry + retention/erasure (§15.12), token rotation + audit log (§15.15), and fraud L2 additions (§15.18). The site-health HTTP check (§15.3) can also ship in Phase 0 as alert-only (autopause needs the Marketing API write that is in MVP anyway for budget guards). Everything else in §15 is Phase 2–6 and is phased in roadmap.md.

---

## 15. Gap Closure Modules **[v1.2 — added]**

> Each subsection: vision reference → what it does → schema → service/job → API. Phase per roadmap.md v1.1. Tables follow §4 conventions (UUID PK, `tenant_id`, RLS, `created_at`).

### 15.1 External Signals Module (vision §11.3, §8.2, §26.6) — Phase 3

**Purpose.** Give the model and the diagnostics a notion of "the world moved" so that a CPM spike, a holiday, a heatwave or a news shock is not read as creative burnout. Four signal families: **CPM index by niche** (from Meta Insights across the tenant's own account; cross-tenant niche index only after the §15.12.7 legal check), **event calendar** (internal: Black Friday, public holidays per geo, industry trade shows, client promo dates), **weather** (per tenant target geo; **[v1.2.1 — changed]** ~~only for niches flagged `weather_sensitive` in `tenants.settings`~~ on by default for client #1 — agricultural SAP demand follows drought and rainfall deficit, so this is a primary demand driver, not an optional niche flag; `tenants.settings.weather_sensitive` stays as the switch for verticals where it is noise), **news background** (headlines for the tenant's niche keywords → LLM → sentiment −1..1 and up to 3 topic tags).

```sql
CREATE TABLE external_signals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID REFERENCES tenants(id),       -- NULL = platform-wide (holidays, niche CPM index)
    signal_type     VARCHAR(30) NOT NULL,              -- CPM_INDEX, CALENDAR, WEATHER, NEWS, COMPETITOR (from §9.1)
    geo             VARCHAR(10),
    observed_at     TIMESTAMPTZ NOT NULL,
    valid_from      TIMESTAMPTZ, valid_to TIMESTAMPTZ, -- calendar windows
    value_num       REAL,                              -- cpm index, temperature, sentiment
    value_tags      TEXT[],                            -- ["black_friday"], ["rain"], ["supply_shortage"]
    source          VARCHAR(50),                       -- meta_insights, open_meteo, newsapi, manual
    raw             JSONB,
    created_at      TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_ext_signals ON external_signals(tenant_id, signal_type, observed_at);

CREATE TABLE event_calendar (                          -- marketer-editable, feeds CALENDAR signals
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id),             -- NULL = global
    name VARCHAR(200) NOT NULL, tag VARCHAR(50) NOT NULL,
    starts_on DATE NOT NULL, ends_on DATE NOT NULL, geo VARCHAR(10),
    expected_effect VARCHAR(20),                       -- CPM_UP, DEMAND_UP, DEMAND_DOWN, UNKNOWN
    created_at TIMESTAMPTZ DEFAULT now()
);
```

**Where it enters.** (1) `sessions.external_ctx` snapshot at session start (§4.8) → GLM covariates in Phase 3 (`cpm_index`, `is_holiday`, `weather_bucket`, `news_sentiment`), so atom effects are estimated net of context. (2) `BaselineAnomalyService` (FastAPI, hourly): takes CPM/CTR/CVR series for anchors and tests + external context and labels a drop as `MARKET` (anchors fell too / CPM index up / calendar tag active), `CREATIVE` (tests fell, anchors held), `TECHNICAL` (Site Health failing), `FRAUD` (suspicious share up) — this is the §8.2 three-row table made executable. (3) Circuit-breaker diagnosis (§15.14) and §3.2 diagnostic card row "everything falls at once → external factor".

**Jobs.** `ExternalSignalsScheduler` (Spring): hourly CPM + news, daily weather + calendar materialisation. **API.** `GET/PUT /api/v1/signals/calendar`, `GET /api/v1/signals/context?date=` (what the system believes about the world on a date — shown on the dashboard next to any anomaly), FastAPI `POST /signals/anomaly`.

**Honest scope.** **[v1.2.1 — changed]** ~~For client #1 (B2B hydrogels) weather is almost certainly irrelevant and should stay off; the module exists so the stack is not rebuilt for the second vertical.~~ For client #1 (agricultural hydrogels / SAP) weather is **critical**: it is the demand driver for the product itself — drought periods, rainfall deficit and the start of the growing season decide whether a grower is in the market at all. Weather is therefore a first-class covariate for this tenant, not a per-niche extra. What is pulled (per target geo, daily, from Open-Meteo or an agro-meteorological source): precipitation anomaly vs 30-year normal (7/30/90-day windows), drought index (SPI or SPEI; US Drought Monitor class for US geos), soil temperature / growing-degree days as a season-start proxy, and a heat-stress flag. Stored as `WEATHER` rows with `value_tags` such as `["drought_moderate","season_start"]` and in `sessions.external_ctx.weather`. In the Phase 3 GLM these enter as covariates on CVR (and on the CRM-stage conversions), so a creative tested during a wet month is not written off against one tested during a drought. The anomaly scan treats a drought-index change as a `MARKET` explanation for simultaneous shifts, and the event calendar should carry the agronomic calendar (sowing / planting windows per crop and geo) for the tenant's target regions. News sentiment remains the weak, noisy covariate — wide prior, logged for attribution first, modelled second.

### 15.2 Anchor Creatives as Market-Shock Detector (vision §11.2, §12.3) — Phase 3

**What v1.1 has.** `meta_ad_sets.is_anchor` and Gate 3 "P(CVR > anchor)". **What is missing.** The anchor's main job: deciding *market vs creative*.

- **Placement rule enforced at launch**: `TwoContourService.launchTestBatch()` refuses a batch that does not contain **2 anchor ad sets** on the same audience spec and budget as the test cells (override with reason allowed; logged). One anchor is a single point of failure (vision: "one may burn out").
- **Divergence test** (FastAPI `POST /anchors/divergence`, hourly in the anomaly scan): for each metric m ∈ {hook_rate, CTR, CVR} compute Δ = m_tests − m_anchors over the last 24/72 h from Beta posteriors; emit `MARKET` if P(anchors dropped vs their own 14-day baseline) > 0.9 **and** P(tests dropped) > 0.9; `CREATIVE` if only tests dropped; `NONE` otherwise. Threshold X and the 0.9 are per-tenant settings.
- **Consequences**: `MARKET` suppresses creative-rotation recommendations and gate kills for the window (gates compare to anchor anyway, but burnout/saturation detectors would otherwise fire); `CREATIVE` lets them proceed. Both anchors burning out → alert "replace anchor" (anchor itself has a burnout score).
- **Storage**: `anchor_divergence_log(tenant_id, campaign_id, window, metric, p_anchor_drop, p_test_drop, verdict, external_ctx, created_at)`. **API**: `GET /api/v1/campaigns/{id}/anchor-divergence`; `/diagnostics/{tenant}/anchors` (exists) reads from it.

### 15.3 Site Health Guard (vision §11.4, §22) — Phase 3 (HTTP check alert-only from Phase 0)

**Purpose.** Stop ad spend when the destination is broken; distinguish "site down" from "market down".

```sql
CREATE TABLE site_health_checks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    variant_id UUID REFERENCES landing_variants(id),   -- NULL = tenant domain root
    check_type VARCHAR(20) NOT NULL,                   -- HTTP, SYNTHETIC_FORM, CVR_COLLAPSE
    status VARCHAR(10) NOT NULL,                       -- PASS, FAIL
    latency_ms INT, http_status INT, detail JSONB,
    checked_at TIMESTAMPTZ DEFAULT now()
);
CREATE TABLE site_health_incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    cause VARCHAR(20) NOT NULL,                        -- HTTP_5XX, TIMEOUT, FORM_ERROR, PAYMENT_ERROR, CVR_COLLAPSE
    opened_at TIMESTAMPTZ DEFAULT now(), resolved_at TIMESTAMPTZ,
    paused_ad_set_ids TEXT[],                          -- what we paused
    auto_resumed BOOLEAN, resume_confirmed_by UUID REFERENCES users(id),
    consecutive_passes INT DEFAULT 0
);
```

**Rules (verbatim from the vision).** HTTP check every 1–5 min (HEAD then GET; 2 consecutive 5xx/timeouts open an incident). Synthetic form check every 15–30 min with Playwright: fills the ACTIVE variant's form with `healthcheck+<tenant>@…` (filtered out of `events`, `ghl` linking and CAPI by the sentinel), submits, expects the success state; payment gateway check where a checkout exists. CVR-collapse: CTR stable and CVR → 0 within < 1 h on ≥ N sessions. **Autopause is automatic, no confirmation** — goes through the **P0** lane of the Meta API queue (§15.12 of vision → §15.20 here). **Resume** only after 3 consecutive passes **and** marketer confirmation (`POST /api/v1/site-health/{id}/resume`), following the §15.14 batch-resume protocol (2–3 ad sets, +24 h, +48 h), not all at once. Every pause and its cause is logged and alerted. `TECHNICAL` verdict is exported to §15.1/§15.2 so anchors do not misread the drop as market.

### 15.4 Compliance & IP Pre-Approval Pipeline (vision §12.1, §12.1a, §12.2, §22) — Phase 2 (text) / Phase 4 (video)

**Purpose.** An ad-account ban kills the system; an IP claim kills the client relationship. Checks run **before** the marketer can approve, as a pre-filter; the human remains final (§12 #18).

| Check | Applies to | Method | Phase |
|-------|-----------|--------|-------|
| Meta policy review | landing text, ad copy, video transcript | LLM reviewer with a prompt built from Meta Advertising Policies (prohibited claims: before/after, health, financial outcomes; personal-attributes wording; misleading claims) → PASS / WARN / FAIL + cited rule | 2 |
| Claims vs product fact base | same | LLM compares every factual claim against `product_facts` (marketer-maintained list per tenant); unsupported claim → WARN; contradicted → FAIL. Catches clickbait and policy violations together (vision §12.2) | 2 |
| Trademark scan | text | LLM + exact match against `trademark_watchlist` (niche competitor names/slogans, maintained per niche) | 2 |
| Face detection vs public figures | video footage, images | MediaPipe/Rekognition face detection → match against public-figure index + competitor-staff list; any hit → FAIL until marketer confirms model release | 4 |
| Music licence | audio track | Audio fingerprint vs royalty-free library manifest; unknown track → FAIL (vision §22 "music rights") | 4 |
| AI-content labelling | any generated asset | Flag assets that need Meta's AI disclosure (C2PA metadata present / avatar used); `requires_ai_label` | 4–5 |
| Manual checklist | everything | Admin shows the checklist (vision §12.1a) — approval button enabled only when all items ticked or overridden with a reason | 2 |

```sql
CREATE TABLE compliance_checks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    entity_type VARCHAR(20) NOT NULL,                  -- LANDING_VARIANT, CREATIVE, BRIEF, OFFER
    entity_id UUID NOT NULL,
    check_type VARCHAR(30) NOT NULL,                   -- META_POLICY, CLAIMS, TRADEMARK, FACE, MUSIC, AI_LABEL
    result VARCHAR(10) NOT NULL,                       -- PASS, WARN, FAIL
    findings JSONB,                                    -- [{rule, excerpt, severity, suggestion}]
    model_version VARCHAR(50),
    overridden_by UUID REFERENCES users(id), override_reason TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE TABLE product_facts (id UUID PRIMARY KEY, tenant_id UUID NOT NULL, fact TEXT NOT NULL, source TEXT, verified_by UUID, created_at TIMESTAMPTZ DEFAULT now());
CREATE TABLE trademark_watchlist (id UUID PRIMARY KEY, niche VARCHAR(100), term TEXT NOT NULL, owner TEXT, created_at TIMESTAMPTZ DEFAULT now());
```

**API.** `POST /api/v1/compliance/check` (sync for text, async job for media), `GET /api/v1/compliance/{entityId}`, `PUT /api/v1/compliance/{id}/override`. **Flow change.** `VariantService.approveVariant()` and the Phase 4 render-approve call require `latest(compliance_checks) ∈ {PASS, overridden}`.

### 15.5 Quality Score & Clickbait Detector (vision §3.1, §12.2, §15.3) — Phase 2 (score) / Phase 3 (vs anchor)

**Purpose.** Leading indicator of clickbait before returns/churn arrive; protective metric alongside the reward.

- `quality_score = w₁·match_score + w₂·(1 − bounce_rate) + w₃·scroll_depth_norm + w₄·form_completion_rate`, computed per closed session (`sessions.quality_score`) and aggregated per creative and per variant (`GET /api/v1/analytics/quality-score`). Weights per tenant, default equal; recalibrated in Phase 3 against realised outcomes (Delayed Churn Penalty, deal won).
- **Clickbait rule (vision §15.3 pre-LKJ heuristic)**: if a creative's CTR > anchor's CTR with P > 0.9 **and** its CVR < anchor's or its bounce > anchor's with P > 0.9 → flag `CLICKBAIT_SUSPECT` on the creative, lower its Quality Score, surface in dashboard and gates. When the Phase 3 GLM with correlated creative effects is live, the rule is replaced by the posterior of corr(z_CTR, z_CVR) < 0.
- **Use in reward**: Quality Score is **not** added to α/β (one binary event per arm, §4.4). It acts as (a) a gate input (Gate 2 Bounce/Scroll already), (b) a multiplier on the Phase 3 profit estimate until real outcome data arrive (vision: "loses weight once final data come"), (c) a dashboard protective metric with frequency and returns.
- Storage: `creative_quality(tenant_id, creative_id, window, quality_score, clickbait_suspect, components JSONB, computed_at)`; FastAPI `POST /quality/score`, `POST /quality/clickbait-check`.

### 15.6 Message-Market Fit Diagnostics + Dynamic Offer Explorer (vision §18, §4.3a) — Phase 2 (mask + floor) / Phase 3 (diagnostics + explorer)

**Phase 2 — make the offer sub-atoms usable.** v1.1 stores `variant_offers` but nothing validates or enforces them:
- **Validity mask** (`OfferValidator`): `discount_type = none ⇒ discount_depth = 0`; `guarantee_type = none ⇒ guarantee_days NULL`; `has_real_urgency = true ⇒ urgency_source ∈ {inventory_api, crm_deadline, seasonal_window}` (no fake timers — vision §4.3a DSA/FTC); invalid combinations rejected at `POST /api/v1/landings`.
- **Margin floor enforcement**: `margin_floor` already on `variant_offers`; add `tenants.settings.min_margin` and `variant_offers.estimated_margin`; FastAPI `load_arms()` **excludes** variants with `estimated_margin < min_margin` from the candidate set (vision: "variants below floor do not participate in Thompson Sampling"). Installment terms are flagged `requires_legal_review` until the tenant's legal checkbox is set.
- **Rule**: offers are tested on **landings** (our randomisation), not on creatives (auction bias) — `creatives.offer_variant` is read-only metadata, never a test dimension, until Phase 3 confirms on landings.

**Phase 3 — Message-Market Fit scan** (FastAPI `POST /mmf/diagnose`, daily job): per audience segment, from the GLM/arm posteriors: `p_any_above_breakeven = P(max_v ROAS_v > breakeven)`; `between_variant_var` from the hierarchical model. If `p_any_above_breakeven < 0.10` **and** `between_variant_var < τ` → `OFFER_PROBLEM` for that audience; also the §18.3 signals (all creatives CPA above threshold for audience; high CTR + high bounce on all landings; conversions but low LTV/high returns). Result written to `mmf_diagnoses(tenant_id, audience_key, p_any, var, verdict, signals JSONB, created_at)` and shown as a **strategic recommendation** card (vision §18.4), never as an automatic action.

**Phase 3 — Dynamic Offer Explorer** (FastAPI `POST /offers/generate` + `/offers/rank`): on `OFFER_PROBLEM`, generate 3–5 alternative sub-atom combinations via LLM (each with a hypothesis string), drop invalid/under-floor ones, rank by posterior predictive from the GLM (sub-atoms are ordinary covariates) with the Universal/niche prior when available, write to `offer_candidates(tenant_id, audience_key, sub_atoms JSONB, hypothesis, rank_score, status PROPOSED/APPROVED/REJECTED/LAUNCHED)`. Marketer approves (`POST /api/v1/offers/candidates/{id}/approve`) → a landing variant is created with the new `variant_offers` row → enters Thompson. Segment-level only, never per-individual pricing (vision §7.4). This is the same candidate-generator pattern as §14 evolution and §9.1 competitive intelligence; share the `*_candidates` → approve → launch state machine.

### 15.7 LLM Content Generation, Message Match Score, RLHA Store (vision §5.1, §6.3a, §13.3) — Phase 2

**Why it is a gap.** The vision's Phase 2 is "constructor **with LLM slot-filling** + message match + RLHA". v1.1/roadmap have the constructor and manual content only, and RLHA is deferred *because* there is no generation. All three ship together.

- **Generation** (FastAPI `POST /content/generate`): inputs = block type + slots, `product_facts` (§15.4), target audience, paired creative's hook text/transcript, best atoms from posteriors (Phase 3; Phase 2 uses descriptive stats), **RLHA few-shot pairs for this tenant** (below), tenant style profile. Output = slot values + `generation_id`. Always creates a DRAFT; never ACTIVE (vision §5.4.1 Process 1). Runs `compliance_checks` automatically on the draft.
- **Message Match score** (FastAPI `POST /content/match-score`): LLM scores creative hook/promise vs landing headline/offer → `landing_variants.match_score` (exists, never filled). Wide prior, low weight in the GLM until calibrated on real CVR (vision §6.3a); the calibration job compares match_score buckets against realised CVR monthly.
- **RLHA store**: every marketer edit of generated content saves the pair.

```sql
CREATE TABLE content_generations (id UUID PRIMARY KEY, tenant_id UUID NOT NULL, entity_type VARCHAR(20), entity_id UUID, block_event_id VARCHAR(50), slot VARCHAR(50), prompt_hash VARCHAR(64), generated_text TEXT NOT NULL, model_version VARCHAR(50), created_at TIMESTAMPTZ DEFAULT now());
CREATE TABLE rlha_corrections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    generation_id UUID REFERENCES content_generations(id),
    generated_text TEXT NOT NULL, approved_text TEXT NOT NULL,
    delta_tags TEXT[],                                 -- LLM-classified: TONE, LENGTH, FORBIDDEN_WORD, STRUCTURE, BRAND_CONTEXT
    context JSONB,                                     -- content type, audience, block
    use_as_few_shot BOOLEAN DEFAULT TRUE,
    edited_by UUID REFERENCES users(id), created_at TIMESTAMPTZ DEFAULT now()
);
```

**Metric** (vision §13.3): `% generations approved without edits`, weekly, per tenant — `GET /api/v1/rlha/metrics`; target 30 % → 70 % over 3 months. Few-shot selection: most recent 8–12 pairs for the same content type; Phase 5+ LoRA when a niche has 200+ pairs (out of scope here, noted).

### 15.8 Voice of Customer (vision §9.2) — Phase 1 (ingest) / Phase 2 (use)

**Purpose.** Real objections from reviews, ad comments (own and competitors'), support chats → material for hooks and landing blocks; also seeds the persona set (§15.9) and the objection taxonomy used by propensity (§7.1 "main objection").

- Ingest: `POST /api/v1/voc/import` (CSV/JSON upload of reviews or chat exports; marketer-driven in Phase 1), Meta ad comments via Graph API for the tenant's own ads (Phase 3, P2 queue lane), competitor ad comments only where the Ad Library/Graph API exposes them.
- Extraction: FastAPI `POST /voc/extract-objections` → LLM clusters quotes into an objection taxonomy (price / trust / understanding / urgency / fit) with representative quotes and counts.
- Storage: `voc_sources(id, tenant_id, source_type REVIEW/AD_COMMENT/SUPPORT_CHAT, imported_at, raw_ref)`, `voc_objections(id, tenant_id, objection_type, label, quote_examples TEXT[], count, first_seen, last_seen)`.
- Use: `GET /api/v1/voc/objections` feeds content generation (§15.7) and the Creator Brief ("objections to address"); objection shares per audience become a feature of the MMF scan (§15.6). PII in quotes is redacted at import (names, emails).

### 15.9 Synthetic Persona Sandboxing (vision §15.12.12) — Phase 3, as an A/B of the method

- `POST /persona/build`: from CRM history (deal notes via GHL), VoC objections (§15.8), niche priors → 4–8 personas per tenant stored in `personas(id, tenant_id, name, description, traits JSONB, source_refs)`.
- `POST /persona/score`: LLM agents score each candidate hook/offer/landing per persona (relevance, trust, P(click)) → `persona_scores(candidate_type, candidate_id, persona_id, scores JSONB, model_version)`.
- **Protocol enforced in code, not in a doc**: `tenants.settings.persona_mode ∈ {OFF, LOG_ONLY, AB_TEST, PREFILTER}`. Default after enabling = `AB_TEST` for 3 months: launch batches are randomly assigned 50/50 to "prefiltered by persona" vs "full pool"; `batch.persona_arm` recorded; the weekly report compares cost-to-find-winner, winner ROAS, and false-kill rate (candidates filtered by personas that later won in the other arm). Promotion to `PREFILTER` requires P(prefilter better) > 0.9 on those metrics; otherwise auto-revert to `LOG_ONLY`. In `AB_TEST`/`LOG_ONLY` scores **never block** a launch.

### 15.10 Taxonomy Alignment Layer (vision §15.12.11) — Phase 1–2 (vision), not 0.5b

**Why now.** v1.1 has `creative_atoms.embedding` but no registry, so every tenant/month can mint a new spelling of the same atom and the shrinkage in Phase 3 pools nothing. The vision puts this in Phase 1–2; the roadmap had it in 0.5b.

```sql
CREATE TABLE atom_registry (                          -- platform-wide, no tenant_id
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    atom_type VARCHAR(50) NOT NULL,
    canonical_value VARCHAR(100) NOT NULL,             -- "hook_question"
    description TEXT,
    centroid vector(768) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'CANDIDATE',   -- CANDIDATE → CONFIRMED after N appearances across ≥2 tenants
    appearance_count INT DEFAULT 0, tenant_count INT DEFAULT 0,
    merged_into UUID REFERENCES atom_registry(id),
    created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now()
);
ALTER TABLE creative_atoms ADD COLUMN registry_id UUID REFERENCES atom_registry(id);   -- the global ID the model uses
ALTER TABLE creative_atoms ADD COLUMN alignment_sim REAL;                               -- cosine to centroid at assignment
```

- `POST /taxonomy/align` is called inside `creative_dna.py` after extraction: embed `atom_type + atom_value + description`; nearest centroid of same `atom_type`; if cosine ≥ θ (start 0.86, tuned) → assign `registry_id`; else create CANDIDATE. Weekly `POST /taxonomy/recalibrate`: re-cluster, merge centroids closer than θ_merge, confirm candidates with ≥ N appearances in ≥ 2 tenants, re-point `registry_id` for merged entries (model reads `registry_id`, never `atom_value`).
- Same embedding store serves the **Niche Creative Entropy Index** (vision §22): weekly cosine-similarity distribution of active hooks across tenants in a niche; below-threshold entropy → diversity penalty in Phase 3 candidate ranking and a "rotate hooks" recommendation (Phase 0.5b/6, roadmap).

### 15.11 Off-Policy Evaluation as the Autopilot Gate (vision §12.5, §23 Phase 3) — Phase 3, blocks Phase 6-6

- FastAPI `POST /ope/evaluate` and weekly job: from `decision_log` (candidates with `propensity`, chosen, reward joined from `events` by `decision_id`) compute **IPS**, **SNIPS** and **doubly-robust** value of the *current* policy (today's posteriors) vs the *logged* policy, with effective sample size (ESS) and bootstrap CI; clip weights at `w_max` (default 10) and report clipped mass. Also replay a "random" and a "always-best-variant" policy as references.
- Storage: `ope_runs(tenant_id, policy_version, logged_from, logged_to, ips, snips, dr, ci_low, ci_high, ess, clipped_mass, verdict, created_at)`.
- **Gate** (§12 #15): `AutonomyService.canUpgrade(tenant, level)` requires latest `ope_runs.verdict = PASS` (P(current ≥ logged) > 0.9, ESS ≥ 200) for Semi-autopilot and Autopilot; also required before raising `exploration_rate` or enabling `auto_scale_winner`. Dashboard shows the OPE card next to the autonomy switch.

### 15.12 Event Schema Registry, Retention, Right to Erasure (vision §23 Phase 0, §22) — Phase 0

```sql
CREATE TABLE event_schemas (event_type VARCHAR(50), schema_version SMALLINT, json_schema JSONB NOT NULL, introduced_at TIMESTAMPTZ DEFAULT now(), deprecated_at TIMESTAMPTZ, PRIMARY KEY (event_type, schema_version));
-- events.schema_version (§4.5) references this; ingestion validates event_data against json_schema and rejects/quarantines mismatches (quarantine table events_rejected)
CREATE TABLE erasure_requests (id UUID PRIMARY KEY, tenant_id UUID NOT NULL, subject_key_type VARCHAR(20), subject_key_hash VARCHAR(64), requested_at TIMESTAMPTZ DEFAULT now(), completed_at TIMESTAMPTZ, rows_deleted JSONB, requested_by UUID);
```

- **Retention policy** (tenant-level default, platform floor): raw `events` 13 months → monthly partitions dropped by the Retention sweep (§9) after `agg_variant_daily` / `agg_creative_daily` aggregates are materialised; `decision_log` 25 months (needed for OPE); `sessions` with PII hashes 13 months; aggregates and model parameters indefinitely (not personal data).
- **Erasure** (`POST /api/v1/privacy/erasure`): locate by `external_id_hash` or `crm_user_id` → delete `sessions`, their `events` and `decision_log` rows, null PII fields in `ghl_webhook_log.payload`, remove from `meta_audience_sync` lists → enqueue arms/GLM recalc → write `erasure_requests.rows_deleted` → confirm. SLA 30 days (GDPR), target 72 h. Erasure of a row that fed a posterior does not require retraining to be lawful (aggregates), but the recalc keeps arms consistent with the log.

### 15.13 Autonomy Levels + Expert Prior/Veto (vision §12.4) — Phase 3

```sql
ALTER TABLE tenants ADD COLUMN autonomy_level VARCHAR(16) NOT NULL DEFAULT 'ADVISOR';   -- OBSERVER, ADVISOR, SEMI_AUTOPILOT, AUTOPILOT
CREATE TABLE expert_ratings (id UUID PRIMARY KEY, tenant_id UUID NOT NULL, entity_type VARCHAR(20), entity_id UUID, rating SMALLINT CHECK (rating BETWEEN 1 AND 5), veto BOOLEAN DEFAULT FALSE, reason TEXT, rated_by UUID, created_at TIMESTAMPTZ DEFAULT now());
```

- **Level semantics enforced by `AutonomyService`** (single place every write-action passes through): OBSERVER — no Meta writes except P0 safety (circuit breaker, Site Health); ADVISOR (default) — every action is a proposal requiring approval (`pending_actions` table; the existing `autopilot_actions` becomes this with `status PROPOSED/APPROVED/EXECUTED/UNDONE`); SEMI_AUTOPILOT — test/rotate inside limits automatically, new concepts and budget increases need approval; AUTOPILOT — full loop inside limits. Upgrade requires the OPE gate (§15.11) and explicit marketer action; downgrade is one click and happens automatically on L3 circuit breaker.
- **Expert prior / veto**: `veto = true` removes the entity from candidates (hard). A rating maps to a prior shift on the creative's optimistic prior (`creatives.exploration_prior_alpha/beta` exist): rating 5 → Beta(3,1), 3 → Beta(2,1) (default), 1 → Beta(1,2); recorded so the Phase 3 model can learn how predictive the marketer's ratings are (vision §8.3 "expert rating as a prior source").

### 15.14 Circuit-Breaker Recovery Protocol (vision §12.5b) — Phase 3

v1.1/roadmap have L1–L3 triggers and `resolveEvent()`. Missing: the protocol after a trigger.

- **Automated post-mortem** (`GET /api/v1/circuit-breaker/{id}/diagnosis`, computed at trigger time by `RecoveryDiagnosisService`): runs the five checks of vision §12.5b and returns a ranked cause list with evidence — EXTERNAL (§15.1 anomaly verdict MARKET / CPM z-score / calendar tag), TECHNICAL (§15.3 incident open), OFFER (§15.6 `OFFER_PROBLEM` for the affected audience), FRAUD (§15.18 suspicious share, L3 scan hits), CASCADE (≥ k killed creatives sharing an atom → graveyard analysis).
- **Marketer confirms cause and plan** (`POST /confirm-cause {cause, plan}`) — required before any resume.
- **Batch resume** (`POST /resume-batch`): step 1 resumes the 2–3 best ad sets at their **previous** budgets; step 2 (+24 h, metrics normal) 2–3 more; step 3 (+48 h) the rest. Never a percentage ramp (≤ 20 % rule). If the pause exceeded **7 days**, ad sets are relaunched as **new** ad sets (learning phase restarts; `TwoContourService.relaunchAsNew`). Re-trigger during recovery → back to L3 + manual review.
- **Logging for calibration**: `circuit_breaker_events` gains `diagnosis JSONB, confirmed_cause, resume_steps JSONB, outcome, pause_duration`; the Phase 3 report "false-positive rate of L2/L3" is computed from it and feeds threshold tuning.

### 15.15 Token Rotation + Immutable Meta API Audit Log (vision §12.7) — Phase 0

- `TokenRotationScheduler` (daily): for every tenant token older than 50 days call the Meta long-lived token exchange; on success re-encrypt and store with `rotated_at`; on failure alert (Telegram/Slack + email) and set `tenants.meta_token_status = EXPIRING`; at expiry → `INVALID` → autonomy downgraded to OBSERVER (safety: Meta Automated Rules still protect spend).
- Envelope encryption: per-tenant data key (random 256-bit) encrypted by the master key (env / KMS); fields `meta_access_token_enc`, `capi_access_token_enc`, GHL API key use the tenant key. Key rotation re-wraps data keys only.
- `meta_api_audit(id, tenant_id, actor_type USER/JOB/SYSTEM, actor_id, action, target_type, target_id, request_hash, response_code, queue_lane P0/P1/P2, duration_ms, created_at)`: written by the single `MetaApiClient` wrapper (the same wrapper that implements the priority queue, §15.20), so no call can bypass it. `mai_app` has INSERT/SELECT only; monthly export to object storage with retention lock. `GET /api/v1/audit-log` for the tenant admin.

### 15.16 Explainability Service (vision §21.2–21.3) — Phase 2

- Principle: text is generated **from model statistics**, LLM only formats. `GET /explain/{tenant_id}/{entity}` assembles the numbers (posterior mean + 95 % CI, n, P(best), anchor comparison, Quality Score, frequency vs learned burnout threshold, external context on the date) into a fixed template; the LLM may rephrase the template but receives **only** those numbers and may not add claims (prompt-level constraint + post-check that every number in the output exists in the input).
- On-demand post-mortem per **campaign** (per-creative exists in Phase 6-5 graveyard): `POST /api/v1/campaigns/{id}/postmortem` → same service with campaign-level stats + circuit-breaker/anomaly history.
- CI honesty (vision §21.2): report Beta-Binomial/overdispersed intervals once the Phase 3 model is live, flag "binomial lower bound" before that.

### 15.17 Predictive-Layer Models Not Yet Specified (vision §15.6–15.8, §15.11) — Phase 4 / 6

| Model | Vision | Where | Phase |
|-------|--------|-------|-------|
| Cost model `log(CPM) = f(atoms, placement, calendar)` | §15.6 ⚠️ | FastAPI `POST /predict/cpm`; feeds profit-per-impression before launch | 4+ |
| Burnout as survival model (Cox PH / Bayesian survival on frequency, initial ROAS, audience, season) | §15.8 task 2 | replaces the Phase 6-1 heuristic score; `POST /burnout/survival` | 6 |
| Interaction terms by Bayes factor, or BART | §15.8 task 1 | `POST /glm/interactions`; candidate ranking uses it when BF > 3 | 6 |
| LightGBM "second opinion" ensemble on GLM features | §15.11 | disagreement between GLM and LightGBM ranking → flag for review, not auto-average | 4+ |
| Creative-space embedding (VAE / contrastive) | §15.8 task 4 | research; reuse `atom_registry` embeddings as the first baseline | 6+/7 |
| Profit-per-impression reward | §3.1, §15.6 | Phase 3 GLM/TS: `profit = revenue × margin − cost_per_impression`; margin from `tenants.settings`/offer; cost = observed CPM per ad set until the CPM model exists | 3 |

### 15.18 Fraud Protection — Missing Layers (vision §12.6.2) — Phase 0 (L2) / Phase 3 (L3)

- **L2 additions** (inline on ingest, mobile-safe): inter-event timing σ < 50 ms over ≥ 5 events → `AUTOMATED_TIMING`; constant scroll velocity with no acceleration → `LINEAR_SCROLL`; IP cluster rule extended to **IP + UA + viewport** (CGNAT-safe); zero-scroll + < 1 s **and** no touch (mobile) / no mouse move (desktop) — split by device. Edge filter runs **log-only for 2–4 weeks** per tenant (`tenants.settings.fraud_mode = LOG_ONLY|ENFORCE`), with an allowlist for Meta crawlers and in-app browsers.
- **L3 funnel anomalies** (hourly job): CTR up without conversions by geo × placement; click bursts in the target geo's night hours (per-tenant timezone table, vision's US example 07:00–12:00 UTC); one ad set's CTR anomalous vs the same creative in other ad sets; **first-party clicks vs Meta-reported clicks** per ad set — delta ≈ invalid clicks (vision §25 p.20 alternative since Invalid Click Rate is not confirmed in the API). Hits → alert + auto-exclude sessions from learning.
- **L4 contract restated**: suspicious sessions are excluded from atom-effect estimation **but kept in the denominator** of profit/impression; share > 10 % → alert with source breakdown. reCAPTCHA Enterprise v3 invisible stays optional, form-level only, never v2.

### 15.19 Proxy-Value Calibration & CRM Discipline (vision §16.1, §22) — Phase 3

- Weekly `ProxyCalibrationJob` (FastAPI): for cohorts whose outcome is known (won/lost/no-show), compare the proxy value that was sent to CAPI with the realised value; report bias per stage; adjust the multipliers (0.15 / 0.40 defaults) within marketer-set bounds; store in `proxy_calibration_runs`.
- **A/B of proxy value itself** (vision): `tenants.settings.capi_value_mode = PROXY | FIXED | AB`; in AB, ad sets are split and the report compares lead quality and CPA between proxy-valued and fixed-valued cohorts before PROXY becomes the default for that tenant.
- **CRM discipline report** (weekly, Spring): appointments older than 24 h with no `showed/noshow`; count and list to the tenant's channel; alert when > 20 % of the last 30 days' appointments are unmarked (this is the §22 risk made operational; without it the Quality Gate starves silently).

### 15.20 Meta API Priority Queue & Two-Contour Rules (vision §10.3, §6.5) — Phase 3

- **Queue**: all Marketing API / CAPI / Ad Library calls go through `MetaApiClient` with three lanes — **P0** (circuit breaker pause/resume, Site Health autopause, budget ceiling) bypasses the queue and has a reserved 10 % of the quota; **P1** (ad set create/pause, budget changes, CAPI events); **P2** (learning-phase polls, insights reads, Ad Library polling) — throttled first when the token bucket nears the limit. Per-token bucket sized from `X-Business-Use-Case-Usage` / `X-App-Usage` headers; metrics exported: quota used, time to reset, P2 backlog. Error 17/613 → exponential backoff on P1/P2 only; P0 retries immediately on the reserve. Fallback remains Meta Automated Rules (§12 #13). Lives in **Spring** (all Meta calls are there in this architecture; the vision placed it in FastAPI — resolved in favour of where the calls are).
- **Learning-phase snapshot**: `learning_phase_log(ad_set_id, phase_before, phase_after, action, at)` written around every write and re-read at +24 h — the vision's rule "log learning status before/after every launch", so the "may reset" claim gets our own evidence.
- **Creative Testing tool**: `TwoContourService.launchTestBatch(mode = ISOLATED_AD_SETS | CREATIVE_TESTING_TOOL)`; the second mode uses Meta's 2025 Creative Testing feature inside an existing ad set when the API exposes it (⚠️ verify availability and bias, vision §25 p.14); default stays isolated ad sets.
- **Bid Cap soft launch**: `confirmAndTransfer(bidCap = target_cpa × 1.2)` optional flag when duplicating a winner into the scale campaign; recorded so under-delivery can be attributed to the cap.

### 15.21 Smaller Gaps Folded Into Existing Modules

| Vision | Gap | Resolution |
|--------|-----|-----------|
| §7.2 | behavioural signals | new event types in §4.5; tracker emits VIDEO_PROGRESS, QUIZ_ANSWER/CALC_INTERACT, FIRST_CLICK, RETURN_VISIT; `sessions.visit_count` |
| §7.4 | cross-device | `sessions.external_id_hash`, hashed `external_id` added to CAPI payloads after consent, `RetroCorrectionJob` flips earlier bounce (§9) |
| §5.4.1 Process 3 | marketer-approved reorder rules | `personalization_rules(tenant_id, cluster_label, allowed_block_order TEXT[], approved_by)` replaces hard-coded `CLUSTER_BLOCK_RULES`; `/decide/personalise` reads it (Phase 5) |
| §7.3 stage 5 | retargeting creative by segment | `segment_creative_map(tenant_id, cluster_label, creative_id)` used when syncing Custom Audiences (Phase 5) |
| §5.5 | brief versioning, pairing, creator effect | `creator_briefs.version`, `paired_brief_id` (video ↔ landing), `result_creative_id`; `creatives.creator_id` → random effect in the Phase 3+ GLM; per-segment briefs Phase 5; Creator Marketplace seeding Phase 6+ (skeleton only) |
| §5.2 | AI avatars / text-to-video / Voice-as-Atom | Phase 5 behind `tenants.settings.ai_video_enabled`; provider adapter (HeyGen/Synthesia/Runway); mandatory audience-acceptance A/B before any scale use; voice atoms `VOICE_TONE`, `VOICE_PACE`; written consent + AI label required (§15.4) |
| §17.3 | Geo-Lift, Meta Experiments API | Phase 5+: `geo_lift_tests` + CausalImpact runner in FastAPI; Experiments API wrapper in `MetaApiClient` (P2) |
| §8.5 | portfolio optimisation | Phase 6+, conditional on data; `POST /predict/portfolio` behind a flag, never conflicts with CBO inside a campaign |
| §3.2 | diagnostics gaps | baseline per placement × device (Simpson), Benjamini–Hochberg on the symptom list, "LPV ≪ clicks" check wired to Site Health + pixel EMQ |
| §24 v1.9.9 | month-1 client view | dashboard widgets: Quality-Gate filtered leads (money saved), date-to-significance from the power calculator |
| §15.12.5/7, §6.4 | Universal model specifics | relative z-score thresholds vs niche × placement × device, FPR calibration < 5 %, aggregate floor ≥ 5 tenants, StudentT(ν=4) niche priors — Phase 0.5b (roadmap) |

---

## 16. Client #1 Adaptation — Investor Funnel, Reg D 506(c) (vision §19) **[v1.3 — added]**

> Vision §19 established that client #1 runs an **investor lead-gen funnel** (booking a call with the team; accredited investors; $75K average ticket), not a B2B product sale. Several modules already implemented against the old "form → call → meeting → deal" chain must be re-pointed. This section specifies the changes as **configuration over existing modules** wherever possible, so the generic B2B path stays available for the next vertical. Each subsection names the module it changes.

### 16.1 Design principle: funnel as configuration, not code

The old chain is hard-wired in three places: the CAPI gate (`CAPIForwardingService`: call→`Lead`×0.15, showed→`Schedule`×0.40, won→`Purchase`), the reward map (`/decide/reward`: CALL_ANSWERED/MEETING_ATTENDED→α), and `thompson_arms.reward_event` defaults. All three move behind one per-tenant **funnel definition**:

```sql
CREATE TABLE funnel_stages (                         -- per tenant, ordered
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    stage_order SMALLINT NOT NULL,                   -- 1..N
    stage_key VARCHAR(40) NOT NULL,                  -- FORM_SUBMIT, BOOKED, COORD_CONFIRMED, CALL_HELD, ACCRED_VERIFIED, FUNDS_RECEIVED ...
    source VARCHAR(30) NOT NULL,                     -- FIRST_PARTY, GHL_APPOINTMENT, GHL_OPPORTUNITY, GHL_CONVERSATION, GHL_FIELD
    source_match JSONB NOT NULL,                     -- e.g. {"event":"AppointmentUpdate","appointmentStatus":"showed"}
    is_quality_key BOOLEAN DEFAULT FALSE,            -- the stage gates/QS use as "quality" (CALL_HELD for client #1)
    is_terminal BOOLEAN DEFAULT FALSE,               -- FUNDS_RECEIVED
    expected_value_usd REAL,                         -- base E[value | reached stage]; §16.4
    UNIQUE(tenant_id, stage_key), UNIQUE(tenant_id, stage_order)
);
CREATE TABLE capi_event_map (                        -- replaces the hard-coded chain in CAPIForwardingService
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    stage_key VARCHAR(40) NOT NULL,
    pixel_role VARCHAR(20) NOT NULL,                 -- PRIMARY, TEST, PRODUCT, NONE (NONE = do not send)
    meta_event VARCHAR(40),                          -- Lead, Schedule, CompleteRegistration, Purchase, or custom name
    value_formula VARCHAR(200),                      -- "base * k_accr * k_ticket" — evaluated by ValueFormulaService (§16.4)
    send_condition JSONB,                            -- {"self_accredited": true} → non-accredited leads not sent to PRIMARY
    UNIQUE(tenant_id, stage_key, pixel_role)
);
```

`FunnelService.resolveStage(event)` maps any incoming first-party or GHL event to a `stage_key`; `CAPIForwardingService` and `/decide/reward` consume `stage_key`, never raw event names. The B2B default (`FORM_SUBMIT → CALL_ANSWERED → MEETING_ATTENDED → DEAL_WON`) is seeded as the template; client #1 gets the §16.2 funnel. **Changes:** §13.1 handler, §13.4, §4.4 reward comment, roadmap 0-5 and 2-6 items.

### 16.2 Client #1 funnel definition (vision §19.2) and new events

| order | stage_key | source | match | quality key | E[value] |
|------:|-----------|--------|-------|-------------|----------|
| 1 | `CALENDAR_OPEN` | FIRST_PARTY | event_type `CALENDAR_OPEN` | | |
| 2 | `SLOT_SELECTED` | FIRST_PARTY | event_type `SLOT_SELECTED` (payload: `slot_at`, `booking_distance_h`, `browser_tz`) | | |
| 3 | `FORM_SUBMIT` | FIRST_PARTY | `FORM_SUBMIT` (payload: `self_accredited`, `ticket_bucket`) | | $4K |
| 4 | `BOOKED` | GHL_APPOINTMENT | `AppointmentCreate` | | $7.6K |
| 5 | `COORD_CONFIRMED` | GHL_CONVERSATION / GHL_FIELD | coordinator outcome field = confirmed (⚠️ exact GHL field) | | |
| 6 | `CALL_HELD` | GHL_APPOINTMENT | `AppointmentUpdate.appointmentStatus = showed` | **yes** | $15.8K |
| 6' | `NO_SHOW` | GHL_APPOINTMENT | `appointmentStatus = noshow` or 7-day timeout (existing `AppointmentCheckScheduler`) | | 0 |
| 7 | `FOLLOWUP_HELD` | GHL_APPOINTMENT | second appointment showed | | |
| 8 | `ACCRED_VERIFIED` | GHL_FIELD | custom field `accreditation_verified = true` | | |
| 9 | `INVEST_COMMITTED` | GHL_OPPORTUNITY | stage = committed | | |
| 10 | `FUNDS_RECEIVED` | GHL_OPPORTUNITY | `OpportunityStatusUpdate.status = won`, `monetaryValue` | terminal | actual |

New first-party event types registered in `event_schemas` (§15.12): `CALENDAR_OPEN`, `SLOT_SELECTED`. Tracker emits them from the booking widget; `SLOT_SELECTED.booking_distance_h` and `browser_tz` are the two landing-side predictors of no-show that the client has never measured.

Schema additions (extend the existing `appointments` table from roadmap 0-10 and `sessions`):

```sql
ALTER TABLE appointments ADD COLUMN booking_distance_h REAL;        -- hours from booking to slot
ALTER TABLE appointments ADD COLUMN slot_tz VARCHAR(64);             -- tz of the slot as shown
ALTER TABLE appointments ADD COLUMN lead_tz VARCHAR(64);             -- browser tz at booking (null = unknown → coordinator confirms)
ALTER TABLE appointments ADD COLUMN coord_confirmed_at TIMESTAMPTZ;
ALTER TABLE appointments ADD COLUMN followup_variant_id UUID;        -- §16.5
ALTER TABLE sessions ADD COLUMN self_accredited BOOLEAN;             -- from form
ALTER TABLE sessions ADD COLUMN ticket_bucket VARCHAR(12);           -- LT25K, 25_50K, 50_100K, 100_250K, GT250K
ALTER TABLE sessions ADD COLUMN accreditation_verified_at TIMESTAMPTZ;
```

### 16.3 CAPI chain v2 and pixel routing (vision §19.4) — changes `CAPIForwardingService`, `meta_pixels`

`capi_event_map` rows for client #1 (**[v1.5 — added]** `ValueFormulaService.seedInvestorCAPIMap` seeds exactly these rows since roadmap 9-7 (2026-10-20); before that the seed had BOOKED→Lead, CALL_HELD→Schedule, ACCRED_VERIFIED→CompleteRegistration, INVEST_COMMITTED→Purchase, which contradicted vision §19.4):

| stage_key | pixel_role | meta_event | value_formula | send_condition |
|-----------|------------|------------|---------------|----------------|
| FORM_SUBMIT | PRIMARY | `Lead` | `4000 * k_accr * k_ticket` | `self_accredited = true` |
| FORM_SUBMIT | PRODUCT (`future_regcf`) | `Lead` | `0` | `self_accredited = false` |
| BOOKED | PRIMARY | `Schedule` | `7600 * k_ticket` | `self_accredited = true` |
| CALL_HELD | PRIMARY | `CompleteRegistration` | `15800 * k_ticket` | — |
| NO_SHOW | NONE | — | — | — |
| ACCRED_VERIFIED | PRIMARY | custom `AccreditationVerified` | `0` | — |
| FUNDS_RECEIVED | PRIMARY | `Purchase` | `monetaryValue` | — |

- `k_accr`: yes = 1.0, no = 0 (and routed to the `future_regcf` audience pixel/Custom Audience instead of PRIMARY). `k_ticket`: LT25K 0.3, 25_50K 0.6, 50_100K 1.0, 100_250K 1.8, GT250K 3.0 — stored in `tenants.settings.value_coeffs`, recalibrated weekly (§16.4).
- **One `Purchase` per lead**, same `event_id` lineage as §4.5; `CompleteRegistration` is a standard event and can be chosen as the optimisation event once volume ≥ 50/week. ⚠️ Verify whether a later `value` update for an already-sent `event_id` is honoured by CAPI; if not, value optimisation stays on `Schedule` with `k_ticket` only, and `CompleteRegistration` carries the quality signal as a separate event.
- `meta_pixels.purpose` already has PRIMARY/TEST/PRODUCT; add `role_key` (`investor_primary`, `future_regcf`, `webinar`, `agency_carpediem`, `agency_omm`) so the map can target them by name. Agency pixels = TEST role; webinar = PRODUCT.
- The GHL `appointmentStatus` discipline (§13, §15.19) now decides `CompleteRegistration`; the weekly "appointments without status" report becomes a hard dependency, not a nice-to-have.

### 16.4 Value coefficients and calibration (vision §19.7, §19.14) — extends §15.19

`ValueFormulaService` evaluates `value_formula` with `base` from `funnel_stages.expected_value_usd` and coefficients from `tenants.settings.value_coeffs`. Weekly `ProxyCalibrationJob` (exists, 3-21) gains two tables:
- **Self-report calibration**: P(ACCRED_VERIFIED | self_accredited = yes) per creative and per `ticket_bucket`; if a creative's verified share falls below the tenant threshold, `k_accr` for its leads is scaled down (retrospective value correction via §6.7 mechanics, cap 30 %).
- **Stage value refresh**: `expected_value_usd` per stage re-estimated from cohorts with known outcome (min cohort 20), replacing the §19.2 seed values ($4K / $7.6K / $15.8K).
- `capi_value_mode = PROXY | FIXED | AB` (3-21) applies unchanged; the AB compares cost per CALL_HELD and verified share.

### 16.5 Follow-up Sequence Optimisation (vision §19.6) — new module, Phase 2 infrastructure

**Purpose.** Treat the §6.3 graph edge "which follow-up?" as a contextual bandit whose reward is `CALL_HELD`. The system **chooses** a sequence variant per booking; **GHL workflows execute** it. No messaging is sent from our services.

```sql
CREATE TABLE followup_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(100) NOT NULL,
    ghl_workflow_id VARCHAR(100) NOT NULL,          -- the workflow GHL runs when the lead gets this tag
    ghl_tag VARCHAR(100) NOT NULL,                  -- tag we set on the contact to trigger it
    cadence JSONB NOT NULL,                         -- [{"offset_min":-1440,"channel":"SMS","template":"confirm_agenda"}, ...]
    channels TEXT[] NOT NULL,                       -- SMS, EMAIL, WHATSAPP, VOICE_NOTE, COORD_CALL, AI_CALL
    max_touches SMALLINT NOT NULL,                  -- anti-spam cap set by client
    audience VARCHAR(20) NOT NULL DEFAULT 'BOOKED', -- BOOKED (pre-call) | NO_SHOW (re-engagement) | FORM_NO_SLOT
    status VARCHAR(12) DEFAULT 'DRAFT',             -- DRAFT, REVIEW, ACTIVE, PAUSED (approval = marketer, §12.4)
    compliance_check_id UUID,                       -- §15.4: templates pass text compliance
    created_at TIMESTAMPTZ DEFAULT now()
);
CREATE TABLE followup_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    appointment_id UUID NOT NULL,                   -- or contact for FORM_NO_SLOT
    variant_id UUID NOT NULL REFERENCES followup_variants(id),
    decision_id UUID NOT NULL,                      -- row in decision_log (propensity logged like any Thompson decision)
    context JSONB,                                  -- geo, lead_tz, booking_distance_h, ticket_bucket, self_accredited, creative_id, device, is_international
    outcome VARCHAR(20),                            -- CALL_HELD, NO_SHOW, RESCHEDULED_HELD, RESCHEDULED_NO_SHOW, CANCELLED
    outcome_at TIMESTAMPTZ,
    assigned_at TIMESTAMPTZ DEFAULT now()
);
```

- **Decision**: FastAPI `POST /decide/followup {tenant_id, appointment_id, context}` → Thompson over ACTIVE variants for the matching `audience`; arms keyed by `(variant_id, context_key)` where `context_key` ∈ {`intl`, `domestic`} × `ticket_bucket` group (coarse at first; finer when ESS allows). Reuses `thompson_arms` with `reward_event = CALL_HELD` and the §4.4 single-writer rule — FastAPI's recalc derives α/β from `followup_assignments.outcome`.
- **Execution**: Spring sets `ghl_tag` on the contact via GHL API (P1 lane); GHL workflow runs the cadence. Outcome arrives through the existing appointment webhooks → `outcome` filled by `FunnelService`.
- **Guard rails**: variants are approved content (§12.4 Advisor); `max_touches` enforced in the variant, not trusted to the workflow; exploration share 10–20 % (§6.4); a variant with P(worse than control) > 0.95 after ESS ≥ 100 is auto-paused and alerted.
- **Control arm**: the client's current cadence (24h / 1h / 15m, SMS + email) is `variant 0` and never paused — it is the baseline for the "< 30 % no-show" target report.
- **Re-engagement audience** (`NO_SHOW`): the ~200-lead pool; variants = call+voice note / SMS reschedule link / 2-day retry / AI-agent call; reward = `RESCHEDULED_HELD`.
- **Reporting**: weekly card from the explain service (§15.16): show-rate per variant with CI, P(best), effect by `intl` vs `domestic`.
- **API**: `GET/POST/PUT /api/v1/followup/variants`, `POST /api/v1/followup/variants/{id}/approve`, `GET /api/v1/followup/report`.

### 16.6 Reward ladder configuration (vision §19.3) — changes `thompson_arms.reward_event`, gates

- Landing arms: `reward_event = SLOT_SELECTED` (L1, hundreds/week) as primary row, `FORM_SUBMIT` (L2) as second row; `REWARD_ON_FORM_SUBMIT` flag (Audit F1) is superseded by per-tenant `reward_levels` in `tenants.settings`: `{"L1":"SLOT_SELECTED","L2":"FORM_SUBMIT","weights":{"L1":0.4,"L2":0.6},"corrector":"CALL_HELD"}`.
- **Weekly corrector job** (new, FastAPI): per landing variant and per creative, `show_rate = CALL_HELD / BOOKED` with Beta shrinkage to the tenant mean; adjusts the L1/L2 weights of that arm's combined score by `show_rate / mean_show_rate` (clipped 0.5–1.5). Not an α/β increment — a weight on the combination, as §24 requires.
- **Gates** (3-4): Gate 2 metric set += `booking_rate` (BOOKED / clicks); Gate 3 metric = **cost per CALL_HELD vs anchor** (not CPA per booking); kill rule adds `P(show_rate < anchor show_rate) > 0.9`. `GateEvaluationService` reads the quality stage from `funnel_stages.is_quality_key`.
- **Quality Score** (2-12): component `show_rate` added with the highest default weight for tenants whose quality key is an appointment stage.
- **No-show attribution model** (3-1 extension): `logit(show_rate) = Σ β_j·atom_j + γ·booking_distance + δ·tz_known + segment effects` as a separate stage model; its β feed the brief (§19.10) and the clickbait detector (3-14).

### 16.7 Compliance for securities marketing (vision §19.9) — extends §15.4

```sql
CREATE TABLE compliance_rulebases (id UUID PRIMARY KEY, tenant_id UUID, name VARCHAR(40) NOT NULL, -- META_FINANCIAL, META_PERSONAL_ATTR, SEC_506C, CLIENT_LEGAL
    rules JSONB NOT NULL, source TEXT, approved_by UUID, version INT, created_at TIMESTAMPTZ DEFAULT now());
CREATE TABLE approved_phrases (id UUID PRIMARY KEY, tenant_id UUID NOT NULL, phrase TEXT NOT NULL, context VARCHAR(40), -- HOOK, CTA, DISCLAIMER, PROOF
    approved_by UUID, approved_at TIMESTAMPTZ, source_generation_id UUID, UNIQUE(tenant_id, phrase));
```

- The LLM reviewer (2-11) runs **all active rulebases** for the tenant; client #1 gets `META_FINANCIAL`, `META_PERSONAL_ATTR` (rule: *describe the offer, never the viewer* — flag second-person financial-status statements), `SEC_506C` (no return guarantees/forecasts without caveat; risk disclosure present; forward-looking caveat; consistency with Form D materials; "accredited investors only" wording), `CLIENT_LEGAL` (the lawyer's own list). `SEC_506C` rules are drafted **with the client's counsel** and versioned.
- `approved_phrases` is filled from every text that passed CEO + lawyer; content generation (2-9) composes from it first and marks new phrases `needs_legal`; RLHA (2-10) tags lawyer edits `delta_tags = LEGAL`. Metric: share of texts without legal edits.
- **Blocked-creative recovery job**: for creatives with graveyard reason `blocked_by_meta`, generate variation briefs (4-2) that keep hook/structure/proof atoms and swap copy to approved phrases; compliance check before render.
- Add graveyard reasons (6-5): `blocked_by_meta`, `no_show`, `low_accredited_share`.

### 16.8 Investor offer atom set (vision §19.11) — makes `variant_offers` schema-per-vertical

`variant_offers` is B2B/e-commerce specific (discount, guarantee, installment). Replace with a per-tenant offer schema:

```sql
CREATE TABLE offer_atom_schemas (id UUID PRIMARY KEY, tenant_id UUID, vertical VARCHAR(40) NOT NULL, -- B2B_PRODUCT, INVESTOR_RAISE, ...
    atoms JSONB NOT NULL);  -- [{"key":"first_touch_format","values":["call","webinar","event","video_presentation"]}, {"key":"entry_threshold_shown","values":["none","from_25k","from_50k"]}, {"key":"proof_type",...}, {"key":"urgency","values":["none","round_close_date","allocation_left"],"must_be_real":true}, {"key":"founder_presence",...}, {"key":"followup_offer",...}]
ALTER TABLE variant_offers ADD COLUMN atoms JSONB;   -- values keyed by the tenant's schema; legacy columns kept for B2B tenants
```

`OfferValidator` (2-2) validates against the tenant's schema; for `INVESTOR_RAISE` the margin-floor check is replaced by **`compliance_checks` PASS on the offer text** (vision §19.11). Dynamic Offer Explorer (3-19) generates over the schema's atoms.

### 16.9 Attribution across GHL sub-accounts and agencies (vision §19.12) — changes §13.2, `meta_pixels`, dashboard

- At `FORM_SUBMIT`, Spring writes `mai_event_id` and `mai_session_id` into **GHL contact custom fields** via API (P1), before the contact can be moved. Linking order: custom fields → email/phone hash → UTM (last). Webhooks registered on **every** sub-account (`ghl_subaccounts(tenant_id, location_id, role MAIN/AGENCY/WEBINAR/MAILING, webhook_secret)`); opportunities are read from the MAIN location only.
- Per-agency pixels are `meta_pixels.purpose = TEST` with `role_key`; `TwoContourService` treats each agency account as a test contour; winners move to the client's primary via the confirmation run (3-2).
- Dashboard extension: creative-level attribution continues past CALL_HELD to `ACCRED_VERIFIED` and `FUNDS_RECEIVED` (client's dashboard stops at the held call). Legacy creative names mapped once via `creative_aliases(tenant_id, alias, creative_id)`.
- Daily integrity check (§15.1 anomaly job): `BOOKED` in GHL vs `Schedule` in Meta vs `FORM_SUBMIT` first-party — deltas > 10 % alert.

### 16.10 External signals role per funnel (vision §19.13) — config on §15.1

`tenants.settings.funnels = [{"key":"investor","weather_role":"NARRATIVE","news_tags":["drought","water_policy","agtech_funding"]},{"key":"grower","weather_role":"DEMAND","geos":["US-IL","US-MO"]}]`. Sessions are tagged with `funnel_key` from the landing (investor landings vs event/webinar landings). The GLM uses `weather_bucket` as covariate only for `DEMAND` funnels; for `NARRATIVE` funnels the drought/news tags enter via `news_sentiment`/topic tags. Calendar gets `round_close_date` and event dates (PTI, Missouri). Two anchors are mandatory in the test contour (3-14) — with one live creative the market/creative distinction is otherwise impossible.

### 16.11 Jobs and routes added by §16

| Job | Frequency | Service | Description |
|-----|-----------|---------|-------------|
| Show-rate corrector | Weekly | FastAPI | Per variant/creative `CALL_HELD / BOOKED` with shrinkage → L1/L2 weight adjustment (§16.6) |
| Self-report calibration | Weekly | FastAPI | P(verified given self_accredited) per creative/ticket → `k_accr` scaling (§16.4) |
| Stage value refresh | Weekly | FastAPI | Re-estimate `expected_value_usd` per stage from closed cohorts (min 20) (§16.4) |
| Follow-up recalc | Every 15 min | FastAPI | α/β for follow-up arms from `followup_assignments.outcome` (§16.5) |
| Follow-up auto-pause | Hourly | FastAPI | Pause variants with P(worse than control) > 0.95, ESS ≥ 100 (§16.5) |
| Blocked-creative recovery | On graveyard insert `blocked_by_meta` | Spring → FastAPI | Variation brief with approved phrases (§16.7) |
| GHL contact tagging | On FORM_SUBMIT | Spring | Write `mai_event_id`/`mai_session_id` custom fields; set follow-up tag (§16.5, §16.9) |
| Funnel integrity check | Daily | Spring | BOOKED vs Schedule vs FORM_SUBMIT deltas (§16.9) |

| Area | Routes |
|------|--------|
| Funnel config | `GET/PUT /api/v1/tenants/me/funnel` (stages + capi map + reward levels), `GET /api/v1/funnel/stages/{contactId}` |
| Follow-up | `GET/POST/PUT /api/v1/followup/variants`, `POST /api/v1/followup/variants/{id}/approve`, `GET /api/v1/followup/report`; FastAPI `POST /decide/followup` |
| Compliance | `GET/PUT /api/v1/compliance/rulebases`, `GET/POST /api/v1/compliance/approved-phrases` |
| Offers | `GET/PUT /api/v1/tenants/me/offer-schema` |
| GHL | `GET/POST /api/v1/ghl/subaccounts`, `POST /api/v1/creatives/aliases` |

### 16.12 What §16 changes in earlier sections

| Section | Change |
|---------|--------|
| §4.4 | `reward_event` values come from `tenants.settings.reward_levels`; client #1: `SLOT_SELECTED` / `FORM_SUBMIT`; corrector `CALL_HELD` |
| §4.5 | new event types `CALENDAR_OPEN`, `SLOT_SELECTED`; `FORM_SUBMIT` payload gains `self_accredited`, `ticket_bucket` |
| §4.8 | `sessions` + `self_accredited`, `ticket_bucket`, `accreditation_verified_at`, `funnel_key` |
| §13.1, §13.4 | handler emits `stage_key` via `FunnelService`; the proxy chain table in §13.4 is the **B2B template**; client #1 uses §16.3 |
| §15.4 | rulebases + approved phrases |
| §15.5 | Quality Score `show_rate` component |
| §15.6 | offer schema per vertical; margin floor → compliance PASS for investor raise |
| §15.19 | self-report calibration, stage value refresh |
| §15.1 | `weather_role` per funnel |
| §9 | jobs in §16.11 |

### 16.13 Beyond-current-capability modules (vision §19.18) **[v1.4 — added]** — additive only

> §16.13–16.20 add capabilities the client cannot reproduce with their stack. Rule for this block: **new tables, new columns with defaults, new endpoints, new jobs — no changes to existing tables' semantics, services' contracts or migrations.** Each subsection names the implemented module it extends.

### 16.14 Funnel Path as a Thompson Arm (vision §19.18.1) — extends landing variants + `/decide/landing`

```sql
CREATE TABLE funnel_paths (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    path_key VARCHAR(30) NOT NULL,                 -- A_CALL_4D (current), B_MATERIALS_FIRST, C_TIERED_BY_TICKET, D_CALL_24H
    name VARCHAR(100), description TEXT,
    steps JSONB NOT NULL,                          -- ordered pages/blocks: [{"page":"landing"},{"page":"materials","gate":{"video_pct":50,"dataroom_open":true}},{"page":"booking","calendar_window_days":4}]
    eligibility JSONB,                             -- {"ticket_bucket":[">=50_100K"]} for tiered paths
    status VARCHAR(12) DEFAULT 'DRAFT',            -- marketer approval as for variants
    created_at TIMESTAMPTZ DEFAULT now(), UNIQUE(tenant_id, path_key)
);
ALTER TABLE landing_variants ADD COLUMN funnel_path_id UUID REFERENCES funnel_paths(id);   -- NULL = path A (current behaviour, so nothing changes for existing variants)
```

- `/decide/landing` is unchanged: a (variant, path) pair is just a variant row with `funnel_path_id` set; arms stay `thompson_arms(variant_id, context_key, reward_event)`. The **reward for path comparison** is a new arm row with `reward_event = CALL_HELD` and a second with `INVEST_COMMITTED`, normalised per 1 000 LPV by the existing recalc (denominator = `PAGE_VIEW` of that variant). Existing arms (`SLOT_SELECTED`/`FORM_SUBMIT`) remain for landing-content decisions.
- Path pages (materials, webinar registration, data-room-light) are new block components (`MaterialsBlock`, `DataRoomBlock`, `WebinarRegBlock`) rendered by the existing `LandingRenderer`; engagement events `MATERIAL_VIEW`, `VIDEO_PROGRESS`, `DATAROOM_OPEN` registered in `event_schemas`.
- Path B gating (`invite to call only if engaged`) is evaluated by a new `PathGateService` on event ingest; it sets a GHL tag that triggers the booking invitation workflow — reuses the §16.5 tag→workflow mechanism.
- Routes: `GET/POST/PUT /api/v1/funnel-paths`, `POST /api/v1/funnel-paths/{id}/approve`; FastAPI unchanged.

### 16.15 No-Show Propensity, Risk-Tiered Handling, Slot Overbooking (vision §19.18.2) — extends `propensity_service` (5-2), follow-up (§16.5), calendar widget

```sql
CREATE TABLE noshow_scores (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, appointment_id UUID NOT NULL, session_id VARCHAR(100),
    p_show REAL NOT NULL, risk_tier VARCHAR(8) NOT NULL,   -- LOW, MID, HIGH (tenant thresholds)
    features JSONB, model_version VARCHAR(50), scored_at TIMESTAMPTZ DEFAULT now()
);
CREATE TABLE slot_capacity_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL, calendar_id VARCHAR(100) NOT NULL,
    max_overbook REAL NOT NULL DEFAULT 1.0,       -- 1.0 = no overbooking; 2.0 = up to two HIGH-risk bookings per slot
    target_fill REAL NOT NULL DEFAULT 0.9,        -- desired P(at least one shows)
    enabled BOOLEAN DEFAULT FALSE,
    UNIQUE(tenant_id, calendar_id)
);
```

- FastAPI `POST /propensity/noshow` (new router beside `/propensity/score`): target `CALL_HELD` over historical `appointments` (≈ 500 with outcomes); features = session behaviour (existing `extract_propensity_features`), `booking_distance_h`, `lead_tz IS NOT NULL`, `ticket_bucket`, `self_accredited`, `creative_id`, device, country. Rule-based fallback until AUC ≥ 0.70 on holdout; model stored in existing `propensity_models`.
- Scored on `BOOKED`; `risk_tier` written to `followup_assignments.context` so `/decide/followup` (§16.5) **conditions on it** — HIGH-risk arms may include "coordinator call ≤ 15 min + nearer slot offer + path-B materials"; no new decision engine.
- **Overbooking**: `SlotCapacityService` computes, per slot, `P(no one shows) = Π (1 − p_show_i)`; while it exceeds `1 − target_fill` and bookings < `max_overbook`, the booking widget keeps the slot open. Double show → GHL workflow "coordinator takes second lead, priority reschedule". Default `enabled = false`; client turns it on per calendar.
- **Dynamic calendar window**: widget requests `GET /api/v1/booking/window?session_id=` → returns `window_days` from the session's pre-booking score (LOW risk → nearest slots; HIGH → window with a warm-up day). Falls back to the variant's configured window.
- Routes: `GET /api/v1/noshow/scores?appointmentId=`, `GET/PUT /api/v1/booking/capacity-policies`, `GET /api/v1/booking/window`.

### 16.16 Predicted Lead Value at Day 0 (vision §19.18.3) — extends `capi_event_map.value_formula` (§16.3), `ProxyCalibrationJob` (3-21)

- FastAPI `POST /predict/lead-value {tenant_id, session_id}` → `E[value] = p_show × p_verified_given_show × p_invest_given_verified × E[ticket|bucket]`; `p_show` from §16.15, `p_verified` from the self-report calibration table (§16.4), `p_invest` per segment constant (seed 0.21) until ≥ 20 outcomes per segment, `E[ticket]` from bucket midpoints then realised means. Returns value + component breakdown + `model_version`.
- `ValueFormulaService` gains the token **`predicted_value`** (additive; existing formulas with `base·k_accr·k_ticket` keep working). Client #1 `FORM_SUBMIT → Lead` row may switch its formula to `predicted_value` once calibration passes.
- `lead_value_predictions(tenant_id, session_id, predicted, components JSONB, model_version, realised REAL NULL, realised_at)`; `ProxyCalibrationJob` fills `realised` and reports calibration by decile (predicted vs realised) — the predictor is re-fit weekly; the actual events of §16.3 continue to be sent unchanged.

### 16.17 Compliance by Construction + Staged Submission + Moderator Persona (vision §19.18.4) — extends 2-9, §16.7, 3-2, 3-22

- **Grammar mode** for `POST /content/generate`: optional flag `compose_from_approved = true` → generator may only emit sentences from `approved_phrases` (per context HOOK/CTA/DISCLAIMER/PROOF) plus slot fills (product facts, numbers); any sentence outside the set is returned in a separate `new_phrases[]` array flagged `needs_legal`. Legal review UI shows **only** `new_phrases`. Existing free-form mode remains default.
- **Staged submission**: `TwoContourService.launchTestBatch(..., moderationProbe = true)` (new optional parameter, default false) creates a $5–10/day ad set per new creative, polls `effective_status`/`ad_review_feedback` via the existing Meta sync (P2 lane), and writes `moderation_results(creative_id, verdict APPROVED/REJECTED, reason, probed_at)`; REJECTED → graveyard reason `blocked_by_meta` + recovery job (§16.7) before any real budget.
- **Moderator persona**: a persona of type `META_REVIEWER` in `personas` (3-22) with the rulebases as traits; `POST /persona/score` with `candidate_type = AD_TEXT` returns a pre-verdict; `persona_scores` joined to `moderation_results` gives its accuracy, reported weekly; it never blocks a launch (same rule as 3-22).

### 16.18 Post-Call Intelligence (vision §19.18.5) — extends Whisper (4-1), VoC (1-7), follow-up (§16.5)

```sql
CREATE TABLE call_recordings (id UUID PRIMARY KEY, tenant_id UUID NOT NULL, appointment_id UUID NOT NULL, source VARCHAR(20), -- GHL, ZOOM, UPLOAD
    media_url TEXT, transcript TEXT, transcribed_at TIMESTAMPTZ, consent_recorded BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ DEFAULT now());
CREATE TABLE call_objections (id UUID PRIMARY KEY, tenant_id UUID NOT NULL, call_id UUID REFERENCES call_recordings(id), objection_id UUID REFERENCES voc_objections(id), quote TEXT, stage_after VARCHAR(40), created_at TIMESTAMPTZ DEFAULT now());
```

- Ingest: `POST /api/v1/calls/recordings` (webhook from GHL call recording / Zoom cloud recording, or manual upload); **only with `consent_recorded = true`** (recording-consent laws differ by US state — ⚠️ client's counsel). Transcription via the existing Whisper client (4-1); PII redaction as in 1-7.
- Extraction: existing `POST /voc/extract-objections` with `source_type = CALL`; objections linked to the call's creative-source and `ticket_bucket`; `stage_after` = the stage the lead reached afterwards → objection → outcome association.
- Outputs: objection frequency by creative/segment on the dashboard; brief (4-4) section "asked on calls"; **follow-up bandit audience `HELD`** — variants = material sequences after the call, reward = `INVEST_COMMITTED` with intermediate events (`DATAROOM_OPEN`, second appointment) as early rewards (same arm structure as §16.5).
- Creative attribution extended to `FUNDS_RECEIVED` already exists in §16.9; this adds the *why* between held call and funds.

### 16.19 Investor Archetypes (vision §19.18.6) — extends `cluster_service` (5-1), `personalization_rules`, 5-5, 5-8

- `fit_clusters` gains optional features `ticket_bucket`, `self_accredited`, `MATERIAL_VIEW`/`DATAROOM_OPEN` dwell, return-visit count (all already in `sessions`/`events`); labels seeded with the §19.18.6 hypotheses via `rule_based_label`; stored in existing `behavior_clusters`.
- New mapping table `archetype_policies(tenant_id, cluster_label, funnel_path_id, first_touch_format, followup_audience_override, brief_segment)` — read by `/decide/landing` (path eligibility, §16.14), `/decide/followup` (§16.5) and `brief/generate-data-driven` (5-8). Control group 7.5 % unchanged.
- Custom Audiences per archetype through the existing 5-5 sync (`segment_creative_map` already supports label → creative).

### 16.20 Agency Measurability + Reg CF Waitlist Value (vision §19.18.7–19.18.8) — extends 3-2/3-4, 4-4, §16.3

- `creatives.source_id` (new, nullable) → `creative_sources(id, tenant_id, name, kind AGENCY/INHOUSE/CREATOR)`; the existing creator random effect (4-4) takes `source_id` as the grouping when `creator_id` is null; gate summary (3-4) and the weekly report add a per-source table: cost per `CALL_HELD`, verified share, show rate, with credible intervals. Agency ad sets already enter the unified test contour via §16.9.
- Reg CF waitlist: follow-up audience `REGCF_WAITLIST` (§16.5) for `self_accredited = false` leads, with intermediate rewards (`EMAIL_OPEN`, `REPLY`, `INTEREST_CONFIRMED`); `waitlist_value_estimates(tenant_id, as_of, n_leads, p_convert_regcf, avg_ticket_regcf, expected_value, ci_low, ci_high)` computed weekly from tenant-set priors until a Reg CF round provides outcomes; dashboard widget "waitlist value". Touch frequency capped by `max_touches`; content gated on counsel's answer (§19.16 p.3).

### 16.21 Jobs and routes added by §16.13–16.20

| Job | Frequency | Service | Description |
|-----|-----------|---------|-------------|
| No-show scoring | On `BOOKED` | FastAPI | `POST /propensity/noshow` → `noshow_scores`, `risk_tier` into follow-up context (§16.15) |
| Slot capacity update | Every 15 min | Spring | Recompute per-slot open/closed from `p_show` and policy (§16.15) |
| Lead-value predictor refit | Weekly | FastAPI | Refit on realised outcomes; calibration by decile (§16.16) |
| Moderation probe poll | Every 30 min (with Meta sync) | Spring | Read review status of probe ad sets → `moderation_results` (§16.17) |
| Call transcription + extraction | On recording webhook | Spring → FastAPI | Whisper → VoC objections → `call_objections` (§16.18) |
| Archetype refit | Weekly (with 5-1) | FastAPI | Clusters with new features; `archetype_policies` validation (§16.19) |
| Waitlist value estimate | Weekly | FastAPI | `waitlist_value_estimates` (§16.20) |

| Area | Routes |
|------|--------|
| Funnel paths | `GET/POST/PUT /api/v1/funnel-paths`, `POST /api/v1/funnel-paths/{id}/approve` |
| No-show / booking | FastAPI `POST /propensity/noshow`; `GET /api/v1/noshow/scores`, `GET/PUT /api/v1/booking/capacity-policies`, `GET /api/v1/booking/window` |
| Lead value | FastAPI `POST /predict/lead-value`; `GET /api/v1/lead-value/calibration` |
| Moderation | `GET /api/v1/moderation/results`; `launchTestBatch(moderationProbe=true)` |
| Calls | `POST /api/v1/calls/recordings`, `GET /api/v1/calls/objections` |
| Archetypes | `GET/PUT /api/v1/archetypes/policies` |
| Sources / waitlist | `GET/POST /api/v1/creative-sources`, `GET /api/v1/reports/sources`, `GET /api/v1/waitlist/value` |

