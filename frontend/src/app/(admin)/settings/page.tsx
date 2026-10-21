"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";
import DomainsCard from "@/components/admin/DomainsCard";

export default function SettingsPage() {
  const [email, setEmail]               = useState("");
  const [alertChannel, setAlertChannel] = useState("TELEGRAM");
  const [slackWebhook, setSlackWebhook] = useState("");
  const [saving, setSaving]             = useState(false);
  const [saved, setSaved]               = useState(false);
  const [loading, setLoading]           = useState(true);

  useEffect(() => {
    apiFetch<any>("/api/v1/tenants/me/settings")
      .then((data) => {
        setEmail(data.report_email ?? "");
        setAlertChannel(data.alert_channel ?? "TELEGRAM");
        setSlackWebhook(data.slack_webhook_url ?? "");
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

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

      {/* Roadmap Phase 10: tenant landing domains + self-hosted runner keys */}
      <DomainsCard />
    </div>
  );
}
