"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import DomainsCard from "@/components/admin/DomainsCard";

export default function SettingsPage() {
  const [email, setEmail]               = useState("");
  const [alertChannel, setAlertChannel] = useState("TELEGRAM");
  const [slackWebhook, setSlackWebhook] = useState("");
  const [downloadMode, setDownloadMode] = useState("FINAL");   // roadmap Phase 11: FINAL | PREVIEW_ONLY
  const [metaPageId, setMetaPageId] = useState("");
  const [igActorId, setIgActorId] = useState("");
  const [metaSaving, setMetaSaving] = useState(false);
  const [metaMsg, setMetaMsg] = useState("");
  const [saving, setSaving]             = useState(false);
  const [saved, setSaved]               = useState(false);
  const [loading, setLoading]           = useState(true);

  useEffect(() => {
    apiFetch<any>("/api/v1/tenants/me/settings")
      .then((data) => {
        setEmail(data.report_email ?? "");
        setAlertChannel(data.alert_channel ?? "TELEGRAM");
        setSlackWebhook(data.slack_webhook_url ?? "");
        setDownloadMode(data.creative_download_mode ?? "FINAL");
      })
      .catch(() => {})
      .finally(() => setLoading(false));
    // Phase 11: Meta page / Instagram actor used when the platform creates ads
    apiFetch<any>("/api/v1/tenants/me/meta-credentials")
      .then((d) => {
        setMetaPageId(d?.meta_page_id ?? d?.metaPageId ?? "");
        setIgActorId(d?.meta_instagram_actor_id ?? d?.metaInstagramActorId ?? "");
      })
      .catch(() => {});
  }, []);

  async function saveMetaPage() {
    setMetaSaving(true); setMetaMsg("");
    try {
      await apiFetch("/api/v1/tenants/me/meta-credentials", {
        method: "PUT",
        body: JSON.stringify({ meta_page_id: metaPageId.trim() || null, meta_instagram_actor_id: igActorId.trim() || null }),
      });
      setMetaMsg("Saved.");
    } catch (e: any) {
      setMetaMsg(e?.message ?? "Save failed");
    } finally { setMetaSaving(false); }
  }

  async function handleSave() {
    setSaving(true);
    setSaved(false);
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
    } catch {
      alert("Save failed");
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <p className="text-gray-400 text-sm p-8">Loading...</p>;

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
        </div>
      </div>

      {/* Roadmap Phase 11: identities Meta needs when the platform creates ads */}
      <div className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
        <div>
          <h2 className="font-semibold text-gray-800">Meta page</h2>
          <p className="text-xs text-gray-400">Used when a creative is published from the Meta ads page (ad creative = page post).</p>
        </div>
        <div className="grid md:grid-cols-2 gap-3">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Facebook Page ID</label>
            <input value={metaPageId} onChange={(e) => setMetaPageId(e.target.value)} placeholder="1234567890"
                   className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Instagram actor ID (optional)</label>
            <input value={igActorId} onChange={(e) => setIgActorId(e.target.value)} placeholder="17841400000000000"
                   className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400" />
          </div>
        </div>
        <div className="flex items-center gap-3">
          <button onClick={saveMetaPage} disabled={metaSaving}
                  className="px-6 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
            {metaSaving ? "Saving..." : "Save Meta page"}
          </button>
          {metaMsg && <span className={`text-sm ${metaMsg === "Saved." ? "text-green-600" : "text-red-600"}`}>{metaMsg}</span>}
        </div>
      </div>

      {/* Roadmap Phase 10: tenant landing domains + self-hosted runner keys */}
      <DomainsCard />
    </div>
  );
}
