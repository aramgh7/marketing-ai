/**
 * Audit F-24: block-level URLs come from the LLM / DB. Only http(s) (and same-page anchors / relative
 * paths for CTAs) may become an href — `javascript:`, `data:` and the like are dropped.
 */
export function safeHttpUrl(url?: string | null): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    return u.protocol === "https:" || u.protocol === "http:" ? u.toString() : null;
  } catch {
    return null;
  }
}

/** For CTA links: absolute http(s) URL, or a relative path / `#anchor` on the same page; `fallback` otherwise. */
export function safeCtaHref(url: string | undefined, fallback = "#form"): string {
  if (!url) return fallback;
  const v = url.trim();
  if (v.startsWith("#") || (v.startsWith("/") && !v.startsWith("//") && !v.startsWith("/\\"))) return v;
  return safeHttpUrl(v) ?? fallback;
}
