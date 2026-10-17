"use client";
import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import StatusBadge from "@/components/admin/StatusBadge";
import { apiFetch } from "@/lib/api";

export default function BriefDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [brief, setBrief]     = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [approving, setApproving] = useState(false);

  useEffect(() => {
    apiFetch<any>(`/api/v1/briefs/${id}`)
      .then(setBrief)
      .catch(() => setError("Could not load brief"))
      .finally(() => setLoading(false));
  }, [id]);

  async function handleApprove() {
    setApproving(true);
    try {
      await apiFetch(`/api/v1/briefs/${id}/approve`, { method: "PUT" });
      setBrief((prev: any) => ({ ...prev, is_draft: false }));
    } catch (e: any) {
      setError(e.message ?? "Approval failed");
    } finally {
      setApproving(false);
    }
  }

  if (loading) return <p className="text-gray-400 text-sm p-8">Loading...</p>;
  if (!brief)
    return (
      <p className="text-red-500 text-sm p-8">{error ?? "Brief not found"}</p>
    );

  return (
    <div className="max-w-3xl space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">
            {brief.title ?? brief.goal ?? brief.id}
          </h1>
          <div className="flex items-center gap-3 mt-2">
            <StatusBadge
              status={brief.is_draft ? "DRAFT" : "APPROVED"}
            />
            {brief.brief_type && (
              <span className="text-xs text-gray-400">{brief.brief_type}</span>
            )}
            {brief.cluster_label && (
              <span className="text-xs bg-indigo-50 text-indigo-700 px-2 py-0.5 rounded">
                {brief.cluster_label}
              </span>
            )}
          </div>
        </div>
        {brief.is_draft && (
          <button
            onClick={handleApprove}
            disabled={approving}
            className="px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
          >
            {approving ? "Approving..." : "Approve Brief"}
          </button>
        )}
      </div>

      {brief.goal && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-2">Goal</h2>
          <p className="text-sm text-gray-700">{brief.goal}</p>
        </div>
      )}

      {brief.target_audience && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-2">Target Audience</h2>
          <p className="text-sm text-gray-700">{brief.target_audience}</p>
        </div>
      )}

      {brief.voc_section && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-2">VoC Insights</h2>
          <pre className="text-xs text-gray-600 whitespace-pre-wrap">
            {JSON.stringify(brief.voc_section, null, 2)}
          </pre>
        </div>
      )}

      {error && <p className="text-red-500 text-sm">{error}</p>}

      <button
        onClick={() => router.push("/briefs")}
        className="text-sm text-gray-500 hover:underline"
      >
        ← Back to Briefs
      </button>
    </div>
  );
}
