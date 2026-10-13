# Marketing AI Platform — Roadmap

> Version 1.0 — 2026-10-13
> First client: B2B hydrogel manufacturer (long sales cycle)
> Business model: SaaS
> Reference: vision.md v1.9.6 §23-24, architecture.md v1.0

---

## Phase 0 — Foundation (Scaffold + Infrastructure)

> Goal: working project skeleton, database, event pipeline, fraud/budget protection.
> All later phases depend on this.

### 0-1. Project Scaffold
- [ ] Spring Boot project (platform-api): build.gradle.kts, Application.java, config, security
- [ ] FastAPI project (ml-service): main.py, requirements.txt, Dockerfile
- [ ] Next.js project (frontend): app router, middleware.ts, package.json
- [ ] docker-compose.yml (PostgreSQL + pgvector, Redis, 3 services)
- [ ] Dockerfiles for each service
- [ ] `docker-compose up` — all services start and respond to health checks

### 0-2. Database Schema (Flyway Migrations)
- [ ] V1 — tenants, users
- [ ] V2 — creatives, creative_atoms
- [ ] V3 — landing_variants, variant_offers
- [ ] V4 — thompson_arms
- [ ] V5 — events (partitioned), sessions
- [ ] V6 — decision_log (propensity logging)
- [ ] V7 — meta_campaigns, meta_ad_sets, budget_guards
- [ ] V8 — meta_pixels (multi-pixel support §6.8)
- [ ] RLS policies on all tenant-scoped tables

### 0-3. Auth & Multi-Tenant
- [ ] JWT auth (register, login, refresh) — Spring Security
- [ ] TenantFilter: extract tenant from JWT → set `app.current_tenant` on JDBC connection
- [ ] RLS enforcement verified with integration test
- [ ] CORS config (whitelist tenant domains)

### 0-4. Event Ingestion Pipeline
- [ ] `POST /api/v1/events` — batch event ingestion endpoint (Spring Boot)
- [ ] Event validation (required fields, consent_level check)
- [ ] Async write to events table (non-blocking)
- [ ] Session creation/update on first event
- [ ] Event deduplication by event_id (for sGTM+CAPI overlap)
- [ ] Lead qualification events: call.completed, appointment.completed, appointment.no_show from GHL

### 0-5. Server-Side Tracking (sGTM / proxy)
- [ ] sGTM setup on GCP OR custom server-side proxy endpoint
- [ ] Meta Pixel as fallback/deduplication (event_id matching)
- [ ] CAPI event forwarding to Meta (requires Meta App — can mock initially)
- [ ] Event quality monitoring (EMQ proxy metric)

### 0-6. Fraud Protection (§12.6 — basic)
- [ ] Cloudflare Bot Management integration (or reCAPTCHA Enterprise v3 as alternative)
- [ ] Behavioral session filter: flag sessions with < 2s duration, zero scroll, bot-like patterns
- [ ] `is_suspicious` flag on events and sessions
- [ ] Suspicious sessions excluded from Thompson Sampling model

### 0-7. Financial Circuit Breaker L1 (§12.5a)
- [ ] Budget guards table + CRUD endpoints
- [ ] Per-creative stop-loss: when spend > threshold → alert + auto-pause recommendation
- [ ] Monthly budget ceiling per tenant account
- [ ] Telegram Bot alert on trigger
- [ ] Scheduled check job (every 5 min)

### 0-8. CMP Integration (§12.1a)
- [ ] Cookiebot script integration in frontend
- [ ] Consent level detection (full / analytics_only / none)
- [ ] Events tagged with consent_level
- [ ] No-consent sessions: track only aggregated, no personalization

### 0-9. Pixel Pollution Protection (§6.8)
- [ ] Multi-pixel support: meta_pixels table + CRUD endpoints
- [ ] Pixel assignment to campaigns (primary/test/product)
- [ ] CAPI Quality Gate: filter events by CRM lead quality before forwarding to Meta
- [ ] Admin UI: manage pixels per tenant, assign to campaigns

### 0-10. Google Calendar Integration
- [ ] Google Calendar API setup (OAuth2 credentials for client's workspace)
- [ ] Meeting attendance verification: scheduled vs completed vs no-show
- [ ] Fallback: if GHL tracks appointment status natively, Calendar API is optional

**Phase 0 exit criteria**: `docker-compose up` runs all services; auth works; events can be ingested and stored; fraud flagging active; budget guard alerts fire; lead qualification events from GHL flow through to CAPI.

---

## Phase 1 — Analytics Brain

> Goal: Creative DNA extraction, descriptive diagnostics, dashboard.
> Value: "which creatives work and why" — useful without any automation.

### 1-1. Creative Upload & Storage
- [ ] `POST /api/v1/creatives` — multipart upload → Cloudflare R2
- [ ] Thumbnail generation (FFmpeg single frame for video)
- [ ] Creative metadata stored in DB
- [ ] Creative list/detail endpoints

### 1-2. Creative DNA Extraction (§4.2)
- [ ] `POST /analyze/creative` (FastAPI) — async job
- [ ] Video: Whisper transcription → Claude Vision atom extraction
- [ ] Image: Claude Vision direct analysis
- [ ] Atoms stored in creative_atoms table
- [ ] Atom types: hook, structure, edit_pace, face, subtitles, tone, proof_type, music, duration, cta, production_style
- [ ] Confidence scores per atom
- [ ] Manual atom editing via admin UI

### 1-3. Descriptive Diagnostics Dashboard
- [ ] Tenant overview: total spend, conversions, revenue, ROAS
- [ ] Per-creative performance table (if Meta data available, else placeholder)
- [ ] Atom-level breakdown: "hook type X → average CTR Y"
- [ ] Diagnostic card from §3.2 (symptoms → diagnoses → actions)
- [ ] **Disclaimer**: Phase 1 = descriptive, NOT causal (§23 note)

### 1-4. Creator Brief Skeleton (§5.5)
- [ ] Template-based brief (not ML-driven yet)
- [ ] Marketer fills in target audience, product info
- [ ] System suggests atom combinations from descriptive stats
- [ ] PDF/markdown export of brief

### 1-5. Weekly Auto-Report
- [ ] Scheduled job: compile weekly metrics per tenant
- [ ] Email or Telegram delivery
- [ ] Includes: top creatives, atom insights, spend summary, anomalies

**Phase 1 exit criteria**: marketer uploads creatives → sees extracted atoms + performance breakdown in dashboard; can generate a template brief.

---

## Phase 2 — Landing Page Engine + Thompson Sampling on Site

> Goal: first closed loop — generate landings, test with Thompson Sampling, learn.
> This is the core MVP value proposition.

### 2-1. Landing Block Components (Next.js)
- [ ] HeroBlock, ProblemBlock, ProofBlock, PricingBlock, CTABlock, FAQBlock
- [ ] Each block renders `data-block-id={eventId}` for analytics
- [ ] Responsive: desktop + mobile
- [ ] Configurable via JSONB (text slots, images, colors)
- [ ] Block preview in admin panel

### 2-2. Landing Variant CRUD (Admin Panel)
- [ ] Create variant: select blocks, fill content, set block_order
- [ ] LLM content generation assist (Claude → fill text slots from product info)
- [ ] Message Match: generate landing headline paired with creative hook (§5.3)
- [ ] Variant preview (full page render)
- [ ] Approval workflow: DRAFT → REVIEW → ACTIVE
- [ ] Offer sub-atoms (§4.3a): 6 dimensions configurable per variant

### 2-3. Thompson Sampling Engine
- [ ] `POST /decide` (FastAPI): sample from Beta(α,β) per variant, return winner
- [ ] Redis-cached posteriors for <15ms response
- [ ] Propensity logging: every decision → decision_log with full candidate set
- [ ] Daily α/β decay (×0.99) — scheduled job
- [ ] Posterior recalculation from events (every 15 min)
- [ ] Power calculator: "is there enough data for a decision?"

### 2-4. Edge Middleware (Next.js)
- [ ] middleware.ts: intercept landing requests
- [ ] Extract creative_id, UTM params from URL
- [ ] Call FastAPI /decide → get variant_id
- [ ] SSR render chosen variant
- [ ] Set session cookie (first-party, with consent)

### 2-5. Client-Side Event Tracker
- [ ] tracker.ts: scroll depth + velocity, hover, block_view, clicks
- [ ] Form tracking: form_start, form_field_focus, form_submit
- [ ] Batch send to Spring Boot /api/v1/events (every 5s + on page leave)
- [ ] Consent-aware: full tracking only with consent

### 2-6. Conversion Flow
- [ ] Form submission → LEAD event in DB
- [ ] Purchase/payment webhook → PURCHASE event with revenue
- [ ] Thompson arm update: α++ on conversion, β++ on non-conversion
- [ ] Revenue tracking for future hurdle model (store but don't use in MVP TS)

### 2-7. A/B Dashboard (Bayesian)
- [ ] Per-variant: conversions, conversion rate, posterior distribution chart
- [ ] Probability of being best (from Thompson posteriors)
- [ ] "Winner" recommendation with confidence level
- [ ] Anchor creative comparison: market effect vs creative effect (§11.2)

### 2-8. RLHA — Marketer Feedback Loop (§13.3)
- [ ] Track marketer edits to LLM-generated content (delta)
- [ ] Store corrections: original → edited for each text slot
- [ ] Few-shot prompt bank: client-specific writing style
- [ ] LLM uses correction history in subsequent generations

**Phase 2 exit criteria**: marketer creates landing variants → Thompson Sampling routes visitors → events tracked → posteriors update → dashboard shows which variant wins with Bayesian confidence.

---

## Phase 3 — Decision Graph + Meta Integration

> Goal: full Thompson Sampling with Bayesian GLM; two-contour Meta structure; staged gates.

### 3-1. Bayesian GLM (§6.3a, §15.3)
- [ ] PyMC hurdle model: Binomial(conversion) × Lognormal(revenue)
- [ ] Atom-level effects with partial pooling (§15.4)
- [ ] Segment random effects (audience × atom interactions)
- [ ] Beta-Binomial for overdispersion
- [ ] match_score as calibrated feature
- [ ] Off-policy evaluation on propensity logs

### 3-2. Two-Contour Meta Structure (§6.5)
- [ ] Test contour: isolated ad sets, equal budgets, new creatives in batches
- [ ] Scale contour: consolidated campaigns (Advantage+ / CBO)
- [ ] Winner transfer: test → scale with confirmation run
- [ ] Winner's curse mitigation: shrinkage + confirming prognosis
- [ ] Learning Phase monitoring (log status before/after each launch)

### 3-3. Meta API Integration
- [ ] Meta Marketing API: create/pause/update campaigns and ad sets
- [ ] Rate-limiting priority queue (§10.3): P0 circuit breaker, P1 normal, P2 background
- [ ] CAPI: send conversion events with value (margin, predicted LTV)
- [ ] Meta sync job: pull metrics, learning phase, spend every 30 min

### 3-4. Staged Gates — Sequential Elimination (§12.3)
- [ ] Gate 1: minimum impressions × CPM threshold → kill dead creatives early
- [ ] Gate 2: click + landing engagement threshold → eliminate weak performers
- [ ] Gate 3: conversion + revenue threshold → final survivors to scale
- [ ] Bayesian stopping rules (not fixed sample size)

### 3-5. Full Circuit Breaker (§12.5a L2-L3)
- [ ] L2: cascading pause (campaign level)
- [ ] L3: account-level emergency stop
- [ ] Gradual ramp-up recovery protocol
- [ ] Meta Automated Rules as last-resort fallback

### 3-6. Exploration Budget (§6.4)
- [ ] 10-20% traffic to new/untested combinations
- [ ] Optimistic prior for new variants (wide Beta)
- [ ] Expected Value of Information ranking

### 3-7. Delayed Churn Penalty (§6.7)
- [ ] CRM webhook receiver: churn, refund, deal_lost events
- [ ] Cohort → atom attribution
- [ ] Retrospective posterior correction (max 30% penalty per recalc)
- [ ] Min cohort size = 20 before recalc

### 3-11. Pixel Pollution Auto-Detection (§6.8 Level 3)
- [ ] Lead quality drift detector: compare incoming lead profile vs confirmed client profile
- [ ] Auto-alert on anomalous CTR + low CRM conversion rate
- [ ] Auto-pause recommendation for campaigns polluting pixel

### 3-8. What-If Simulator (§21.4)
- [ ] Scenario analysis from Thompson posteriors
- [ ] "What if we increase budget by X?" → predicted CPA, ROAS

### 3-9. System vs Baseline Benchmark (§21.5)
- [ ] 3-5% micro-holdout (default ON, toggleable)
- [ ] Holdout sees random variant (no Thompson)
- [ ] Measures incremental lift of the system

### 3-10. Reactive Competitive Intelligence (§9.1)
- [ ] Ad Library polling: detect competitor creatives running > 14 days
- [ ] Vision LLM extracts atoms from competitor creatives
- [ ] Generate counter-hypotheses
- [ ] Alert marketer with ready-to-test batch (human approval required)

**Phase 3 exit criteria**: full Bayesian model running; Meta campaigns managed via API; staged gates auto-eliminate losers; system measurably outperforms random allocation.

---

## Phase 4 — Video Assembly

### 4-1. Video Template Engine
- [ ] Upload source footage → Whisper + Vision LLM scene tagging
- [ ] LLM director: montage plan (Hook → Problem → Solution → Proof → CTA)
- [ ] Scene selection from tagged footage
- [ ] FFmpeg / Remotion render: 9:16, 4:5, 1:1

### 4-2. Variation Generation
- [ ] Hook × Body × CTA combinations (3 × 1 × 3 = 9 creatives)
- [ ] Subtitle/overlay variations (low-effort, high-variation)
- [ ] Marketer approval before launch

### 4-3. Media Asset Pipeline
- [ ] S3/R2 storage with lifecycle policy
- [ ] Hot → Cold (90 days), losers deleted (30 days after Gate)

### 4-4. Data-Driven Creator Brief (§5.5)
- [ ] Brief from model posteriors + failure graveyard + Ad Library references
- [ ] Anti-patterns section ("don't do this, here's why")
- [ ] Benchmarks from own model data
- [ ] Free Concept slot

---

## Phase 5 — Funnel Personalization

### 5-1. Behavioral Clusters (§7.3)
- [ ] Session clustering: 5-8 archetypes (price_seeker, proof_seeker, ready_to_buy, etc.)
- [ ] LLM labels clusters, generates hypotheses

### 5-2. Propensity Model
- [ ] Gradient boosting on session features → P(conversion)
- [ ] Main objection prediction (price, trust, urgency, understanding)

### 5-3. Uplift Modeling
- [ ] Who benefits from intervention vs who converts anyway
- [ ] Contextual bandit for block/offer selection

### 5-4. Real-Time Landing Adaptation
- [ ] Block reordering within session (approved blocks only)
- [ ] "Price seeker" → pricing up, "skeptic" → proof up
- [ ] 5-10% control group with standard experience

### 5-5. Meta Feedback Loop
- [ ] Behavioral segments → Custom Audiences via CAPI
- [ ] Retargeting: "saw price, left" → show payment flexibility video

---

## Phase 6 — Predictive Layer & Autopilot

- [ ] Creative burnout prediction (Frequency ↑ → Hook Rate ↓)
- [ ] Audience saturation detection (d(Reach)/d(Spend))
- [ ] Predictive budget curve (marginal efficiency)
- [ ] Evolutionary algorithm: atom recombination for new candidates
- [ ] Negative Learning graveyard with post-mortems
- [ ] Autopilot mode with safety limits

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

- [ ] Google Ads API integration
- [ ] TikTok Ads API integration
- [ ] Unified decision graph with channel branches
- [ ] Bayesian MMM for cross-channel budget allocation
- [ ] Cross-channel attribution (MMM + MTA hybrid)

---

## Bug List

_(empty — project not started)_

---

## Current Status

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 0 | **NOT STARTED** | Next: scaffold (0-1) |
| Phase 1 | Not started | Depends on 0-1 through 0-4 |
| Phase 2 | Not started | Core MVP |
| Phase 3 | Not started | Full Meta integration |
| Phase 4 | Not started | Video assembly |
| Phase 5 | Not started | Personalization |
| Phase 6 | Not started | Predictive |
| Phase 0.5 | Not started | Needs 5+ clients |
| Phase 7 | Not started | Multi-channel |

**Immediate priority**: Phase 0-1 (scaffold) → Phase 0-2 (DB schema) → Phase 0-3 (auth)
