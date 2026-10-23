"use client";

import { useEffect, useRef, useState } from "react";
import { Tracker } from "@/lib/tracker";
import { getRecaptchaToken } from "@/lib/recaptcha";
import HeroBlock, { HeroBlockData } from "./blocks/HeroBlock";
import ProblemBlock, { ProblemBlockData } from "./blocks/ProblemBlock";
import ProofBlock, { ProofBlockData } from "./blocks/ProofBlock";
import PricingBlock, { PricingBlockData } from "./blocks/PricingBlock";
import CTABlock, { CTABlockData, LEAD_FORM_ID, HONEYPOT_FIELD, type SubmitState } from "./blocks/CTABlock";
import FAQBlock, { FAQBlockData } from "./blocks/FAQBlock";
import BookingCalendar from "./blocks/BookingCalendar";
import AccreditationCheckbox from "./blocks/AccreditationCheckbox";
import TicketSelector from "./blocks/TicketSelector";
import Disclaimer506c from "./blocks/Disclaimer506c";
import SocialProofInvestors from "./blocks/SocialProofInvestors";
import MaterialsBlock, { MaterialsBlockData } from "./blocks/MaterialsBlock";
import DataRoomBlock, { DataRoomBlockData } from "./blocks/DataRoomBlock";
import WebinarRegBlock, { WebinarRegBlockData } from "./blocks/WebinarRegBlock";

export interface LandingBlock {
  type:
    | "HERO" | "PROBLEM" | "PROOF" | "PRICING" | "CTA" | "FAQ"
    | "booking_calendar" | "accreditation_checkbox" | "ticket_selector"
    | "disclaimer_506c" | "social_proof_investors"
    | "materials" | "data_room" | "webinar_reg";   // roadmap 8-14 (paths B / C)
  id: string;
  data: Record<string, unknown>;
}

interface Props {
  blocks: LandingBlock[];
  tenantId: string;
  sessionId: string;
  variantId: string;
  decisionId: string;
  /** Roadmap Phase 10: admin preview of DRAFT/REVIEW variants — no tracking, no form/reward POSTs. */
  preview?: boolean;
}

import { API_BASE } from "@/lib/api";

export default function LandingRenderer({
  blocks,
  tenantId,
  sessionId,
  variantId,
  decisionId,
  preview = false,
}: Props) {
  const trackerInit = useRef(false);
  const mainRef = useRef<HTMLElement>(null);
  const [formData, setFormData] = useState<Record<string, unknown>>({});
  const [previewNote, setPreviewNote] = useState(false);
  const [submitState, setSubmitState] = useState<SubmitState>({ status: "idle" });

  useEffect(() => {
    if (preview || trackerInit.current) return;
    trackerInit.current = true;
    Tracker.init(tenantId, sessionId);
  }, [tenantId, sessionId, preview]);

  // Fire BLOCK_VIEW when a section scrolls into view (IntersectionObserver)
  useEffect(() => {
    if (preview || !mainRef.current) return;
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            const blockId = (entry.target as HTMLElement).dataset.blockId;
            // Audit 7.9: only stop observing once the view was actually recorded; before consent
            // the event is dropped and the block must be re-reported later.
            const sent = blockId
              ? Tracker.track("BLOCK_VIEW", { block_id: blockId }, { blockEventId: blockId, variantId })
              : true;
            if (sent) observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.3 }
    );
    mainRef.current.querySelectorAll("[data-block-id]").forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [variantId, preview]);

  const trackCta = () => {
    if (!preview) Tracker.track("CLICK", { element: "cta" }, { variantId });
  };

  const trackFormStart = () => {
    if (!preview) Tracker.track("FORM_START", {}, { variantId });
  };

  /**
   * Audit F-17: the lead form reports sending / sent / error, clears itself after success (no
   * duplicate on a second click), drops honeypot submissions silently and attaches the reCAPTCHA
   * token (body `recaptchaToken` + `X-Recaptcha-Token` header, which RecaptchaFilter verifies).
   */
  const trackFormSubmit = async (values: Record<string, string>): Promise<boolean> => {
    if (preview) {
      setPreviewNote(true);
      return false;
    }
    if (submitState.status === "sending") return false;
    // Honeypot: a hidden field no human fills in. Pretend success, send nothing.
    if ((values[HONEYPOT_FIELD] ?? "").trim() !== "") {
      setSubmitState({ status: "done" });
      return true;
    }
    setSubmitState({ status: "sending" });
    Tracker.track("FORM_SUBMIT", { fields: Object.keys(values).filter((k) => k !== HONEYPOT_FIELD) }, { variantId });

    const recaptchaToken = await getRecaptchaToken("lead_form");

    // Audit 4.9: the lead data itself was never sent anywhere. Qualifier controls
    // (accreditation, ticket) are bound to the form via the `form` attribute, so they are in
    // `values`; formData keeps the React-side copy as a fallback.
    const submission = {
      tenant_id: tenantId,
      variant_id: variantId,
      session_id: sessionId,
      decision_id: decisionId || null,
      first_name: values.name ?? values.first_name ?? null,
      email: values.email ?? null,
      phone: values.phone ?? null,
      self_accredited:
        values.self_accredited !== undefined
          ? values.self_accredited === "on" || values.self_accredited === "true"
          : (formData.self_accredited as boolean | undefined) ?? null,
      ticket_bucket: values.ticket_bucket || (formData.ticket_bucket as string | undefined) || null,
      consent_level: Tracker.consent(),
      ...Tracker.utms(),
      ...(recaptchaToken ? { recaptchaToken } : {}),
    };
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    // The header is what RecaptchaFilter reads. Cross-origin (tenant domain / runner) the platform's
    // CORS allow-list must include X-Recaptcha-Token, otherwise the preflight — and the lead — fails;
    // until it does, send the header on the same origin only (the token still travels in the body).
    if (recaptchaToken && sameOrigin(API_BASE)) headers["X-Recaptcha-Token"] = recaptchaToken;
    let ok = false;
    try {
      const res = await fetch(`${API_BASE}/public/form/submit`, {
        method: "POST",
        headers,
        body: JSON.stringify(submission),
        keepalive: true,
      });
      ok = res.ok;
      if (!ok) {
        setSubmitState({ status: "error", message: await errorText(res) });
      }
    } catch {
      setSubmitState({ status: "error", message: "Network error — please try again." });
    }
    if (!ok) return false;
    setSubmitState({ status: "done" });
    if (decisionId) {
      try {
        await fetch(`${API_BASE}/public/reward`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ decisionId, rewardType: "FORM_SUBMIT" }),
          keepalive: true,
        });
      } catch {
        // Reward failure must never crash the landing page
      }
    }
    return true;
  };

  return (
    <main ref={mainRef}>
      {preview && previewNote && (
        <p
          role="status"
          className="fixed bottom-4 left-1/2 -translate-x-1/2 z-50 rounded-lg bg-amber-100 border border-amber-300 text-amber-900 text-sm px-4 py-2 shadow"
        >
          Preview mode — the form is disabled, nothing was sent.
        </p>
      )}
      {blocks.map((block) => {
        switch (block.type) {
          case "HERO":
            return (
              <HeroBlock
                key={block.id}
                blockId={block.id}
                data={block.data as unknown as HeroBlockData}
                onCta={trackCta}
              />
            );
          case "PROBLEM":
            return (
              <ProblemBlock
                key={block.id}
                blockId={block.id}
                data={block.data as unknown as ProblemBlockData}
              />
            );
          case "PROOF":
            return (
              <ProofBlock
                key={block.id}
                blockId={block.id}
                data={block.data as unknown as ProofBlockData}
              />
            );
          case "PRICING":
            return (
              <PricingBlock
                key={block.id}
                blockId={block.id}
                data={block.data as unknown as PricingBlockData}
                onCta={trackCta}
              />
            );
          case "CTA":
            return (
              <CTABlock
                key={block.id}
                blockId={block.id}
                data={block.data as unknown as CTABlockData}
                onFormStart={trackFormStart}
                onFormSubmit={trackFormSubmit}
                submitState={submitState}
              />
            );
          case "FAQ":
            return (
              <FAQBlock
                key={block.id}
                blockId={block.id}
                data={block.data as unknown as FAQBlockData}
              />
            );
          case "booking_calendar":
            return (
              <div key={block.id} data-block-id={block.id}>
                <BookingCalendar
                  calendarUrl={(block.data.calendar_url as string) ?? ""}
                  showTimezone={(block.data.show_timezone as boolean) ?? true}
                />
              </div>
            );
          case "accreditation_checkbox":
            return (
              <div key={block.id} data-block-id={block.id}>
                <AccreditationCheckbox
                  formId={LEAD_FORM_ID}
                  required={(block.data.required as boolean) ?? false}
                  labelText={block.data.label_text as string | undefined}
                  onChange={(checked) =>
                    setFormData((prev) => ({ ...prev, self_accredited: checked }))
                  }
                />
              </div>
            );
          case "ticket_selector":
            return (
              <div key={block.id} data-block-id={block.id}>
                <TicketSelector
                  formId={LEAD_FORM_ID}
                  label={block.data.label as string | undefined}
                  required={(block.data.required as boolean) ?? false}
                  onChange={(bucket) =>
                    setFormData((prev) => ({ ...prev, ticket_bucket: bucket }))
                  }
                />
              </div>
            );
          case "disclaimer_506c":
            return (
              <div key={block.id} data-block-id={block.id}>
                <Disclaimer506c
                  text={block.data.text as string | undefined}
                  version={block.data.version as string | undefined}
                />
              </div>
            );
          case "materials":
            return (
              <MaterialsBlock
                key={block.id}
                blockId={block.id}
                data={block.data as unknown as MaterialsBlockData}
              />
            );
          case "data_room":
            return (
              <DataRoomBlock
                key={block.id}
                blockId={block.id}
                data={block.data as unknown as DataRoomBlockData}
              />
            );
          case "webinar_reg":
            return (
              <WebinarRegBlock
                key={block.id}
                blockId={block.id}
                data={block.data as unknown as WebinarRegBlockData}
                tenantId={tenantId}
                sessionId={sessionId}
                variantId={variantId}
              />
            );
          case "social_proof_investors":
            return (
              <div key={block.id} data-block-id={block.id}>
                <SocialProofInvestors
                  quotes={(block.data.quotes as Array<{ text: string; author?: string; amount?: string }>) ?? []}
                  showAmounts={(block.data.show_amounts as boolean) ?? false}
                />
              </div>
            );
          default:
            return null;
        }
      })}
    </main>
  );
}

function sameOrigin(apiBase: string): boolean {
  if (typeof window === "undefined") return false;
  try {
    return new URL(apiBase, window.location.href).origin === window.location.origin;
  } catch {
    return false;
  }
}

/** Human-readable reason from an error response (`detail` → `message` → `error` → status). */
async function errorText(res: Response): Promise<string> {
  try {
    const body = await res.json();
    for (const k of ["detail", "message", "error"]) {
      if (typeof body?.[k] === "string" && body[k].trim()) return body[k];
    }
  } catch {
    // not JSON
  }
  return res.status === 429 ? "Too many requests — please try again in a minute." : `Submission failed (${res.status}).`;
}
