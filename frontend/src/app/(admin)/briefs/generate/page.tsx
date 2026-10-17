"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { apiFetch } from "@/lib/api";

const CLUSTERS = [
  "PRICE_SENSITIVE",
  "QUALITY_FOCUSED",
  "BRAND_LOYAL",
  "CONVENIENCE_SEEKER",
  "ENGAGED",
  "COLD_AUDIENCE",
];

const AUDIENCES = [
  "retargeting_engaged",
  "lookalike_buyers",
  "cold_broad",
  "cold_interest",
];

export default function GenerateBriefPage() {
  const router = useRouter();
  const [cluster, setCluster]   = useState(CLUSTERS[0]);
  const [audience, setAudience] = useState(AUDIENCES[0]);
  const [loading, setLoading]   = useState(false);
  const [error, setError]       = useState<string | null>(null);

  async function handleGenerate() {
    setLoading(true);
    setError(null);
    try {
      const brief = await apiFetch<any>("/api/v1/briefs/generate-data-driven", {
        method: "POST",
        body: JSON.stringify({
          cluster_label:    cluster,
          audience_segment: audience,
        }),
      });
      router.push(`/briefs/${brief.id}`);
    } catch (e: any) {
      setError(e.message ?? "Generation failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="max-w-xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">
          Generate Data-Driven Brief
        </h1>
        <p className="text-sm text-gray-500 mt-1">
          Creates a brief using posteriors, VoC objections, and niche
          benchmarks for the selected segment.
        </p>
      </div>

      <div className="bg-white rounded-xl border border-gray-200 p-6 space-y-5">
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Audience Cluster
          </label>
          <select
            value={cluster}
            onChange={(e) => setCluster(e.target.value)}
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          >
            {CLUSTERS.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700 mb-1">
            Audience Segment
          </label>
          <select
            value={audience}
            onChange={(e) => setAudience(e.target.value)}
            className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          >
            {AUDIENCES.map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </select>
        </div>

        {error && <p className="text-red-500 text-sm">{error}</p>}

        <button
          onClick={handleGenerate}
          disabled={loading}
          className="w-full py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
        >
          {loading ? "Generating..." : "Generate Brief"}
        </button>
      </div>
    </div>
  );
}
