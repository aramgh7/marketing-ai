/**
 * Browser-side API client.
 *
 * Base URL convention (audit 7.2): NEXT_PUBLIC_API_URL may be given with or without the
 * `/api/v1` context path; it is normalised to end with `/api/v1` exactly once, and call sites may
 * pass paths with or without the prefix. Both `apiFetch("/briefs")` and `apiFetch("/api/v1/briefs")`
 * reach `<origin>/api/v1/briefs`.
 */
const RAW_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

export const API_BASE = RAW_BASE.replace(/\/+$/, "").replace(/\/api\/v1$/, "") + "/api/v1";

export function apiPath(path: string): string {
  const p = path.startsWith("/") ? path : `/${path}`;
  return API_BASE + p.replace(/^\/api\/v1(?=\/|$)/, "");
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
export function storeAuthResponse(data: { token?: string; refreshToken?: string }): void {
  storeTokens(data?.token, data?.refreshToken);
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
    throw new Error("Unauthorized");
  }

  if (!res.ok) throw new Error(`API error ${res.status}`);

  // 204 No Content (e.g. PATCH /tenants/me/settings) has no body — audit 7.7
  if (res.status === 204 || res.headers.get("content-length") === "0") {
    return undefined as T;
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
