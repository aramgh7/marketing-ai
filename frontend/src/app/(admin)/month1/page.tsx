"use client";
import { useEffect, useState } from "react";
import StatCard from "@/components/admin/StatCard";
import { apiFetch } from "@/lib/api";

// Audit 7.6: was a server component with no Authorization header -> every widget empty.
async function fetchJson(path: string): Promise<any> {
  try {
    return await apiFetch<any>(path);
  } catch {
    return null;
  }
}

export default function Month1Page() {
  const [data, setData] = useState<any[] | null>(null);

  useEffect(() => {
    Promise.all([
      fetchJson("/month1/pixel-protection"),
      fetchJson("/month1/cost-per-held-call"),
      fetchJson("/month1/noshow-rate"),
      fetchJson("/open-items"),
    ]).then(setData);
  }, []);

  if (!data) return <div className="p-8 text-gray-400">Loading Month-1 deliverables…</div>;
  const [pixelData, costData, noshowData, openItems] = data;

  const pp      = pixelData  ?? {};
  const cost    = costData   ?? {};
  const noshow  = noshowData ?? {};
  const oi      = openItems  ?? { total: 0, high_impact_open: 0, legal_blocked: 0 };

  const creatives: any[] = cost.creatives ?? [];
  const variants: any[]  = noshow.variants ?? [];
  const items: any[]     = openItems?.items ?? [];

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold text-white">Month-1 Deliverables</h1>
        <p className="text-gray-400 text-sm mt-1">
          Client #1 — InteliGel investor funnel (Reg D 506(c))
        </p>
      </div>

      {/* ── Widget 1: Pixel Protection ──────────────────────────────────────── */}
      <section>
        <h2 className="text-lg font-semibold text-white mb-3">Pixel Protection</h2>
        <p className="text-xs text-gray-500 mb-4">
          Leads NOT sent to the investor pixel this week (last 7 days).
        </p>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard
            label="Total Submissions"
            value={String(pp.total_submissions ?? "—")}
          />
          <StatCard
            label="Sent to Investor Pixel"
            value={String(pp.sent_to_investor_pixel ?? "—")}
          />
          <StatCard
            label="Protected"
            value={String(pp.protected_count ?? "—")}
            subLabel="non-accredited + low score"
          />
          <StatCard
            label="Protection Rate"
            value={pp.protection_rate_pct != null ? `${pp.protection_rate_pct}%` : "—"}
          />
        </div>
      </section>

      {/* ── Widget 2: Cost per CALL_HELD ────────────────────────────────────── */}
      <section>
        <h2 className="text-lg font-semibold text-white mb-3">
          Cost per CALL_HELD by Creative
        </h2>
        <p className="text-xs text-gray-500 mb-4">
          Last 30 days. Anchor = lowest cost in the set.
        </p>
        {creatives.length === 0 ? (
          <p className="text-gray-500 text-sm">No held-call data yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left text-gray-300">
              <thead className="text-xs uppercase text-gray-500 border-b border-gray-700">
                <tr>
                  <th className="py-2 pr-4">Creative</th>
                  <th className="py-2 pr-4 text-right">Held Calls</th>
                  <th className="py-2 pr-4 text-right">Cost / Held Call</th>
                  <th className="py-2 text-right">vs Anchor</th>
                </tr>
              </thead>
              <tbody>
                {creatives.map((c: any) => (
                  <tr key={c.creative_id} className="border-b border-gray-800">
                    <td className="py-2 pr-4 truncate max-w-xs">
                      {c.creative_name ?? c.creative_id?.slice(0, 8)}
                    </td>
                    <td className="py-2 pr-4 text-right">{c.held_calls}</td>
                    <td className="py-2 pr-4 text-right">
                      {c.cost_per_held_call != null ? `$${c.cost_per_held_call}` : "—"}
                    </td>
                    <td className={`py-2 text-right font-medium ${
                      c.vs_anchor_pct > 20  ? "text-red-400"
                    : c.vs_anchor_pct === 0 ? "text-green-400"
                    : "text-yellow-400"
                    }`}>
                      {c.vs_anchor_pct != null
                        ? (c.vs_anchor_pct === 0 ? "anchor" : `+${c.vs_anchor_pct}%`)
                        : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* ── Widget 3: No-show rate per follow-up variant ─────────────────────── */}
      <section>
        <h2 className="text-lg font-semibold text-white mb-3">
          No-Show Rate by Follow-up Variant
        </h2>
        <p className="text-xs text-gray-500 mb-4">Last 30 days.</p>
        {variants.length === 0 ? (
          <p className="text-gray-500 text-sm">No follow-up assignment data yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm text-left text-gray-300">
              <thead className="text-xs uppercase text-gray-500 border-b border-gray-700">
                <tr>
                  <th className="py-2 pr-4">Variant</th>
                  <th className="py-2 pr-4 text-right">Assigned</th>
                  <th className="py-2 pr-4 text-right">No-show %</th>
                  <th className="py-2 text-right">Days to Sig.</th>
                </tr>
              </thead>
              <tbody>
                {variants.map((v: any) => (
                  <tr key={v.variant_id} className="border-b border-gray-800">
                    <td className="py-2 pr-4">
                      {v.variant_name}
                      {v.is_control && (
                        <span className="ml-2 text-xs bg-blue-900 text-blue-300 px-1 rounded">
                          control
                        </span>
                      )}
                    </td>
                    <td className="py-2 pr-4 text-right">{v.assigned}</td>
                    <td className="py-2 pr-4 text-right">
                      {v.noshow_rate_pct != null ? `${v.noshow_rate_pct}%` : "—"}
                    </td>
                    <td className="py-2 text-right text-gray-400 text-xs">
                      {v.is_control
                        ? "—"
                        : v.days_to_significance_est === "already_sig"
                        ? <span className="text-green-400">significant</span>
                        : v.days_to_significance_est != null
                        ? `~${v.days_to_significance_est}d`
                        : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* ── Open Items ───────────────────────────────────────────────────────── */}
      <section>
        <h2 className="text-lg font-semibold text-white mb-1">Open Items</h2>
        <p className="text-xs text-gray-500 mb-4">
          {oi.total} items · {oi.high_impact_open} high-impact open
          {oi.legal_blocked > 0 && (
            <span className="ml-2 text-yellow-400">
              · {oi.legal_blocked} legal-blocked
            </span>
          )}
        </p>
        <div className="space-y-3">
          {items.map((item: any) => (
            <div
              key={item.id}
              className="bg-gray-900 rounded-lg p-4 border border-gray-800"
            >
              <div className="flex items-start justify-between gap-4">
                <div>
                  <span className="text-xs font-mono text-gray-500 mr-2">
                    {item.id}
                  </span>
                  <span
                    className={`text-xs px-1.5 py-0.5 rounded mr-2 ${
                      item.impact === "HIGH"
                        ? "bg-red-900 text-red-300"
                        : item.impact === "MEDIUM"
                        ? "bg-yellow-900 text-yellow-300"
                        : "bg-gray-700 text-gray-400"
                    }`}
                  >
                    {item.impact}
                  </span>
                  <span className="text-white font-medium">{item.title}</span>
                </div>
                <span className="text-xs text-gray-500 shrink-0">
                  {item.owner}
                </span>
              </div>
              <p className="text-gray-400 text-xs mt-2">{item.description}</p>
              {item.note && (
                <p className="text-yellow-400 text-xs mt-1 font-medium">
                  ⚠ {item.note}
                </p>
              )}
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
