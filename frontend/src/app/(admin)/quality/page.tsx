"use client";
import { useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";

/** GET /quality-score?by=creative|variant&days= contract (roadmap 9-5; arch §15.5). */
interface QualityRow {
  id: string;
  name: string | null;
  quality_score: number | null;
  n_sessions: number;
  bounce_rate: number | null;
  form_completion: number | null;
  match_score: number | null;
  show_rate: number | null;
  clickbait_flag: boolean;
}

const pct = (v: number | null | undefined) => (v == null ? "—" : `${(Number(v) * 100).toFixed(0)}%`);
const num = (v: number | null | undefined) => (v == null ? "—" : Number(v).toFixed(2));

function scoreClass(s: number | null): string {
  if (s == null) return "text-gray-400";
  if (s >= 0.7) return "text-green-700";
  if (s >= 0.4) return "text-yellow-700";
  return "text-red-700";
}

export default function QualityPage() {
  const [by, setBy] = useState<"creative" | "variant">("creative");
  const [days, setDays] = useState(30);
  const [rows, setRows] = useState<QualityRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiFetch<QualityRow[]>(`/api/v1/quality-score?by=${by}&days=${days}`);
      setRows(Array.isArray(data) ? data : []);
    } catch (err) {
      setRows([]);
      setError(err instanceof ApiError ? err.message : "Load failed");
    } finally {
      setLoading(false);
    }
  }, [by, days]);

  useEffect(() => {
    load();
  }, [load]);

  const flagged = rows.filter((r) => r.clickbait_flag).length;

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Quality Score</h1>
        <p className="text-sm text-gray-500 mt-1">
          f(match score, bounce, form completion, show rate) per {by}; the clickbait rule flags high CTR with collapsing downstream quality vs the anchor (vision §12.2).
        </p>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="flex items-center gap-4">
        <div className="inline-flex rounded-lg border border-gray-200 overflow-hidden text-sm">
          {(["creative", "variant"] as const).map((b) => (
            <button
              key={b}
              type="button"
              onClick={() => setBy(b)}
              className={`px-4 py-2 ${by === b ? "bg-indigo-600 text-white" : "bg-white text-gray-600 hover:bg-gray-50"}`}
            >
              by {b}
            </button>
          ))}
        </div>
        <label className="text-sm flex items-center gap-2">
          <span className="text-gray-700">Days</span>
          <input
            type="number"
            min={1}
            max={365}
            value={days}
            onChange={(e) => setDays(Math.max(1, Math.min(365, Number(e.target.value) || 30)))}
            className="w-20 border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </label>
        <span className="text-xs text-gray-500">{rows.length} rows · {flagged} flagged as clickbait</span>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        {loading ? (
          <p className="px-6 py-8 text-gray-400 text-sm">Loading…</p>
        ) : rows.length === 0 ? (
          <p className="px-6 py-8 text-gray-400 text-sm">No quality scores for this window yet (computed every 15 min from closed sessions).</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-6 py-3 text-left">{by}</th>
                <th className="px-6 py-3 text-right">Quality</th>
                <th className="px-6 py-3 text-right">Sessions</th>
                <th className="px-6 py-3 text-right">Bounce</th>
                <th className="px-6 py-3 text-right">Form completion</th>
                <th className="px-6 py-3 text-right">Match</th>
                <th className="px-6 py-3 text-right">Show rate</th>
                <th className="px-6 py-3 text-left">Clickbait</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="border-t border-gray-100">
                  <td className="px-6 py-3">
                    <div className="font-medium text-gray-800">{r.name || "—"}</div>
                    <div className="text-xs text-gray-400 font-mono">{r.id}</div>
                  </td>
                  <td className={`px-6 py-3 text-right font-semibold ${scoreClass(r.quality_score)}`}>{num(r.quality_score)}</td>
                  <td className="px-6 py-3 text-right">{r.n_sessions}</td>
                  <td className="px-6 py-3 text-right">{pct(r.bounce_rate)}</td>
                  <td className="px-6 py-3 text-right">{pct(r.form_completion)}</td>
                  <td className="px-6 py-3 text-right">{num(r.match_score)}</td>
                  <td className="px-6 py-3 text-right">{pct(r.show_rate)}</td>
                  <td className="px-6 py-3">
                    {r.clickbait_flag ? (
                      <span className="inline-block px-2 py-0.5 rounded text-xs font-medium bg-red-100 text-red-800">flagged</span>
                    ) : (
                      <span className="text-gray-400">—</span>
                    )}
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
