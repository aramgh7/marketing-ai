"use client";
import { useCallback, useEffect, useState } from "react";
import { ApiError, FORBIDDEN_MESSAGE, apiFetch, isForbidden } from "@/lib/api";

/** GET /tenants/me/autonomy contract (roadmap 9-3 / 9-5). */
interface AutonomyState {
  level: "OBSERVER" | "ADVISOR" | "SEMI_AUTOPILOT" | "AUTOPILOT" | string;
  valid_levels: string[];
  ope: { verdict: "PASS" | "FAIL" | "INSUFFICIENT_DATA" | string; created_at: string; age_days: number } | null;
  can_upgrade: boolean;
  reason: string;
}

/** Latest OPE run row from GET /ope (OpeController). */
interface OpeRun {
  id?: string;
  verdict: string;
  message?: string;
  ips?: number | null;
  snips?: number | null;
  dr?: number | null;
  ci_low?: number | null;
  ci_high?: number | null;
  ess?: number | null;
  clipped_mass?: number | null;
  created_at?: string;
  n_decisions?: number;
  run_id?: string;
}

const LEVELS = ["OBSERVER", "ADVISOR", "SEMI_AUTOPILOT", "AUTOPILOT"];
const LEVEL_HELP: Record<string, string> = {
  OBSERVER: "Read-only — no Meta writes at all.",
  ADVISOR: "Recommendations only; every write needs a human (default).",
  SEMI_AUTOPILOT: "Pause / resume / scale within thresholds. Requires OPE PASS ≤ 7 days.",
  AUTOPILOT: "All permitted actions incl. campaign create and audience updates. Requires OPE PASS ≤ 7 days.",
};

function verdictClass(v: string | undefined): string {
  if (v === "PASS") return "bg-green-100 text-green-800";
  if (v === "FAIL") return "bg-red-100 text-red-800";
  return "bg-yellow-100 text-yellow-800";
}

const fmt = (n: number | null | undefined, d = 3) => (n == null ? "—" : Number(n).toFixed(d));

export default function AutonomyPage() {
  const [state, setState] = useState<AutonomyState | null>(null);
  const [ope, setOpe] = useState<OpeRun | null>(null);
  const [target, setTarget] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [a, o] = await Promise.all([
        apiFetch<AutonomyState>("/api/v1/tenants/me/autonomy"),
        apiFetch<OpeRun>("/api/v1/ope").catch(() => null),
      ]);
      setState(a);
      setTarget(a?.level ?? "ADVISOR");
      setOpe(o);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Load failed");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function apply() {
    if (!state || target === state.level) return;
    setSaving(true);
    setError(null);
    setInfo(null);
    try {
      const res = await apiFetch<{ level: string }>("/api/v1/tenants/me/autonomy", {
        method: "PUT",
        body: JSON.stringify({ level: target }),
      });
      setInfo(`Autonomy level is now ${res?.level ?? target}.`);
      load();
    } catch (err) {
      if (isForbidden(err)) {
        setError(`${FORBIDDEN_MESSAGE}: changing the autonomy level needs the ADMIN role.`);
      } else if (err instanceof ApiError && err.status === 409) {
        setError(err.message || "Upgrade blocked by the OPE gate.");
      } else {
        setError(err instanceof ApiError ? err.message : "Update failed");
      }
    } finally {
      setSaving(false);
    }
  }

  async function runOpe() {
    setRunning(true);
    setError(null);
    setInfo(null);
    try {
      const res = await apiFetch<OpeRun>("/api/v1/ope/evaluate", { method: "POST" });
      setOpe(res);
      setInfo(`OPE finished: ${res?.verdict ?? "?"}${res?.message ? ` — ${res.message}` : ""}`);
      load();
    } catch (err) {
      setError(isForbidden(err) ? `${FORBIDDEN_MESSAGE}: running OPE needs the ADMIN role.` : err instanceof ApiError ? err.message : "OPE run failed");
    } finally {
      setRunning(false);
    }
  }

  const idx = (l: string) => LEVELS.indexOf(l);
  const isUpgrade = state ? idx(target) > idx(state.level) : false;

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Autonomy</h1>
        <p className="text-sm text-gray-500 mt-1">
          OBSERVER → ADVISOR → SEMI_AUTOPILOT → AUTOPILOT. Going above ADVISOR requires an off-policy evaluation PASS not older than 7 days (vision §12.4–12.5).
        </p>
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}
      {info && <p className="text-sm text-green-700">{info}</p>}

      {loading || !state ? (
        <p className="text-sm text-gray-400">Loading…</p>
      ) : (
        <div className="grid grid-cols-2 gap-6">
          <div className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
            <h2 className="font-semibold text-gray-800">Current level</h2>
            <div className="text-3xl font-bold text-indigo-600">{state.level}</div>
            <p className="text-sm text-gray-600">{LEVEL_HELP[state.level] ?? ""}</p>
            <div className="text-sm">
              <span className="text-gray-500">Upgrade allowed: </span>
              <span className={state.can_upgrade ? "text-green-700 font-medium" : "text-red-600 font-medium"}>
                {state.can_upgrade ? "yes" : "no"}
              </span>
              {state.reason && <p className="text-xs text-gray-500 mt-1">{state.reason}</p>}
            </div>

            <div className="pt-2 space-y-2">
              <label className="text-sm block">
                <span className="text-gray-700">Set level</span>
                <select
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                  className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  {(state.valid_levels?.length ? state.valid_levels : LEVELS).map((l) => (
                    <option key={l} value={l}>
                      {l}
                    </option>
                  ))}
                </select>
              </label>
              {target !== state.level && (
                <p className="text-xs text-gray-500">{LEVEL_HELP[target] ?? ""}</p>
              )}
              <button
                type="button"
                onClick={apply}
                disabled={saving || target === state.level || (isUpgrade && idx(target) > idx("ADVISOR") && !state.can_upgrade)}
                className="bg-indigo-600 text-white text-sm font-medium rounded-lg px-4 py-2 hover:bg-indigo-700 disabled:opacity-50"
              >
                {saving ? "Applying…" : isUpgrade ? "Upgrade" : "Apply"}
              </button>
            </div>
          </div>

          <div className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
            <div className="flex items-center justify-between">
              <h2 className="font-semibold text-gray-800">Off-policy evaluation</h2>
              <button
                type="button"
                onClick={runOpe}
                disabled={running}
                className="bg-gray-800 text-white text-xs font-medium rounded-lg px-3 py-1.5 hover:bg-gray-900 disabled:opacity-50"
              >
                {running ? "Running…" : "Run OPE now"}
              </button>
            </div>
            <div className="flex items-center gap-3">
              <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${verdictClass(state.ope?.verdict ?? ope?.verdict)}`}>
                {state.ope?.verdict ?? ope?.verdict ?? "NO RUN"}
              </span>
              {state.ope && (
                <span className="text-xs text-gray-500">
                  {state.ope.age_days} day(s) old · {new Date(state.ope.created_at).toLocaleString()}
                </span>
              )}
            </div>
            {ope && ope.verdict !== "INSUFFICIENT_DATA" ? (
              <table className="text-sm w-full">
                <tbody>
                  {[
                    ["IPS", fmt(ope.ips)],
                    ["SNIPS", fmt(ope.snips)],
                    ["Doubly robust", fmt(ope.dr)],
                    ["95% CI", `${fmt(ope.ci_low)} – ${fmt(ope.ci_high)}`],
                    ["ESS", fmt(ope.ess, 0)],
                    ["Clipped mass", fmt(ope.clipped_mass)],
                    ["Decisions", ope.n_decisions != null ? String(ope.n_decisions) : "—"],
                  ].map(([k, v]) => (
                    <tr key={k} className="border-t border-gray-100">
                      <td className="py-1.5 text-gray-500">{k}</td>
                      <td className="py-1.5 text-right font-mono text-gray-800">{v}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="text-sm text-gray-400">{ope?.message ?? "No OPE run yet. Needs ≥ 10 logged decisions in the last 30 days."}</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
