"use client";
import { useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import DomainsCard from "@/components/admin/DomainsCard";
import LoadError from "@/components/admin/LoadError";

export default function SettingsPage() {
  const [email, setEmail]               = useState("");
  const [alertChannel, setAlertChannel] = useState("TELEGRAM");
  const [slackWebhook, setSlackWebhook] = useState("");
  const [downloadMode, setDownloadMode] = useState("FINAL");   // roadmap Phase 11: FINAL | PREVIEW_ONLY
  const [metaPageId, setMetaPageId] = useState("");
  const [igActorId, setIgActorId] = useState("");
  const [metaToken, setMetaToken] = useState("");
  const [metaAdAccountId, setMetaAdAccountId] = useState("");
  const [metaAppId, setMetaAppId] = useState("");
  const [hasToken, setHasToken] = useState(false);
  const [metaSaving, setMetaSaving] = useState(false);
  const [metaMsg, setMetaMsg] = useState("");
  const [saving, setSaving]             = useState(false);
  const [saved, setSaved]               = useState(false);
  const [saveError, setSaveError]       = useState("");
  const [loading, setLoading]           = useState(true);
  const [loadError, setLoadError]       = useState<string | null>(null);
  const [metaLoadError, setMetaLoadError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    setMetaLoadError(null);
    apiFetch<any>("/api/v1/tenants/me/settings")
      .then((data) => {
        setEmail(data?.report_email ?? "");
        setAlertChannel(data?.alert_channel ?? "TELEGRAM");
        setSlackWebhook(data?.slack_webhook_url ?? "");
        setDownloadMode(data?.creative_download_mode ?? "FINAL");
      })
      .catch((e: unknown) => setLoadError(e instanceof ApiError ? e.message : "Could not load settings"))
      .finally(() => setLoading(false));
    // Phase 11: Meta page / Instagram actor used when the platform creates ads
    apiFetch<any>("/api/v1/tenants/me/meta-credentials")
      .then((d) => {
        setMetaPageId(d?.meta_page_id ?? d?.metaPageId ?? "");
        setIgActorId(d?.meta_instagram_actor_id ?? d?.metaInstagramActorId ?? "");
        setMetaAdAccountId(d?.ad_account_id ?? d?.adAccountId ?? "");
        setMetaAppId(d?.app_id ?? d?.appId ?? "");
        setHasToken(!!d?.has_token);
      })
      .catch((e: unknown) => setMetaLoadError(e instanceof ApiError ? e.message : "Could not load Meta credentials"));
  }, []);

  useEffect(() => { load(); }, [load]);

  async function saveMetaPage() {
    setMetaSaving(true); setMetaMsg("");
    try {
      await apiFetch("/api/v1/tenants/me/meta-credentials", {
        method: "PUT",
        body: JSON.stringify({
          meta_page_id: metaPageId.trim() || null,
          meta_instagram_actor_id: igActorId.trim() || null,
          ...(metaToken.trim() ? { access_token: metaToken.trim(), ad_account_id: metaAdAccountId.trim() } : {}),
          ...(metaAdAccountId.trim() && !metaToken.trim() ? { ad_account_id: metaAdAccountId.trim() } : {}),
          ...(metaAppId.trim() ? { app_id: metaAppId.trim() } : {}),
        }),
      });
      if (metaToken.trim()) { setMetaToken(""); setHasToken(true); }
      setMetaMsg("Saved.");
    } catch (e: any) {
      setMetaMsg(e?.message ?? "Save failed");
    } finally { setMetaSaving(false); }
  }

  async function handleSave() {
    setSaving(true);
    setSaved(false);
    setSaveError("");
    try {
      await apiFetch("/api/v1/tenants/me/settings", {
        method: "PATCH",
        body: JSON.stringify({
          report_email:      email,
          alert_channel:     alertChannel,
          slack_webhook_url: slackWebhook,
          creative_download_mode: downloadMode,
        }),
      });
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch (e: unknown) {
      setSaveError(e instanceof ApiError ? e.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <p className="text-gray-400 text-sm p-8">Loading...</p>;
  if (loadError) {
    return (
      <div className="max-w-3xl p-8">
        <LoadError title="Could not load settings" message={loadError} onRetry={load} />
      </div>
    );
  }

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Settings</h1>
        <p className="text-sm text-gray-500 mt-1">Tenant configuration</p>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-6 space-y-5">
        <h2 className="font-semibold text-gray-800">Notifications</h2>

        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Weekly Report Email
          </label>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="marketer@company.com"
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Alert Channel
          </label>
          <select
            value={alertChannel}
            onChange={(e) => setAlertChannel(e.target.value)}
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          >
            <option value="TELEGRAM">Telegram</option>
            <option value="SLACK">Slack</option>
            <option value="BOTH">Both</option>
          </select>
        </div>

        {(alertChannel === "SLACK" || alertChannel === "BOTH") && (
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Slack Webhook URL
            </label>
            <input
              type="url"
              value={slackWebhook}
              onChange={(e) => setSlackWebhook(e.target.value)}
              placeholder="https://hooks.slack.com/services/..."
              className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
            />
          </div>
        )}

        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Creative downloads
          </label>
          <select
            value={downloadMode}
            onChange={(e) => setDownloadMode(e.target.value)}
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          >
            <option value="FINAL">FINAL — final renders can be downloaded (internal use); a creative that reaches Meta by hand stays &quot;not in Meta&quot;</option>
            <option value="PREVIEW_ONLY">PREVIEW_ONLY — only previews download; final renders go to Meta from the Meta ads page</option>
          </select>
          <p className="text-xs text-gray-400 mt-1">
            Creatives reach Meta only through the platform, so the creative → landing → path pair stays measurable (roadmap Phase 11).
          </p>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-6 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
          >
            {saving ? "Saving..." : "Save Settings"}
          </button>
          {saved && (
            <span className="text-green-600 text-sm">Saved!</span>
          )}
          {saveError && <span className="text-red-600 text-sm">{saveError}</span>}
        </div>
      </div>

      {/* Roadmap Phase 11: identities Meta needs when the platform creates ads */}
      <div className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
        <div>
          <h2 className="font-semibold text-gray-800">Meta credentials</h2>
          <p className="text-xs text-gray-400">Access token, ad account, page ID and Instagram actor ID used when the platform creates or syncs ads.</p>
        </div>
        {metaLoadError && <LoadError title="Could not load Meta credentials" message={metaLoadError} onRetry={load} />}

        {/* Token */}
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Meta Access Token
            {hasToken && <span className="ml-2 text-xs font-normal text-green-600">● token set</span>}
          </label>
          <input
            value={metaToken}
            onChange={(e) => setMetaToken(e.target.value)}
            placeholder={hasToken ? "Enter new token to rotate…" : "EAAxxxxxxx… (system user token)"}
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400 font-mono"
          />
          <p className="text-xs text-gray-400 mt-1">
            System user token from Meta Business Manager → System Users → Generate token. Required scopes: <code>ads_management</code>, <code>ads_read</code>, <code>business_management</code>.
          </p>
        </div>

        <div className="grid md:grid-cols-2 gap-3">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Ad Account ID</label>
            <input value={metaAdAccountId} onChange={(e) => setMetaAdAccountId(e.target.value)} placeholder="act_123456789"
                   className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
            <p className="text-xs text-gray-400 mt-1">Required when saving a new token. Format: <code>act_</code> + numeric ID.</p>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">App ID (optional)</label>
            <input value={metaAppId} onChange={(e) => setMetaAppId(e.target.value)} placeholder="1234567890"
                   className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
          </div>
        </div>

        <div className="grid md:grid-cols-2 gap-3">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Facebook Page ID</label>
            <input value={metaPageId} onChange={(e) => setMetaPageId(e.target.value)} placeholder="1234567890"
                   className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Instagram Actor ID (optional)</label>
            <input value={igActorId} onChange={(e) => setIgActorId(e.target.value)} placeholder="17841400000000000"
                   className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button onClick={saveMetaPage} disabled={metaSaving}
                  className="px-6 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
            {metaSaving ? "Saving..." : "Save Meta credentials"}
          </button>
          {metaMsg && <span className={`text-sm ${metaMsg === "Saved." ? "text-green-600" : "text-red-600"}`}>{metaMsg}</span>}
        </div>
      </div>

      {/* Roadmap Phase 10: tenant landing domains + self-hosted runner keys */}
      <DomainsCard />
    </div>
  );
}
