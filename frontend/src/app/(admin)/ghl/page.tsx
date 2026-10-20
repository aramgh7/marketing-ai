"use client";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";

/** Rows from GhlSubaccountController (roadmap 8-9 / 9-5). */
interface Subaccount {
  id: string;
  location_id: string;
  name: string | null;
  role: "MAIN" | "AGENCY" | "WEBINAR" | "MAILING" | string;
  is_active: boolean;
  created_at: string;
  has_secret: boolean;
  has_api_key: boolean;
}

interface IntegrityRow {
  check_date: string;
  form_submits: number;
  booked: number;
  schedule_sent: number;
  delta_booked_schedule: number | null;
  delta_form_booked: number | null;
  verdict: string;
  alert_fired: boolean;
}

const ROLES = ["MAIN", "AGENCY", "WEBINAR", "MAILING"];
const input =
  "w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500";

const pct = (v: number | null) => (v == null ? "—" : `${(Number(v) * 100).toFixed(1)}%`);

export default function GhlPage() {
  const [rows, setRows] = useState<Subaccount[]>([]);
  const [integrity, setIntegrity] = useState<IntegrityRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [savedHint, setSavedHint] = useState<string | null>(null);

  const [locationId, setLocationId] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState("MAIN");
  const [secret, setSecret] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, i] = await Promise.all([
        apiFetch<Subaccount[]>("/api/v1/ghl/subaccounts"),
        apiFetch<IntegrityRow[]>("/api/v1/ghl/integrity?days=14").catch(() => []),
      ]);
      setRows(Array.isArray(s) ? s : []);
      setIntegrity(Array.isArray(i) ? i : []);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Load failed");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function upsert(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    setSavedHint(null);
    try {
      const res = await apiFetch<{ location_id: string; role: string; status: string; webhook_url_hint?: string }>(
        "/api/v1/ghl/subaccounts",
        {
          method: "POST",
          body: JSON.stringify({
            location_id: locationId.trim(),
            name: name.trim() || null,
            role,
            webhook_secret: secret.trim() || null,
            api_key: apiKey.trim() || null,
          }),
        }
      );
      setSavedHint(res?.webhook_url_hint ?? `Saved ${res?.location_id} as ${res?.role}`);
      setLocationId("");
      setName("");
      setSecret("");
      setApiKey("");
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }

  async function deactivate(id: string) {
    setError(null);
    try {
      await apiFetch(`/api/v1/ghl/subaccounts/${id}`, { method: "DELETE" });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Deactivate failed");
    }
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">GoHighLevel</h1>
        <p className="text-sm text-gray-500 mt-1">
          One row per GHL location: its role, the webhook secret its workflows send, and the private-integration API key used to write our ids into contacts. Opportunity status is accepted from MAIN only.
        </p>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}
      {savedHint && <p className="text-sm text-green-700 font-mono break-all">{savedHint}</p>}

      <form onSubmit={upsert} className="bg-white rounded-xl border border-gray-200 p-6 grid grid-cols-2 gap-4 max-w-3xl">
        <h2 className="col-span-2 font-semibold text-gray-800">Add / update sub-account</h2>
        <label className="text-sm">
          <span className="text-gray-700">Location id</span>
          <input required value={locationId} onChange={(e) => setLocationId(e.target.value)} className={input} />
        </label>
        <label className="text-sm">
          <span className="text-gray-700">Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} className={input} />
        </label>
        <label className="text-sm">
          <span className="text-gray-700">Role</span>
          <select value={role} onChange={(e) => setRole(e.target.value)} className={input}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          <span className="text-gray-700">Webhook secret (X-Webhook-Secret)</span>
          <input type="password" value={secret} onChange={(e) => setSecret(e.target.value)} className={input} autoComplete="off" />
        </label>
        <label className="text-sm col-span-2">
          <span className="text-gray-700">LeadConnector API key (private integration)</span>
          <input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} className={input} autoComplete="off" />
        </label>
        <div className="col-span-2 text-right">
          <button type="submit" disabled={saving} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
            {saving ? "Saving…" : "Save"}
          </button>
        </div>
      </form>

      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100">
          <h2 className="font-semibold text-gray-800">Sub-accounts</h2>
        </div>
        {loading ? (
          <p className="px-6 py-8 text-gray-400 text-sm">Loading…</p>
        ) : rows.length === 0 ? (
          <p className="px-6 py-8 text-gray-400 text-sm">No sub-accounts yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-6 py-3 text-left">Location</th>
                <th className="px-6 py-3 text-left">Role</th>
                <th className="px-6 py-3 text-left">Secret</th>
                <th className="px-6 py-3 text-left">API key</th>
                <th className="px-6 py-3 text-left">Active</th>
                <th className="px-6 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="border-t border-gray-100">
                  <td className="px-6 py-3">
                    <div className="font-medium text-gray-800">{r.name || "—"}</div>
                    <div className="text-xs text-gray-400 font-mono">{r.location_id}</div>
                  </td>
                  <td className="px-6 py-3">{r.role}</td>
                  <td className="px-6 py-3">{r.has_secret ? "✓" : "—"}</td>
                  <td className="px-6 py-3">{r.has_api_key ? "✓" : "—"}</td>
                  <td className="px-6 py-3">{r.is_active ? "yes" : "no"}</td>
                  <td className="px-6 py-3 text-right">
                    {r.is_active && (
                      <button onClick={() => deactivate(r.id)} className="text-red-600 hover:underline">Deactivate</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100">
          <h2 className="font-semibold text-gray-800">Funnel integrity (last 14 days)</h2>
          <p className="text-xs text-gray-500">Daily 07:15 UTC: FORM_SUBMIT vs BOOKED in CRM vs Schedule sent to Meta.</p>
        </div>
        {integrity.length === 0 ? (
          <p className="px-6 py-8 text-gray-400 text-sm">No checks yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-6 py-3 text-left">Date</th>
                <th className="px-6 py-3 text-right">Forms</th>
                <th className="px-6 py-3 text-right">Booked</th>
                <th className="px-6 py-3 text-right">Schedule sent</th>
                <th className="px-6 py-3 text-right">Δ booked/schedule</th>
                <th className="px-6 py-3 text-right">Δ form/booked</th>
                <th className="px-6 py-3 text-left">Verdict</th>
              </tr>
            </thead>
            <tbody>
              {integrity.map((r) => (
                <tr key={r.check_date} className="border-t border-gray-100">
                  <td className="px-6 py-3 text-gray-600">{String(r.check_date).slice(0, 10)}</td>
                  <td className="px-6 py-3 text-right">{r.form_submits}</td>
                  <td className="px-6 py-3 text-right">{r.booked}</td>
                  <td className="px-6 py-3 text-right">{r.schedule_sent}</td>
                  <td className="px-6 py-3 text-right">{pct(r.delta_booked_schedule)}</td>
                  <td className="px-6 py-3 text-right">{pct(r.delta_form_booked)}</td>
                  <td className="px-6 py-3">
                    <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${r.alert_fired ? "bg-red-100 text-red-800" : "bg-green-100 text-green-800"}`}>
                      {r.verdict}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
