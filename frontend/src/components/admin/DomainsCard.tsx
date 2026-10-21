"use client";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/api";

/**
 * Roadmap Phase 10: landing domains. The landing is served under the tenant's own host name
 * (CNAME to the platform, or a self-hosted runner on the client's server); the admin stays on
 * the platform host. Verification = TXT record (+ CNAME for CNAME hosting).
 */
type Domain = {
  id: string; host: string; kind: "LANDING" | "COLLECT"; hosting: "CNAME" | "SELF_HOSTED";
  default_slug: string | null; status: "PENDING" | "VERIFIED" | "FAILED" | "DISABLED";
  verified_at: string | null; last_check_at: string | null; last_error: string | null;
  cname_target: string; txt_name: string; txt_value: string; created_at: string;
};

const STATUS_CLS: Record<string, string> = {
  VERIFIED: "bg-green-100 text-green-800",
  PENDING: "bg-yellow-100 text-yellow-800",
  FAILED: "bg-red-100 text-red-800",
  DISABLED: "bg-gray-100 text-gray-500",
};

export default function DomainsCard() {
  const [rows, setRows] = useState<Domain[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [issued, setIssued] = useState<{ id: string; key: string } | null>(null);
  const [form, setForm] = useState({ host: "", kind: "LANDING", hosting: "CNAME", default_slug: "" });

  async function load() {
    try { setRows(await apiFetch<Domain[]>("/api/v1/domains")); setError(""); }
    catch (e: any) { setError(e?.message ?? "load failed"); }
  }
  useEffect(() => { load(); }, []);

  async function add() {
    setBusy("add");
    try {
      await apiFetch("/api/v1/domains", {
        method: "POST",
        body: JSON.stringify({ ...form, host: form.host.trim().toLowerCase(), default_slug: form.default_slug || null }),
      });
      setForm({ ...form, host: "", default_slug: "" });
      await load();
    } catch (e: any) { setError(e?.message ?? "add failed"); }
    finally { setBusy(null); }
  }
  async function verify(d: Domain) {
    setBusy(d.id);
    try { await apiFetch(`/api/v1/domains/${d.id}/verify`, { method: "POST" }); await load(); }
    catch (e: any) { setError(e?.message ?? "verify failed"); }
    finally { setBusy(null); }
  }
  async function remove(d: Domain) {
    setBusy(d.id);
    try { await apiFetch(`/api/v1/domains/${d.id}`, { method: "DELETE" }); await load(); }
    catch (e: any) { setError(e?.message ?? "delete failed"); }
    finally { setBusy(null); }
  }
  async function issueKey(d: Domain) {
    setBusy(d.id);
    try {
      const r = await apiFetch<{ key: string }>(`/api/v1/domains/${d.id}/runner-key`, { method: "POST", body: JSON.stringify({ label: d.host }) });
      setIssued({ id: d.id, key: r.key });
      setOpen(d.id);
    } catch (e: any) { setError(e?.message ?? "key issue failed"); }
    finally { setBusy(null); }
  }

  return (
    <div className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
      <div>
        <h2 className="font-semibold text-gray-800">Landing domains</h2>
        <p className="text-xs text-gray-500 mt-1">
          Landings are served under your own host name; the admin stays on the platform host. Point a CNAME at the
          platform, or run the landing runner on your own server and issue it a key.
        </p>
      </div>
      {error && <div className="rounded bg-red-50 border border-red-200 p-2 text-xs text-red-700">{error}</div>}

      {rows.length === 0 ? (
        <p className="text-xs text-gray-400">No domains yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="text-xs w-full">
            <thead>
              <tr className="text-left text-gray-500">
                <th className="pr-2 py-1">host</th><th className="pr-2">kind</th><th className="pr-2">hosting</th>
                <th className="pr-2">status</th><th className="pr-2">last check</th><th></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((d) => (
                <DomainRow key={d.id} d={d} busy={busy === d.id} open={open === d.id}
                  issuedKey={issued?.id === d.id ? issued.key : null}
                  onToggle={() => setOpen(open === d.id ? null : d.id)}
                  onVerify={() => verify(d)} onDelete={() => remove(d)} onIssueKey={() => issueKey(d)} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="border-t border-gray-100 pt-3 space-y-2">
        <h3 className="text-xs font-semibold text-gray-700">Add a domain</h3>
        <div className="grid md:grid-cols-4 gap-2">
          <input value={form.host} onChange={(e) => setForm({ ...form, host: e.target.value })} placeholder="go.client.com" className="border rounded px-2 py-1.5 text-sm md:col-span-2" />
          <select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })} className="border rounded px-2 py-1.5 text-sm">
            <option value="LANDING">LANDING</option><option value="COLLECT">COLLECT (events)</option>
          </select>
          <select value={form.hosting} onChange={(e) => setForm({ ...form, hosting: e.target.value })} className="border rounded px-2 py-1.5 text-sm">
            <option value="CNAME">CNAME → platform</option><option value="SELF_HOSTED">Self-hosted runner</option>
          </select>
          <input value={form.default_slug} onChange={(e) => setForm({ ...form, default_slug: e.target.value.toLowerCase() })} placeholder="default slug for / (optional)" className="border rounded px-2 py-1.5 text-sm md:col-span-2" />
          <button onClick={add} disabled={busy === "add" || !form.host.trim()} className="px-3 py-1.5 rounded bg-indigo-600 text-white text-sm disabled:opacity-50">
            {busy === "add" ? "Adding…" : "Add domain"}
          </button>
        </div>
      </div>
    </div>
  );
}

function DomainRow({ d, busy, open, issuedKey, onToggle, onVerify, onDelete, onIssueKey }: {
  d: Domain; busy: boolean; open: boolean; issuedKey: string | null;
  onToggle: () => void; onVerify: () => void; onDelete: () => void; onIssueKey: () => void;
}) {
  const [confirmDelete, setConfirmDelete] = useState(false);
  return (
    <>
      <tr className="border-t border-gray-100 align-top">
        <td className="pr-2 py-1.5 font-medium text-gray-900">{d.host}{d.default_slug && <span className="text-gray-400 font-normal"> → /l/{d.default_slug}</span>}</td>
        <td className="pr-2 py-1.5">{d.kind}</td>
        <td className="pr-2 py-1.5">{d.hosting === "CNAME" ? "CNAME" : "Self-hosted"}</td>
        <td className="pr-2 py-1.5">
          <span className={`inline-block px-1.5 py-0.5 rounded text-[10px] font-medium ${STATUS_CLS[d.status] ?? ""}`}>{d.status}</span>
          {d.last_error && <div className="text-[10px] text-red-600 max-w-[16rem]">{d.last_error}</div>}
        </td>
        <td className="pr-2 py-1.5 text-gray-500 whitespace-nowrap">{d.last_check_at?.slice(0, 16) ?? "—"}</td>
        <td className="py-1.5">
          <div className="flex flex-wrap gap-1">
            <button onClick={onToggle} className="px-2 py-0.5 rounded border text-[11px]">{open ? "Hide" : "DNS"}</button>
            <button onClick={onVerify} disabled={busy} className="px-2 py-0.5 rounded border text-[11px] disabled:opacity-50">Verify</button>
            {d.hosting === "SELF_HOSTED" && (
              <button onClick={onIssueKey} disabled={busy} className="px-2 py-0.5 rounded border text-[11px] disabled:opacity-50">Issue runner key</button>
            )}
            {confirmDelete ? (
              <>
                <button onClick={onDelete} disabled={busy} className="px-2 py-0.5 rounded bg-red-600 text-white text-[11px]">Confirm</button>
                <button onClick={() => setConfirmDelete(false)} className="px-2 py-0.5 rounded border text-[11px]">Cancel</button>
              </>
            ) : (
              <button onClick={() => setConfirmDelete(true)} className="px-2 py-0.5 rounded border border-red-200 text-red-600 text-[11px]">Delete</button>
            )}
          </div>
        </td>
      </tr>
      {open && (
        <tr className="bg-gray-50">
          <td colSpan={6} className="p-3 space-y-2">
            <div className="text-[11px] text-gray-600">Add these DNS records at your registrar, then press Verify:</div>
            <pre className="text-[11px] bg-white border border-gray-200 rounded p-2 overflow-x-auto">
{d.hosting === "CNAME" ? `CNAME  ${d.host}  →  ${d.cname_target}\n` : ""}TXT    {d.txt_name}  =  "{d.txt_value}"
            </pre>
            {d.hosting === "SELF_HOSTED" && (
              <div className="space-y-1">
                <div className="text-[11px] text-gray-600">
                  Self-hosted: run the landing runner on your server behind HTTPS for <b>{d.host}</b>. It only talks outbound to the platform API.
                </div>
                {issuedKey ? (
                  <>
                    <div className="text-[11px] text-amber-700">Runner key — shown once, copy it now:</div>
                    <pre className="text-[11px] bg-white border border-amber-300 rounded p-2 overflow-x-auto select-all">{issuedKey}</pre>
                  </>
                ) : (
                  <div className="text-[11px] text-gray-500">Issue a runner key and put it into <code>RUNNER_KEY</code>.</div>
                )}
                <pre className="text-[11px] bg-white border border-gray-200 rounded p-2 overflow-x-auto">
{`# .env (see frontend/.env.runner.example)
PLATFORM_API_URL=https://api.<platform>/api/v1
RUNNER_KEY=${issuedKey ?? "<runner key>"}
docker compose -f docker-compose.runner.yml up -d --build`}
                </pre>
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  );
}
