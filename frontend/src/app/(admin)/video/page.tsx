"use client";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";
import StatusBadge from "@/components/admin/StatusBadge";

interface Footage {
  id: string;
  title: string;
  status: string;
  duration: number | null;
  transcript: string | null;
  montagePlan: string | null;
  createdAt: string;
}

interface Render {
  id: string;
  footageId: string;
  footageTitle: string | null;
  aspectRatio: string;
  status: string;
  fileUrl: string | null;
  createdAt: string;
}

const inp = "w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500";
const RATIOS = ["9:16", "4:5", "1:1"];

export default function VideoPage() {
  const [tab, setTab] = useState<"footage" | "renders">("footage");

  // footage
  const [footage, setFootage] = useState<Footage[]>([]);
  const [footageLoading, setFootageLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // upload
  const [file, setFile] = useState<File | null>(null);
  const [uploadTitle, setUploadTitle] = useState("");
  const [description, setDescription] = useState("");
  const [uploading, setUploading] = useState(false);

  // per-item actions
  const [planGenId, setPlanGenId] = useState<string | null>(null);
  const [expandedPlan, setExpandedPlan] = useState<string | null>(null);
  const [renderPickId, setRenderPickId] = useState<string | null>(null);
  const [selectedRatios, setSelectedRatios] = useState<string[]>(["9:16"]);
  const [rendering, setRendering] = useState(false);

  // renders tab
  const [renders, setRenders] = useState<Render[]>([]);
  const [rendersLoading, setRendersLoading] = useState(false);

  const loadFootage = useCallback(() => {
    setFootageLoading(true);
    apiFetch<Footage[]>("/api/v1/video/footage")
      .then((rows) => setFootage(Array.isArray(rows) ? rows : []))
      .catch((e) => setError(e instanceof Error ? e.message : "Load failed"))
      .finally(() => setFootageLoading(false));
  }, []);

  const loadRenders = useCallback(() => {
    setRendersLoading(true);
    apiFetch<Render[]>("/api/v1/video/renders")
      .then((rows) => setRenders(Array.isArray(rows) ? rows : []))
      .catch(() => setRenders([]))
      .finally(() => setRendersLoading(false));
  }, []);

  useEffect(() => {
    loadFootage();
  }, [loadFootage]);

  useEffect(() => {
    if (tab === "renders") loadRenders();
  }, [tab, loadRenders]);

  async function upload(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    setUploading(true);
    setError(null);
    const fd = new FormData();
    fd.append("file", file);
    if (uploadTitle.trim()) fd.append("title", uploadTitle.trim());
    if (description.trim()) fd.append("description", description.trim());
    try {
      const token = typeof window !== "undefined" ? localStorage.getItem("token") : null;
      const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}/api/v1/video/footage`, {
        method: "POST",
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        body: fd,
      });
      if (!res.ok) throw new Error(`Upload failed: ${res.status}`);
      setFile(null); setUploadTitle(""); setDescription("");
      loadFootage();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Upload failed");
    } finally {
      setUploading(false);
    }
  }

  async function generatePlan(id: string) {
    setPlanGenId(id);
    setError(null);
    try {
      await apiFetch(`/api/v1/video/footage/${id}/montage-plan`, { method: "POST" });
      loadFootage();
      setExpandedPlan(id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Plan generation failed");
    } finally {
      setPlanGenId(null);
    }
  }

  async function startRender(id: string) {
    setRendering(true);
    setError(null);
    try {
      await apiFetch(`/api/v1/video/footage/${id}/render`, {
        method: "POST",
        body: JSON.stringify({ aspectRatios: selectedRatios }),
      });
      setRenderPickId(null);
      loadRenders();
      setTab("renders");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Render failed");
    } finally {
      setRendering(false);
    }
  }

  async function approveRender(id: string) {
    try {
      await apiFetch(`/api/v1/video/renders/${id}/approve`, { method: "POST" });
      loadRenders();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Approve failed");
    }
  }

  async function rejectRender(id: string) {
    try {
      await apiFetch(`/api/v1/video/renders/${id}/reject`, { method: "POST" });
      loadRenders();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Reject failed");
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Video</h1>
        <p className="text-sm text-gray-500 mt-1">Upload raw footage, generate AI montage plans, and render in multiple aspect ratios.</p>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-200">
        {(["footage", "renders"] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${tab === t ? "border-indigo-600 text-indigo-600" : "border-transparent text-gray-500 hover:text-gray-700"}`}
          >
            {t === "footage" ? "Footage" : "Renders"}
          </button>
        ))}
      </div>

      {tab === "footage" && (
        <div className="space-y-6">
          {/* Upload */}
          <form onSubmit={upload} className="bg-white rounded-xl border border-gray-200 p-6 space-y-4 max-w-2xl">
            <h2 className="font-semibold text-gray-800">Upload footage</h2>
            <div className="grid grid-cols-2 gap-4">
              <label className="text-sm">
                <span className="text-gray-700">Title</span>
                <input value={uploadTitle} onChange={(e) => setUploadTitle(e.target.value)} className={`mt-1 ${inp}`} placeholder="e.g. Founder interview take 3" />
              </label>
              <label className="text-sm">
                <span className="text-gray-700">Description</span>
                <input value={description} onChange={(e) => setDescription(e.target.value)} className={`mt-1 ${inp}`} />
              </label>
            </div>
            <label className="text-sm block">
              <span className="text-gray-700">Video file</span>
              <input
                type="file"
                accept="video/*"
                required
                className="mt-1 block w-full text-sm text-gray-600 file:mr-3 file:py-1 file:px-3 file:rounded file:border-0 file:bg-indigo-50 file:text-indigo-700 hover:file:bg-indigo-100"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </label>
            <button type="submit" disabled={uploading || !file} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
              {uploading ? "Uploading…" : "Upload"}
            </button>
          </form>

          {/* Footage list */}
          {footageLoading ? (
            <p className="text-sm text-gray-400">Loading…</p>
          ) : footage.length === 0 ? (
            <p className="text-sm text-gray-400">No footage yet.</p>
          ) : (
            <div className="space-y-3">
              {footage.map((f) => (
                <div key={f.id} className="bg-white rounded-xl border border-gray-200 p-5">
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex-1">
                      <div className="font-medium text-gray-900">{f.title}</div>
                      <div className="flex items-center gap-3 mt-1">
                        <StatusBadge status={f.status} />
                        {f.duration && <span className="text-xs text-gray-400">{f.duration}s</span>}
                      </div>
                      {f.transcript && (
                        <p className="text-xs text-gray-500 mt-2 line-clamp-2">{f.transcript.slice(0, 120)}{f.transcript.length > 120 ? "…" : ""}</p>
                      )}
                    </div>
                    <div className="flex gap-2 flex-shrink-0">
                      <button
                        onClick={() => generatePlan(f.id)}
                        disabled={planGenId === f.id}
                        className="text-xs border border-gray-300 rounded px-3 py-1.5 hover:bg-gray-50 disabled:opacity-50"
                      >
                        {planGenId === f.id ? "Generating…" : "Montage plan"}
                      </button>
                      <button
                        onClick={() => setRenderPickId(renderPickId === f.id ? null : f.id)}
                        className="text-xs bg-indigo-600 text-white rounded px-3 py-1.5 hover:bg-indigo-700"
                      >
                        Render
                      </button>
                    </div>
                  </div>

                  {/* Montage plan */}
                  {f.montagePlan && (expandedPlan === f.id || f.montagePlan) && (
                    <div className="mt-3 bg-gray-50 rounded-lg p-3">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-xs font-semibold text-gray-600">Montage plan</span>
                        <button onClick={() => setExpandedPlan(expandedPlan === f.id ? null : f.id)} className="text-xs text-gray-400 hover:text-gray-600">
                          {expandedPlan === f.id ? "Collapse" : "Expand"}
                        </button>
                      </div>
                      {expandedPlan === f.id && (
                        <pre className="text-xs text-gray-700 whitespace-pre-wrap overflow-auto max-h-48">{typeof f.montagePlan === "string" ? f.montagePlan : JSON.stringify(f.montagePlan, null, 2)}</pre>
                      )}
                    </div>
                  )}

                  {/* Render picker */}
                  {renderPickId === f.id && (
                    <div className="mt-3 border-t border-gray-100 pt-3 space-y-3">
                      <div className="text-sm font-medium text-gray-700">Select aspect ratios to render:</div>
                      <div className="flex gap-3">
                        {RATIOS.map((r) => (
                          <label key={r} className="flex items-center gap-1.5 text-sm cursor-pointer">
                            <input
                              type="checkbox"
                              checked={selectedRatios.includes(r)}
                              onChange={(e) => setSelectedRatios(e.target.checked ? [...selectedRatios, r] : selectedRatios.filter((x) => x !== r))}
                            />
                            {r}
                          </label>
                        ))}
                      </div>
                      <button
                        onClick={() => startRender(f.id)}
                        disabled={rendering || selectedRatios.length === 0}
                        className="bg-indigo-600 text-white text-xs font-medium rounded-lg px-3 py-1.5 hover:bg-indigo-700 disabled:opacity-50"
                      >
                        {rendering ? "Starting render…" : "Start render"}
                      </button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {tab === "renders" && (
        <div>
          {rendersLoading ? (
            <p className="text-sm text-gray-400">Loading…</p>
          ) : renders.length === 0 ? (
            <p className="text-sm text-gray-400">No renders yet. Upload footage and trigger a render.</p>
          ) : (
            <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
              <table className="w-full text-sm">
                <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
                  <tr>
                    <th className="px-6 py-3 text-left">Footage</th>
                    <th className="px-6 py-3 text-left">Ratio</th>
                    <th className="px-6 py-3 text-left">Status</th>
                    <th className="px-6 py-3 text-left">Created</th>
                    <th className="px-6 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {renders.map((r) => (
                    <tr key={r.id} className="border-t border-gray-100">
                      <td className="px-6 py-3 text-gray-800">{r.footageTitle || r.footageId.slice(0, 8) + "…"}</td>
                      <td className="px-6 py-3">
                        <span className="px-2 py-0.5 bg-gray-100 text-gray-700 text-xs font-medium rounded">{r.aspectRatio}</span>
                      </td>
                      <td className="px-6 py-3"><StatusBadge status={r.status} /></td>
                      <td className="px-6 py-3 text-gray-400">{new Date(r.createdAt).toLocaleDateString()}</td>
                      <td className="px-6 py-3 text-right space-x-2">
                        {r.fileUrl && (
                          <a href={r.fileUrl} target="_blank" rel="noreferrer" className="text-indigo-600 hover:underline text-xs">Download</a>
                        )}
                        {r.status === "DONE" && (
                          <button onClick={() => approveRender(r.id)} className="text-green-600 hover:underline text-xs">Approve</button>
                        )}
                        {r.status !== "REJECTED" && (
                          <button onClick={() => rejectRender(r.id)} className="text-red-600 hover:underline text-xs">Reject</button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
