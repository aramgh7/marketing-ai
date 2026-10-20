"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import StatusBadge from "@/components/admin/StatusBadge";
import { apiFetch } from "@/lib/api";

export default function BriefsPage() {
  const [briefs, setBriefs]   = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiFetch<any[]>("/api/v1/briefs")
      .then(setBriefs)
      .catch(() => setBriefs([]))
      .finally(() => setLoading(false));
  }, []);

  async function approve(id: string) {
    try {
      await apiFetch(`/api/v1/briefs/${id}/approve`, { method: "PUT" });
      setBriefs((prev) =>
        prev.map((b) =>
          b.id === id ? { ...b, status: "APPROVED" } : b
        )
      );
    } catch {
      alert("Approval failed");
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Briefs</h1>
          <p className="text-sm text-gray-500 mt-1">
            Creative briefs for video and copy generation
          </p>
        </div>
        <Link
          href="/briefs/generate"
          className="px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700"
        >
          + Generate Data-Driven Brief
        </Link>
      </div>

      {loading ? (
        <p className="text-gray-400 text-sm">Loading...</p>
      ) : briefs.length === 0 ? (
        <p className="text-gray-400 text-sm py-8">No briefs yet.</p>
      ) : (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-6 py-3 text-left">Brief</th>
                <th className="px-6 py-3 text-left">Type</th>
                <th className="px-6 py-3 text-left">Status</th>
                <th className="px-6 py-3 text-left">Cluster</th>
                <th className="px-6 py-3 text-left">Created</th>
                <th className="px-6 py-3 text-left">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {briefs.map((b: any) => (
                <tr key={b.id} className="hover:bg-gray-50">
                  <td className="px-6 py-4">
                    <div className="font-medium text-gray-900 max-w-xs truncate">
                      {b.title ?? b.goal ?? b.id}
                    </div>
                  </td>
                  <td className="px-6 py-4 text-gray-500">
                    {b.briefType ?? b.brief_type ?? "VIDEO"}
                  </td>
                  <td className="px-6 py-4">
                    <StatusBadge
                      status={
                        b.status ?? "DRAFT"
                      }
                    />
                  </td>
                  <td className="px-6 py-4 text-gray-400 text-xs">
                    {b.clusterLabel ?? b.cluster_label ?? "—"}
                  </td>
                  <td className="px-6 py-4 text-gray-400">
                    {(b.createdAt ?? b.created_at) ? new Date(b.createdAt ?? b.created_at).toLocaleDateString() : "—"}
                  </td>
                  <td className="px-6 py-4 flex gap-3">
                    {(b.status ?? "DRAFT") === "DRAFT" && (
                      <button
                        onClick={() => approve(b.id)}
                        className="text-indigo-600 hover:underline text-xs font-medium"
                      >
                        Approve
                      </button>
                    )}
                    <Link
                      href={`/briefs/${b.id}`}
                      className="text-gray-500 hover:underline text-xs"
                    >
                      View
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
