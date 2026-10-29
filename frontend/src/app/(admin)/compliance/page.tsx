"use client";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";

/** ComplianceCheck entity rows from GET /compliance/{entityId} (roadmap 9-5). */
interface ComplianceCheck {
  id: string;
  entityType: string;
  entityId: string;
  checkType: string;
  result: string; // PASS | FAIL | WARN | …
  findings: string | Record<string, unknown> | unknown[] | null;
  modelVersion: string | null;
  overriddenBy: string | null;
  overrideReason: string | null;
  createdAt: string;
}

const ENTITY_TYPES = ["creative", "variant", "content", "offer"];
const RULEBASE_KEYS = ["META_FINANCIAL", "META_PERSONAL_ATTR", "SEC_506C", "CLIENT_LEGAL"];
const input =
  "w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500";

interface Rulebase {
  key: string;
  description: string | null;
  enabled: boolean;
}

interface ApprovedPhrase {
  id: string;
  rulebaseKey: string;
  phraseText: string;
  notes: string | null;
  createdAt: string;
}

function resultClass(r: string): string {
  if (r === "PASS") return "bg-green-100 text-green-800";
  if (r === "FAIL") return "bg-red-100 text-red-800";
  if (r === "WARN") return "bg-yellow-100 text-yellow-800";
  return "bg-gray-100 text-gray-700";
}

function findingsText(f: ComplianceCheck["findings"]): string {
  if (f == null) return "";
  if (typeof f === "string") {
    try {
      return JSON.stringify(JSON.parse(f), null, 1);
    } catch {
      return f;
    }
  }
  return JSON.stringify(f, null, 1);
}

export default function CompliancePage() {
  const [activeTab, setActiveTab] = useState<"checks" | "rules">("checks");

  // ── Rules tab state ────────────────────────────────────────────────────────
  const [rulebases, setRulebases] = useState<Rulebase[]>([]);
  const [phrases, setPhrases] = useState<ApprovedPhrase[]>([]);
  const [rulesLoading, setRulesLoading] = useState(false);
  const [phraseRulebase, setPhraseRulebase] = useState("META_FINANCIAL");
  const [phraseText, setPhraseText] = useState("");
  const [phraseNotes, setPhraseNotes] = useState("");
  const [addingPhrase, setAddingPhrase] = useState(false);

  const loadRules = useCallback(() => {
    setRulesLoading(true);
    Promise.all([
      apiFetch<Rulebase[]>("/api/v1/compliance/rulebases"),
      apiFetch<ApprovedPhrase[]>("/api/v1/compliance/approved-phrases"),
    ])
      .then(([rb, ph]) => {
        setRulebases(Array.isArray(rb) ? rb : []);
        setPhrases(Array.isArray(ph) ? ph : []);
      })
      .catch(() => {})
      .finally(() => setRulesLoading(false));
  }, []);

  useEffect(() => {
    if (activeTab === "rules") loadRules();
  }, [activeTab, loadRules]);

  async function addPhrase(e: FormEvent) {
    e.preventDefault();
    setAddingPhrase(true);
    try {
      await apiFetch("/api/v1/compliance/approved-phrases", {
        method: "POST",
        body: JSON.stringify({ rulebaseKey: phraseRulebase, phraseText: phraseText.trim(), notes: phraseNotes.trim() || null }),
      });
      setPhraseText(""); setPhraseNotes("");
      loadRules();
    } catch (err) {
      console.error(err);
    } finally {
      setAddingPhrase(false);
    }
  }

  // ── Checks tab state ───────────────────────────────────────────────────────
  const [entityId, setEntityId] = useState("");
  const [checks, setChecks] = useState<ComplianceCheck[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [overrideId, setOverrideId] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  // ad-hoc text check
  const [text, setText] = useState("");
  const [textEntityType, setTextEntityType] = useState("content");
  const [textEntityId, setTextEntityId] = useState("");
  const [checking, setChecking] = useState(false);
  const [lastCheck, setLastCheck] = useState<ComplianceCheck | null>(null);

  async function loadChecks(id = entityId) {
    if (!id.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const rows = await apiFetch<ComplianceCheck[]>(`/api/v1/compliance/${id.trim()}`);
      setChecks(Array.isArray(rows) ? rows : []);
    } catch (err) {
      setChecks(null);
      setError(err instanceof ApiError ? err.message : "Load failed");
    } finally {
      setLoading(false);
    }
  }

  async function submitOverride(id: string) {
    setError(null);
    try {
      await apiFetch(`/api/v1/compliance/${id}/override`, {
        method: "PUT",
        body: JSON.stringify({ reason }),
      });
      setOverrideId(null);
      setReason("");
      loadChecks();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Override failed");
    }
  }

  async function runTextCheck(e: FormEvent) {
    e.preventDefault();
    setChecking(true);
    setError(null);
    try {
      const id = textEntityId.trim() || crypto.randomUUID();
      const row = await apiFetch<ComplianceCheck>("/api/v1/compliance/check", {
        method: "POST",
        body: JSON.stringify({ entity_type: textEntityType, entity_id: id, text }),
      });
      setLastCheck(row);
      setEntityId(id);
      loadChecks(id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Check failed");
    } finally {
      setChecking(false);
    }
  }

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Compliance</h1>
        <p className="text-sm text-gray-500 mt-1">
          Pre-approval checks (Meta policy, claims vs facts, SEC 506(c) rulebases). Approve is blocked until the latest check is PASS or overridden with a reason.
        </p>
      </div>

      {/* Tab switcher */}
      <div className="flex gap-1 border-b border-gray-200">
        {(["checks", "rules"] as const).map((t) => (
          <button
            key={t}
            onClick={() => setActiveTab(t)}
            className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${activeTab === t ? "border-indigo-600 text-indigo-600" : "border-transparent text-gray-500 hover:text-gray-700"}`}
          >
            {t === "checks" ? "Checks" : "Rulebases & Phrases"}
          </button>
        ))}
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {/* ── Rules tab ───────────────────────────────────────────────────────── */}
      {activeTab === "rules" && (
        <div className="space-y-6">
          {rulesLoading ? <p className="text-sm text-gray-400">Loading…</p> : (
            <>
              {/* Rulebases */}
              <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                <div className="px-6 py-4 border-b border-gray-100">
                  <h2 className="font-semibold text-gray-800">Rulebases</h2>
                </div>
                {rulebases.length === 0 ? (
                  <p className="px-6 py-4 text-sm text-gray-400">No rulebases found.</p>
                ) : (
                  <table className="w-full text-sm">
                    <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
                      <tr>
                        <th className="px-6 py-3 text-left">Key</th>
                        <th className="px-6 py-3 text-left">Description</th>
                        <th className="px-6 py-3 text-left">Status</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rulebases.map((rb) => (
                        <tr key={rb.key} className="border-t border-gray-100">
                          <td className="px-6 py-3 font-mono text-sm text-gray-800">{rb.key}</td>
                          <td className="px-6 py-3 text-gray-600">{rb.description ?? "—"}</td>
                          <td className="px-6 py-3">
                            <span className={`px-2 py-0.5 rounded text-xs font-medium ${rb.enabled ? "bg-green-100 text-green-700" : "bg-gray-100 text-gray-500"}`}>
                              {rb.enabled ? "Active" : "Disabled"}
                            </span>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>

              {/* Approved phrases */}
              <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
                <div className="px-6 py-4 border-b border-gray-100">
                  <h2 className="font-semibold text-gray-800">Approved phrases</h2>
                  <p className="text-xs text-gray-400 mt-0.5">Pre-approved formulations that pass compliance checks automatically.</p>
                </div>
                <form onSubmit={addPhrase} className="px-6 py-4 border-b border-gray-100 grid grid-cols-3 gap-3 bg-gray-50">
                  <label className="text-sm">
                    <span className="text-gray-700">Rulebase</span>
                    <select value={phraseRulebase} onChange={(e) => setPhraseRulebase(e.target.value)} className={`mt-1 ${input}`}>
                      {RULEBASE_KEYS.map((k) => <option key={k} value={k}>{k}</option>)}
                    </select>
                  </label>
                  <label className="text-sm">
                    <span className="text-gray-700">Phrase text</span>
                    <textarea required rows={1} value={phraseText} onChange={(e) => setPhraseText(e.target.value)} className={`mt-1 ${input}`} />
                  </label>
                  <label className="text-sm">
                    <span className="text-gray-700">Notes</span>
                    <input value={phraseNotes} onChange={(e) => setPhraseNotes(e.target.value)} className={`mt-1 ${input}`} placeholder="Why this is approved" />
                  </label>
                  <div className="col-span-3 text-right">
                    <button type="submit" disabled={addingPhrase} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
                      {addingPhrase ? "Saving…" : "Add phrase"}
                    </button>
                  </div>
                </form>
                {phrases.length === 0 ? (
                  <p className="px-6 py-4 text-sm text-gray-400">No approved phrases yet.</p>
                ) : (
                  <table className="w-full text-sm">
                    <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
                      <tr>
                        <th className="px-6 py-3 text-left">Phrase</th>
                        <th className="px-6 py-3 text-left">Rulebase</th>
                        <th className="px-6 py-3 text-left">Notes</th>
                        <th className="px-6 py-3 text-left">Added</th>
                      </tr>
                    </thead>
                    <tbody>
                      {phrases.map((p) => (
                        <tr key={p.id} className="border-t border-gray-100">
                          <td className="px-6 py-3 text-gray-800 max-w-sm">{p.phraseText}</td>
                          <td className="px-6 py-3"><span className="px-2 py-0.5 bg-indigo-50 text-indigo-700 text-xs rounded font-mono">{p.rulebaseKey}</span></td>
                          <td className="px-6 py-3 text-gray-500 text-xs">{p.notes ?? "—"}</td>
                          <td className="px-6 py-3 text-gray-400 whitespace-nowrap">{new Date(p.createdAt).toLocaleDateString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </>
          )}
        </div>
      )}

      {/* ── Checks tab ──────────────────────────────────────────────────────── */}
      {activeTab === "checks" && (<>

      <form onSubmit={runTextCheck} className="bg-white rounded-xl border border-gray-200 p-6 space-y-4 max-w-3xl">
        <h2 className="font-semibold text-gray-800">Check a text</h2>
        <div className="grid grid-cols-2 gap-4">
          <label className="text-sm">
            <span className="text-gray-700">Entity type</span>
            <select value={textEntityType} onChange={(e) => setTextEntityType(e.target.value)} className={input}>
              {ENTITY_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm">
            <span className="text-gray-700">Entity id (UUID, optional — generated if empty)</span>
            <input value={textEntityId} onChange={(e) => setTextEntityId(e.target.value)} className={input} />
          </label>
        </div>
        <label className="text-sm block">
          <span className="text-gray-700">Text</span>
          <textarea required rows={4} value={text} onChange={(e) => setText(e.target.value)} className={input} />
        </label>
        <div className="flex items-center gap-4">
          <button type="submit" disabled={checking} className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50">
            {checking ? "Checking…" : "Run check"}
          </button>
          {lastCheck && (
            <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${resultClass(lastCheck.result)}`}>
              {lastCheck.result}
            </span>
          )}
        </div>
      </form>

      <div className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
        <div className="flex items-end gap-3 max-w-3xl">
          <label className="text-sm flex-1">
            <span className="text-gray-700">Entity id</span>
            <input value={entityId} onChange={(e) => setEntityId(e.target.value)} className={input} placeholder="creative / variant / content UUID" />
          </label>
          <button type="button" onClick={() => loadChecks()} disabled={loading || !entityId.trim()} className="bg-gray-800 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-gray-900 disabled:opacity-50">
            {loading ? "Loading…" : "Load checks"}
          </button>
        </div>

        {checks && checks.length === 0 && <p className="text-sm text-gray-400">No checks for this entity.</p>}
        {checks && checks.length > 0 && (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase">
              <tr>
                <th className="px-4 py-3 text-left">When</th>
                <th className="px-4 py-3 text-left">Check</th>
                <th className="px-4 py-3 text-left">Result</th>
                <th className="px-4 py-3 text-left">Findings</th>
                <th className="px-4 py-3 text-left">Override</th>
              </tr>
            </thead>
            <tbody>
              {checks.map((c) => (
                <tr key={c.id} className="border-t border-gray-100 align-top">
                  <td className="px-4 py-3 text-gray-500 whitespace-nowrap">{new Date(c.createdAt).toLocaleString()}</td>
                  <td className="px-4 py-3">
                    <div className="font-medium text-gray-800">{c.checkType}</div>
                    <div className="text-xs text-gray-400">{c.entityType} · {c.modelVersion ?? "—"}</div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${resultClass(c.result)}`}>{c.result}</span>
                  </td>
                  <td className="px-4 py-3">
                    <pre className="text-xs text-gray-600 whitespace-pre-wrap max-w-md max-h-40 overflow-auto">{findingsText(c.findings)}</pre>
                  </td>
                  <td className="px-4 py-3">
                    {c.overriddenBy ? (
                      <div className="text-xs text-gray-600">
                        <span className="text-indigo-600 font-medium">overridden</span>
                        <div>{c.overrideReason}</div>
                      </div>
                    ) : overrideId === c.id ? (
                      <div className="space-y-2">
                        <input value={reason} onChange={(e) => setReason(e.target.value)} className={input} placeholder="reason (required)" />
                        <div className="space-x-2">
                          <button onClick={() => submitOverride(c.id)} disabled={!reason.trim()} className="text-indigo-600 hover:underline disabled:opacity-50">Confirm</button>
                          <button onClick={() => { setOverrideId(null); setReason(""); }} className="text-gray-500 hover:underline">Cancel</button>
                        </div>
                      </div>
                    ) : c.result !== "PASS" ? (
                      <button onClick={() => setOverrideId(c.id)} className="text-indigo-600 hover:underline">Override…</button>
                    ) : (
                      <span className="text-gray-400">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      </>)}
    </div>
  );
}
