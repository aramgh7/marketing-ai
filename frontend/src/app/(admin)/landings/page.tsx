"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import StatusBadge from "@/components/admin/StatusBadge";
import { apiFetch } from "@/lib/api";

/**
 * Roadmap Phase 10: the marketer sees the variants the system assembled for one brief side by
 * side, edits text in place (edits feed RLHA), previews, chooses one for review or rejects it
 * with a reason. Approval itself (compliance checklist) stays on /creatives/{id}.
 */

type Block = { type: string; id: string; data: Record<string, unknown> };
type Variant = {
  id: string; name: string; slug: string; status: string; creativeId?: string | null;
  blocks: Block[] | string; blockOrder?: string[] | null;
  setId?: string | null; rejectReason?: string | null; rejectNote?: string | null; styleAngle?: string | null;
  offer?: (Record<string, unknown> & { id?: string }) | null;
};
type SetRow = { set_id: string; name: string; created_at: string; count: number; statuses: Record<string, number> };

const BLOCK_TYPES = ["HERO", "PROBLEM", "CTA", "FAQ"];
const EDIT_REASONS = ["TONE", "FACTS", "LEGAL", "LENGTH", "STRUCTURE", "OTHER"];
const REJECT_REASONS = ["TEXT", "TONE", "COMPLIANCE", "OFF_BRIEF", "STRUCTURE", "OTHER"];
const SLUG_RE = /^[a-z0-9-]+$/;

/** Text slots the compare view shows and edits, per block type. */
const TEXT_SLOTS: Record<string, string[]> = {
  HERO: ["headline", "subheadline", "cta_text"],
  PROBLEM: ["heading"],
  CTA: ["heading", "subheading", "cta_text", "urgency_text"],
  FAQ: ["heading"],
  PROOF: ["heading"],
  PRICING: ["heading", "guarantee_text"],
};

function parseBlocks(raw: Block[] | string | undefined): Block[] {
  if (Array.isArray(raw)) return raw;
  try { return JSON.parse(raw ?? "[]"); } catch { return []; }
}

export default function LandingsPage() {
  const [sets, setSets] = useState<SetRow[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [variants, setVariants] = useState<Variant[]>([]);
  const [error, setError] = useState("");
  const [generating, setGenerating] = useState(false);
  const [form, setForm] = useState({
    name: "", slug_base: "", count: 3, audience: "", hook_text: "", style_profile: "",
    block_types: [...BLOCK_TYPES],
  });

  async function loadSets(select?: string) {
    try {
      const rows = await apiFetch<SetRow[]>("/api/v1/landings/sets");
      setSets(rows);
      const pick = select ?? selected ?? rows[0]?.set_id ?? null;
      setSelected(pick);
      setError("");
    } catch (e: any) { setError(e?.message ?? "load failed"); }
  }
  async function loadVariants(setId: string) {
    try {
      setVariants(await apiFetch<Variant[]>(`/api/v1/landings?set_id=${encodeURIComponent(setId)}`));
    } catch (e: any) { setError(e?.message ?? "load failed"); }
  }
  useEffect(() => { loadSets(); }, []);                      // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (selected) loadVariants(selected); else setVariants([]); }, [selected]);

  const slugOk = SLUG_RE.test(form.slug_base);
  const canGenerate = form.name.trim().length > 0 && slugOk && form.block_types.length > 0 && !generating;

  async function generate() {
    setGenerating(true);
    setError("");
    try {
      const res = await apiFetch<{ set_id: string; variants: Variant[] }>("/api/v1/landings/generate-set", {
        method: "POST",
        body: JSON.stringify({ ...form, count: Math.min(5, Math.max(1, Number(form.count) || 3)) }),
      });
      setForm({ ...form, name: "", slug_base: "" });
      await loadSets(res.set_id);
    } catch (e: any) { setError(e?.message ?? "generation failed"); }
    finally { setGenerating(false); }
  }

  function toggleBlockType(t: string) {
    setForm((f) => ({
      ...f,
      block_types: f.block_types.includes(t) ? f.block_types.filter((x) => x !== t) : [...f.block_types, t],
    }));
  }

  const refresh = () => { if (selected) loadVariants(selected); loadSets(); };

  return (
    <div className="max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Landings</h1>
        <p className="text-sm text-gray-500">
          The system assembles several landing variants from one brief. You compare them, edit, preview,
          choose one for review or reject it with a reason. Every edit and rejection teaches the next generation.
        </p>
      </div>
      {error && <div className="rounded bg-red-50 border border-red-200 p-3 text-sm text-red-700">{error}</div>}

      {/* (a) Generate a set */}
      <section className="bg-white rounded-lg border border-gray-200 p-4 space-y-3">
        <h2 className="text-sm font-semibold text-gray-800">Generate a set</h2>
        <div className="grid md:grid-cols-3 gap-2">
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="name, e.g. Spring raise — investors" className="border rounded px-2 py-1.5 text-sm" />
          <div>
            <input value={form.slug_base} onChange={(e) => setForm({ ...form, slug_base: e.target.value.toLowerCase() })} placeholder="slug base, e.g. spring-raise" className={`border rounded px-2 py-1.5 text-sm w-full ${form.slug_base && !slugOk ? "border-red-400" : ""}`} />
            {form.slug_base && !slugOk && <p className="text-[11px] text-red-600 mt-0.5">lowercase letters, digits and hyphens only</p>}
          </div>
          <input type="number" min={1} max={5} value={form.count} onChange={(e) => setForm({ ...form, count: Number(e.target.value) })} className="border rounded px-2 py-1.5 text-sm" title="variants (1–5)" />
          <input value={form.audience} onChange={(e) => setForm({ ...form, audience: e.target.value })} placeholder="audience (optional)" className="border rounded px-2 py-1.5 text-sm" />
          <input value={form.hook_text} onChange={(e) => setForm({ ...form, hook_text: e.target.value })} placeholder="creative hook used in the ad (message match)" className="border rounded px-2 py-1.5 text-sm" />
          <input value={form.style_profile} onChange={(e) => setForm({ ...form, style_profile: e.target.value })} placeholder="style profile (optional)" className="border rounded px-2 py-1.5 text-sm" />
        </div>
        <div className="flex flex-wrap gap-3 items-center">
          {BLOCK_TYPES.map((t) => (
            <label key={t} className="text-xs text-gray-700 flex items-center gap-1">
              <input type="checkbox" checked={form.block_types.includes(t)} onChange={() => toggleBlockType(t)} /> {t}
            </label>
          ))}
          <button onClick={generate} disabled={!canGenerate} className="ml-auto px-3 py-1.5 rounded bg-indigo-600 text-white text-sm disabled:opacity-50">
            {generating ? "Generating…" : `Generate ${form.count} variant${form.count === 1 ? "" : "s"}`}
          </button>
        </div>
        {generating && (
          <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded px-3 py-2">
            Generating {form.count} variants… this takes up to a minute (one LLM call per block, per variant).
          </p>
        )}
      </section>

      {/* (b) Sets */}
      <section className="bg-white rounded-lg border border-gray-200 p-4">
        <h2 className="text-sm font-semibold text-gray-800 mb-2">Sets</h2>
        {sets.length === 0 ? (
          <p className="text-xs text-gray-400">No sets yet — generate the first one above.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {sets.map((s) => (
              <li key={s.set_id}>
                <button onClick={() => setSelected(s.set_id)} className={`w-full text-left px-2 py-2 rounded flex flex-wrap items-center gap-2 text-sm ${selected === s.set_id ? "bg-indigo-50" : "hover:bg-gray-50"}`}>
                  <span className="font-medium text-gray-900">{s.name}</span>
                  <span className="text-xs text-gray-400">{s.created_at?.slice(0, 16)}</span>
                  <span className="text-xs text-gray-500">{s.count} variants</span>
                  <span className="ml-auto flex gap-1">
                    {Object.entries(s.statuses ?? {}).filter(([, n]) => n > 0).map(([st, n]) => (
                      <span key={st} className="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-600">{st} {n}</span>
                    ))}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* (c) Compare */}
      {selected && (
        <section className="space-y-2">
          <h2 className="text-sm font-semibold text-gray-800">Compare</h2>
          {variants.length === 0 ? (
            <p className="text-xs text-gray-400">No variants in this set.</p>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              {variants.map((v) => <VariantCard key={v.id} v={v} onChange={refresh} onError={setError} />)}
            </div>
          )}
        </section>
      )}
    </div>
  );
}

// ── one variant card ───────────────────────────────────────────────────────────

function VariantCard({ v, onChange, onError }: { v: Variant; onChange: () => void; onError: (m: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [blocks, setBlocks] = useState<Block[]>(() => parseBlocks(v.blocks));
  const [reason, setReason] = useState("TONE");
  const [rejecting, setRejecting] = useState(false);
  const [rejectReason, setRejectReason] = useState("TEXT");
  const [rejectNote, setRejectNote] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => { setBlocks(parseBlocks(v.blocks)); }, [v.blocks]);

  const archived = v.status === "ARCHIVED";
  const editable = v.status === "DRAFT" || v.status === "REVIEW" || v.status === "PAUSED";

  function setSlot(bi: number, key: string, value: string) {
    setBlocks((bs) => bs.map((b, i) => (i === bi ? { ...b, data: { ...b.data, [key]: value } } : b)));
  }
  function setArrayItem(bi: number, key: string, idx: number, value: unknown) {
    setBlocks((bs) => bs.map((b, i) => {
      if (i !== bi) return b;
      const arr = Array.isArray(b.data[key]) ? [...(b.data[key] as unknown[])] : [];
      arr[idx] = value;
      return { ...b, data: { ...b.data, [key]: arr } };
    }));
  }

  async function save() {
    setBusy(true);
    try {
      const offer = v.offer ? Object.fromEntries(Object.entries(v.offer).filter(([k]) => k !== "id")) : undefined;
      await apiFetch(`/api/v1/landings/${v.id}?edit_reason=${reason}`, {
        method: "PUT",
        body: JSON.stringify({
          name: v.name, slug: v.slug, creativeId: v.creativeId ?? null,
          blocks: JSON.stringify(blocks),
          blockOrder: v.blockOrder ?? blocks.map((b) => b.type),
          offer, setId: v.setId ?? null, styleAngle: v.styleAngle ?? null,
        }),
      });
      setEditing(false);
      onChange();
    } catch (e: any) { onError(e?.message ?? "save failed"); }
    finally { setBusy(false); }
  }
  async function choose() {
    setBusy(true);
    try {
      await apiFetch(`/api/v1/landings/${v.id}/submit`, { method: "POST" });
      onChange();
    } catch (e: any) { onError(e?.message ?? "submit failed"); }
    finally { setBusy(false); }
  }
  async function reject() {
    setBusy(true);
    try {
      await apiFetch(`/api/v1/landings/${v.id}/reject`, { method: "POST", body: JSON.stringify({ reason: rejectReason, note: rejectNote || null }) });
      setRejecting(false);
      onChange();
    } catch (e: any) { onError(e?.message ?? "reject failed"); }
    finally { setBusy(false); }
  }

  return (
    <div className={`bg-white rounded-lg border border-gray-200 p-4 flex flex-col gap-3 ${archived ? "opacity-50" : ""}`}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="font-medium text-gray-900 truncate" title={v.name}>{v.name}</div>
          <div className="text-[11px] text-gray-400">/l/{v.slug}</div>
          {v.styleAngle && <div className="text-[11px] text-indigo-600 mt-0.5">angle: {v.styleAngle}</div>}
        </div>
        <StatusBadge status={v.status} />
      </div>

      {archived && (
        <div className="text-xs rounded bg-red-50 border border-red-100 text-red-700 px-2 py-1">
          Rejected: {v.rejectReason ?? "—"}{v.rejectNote ? ` — ${v.rejectNote}` : ""}
        </div>
      )}

      <div className="space-y-3 text-sm">
        {blocks.map((b, bi) => (
          <BlockView key={b.id ?? bi} block={b} editing={editing && editable}
            onSlot={(k, val) => setSlot(bi, k, val)}
            onArrayItem={(k, idx, val) => setArrayItem(bi, k, idx, val)} />
        ))}
        {blocks.length === 0 && <p className="text-xs text-gray-400">No blocks.</p>}
      </div>

      {editing && editable && (
        <div className="flex gap-2 items-center">
          <select value={reason} onChange={(e) => setReason(e.target.value)} className="border rounded px-2 py-1 text-xs" title="why did you change it? (feeds RLHA)">
            {EDIT_REASONS.map((r) => <option key={r}>{r}</option>)}
          </select>
          <button onClick={save} disabled={busy} className="px-3 py-1 rounded bg-indigo-600 text-white text-xs disabled:opacity-50">Save</button>
          <button onClick={() => { setEditing(false); setBlocks(parseBlocks(v.blocks)); }} className="px-3 py-1 rounded border text-xs">Cancel</button>
        </div>
      )}

      {rejecting && !archived && (
        <div className="space-y-1 border-t border-gray-100 pt-2">
          <div className="flex gap-2">
            <select value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} className="border rounded px-2 py-1 text-xs">
              {REJECT_REASONS.map((r) => <option key={r}>{r}</option>)}
            </select>
            <input value={rejectNote} onChange={(e) => setRejectNote(e.target.value)} placeholder="note (optional, becomes a learning example)" className="border rounded px-2 py-1 text-xs flex-1" />
          </div>
          <div className="flex gap-2">
            <button onClick={reject} disabled={busy} className="px-3 py-1 rounded bg-red-600 text-white text-xs disabled:opacity-50">Confirm reject</button>
            <button onClick={() => setRejecting(false)} className="px-3 py-1 rounded border text-xs">Cancel</button>
          </div>
        </div>
      )}

      {!archived && (
        <div className="mt-auto flex flex-wrap gap-2 pt-2 border-t border-gray-100">
          <a href={`/l/preview/${v.id}`} target="_blank" rel="noopener noreferrer" className="px-3 py-1 rounded border text-xs text-gray-700 hover:bg-gray-50">Preview ↗</a>
          {editable && !editing && (
            <button onClick={() => setEditing(true)} className="px-3 py-1 rounded border text-xs text-gray-700 hover:bg-gray-50">Edit</button>
          )}
          {v.status === "DRAFT" && (
            <button onClick={choose} disabled={busy || editing} className="px-3 py-1 rounded bg-green-600 text-white text-xs disabled:opacity-50">Choose → review</button>
          )}
          {v.status === "REVIEW" && (
            <Link href={`/creatives/${v.id}`} className="px-3 py-1 rounded bg-green-600 text-white text-xs">Compliance & approve →</Link>
          )}
          {v.status === "ACTIVE" && (
            <a href={`/l/${v.slug}`} target="_blank" rel="noopener noreferrer" className="px-3 py-1 rounded border text-xs text-green-700">Live ↗</a>
          )}
          {(v.status === "DRAFT" || v.status === "REVIEW") && !rejecting && (
            <button onClick={() => setRejecting(true)} className="ml-auto px-3 py-1 rounded border border-red-200 text-xs text-red-600 hover:bg-red-50">Reject</button>
          )}
        </div>
      )}
    </div>
  );
}

// ── compact block rendering / editing ──────────────────────────────────────────

function BlockView({ block, editing, onSlot, onArrayItem }: {
  block: Block; editing: boolean;
  onSlot: (key: string, value: string) => void;
  onArrayItem: (key: string, idx: number, value: unknown) => void;
}) {
  const d = block.data ?? {};
  const slots = TEXT_SLOTS[block.type];
  const text = (k: string) => (d[k] == null ? "" : String(d[k]));

  // plain function, not a nested component: a nested component remounts per keystroke and loses focus
  const slot = (k: string, big?: boolean) =>
    editing ? (
      <textarea value={text(k)} onChange={(e) => onSlot(k, e.target.value)} placeholder={k}
        className={`w-full border rounded p-1 text-xs ${big ? "h-16" : "h-10"}`} />
    ) : text(k) ? (
      <p className={big ? "font-semibold text-gray-900" : "text-gray-700"}>{text(k)}</p>
    ) : null;

  return (
    <div className="rounded border border-gray-100 p-2 space-y-1">
      <div className="text-[10px] uppercase tracking-wide text-gray-400">{block.type}</div>
      {slots ? (
        <>
          {slots.map((k, i) => <div key={k}>{slot(k, i === 0 && block.type !== "PROBLEM")}</div>)}
          {block.type === "PROBLEM" && (
            <ul className="list-disc pl-4 space-y-0.5">
              {((d.pain_points as unknown[]) ?? []).map((p, i) => (
                <li key={i} className="text-gray-700">
                  {editing ? (
                    <input value={String(p ?? "")} onChange={(e) => onArrayItem("pain_points", i, e.target.value)} className="w-full border rounded px-1 text-xs" />
                  ) : String(p ?? "")}
                </li>
              ))}
            </ul>
          )}
          {block.type === "FAQ" && (
            <dl className="space-y-1">
              {(((d.items as { question?: string; answer?: string }[]) ?? [])).map((it, i) => (
                <div key={i}>
                  {editing ? (
                    <>
                      <input value={it?.question ?? ""} onChange={(e) => onArrayItem("items", i, { ...it, question: e.target.value })} className="w-full border rounded px-1 text-xs font-medium mb-0.5" placeholder="question" />
                      <textarea value={it?.answer ?? ""} onChange={(e) => onArrayItem("items", i, { ...it, answer: e.target.value })} className="w-full border rounded px-1 text-xs h-12" placeholder="answer" />
                    </>
                  ) : (
                    <>
                      <dt className="font-medium text-gray-800">{it?.question}</dt>
                      <dd className="text-gray-600 text-xs">{it?.answer}</dd>
                    </>
                  )}
                </div>
              ))}
            </dl>
          )}
        </>
      ) : (
        <p className="text-xs text-gray-500">{Object.keys(d).join(", ") || "no data"}</p>
      )}
    </div>
  );
}
