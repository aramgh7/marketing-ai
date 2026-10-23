"use client";
import { useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import LoadError from "@/components/admin/LoadError";

/** Roadmap Phase 11-5: the pair "creative → landing variant" is what the marketer compares. */
type Pair = {
  creative_id: string | null; creative_title: string | null; creative_code: string | null;
  variant_id: string; variant_name: string | null; decisions: number; rewards: number; cvr: number | null;
  alpha: number; beta: number; posterior_mean: number; win_probability: number | null;
};

export default function AbResultsPage() {
  const [arms, setArms]       = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [armsError, setArmsError] = useState<string | null>(null);
  const [names, setNames]     = useState<Record<string, string>>({});
  const [pairs, setPairs]     = useState<Pair[]>([]);
  const [pairsDays, setPairsDays] = useState(30);
  const [pairsError, setPairsError] = useState("");
  const [pairsTick, setPairsTick] = useState(0); // bumped by Retry

  const loadArms = useCallback(() => {
    setLoading(true);
    setArmsError(null);
    Promise.all([
      apiFetch<any[]>("/api/v1/landings/arms"),
      // Audit F-22: /landings/arms returns variant_name = UUID; join the names from /landings here.
      apiFetch<{ id: string; name?: string | null; slug?: string | null }[]>("/api/v1/landings").catch(() => []),
    ])
      .then(([rows, variants]) => {
        setArms(Array.isArray(rows) ? rows : []);
        const map: Record<string, string> = {};
        for (const v of variants ?? []) if (v?.id) map[v.id] = v.name || v.slug || v.id;
        setNames(map);
      })
      .catch((e: unknown) => setArmsError(e instanceof ApiError ? e.message : "Could not load arms"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { loadArms(); }, [loadArms]);

  /** A readable variant name: the joined landing name, else the API's value unless it is just the UUID. */
  const variantLabel = (id: string, apiName: string | null | undefined) =>
    names[id] ?? (apiName && apiName !== id ? apiName : id);

  useEffect(() => {
    let cancelled = false;
    setPairsError("");
    apiFetch<{ pairs: Pair[] }>(`/api/v1/landings/pairs?days=${pairsDays}`)
      .then((r) => { if (!cancelled) setPairs(r?.pairs ?? []); })
      .catch((e: any) => { if (!cancelled) { setPairs([]); setPairsError(e?.message ?? "pairs unavailable"); } });
    return () => { cancelled = true; };
  }, [pairsDays, pairsTick]);

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
      ) : armsError ? (
        <LoadError title="Could not load A/B arms" message={armsError} onRetry={loadArms} />
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
                      {variantLabel(arm.variant_id, arm.variant_name)}
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
        {pairsError && <LoadError title="Could not load pairs" message={pairsError} onRetry={() => setPairsTick((t) => t + 1)} />}
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
                    <td className="pr-2 py-1 text-gray-900">{variantLabel(p.variant_id, p.variant_name)}</td>
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
