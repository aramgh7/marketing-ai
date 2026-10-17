import StatCard from "@/components/admin/StatCard";
import StatusBadge from "@/components/admin/StatusBadge";

async function getCampaigns(): Promise<any[]> {
  const base =
    process.env.NEXT_PUBLIC_API_URL ?? "http://platform-api:8080";
  try {
    const res = await fetch(`${base}/api/v1/campaigns`, {
      cache: "no-store",
    });
    if (!res.ok) return [];
    return res.json();
  } catch {
    return [];
  }
}

export default async function DashboardPage() {
  const campaigns: any[] = await getCampaigns();

  const totalBudget = campaigns.reduce(
    (s: number, c: any) => s + (c.daily_budget ?? 0),
    0
  );
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
        {campaigns.length === 0 ? (
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
                    ${c.daily_budget}
                  </td>
                  <td className="px-6 py-4 text-gray-500">{c.objective}</td>
                  <td className="px-6 py-4 text-gray-400">
                    {new Date(c.created_at).toLocaleDateString()}
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
