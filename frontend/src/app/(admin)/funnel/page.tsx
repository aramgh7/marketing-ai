"use client";

import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";

interface FunnelStage {
  stage_key: string;
  stage_label: string;
  ordinal: number;
  is_quality_key: boolean;
  reward_weight: number;
  micro_reward_weight: number;
  capi_event: string | null;
}

interface WeeklyCounter {
  stage_key: string;
  count_this_week: number;
  count_prev_week: number;
  delta_pct: number | null;
}

interface ChecklistItem {
  _summary?: { ready_to_launch: boolean; passed: number; total: number };
  key?: string;
  label?: string;
  passed?: boolean;
  detail?: string;
}

export default function FunnelAdminPage() {
  const [stages, setStages] = useState<FunnelStage[]>([]);
  const [counters, setCounters] = useState<WeeklyCounter[]>([]);
  const [checklist, setChecklist] = useState<ChecklistItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      apiFetch<any>("/funnel/me"),
      apiFetch<any>("/reward/ladder"),
      apiFetch<any>("/onboarding/checklist"),
    ])
      .then(([funnel, ladder, cl]) => {
        setStages(funnel.stages || []);
        setCounters(ladder.weekly_counters || []);
        setChecklist(cl || []);
        setLoading(false);
      })
      .catch(e => {
        setError(e.message);
        setLoading(false);
      });
  }, []);

  if (loading) return <div className="p-8 text-gray-400">Loading funnel…</div>;
  if (error)   return <div className="p-8 text-red-500">Error: {error}</div>;

  const summary = checklist.find(i => i._summary)?._summary;
  const checkItems = checklist.filter(i => !i._summary);

  return (
    <div className="p-8 max-w-5xl mx-auto space-y-8">
      <h1 className="text-2xl font-bold">Funnel Configuration</h1>

      {/* Onboarding checklist */}
      {summary && (
        <div className={`rounded-lg p-4 border ${summary.ready_to_launch ? "bg-green-50 border-green-200" : "bg-yellow-50 border-yellow-200"}`}>
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-semibold text-sm uppercase tracking-wide text-gray-600">
              Onboarding Checklist
            </h2>
            <span className={`text-sm font-medium ${summary.ready_to_launch ? "text-green-700" : "text-yellow-700"}`}>
              {summary.passed}/{summary.total} ready
              {summary.ready_to_launch ? " ✓ Ready to launch" : ""}
            </span>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {checkItems.map(item => (
              <div key={item.key} className="flex items-start gap-2 text-sm">
                <span className={item.passed ? "text-green-500" : "text-red-400"}>
                  {item.passed ? "✓" : "✗"}
                </span>
                <div>
                  <span className="font-medium">{item.label}</span>
                  <span className="text-gray-400 ml-1">— {item.detail}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Funnel ladder */}
      <div className="bg-white rounded-lg shadow overflow-hidden">
        <div className="px-6 py-4 border-b bg-gray-50">
          <h2 className="font-semibold text-gray-700">Funnel Stages — Reward Ladder</h2>
          <p className="text-xs text-gray-400 mt-1">Green rows = quality gate stages (drive Thompson Sampling)</p>
        </div>
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">#</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Stage</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">CAPI Event</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase">Reward</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase">This Week</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase">Prev Week</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase">Δ%</th>
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-100">
            {stages.map(stage => {
              const counter = counters.find(c => c.stage_key === stage.stage_key);
              const delta = counter?.delta_pct;
              return (
                <tr key={stage.stage_key} className={stage.is_quality_key ? "bg-green-50" : ""}>
                  <td className="px-4 py-3 text-sm text-gray-400">{stage.ordinal}</td>
                  <td className="px-4 py-3">
                    <div className="font-medium text-gray-900 text-sm">
                      {stage.stage_label || stage.stage_key}
                      {stage.is_quality_key && (
                        <span className="ml-2 px-1.5 py-0.5 text-xs bg-green-100 text-green-700 rounded">
                          quality
                        </span>
                      )}
                    </div>
                    <div className="text-xs text-gray-400 font-mono">{stage.stage_key}</div>
                  </td>
                  <td className="px-4 py-3 text-sm text-gray-600 font-mono">
                    {stage.capi_event || <span className="text-gray-300">—</span>}
                  </td>
                  <td className="px-4 py-3 text-right text-sm">{stage.reward_weight}</td>
                  <td className="px-4 py-3 text-right text-sm font-mono">
                    {counter?.count_this_week ?? <span className="text-gray-300">—</span>}
                  </td>
                  <td className="px-4 py-3 text-right text-sm font-mono text-gray-400">
                    {counter?.count_prev_week ?? <span className="text-gray-300">—</span>}
                  </td>
                  <td className="px-4 py-3 text-right text-sm">
                    {delta != null ? (
                      <span className={delta >= 0 ? "text-green-600" : "text-red-500"}>
                        {delta >= 0 ? "+" : ""}{delta.toFixed(1)}%
                      </span>
                    ) : <span className="text-gray-300">—</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* CAPI event map summary */}
      {stages.some(s => s.capi_event) && (
        <div className="bg-gray-50 rounded-lg p-4">
          <h2 className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">CAPI Event Mapping</h2>
          <div className="flex flex-wrap gap-2">
            {stages.filter(s => s.capi_event).map(s => (
              <span key={s.stage_key} className="px-3 py-1 bg-white border border-gray-200 rounded text-xs">
                <span className="text-gray-400 font-mono">{s.stage_key}</span>
                <span className="mx-1 text-gray-300">→</span>
                <span className="font-medium">{s.capi_event}</span>
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
