"use client";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";

interface ProductFact {
  id: string;
  claim: string;
  category: string;
  source: string | null;
  createdAt: string;
}

const CATEGORIES = ["YIELD", "PROOF", "GUARANTEE", "REGULATORY", "OTHER"];
const inp = "w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500";

function catClass(c: string): string {
  if (c === "YIELD") return "bg-green-100 text-green-700";
  if (c === "PROOF") return "bg-blue-100 text-blue-700";
  if (c === "GUARANTEE") return "bg-indigo-100 text-indigo-700";
  if (c === "REGULATORY") return "bg-red-100 text-red-700";
  return "bg-gray-100 text-gray-600";
}

export default function ProductFactsPage() {
  const [facts, setFacts] = useState<ProductFact[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [claim, setClaim] = useState("");
  const [category, setCategory] = useState("PROOF");
  const [source, setSource] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    apiFetch<ProductFact[]>("/api/v1/product-facts")
      .then((rows) => setFacts(Array.isArray(rows) ? rows : []))
      .catch((e) => setError(e instanceof Error ? e.message : "Load failed"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  async function create(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await apiFetch("/api/v1/product-facts", {
        method: "POST",
        body: JSON.stringify({ claim: claim.trim(), category, source: source.trim() || null }),
      });
      setClaim(""); setSource("");
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Create failed");
    } finally {
      setSaving(false);
    }
  }

  async function remove(id: string) {
    if (!confirm("Delete this fact? This may cause previously passing landings to get a WARN on the next compliance check.")) return;
    setError(null);
    try {
      await apiFetch(`/api/v1/product-facts/${id}`, { method: "DELETE" });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Delete failed");
    }
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Product Facts</h1>
        <p className="text-sm text-gray-500 mt-1">
          Product facts are the source-of-truth claims used by the compliance engine to validate landing page text.
          Each claim you add becomes an allowed assertion — unsupported claims will be flagged as WARN.
        </p>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {/* Add form */}
      <form onSubmit={create} className="bg-white rounded-xl border border-gray-200 p-6 space-y-4 max-w-3xl">
        <h2 className="font-semibold text-gray-800">Add fact</h2>
        <label className="text-sm block">
          <span className="text-gray-700">Claim</span>
          <textarea
            required
            rows={3}
            value={claim}
            onChange={(e) => setClaim(e.target.value)}
            className={`mt-1 ${inp}`}
            placeholder="e.g. Our platform improves lead quality by 40% compared to industry average."
          />
        </label>
        <div className="grid grid-cols-2 gap-4">
          <label className="text-sm">
            <span className="text-gray-700">Category</span>
            <select value={category} onChange={(e) => setCategory(e.target.value)} className={`mt-1 ${inp}`}>
              {CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </label>
          <label className="text-sm">
            <span className="text-gray-700">Source (URL or document name)</span>
            <input value={source} onChange={(e) => setSource(e.target.value)} className={`mt-1 ${inp}`} placeholder="e.g. Q3 2026 Performance Report" />
          </label>
        </div>
        <button type="submit" disabled={saving} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
          {saving ? "Saving…" : "Add fact"}
        </button>
      </form>

      {/* Facts list */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100">
          <h2 className="font-semibold text-gray-800">Approved claims ({facts.length})</h2>
        </div>
        {loading ? (
          <p className="px-6 py-8 text-sm text-gray-400">Loading…</p>
        ) : facts.length === 0 ? (
          <div className="px-6 py-8 text-sm text-gray-400 space-y-1">
            <p>No product facts yet.</p>
            <p>Product facts are used by the compliance engine to validate landing page claims. Add your first fact above.</p>
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-6 py-3 text-left">Claim</th>
                <th className="px-6 py-3 text-left">Category</th>
                <th className="px-6 py-3 text-left">Source</th>
                <th className="px-6 py-3 text-left">Added</th>
                <th className="px-6 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {facts.map((f) => (
                <tr key={f.id} className="border-t border-gray-100 align-top">
                  <td className="px-6 py-3 text-gray-800 max-w-md">{f.claim}</td>
                  <td className="px-6 py-3">
                    <span className={`px-2 py-0.5 rounded text-xs font-medium ${catClass(f.category)}`}>{f.category}</span>
                  </td>
                  <td className="px-6 py-3 text-gray-500 text-xs">{f.source ?? "—"}</td>
                  <td className="px-6 py-3 text-gray-400 whitespace-nowrap">{new Date(f.createdAt).toLocaleDateString()}</td>
                  <td className="px-6 py-3 text-right">
                    <button onClick={() => remove(f.id)} className="text-red-600 hover:underline text-xs">Delete</button>
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
