"use client";
import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import StatusBadge from "@/components/admin/StatusBadge";
import { apiFetch } from "@/lib/api";

const COMPLIANCE_ITEMS = [
  { id: "ip",       label: "No trademark / IP violations in content" },
  { id: "faces",    label: "No identifiable real faces without consent" },
  { id: "claims",   label: "All product claims are factually verifiable" },
  { id: "ai_label", label: "AI-generated label present (C2PA / disclosed)" },
  { id: "offer",    label: "Offer terms are legally sound and validated" },
  { id: "tone",     label: "Content matches brand tone-of-voice" },
];

export default function VariantDetailPage() {
  const { id }   = useParams<{ id: string }>();
  const router   = useRouter();
  const [variant, setVariant]   = useState<any>(null);
  const [checks, setChecks]     = useState<Record<string, boolean>>({});
  const [loading, setLoading]   = useState(true);
  const [approving, setApproving] = useState(false);
  const [error, setError]       = useState<string | null>(null);

  useEffect(() => {
    apiFetch<any>(`/api/v1/landings/${id}`)
      .then(setVariant)
      .catch(() => setError("Could not load variant"))
      .finally(() => setLoading(false));
  }, [id]);

  const allChecked = COMPLIANCE_ITEMS.every((item) => checks[item.id]);

  async function handleApprove() {
    setApproving(true);
    setError(null);
    try {
      await apiFetch(`/api/v1/landings/${id}/approve`, { method: "POST" });
      router.push("/creatives");
    } catch (e: any) {
      setError(e.message ?? "Approval failed");
    } finally {
      setApproving(false);
    }
  }

  if (loading) return <p className="text-gray-400 text-sm p-8">Loading...</p>;
  if (!variant)
    return (
      <p className="text-red-500 text-sm p-8">{error ?? "Variant not found"}</p>
    );

  const isAlreadyActive = variant.status === "ACTIVE";

  return (
    <div className="max-w-3xl space-y-8">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">
            {variant.name ?? variant.id}
          </h1>
          <div className="flex items-center gap-3 mt-2">
            <StatusBadge status={variant.status} />
            <span className="text-xs text-gray-400">
              {variant.placement ?? "Unknown placement"}
            </span>
          </div>
        </div>
        {variant.slug && (
          <a
            href={`/l/${variant.slug}`}
            target="_blank"
            rel="noopener noreferrer"
            className="text-sm text-indigo-600 hover:underline"
          >
            Preview landing ↗
          </a>
        )}
      </div>

      {variant.atoms && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h2 className="font-semibold text-gray-800 mb-4">Creative Atoms</h2>
          <dl className="grid grid-cols-2 gap-4">
            {Object.entries(variant.atoms).map(([k, v]) => (
              <div key={k}>
                <dt className="text-xs text-gray-400 uppercase tracking-wide">
                  {k}
                </dt>
                <dd className="mt-1 text-sm text-gray-700">{String(v)}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}

      {/* Compliance checklist — vision §12.1a */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h2 className="font-semibold text-gray-800 mb-1">
          Compliance Checklist
        </h2>
        <p className="text-xs text-gray-400 mb-4">
          All items must be confirmed before approval (vision §12.1a)
        </p>
        <ul className="space-y-3">
          {COMPLIANCE_ITEMS.map((item) => (
            <li key={item.id} className="flex items-center gap-3">
              <input
                type="checkbox"
                id={item.id}
                checked={!!checks[item.id]}
                onChange={(e) =>
                  setChecks((prev) => ({
                    ...prev,
                    [item.id]: e.target.checked,
                  }))
                }
                className="w-4 h-4 rounded border-gray-300 text-indigo-600"
              />
              <label htmlFor={item.id} className="text-sm text-gray-700">
                {item.label}
              </label>
            </li>
          ))}
        </ul>
      </div>

      {error && <p className="text-red-500 text-sm">{error}</p>}

      <div className="flex gap-3 items-center">
        <button
          onClick={handleApprove}
          disabled={!allChecked || approving || isAlreadyActive}
          className={`px-6 py-2 rounded-lg text-sm font-medium transition-colors ${
            allChecked && !isAlreadyActive
              ? "bg-indigo-600 text-white hover:bg-indigo-700"
              : "bg-gray-200 text-gray-400 cursor-not-allowed"
          }`}
        >
          {approving
            ? "Approving..."
            : isAlreadyActive
            ? "Already Active"
            : "Approve & Launch"}
        </button>
        <button
          onClick={() => router.push("/creatives")}
          className="px-6 py-2 rounded-lg text-sm font-medium text-gray-600 border border-gray-200 hover:bg-gray-50"
        >
          Back
        </button>
        {!allChecked && !isAlreadyActive && (
          <p className="text-xs text-amber-600">
            Complete all compliance checks to enable approval
          </p>
        )}
      </div>
    </div>
  );
}
