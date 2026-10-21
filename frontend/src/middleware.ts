import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { RUNNER, RUNNER_KEY, isPlatformHost, requestHost, stripPort } from "@/lib/hosts";

// Edge middleware runs server-side — use internal Docker network URL when available
const API_BASE =
  process.env.API_INTERNAL_URL ??
  process.env.NEXT_PUBLIC_API_URL ??
  "http://localhost:8080/api/v1";

const ML_SERVICE_URL = process.env.ML_SERVICE_URL ?? "http://localhost:8000";

/** Runner mode or no ML access: decisions go through the platform API with the tenant's runner key. */
const DECIDE_VIA_PUBLIC = RUNNER || (!process.env.ML_SERVICE_URL && !!RUNNER_KEY);

/** Paths that may be served on a tenant domain / in runner mode (admin never is). */
const LANDING_ONLY_ALLOWED = [/^\/l\//, /^\/_next\//, /^\/favicon\.ico$/, /^\/api\/health$/];

// ── Tenant domain resolution cache (module-level, 60 s TTL) ───────────────────
interface DomainInfo { tenantId: string; host: string; kind: string; hosting: string; defaultSlug: string | null }
const DOMAIN_TTL_MS = 60_000;
const domainCache = new Map<string, { at: number; info: DomainInfo | null }>();

async function resolveDomain(host: string): Promise<DomainInfo | null> {
  const hit = domainCache.get(host);
  if (hit && Date.now() - hit.at < DOMAIN_TTL_MS) return hit.info;
  let info: DomainInfo | null = null;
  try {
    const res = await fetch(`${API_BASE}/public/domain/${encodeURIComponent(host)}`);
    if (res.ok) info = (await res.json()) as DomainInfo;
  } catch {
    // unreachable API → treat as unknown host (404), re-checked after TTL
  }
  domainCache.set(host, { at: Date.now(), info });
  return info;
}

/**
 * Edge middleware — Phase 2-4: Thompson Sampling variant assignment.
 * Roadmap Phase 10: host-aware (tenant domains) and runner mode (landings only).
 *
 * For /l/[slug] requests:
 *  1. Ensure session cookie exists (creates new UUID if missing).
 *  2. If no variant cookie or the current variant is stale (> 1h), call
 *     FastAPI /decide/landing (or the platform's /public/decide in runner mode) to pick the best arm.
 *  3. Set _sid, _variant, _decision cookies on the response.
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
      response.cookies.set("_sid", sessionId, {
        maxAge: 60 * 60 * 24 * 30, // 30 days
        httpOnly: true,
        sameSite: "lax",
      });
    }

    // 2. If variant already assigned for this session, skip decide
    const existingVariant = request.cookies.get("_variant")?.value;
    const assignedAt = request.cookies.get("_assigned_at")?.value;
    const oneHourAgo = Date.now() - 60 * 60 * 1000;
    const isStale =
      !assignedAt || parseInt(assignedAt, 10) < oneHourAgo;

    if (!existingVariant || isStale) {
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

        // arch v1.1 §5.2 + vision v1.9.9 §12.1a: session_id only after consent.
        // Before consent decision, Thompson runs without a persistent identifier.
        const consentLevel = request.cookies.get("_consent")?.value;
        const hasTracking = consentLevel === "full" || consentLevel === "analytics_only";

        let decideRes: Response | null = null;

        if (DECIDE_VIA_PUBLIC) {
          // Runner on the client's server: no ML key, no DB — the platform decides.
          decideRes = await fetch(`${API_BASE}/public/decide`, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "X-Runner-Key": RUNNER_KEY,
            },
            body: JSON.stringify({
              slug,
              host: tenantHost,
              session_id: hasTracking ? sessionId : null,
              context,
            }),
          });
        } else {
          // Fetch tenant_id from Spring Boot (public endpoint); scoped to the tenant domain when on one
          const q = tenantHost ? `?host=${encodeURIComponent(tenantHost)}` : "";
          const variantRes = await fetch(`${API_BASE}/public/l/${slug}${q}`);
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
                context_key: "global",
                context,
              }),
            });
          }
        }

        if (decideRes?.ok) {
          const decision: { variant_id: string; decision_id: string } =
            await decideRes.json();
          response.cookies.set("_variant", decision.variant_id, {
            maxAge: 60 * 60, // 1h — reassign if page is revisited after an hour
            httpOnly: false,  // readable by client for tracker
            sameSite: "lax",
          });
          response.cookies.set("_decision", decision.decision_id, {
            maxAge: 60 * 60,
            httpOnly: false,
            sameSite: "lax",
          });
          response.cookies.set("_assigned_at", String(Date.now()), {
            maxAge: 60 * 60,
            httpOnly: true,
            sameSite: "lax",
          });
        }
      } catch {
        // Decide failure is non-fatal — SSR page falls back to default variant
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
