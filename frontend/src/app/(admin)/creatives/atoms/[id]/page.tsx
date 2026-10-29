"use client";
import Link from "next/link";
import { use, FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";

interface Atom {
  id: string;
  atomType: string;
  atomValue: string;
  confidence: number | null;
  source: "auto" | "manual" | string;
}

interface Creative {
  id: string;
  name: string | null;
  filename: string | null;
  atoms: Atom[];
}

const ATOM_TYPES = ["HOOK", "STRUCTURE", "EDIT_PACE", "FACE", "TONE", "PROOF_TYPE", "CTA", "MUSIC", "PRODUCTION_STYLE", "DURATION"];
const inp = "border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500";

function confidenceColor(c: number | null): string {
  if (c == null) return "text-gray-400";
  if (c >= 0.85) return "text-green-600";
  if (c >= 0.65) return "text-yellow-600";
  return "text-red-500";
}

export default function AtomDetailPage({ params: paramsProp }: { params: Promise<{ id: string }> }) {
  const params = use(paramsProp);
  const [creative, setCreative] = useState<Creative | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // edit state
  const [editId, setEditId] = useState<string | null>(null);
  const [editValue, setEditValue] = useState("");

  // add form
  const [newType, setNewType] = useState("HOOK");
  const [newValue, setNewValue] = useState("");
  const [adding, setAdding] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    apiFetch<Creative>(`/api/v1/creatives/${params.id}`)
      .then((c) => setCreative(c))
      .catch((e) => setError(e instanceof Error ? e.message : "Load failed"))
      .finally(() => setLoading(false));
  }, [params.id]);

  useEffect(() => { load(); }, [load]);

  async function saveEdit(atomId: string) {
    setError(null);
    try {
      await apiFetch(`/api/v1/creatives/${params.id}/atoms/${atomId}`, {
        method: "PUT",
        body: JSON.stringify({ atomValue: editValue.trim() }),
      });
      setEditId(null);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Save failed");
    }
  }

  async function addAtom(e: FormEvent) {
    e.preventDefault();
    setAdding(true);
    setError(null);
    try {
      await apiFetch(`/api/v1/creatives/${params.id}/atoms`, {
        method: "POST",
        body: JSON.stringify({ atomType: newType, atomValue: newValue.trim() }),
      });
      setNewValue("");
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Add failed");
    } finally {
      setAdding(false);
    }
  }

  async function deleteAtom(atomId: string) {
    if (!confirm("Delete this atom?")) return;
    setError(null);
    try {
      await apiFetch(`/api/v1/creatives/${params.id}/atoms/${atomId}`, { method: "DELETE" });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Delete failed");
    }
  }

  // group atoms by type
  const grouped = (creative?.atoms ?? []).reduce<Record<string, Atom[]>>((acc, a) => {
    if (!acc[a.atomType]) acc[a.atomType] = [];
    acc[a.atomType].push(a);
    return acc;
  }, {});

  const title = creative?.name || creative?.filename || params.id.slice(0, 8) + "…";

  return (
    <div className="space-y-8">
      <div className="flex items-center gap-3">
        <Link href="/creatives" className="text-sm text-indigo-600 hover:underline">← Creatives</Link>
        <span className="text-gray-300">/</span>
        <h1 className="text-2xl font-bold text-gray-900">
          {loading ? "Loading…" : title}
        </h1>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {!loading && creative && (
        <>
          {/* Atoms grouped by type */}
          {Object.keys(grouped).length === 0 ? (
            <p className="text-sm text-gray-400">No atoms yet. Add one below.</p>
          ) : (
            <div className="space-y-4">
              {ATOM_TYPES.filter((t) => grouped[t]).concat(
                Object.keys(grouped).filter((t) => !ATOM_TYPES.includes(t))
              ).map((type) => (
                <div key={type} className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                  <div className="px-5 py-3 bg-gray-50 border-b border-gray-200">
                    <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{type}</span>
                  </div>
                  <table className="w-full text-sm">
                    <tbody>
                      {grouped[type].map((a) => (
                        <tr key={a.id} className="border-t border-gray-100 first:border-0">
                          <td className="px-5 py-3 flex-1">
                            {editId === a.id ? (
                              <input
                                value={editValue}
                                onChange={(e) => setEditValue(e.target.value)}
                                className={`${inp} w-full`}
                                autoFocus
                              />
                            ) : (
                              <span className="text-gray-800">{a.atomValue}</span>
                            )}
                          </td>
                          <td className="px-5 py-3 w-24">
                            {a.confidence != null && (
                              <span className={`text-xs font-mono ${confidenceColor(a.confidence)}`}>
                                {(a.confidence * 100).toFixed(0)}%
                              </span>
                            )}
                          </td>
                          <td className="px-5 py-3 w-20 text-xs text-gray-400">{a.source}</td>
                          <td className="px-5 py-3 text-right whitespace-nowrap space-x-2">
                            {editId === a.id ? (
                              <>
                                <button onClick={() => saveEdit(a.id)} className="text-indigo-600 hover:underline text-xs">Save</button>
                                <button onClick={() => setEditId(null)} className="text-gray-500 hover:underline text-xs">Cancel</button>
                              </>
                            ) : (
                              <>
                                <button onClick={() => { setEditId(a.id); setEditValue(a.atomValue); }} className="text-indigo-600 hover:underline text-xs">Edit</button>
                                <button onClick={() => deleteAtom(a.id)} className="text-red-500 hover:underline text-xs">Delete</button>
                              </>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ))}
            </div>
          )}

          {/* Add atom form */}
          <form onSubmit={addAtom} className="bg-white rounded-xl border border-gray-200 p-5 flex flex-wrap gap-3 items-end max-w-2xl">
            <h2 className="w-full font-semibold text-gray-800 text-sm">Add atom</h2>
            <label className="text-sm">
              <span className="text-gray-700 text-xs block mb-1">Type</span>
              <select value={newType} onChange={(e) => setNewType(e.target.value)} className={inp}>
                {ATOM_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </label>
            <label className="text-sm flex-1">
              <span className="text-gray-700 text-xs block mb-1">Value</span>
              <input required value={newValue} onChange={(e) => setNewValue(e.target.value)} className={`${inp} w-full`} placeholder="e.g. fear_of_loss" />
            </label>
            <button type="submit" disabled={adding} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
              {adding ? "Adding…" : "Add"}
            </button>
          </form>
        </>
      )}
    </div>
  );
}
