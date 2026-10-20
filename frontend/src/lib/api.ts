/**
 * Browser-side API client.
 *
 * Base URL convention (audit 7.2): NEXT_PUBLIC_API_URL may be given with or without the
 * `/api/v1` context path; it is normalised to end with `/api/v1` exactly once, and call sites may
 * pass paths with or without the prefix. Both `apiFetch("/briefs")` and `apiFetch("/api/v1/briefs")`
 * reach `<origin>/api/v1/briefs`.
 *
 * Roadmap 9-5: a final 401 in the browser clears the stored tokens and redirects to `/login`;
 * non-2xx responses throw `ApiError` (status + parsed body) so pages can show server messages.
 */
const RAW_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

export const API_BASE = RAW_BASE.replace(/\/+$/, "").replace(/\/api\/v1$/, "") + "/api/v1";

export function apiPath(path: string): string {
  const p = path.startsWith("/") ? path : `/${path}`;
  return API_BASE + p.replace(/^\/api\/v1(?=\/|$)/, "");
}

/** Thrown for every non-2xx response. `body` is the parsed JSON (or raw text) when present. */
export class ApiError extends Error {
  status: number;
  body: unknown;
  constructor(status: number, body: unknown) {
    const msg =
      body && typeof body === "object" && "error" in (body as Record<string, unknown>)
        ? String((body as Record<string, unknown>).error)
        : `API error ${status}`;
    super(msg);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

function readToken(key: string): string | null {
  try {
    return typeof window !== "undefined" ? localStorage.getItem(key) : null;
  } catch {
    return null;
  }
}

function storeTokens(access?: string | null, refresh?: string | null): void {
  try {
    if (access) localStorage.setItem("access_token", access);
    if (refresh) localStorage.setItem("refresh_token", refresh);
  } catch {
    /* storage unavailable */
  }
}

/** AuthResponse from Spring: { token, refreshToken, userId, tenantId, role, email } (audit 7.3). */
export function storeAuthResponse(data: {
  token?: string;
  refreshToken?: string;
  tenantId?: string;
  email?: string;
  role?: string;
}): void {
  storeTokens(data?.token, data?.refreshToken);
  try {
    if (data?.tenantId) localStorage.setItem("tenant_id", String(data.tenantId));
    if (data?.email) localStorage.setItem("email", data.email);
    if (data?.role) localStorage.setItem("role", data.role);
  } catch {
    /* storage unavailable */
  }
}

/** True when an access token is stored (the guard's only check; the server validates it). */
export function hasSession(): boolean {
  return !!readToken("access_token");
}

/** Who is signed in, from localStorage (for the sidebar). */
export function currentUser(): { email: string | null; tenantId: string | null; role: string | null } {
  return { email: readToken("email"), tenantId: readToken("tenant_id"), role: readToken("role") };
}

export function clearAuth(): void {
  try {
    for (const k of ["access_token", "refresh_token", "tenant_id", "email", "role"]) {
      localStorage.removeItem(k);
    }
  } catch {
    /* storage unavailable */
  }
}

/** POST /auth/logout (revokes the refresh token), then clear local storage. Never throws. */
export async function logout(): Promise<void> {
  const refresh = readToken("refresh_token");
  if (refresh) {
    try {
      await fetch(apiPath("/auth/logout"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: refresh }),
      });
    } catch {
      /* best effort */
    }
  }
  clearAuth();
}

function redirectToLogin(): void {
  if (typeof window === "undefined") return;
  if (window.location.pathname.startsWith("/login") || window.location.pathname.startsWith("/register")) return;
  const next = encodeURIComponent(window.location.pathname + window.location.search);
  window.location.replace(`/login?next=${next}`);
}

async function parseBody(res: Response): Promise<unknown> {
  const text = await res.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export async function apiFetch<T>(
  path: string,
  options?: RequestInit,
  _retried = false
): Promise<T> {
  const token = readToken("access_token");

  const res = await fetch(apiPath(path), {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options?.headers ?? {}),
    },
  });

  if (res.status === 401 && !_retried) {
    const stored = readToken("refresh_token");
    if (stored) {
      const refreshRes = await fetch(apiPath("/auth/refresh"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: stored }),
      });
      if (refreshRes.ok) {
        const data = await refreshRes.json();
        storeAuthResponse(data);
        return apiFetch<T>(path, options, true);
      }
    }
    // Final 401: the session is gone — drop the tokens and go to the login page (roadmap 9-5).
    clearAuth();
    redirectToLogin();
    throw new ApiError(401, { error: "Unauthorized" });
  }

  if (!res.ok) throw new ApiError(res.status, await parseBody(res));

  // 204 No Content (e.g. PATCH /tenants/me/settings) has no body — audit 7.7
  if (res.status === 204 || res.headers.get("content-length") === "0") {
    return undefined as T;
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
