"use client";
import { Fragment, useCallback, useEffect, useState } from "react";
import { API_BASE, ApiError, apiFetch } from "@/lib/api";
import LoadError from "@/components/admin/LoadError";

/**
 * Roadmap Phase 11 (`.ai/creative-publishing.md`): the only road into Meta goes through the platform.
 *  - linkage health banner (test launches are blocked while red)
 *  - creatives: publish to Meta (ad created PAUSED, url_tags carry {{ad.id}}), activate / pause, download (not a publishing path)
 *  - ads in Meta: the ad map (managed / reconciled / manual), unmanaged ads to link, drift to acknowledge
 */

type Creative = {
  id: string; title: string | null; type: string; status: string; fileUrl: string | null; thumbnailUrl: string | null;
  durationSec: number | null; isAnchor: boolean; metaAdSetId: string | null;
  publishStatus?: string | null; creativeCode?: string | null;
};
type Readiness = { key: string; label: string; passed: boolean; detail?: string | null };
type Preview = { creative_code: string; ad_name: string; destination_url: string; url_tags: string; readiness: Readiness[] };
type AdMapRow = {
  id: string; meta_ad_id: string; meta_adset_id: string | null; meta_campaign_id: string | null; ad_name: string | null;
  creative_id: string | null; creative_title: string | null; creative_code: string | null; entry_slug: string | null;
  destination_url: string | null; url_tags: string | null; source: string; status: string;
  drift: Record<string, { expected?: unknown; actual?: unknown }> | null; last_seen_at: string | null; created_at: string;
};
/** GET /meta/linkage-health (MetaAdMapController): snake_case keys — audit F-10. */
type Health = {
  healthy?: boolean; active_ads?: number; unmanaged_count?: number; missing_tags_count?: number; drift_count?: number;
  summary?: string | null;
};
/** GET /tenants/me/settings — only the key this page needs (audit F-13). */
type TenantSettings = { creative_download_mode?: "FINAL" | "PREVIEW_ONLY" | string | null };
type MetaCreds = { meta_page_id?: string | null; meta_instagram_actor_id?: string | null; has_token?: boolean };
type Pixel = { id: string; isDefault: boolean; status: string };
type Campaign = { id: string; name: string; metaCampaignId: string | null; status: string };
type AdSet = { id: string; campaignId: string; metaAdSetId: string | null; status: string; creativeId: string | null };
type Variant = { id: string; slug: string; name: string; status: string };
type Domain = { id: string; host: string; kind: string; status: string; default_slug: string | null };

const PUBLISH_CLS: Record<string, string> = {
  NOT_IN_META:     "bg-gray-100 text-gray-700",
  PENDING_REVIEW:  "bg-yellow-100 text-yellow-800",
  APPROVED_PAUSED: "bg-blue-100 text-blue-800",
  ACTIVE_IN_META:  "bg-green-100 text-green-800",
  PAUSED_IN_META:  "bg-orange-100 text-orange-800",
  REJECTED:        "bg-red-100 text-red-800",
  UNMANAGED:       "bg-red-50 text-red-700 border border-red-200",
  DRIFT:           "bg-purple-100 text-purple-800",
};
const MAP_CLS: Record<string, string> = {
  PAUSED: "bg-orange-100 text-orange-800", PENDING_REVIEW: "bg-yellow-100 text-yellow-800", APPROVED: "bg-blue-100 text-blue-800",
  ACTIVE: "bg-green-100 text-green-800", REJECTED: "bg-red-100 text-red-800", ARCHIVED: "bg-gray-100 text-gray-500",
  UNMANAGED: "bg-red-50 text-red-700 border border-red-200", DRIFT: "bg-purple-100 text-purple-800", UNKNOWN: "bg-gray-100 text-gray-600",
};
const CTAS = ["LEARN_MORE", "SIGN_UP", "BOOK_NOW", "CONTACT_US", "APPLY_NOW"];

function Chip({ value, map }: { value: string | null | undefined; map: Record<string, string> }) {
  const v = value ?? "—";
  return <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${map[v] ?? "bg-gray-100 text-gray-600"}`}>{v}</span>;
}

function fileHref(url: string | null): string | null {
  if (!url) return null;
  if (/^https?:\/\//.test(url)) return url;
  const origin = API_BASE.replace(/\/api\/v1$/, "");
  return url.startsWith("/api/v1") ? origin + url : url.startsWith("/") ? API_BASE + url : `${API_BASE}/${url}`;
}

export default function MetaAdsPage() {
  const [creatives, setCreatives] = useState<Creative[]>([]);
  const [adMap, setAdMap] = useState<AdMapRow[]>([]);
  const [health, setHealth] = useState<Health | null>(null);
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [adSets, setAdSets] = useState<AdSet[]>([]);
  const [variants, setVariants] = useState<Variant[]>([]);
  const [domains, setDomains] = useState<Domain[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [metaCreds, setMetaCreds] = useState<MetaCreds | null>(null);
  const [hasDefaultPixel, setHasDefaultPixel] = useState<boolean | null>(null);
  const [loadErrors, setLoadErrors] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [downloadMode, setDownloadMode] = useState<string>("FINAL");
  const [publishing, setPublishing] = useState<string | null>(null); // creative id with the panel open
  const [busy, setBusy] = useState<string | null>(null);
  const [expandedDrift, setExpandedDrift] = useState<string | null>(null);
  const [linkChoice, setLinkChoice] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    // Audit F-19: a failed load is reported per source instead of silently showing an empty page.
    const failed: string[] = [];
    const safe = async <T,>(label: string, p: Promise<T>, fallback: T): Promise<T> => {
      try { return await p; }
      catch (e) { failed.push(`${label}: ${e instanceof ApiError ? e.message : "request failed"}`); return fallback; }
    };
    setLoading(true);
    const [c, m, h, camps, vars, doms, settings, creds, pixels] = await Promise.all([
      safe("creatives", apiFetch<Creative[]>("/api/v1/creatives"), []),
      safe("ad map", apiFetch<AdMapRow[]>("/api/v1/meta/ad-map"), []),
      safe("linkage health", apiFetch<Health>("/api/v1/meta/linkage-health"), null as unknown as Health),
      safe("campaigns", apiFetch<Campaign[]>("/api/v1/campaigns"), []),
      safe("landings", apiFetch<Variant[]>("/api/v1/landings?status=ACTIVE"), []),
      safe("domains", apiFetch<Domain[]>("/api/v1/domains"), []),
      safe("settings", apiFetch<TenantSettings>("/api/v1/tenants/me/settings"), null as unknown as TenantSettings),
      safe("meta credentials", apiFetch<MetaCreds>("/api/v1/tenants/me/meta-credentials"), null as unknown as MetaCreds),
      safe("pixels", apiFetch<Pixel[]>("/api/v1/pixels"), [] as Pixel[]),
    ]);
    setCreatives((c ?? []).filter((x) => x.status !== "ARCHIVED"));
    setAdMap(m ?? []);
    setHealth(h);
    setMetaCreds(creds);
    setHasDefaultPixel((pixels ?? []).some((p) => p.isDefault && p.status !== "ARCHIVED"));
    setCampaigns(camps ?? []);
    setVariants(vars ?? []);
    setDomains((doms ?? []).filter((d) => d.status === "VERIFIED" && d.kind === "LANDING"));
    setDownloadMode(String(settings?.creative_download_mode ?? "FINAL").toUpperCase());
    // ad sets of every campaign (small lists)
    const sets: AdSet[] = [];
    for (const cp of camps ?? []) {
      const s = await safe(`ad sets of ${cp.name}`, apiFetch<AdSet[]>(`/api/v1/ad-sets?campaignId=${cp.id}`), []);
      sets.push(...s);
    }
    setAdSets(sets);
    setLoadErrors(failed);
    setLoading(false);
  }, []);
  useEffect(() => { load(); }, [load]);

  async function act(creativeId: string, action: "activate" | "pause") {
    setBusy(creativeId); setError(""); setNotice("");
    try {
      await apiFetch(`/api/v1/creatives/${creativeId}/${action}`, { method: "POST" });
      setNotice(action === "activate" ? "Ad activated in Meta." : "Ad paused in Meta.");
      await load();
    } catch (e: any) { setError(e?.message ?? `${action} failed`); }
    finally { setBusy(null); }
  }
  async function link(row: AdMapRow) {
    const creativeId = linkChoice[row.id];
    if (!creativeId) return;
    setBusy(row.id); setError("");
    try {
      await apiFetch(`/api/v1/meta/ad-map/${row.id}/link`, { method: "POST", body: JSON.stringify({ creative_id: creativeId }) });
      await load();
    } catch (e: any) { setError(e?.message ?? "link failed"); }
    finally { setBusy(null); }
  }
  async function ackDrift(row: AdMapRow) {
    setBusy(row.id); setError("");
    try {
      await apiFetch(`/api/v1/meta/ad-map/${row.id}/acknowledge-drift`, { method: "POST" });
      await load();
    } catch (e: any) { setError(e?.message ?? "acknowledge failed"); }
    finally { setBusy(null); }
  }

  const healthy = health?.healthy ?? true;
  const unmanagedCount = health?.unmanaged_count ?? adMap.filter((r) => r.status === "UNMANAGED").length;
  const driftCount = health?.drift_count ?? adMap.filter((r) => r.status === "DRIFT").length;
  const missingTags = health?.missing_tags_count ?? 0;
  const previewOnly = downloadMode === "PREVIEW_ONLY";

  /**
   * Audit F-13: `/files/...` answers 403 + `X-Error` with PREVIEW_ONLY; a plain link would open an
   * empty tab. Fetch with the token, surface the reason, and open the blob only on success.
   */
  async function download(href: string, title: string | null) {
    setError("");
    try {
      const token = typeof window !== "undefined" ? localStorage.getItem("access_token") : null;
      const res = await fetch(href, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
      if (!res.ok) {
        let detail = res.headers.get("X-Error") ?? "";
        if (!detail) {
          try { const b = await res.json(); detail = b?.detail ?? b?.message ?? b?.error ?? ""; } catch { /* no body */ }
        }
        setError(detail || (res.status === 403 ? "Download refused for this tenant." : `Download failed (${res.status}).`));
        return;
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = (title ?? "creative").replace(/[^\w.-]+/g, "_") || "creative";
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch {
      setError("Download failed: network error.");
    }
  }

  return (
    <div className="max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Meta ads</h1>
        <p className="text-sm text-gray-500">
          A creative reaches Meta only through this page: the ad is created paused with the landing URL and Meta&apos;s ad id in the URL,
          so every click is attributed to the creative → landing → path pair. Ads created by hand are unmanaged and excluded from learning.
        </p>
      </div>

      {/* Connection status: 4 credential indicators */}
      <div className="rounded-lg border border-gray-200 bg-white p-4">
        <div className="text-sm font-semibold text-gray-700 mb-2">Connection status</div>
        <div className="flex flex-wrap gap-4 text-xs">
          {[
            { label: "Page ID", ok: !!metaCreds?.meta_page_id },
            { label: "Instagram ID", ok: !!metaCreds?.meta_instagram_actor_id },
            { label: "Token valid", ok: !!metaCreds?.has_token },
            { label: "Default pixel set", ok: hasDefaultPixel === true },
          ].map(({ label, ok }) => (
            <span key={label} className={`flex items-center gap-1 font-medium ${ok ? "text-green-600" : "text-red-500"}`}>
              {ok ? "✓" : "✗"} {label}
            </span>
          ))}
        </div>
        {metaCreds !== null && (!metaCreds.meta_page_id || !metaCreds.has_token) && (
          <p className="text-xs text-gray-400 mt-1">
            Set credentials in <a href="/settings" className="underline">Settings → Meta credentials</a>.
          </p>
        )}
      </div>

      {health && (
        <div className={`rounded-lg border p-4 text-sm ${healthy ? "bg-green-50 border-green-200 text-green-800" : "bg-red-50 border-red-200 text-red-800"}`}>
          <div className="font-semibold">{healthy ? "Linkage healthy" : "Linkage red — test launches are blocked while red"}</div>
          <div className="text-xs mt-1">
            active ads {health.active_ads ?? "—"} · unmanaged {unmanagedCount} · missing url_tags {missingTags} · drift {driftCount}
            {health.summary && <span className="ml-2 opacity-80">{health.summary}</span>}
          </div>
        </div>
      )}
      {loadErrors.length > 0 && (
        <LoadError title="Some data could not be loaded" message={loadErrors.join(" · ")} onRetry={() => { void load(); }} />
      )}
      {loading && <p className="text-sm text-gray-400">Loading…</p>}
      {error && <div className="rounded bg-red-50 border border-red-200 p-3 text-sm text-red-700">{error}</div>}
      {notice && <div className="rounded bg-green-50 border border-green-200 p-3 text-sm text-green-700">{notice}</div>}

      {/* ── Creatives ─────────────────────────────────────────────────────── */}
      <section className="bg-white rounded-lg border border-gray-200 p-4">
        <h2 className="text-sm font-semibold text-gray-800 mb-2">Creatives</h2>
        <div className="overflow-x-auto">
          <table className="text-xs w-full">
            <thead>
              <tr className="text-left text-gray-500">
                <th className="pr-2"></th><th className="pr-2">title</th><th className="pr-2">type</th><th className="pr-2">code</th>
                <th className="pr-2">in Meta</th><th>actions</th>
              </tr>
            </thead>
            <tbody>
              {creatives.map((c) => {
                const ps = c.publishStatus ?? "NOT_IN_META";
                const href = fileHref(c.fileUrl);
                const thumb = fileHref(c.thumbnailUrl);
                return (
                  <Fragment key={c.id}>
                    <tr className="border-t border-gray-100 align-top">
                      <td className="pr-2 py-1">
                        {thumb ? <img src={thumb} alt="" className="w-12 h-8 object-cover rounded bg-gray-100" /> : <div className="w-12 h-8 rounded bg-gray-100" />}
                      </td>
                      <td className="pr-2 py-1 text-gray-900">{c.title ?? c.id}{c.isAnchor && <span className="ml-1 text-[10px] text-indigo-600">anchor</span>}</td>
                      <td className="pr-2 py-1 text-gray-500">{c.type}{c.durationSec ? ` · ${Math.round(c.durationSec)}s` : ""}</td>
                      <td className="pr-2 py-1 font-mono text-gray-700">{c.creativeCode ?? "—"}</td>
                      <td className="pr-2 py-1"><Chip value={ps} map={PUBLISH_CLS} /></td>
                      <td className="py-1">
                        <div className="flex flex-wrap gap-1 items-center">
                          {(ps === "NOT_IN_META" || ps === "REJECTED" || ps === "UNMANAGED") && (
                            <button onClick={() => setPublishing(publishing === c.id ? null : c.id)} className="px-2 py-1 rounded bg-indigo-600 text-white">
                              {publishing === c.id ? "Close" : "Publish to Meta"}
                            </button>
                          )}
                          {ps === "APPROVED_PAUSED" && (
                            <button disabled={busy === c.id} onClick={() => act(c.id, "activate")} className="px-2 py-1 rounded bg-green-600 text-white disabled:opacity-50">Activate</button>
                          )}
                          {ps === "ACTIVE_IN_META" && (
                            <button disabled={busy === c.id} onClick={() => act(c.id, "pause")} className="px-2 py-1 rounded bg-orange-500 text-white disabled:opacity-50">Pause</button>
                          )}
                          {ps === "PENDING_REVIEW" && <span className="text-gray-400">waiting for Meta&apos;s review verdict</span>}
                          {href && !previewOnly && (
                            <button type="button" onClick={() => download(href, c.title)} className="text-gray-500 underline"
                                    title="A creative that reaches Meta by hand is unmanaged and excluded from learning.">
                              download (not a publishing path)
                            </button>
                          )}
                          {href && previewOnly && (
                            <span className="text-gray-400 cursor-not-allowed"
                                  title="creative_download_mode = PREVIEW_ONLY: final renders are published to Meta from this page only (Settings → Creative downloads).">
                              download disabled (preview-only)
                            </span>
                          )}
                        </div>
                      </td>
                    </tr>
                    {publishing === c.id && (
                      <tr className="bg-gray-50">
                        <td colSpan={6} className="p-3">
                          <PublishPanel creative={c} adSets={adSets} campaigns={campaigns} variants={variants} domains={domains}
                                        onDone={async (msg) => { setNotice(msg); setPublishing(null); await load(); }}
                                        onError={setError} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
              {creatives.length === 0 && <tr><td colSpan={6} className="text-gray-400 py-2">No creatives yet — upload one via the API or the montage flow.</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {/* ── Ads in Meta ───────────────────────────────────────────────────── */}
      <section className="bg-white rounded-lg border border-gray-200 p-4">
        <h2 className="text-sm font-semibold text-gray-800 mb-2">Ads in Meta (ad map)</h2>
        <p className="text-xs text-gray-400 mb-2">MANAGED = created here · RECONCILED = found by the hourly read of the ad account · MANUAL = linked by a person.</p>
        <div className="overflow-x-auto">
          <table className="text-xs w-full">
            <thead>
              <tr className="text-left text-gray-500">
                <th className="pr-2">ad</th><th className="pr-2">status</th><th className="pr-2">source</th><th className="pr-2">creative</th>
                <th className="pr-2">landing</th><th className="pr-2">last seen</th><th>actions</th>
              </tr>
            </thead>
            <tbody>
              {adMap.map((r) => (
                <Fragment key={r.id}>
                  <tr className={`border-t border-gray-100 align-top ${r.status === "ARCHIVED" ? "opacity-50" : ""}`}>
                    <td className="pr-2 py-1">
                      <div className="text-gray-900">{r.ad_name ?? "(no name)"}</div>
                      <div className="font-mono text-gray-400">{r.meta_ad_id}{r.meta_adset_id ? ` · set ${r.meta_adset_id}` : ""}</div>
                    </td>
                    <td className="pr-2 py-1"><Chip value={r.status} map={MAP_CLS} /></td>
                    <td className="pr-2 py-1 text-gray-500">{r.source}</td>
                    <td className="pr-2 py-1">
                      {r.creative_id ? (
                        <span className="text-gray-900"><span className="font-mono">{r.creative_code ?? ""}</span> {r.creative_title ?? r.creative_id}</span>
                      ) : (
                        <span className="text-red-600">not linked</span>
                      )}
                    </td>
                    <td className="pr-2 py-1 text-gray-500">
                      {r.entry_slug ? `/l/${r.entry_slug}` : "—"}
                      {r.url_tags && !r.url_tags.includes("mai_c=") && <div className="text-red-600">url_tags without mai_c</div>}
                      {!r.url_tags && r.status !== "UNMANAGED" && <div className="text-red-600">no url_tags</div>}
                    </td>
                    <td className="pr-2 py-1 text-gray-400 whitespace-nowrap">{r.last_seen_at ? r.last_seen_at.slice(0, 16) : "—"}</td>
                    <td className="py-1">
                      <div className="flex flex-wrap gap-1 items-center">
                        {r.status === "UNMANAGED" && (
                          <>
                            <select value={linkChoice[r.id] ?? ""} onChange={(e) => setLinkChoice({ ...linkChoice, [r.id]: e.target.value })} className="border rounded px-1 py-0.5">
                              <option value="">link to creative…</option>
                              {creatives.map((c) => <option key={c.id} value={c.id}>{c.creativeCode ?? ""} {c.title ?? c.id}</option>)}
                            </select>
                            <button disabled={!linkChoice[r.id] || busy === r.id} onClick={() => link(r)} className="px-2 py-1 rounded bg-indigo-600 text-white disabled:opacity-50">Link</button>
                          </>
                        )}
                        {r.status === "DRIFT" && (
                          <>
                            <button onClick={() => setExpandedDrift(expandedDrift === r.id ? null : r.id)} className="px-2 py-1 rounded border border-gray-300 text-gray-700">
                              {expandedDrift === r.id ? "Hide drift" : "Show drift"}
                            </button>
                            <button disabled={busy === r.id} onClick={() => ackDrift(r)} className="px-2 py-1 rounded bg-purple-600 text-white disabled:opacity-50">Acknowledge drift</button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                  {expandedDrift === r.id && r.drift && (
                    <tr className="bg-purple-50">
                      <td colSpan={7} className="p-2">
                        <div className="text-[11px] text-purple-900 font-semibold mb-1">Changed in Meta since publishing (the arm is frozen until acknowledged):</div>
                        <table className="text-[11px]">
                          <tbody>
                            {Object.entries(r.drift).map(([field, d]) => (
                              <tr key={field}>
                                <td className="pr-3 font-mono text-purple-800">{field}</td>
                                <td className="pr-3 text-gray-500">expected: {String(d?.expected ?? "—")}</td>
                                <td className="text-gray-900">actual: {String(d?.actual ?? "—")}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
              {adMap.length === 0 && <tr><td colSpan={7} className="text-gray-400 py-2">No ads mapped yet. Publish a creative, or wait for the hourly reconciliation of the ad account.</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

// ── Publish panel ────────────────────────────────────────────────────────────

function PublishPanel({ creative, adSets, campaigns, variants, domains, onDone, onError }: {
  creative: Creative; adSets: AdSet[]; campaigns: Campaign[]; variants: Variant[]; domains: Domain[];
  onDone: (msg: string) => Promise<void>; onError: (msg: string) => void;
}) {
  const [adSetId, setAdSetId] = useState(adSets.find((s) => s.metaAdSetId)?.metaAdSetId ?? "");
  const [freeAdSet, setFreeAdSet] = useState("");
  const [entrySlug, setEntrySlug] = useState(variants[0]?.slug ?? "");
  const [host, setHost] = useState("");
  const [primaryText, setPrimaryText] = useState("");
  const [headline, setHeadline] = useState(creative.title ?? "");
  const [cta, setCta] = useState("LEARN_MORE");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [previewError, setPreviewError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<{ meta_ad_id: string; status: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    setPreview(null); setPreviewError("");
    const q = new URLSearchParams();
    if (entrySlug) q.set("entry_slug", entrySlug);
    if (host) q.set("host", host);
    apiFetch<Preview>(`/api/v1/creatives/${creative.id}/publish-preview?${q.toString()}`)
      .then((p) => { if (!cancelled) setPreview(p); })
      .catch((e: any) => { if (!cancelled) setPreviewError(e?.message ?? "preview failed"); });
    return () => { cancelled = true; };
  }, [creative.id, entrySlug, host]);

  const effectiveAdSet = freeAdSet.trim() || adSetId;
  const allReady = !!preview && preview.readiness.every((r) => r.passed);
  const canSubmit = allReady && !!effectiveAdSet && !!entrySlug && primaryText.trim().length > 0 && headline.trim().length > 0 && !submitting && !result;

  async function submit() {
    setSubmitting(true);
    try {
      const res = await apiFetch<{ meta_ad_id: string; status: string }>(`/api/v1/creatives/${creative.id}/publish`, {
        method: "POST",
        body: JSON.stringify({
          meta_adset_id: effectiveAdSet, entry_slug: entrySlug, primary_text: primaryText.trim(), headline: headline.trim(),
          call_to_action: cta, host: host || null,
        }),
      });
      setResult(res);
      await onDone(`Published: Meta ad ${res.meta_ad_id} created paused (${res.status}). Activate after Meta's review verdict.`);
    } catch (e: any) {
      const body = e?.body;
      const items = body && typeof body === "object" && Array.isArray((body as any).readiness)
        ? ((body as any).readiness as Readiness[]).filter((r) => !r.passed).map((r) => r.label).join("; ")
        : "";
      onError((e?.message ?? "publish failed") + (items ? ` — ${items}` : ""));
    } finally { setSubmitting(false); }
  }

  const campaignName = (id: string) => campaigns.find((c) => c.id === id)?.name ?? id;

  return (
    <div className="grid md:grid-cols-2 gap-3 text-xs">
      <div className="space-y-2">
        <div>
          <label className="block text-gray-600 mb-0.5">Meta ad set</label>
          <select value={adSetId} onChange={(e) => setAdSetId(e.target.value)} className="border rounded px-2 py-1 w-full">
            <option value="">— choose —</option>
            {adSets.filter((s) => s.metaAdSetId).map((s) => (
              <option key={s.id} value={s.metaAdSetId!}>{campaignName(s.campaignId)} · {s.metaAdSetId} ({s.status})</option>
            ))}
          </select>
          <input value={freeAdSet} onChange={(e) => setFreeAdSet(e.target.value)} placeholder="or paste a Meta ad set id" className="border rounded px-2 py-1 w-full mt-1" />
        </div>
        <div>
          <label className="block text-gray-600 mb-0.5">Entry landing (slug)</label>
          <select value={entrySlug} onChange={(e) => setEntrySlug(e.target.value)} className="border rounded px-2 py-1 w-full">
            <option value="">— choose —</option>
            {variants.map((v) => <option key={v.id} value={v.slug}>{v.slug} — {v.name}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-gray-600 mb-0.5">Domain (optional, verified landing domains)</label>
          <select value={host} onChange={(e) => setHost(e.target.value)} className="border rounded px-2 py-1 w-full">
            <option value="">platform default</option>
            {domains.map((d) => <option key={d.id} value={d.host}>{d.host}</option>)}
          </select>
        </div>
        <div>
          <label className="block text-gray-600 mb-0.5">Primary text (approved phrases only — checked by compliance)</label>
          <textarea value={primaryText} onChange={(e) => setPrimaryText(e.target.value)} className="border rounded p-2 w-full h-20" />
        </div>
        <div className="flex gap-2">
          <div className="flex-1">
            <label className="block text-gray-600 mb-0.5">Headline</label>
            <input value={headline} onChange={(e) => setHeadline(e.target.value)} className="border rounded px-2 py-1 w-full" />
          </div>
          <div>
            <label className="block text-gray-600 mb-0.5">CTA</label>
            <select value={cta} onChange={(e) => setCta(e.target.value)} className="border rounded px-2 py-1">
              {CTAS.map((c) => <option key={c}>{c}</option>)}
            </select>
          </div>
        </div>
      </div>

      <div className="space-y-2">
        <div className="bg-white rounded border border-gray-200 p-2">
          <div className="text-gray-500 mb-1">Ad name</div>
          <div className="font-mono text-gray-900">{preview?.ad_name ?? "…"}</div>
          <div className="text-gray-500 mt-2 mb-1">Destination URL</div>
          <div className="font-mono break-all text-gray-900">{preview?.destination_url ?? "…"}</div>
          <div className="text-gray-500 mt-2 mb-1">url_tags (Meta fills {"{{ad.id}}"} on every click)</div>
          <div className="font-mono break-all text-gray-900">{preview?.url_tags ?? "…"}</div>
        </div>
        <div className="bg-white rounded border border-gray-200 p-2">
          <div className="text-gray-500 mb-1">Readiness</div>
          {previewError && <div className="text-red-600">{previewError}</div>}
          <ul className="space-y-0.5">
            {(preview?.readiness ?? []).map((r) => (
              <li key={r.key} className="flex gap-2">
                <span className={r.passed ? "text-green-600" : "text-red-600"}>{r.passed ? "✔" : "✘"}</span>
                <span className="text-gray-800">{r.label}</span>
                {r.detail && <span className="text-gray-400">{r.detail}</span>}
              </li>
            ))}
          </ul>
        </div>
        <div className="flex items-center gap-2">
          <button disabled={!canSubmit} onClick={submit} className="px-3 py-1.5 rounded bg-indigo-600 text-white disabled:opacity-50">
            {submitting ? "Publishing…" : "Publish (paused) to Meta"}
          </button>
          {!allReady && preview && <span className="text-gray-400">fix the red readiness items first</span>}
          {result && <span className="text-green-700">Meta ad id <span className="font-mono">{result.meta_ad_id}</span></span>}
        </div>
      </div>
    </div>
  );
}
