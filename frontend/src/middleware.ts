import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { RUNNER, RUNNER_KEY, isPlatformHost, requestHost, stripPort } from "@/lib/hosts";

// Edge middleware runs server-side — use internal Docker network URL when available
const API_BASE =
  process.env.API_INTERNAL_URL ??
  process.env.NEXT_PUBLIC_API_URL ??
  "http://localhost:8080/api/v1";

/**
 * Audit ML-12: the ML service has a single caller — the platform API. The direct edge → ML branch
 * (`ML_SERVICE_URL` + `INTERNAL_API_KEY`) is kept only behind DECIDE_VIA_ML=1 (default off) until
 * `POST /public/decide` accepts a platform-side server key; by default every decision goes through
 * the platform API (`API_INTERNAL_URL` on the platform host, the tenant's runner key on a runner).
 */
const DECIDE_VIA_ML = process.env.DECIDE_VIA_ML === "1" && !RUNNER;
const ML_SERVICE_URL = process.env.ML_SERVICE_URL ?? "http://localhost:8000";
/** Server-side key the platform host presents to /public/decide (never shipped to the browser). */
const PLATFORM_DECIDE_KEY = process.env.INTERNAL_API_KEY ?? "";

/** Audit F-8: no fetch in the edge may hang the landing. */
const DOMAIN_TIMEOUT_MS = 1_500;
const DECIDE_TIMEOUT_MS = 1_500;
const VARIANT_TIMEOUT_MS = 4_000;

/** Audit F-14: cookies are `secure` in production (TLS terminates in front of the app). */
const SECURE_COOKIES = process.env.NODE_ENV === "production" && process.env.INSECURE_COOKIES !== "1";

/** Roadmap Phase 11: our creative ids are UUIDs; anything else in `mai_c` is a Meta ad id ({{ad.id}}). */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Creative identity from the landing URL (`.ai/creative-publishing.md` §3):
 *   mai_c = {{ad.id}} (Meta) or our creative UUID (manual / other channels), legacy `creative_id`
 *   mai_p = {{placement}}, mai_s = {{site_source_name}} — context only, never a new arm.
 */
function creativeParams(params: URLSearchParams): { raw: string; creativeId: string | null; context: Record<string, string> } {
  const raw = (params.get("mai_c") ?? params.get("creative_id") ?? "").trim();
  const context: Record<string, string> = {};
  let creativeId: string | null = null;
  if (raw) {
    if (UUID_RE.test(raw)) creativeId = raw.toLowerCase();
    else context.meta_ad_id = raw;
  }
  const placement = params.get("mai_p")?.trim();
  const source = params.get("mai_s")?.trim();
  if (placement) context.placement = placement;
  if (source) context.site_source = source;
  return { raw, creativeId, context };
}

/** Paths that may be served on a tenant domain / in runner mode (admin never is). */
const LANDING_ONLY_ALLOWED = [/^\/l\//, /^\/_next\//, /^\/favicon\.ico$/, /^\/robots\.txt$/];

// ── Tenant domain resolution cache (module-level, 60 s TTL) ───────────────────
interface DomainInfo { tenantId: string; host: string; kind: string; hosting: string; defaultSlug: string | null }
const DOMAIN_TTL_MS = 60_000;
const domainCache = new Map<string, { at: number; info: DomainInfo | null }>();

/**
 * Audit F-7: only definite answers (200 → info, 404 → null) are cached. A network error, a timeout
 * or a 5xx keeps the last known value (even if expired) so an API outage does not turn every tenant
 * landing into a 404 — the ISR'd page keeps serving (`landing-hosting.md`, mode C).
 */
async function resolveDomain(host: string): Promise<DomainInfo | null> {
  const hit = domainCache.get(host);
  if (hit && Date.now() - hit.at < DOMAIN_TTL_MS) return hit.info;
  try {
    const res = await fetch(`${API_BASE}/public/domain/${encodeURIComponent(host)}`, {
      signal: AbortSignal.timeout(DOMAIN_TIMEOUT_MS),
    });
    if (res.ok) {
      const info = (await res.json()) as DomainInfo;
      domainCache.set(host, { at: Date.now(), info });
      return info;
    }
    if (res.status === 404) {
      domainCache.set(host, { at: Date.now(), info: null });
      return null;
    }
  } catch {
    // unreachable API / timeout → fall through to the stale value
  }
  return hit ? hit.info : null;
}

type CookieOpts = { maxAge: number; httpOnly: boolean };
function setCookie(response: NextResponse, name: string, value: string, opts: CookieOpts): void {
  response.cookies.set(name, value, { ...opts, sameSite: "lax", secure: SECURE_COOKIES, path: "/" });
}

/**
 * Edge middleware — Phase 2-4: Thompson Sampling variant assignment.
 * Roadmap Phase 10: host-aware (tenant domains) and runner mode (landings only).
 *
 * For /l/[slug] requests:
 *  1. Ensure session cookie exists (creates new UUID if missing).
 *  2. If no variant cookie, the current variant is stale (> 1h) or the visitor arrived through a
 *     different creative (`mai_c` ≠ `_creative`, audit F-16), call the platform's /public/decide
 *     (or FastAPI /decide/landing when DECIDE_VIA_ML=1) to pick the best arm.
 *  3. Set _sid, _variant, _decision, _creative cookies on the response.
 *
 * On a tenant's custom domain (host not in PLATFORM_HOSTS) or in runner mode only landing
 * paths are served; "/" rewrites to the domain's default landing; admin routes are 404.
 *
 * For /dashboard routes: auth check is handled by Spring Boot — no JWT
 * validation in edge (keeps the edge thin and avoids secret leakage).
 */
export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const host = requestHost(request.headers);
  const platformHost = isPlatformHost(host);
  const landingOnly = RUNNER || !platformHost;
  const tenantHost = platformHost ? null : stripPort(host);

  // ── Tenant domain / runner: landings only ─────────────────────────────────
  if (landingOnly) {
    if (pathname === "/") {
      if (!tenantHost) return new NextResponse(null, { status: 404 });
      const info = await resolveDomain(tenantHost);
      if (!info?.defaultSlug) return new NextResponse(null, { status: 404 });
      const url = request.nextUrl.clone();
      url.pathname = `/l/${info.defaultSlug}`;
      return NextResponse.rewrite(url);
    }
    // admin preview needs a platform session; never served on a tenant domain or a runner
    if (pathname.startsWith("/l/preview/") || !LANDING_ONLY_ALLOWED.some((re) => re.test(pathname))) {
      return new NextResponse(null, { status: 404 });
    }
  }

  // ── Landing page A/B assignment ───────────────────────────────────────────
  if (pathname.startsWith("/l/")) {
    const slug = pathname.slice(3); // strip /l/
    if (!slug || slug.startsWith("preview/")) return NextResponse.next(); // preview = admin only, no decision

    if (tenantHost) {
      // Unknown or unverified host → nothing is served under it.
      const info = await resolveDomain(tenantHost);
      if (!info) return new NextResponse(null, { status: 404 });
    }

    const response = NextResponse.next();

    // 1. Session ID
    let sessionId = request.cookies.get("_sid")?.value;
    if (!sessionId) {
      sessionId = crypto.randomUUID();
      setCookie(response, "_sid", sessionId, { maxAge: 60 * 60 * 24 * 30, httpOnly: true }); // 30 days
    }

    // 2. If variant already assigned for this session, skip decide
    const existingVariant = request.cookies.get("_variant")?.value;
    const assignedAt = request.cookies.get("_assigned_at")?.value;
    const oneHourAgo = Date.now() - 60 * 60 * 1000;
    const isStale =
      !assignedAt || parseInt(assignedAt, 10) < oneHourAgo;

    // Audit F-16: a visitor who comes back within the hour through another ad must be re-decided,
    // otherwise every event of this session is attributed to the previous creative. `_creative`
    // holds our creative UUID (resolved by ML from the Meta ad id), so the raw `mai_c` that produced
    // it is remembered in `_creative_src` and compared with the one in the URL.
    const creative = creativeParams(request.nextUrl.searchParams);
    const existingCreative = (request.cookies.get("_creative")?.value ?? "").toLowerCase();
    const existingSrc = (request.cookies.get("_creative_src")?.value ?? "").toLowerCase();
    const creativeChanged = !!creative.raw && creative.raw.toLowerCase() !== existingSrc;

    if (!existingVariant || isStale || creativeChanged) {
      try {
        // Extract UTM params for context
        const url = request.nextUrl;
        const context: Record<string, string> = {};
        for (const key of ["utm_source", "utm_medium", "utm_campaign"]) {
          const v = url.searchParams.get(key);
          if (v) context[key] = v;
        }
        const ua = request.headers.get("user-agent") ?? "";
        context.device = /mobile/i.test(ua) ? "mobile" : "desktop";

        // Phase 11: which creative brought the visitor (resolved to our creative by ML via meta_ad_map)
        Object.assign(context, creative.context);

        // arch v1.1 §5.2 + vision v1.9.9 §12.1a: session_id only after consent.
        // Before consent decision, Thompson runs without a persistent identifier.
        const consentLevel = request.cookies.get("_consent")?.value;
        const hasTracking = consentLevel === "full" || consentLevel === "analytics_only";

        let decideRes: Response | null = null;

        if (DECIDE_VIA_ML) {
          // Legacy direct ML call (audit ML-12) — opt-in only, see DECIDE_VIA_ML above.
          const q = tenantHost ? `?host=${encodeURIComponent(tenantHost)}` : "";
          const variantRes = await fetch(`${API_BASE}/public/l/${slug}${q}`, {
            signal: AbortSignal.timeout(VARIANT_TIMEOUT_MS),
          });
          if (variantRes.ok) {
            const variant: { tenantId: string; id: string } = await variantRes.json();
            decideRes = await fetch(`${ML_SERVICE_URL}/decide/landing`, {
              method: "POST",
              headers: {
                "Content-Type": "application/json",
                // every ML router requires the internal key (server-side only — never exposed to the browser)
                "X-Internal-API-Key": process.env.INTERNAL_API_KEY ?? "",
              },
              body: JSON.stringify({
                tenant_id: variant.tenantId,
                session_id: hasTracking ? sessionId : null,
                creative_id: creative.creativeId,
                context_key: "global",
                context,
              }),
              signal: AbortSignal.timeout(DECIDE_TIMEOUT_MS),
            });
          }
        } else {
          // The platform decides (runner: tenant runner key; platform host: server-side internal key).
          const headers: Record<string, string> = { "Content-Type": "application/json" };
          if (RUNNER_KEY) headers["X-Runner-Key"] = RUNNER_KEY;
          else if (PLATFORM_DECIDE_KEY) headers["X-Internal-API-Key"] = PLATFORM_DECIDE_KEY;
          decideRes = await fetch(`${API_BASE}/public/decide`, {
            method: "POST",
            headers,
            body: JSON.stringify({
              slug,
              host: tenantHost,
              session_id: hasTracking ? sessionId : null,
              creative_id: creative.creativeId,
              context,
            }),
            signal: AbortSignal.timeout(DECIDE_TIMEOUT_MS),
          });
        }

        if (decideRes?.ok) {
          const decision: { variant_id: string; decision_id?: string; creative_id?: string | null; fallback?: boolean } =
            await decideRes.json();
          setCookie(response, "_variant", decision.variant_id, { maxAge: 60 * 60, httpOnly: false }); // 1h — reassign after an hour; readable by the tracker
          setCookie(response, "_decision", decision.decision_id ?? "", { maxAge: 60 * 60, httpOnly: false });
          setCookie(response, "_assigned_at", String(Date.now()), { maxAge: 60 * 60, httpOnly: true });
          // Phase 11: the resolved creative travels with the session so every event carries creative_id.
          // Audit F-16: on a fallback answer (no ML behind the platform) keep the creative known from the URL.
          const resolvedCreative = decision.creative_id || creative.creativeId || null;
          if (resolvedCreative) {
            setCookie(response, "_creative", resolvedCreative, { maxAge: 60 * 60, httpOnly: false }); // read by the tracker
          } else if (creativeChanged && existingCreative) {
            response.cookies.delete("_creative");
          }
          if (creative.raw) setCookie(response, "_creative_src", creative.raw, { maxAge: 60 * 60, httpOnly: true });
        } else if (creativeChanged && creative.creativeId) {
          // decide failed, but the visitor demonstrably came through this creative
          setCookie(response, "_creative", creative.creativeId, { maxAge: 60 * 60, httpOnly: false });
          setCookie(response, "_creative_src", creative.raw, { maxAge: 60 * 60, httpOnly: true });
        }
      } catch {
        // Decide failure / timeout is non-fatal — SSR page falls back to default variant
      }
    }

    return response;
  }

  return NextResponse.next();
}

export const config = {
  // Phase 10: every path, so tenant domains / runner mode can refuse non-landing routes.
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
