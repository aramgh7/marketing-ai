"use client";
import { useState } from "react";
import { API_BASE } from "@/lib/api";
import { Tracker } from "@/lib/tracker";

/**
 * Webinar registration for path C "TIERED_BY_TICKET" (< $50K → group webinar) and path B
 * (roadmap 8-14; vision §19.18.1). Registration is stored through the same public lead-form
 * endpoint as the CTA form (funnel_key = "webinar") and fires WEBINAR_REG, which a tenant's
 * funnel may declare as a stage.
 */
export interface WebinarRegBlockData {
  heading: string;
  subheading?: string;
  webinar_at?: string;       // ISO 8601
  duration_min?: number;
  cta_text?: string;
  show_timezone?: boolean;
  background_color?: string;
  text_color?: string;
}

interface Props {
  blockId: string;
  data: WebinarRegBlockData;
  tenantId: string;
  sessionId: string;
  variantId: string;
}

export default function WebinarRegBlock({ blockId, data, tenantId, sessionId, variantId }: Props) {
  const bg = data.background_color ?? "#1e3a8a";
  const color = data.text_color ?? "#ffffff";
  const [state, setState] = useState<"idle" | "sending" | "done" | "error">("idle");
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const when = data.webinar_at ? new Date(data.webinar_at) : null;

  const submit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const values: Record<string, string> = {};
    new FormData(form).forEach((v, k) => { values[k] = String(v); });
    setState("sending");
    Tracker.track("WEBINAR_REG", { webinar_at: data.webinar_at ?? null, browser_tz: tz }, { blockEventId: blockId, variantId });
    try {
      const res = await fetch(`${API_BASE}/public/form/submit`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        keepalive: true,
        body: JSON.stringify({
          tenant_id: tenantId,
          variant_id: variantId,
          session_id: sessionId,
          funnel_key: "webinar",
          first_name: values.name ?? null,
          email: values.email ?? null,
          phone: values.phone ?? null,
          consent_level: Tracker.consent(),
          webinar_at: data.webinar_at ?? null,
          browser_tz: tz,
          ...Tracker.utms(),
        }),
      });
      setState(res.ok ? "done" : "error");
    } catch {
      setState("error");
    }
  };

  return (
    <section
      data-block-id={blockId}
      data-block-type="webinar_reg"
      style={{ background: bg, color }}
      className="px-6 py-20 text-center"
    >
      <div className="max-w-xl mx-auto">
        <h2 className="text-3xl font-bold mb-3">{data.heading}</h2>
        {data.subheading && <p className="text-lg opacity-80 mb-6">{data.subheading}</p>}
        {when && !Number.isNaN(when.getTime()) && (
          <p className="text-sm font-semibold mb-6">
            {when.toLocaleString(undefined, { dateStyle: "full", timeStyle: "short" })}
            {data.duration_min ? ` · ${data.duration_min} min` : ""}
            {data.show_timezone !== false ? ` · ${tz}` : ""}
          </p>
        )}
        {state === "done" ? (
          <p className="text-lg font-semibold bg-white/10 rounded-xl py-4">You are registered — check your inbox for the link.</p>
        ) : (
          <form className="flex flex-col gap-3" onSubmit={submit}>
            <input name="name" type="text" placeholder="Your name" required
              className="px-4 py-3 rounded-xl text-gray-900 text-base border border-gray-300" />
            <input name="email" type="email" placeholder="Email" required
              className="px-4 py-3 rounded-xl text-gray-900 text-base border border-gray-300" />
            <input name="phone" type="tel" placeholder="Phone (optional)"
              className="px-4 py-3 rounded-xl text-gray-900 text-base border border-gray-300" />
            <button type="submit" disabled={state === "sending"}
              className="bg-white text-blue-800 font-bold text-lg px-8 py-4 rounded-xl shadow-lg hover:bg-gray-100 disabled:opacity-60">
              {data.cta_text ?? "Reserve my seat"}
            </button>
            {state === "error" && <p className="text-sm text-red-200">Something went wrong — please try again.</p>}
          </form>
        )}
      </div>
    </section>
  );
}
