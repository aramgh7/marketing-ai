"use client";

import { useEffect, useRef, useState } from "react";
import { Tracker } from "@/lib/tracker";
import HeroBlock, { HeroBlockData } from "./blocks/HeroBlock";
import ProblemBlock, { ProblemBlockData } from "./blocks/ProblemBlock";
import ProofBlock, { ProofBlockData } from "./blocks/ProofBlock";
import PricingBlock, { PricingBlockData } from "./blocks/PricingBlock";
import CTABlock, { CTABlockData, LEAD_FORM_ID } from "./blocks/CTABlock";
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

  const trackFormSubmit = async (values: Record<string, string>) => {
    if (preview) {
      setPreviewNote(true);
      return;
    }
    Tracker.track("FORM_SUBMIT", { fields: Object.keys(values) }, { variantId });

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
    };
    try {
      await fetch(`${API_BASE}/public/form/submit`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(submission),
        keepalive: true,
      });
    } catch {
      // Submission failure is logged server-side via CRM; never crash the landing page
    }
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
