"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";

type Metrics = {
  generations: number; edited: number; accepted_without_edits: number;
  accepted_without_edits_rate: number | null; edit_rate: number | null; target_accepted_rate: number;
  total_corrections: number; few_shot_count: number; unique_editors: number;
  weekly: { week: string; generations: number; edited: number }[];
  renders: { approved: number; rejected: number };
};
type Correction = {
  id: string; generated_text: string; approved_text: string; delta_tags: string[] | null;
  use_as_few_shot: boolean; content_type: string | null; edit_reason: string | null; created_at: string;
};
type RenderFeedback = { id: string; render_id: string; decision: string; reason: string | null; note: string | null; created_at: string };

const EDIT_REASONS = ["TONE", "FACTS", "LEGAL", "LENGTH", "STRUCTURE", "OTHER"];

export default function RlhaPage() {
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [rows, setRows] = useState<Correction[]>([]);
  const [renders, setRenders] = useState<RenderFeedback[]>([]);
  const [error, setError] = useState("");
  const [form, setForm] = useState({ content_type: "landing_block", generated_text: "", approved_text: "", reason: "TONE" });

  async function load() {
    try {
      setMetrics(await apiFetch<Metrics>("/api/v1/rlha/metrics"));
      setRows(await apiFetch<Correction[]>("/api/v1/rlha/corrections?limit=60"));
      setRenders(await apiFetch<RenderFeedback[]>("/api/v1/rlha/render-feedback?limit=20"));
      setError("");
    } catch (e: any) { setError(e?.message ?? "load failed"); }
  }
  useEffect(() => { load(); }, []);

  async function toggle(c: Correction) {
    await apiFetch(`/api/v1/rlha/corrections/${c.id}`, { method: "PATCH", body: JSON.stringify({ use_as_few_shot: !c.use_as_few_shot }) });
    load();
  }
  async function setReason(c: Correction, reason: string) {
    await apiFetch(`/api/v1/rlha/corrections/${c.id}`, { method: "PATCH", body: JSON.stringify({ edit_reason: reason }) });
    load();
  }
  async function addPair() {
    try {
      await apiFetch("/api/v1/rlha/corrections", { method: "POST", body: JSON.stringify(form) });
      setForm({ ...form, generated_text: "", approved_text: "" });
      load();
    } catch (e: any) { setError(e?.message ?? "add failed"); }
  }

  if (!metrics) return <p className="text-gray-400 text-sm p-8">{error || "Loading..."}</p>;
  const rate = metrics.accepted_without_edits_rate;

  return (
    <div className="max-w-5xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Learning from edits (RLHA)</h1>
        <p className="text-sm text-gray-500">Every edit of generated text becomes a style example for the next generation. Target: 70 % accepted without edits.</p>
      </div>
      {error && <div className="rounded bg-red-50 border border-red-200 p-3 text-sm text-red-700">{error}</div>}

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <Card label="Accepted without edits" value={rate === null ? "—" : `${rate}%`} sub={`target ${metrics.target_accepted_rate}%`} good={rate !== null && rate >= metrics.target_accepted_rate} />
        <Card label="Generations" value={String(metrics.generations)} sub={`${metrics.edited} edited`} />
        <Card label="Correction pairs" value={String(metrics.total_corrections)} sub={`${metrics.few_shot_count} used as examples`} />
        <Card label="Renders" value={`${metrics.renders.approved} ✔ / ${metrics.renders.rejected} ✘`} sub="approved / rejected" />
      </div>

      {metrics.weekly.length > 0 && (
        <section className="bg-white rounded-lg border border-gray-200 p-4">
          <h2 className="text-sm font-semibold text-gray-800 mb-2">Weekly edit rate</h2>
          <div className="flex gap-2 items-end h-24">
            {metrics.weekly.map((w) => {
              const pct = w.generations ? Math.round((1 - w.edited / w.generations) * 100) : 0;
              return (
                <div key={w.week} className="flex flex-col items-center text-[10px] text-gray-500 flex-1">
                  <div className="w-full bg-indigo-200 rounded-t" style={{ height: `${Math.max(4, pct)}%` }} title={`${pct}% accepted clean`} />
                  <span>{w.week.slice(5)}</span>
                </div>
              );
            })}
          </div>
        </section>
      )}

      <section className="bg-white rounded-lg border border-gray-200 p-4 space-y-2">
        <h2 className="text-sm font-semibold text-gray-800">Add a pair manually</h2>
        <div className="grid md:grid-cols-2 gap-2">
          <textarea value={form.generated_text} onChange={(e) => setForm({ ...form, generated_text: e.target.value })} placeholder="what the system wrote" className="border rounded p-2 text-sm h-20" />
          <textarea value={form.approved_text} onChange={(e) => setForm({ ...form, approved_text: e.target.value })} placeholder="what you approved" className="border rounded p-2 text-sm h-20" />
        </div>
        <div className="flex gap-2 items-center">
          <select value={form.content_type} onChange={(e) => setForm({ ...form, content_type: e.target.value })} className="border rounded px-2 py-1 text-sm">
            <option value="landing_block">landing block</option><option value="brief">brief</option><option value="ad_copy">ad copy</option>
          </select>
          <select value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} className="border rounded px-2 py-1 text-sm">
            {EDIT_REASONS.map((r) => <option key={r}>{r}</option>)}
          </select>
          <button onClick={addPair} className="px-3 py-1.5 rounded bg-indigo-600 text-white text-sm">Save pair</button>
          <span className="text-xs text-gray-400">FACTS edits are stored but not used as style examples.</span>
        </div>
      </section>

      <section className="bg-white rounded-lg border border-gray-200 p-4">
        <h2 className="text-sm font-semibold text-gray-800 mb-2">Correction pairs</h2>
        <div className="overflow-x-auto">
          <table className="text-xs w-full">
            <thead><tr className="text-left text-gray-500"><th className="pr-2">when</th><th className="pr-2">type</th><th className="pr-2">generated</th><th className="pr-2">approved</th><th className="pr-2">tags</th><th className="pr-2">reason</th><th>example</th></tr></thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} className="border-t border-gray-100 align-top">
                  <td className="pr-2 whitespace-nowrap">{c.created_at.slice(0, 16)}</td>
                  <td className="pr-2">{c.content_type ?? "—"}</td>
                  <td className="pr-2 max-w-[16rem] text-gray-500">{c.generated_text}</td>
                  <td className="pr-2 max-w-[16rem] text-gray-900">{c.approved_text}</td>
                  <td className="pr-2">{(c.delta_tags ?? []).join(", ")}</td>
                  <td className="pr-2">
                    <select value={c.edit_reason ?? ""} onChange={(e) => setReason(c, e.target.value)} className="border rounded px-1 text-xs">
                      <option value="">—</option>{EDIT_REASONS.map((r) => <option key={r}>{r}</option>)}
                    </select>
                  </td>
                  <td><input type="checkbox" checked={c.use_as_few_shot} onChange={() => toggle(c)} /></td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={7} className="text-gray-400 py-2">No corrections yet — edit a generated landing block or brief and it will appear here.</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      <section className="bg-white rounded-lg border border-gray-200 p-4">
        <h2 className="text-sm font-semibold text-gray-800 mb-2">Render feedback (feeds the montage-plan avoid list)</h2>
        <ul className="text-xs space-y-1">
          {renders.map((r) => (
            <li key={r.id} className="flex gap-2"><span className={r.decision === "APPROVED" ? "text-green-600" : "text-red-600"}>{r.decision}</span><span className="text-gray-500">{r.reason ?? ""}</span><span className="text-gray-700">{r.note ?? ""}</span><span className="text-gray-400">{r.created_at.slice(0, 16)}</span></li>
          ))}
          {renders.length === 0 && <li className="text-gray-400">No render feedback yet.</li>}
        </ul>
      </section>
    </div>
  );
}

function Card({ label, value, sub, good }: { label: string; value: string; sub?: string; good?: boolean }) {
  return (
    <div className="bg-white rounded-lg border border-gray-200 p-4">
      <div className="text-xs text-gray-500">{label}</div>
      <div className={`text-2xl font-bold ${good ? "text-green-600" : "text-gray-900"}`}>{value}</div>
      {sub && <div className="text-xs text-gray-400">{sub}</div>}
    </div>
  );
}
