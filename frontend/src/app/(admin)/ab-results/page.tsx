"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";

export default function AbResultsPage() {
  const [arms, setArms]       = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch<any[]>("/api/v1/landings/arms")
      .then(setArms)
      .catch(() => setArms([]))
      .finally(() => setLoading(false));
  }, []);

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
    </div>
  );
}
