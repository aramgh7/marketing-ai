/**
 * Roadmap Phase 10 — tenant domains and the self-hosted landing runner.
 *
 * Dependency-free on purpose: imported by the edge middleware (edge runtime) and by the
 * SSR landing page. Decides whether a request host is one of the platform's own host names
 * (admin + default landings) or a tenant's custom landing domain.
 *
 *   PLATFORM_HOSTS  comma-separated list, e.g. "app.example.com,landings.example.com,localhost:3000"
 *                   an entry without a port matches any port on that host.
 *   LANDING_RUNNER  "1" → this Next.js instance serves landings only (runs on the client's server).
 *   RUNNER_KEY      per-tenant key issued in Settings → Landing domains; sent as X-Runner-Key.
 *   TRUST_PROXY     "1" → honour X-Forwarded-Host (only behind our own reverse proxy / ingress).
 */

export const PLATFORM_HOSTS: string[] = (process.env.PLATFORM_HOSTS ?? "localhost:3000")
  .split(",")
  .map((h) => h.trim().toLowerCase())
  .filter(Boolean);

export const RUNNER = process.env.LANDING_RUNNER === "1";
export const RUNNER_KEY = process.env.RUNNER_KEY ?? "";

/**
 * Audit F-18: `X-Forwarded-Host` is honoured only behind a trusted reverse proxy (TRUST_PROXY=1);
 * otherwise any client could pick the tenant by sending the header.
 */
export const TRUST_PROXY = process.env.TRUST_PROXY === "1";

/** Lower-cased host from `x-forwarded-host` (first value, trusted proxy only) or `host`; "" when neither is present. */
export function requestHost(headers: { get(name: string): string | null }): string {
  const fwd = TRUST_PROXY ? headers.get("x-forwarded-host") : null;
  const raw = (fwd ? fwd.split(",")[0] : headers.get("host")) ?? "";
  return raw.trim().toLowerCase();
}

/** Host without the port part (IPv6 literals keep their brackets). */
export function stripPort(host: string): string {
  if (host.startsWith("[")) {
    const end = host.indexOf("]");
    return end > 0 ? host.slice(0, end + 1) : host;
  }
  const i = host.indexOf(":");
  return i >= 0 ? host.slice(0, i) : host;
}

/** True when the host is one of PLATFORM_HOSTS (an entry without a port matches any port). */
export function isPlatformHost(host: string): boolean {
  if (!host) return true; // no Host header (tests, curl without -H) → treat as platform
  const h = host.toLowerCase();
  const bare = stripPort(h);
  return PLATFORM_HOSTS.some((p) => (p.includes(":") ? p === h : p === bare));
}

/** Host name to scope a public slug lookup to, or null on the platform's own hosts. */
export function tenantHostFor(host: string): string | null {
  if (!host || isPlatformHost(host)) return null;
  return stripPort(host);
}
