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
    super(apiErrorMessage(status, body));
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}

/**
 * Audit F-5: the backend's @RestControllerAdvice answers `{error, detail, status}` where `detail` is the
 * human-readable reason ("Variant cannot be approved: …") and `error` the reason-phrase ("Conflict").
 * Prefer `detail`, then `message` (Spring's default body), then `error`; a 403 from an ADMIN-only
 * endpoint gets a plain "not enough rights" text so pages can show it as is.
 */
function apiErrorMessage(status: number, body: unknown): string {
  if (body && typeof body === "object") {
    const b = body as Record<string, unknown>;
    for (const key of ["detail", "message", "error"]) {
      const v = b[key];
      if (typeof v === "string" && v.trim() && !(key === "error" && status === 403 && /^forbidden$/i.test(v.trim()))) {
        return v;
      }
    }
  } else if (typeof body === "string" && body.trim() && body.length <= 300) {
    return body;
  }
  if (status === 403) return FORBIDDEN_MESSAGE;
  if (status === 401) return "Unauthorized";
  return `API error ${status}`;
}

/** Shown when an ADMIN-only action (autonomy level, Meta-write policy, …) is refused for the current role. */
export const FORBIDDEN_MESSAGE = "Недостаточно прав (ADMIN only)";

/** True when the error is a 403 — the signed-in role may not perform this action. */
export function isForbidden(e: unknown): boolean {
  return e instanceof ApiError && e.status === 403;
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

let sessionDropped = false;

/**
 * Audit F-7: negative cache — a URL that returned 404 is not re-fetched within 30 s.
 * React StrictMode double-invokes effects in dev, so without this the same absent resource
 * fires two identical requests.  Only GET-like requests are cached (non-mutating methods).
 */
const NOT_FOUND_CACHE = new Map<string, number>();
const NOT_FOUND_TTL_MS = 30_000;

function isNegativelyCached(url: string, method = "GET"): boolean {
  if (method !== "GET" && method !== "HEAD") return false;
  const ts = NOT_FOUND_CACHE.get(url);
  if (!ts) return false;
  if (Date.now() - ts < NOT_FOUND_TTL_MS) return true;
  NOT_FOUND_CACHE.delete(url);
  return false;
}

function markNotFound(url: string, method = "GET"): void {
  if (method !== "GET" && method !== "HEAD") return;
  NOT_FOUND_CACHE.set(url, Date.now());
}

/** Audit F-8: default per-request timeout so no apiFetch can hang indefinitely. */
const DEFAULT_TIMEOUT_MS = 15_000;

/** AuthResponse from Spring: { token, refreshToken, userId, tenantId, role, email } (audit 7.3). */
export function storeAuthResponse(data: {
  token?: string;
  refreshToken?: string;
  tenantId?: string;
  email?: string;
  role?: string;
}): void {
  storeTokens(data?.token, data?.refreshToken);
  sessionDropped = false; // a fresh login re-arms the single clearAuth() on the next final 401
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

/**
 * Audit F-3: a single in-flight refresh. Every request that meets a 401 while the access token is
 * expired awaits the same promise, so N parallel calls make one `POST /auth/refresh` (the backend
 * rotates the refresh token, a second concurrent refresh would be rejected) and then all retry once.
 * Resolves to the new access token, or null when the session is gone (tokens cleared once).
 */
let refreshInFlight: Promise<string | null> | null = null;

function refreshAccessToken(): Promise<string | null> {
  if (refreshInFlight) return refreshInFlight;
  refreshInFlight = (async () => {
    const stored = readToken("refresh_token");
    if (!stored) return null;
    try {
      const refreshRes = await fetch(apiPath("/auth/refresh"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: stored }),
      });
      if (!refreshRes.ok) return null;
      const data = await refreshRes.json();
      storeAuthResponse(data);
      return typeof data?.token === "string" ? data.token : readToken("access_token");
    } catch {
      return null;
    }
  })().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

/** Final 401: the session is gone — drop the tokens once and go to the login page (roadmap 9-5). */
function dropSession(): never {
  if (!sessionDropped) {
    sessionDropped = true;
    clearAuth();
    redirectToLogin();
  }
  throw new ApiError(401, { error: "Unauthorized" });
}

export async function apiFetch<T>(
  path: string,
  options?: RequestInit,
  _retried = false
): Promise<T> {
  const token = readToken("access_token");
  const url = apiPath(path);
  const method = (options?.method ?? "GET").toUpperCase();

  // Audit F-7: negative cache — skip the fetch if we recently got a 404 for this URL
  if (!_retried && isNegativelyCached(url, method)) {
    throw new ApiError(404, { error: "Not Found" });
  }

  // Audit F-8: apply a default timeout when the caller hasn't supplied a signal
  const signal: AbortSignal | undefined =
    options?.signal ??
    (typeof AbortSignal !== "undefined" && AbortSignal.timeout
      ? AbortSignal.timeout(DEFAULT_TIMEOUT_MS)
      : undefined);

  const res = await fetch(url, {
    ...options,
    signal,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options?.headers ?? {}),
    },
  });

  if (res.status === 401 && !_retried) {
    // Another call may already have refreshed while this one was in flight: retry with the newer token first.
    const current = readToken("access_token");
    if (current && current !== token) return apiFetch<T>(path, options, true);
    const fresh = await refreshAccessToken();
    if (fresh) return apiFetch<T>(path, options, true);
    dropSession();
  }
  if (res.status === 401) dropSession();

  if (!res.ok) {
    const body = await parseBody(res);
    // Audit F-7: remember 404s so rapid retries skip the network
    if (res.status === 404) markNotFound(url, method);
    throw new ApiError(res.status, body);
  }

  // 204 No Content (e.g. PATCH /tenants/me/settings) has no body — audit 7.7
  if (res.status === 204 || res.headers.get("content-length") === "0") {
    return undefined as T;
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
