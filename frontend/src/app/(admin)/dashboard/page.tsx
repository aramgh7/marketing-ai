"use client";
import { useEffect, useState } from "react";
import StatCard from "@/components/admin/StatCard";
import StatusBadge from "@/components/admin/StatusBadge";
import { apiFetch } from "@/lib/api";

// Audit 7.6: this was a server component fetching NEXT_PUBLIC_API_URL from inside the container
// with no Authorization header, so it always rendered empty. Campaigns are tenant data — load
// them client-side with the user's token like every other admin page.
export default function DashboardPage() {
  const [campaigns, setCampaigns] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch<any[]>("/campaigns")
      .then((rows) => setCampaigns(Array.isArray(rows) ? rows : []))
      .catch(() => setCampaigns([]))
      .finally(() => setLoading(false));
  }, []);

  const budgetOf = (c: any) => Number(c.dailyBudget ?? c.daily_budget ?? 0);
  const createdOf = (c: any) => c.createdAt ?? c.created_at;

  const totalBudget = campaigns.reduce((s: number, c: any) => s + budgetOf(c), 0);
  const active = campaigns.filter((c: any) => c.status === "ACTIVE").length;
  const paused = campaigns.filter((c: any) => c.status === "PAUSED").length;

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
        <p className="text-sm text-gray-500 mt-1">Campaign overview</p>
      </div>

      <div className="grid grid-cols-4 gap-4">
        <StatCard label="Total Campaigns" value={campaigns.length} color="blue" />
        <StatCard label="Active"          value={active}           color="green" />
        <StatCard label="Paused"          value={paused}           color="yellow" />
        <StatCard
          label="Daily Budget"
          value={`$${totalBudget.toFixed(0)}`}
          color="gray"
        />
      </div>

      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100">
          <h2 className="font-semibold text-gray-800">Campaigns</h2>
        </div>
        {loading ? (
          <p className="px-6 py-8 text-gray-400 text-sm">Loading…</p>
        ) : campaigns.length === 0 ? (
          <p className="px-6 py-8 text-gray-400 text-sm">
            No campaigns yet. Connect Meta account to get started.
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-6 py-3 text-left">Name</th>
                <th className="px-6 py-3 text-left">Status</th>
                <th className="px-6 py-3 text-left">Budget</th>
                <th className="px-6 py-3 text-left">Objective</th>
                <th className="px-6 py-3 text-left">Created</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {campaigns.map((c: any) => (
                <tr key={c.id} className="hover:bg-gray-50">
                  <td className="px-6 py-4 font-medium text-gray-900">
                    {c.name}
                  </td>
                  <td className="px-6 py-4">
                    <StatusBadge status={c.status} />
                  </td>
                  <td className="px-6 py-4 text-gray-600">
                    ${budgetOf(c)}
                  </td>
                  <td className="px-6 py-4 text-gray-500">{c.objective}</td>
                  <td className="px-6 py-4 text-gray-400">
                    {createdOf(c) ? new Date(createdOf(c)).toLocaleDateString() : "—"}
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
