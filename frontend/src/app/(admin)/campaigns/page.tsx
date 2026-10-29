"use client";
import React, { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import StatusBadge from "@/components/admin/StatusBadge";

interface Campaign {
  id: string;
  name: string;
  status: string;
  objective: string;
  dailyBudget: number | null;
  explorationRate: number | null;
  maxSpendPerAdset: number | null;
  confirmationStatus: string | null;
  createdAt: string;
}

interface AdSet {
  id: string;
  name: string;
  gateStatus: string | null;
  impressionsTotal: number;
  clicksTotal: number;
  conversionsTotal: number;
  spendToday: number | null;
  spendTotal: number | null;
  isAnchor: boolean;
}

interface BatchRec {
  adSetId: string;
  eviScore: number;
  rank: number;
}

const OBJECTIVES = ["OUTCOME_LEADS", "OUTCOME_TRAFFIC", "OUTCOME_AWARENESS"];
const inp = "w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500";

function gateClass(g: string | null): string {
  if (!g) return "bg-gray-100 text-gray-600";
  if (g === "PASSED") return "bg-green-100 text-green-800";
  if (g === "KILLED") return "bg-red-100 text-red-800";
  if (g === "GATE_3") return "bg-indigo-100 text-indigo-700";
  if (g === "GATE_2") return "bg-blue-100 text-blue-700";
  return "bg-gray-100 text-gray-600";
}

export default function CampaignsPage() {
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // create form
  const [name, setName] = useState("");
  const [objective, setObjective] = useState("OUTCOME_LEADS");
  const [dailyBudget, setDailyBudget] = useState("");
  const [explorationRate, setExplorationRate] = useState(0.15);
  const [maxSpend, setMaxSpend] = useState("");
  const [creating, setCreating] = useState(false);

  // detail panel
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [adSets, setAdSets] = useState<AdSet[]>([]);
  const [adSetsLoading, setAdSetsLoading] = useState(false);
  const [batchRecs, setBatchRecs] = useState<BatchRec[] | null>(null);
  const [launching, setLaunching] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    apiFetch<Campaign[]>("/api/v1/campaigns")
      .then((rows) => setCampaigns(Array.isArray(rows) ? rows : []))
      .catch((e) => setError(e instanceof Error ? e.message : "Load failed"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  async function create(e: FormEvent) {
    e.preventDefault();
    setCreating(true);
    setError(null);
    try {
      await apiFetch("/api/v1/campaigns", {
        method: "POST",
        body: JSON.stringify({
          name: name.trim(),
          objective,
          dailyBudget: dailyBudget ? Number(dailyBudget) : null,
          explorationRate,
          maxSpendPerAdset: maxSpend ? Number(maxSpend) : null,
        }),
      });
      setName(""); setDailyBudget(""); setMaxSpend("");
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Create failed");
    } finally {
      setCreating(false);
    }
  }

  async function selectCampaign(id: string) {
    if (selectedId === id) { setSelectedId(null); return; }
    setSelectedId(id);
    setAdSets([]);
    setBatchRecs(null);
    setAdSetsLoading(true);
    try {
      const rows = await apiFetch<AdSet[]>(`/api/v1/ad-sets?campaignId=${id}`);
      setAdSets(Array.isArray(rows) ? rows : []);
    } catch {
      setAdSets([]);
    } finally {
      setAdSetsLoading(false);
    }
  }

  async function toggleStatus(c: Campaign) {
    const path = c.status === "ACTIVE" ? "pause" : "activate";
    setError(null);
    try {
      await apiFetch(`/api/v1/campaigns/${c.id}/${path}`, { method: "POST" });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Status update failed");
    }
  }

  async function recommendBatch(id: string) {
    setBatchRecs(null);
    try {
      const rec = await apiFetch<{ recommendations: BatchRec[] }>(`/api/v1/campaigns/${id}/recommend-batch`);
      setBatchRecs(rec.recommendations ?? []);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Recommend failed");
    }
  }

  async function launchBatch(id: string) {
    setLaunching(true);
    setError(null);
    try {
      await apiFetch(`/api/v1/campaigns/${id}/launch-batch`, {
        method: "POST",
        body: JSON.stringify({}),
      });
      setBatchRecs(null);
      selectCampaign(id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Launch failed");
    } finally {
      setLaunching(false);
    }
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Campaigns</h1>
        <p className="text-sm text-gray-500 mt-1">Two-contour Meta campaign management with staged gate evaluation.</p>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {/* Create form */}
      <form onSubmit={create} className="bg-white rounded-xl border border-gray-200 p-6 space-y-4 max-w-3xl">
        <h2 className="font-semibold text-gray-800">Create campaign</h2>
        <div className="grid grid-cols-2 gap-4">
          <label className="text-sm">
            <span className="text-gray-700">Campaign name</span>
            <input required value={name} onChange={(e) => setName(e.target.value)} className={inp} />
          </label>
          <label className="text-sm">
            <span className="text-gray-700">Objective</span>
            <select value={objective} onChange={(e) => setObjective(e.target.value)} className={inp}>
              {OBJECTIVES.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </label>
          <label className="text-sm">
            <span className="text-gray-700">Daily budget ($)</span>
            <input type="number" min="1" value={dailyBudget} onChange={(e) => setDailyBudget(e.target.value)} className={inp} placeholder="e.g. 50" />
          </label>
          <label className="text-sm">
            <span className="text-gray-700">Max spend per ad set ($)</span>
            <input type="number" min="1" value={maxSpend} onChange={(e) => setMaxSpend(e.target.value)} className={inp} placeholder="e.g. 200" />
          </label>
          <label className="text-sm col-span-2">
            <span className="text-gray-700">Exploration rate: <strong>{Math.round(explorationRate * 100)}%</strong></span>
            <input type="range" min="5" max="30" step="5" value={explorationRate * 100} onChange={(e) => setExplorationRate(Number(e.target.value) / 100)} className="w-full mt-1" />
            <div className="flex justify-between text-xs text-gray-400 mt-0.5"><span>5%</span><span>30%</span></div>
          </label>
        </div>
        <button type="submit" disabled={creating} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
          {creating ? "Creating…" : "Create campaign"}
        </button>
      </form>

      {/* Campaign list */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100">
          <h2 className="font-semibold text-gray-800">All campaigns</h2>
        </div>
        {loading ? (
          <p className="px-6 py-8 text-sm text-gray-400">Loading…</p>
        ) : campaigns.length === 0 ? (
          <p className="px-6 py-8 text-sm text-gray-400">No campaigns yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-6 py-3 text-left">Name</th>
                <th className="px-6 py-3 text-left">Status</th>
                <th className="px-6 py-3 text-left">Objective</th>
                <th className="px-6 py-3 text-right">Daily budget</th>
                <th className="px-6 py-3 text-left">Confirmation</th>
                <th className="px-6 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {campaigns.map((c) => (
                <React.Fragment key={c.id}>
                  <tr
                    className="border-t border-gray-100 cursor-pointer hover:bg-gray-50"
                    onClick={() => selectCampaign(c.id)}
                  >
                    <td className="px-6 py-3 font-medium text-gray-900">{c.name}</td>
                    <td className="px-6 py-3"><StatusBadge status={c.status} /></td>
                    <td className="px-6 py-3 text-gray-600">{c.objective}</td>
                    <td className="px-6 py-3 text-right text-gray-600">{c.dailyBudget != null ? `$${c.dailyBudget}` : "—"}</td>
                    <td className="px-6 py-3">
                      {c.confirmationStatus
                        ? <span className={`px-2 py-0.5 rounded text-xs font-medium ${c.confirmationStatus === "PASSED" ? "bg-green-100 text-green-800" : c.confirmationStatus === "FAILED" ? "bg-red-100 text-red-800" : "bg-gray-100 text-gray-600"}`}>{c.confirmationStatus}</span>
                        : <span className="text-gray-400">—</span>}
                    </td>
                    <td className="px-6 py-3 text-right" onClick={(e) => e.stopPropagation()}>
                      <button
                        onClick={() => toggleStatus(c)}
                        className={`text-xs font-medium rounded px-2 py-1 ${c.status === "ACTIVE" ? "bg-yellow-100 text-yellow-700 hover:bg-yellow-200" : "bg-green-100 text-green-700 hover:bg-green-200"}`}
                      >
                        {c.status === "ACTIVE" ? "Pause" : "Activate"}
                      </button>
                    </td>
                  </tr>
                  {selectedId === c.id && (
                    <tr key={`${c.id}-detail`} className="border-t border-indigo-100 bg-indigo-50">
                      <td colSpan={6} className="px-6 py-4">
                        <div className="space-y-3">
                          <div className="flex items-center gap-3">
                            <span className="text-sm font-semibold text-gray-700">Ad sets</span>
                            <button
                              onClick={() => recommendBatch(c.id)}
                              className="text-xs text-indigo-600 hover:underline"
                            >Recommend batch</button>
                            {batchRecs && (
                              <button
                                onClick={() => launchBatch(c.id)}
                                disabled={launching}
                                className="text-xs bg-indigo-600 text-white rounded px-2 py-1 hover:bg-indigo-700 disabled:opacity-50"
                              >{launching ? "Launching…" : "Launch batch"}</button>
                            )}
                          </div>
                          {batchRecs && batchRecs.length > 0 && (
                            <div className="text-xs text-gray-600 space-y-1">
                              <div className="font-medium">Recommended (by EVI score):</div>
                              {batchRecs.map((r) => (
                                <div key={r.adSetId}>#{r.rank} — ad set {r.adSetId.slice(0, 8)}… EVI: {r.eviScore.toFixed(4)}</div>
                              ))}
                            </div>
                          )}
                          {adSetsLoading ? (
                            <p className="text-xs text-gray-400">Loading ad sets…</p>
                          ) : adSets.length === 0 ? (
                            <p className="text-xs text-gray-400">No ad sets for this campaign.</p>
                          ) : (
                            <table className="w-full text-xs bg-white rounded-lg border border-gray-200">
                              <thead className="bg-gray-50 text-gray-500">
                                <tr>
                                  <th className="px-4 py-2 text-left">Name</th>
                                  <th className="px-4 py-2 text-left">Gate</th>
                                  <th className="px-4 py-2 text-right">Impressions</th>
                                  <th className="px-4 py-2 text-right">Clicks</th>
                                  <th className="px-4 py-2 text-right">Conversions</th>
                                  <th className="px-4 py-2 text-right">Spend</th>
                                </tr>
                              </thead>
                              <tbody>
                                {adSets.map((s) => (
                                  <tr key={s.id} className="border-t border-gray-100">
                                    <td className="px-4 py-2 font-medium text-gray-800">
                                      {s.name}
                                      {s.isAnchor && <span className="ml-1 text-indigo-500 text-xs">anchor</span>}
                                    </td>
                                    <td className="px-4 py-2">
                                      {s.gateStatus ? (
                                        <span className={`px-1.5 py-0.5 rounded text-xs font-medium ${gateClass(s.gateStatus)}`}>{s.gateStatus}</span>
                                      ) : "—"}
                                    </td>
                                    <td className="px-4 py-2 text-right">{s.impressionsTotal ?? 0}</td>
                                    <td className="px-4 py-2 text-right">{s.clicksTotal ?? 0}</td>
                                    <td className="px-4 py-2 text-right">{s.conversionsTotal ?? 0}</td>
                                    <td className="px-4 py-2 text-right">{s.spendTotal != null ? `$${s.spendTotal.toFixed(2)}` : "—"}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          )}
                        </div>
                      </td>
                    </tr>
                  )}
                </React.Fragment>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
