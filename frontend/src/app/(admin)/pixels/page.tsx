"use client";
import { FormEvent, useCallback, useEffect, useState } from "react";
import StatusBadge from "@/components/admin/StatusBadge";
import { ApiError, apiFetch } from "@/lib/api";

/** PixelResponse from MetaPixelController (roadmap 9-5 admin UI for §6.8 multi-pixel). */
interface Pixel {
  id: string;
  pixelId: string;
  name: string | null;
  purpose: "PRIMARY" | "TEST" | "PRODUCT" | string;
  isDefault: boolean;
  hasCapiToken: boolean;
  qualityFilter: Record<string, unknown>;
  status: string;
  createdAt: string;
}

const PURPOSES = ["PRIMARY", "TEST", "PRODUCT"];
const input =
  "w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500";

export default function PixelsPage() {
  const [pixels, setPixels] = useState<Pixel[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // create form
  const [pixelId, setPixelId] = useState("");
  const [name, setName] = useState("");
  const [purpose, setPurpose] = useState("PRIMARY");
  const [capiToken, setCapiToken] = useState("");
  const [minScore, setMinScore] = useState("");
  const [isDefault, setIsDefault] = useState(false);
  const [saving, setSaving] = useState(false);

  // inline edit
  const [editId, setEditId] = useState<string | null>(null);
  const [editName, setEditName] = useState("");
  const [editMinScore, setEditMinScore] = useState("");
  const [editStatus, setEditStatus] = useState("ACTIVE");
  const [editToken, setEditToken] = useState("");

  const load = useCallback(() => {
    setLoading(true);
    apiFetch<Pixel[]>("/api/v1/pixels")
      .then((rows) => setPixels(Array.isArray(rows) ? rows : []))
      .catch((e) => setError(e instanceof Error ? e.message : "Load failed"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function qualityFilterFrom(min: string): Record<string, unknown> | undefined {
    const n = Number(min);
    if (!min.trim() || Number.isNaN(n)) return undefined;
    return { min_quality_score: n };
  }

  async function create(e: FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await apiFetch("/api/v1/pixels", {
        method: "POST",
        body: JSON.stringify({
          pixelId: pixelId.trim(),
          name: name.trim() || null,
          purpose,
          capiAccessToken: capiToken.trim() || null,
          qualityFilter: qualityFilterFrom(minScore),
          isDefault,
        }),
      });
      setPixelId("");
      setName("");
      setCapiToken("");
      setMinScore("");
      setIsDefault(false);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Create failed");
    } finally {
      setSaving(false);
    }
  }

  function startEdit(p: Pixel) {
    setEditId(p.id);
    setEditName(p.name ?? "");
    const qf = p.qualityFilter ?? {};
    setEditMinScore(qf.min_quality_score != null ? String(qf.min_quality_score) : "");
    setEditStatus(p.status ?? "ACTIVE");
    setEditToken("");
  }

  async function saveEdit(id: string) {
    setError(null);
    try {
      await apiFetch(`/api/v1/pixels/${id}`, {
        method: "PUT",
        body: JSON.stringify({
          name: editName.trim() || null,
          status: editStatus,
          qualityFilter: qualityFilterFrom(editMinScore) ?? {},
          capiAccessToken: editToken.trim() || null,
        }),
      });
      setEditId(null);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Update failed");
    }
  }

  async function deactivate(id: string) {
    setError(null);
    try {
      await apiFetch(`/api/v1/pixels/${id}`, { method: "DELETE" });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Deactivate failed");
    }
  }

  async function makeDefault(id: string) {
    setError(null);
    try {
      await apiFetch(`/api/v1/pixels/${id}/default`, { method: "PUT" });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Update failed");
    }
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Pixels</h1>
        <p className="text-sm text-gray-500 mt-1">
          Multi-pixel setup (PRIMARY / TEST / PRODUCT). CAPI tokens are stored sealed and never shown again.
        </p>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <form onSubmit={create} className="bg-white rounded-xl border border-gray-200 p-6 grid grid-cols-2 gap-4 max-w-3xl">
        <h2 className="col-span-2 font-semibold text-gray-800">Add pixel</h2>
        <label className="text-sm">
          <span className="text-gray-700">Meta pixel ID</span>
          <input required value={pixelId} onChange={(e) => setPixelId(e.target.value)} className={input} placeholder="123456789012345" />
        </label>
        <label className="text-sm">
          <span className="text-gray-700">Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} className={input} />
        </label>
        <label className="text-sm">
          <span className="text-gray-700">Purpose</span>
          <select value={purpose} onChange={(e) => setPurpose(e.target.value)} className={input}>
            {PURPOSES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          <span className="text-gray-700">Min quality score (quality filter)</span>
          <input value={minScore} onChange={(e) => setMinScore(e.target.value)} className={input} placeholder="e.g. 0.5" />
        </label>
        <label className="text-sm col-span-2">
          <span className="text-gray-700">CAPI access token</span>
          <input type="password" value={capiToken} onChange={(e) => setCapiToken(e.target.value)} className={input} autoComplete="off" />
        </label>
        <label className="text-sm flex items-center gap-2">
          <input type="checkbox" checked={isDefault} onChange={(e) => setIsDefault(e.target.checked)} />
          <span className="text-gray-700">Set as default pixel</span>
        </label>
        <div className="text-right">
          <button type="submit" disabled={saving} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
            {saving ? "Saving…" : "Create"}
          </button>
        </div>
      </form>

      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="px-6 py-4 border-b border-gray-100">
          <h2 className="font-semibold text-gray-800">Configured pixels</h2>
        </div>
        {loading ? (
          <p className="px-6 py-8 text-gray-400 text-sm">Loading…</p>
        ) : pixels.length === 0 ? (
          <p className="px-6 py-8 text-gray-400 text-sm">No pixels yet.</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-6 py-3 text-left">Pixel</th>
                <th className="px-6 py-3 text-left">Purpose</th>
                <th className="px-6 py-3 text-left">CAPI token</th>
                <th className="px-6 py-3 text-left">Quality filter</th>
                <th className="px-6 py-3 text-left">Status</th>
                <th className="px-6 py-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {pixels.map((p) => (
                <tr key={p.id} className="border-t border-gray-100 align-top">
                  {editId === p.id ? (
                    <>
                      <td className="px-6 py-3">
                        <div className="text-xs text-gray-400 font-mono">{p.pixelId}</div>
                        <input value={editName} onChange={(e) => setEditName(e.target.value)} className={input} placeholder="Name" />
                      </td>
                      <td className="px-6 py-3">{p.purpose}</td>
                      <td className="px-6 py-3">
                        <input type="password" value={editToken} onChange={(e) => setEditToken(e.target.value)} className={input} placeholder={p.hasCapiToken ? "replace token…" : "set token…"} autoComplete="off" />
                      </td>
                      <td className="px-6 py-3">
                        <input value={editMinScore} onChange={(e) => setEditMinScore(e.target.value)} className={input} placeholder="min score" />
                      </td>
                      <td className="px-6 py-3">
                        <select value={editStatus} onChange={(e) => setEditStatus(e.target.value)} className={input}>
                          <option value="ACTIVE">ACTIVE</option>
                          <option value="ARCHIVED">ARCHIVED</option>
                        </select>
                      </td>
                      <td className="px-6 py-3 text-right space-x-2 whitespace-nowrap">
                        <button onClick={() => saveEdit(p.id)} className="text-indigo-600 hover:underline">Save</button>
                        <button onClick={() => setEditId(null)} className="text-gray-500 hover:underline">Cancel</button>
                      </td>
                    </>
                  ) : (
                    <>
                      <td className="px-6 py-3">
                        <div className="font-medium text-gray-800">{p.name || "—"}</div>
                        <div className="text-xs text-gray-400 font-mono">{p.pixelId}</div>
                        {p.isDefault && <span className="text-xs text-indigo-600">default</span>}
                      </td>
                      <td className="px-6 py-3">{p.purpose}</td>
                      <td className="px-6 py-3">{p.hasCapiToken ? "✓ stored" : "—"}</td>
                      <td className="px-6 py-3 text-xs text-gray-600 font-mono">
                        {p.qualityFilter && Object.keys(p.qualityFilter).length > 0 ? JSON.stringify(p.qualityFilter) : "defaults"}
                      </td>
                      <td className="px-6 py-3"><StatusBadge status={p.status} /></td>
                      <td className="px-6 py-3 text-right space-x-2 whitespace-nowrap">
                        <button onClick={() => startEdit(p)} className="text-indigo-600 hover:underline">Edit</button>
                        {!p.isDefault && p.status === "ACTIVE" && (
                          <button onClick={() => makeDefault(p.id)} className="text-gray-600 hover:underline">Make default</button>
                        )}
                        {p.status === "ACTIVE" && (
                          <button onClick={() => deactivate(p.id)} className="text-red-600 hover:underline">Deactivate</button>
                        )}
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
