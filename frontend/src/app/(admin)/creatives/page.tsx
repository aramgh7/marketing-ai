"use client";
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import StatusBadge from "@/components/admin/StatusBadge";
import LoadError from "@/components/admin/LoadError";
import { ApiError, apiFetch } from "@/lib/api";

/** LandingVariant statuses (entity: DRAFT -> REVIEW -> ACTIVE, also PAUSED, ARCHIVED). */
const STATUSES = ["ALL", "DRAFT", "REVIEW", "ACTIVE", "PAUSED", "ARCHIVED"];

export default function CreativesPage() {
  const [variants, setVariants] = useState<any[]>([]);
  const [loading, setLoading]   = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [filter, setFilter]     = useState("ALL");

  const load = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    apiFetch<any[]>("/api/v1/landings")
      .then((rows) => setVariants(Array.isArray(rows) ? rows : []))
      .catch((e: unknown) => setLoadError(e instanceof ApiError ? e.message : "Could not load variants"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  const filtered =
    filter === "ALL"
      ? variants
      : variants.filter((v) => v.status === filter);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Landing variants</h1>
        <p className="text-sm text-gray-500 mt-1">
          Review and approve variants before launch
        </p>
      </div>

      <div className="flex gap-2 flex-wrap">
        {STATUSES.map((s) => (
          <button
            key={s}
            onClick={() => setFilter(s)}
            className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
              filter === s
                ? "bg-indigo-600 text-white"
                : "bg-white border border-gray-200 text-gray-600 hover:bg-gray-50"
            }`}
          >
            {s}
          </button>
        ))}
      </div>

      {loading ? (
        <p className="text-gray-400 text-sm">Loading...</p>
      ) : loadError ? (
        <LoadError title="Could not load variants" message={loadError} onRetry={load} />
      ) : filtered.length === 0 ? (
        <p className="text-gray-400 text-sm py-8">No variants found.</p>
      ) : (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-6 py-3 text-left">Variant</th>
                <th className="px-6 py-3 text-left">Status</th>
                <th className="px-6 py-3 text-left">Slug / style</th>
                <th className="px-6 py-3 text-left">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {filtered.map((v: any) => (
                <tr key={v.id} className="hover:bg-gray-50">
                  <td className="px-6 py-4">
                    <div className="font-medium text-gray-900">
                      {v.name ?? v.id}
                    </div>
                    <div className="text-xs text-gray-400">{v.id}</div>
                  </td>
                  <td className="px-6 py-4">
                    <StatusBadge status={v.status} />
                  </td>
                  <td className="px-6 py-4 text-gray-500">
                    {v.slug ? <span className="font-mono text-xs">/l/{v.slug}</span> : "—"}
                    {v.styleAngle && <span className="ml-2 text-xs text-gray-400">{v.styleAngle}</span>}
                  </td>
                  <td className="px-6 py-4">
                    <Link
                      href={`/creatives/${v.id}`}
                      className="text-indigo-600 hover:underline text-xs font-medium"
                    >
                      Review →
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
