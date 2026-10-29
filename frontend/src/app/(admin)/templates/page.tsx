"use client";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, apiFetch } from "@/lib/api";

interface Template {
  id: string;
  name: string;
  description: string | null;
  blockOrder: string[] | null;
  isDefault: boolean;
  tenantId: string | null;
  createdAt: string;
}

const BLOCK_LABELS: Record<string, string> = {
  hero: "Hero",
  problem: "Problem",
  proof: "Proof",
  pricing: "Pricing",
  cta: "CTA",
  faq: "FAQ",
  story: "Story",
  education: "Education",
  social_proof: "Social Proof",
  authority: "Authority",
};

export default function TemplatesPage() {
  const router = useRouter();
  const [templates, setTemplates] = useState<Template[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [seeding, setSeeding] = useState(false);

  // fork editor state
  const [forking, setForking] = useState<string | null>(null);
  const [editBlocks, setEditBlocks] = useState<string[]>([]);
  const [editName, setEditName] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    apiFetch<Template[]>("/api/v1/templates")
      .then((rows) => setTemplates(Array.isArray(rows) ? rows : []))
      .catch((e) => setError(e instanceof Error ? e.message : "Load failed"))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(); }, [load]);

  async function seedDefaults() {
    setSeeding(true);
    setError(null);
    try {
      await apiFetch("/api/v1/templates/seed-defaults", { method: "POST" });
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Seed failed");
    } finally {
      setSeeding(false);
    }
  }

  async function startFork(t: Template) {
    setError(null);
    try {
      const forked = await apiFetch<Template>(`/api/v1/templates/${t.id}/fork`, { method: "POST" });
      setForking(forked.id);
      setEditBlocks(forked.blockOrder ?? t.blockOrder ?? []);
      setEditName(forked.name);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Fork failed");
    }
  }

  function moveBlock(idx: number, dir: -1 | 1) {
    const newBlocks = [...editBlocks];
    const target = idx + dir;
    if (target < 0 || target >= newBlocks.length) return;
    [newBlocks[idx], newBlocks[target]] = [newBlocks[target], newBlocks[idx]];
    setEditBlocks(newBlocks);
  }

  async function saveFork() {
    if (!forking) return;
    setSaving(true);
    setError(null);
    try {
      await apiFetch(`/api/v1/templates/${forking}`, {
        method: "PUT",
        body: JSON.stringify({ name: editName, blockOrder: editBlocks }),
      });
      setForking(null);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }

  function useTemplate(t: Template) {
    if (typeof window !== "undefined") {
      sessionStorage.setItem("selectedTemplateId", t.id);
    }
    router.push("/landings");
  }

  return (
    <div className="space-y-8">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Templates</h1>
          <p className="text-sm text-gray-500 mt-1">Choose a landing page template or fork and customise one.</p>
        </div>
        {templates.length === 0 && !loading && (
          <button onClick={seedDefaults} disabled={seeding} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
            {seeding ? "Seeding…" : "Seed 5 defaults"}
          </button>
        )}
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {/* Fork editor */}
      {forking && (
        <div className="bg-amber-50 border border-amber-200 rounded-xl p-6 space-y-4 max-w-xl">
          <h2 className="font-semibold text-gray-800">Customise template</h2>
          <label className="text-sm block">
            <span className="text-gray-700">Name</span>
            <input value={editName} onChange={(e) => setEditName(e.target.value)} className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
          </label>
          <div>
            <div className="text-sm text-gray-700 mb-2">Block order</div>
            <div className="space-y-1">
              {editBlocks.map((block, i) => (
                <div key={`${block}-${i}`} className="flex items-center gap-2 bg-white border border-gray-200 rounded-lg px-3 py-2">
                  <span className="flex-1 text-sm font-medium text-gray-800">{BLOCK_LABELS[block] ?? block}</span>
                  <button onClick={() => moveBlock(i, -1)} disabled={i === 0} className="text-gray-400 hover:text-gray-700 disabled:opacity-30 text-xs">▲</button>
                  <button onClick={() => moveBlock(i, 1)} disabled={i === editBlocks.length - 1} className="text-gray-400 hover:text-gray-700 disabled:opacity-30 text-xs">▼</button>
                </div>
              ))}
            </div>
          </div>
          <div className="flex gap-2">
            <button onClick={saveFork} disabled={saving} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
              {saving ? "Saving…" : "Save template"}
            </button>
            <button onClick={() => { setForking(null); load(); }} className="text-sm text-gray-500 hover:text-gray-700 px-4 py-2">Cancel</button>
          </div>
        </div>
      )}

      {loading ? (
        <p className="text-sm text-gray-400">Loading…</p>
      ) : templates.length === 0 ? (
        <p className="text-sm text-gray-400">No templates yet. Click "Seed 5 defaults" to populate the platform templates.</p>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {templates.map((t) => (
            <div key={t.id} className={`bg-white rounded-xl border p-5 space-y-4 ${t.id === forking ? "border-indigo-400 ring-2 ring-indigo-200" : "border-gray-200"}`}>
              <div className="flex items-start justify-between gap-2">
                <div>
                  <div className="font-semibold text-gray-900">{t.name}</div>
                  {t.isDefault && <span className="text-xs text-indigo-600">platform default</span>}
                  {!t.tenantId && !t.isDefault && <span className="text-xs text-gray-400">custom</span>}
                </div>
              </div>
              {t.description && <p className="text-sm text-gray-500">{t.description}</p>}
              {t.blockOrder && t.blockOrder.length > 0 && (
                <div className="flex flex-wrap gap-1">
                  {t.blockOrder.map((b, i) => (
                    <span key={i} className="px-2 py-0.5 bg-gray-100 text-gray-600 text-xs rounded">{BLOCK_LABELS[b] ?? b}</span>
                  ))}
                </div>
              )}
              <div className="flex gap-2 pt-1">
                <button onClick={() => useTemplate(t)} className="flex-1 bg-indigo-600 text-white text-sm font-medium rounded-lg px-3 py-2 hover:bg-indigo-700">
                  Use
                </button>
                <button onClick={() => startFork(t)} className="flex-1 border border-gray-300 text-sm font-medium rounded-lg px-3 py-2 hover:bg-gray-50">
                  Fork &amp; customise
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
