import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

// Edge middleware runs server-side — use internal Docker network URL when available
const API_BASE =
  process.env.API_INTERNAL_URL ??
  process.env.NEXT_PUBLIC_API_URL ??
  "http://localhost:8080/api/v1";

const ML_SERVICE_URL =
  process.env.ML_SERVICE_URL ?? "http://localhost:8000";

/**
 * Edge middleware — Phase 2-4: Thompson Sampling variant assignment.
 *
 * For /l/[slug] requests:
 *  1. Ensure session cookie exists (creates new UUID if missing).
 *  2. If no variant cookie or the current variant is stale (> 1h), call
 *     FastAPI /decide/landing to pick the best arm.
 *  3. Set _sid, _variant, _decision cookies on the response.
 *
 * For /dashboard routes: auth check is handled by Spring Boot — no JWT
 * validation in edge (keeps the edge thin and avoids secret leakage).
 */
export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // ── Landing page A/B assignment ───────────────────────────────────────────
  if (pathname.startsWith("/l/")) {
    const slug = pathname.slice(3); // strip /l/
    if (!slug) return NextResponse.next();

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
        // Fetch tenant_id from Spring Boot (public endpoint)
        const variantRes = await fetch(`${API_BASE}/public/l/${slug}`);
        if (variantRes.ok) {
          const variant: { tenantId: string; id: string } = await variantRes.json();

          // Extract UTM params for context
          const url = request.nextUrl;
          const context: Record<string, string> = {};
          for (const key of ["utm_source", "utm_medium", "utm_campaign"]) {
            const v = url.searchParams.get(key);
            if (v) context[key] = v;
          }
          const ua = request.headers.get("user-agent") ?? "";
          context.device =
            /mobile/i.test(ua) ? "mobile" : "desktop";

          // arch v1.1 §5.2 + vision v1.9.9 §12.1a: session_id only after consent.
          // Before consent decision, Thompson runs without a persistent identifier.
          const consentLevel = request.cookies.get("_consent")?.value;
          const hasTracking = consentLevel === "full" || consentLevel === "analytics_only";

          const decideRes = await fetch(`${ML_SERVICE_URL}/decide/landing`, {
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

          if (decideRes.ok) {
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
  matcher: ["/l/:slug*", "/dashboard/:path*"],
};
