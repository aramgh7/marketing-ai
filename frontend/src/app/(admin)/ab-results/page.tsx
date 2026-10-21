"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";

/** Roadmap Phase 11-5: the pair "creative → landing variant" is what the marketer compares. */
type Pair = {
  creative_id: string | null; creative_title: string | null; creative_code: string | null;
  variant_id: string; variant_name: string | null; decisions: number; rewards: number; cvr: number | null;
  alpha: number; beta: number; posterior_mean: number; win_probability: number | null;
};

export default function AbResultsPage() {
  const [arms, setArms]       = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [pairs, setPairs]     = useState<Pair[]>([]);
  const [pairsDays, setPairsDays] = useState(30);
  const [pairsError, setPairsError] = useState("");

  useEffect(() => {
    apiFetch<any[]>("/api/v1/landings/arms")
      .then(setArms)
      .catch(() => setArms([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    let cancelled = false;
    setPairsError("");
    apiFetch<{ pairs: Pair[] }>(`/api/v1/landings/pairs?days=${pairsDays}`)
      .then((r) => { if (!cancelled) setPairs(r?.pairs ?? []); })
      .catch((e: any) => { if (!cancelled) { setPairs([]); setPairsError(e?.message ?? "pairs unavailable"); } });
    return () => { cancelled = true; };
  }, [pairsDays]);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">A/B Results</h1>
        <p className="text-sm text-gray-500 mt-1">
          Bayesian posterior distributions (Thompson Sampling)
        </p>
      </div>

      {loading ? (
        <p className="text-gray-400 text-sm">Loading...</p>
      ) : arms.length === 0 ? (
        <p className="text-gray-400 text-sm py-8">No active A/B tests yet.</p>
      ) : (
        <div className="space-y-4">
          {arms.map((arm: any) => {
            const alpha = arm.alpha ?? 1;
            const beta  = arm.beta  ?? 1;
            const mean  = alpha / (alpha + beta);
            const n     = Math.max(0, alpha + beta - 2);

            return (
              <div
                key={arm.variant_id}
                className="bg-white rounded-xl border border-gray-200 p-6"
              >
                <div className="flex items-center justify-between mb-4">
                  <div>
                    <h3 className="font-semibold text-gray-800">
                      {arm.variant_name ?? arm.variant_id}
                    </h3>
                    <p className="text-xs text-gray-400">
                      α={alpha.toFixed(1)} β={beta.toFixed(1)} · n≈{n}
                    </p>
                  </div>
                  <div className="text-right">
                    <div className="text-2xl font-bold text-indigo-600">
                      {(mean * 100).toFixed(2)}%
                    </div>
                    <div className="text-xs text-gray-400">
                      posterior mean CVR
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-2 text-xs text-gray-500">
                  <span>0%</span>
                  <div className="flex-1 bg-gray-100 rounded-full h-3 overflow-hidden">
                    <div
                      className="h-3 rounded-full bg-indigo-500 transition-all"
                      style={{
                        width: `${Math.min(mean * 100 * 5, 100)}%`,
                      }}
                    />
                  </div>
                  <span>20%</span>
                </div>

                {arm.win_probability != null && (
                  <div className="mt-3 flex items-center gap-2">
                    <span className="text-xs text-gray-500">P(best):</span>
                    <div className="flex-1 bg-gray-100 rounded-full h-2">
                      <div
                        className={`h-2 rounded-full transition-all ${
                          arm.win_probability > 0.9
                            ? "bg-green-500"
                            : "bg-indigo-400"
                        }`}
                        style={{ width: `${arm.win_probability * 100}%` }}
                      />
                    </div>
                    <span
                      className={`text-xs font-medium ${
                        arm.win_probability > 0.9
                          ? "text-green-600"
                          : "text-gray-600"
                      }`}
                    >
                      {(arm.win_probability * 100).toFixed(1)}%
                      {arm.win_probability > 0.9 && " ✓ WINNER"}
                    </span>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* ── Creative → landing pairs (Phase 11-5) ───────────────────────── */}
      <section className="bg-white rounded-xl border border-gray-200 p-6">
        <div className="flex flex-wrap items-center justify-between gap-2 mb-3">
          <div>
            <h2 className="font-semibold text-gray-800">Creative → landing pairs (last {pairsDays} days)</h2>
            <p className="text-xs text-gray-400">Which creative with which landing converts. Organic / mail visits are the &quot;(no creative)&quot; row.</p>
          </div>
          <div className="flex gap-1">
            {[7, 30, 90].map((d) => (
              <button key={d} onClick={() => setPairsDays(d)}
                className={`px-2 py-1 rounded text-xs ${pairsDays === d ? "bg-indigo-600 text-white" : "bg-white border border-gray-200 text-gray-600"}`}>
                {d}d
              </button>
            ))}
          </div>
        </div>
        {pairsError && <p className="text-xs text-red-600 mb-2">{pairsError}</p>}
        <div className="overflow-x-auto">
          <table className="text-xs w-full">
            <thead>
              <tr className="text-left text-gray-500">
                <th className="pr-2">creative</th><th className="pr-2">landing</th><th className="pr-2 text-right">decisions</th>
                <th className="pr-2 text-right">rewards</th><th className="pr-2 text-right">CVR</th><th className="pr-2 text-right">posterior</th><th>P(best)</th>
              </tr>
            </thead>
            <tbody>
              {pairs.map((p) => {
                const cvr = p.cvr ?? (p.decisions ? p.rewards / p.decisions : 0);
                const win = p.win_probability ?? 0;
                return (
                  <tr key={`${p.creative_id ?? "none"}-${p.variant_id}`} className="border-t border-gray-100">
                    <td className="pr-2 py-1">
                      {p.creative_id ? (
                        <span><span className="font-mono text-gray-500">{p.creative_code ?? ""}</span> <span className="text-gray-900">{p.creative_title ?? p.creative_id}</span></span>
                      ) : <span className="text-gray-400">(no creative)</span>}
                    </td>
                    <td className="pr-2 py-1 text-gray-900">{p.variant_name ?? p.variant_id}</td>
                    <td className="pr-2 py-1 text-right">{p.decisions}</td>
                    <td className="pr-2 py-1 text-right">{p.rewards}</td>
                    <td className="pr-2 py-1 text-right">{(cvr * 100).toFixed(2)}%</td>
                    <td className="pr-2 py-1 text-right">{(p.posterior_mean * 100).toFixed(2)}%</td>
                    <td className="py-1 min-w-[8rem]">
                      <div className="flex items-center gap-2">
                        <div className="flex-1 bg-gray-100 rounded-full h-2">
                          <div className={`h-2 rounded-full ${win > 0.9 ? "bg-green-500" : "bg-indigo-400"}`} style={{ width: `${Math.min(100, win * 100)}%` }} />
                        </div>
                        <span className={`${win > 0.9 ? "text-green-600 font-medium" : "text-gray-600"}`}>{(win * 100).toFixed(1)}%</span>
                      </div>
                    </td>
                  </tr>
                );
              })}
              {pairs.length === 0 && !pairsError && (
                <tr><td colSpan={7} className="text-gray-400 py-2">No pair data yet — pairs appear once ads published from the Meta ads page bring clicks with the creative id.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
