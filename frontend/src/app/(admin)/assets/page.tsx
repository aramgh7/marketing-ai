"use client";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";

interface Asset {
  id: string;
  name: string;
  filename: string;
  assetType: "IMAGE" | "VIDEO" | string;
  imageUrl: string | null;
  l1Tag: string | null;
  l2Tag: string | null;
  l3Tag: string | null;
  suggestedTags: { level: string; value: string; confidence: number }[] | null;
  status: string;
  createdAt: string;
}

const inp = "w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500";

export default function AssetsPage() {
  const [assets, setAssets] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filterL1, setFilterL1] = useState("");
  const [l1Values, setL1Values] = useState<string[]>([]);

  // upload form
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [uploading, setUploading] = useState(false);

  // tag action
  const [tagging, setTagging] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    const q = filterL1 ? `?l1=${encodeURIComponent(filterL1)}` : "";
    apiFetch<Asset[]>(`/api/v1/assets${q}`)
      .then((rows) => setAssets(Array.isArray(rows) ? rows : []))
      .catch((e) => setError(e instanceof Error ? e.message : "Load failed"))
      .finally(() => setLoading(false));
  }, [filterL1]);

  useEffect(() => {
    load();
    apiFetch<string[]>("/api/v1/assets/tag-values")
      .then((vals) => setL1Values(Array.isArray(vals) ? vals : []))
      .catch(() => {});
  }, [load]);

  async function upload(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    setUploading(true);
    setError(null);
    const fd = new FormData();
    fd.append("file", file);
    if (title.trim()) fd.append("title", title.trim());
    try {
      const token = typeof window !== "undefined" ? localStorage.getItem("token") : null;
      const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}/api/v1/assets/upload`, {
        method: "POST",
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body: fd,
      });
      if (!res.ok) throw new Error(`Upload failed: ${res.status}`);
      setFile(null);
      setTitle("");
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  }

  async function autoTag(id: string) {
    setTagging(id);
    try {
      await apiFetch(`/api/v1/assets/${id}/auto-tag`, { method: "POST" });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Auto-tag failed");
    } finally {
      setTagging(null);
    }
  }

  async function confirmTag(id: string, level: string, value: string) {
    try {
      await apiFetch(`/api/v1/assets/${id}/tags`, {
        method: "PUT",
        body: JSON.stringify({ level, value, confirmed: true }),
      });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Tag update failed");
    }
  }

  async function rejectTag(id: string, level: string) {
    try {
      await apiFetch(`/api/v1/assets/${id}/tags`, {
        method: "PUT",
        body: JSON.stringify({ level, confirmed: false }),
      });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Tag update failed");
    }
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Media Library</h1>
        <p className="text-sm text-gray-500 mt-1">Upload images and videos, review AI-suggested tags, and browse your asset library.</p>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {/* Upload */}
      <form onSubmit={upload} className="bg-white rounded-xl border border-gray-200 p-6 flex flex-wrap gap-4 items-end max-w-2xl">
        <h2 className="w-full font-semibold text-gray-800">Upload asset</h2>
        <label className="text-sm flex-1 min-w-48">
          <span className="text-gray-700">Title (optional)</span>
          <input value={title} onChange={(e) => setTitle(e.target.value)} className={inp} placeholder="e.g. Hero founder photo" />
        </label>
        <label className="text-sm flex-1 min-w-48">
          <span className="text-gray-700">File</span>
          <input
            type="file"
            accept="image/*,video/*"
            required
            className="block w-full text-sm text-gray-600 file:mr-3 file:py-1 file:px-3 file:rounded file:border-0 file:bg-indigo-50 file:text-indigo-700 hover:file:bg-indigo-100"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        </label>
        <button type="submit" disabled={uploading || !file} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
          {uploading ? "Uploading…" : "Upload"}
        </button>
      </form>

      {/* Filter */}
      <div className="flex items-center gap-3">
        <label className="text-sm text-gray-700">Filter by type:</label>
        <select value={filterL1} onChange={(e) => setFilterL1(e.target.value)} className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500">
          <option value="">All</option>
          {l1Values.map((v) => <option key={v} value={v}>{v}</option>)}
        </select>
      </div>

      {/* Grid */}
      {loading ? (
        <p className="text-sm text-gray-400">Loading…</p>
      ) : assets.length === 0 ? (
        <p className="text-sm text-gray-400">No assets yet. Upload your first image or video above.</p>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {assets.map((a) => (
            <div key={a.id} className="bg-white rounded-xl border border-gray-200 overflow-hidden">
              {a.imageUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={a.imageUrl} alt={a.name} className="w-full h-40 object-cover bg-gray-100" />
              ) : (
                <div className="w-full h-40 bg-gray-100 flex items-center justify-center text-3xl">
                  {a.assetType === "VIDEO" ? "🎬" : "🖼️"}
                </div>
              )}
              <div className="p-4 space-y-3">
                <div>
                  <div className="font-medium text-sm text-gray-900 truncate">{a.name || a.filename}</div>
                  <div className="text-xs text-gray-400">{a.assetType}</div>
                </div>
                {/* Current tags */}
                <div className="flex flex-wrap gap-1">
                  {a.l1Tag && <span className="px-2 py-0.5 bg-indigo-100 text-indigo-700 text-xs rounded">{a.l1Tag}</span>}
                  {a.l2Tag && <span className="px-2 py-0.5 bg-blue-100 text-blue-700 text-xs rounded">{a.l2Tag}</span>}
                  {a.l3Tag && <span className="px-2 py-0.5 bg-purple-100 text-purple-700 text-xs rounded">{a.l3Tag}</span>}
                </div>
                {/* Suggested tags */}
                {a.suggestedTags && a.suggestedTags.length > 0 && (
                  <div>
                    <div className="text-xs text-gray-500 mb-1">AI suggestions:</div>
                    <div className="flex flex-wrap gap-1">
                      {a.suggestedTags.map((t, i) => (
                        <span key={i} className="inline-flex items-center gap-1 px-2 py-0.5 bg-amber-50 border border-amber-200 text-amber-800 text-xs rounded">
                          {t.value}
                          <button onClick={() => confirmTag(a.id, t.level, t.value)} className="text-green-600 hover:text-green-800 font-bold" title="Confirm">✓</button>
                          <button onClick={() => rejectTag(a.id, t.level)} className="text-red-500 hover:text-red-700 font-bold" title="Reject">✗</button>
                        </span>
                      ))}
                    </div>
                  </div>
                )}
                <button
                  onClick={() => autoTag(a.id)}
                  disabled={tagging === a.id}
                  className="text-xs text-indigo-600 hover:underline disabled:opacity-50"
                >
                  {tagging === a.id ? "Tagging…" : "Auto-tag with AI"}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
