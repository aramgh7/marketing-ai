"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";

type Condition = { key: string; label: string; passed: boolean; detail: string };
type Status = {
  stage: string;
  stages: string[];
  stage_changed_at: string;
  next_stage: string | null;
  conditions: Condition[];
  can_advance: boolean;
  thresholds: Record<string, number | boolean>;
};
type Decision = {
  id: string; action: string; action_class: string; target_id: string | null; initiator: string;
  stage: string; verdict: string; reason: string; created_at: string;
};

const STAGE_HELP: Record<string, string> = {
  READ_ONLY: "Only protective writes (pause / stop-loss). Marketing API is read-only; Meta Automated Rules are the safety net.",
  GUARDED_WRITES: "Pause, resume and budget steps within thresholds; launches only as moderation probes; no campaign creation.",
  FULL: "All writes, thresholds still apply (budget step, cadence, rate limit, learning-phase lock).",
};

export default function MetaWritePage() {
  const [status, setStatus] = useState<Status | null>(null);
  const [decisions, setDecisions] = useState<Decision[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [overrideReason, setOverrideReason] = useState("");
  const [ovAction, setOvAction] = useState("*");
  const [ovTarget, setOvTarget] = useState("");
  const [ovReason, setOvReason] = useState("");
  const [thresholds, setThresholds] = useState<Record<string, string>>({});

  async function load() {
    try {
      const s = await apiFetch<Status>("/api/v1/tenants/me/meta-write");
      setStatus(s);
      setThresholds(Object.fromEntries(Object.entries(s.thresholds).map(([k, v]) => [k, String(v)])));
      setDecisions(await apiFetch<Decision[]>("/api/v1/tenants/me/meta-write/decisions?limit=40"));
      setError("");
    } catch (e: any) {
      setError(e?.message ?? "load failed");
    }
  }
  useEffect(() => { load(); }, []);

  async function setStage(stage: string, force = false) {
    setBusy(true); setError("");
    try {
      await apiFetch("/api/v1/tenants/me/meta-write/stage", {
        method: "PUT",
        body: JSON.stringify({ stage, override_reason: force ? overrideReason : undefined }),
      });
      await load();
    } catch (e: any) {
      setError(e?.message ?? "stage change refused");
    } finally { setBusy(false); }
  }

  async function savePolicy() {
    setBusy(true); setError("");
    try {
      const body: Record<string, number | boolean> = {};
      for (const [k, v] of Object.entries(thresholds)) {
        body[k] = v === "true" ? true : v === "false" ? false : Number(v);
      }
      await apiFetch("/api/v1/tenants/me/meta-write/policy", { method: "PUT", body: JSON.stringify(body) });
      await load();
    } catch (e: any) { setError(e?.message ?? "policy save failed"); } finally { setBusy(false); }
  }

  async function createOverride() {
    setBusy(true); setError("");
    try {
      await apiFetch("/api/v1/tenants/me/meta-write/overrides", {
        method: "POST",
        body: JSON.stringify({ action: ovAction, target_id: ovTarget || null, reason: ovReason, ttl_minutes: 30 }),
      });
      setOvReason(""); setOvTarget("");
      await load();
    } catch (e: any) { setError(e?.message ?? "override refused"); } finally { setBusy(false); }
  }

  if (!status) return <p className="text-gray-400 text-sm p-8">{error || "Loading..."}</p>;

  const idx = status.stages.indexOf(status.stage);
  const prev = idx > 0 ? status.stages[idx - 1] : null;

  return (
    <div className="max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Meta write policy</h1>
        <p className="text-sm text-gray-500">Stage-gated Marketing API writes. Protective pauses are never blocked.</p>
      </div>
      {error && <div className="rounded bg-red-50 border border-red-200 p-3 text-sm text-red-700">{error}</div>}

      <section className="bg-white rounded-lg border border-gray-200 p-5 space-y-3">
        <div className="flex items-center gap-3 flex-wrap">
          {status.stages.map((s, i) => (
            <span key={s} className={`px-3 py-1 rounded-full text-xs font-semibold ${i === idx ? "bg-indigo-600 text-white" : i < idx ? "bg-indigo-100 text-indigo-700" : "bg-gray-100 text-gray-500"}`}>{s}</span>
          ))}
          <span className="text-xs text-gray-400">since {status.stage_changed_at?.slice(0, 19)}</span>
        </div>
        <p className="text-sm text-gray-700">{STAGE_HELP[status.stage]}</p>
        {status.next_stage && (
          <div className="space-y-2">
            <h2 className="text-sm font-semibold text-gray-800">Readiness for {status.next_stage}</h2>
            <ul className="space-y-1">
              {status.conditions.map((c) => (
                <li key={c.key} className="text-sm flex gap-2">
                  <span className={c.passed ? "text-green-600" : "text-red-600"}>{c.passed ? "✔" : "✘"}</span>
                  <span className="text-gray-800">{c.label}</span>
                  <span className="text-gray-400 truncate">— {c.detail}</span>
                </li>
              ))}
            </ul>
            <div className="flex gap-2 items-center flex-wrap">
              <button disabled={busy || !status.can_advance} onClick={() => setStage(status.next_stage!)}
                className="px-3 py-1.5 rounded bg-indigo-600 text-white text-sm disabled:opacity-40">Advance to {status.next_stage}</button>
              <input value={overrideReason} onChange={(e) => setOverrideReason(e.target.value)} placeholder="override reason (forces advance)"
                className="border rounded px-2 py-1 text-sm w-72" />
              <button disabled={busy || overrideReason.trim().length < 10} onClick={() => setStage(status.next_stage!, true)}
                className="px-3 py-1.5 rounded bg-amber-500 text-white text-sm disabled:opacity-40">Force</button>
              {prev && <button disabled={busy} onClick={() => setStage(prev)} className="px-3 py-1.5 rounded bg-gray-200 text-sm">Downgrade to {prev}</button>}
            </div>
          </div>
        )}
      </section>

      <section className="bg-white rounded-lg border border-gray-200 p-5 space-y-3">
        <h2 className="text-sm font-semibold text-gray-800">Thresholds</h2>
        <div className="grid grid-cols-2 gap-3">
          {Object.entries(thresholds).map(([k, v]) => (
            <label key={k} className="text-xs text-gray-600 flex flex-col gap-1">
              {k}
              <input value={v} onChange={(e) => setThresholds({ ...thresholds, [k]: e.target.value })} className="border rounded px-2 py-1 text-sm" />
            </label>
          ))}
        </div>
        <button disabled={busy} onClick={savePolicy} className="px-3 py-1.5 rounded bg-indigo-600 text-white text-sm">Save thresholds</button>
      </section>

      <section className="bg-white rounded-lg border border-gray-200 p-5 space-y-3">
        <h2 className="text-sm font-semibold text-gray-800">One-shot override (30 min, mandatory reason)</h2>
        <div className="flex gap-2 flex-wrap items-center">
          <select value={ovAction} onChange={(e) => setOvAction(e.target.value)} className="border rounded px-2 py-1 text-sm">
            {["*", "RESUME", "BUDGET_CHANGE", "LAUNCH", "CREATE_AD_SET", "CREATE_CAMPAIGN", "AUDIENCE_UPDATE"].map((a) => <option key={a}>{a}</option>)}
          </select>
          <input value={ovTarget} onChange={(e) => setOvTarget(e.target.value)} placeholder="target id (optional)" className="border rounded px-2 py-1 text-sm w-56" />
          <input value={ovReason} onChange={(e) => setOvReason(e.target.value)} placeholder="reason (≥ 10 chars)" className="border rounded px-2 py-1 text-sm w-80" />
          <button disabled={busy || ovReason.trim().length < 10} onClick={createOverride} className="px-3 py-1.5 rounded bg-amber-500 text-white text-sm disabled:opacity-40">Create override</button>
        </div>
      </section>

      <section className="bg-white rounded-lg border border-gray-200 p-5">
        <h2 className="text-sm font-semibold text-gray-800 mb-2">Recent decisions</h2>
        <div className="overflow-x-auto">
          <table className="text-xs w-full">
            <thead><tr className="text-left text-gray-500"><th className="pr-3">time</th><th className="pr-3">action</th><th className="pr-3">target</th><th className="pr-3">by</th><th className="pr-3">stage</th><th className="pr-3">verdict</th><th>reason</th></tr></thead>
            <tbody>
              {decisions.map((d) => (
                <tr key={d.id} className="border-t border-gray-100">
                  <td className="pr-3 whitespace-nowrap">{d.created_at.slice(0, 19)}</td>
                  <td className="pr-3">{d.action}</td>
                  <td className="pr-3 truncate max-w-[10rem]">{d.target_id ?? "—"}</td>
                  <td className="pr-3">{d.initiator}</td>
                  <td className="pr-3">{d.stage}</td>
                  <td className={`pr-3 font-semibold ${d.verdict === "ALLOW" ? "text-green-600" : d.verdict === "DENY" ? "text-red-600" : "text-amber-600"}`}>{d.verdict}</td>
                  <td className="text-gray-600">{d.reason}</td>
                </tr>
              ))}
              {decisions.length === 0 && <tr><td colSpan={7} className="text-gray-400 py-2">No decisions yet</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
