# Marketing AI Platform — Architecture Document

> Version 1.0 — 2026-10-13
> Scope: MVP (Phase 0 + Phase 1 descriptive + Phase 2 + Thompson on site)
> Reference: vision.md v1.9.6

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
| **Platform API** | Java 21 + Spring Boot 3.3 | Enterprise patterns from Sales-ai; auth, multi-tenant, CRUD, Meta API integration, webhooks, event ingestion |
| **ML Service** | Python 3.12 + FastAPI | PyMC, Thompson Sampling inference, Creative DNA (LLM calls), Bayesian diagnostics — ML ecosystem lives in Python |
| **Frontend** | Next.js 15 (App Router, SSR) | Admin dashboard, landing page renderer with SSR, Edge Middleware for TS routing |
| **Database** | PostgreSQL 16 + pgvector | Events, variants, atoms, propensity logs; pgvector for taxonomy alignment (Phase 1+) |
| **Cache** | Redis 7 | Cached posteriors for <10ms Thompson Sampling decisions |
| **Object Storage** | Cloudflare R2 (S3-compat) | Video assets, rendered creatives, screenshots (Phase 4); no egress fees |
| **Server-Side Tracking** | sGTM on GCP / custom proxy | Phase 0 requirement: bypass ATT/ad-blockers, primary CAPI source |
| **Bot Protection** | Cloudflare Bot Management | Phase 0 fraud filter before session creation |
| **CMP** | Cookiebot | Consent management; determines tracking level per visitor |
| **Alerts** | Telegram Bot API | Circuit Breaker, fraud anomalies, Site Health alerts |
| **Calendar** | Google Calendar API | Verify meeting attendance (show/no-show) when GHL doesn't track it natively (§13, §16.1) |
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
-- Spring Boot sets `SET app.current_tenant = ?` on each request

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
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(50) DEFAULT 'MARKETER', -- ADMIN, MARKETER, VIEWER
    created_at      TIMESTAMPTZ DEFAULT now()
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
    block_order     TEXT[] NOT NULL,                 -- ["hero","proof","pricing","cta"]
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
    -- Reward logic (B2B): form_submit=wait, call_answered=α+=0.3, meeting_attended=α+=1.0,
    -- deal_won=revenue update, no_response_7d=β+=1.0. See vision §16.1
    alpha           REAL NOT NULL DEFAULT 1.0,      -- successes + 1 (Beta prior)
    beta            REAL NOT NULL DEFAULT 1.0,      -- failures + 1 (Beta prior)
    revenue_mu      REAL DEFAULT 0.0,               -- log-revenue mean
    revenue_sigma   REAL DEFAULT 1.0,               -- log-revenue std
    revenue_n       INT DEFAULT 0,                  -- number of purchases
    total_samples   INT DEFAULT 0,
    last_decayed_at TIMESTAMPTZ DEFAULT now(),       -- for daily α/β decay (§6.2)
    updated_at      TIMESTAMPTZ DEFAULT now(),
    UNIQUE(tenant_id, variant_id, context_key)
);
CREATE INDEX idx_arms_tenant ON thompson_arms(tenant_id, context_key);
```

### 4.5 Events (Raw Stream)

```sql
-- High-volume table: partitioned by month, tenant_id in partition key
CREATE TABLE events (
    id              UUID DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    session_id      VARCHAR(100) NOT NULL,
    event_type      VARCHAR(50) NOT NULL,   -- IMPRESSION, CLICK, PAGE_VIEW, SCROLL, HOVER,
                                            -- BLOCK_VIEW, FORM_START, FORM_FIELD, FORM_SUBMIT,
                                            -- CONVERSION, PURCHASE, LEAD
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
    -- Format: [{"variant_id": "...", "sample_value": 0.42, "alpha": 12, "beta": 88}, ...]
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
    revenue         REAL
);
CREATE INDEX idx_sessions_tenant ON sessions(tenant_id, started_at);
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
| GET | `/api/v1/tenants/me` | Current tenant info |
| PUT | `/api/v1/tenants/me` | Update tenant settings |
| PUT | `/api/v1/tenants/me/meta-credentials` | Set Meta API tokens (encrypted) |

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
| POST | `/api/v1/events` | Ingest batch of events from landing tracker |
| POST | `/api/v1/events/capi` | Receive Meta CAPI webhook events |

#### Dashboard
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/analytics/overview` | Tenant overview (conversions, revenue, ROAS) |
| GET | `/api/v1/analytics/variants` | Per-variant performance |
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
| POST | `/api/v1/webhooks/ghl` | Receive GHL events: `contact.created`, `opportunity.*`, `appointment.*`, `call.completed` |

### 5.2 FastAPI — ML Service (port 8000)

#### Thompson Sampling
| Method | Path | Description |
|--------|------|-------------|
| POST | `/decide` | Choose variant for visitor. Input: `{tenant_id, creative_id, context}`. Output: `{variant_id, block_order, decision_id}`. Latency target: <15ms |
| POST | `/decide/batch` | Batch decisions (for pre-warming cache) |

#### Creative DNA
| Method | Path | Description |
|--------|------|-------------|
| POST | `/analyze/creative` | Extract atoms from creative. Input: `{creative_id, file_url, transcript}`. Async: returns job_id |
| GET | `/analyze/status/{job_id}` | Check extraction status |

#### Model
| Method | Path | Description |
|--------|------|-------------|
| POST | `/model/update` | Trigger posterior recalculation from recent events |
| GET | `/model/posteriors/{tenant_id}` | Current posteriors for all arms |
| POST | `/model/decay` | Run daily α/β decay (called by scheduler) |

#### Diagnostics
| Method | Path | Description |
|--------|------|-------------|
| GET | `/diagnostics/{tenant_id}` | Diagnostic card (§3.2): symptoms, diagnoses, actions |
| GET | `/diagnostics/{tenant_id}/power` | Power calculator: min traffic for decision |
| GET | `/diagnostics/{tenant_id}/anchors` | Anchor creative comparison (market vs creative effect) |

#### Creator Brief
| Method | Path | Description |
|--------|------|-------------|
| POST | `/brief/generate` | Generate data-driven brief from posteriors (Phase 1: template only) |

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
   │  ├── Logs decision to decision_log (async, non-blocking)
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
7. Spring Boot writes events to PostgreSQL
   │  ├── Marks suspicious sessions (fraud filter)
   │  └── For CONVERSION/PURCHASE: sends to Meta CAPI
   │
8. GoHighLevel webhook → Spring Boot /api/v1/webhooks/ghl
   │  ├── contact.created / opportunity.* / appointment.* / call.completed
   │  ├── Links GHL contact_id to session_id (via email/phone match)
   │  ├── call.completed → Thompson α += 0.3, send proxy_low to CAPI
   │  ├── appointment.completed → Thompson α += 1.0, send proxy_high to CAPI (key quality signal)
   │  ├── appointment.no_show / no response 7d → Thompson β += 1.0, no CAPI event
   │  └── opportunity.won/lost → final value update / churn penalty
   │
9. Periodic: FastAPI /model/update recalculates posteriors
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

**Internal network**: all services communicate over docker network (dev) or VPC (prod). No public exposure for FastAPI — only Next.js Edge and Spring Boot call it.

**Auth for internal calls**: shared API key in environment variable. No JWT overhead on internal path.

---

## 8. Multi-Tenant Architecture

Approach: **Shared database, shared schema, Row-Level Security (RLS)**.

```sql
-- Enable RLS on all tenant-scoped tables
ALTER TABLE creatives ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON creatives
    USING (tenant_id = current_setting('app.current_tenant')::uuid);

-- Spring Boot sets this on every connection from HikariCP
-- via a ConnectionCustomizer or @Transactional aspect
```

**Spring Boot side**: `TenantFilter` (servlet filter) extracts tenant from JWT, sets `app.current_tenant` on the JDBC connection. Every query is automatically scoped.

**FastAPI side**: tenant_id is always an explicit parameter in API calls (no ambient state). Each query includes `WHERE tenant_id = :tenant_id`.

---

## 9. Scheduled Jobs

| Job | Frequency | Service | Description |
|-----|-----------|---------|-------------|
| Posterior decay | Daily 03:00 UTC | FastAPI | α/β × 0.99 on all arms (§6.2) |
| Posterior recalc | Every 15 min | FastAPI | Recalculate from recent events → update Redis |
| Meta sync | Every 30 min | Spring Boot | Pull campaign/ad set status, learning phase, spend |
| Budget guard check | Every 5 min | Spring Boot | Compare spend vs thresholds → trigger pause if exceeded |
| Fraud session review | Hourly | Spring Boot | Score suspicious sessions, exclude from model |
| GHL sync | Every 60 min | Spring Boot | Pull pipeline stages for open opportunities, update lead quality scores |
| Event partition create | Monthly | Flyway/cron | Create next month's event partition |

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
      GOOGLE_CALENDAR_CREDENTIALS: ${GOOGLE_CALENDAR_CREDENTIALS}
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

---

## 12. Key Design Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Hybrid Spring + FastAPI (not monolith) | ML ecosystem = Python; enterprise API = Java. Clean separation. Internal HTTP adds ~1-2ms, acceptable |
| 2 | Shared PostgreSQL (not separate DBs) | MVP simplicity. Both services need events and arms. RLS handles isolation |
| 3 | Redis for posteriors (not in-memory) | Survives service restart; shared between FastAPI instances; <1ms reads |
| 4 | Events in PostgreSQL partitioned (not Kafka) | MVP doesn't need streaming. Monthly partitions handle volume. Kafka = Phase 4+ if needed |
| 5 | Monorepo (not separate repos) | Shared schema migrations, coordinated deploys, simpler CI |
| 6 | Next.js Edge → FastAPI direct | Landing page latency is critical (§5.4.5). No Spring hop for TS decisions |
| 7 | JSONB for blocks (not separate tables) | Blocks are always loaded as a unit with variant. JSONB = single read, flexible schema |
| 8 | Propensity logging from day 1 | Vision §23 Phase 0 mandatory. Without it, off-policy eval and causal analysis impossible later |
| 9 | Flyway migrations (not Liquibase) | Simpler, SQL-native, good enough for this schema |
| 10 | Multi-tenant from day 1 | Vision §12.7. Retrofitting tenant isolation is painful. RLS cost is minimal |
| 11 | Multi-pixel from day 1 | Vision §6.8. Client experienced pixel pollution from bad campaign. Separate test/primary pixels prevent contamination of main pixel's algorithm |

---

## 13. GoHighLevel CRM Integration

> First client uses **GoHighLevel (GHL)** as CRM. This is the primary source of post-conversion data (lead quality, deal outcome, churn) needed for Proxy Value Bidding (vision §16.1) and Delayed Churn Penalty (vision §6.7).

### 13.1 GHL Webhook Events (Spring Boot receives)

| GHL Event | Meaning | Action in system |
|-----------|---------|-----------------|
| `contact.created` | New lead created in GHL (form submission or manual) | Link `ghl_contact_id` → `session_id` via email/phone match; update event as `LEAD` |
| `opportunity.status_changed` | Lead moved between pipeline stages | Log stage transition + timestamp; update lead quality score |
| `opportunity.won` | Deal closed successfully | Confirm conversion value; reinforce thompson arm (α++) |
| `opportunity.lost` | Deal lost | Apply soft churn penalty to thompson arm (Phase 3+) |
| `appointment.scheduled` | Meeting booked in Google Calendar via GHL | Log appointment; start quality timer (7-day window) |
| `appointment.completed` | Lead showed up to meeting | **Key quality signal**: mark lead as qualified; send proxy_high value to Meta CAPI; Thompson α += 1.0 |
| `appointment.no_show` | Lead didn't show up | Mark as no-show; do NOT send to CAPI |
| `call.completed` | Outbound/inbound call answered | Partial quality signal; send proxy_low to CAPI; Thompson α += 0.3 |

### 13.2 Session ↔ GHL Contact Linking

When GHL fires `contact.created`, Spring Boot attempts to match by:
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

Concrete quality events → proxy value → sent to Meta CAPI as conversion value:

```
GHL event "call.completed"         → proxy value = avg_deal_value × 0.15
GHL event "appointment.completed"  → proxy value = avg_deal_value × 0.40  (key quality signal)
GHL event "opportunity.won"        → proxy value = actual deal value
No response after 7 days           → no CAPI event (pixel not polluted)
```

Sent to Meta CAPI as `value` parameter → Meta optimizes toward high-value leads, not just volume.

### 13.5 GHL API Access (for sync job)

GHL provides REST API for reading contacts/opportunities. Spring Boot scheduled job (every 60 min) pulls open opportunities and updates `pipeline_stage` and `deal_value` in sessions table. Requires GHL API key stored encrypted in `tenants.settings`.

---

## 14. What Is NOT in MVP

These are explicitly deferred per vision.md §24:

- Video rendering (FFmpeg/Remotion) → Phase 4
- Meta Marketing API campaign management → Phase 3
- Real-time personalization (block reordering) → Phase 5
- Uplift modeling → Phase 5
- Bayesian GLM full model (PyMC) → Phase 3 (MVP uses simple Beta-Binomial)
- Universal Cross-Niche Model → Phase 0.5b
- Geo-Lift testing → Phase 5+
- Multi-channel (Google, TikTok) → Phase 7
- Delayed Churn Penalty → Phase 3
- Portfolio optimization → Phase 6+

MVP Thompson Sampling uses **simple Beta(α,β)** for conversion rate, not the full hurdle model. The hurdle model (Binomial × Lognormal) is Phase 3 when revenue data is sufficient.
