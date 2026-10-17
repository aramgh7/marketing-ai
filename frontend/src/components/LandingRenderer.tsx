"use client";

import { useEffect, useRef, useState } from "react";
import { Tracker } from "@/lib/tracker";
import HeroBlock, { HeroBlockData } from "./blocks/HeroBlock";
import ProblemBlock, { ProblemBlockData } from "./blocks/ProblemBlock";
import ProofBlock, { ProofBlockData } from "./blocks/ProofBlock";
import PricingBlock, { PricingBlockData } from "./blocks/PricingBlock";
import CTABlock, { CTABlockData } from "./blocks/CTABlock";
import FAQBlock, { FAQBlockData } from "./blocks/FAQBlock";
import BookingCalendar from "./blocks/BookingCalendar";
import AccreditationCheckbox from "./blocks/AccreditationCheckbox";
import TicketSelector from "./blocks/TicketSelector";
import Disclaimer506c from "./blocks/Disclaimer506c";
import SocialProofInvestors from "./blocks/SocialProofInvestors";

export interface LandingBlock {
  type:
    | "HERO" | "PROBLEM" | "PROOF" | "PRICING" | "CTA" | "FAQ"
    | "booking_calendar" | "accreditation_checkbox" | "ticket_selector"
    | "disclaimer_506c" | "social_proof_investors";
  id: string;
  data: Record<string, unknown>;
}

interface Props {
  blocks: LandingBlock[];
  tenantId: string;
  sessionId: string;
  variantId: string;
  decisionId: string;
}

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

export default function LandingRenderer({
  blocks,
  tenantId,
  sessionId,
  variantId,
  decisionId,
}: Props) {
  const trackerInit = useRef(false);
  const mainRef = useRef<HTMLElement>(null);
  const [formData, setFormData] = useState<Record<string, unknown>>({});

  useEffect(() => {
    if (trackerInit.current) return;
    trackerInit.current = true;
    Tracker.init(tenantId, sessionId);
  }, [tenantId, sessionId]);

  // Fire BLOCK_VIEW when a section scrolls into view (IntersectionObserver)
  useEffect(() => {
    if (!mainRef.current) return;
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            const blockId = (entry.target as HTMLElement).dataset.blockId;
            if (blockId) {
              Tracker.track("BLOCK_VIEW", { block_id: blockId }, { blockEventId: blockId, variantId });
            }
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.3 }
    );
    mainRef.current.querySelectorAll("[data-block-id]").forEach((el) => observer.observe(el));
    return () => observer.disconnect();
  }, [variantId]);

  const trackCta = () =>
    Tracker.track("CLICK", { element: "cta" }, { variantId });

  const trackFormStart = () =>
    Tracker.track("FORM_START", {}, { variantId });

  const trackFormSubmit = async (values: Record<string, string>) => {
    Tracker.track("FORM_SUBMIT", { fields: Object.keys(values) }, { variantId });
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
  };

  return (
    <main ref={mainRef}>
      {blocks.map((block) => {
        switch (block.type) {
          case "HERO":
            return (
              <HeroBlock
                key={block.id}
                blockId={block.id}
                data={block.data as HeroBlockData}
                onCta={trackCta}
              />
            );
          case "PROBLEM":
            return (
              <ProblemBlock
                key={block.id}
                blockId={block.id}
                data={block.data as ProblemBlockData}
              />
            );
          case "PROOF":
            return (
              <ProofBlock
                key={block.id}
                blockId={block.id}
                data={block.data as ProofBlockData}
              />
            );
          case "PRICING":
            return (
              <PricingBlock
                key={block.id}
                blockId={block.id}
                data={block.data as PricingBlockData}
                onCta={trackCta}
              />
            );
          case "CTA":
            return (
              <CTABlock
                key={block.id}
                blockId={block.id}
                data={block.data as CTABlockData}
                onFormStart={trackFormStart}
                onFormSubmit={trackFormSubmit}
              />
            );
          case "FAQ":
            return (
              <FAQBlock
                key={block.id}
                blockId={block.id}
                data={block.data as FAQBlockData}
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
