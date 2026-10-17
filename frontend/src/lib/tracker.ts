/**
 * Client-side event tracker — Phase 0-8 / §12.1b Server-Side First.
 *
 * Batches events and POSTs to /api/v1/events every 5 s and on page leave.
 * Consent-aware: behavioral events (SCROLL, HOVER, BLOCK_VIEW, CLICK) require
 * consent="full". Conversion events are sent for all consent levels.
 *
 * Usage:
 *   import { Tracker } from "@/lib/tracker";
 *   Tracker.init(tenantId, sessionId);
 *   Tracker.track("BLOCK_VIEW", { block_id: "hero" }, { blockEventId: "blk_001" });
 */

import { getConsentLevel, onConsentChange, type ConsentLevel } from "./consent";

const API_BASE =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

const BATCH_INTERVAL_MS = 5_000;
const MAX_BATCH_SIZE = 100;

// Require consent="full" for behavioral signals (vision §7.2 — all signals in §7.2 table)
const BEHAVIORAL = new Set([
  "SCROLL", "HOVER", "BLOCK_VIEW", "CLICK",
  "VIDEO_PROGRESS", "FIRST_CLICK", "QUIZ_ANSWER", "CALC_INTERACT",
  "RETURN_VISIT",  // vision §7.2 "повторные визиты" = behavioral
]);

// Always allowed (sent even when consent="none")
const ALWAYS_ALLOWED = new Set([
  "FORM_START",
  "FORM_SUBMIT",
  "CONVERSION",
  "PURCHASE",
  "LEAD",
]);

interface RawEvent {
  sessionId: string;
  eventType: string;
  tenantId: string;
  creativeId?: string;
  variantId?: string;
  blockEventId?: string;
  eventData?: Record<string, unknown>;
  revenue?: number;
  utmSource?: string;
  utmMedium?: string;
  utmCampaign?: string;
  consentLevel: ConsentLevel;
  eventTime: string;
  eventId: string;  // arch v1.1 §4.5: client-generated dedup key (renamed from clientEventId)
}

class EventTracker {
  private queue: RawEvent[] = [];
  private timer: ReturnType<typeof setInterval> | null = null;
  private tenantId = "";
  private sessionId = "";
  private consentLevel: ConsentLevel = "none";
  private scrollDepth = 0;
  private scrollAttached = false;
  private utms: Record<string, string> = {};

  /** Call once on landing page mount. */
  init(tenantId: string, sessionId: string): void {
    this.tenantId = tenantId;
    this.sessionId = sessionId;
    this.consentLevel = getConsentLevel();
    this.utms = extractUtms();

    // React immediately when consent changes
    onConsentChange((level) => {
      this.consentLevel = level;
      if (level === "full") this.attachScrollListener();
    });

    this.trackPageView();
    if (this.consentLevel === "full") this.attachScrollListener();
    this.startFlushTimer();
    this.attachLeaveListeners();
  }

  /**
   * Track an event. Consent gate is applied automatically.
   * @param blockEventId  e.g. "blk_001" — links to landing block for diagnostics
   */
  track(
    eventType: string,
    eventData?: Record<string, unknown>,
    opts?: { blockEventId?: string; revenue?: number; variantId?: string }
  ): void {
    if (!this.isAllowed(eventType)) return;

    const ev: RawEvent = {
      sessionId: this.sessionId,
      eventType,
      tenantId: this.tenantId,
      consentLevel: this.consentLevel,
      eventTime: new Date().toISOString(),
      eventId: crypto.randomUUID(),
      ...this.utms,
      ...(eventData ? { eventData } : {}),
      ...(opts?.blockEventId ? { blockEventId: opts.blockEventId } : {}),
      ...(opts?.revenue !== undefined ? { revenue: opts.revenue } : {}),
      ...(opts?.variantId ? { variantId: opts.variantId } : {}),
    };

    this.queue.push(ev);
    if (this.queue.length >= MAX_BATCH_SIZE) void this.flush();
  }

  async flush(): Promise<void> {
    if (this.queue.length === 0) return;
    const batch = this.queue.splice(0);

    try {
      await fetch(`${API_BASE}/events`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ events: batch }),
        keepalive: true, // delivers even during page unload
      });
    } catch {
      // Silent — analytics failure must never crash the landing page
    }
  }

  destroy(): void {
    if (this.timer) clearInterval(this.timer);
    void this.flush();
  }

  // ─── Private ────────────────────────────────────────────────────────────────

  private isAllowed(eventType: string): boolean {
    if (ALWAYS_ALLOWED.has(eventType)) return true;
    if (BEHAVIORAL.has(eventType) && this.consentLevel !== "full") return false;
    if (this.consentLevel === "none") return false;
    return true;
  }

  private trackPageView(): void {
    this.track("PAGE_VIEW", {
      href: typeof window !== "undefined" ? window.location.href : "",
      referrer: typeof document !== "undefined" ? document.referrer : "",
    });
  }

  private attachScrollListener(): void {
    if (this.scrollAttached || typeof window === "undefined") return;
    this.scrollAttached = true;

    const timestamps: number[] = [];
    const depths: number[] = [];

    window.addEventListener(
      "scroll",
      () => {
        if (typeof document === "undefined") return;
        const depth = Math.round(
          ((window.scrollY + window.innerHeight) / document.body.scrollHeight) * 100
        );
        // Fire on every 10% increment
        if (depth >= this.scrollDepth + 10) {
          this.scrollDepth = depth;
          timestamps.push(Date.now());
          depths.push(depth);
          // Compute scroll velocity pattern after ≥4 data points
          const pattern = timestamps.length >= 4
            ? computeScrollPattern(timestamps, depths)
            : undefined;
          this.track("SCROLL", {
            scroll_depth: depth,
            ...(pattern ? { scroll_velocity_pattern: pattern } : {}),
          });
        }
      },
      { passive: true }
    );
  }

  private attachLeaveListeners(): void {
    if (typeof window === "undefined") return;
    window.addEventListener("beforeunload", () => void this.flush());
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "hidden") void this.flush();
    });
  }

  private startFlushTimer(): void {
    this.timer = setInterval(() => void this.flush(), BATCH_INTERVAL_MS);
  }
}

function extractUtms(): Record<string, string> {
  if (typeof window === "undefined") return {};
  const p = new URLSearchParams(window.location.search);
  const result: Record<string, string> = {};
  if (p.get("utm_source")) result.utmSource = p.get("utm_source")!;
  if (p.get("utm_medium")) result.utmMedium = p.get("utm_medium")!;
  if (p.get("utm_campaign")) result.utmCampaign = p.get("utm_campaign")!;
  return result;
}

// Singleton — import and use directly
export const Tracker = new EventTracker();

// ─── Scroll velocity pattern detection (vision §7.2) ──────────────────────────

type ScrollVelocityPattern = "FAST_TOP_SLOW_BOTTOM" | "LINEAR" | "ERRATIC" | "FAST";

function computeScrollPattern(
  timestamps: number[],
  depths: number[]
): ScrollVelocityPattern {
  if (timestamps.length < 4) return "ERRATIC";
  const n = timestamps.length;

  // Velocities = depth_delta / time_delta
  const velocities: number[] = [];
  for (let i = 1; i < n; i++) {
    const dt = timestamps[i] - timestamps[i - 1];
    if (dt <= 0) continue;
    velocities.push((depths[i] - depths[i - 1]) / dt);
  }
  if (velocities.length < 2) return "ERRATIC";

  const mean = velocities.reduce((a, b) => a + b, 0) / velocities.length;
  const variance =
    velocities.reduce((s, v) => s + (v - mean) ** 2, 0) / velocities.length;
  const cv = mean > 0 ? Math.sqrt(variance) / mean : 1; // coefficient of variation

  // Split first half vs second half
  const half = Math.floor(velocities.length / 2);
  const firstHalf = velocities.slice(0, half).reduce((a, b) => a + b, 0) / half;
  const secondHalf = velocities.slice(half).reduce((a, b) => a + b, 0) / (velocities.length - half);

  if (cv < 0.15) return "LINEAR";
  if (mean > 0.5 && cv < 0.4) return "FAST";
  if (firstHalf > secondHalf * 1.5) return "FAST_TOP_SLOW_BOTTOM";
  return "ERRATIC";
}

// ─── New tracker signal helpers (vision §7.2) ─────────────────────────────────

/** VIDEO_PROGRESS: 25/50/75/100% checkpoints for on-page video. Requires consent=full. */
export function trackVideoProgress(
  videoId: string,
  progressPct: 25 | 50 | 75 | 100,
  durationSec?: number
): void {
  Tracker.track("VIDEO_PROGRESS", {
    video_id: videoId,
    progress_pct: progressPct,
    ...(durationSec !== undefined ? { duration_sec: durationSec } : {}),
  });
}

/** FIRST_CLICK: first interactive click on the page. Requires consent=full. */
export function trackFirstClick(
  targetSelector: string,
  xPct?: number,
  yPct?: number
): void {
  Tracker.track("FIRST_CLICK", {
    target_selector: targetSelector,
    ...(xPct !== undefined ? { x_pct: xPct } : {}),
    ...(yPct !== undefined ? { y_pct: yPct } : {}),
  });
}

/** QUIZ_ANSWER: visitor answered a quiz question. Requires consent=full. */
export function trackQuizAnswer(
  quizId: string,
  questionIdx: number,
  answerId: string
): void {
  Tracker.track("QUIZ_ANSWER", {
    quiz_id: quizId,
    question_idx: questionIdx,
    answer_id: answerId,
  });
}

/** CALC_INTERACT: visitor interacted with a calculator block. Requires consent=full. */
export function trackCalcInteract(
  calcId: string,
  fieldName: string,
  value?: number
): void {
  Tracker.track("CALC_INTERACT", {
    calc_id: calcId,
    field_name: fieldName,
    ...(value !== undefined ? { value } : {}),
  });
}

/** RETURN_VISIT: visitor is on their 2nd+ visit (sessions.visit_count > 1). */
export function trackReturnVisit(visitCount: number, daysSinceFirst?: number): void {
  if (visitCount < 2) return;
  Tracker.track("RETURN_VISIT", {
    visit_count: visitCount,
    ...(daysSinceFirst !== undefined ? { days_since_first: daysSinceFirst } : {}),
  });
}

// ─── Cross-Device identity helpers (vision §7.4) ──────────────────────────────

/**
 * SHA-256 hash of normalized email or phone — sent as external_id_hash on form submit.
 * Only called with consent=full. Never send raw PII.
 */
export async function hashExternalId(value: string): Promise<string> {
  const normalized = value.trim().toLowerCase();
  const buf = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(normalized)
  );
  return Array.from(new Uint8Array(buf))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Track FORM_SUBMIT with hashed external_id for Advanced Matching (vision §7.4).
 * external_id_hash is only included when consent=full — explicit gate, because
 * FORM_SUBMIT is ALWAYS_ALLOWED and would otherwise bypass the behavioral consent check.
 */
export async function trackFormSubmitWithIdentity(
  email?: string,
  phone?: string
): Promise<void> {
  const raw = (email ?? phone ?? "").trim();
  // Only hash PII when consent=full (vision §7.4: "Только после consent")
  if (!raw || getConsentLevel() !== "full") {
    Tracker.track("FORM_SUBMIT");
    return;
  }
  const hash = await hashExternalId(raw);
  Tracker.track("FORM_SUBMIT", { external_id_hash: hash });
}
