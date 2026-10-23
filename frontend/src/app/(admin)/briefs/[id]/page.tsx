"use client";
import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import StatusBadge from "@/components/admin/StatusBadge";
import LoadError from "@/components/admin/LoadError";
import { ApiError, apiFetch } from "@/lib/api";

/**
 * Audit F-4: mirrors `dto/brief/BriefResponse` (camelCase). Status is `DRAFT` until approved
 * (`PUT /briefs/{id}/approve` → `APPROVED`); V15 also knows `FINAL`.
 */
interface Atom { atomType?: string; atomValue?: string; cvr?: number | null; creativeCount?: number | null; rationale?: string | null }
interface Brief {
  id: string;
  tenantId?: string;
  title?: string | null;
  creativeType?: string | null;
  targetAudience?: string | null;
  productInfo?: string | null;
  recommendedAtoms?: Atom[] | null;
  avoidAtoms?: Atom[] | null;
  structureTemplate?: string | null;
  benchmarks?: Record<string, unknown> | null;
  notes?: string | null;
  status?: string | null;
  version?: number;
  briefType?: string | null;
  pairedBriefId?: string | null;
  resultCreativeId?: string | null;
  markdownExport?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

const pct = (v: number | null | undefined) => (v == null ? "—" : `${(Number(v) * 100).toFixed(2)}%`);

function AtomList({ atoms, tone }: { atoms: Atom[]; tone: "good" | "bad" }) {
  return (
    <ul className="space-y-2">
      {atoms.map((a, i) => (
        <li key={i} className="text-sm text-gray-700 flex flex-wrap items-baseline gap-x-2">
          <span className={`text-xs px-1.5 py-0.5 rounded ${tone === "good" ? "bg-green-50 text-green-700" : "bg-red-50 text-red-700"}`}>
            {a.atomType ?? "atom"}
          </span>
          <span className="font-medium">{a.atomValue ?? "—"}</span>
          <span className="text-xs text-gray-400">
            CVR {pct(a.cvr)}{a.creativeCount != null ? ` · ${a.creativeCount} creatives` : ""}
          </span>
          {a.rationale && <span className="w-full text-xs text-gray-500">{a.rationale}</span>}
        </li>
      ))}
    </ul>
  );
}

export default function BriefDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [brief, setBrief]     = useState<Brief | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError]     = useState<string | null>(null);
  const [approving, setApproving] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    apiFetch<Brief>(`/api/v1/briefs/${id}`)
      .then(setBrief)
      .catch((e: unknown) => setLoadError(e instanceof ApiError ? e.message : "Could not load brief"))
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => { load(); }, [load]);

  async function handleApprove() {
    setApproving(true);
    setError(null);
    try {
      const updated = await apiFetch<Brief>(`/api/v1/briefs/${id}/approve`, { method: "PUT" });
      setBrief((prev) => ({ ...(prev ?? { id }), ...(updated ?? {}), status: updated?.status ?? "APPROVED" }));
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "Approval failed");
    } finally {
      setApproving(false);
    }
  }

  if (loading) return <p className="text-gray-400 text-sm p-8">Loading...</p>;
  if (loadError || !brief) {
    return (
      <div className="max-w-3xl p-8 space-y-4">
        <LoadError title="Could not load brief" message={loadError ?? "Brief not found"} onRetry={load} />
        <button onClick={() => router.push("/briefs")} className="text-sm text-gray-500 hover:underline">← Back to Briefs</button>
      </div>
    );
  }

  const status = (brief.status ?? "DRAFT").toUpperCase();
  const isDraft = status === "DRAFT";
  const recommended = Array.isArray(brief.recommendedAtoms) ? brief.recommendedAtoms : [];
  const avoid = Array.isArray(brief.avoidAtoms) ? brief.avoidAtoms : [];
  const benchmarks = brief.benchmarks && typeof brief.benchmarks === "object" ? brief.benchmarks : null;

  return (
    <div className="max-w-3xl space-y-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{brief.title ?? brief.id}</h1>
          <div className="flex flex-wrap items-center gap-3 mt-2">
            <StatusBadge status={status} />
            {brief.briefType && <span className="text-xs text-gray-400">{brief.briefType}</span>}
            {brief.creativeType && (
              <span className="text-xs bg-indigo-50 text-indigo-700 px-2 py-0.5 rounded">{brief.creativeType}</span>
            )}
            {brief.version != null && <span className="text-xs text-gray-400">v{brief.version}</span>}
            {brief.createdAt && (
              <span className="text-xs text-gray-400">{new Date(brief.createdAt).toLocaleDateString()}</span>
            )}
          </div>
        </div>
        {isDraft && (
          <button
            onClick={handleApprove}
            disabled={approving}
            className="px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
          >
            {approving ? "Approving..." : "Approve Brief"}
          </button>
        )}
      </div>

      {error && <p className="text-red-600 text-sm rounded bg-red-50 border border-red-200 p-3">{error}</p>}

      {brief.productInfo && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-2">Product</h2>
          <p className="text-sm text-gray-700 whitespace-pre-wrap">{brief.productInfo}</p>
        </div>
      )}

      {brief.targetAudience && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-2">Target Audience</h2>
          <p className="text-sm text-gray-700 whitespace-pre-wrap">{brief.targetAudience}</p>
        </div>
      )}

      {recommended.length > 0 && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-3">Recommended atoms</h2>
          <AtomList atoms={recommended} tone="good" />
        </div>
      )}

      {avoid.length > 0 && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-3">Atoms to avoid</h2>
          <AtomList atoms={avoid} tone="bad" />
        </div>
      )}

      {brief.structureTemplate && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-2">Structure</h2>
          <pre className="text-xs text-gray-600 whitespace-pre-wrap">{brief.structureTemplate}</pre>
        </div>
      )}

      {benchmarks && Object.keys(benchmarks).length > 0 && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-2">Benchmarks</h2>
          <dl className="grid grid-cols-2 gap-3">
            {Object.entries(benchmarks).map(([k, v]) => (
              <div key={k}>
                <dt className="text-xs text-gray-400 uppercase tracking-wide">{k}</dt>
                <dd className="mt-0.5 text-sm text-gray-700 break-words">
                  {typeof v === "object" ? JSON.stringify(v) : String(v)}
                </dd>
              </div>
            ))}
          </dl>
        </div>
      )}

      {brief.notes && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-2">Notes</h2>
          <p className="text-sm text-gray-700 whitespace-pre-wrap">{brief.notes}</p>
        </div>
      )}

      {(brief.pairedBriefId || brief.resultCreativeId) && (
        <div className="bg-white rounded-xl border border-gray-200 p-6 text-sm text-gray-700 space-y-1">
          {brief.pairedBriefId && (
            <p>
              Paired brief:{" "}
              <button onClick={() => router.push(`/briefs/${brief.pairedBriefId}`)} className="text-indigo-600 hover:underline font-mono text-xs">
                {brief.pairedBriefId}
              </button>
            </p>
          )}
          {brief.resultCreativeId && (
            <p>Result creative: <span className="font-mono text-xs">{brief.resultCreativeId}</span></p>
          )}
        </div>
      )}

      {brief.markdownExport && (
        <details className="bg-white rounded-xl border border-gray-200 p-6">
          <summary className="font-semibold text-gray-800 cursor-pointer">Markdown export</summary>
          <pre className="mt-3 text-xs text-gray-600 whitespace-pre-wrap">{brief.markdownExport}</pre>
        </details>
      )}

      <button
        onClick={() => router.push("/briefs")}
        className="text-sm text-gray-500 hover:underline"
      >
        ← Back to Briefs
      </button>
    </div>
  );
}
