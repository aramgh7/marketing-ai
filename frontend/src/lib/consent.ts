/**
 * CMP (Consent Management Platform) integration — §12.1a.
 *
 * Three consent levels used throughout the platform:
 *  "full"           — all tracking + behavioral + personalization
 *  "analytics_only" — conversion events only; no SCROLL/HOVER/BLOCK_VIEW
 *  "none"           — nothing tracked; no personalization
 *
 * Integration priority:
 *  1. Cookiebot (when NEXT_PUBLIC_COOKIEBOT_DOMAIN_ID is set and script loads)
 *  2. Custom banner (stores decision in localStorage key "mai_consent")
 *  3. Default: "none" until user decides
 *
 * Vision §12.1a: refusal ≠ bounce.
 * Sessions with consent="none" are excluded from Thompson Sampling training
 * but remain in the impression denominator — never treated as disinterested.
 */

export type ConsentLevel = "full" | "analytics_only" | "none";

const STORAGE_KEY = "mai_consent";

// Cookiebot injects this global when its script loads
declare global {
  interface Window {
    Cookiebot?: {
      consent: {
        necessary: boolean;
        preferences: boolean;
        statistics: boolean;
        marketing: boolean;
      };
      hasResponse: boolean;
    };
  }
}

/** Read current consent level. Safe to call server-side (returns "none"). */
export function getConsentLevel(): ConsentLevel {
  if (typeof window === "undefined") return "none";

  // 1. Cookiebot integration
  const cb = window.Cookiebot;
  if (cb?.hasResponse) {
    if (cb.consent.statistics && cb.consent.marketing) return "full";
    if (cb.consent.statistics) return "analytics_only";
    return "none";
  }

  // 2. Custom banner fallback
  const stored = localStorage.getItem(STORAGE_KEY) as ConsentLevel | null;
  if (stored === "full" || stored === "analytics_only" || stored === "none") {
    return stored;
  }

  // 3. Unset — conservative default
  return "none";
}

// arch v1.1 §5.2 + vision v1.9.9 §12.1a: consent must be readable by Edge middleware.
// localStorage is client-only; set a non-httpOnly cookie so middleware can check consent
// before passing session_id to /decide (before consent → Thompson with no persistent ID).
const CONSENT_COOKIE = "_consent";
const CONSENT_COOKIE_MAX_AGE = 60 * 60 * 24 * 365; // 1 year

/** Persist consent choice from our custom banner. */
export function setConsentLevel(level: ConsentLevel): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(STORAGE_KEY, level);
  document.cookie = `${CONSENT_COOKIE}=${level}; max-age=${CONSENT_COOKIE_MAX_AGE}; path=/; samesite=lax`;
  window.dispatchEvent(new CustomEvent("mai:consent", { detail: level }));
}

/** True if the user has already made a consent decision (banner answered). */
export function hasConsentDecision(): boolean {
  if (typeof window === "undefined") return false;
  if (window.Cookiebot?.hasResponse) return true;
  return localStorage.getItem(STORAGE_KEY) !== null;
}

/**
 * Subscribe to consent changes.
 * Covers both Cookiebot events and our custom banner events.
 * Returns an unsubscribe function.
 */
export function onConsentChange(handler: (level: ConsentLevel) => void): () => void {
  if (typeof window === "undefined") return () => {};

  const fromCookiebot = () => handler(getConsentLevel());
  const fromCustom = (e: Event) =>
    handler((e as CustomEvent<ConsentLevel>).detail);

  window.addEventListener("CookiebotOnAccept", fromCookiebot);
  window.addEventListener("CookiebotOnDecline", fromCookiebot);
  window.addEventListener("mai:consent", fromCustom);

  return () => {
    window.removeEventListener("CookiebotOnAccept", fromCookiebot);
    window.removeEventListener("CookiebotOnDecline", fromCookiebot);
    window.removeEventListener("mai:consent", fromCustom);
  };
}
