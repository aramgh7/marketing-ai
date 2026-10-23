"use client";
import { safeCtaHref } from "@/lib/urls";

export interface PricingTier {
  name: string;
  price: string;
  period?: string;
  description?: string;
  features: string[];
  cta_text?: string;
  cta_url?: string;
  highlighted?: boolean;
}

export interface PricingBlockData {
  heading?: string;
  tiers: PricingTier[];
  guarantee_text?: string;
  background_color?: string;
  text_color?: string;
}

interface Props {
  blockId: string;
  data: PricingBlockData;
  onCta?: () => void;
}

export default function PricingBlock({ blockId, data, onCta }: Props) {
  const bg = data.background_color ?? "#f9fafb";
  const color = data.text_color ?? "#111827";

  return (
    <section
      data-block-id={blockId}
      data-block-type="PRICING"
      style={{ background: bg, color }}
      className="px-6 py-16"
    >
      <div className="max-w-5xl mx-auto">
        {data.heading && (
          <h2 className="text-3xl font-bold mb-10 text-center">{data.heading}</h2>
        )}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {data.tiers.map((tier, i) => (
            <div
              key={i}
              className={`rounded-2xl p-6 ${
                tier.highlighted
                  ? "bg-blue-600 text-white shadow-xl scale-105"
                  : "bg-white border border-gray-200 shadow-sm"
              }`}
            >
              <div className="font-semibold text-lg mb-1">{tier.name}</div>
              <div className="text-3xl font-bold mb-1">
                {tier.price}
                {tier.period && (
                  <span className="text-base font-normal opacity-70">/{tier.period}</span>
                )}
              </div>
              {tier.description && (
                <p className="text-sm mb-4 opacity-70">{tier.description}</p>
              )}
              <ul className="space-y-2 mb-6">
                {tier.features.map((f, j) => (
                  <li key={j} className="flex items-start gap-2 text-sm">
                    <span>✓</span>
                    <span>{f}</span>
                  </li>
                ))}
              </ul>
              {tier.cta_text && (
                <a
                  href={safeCtaHref(tier.cta_url)}
                  onClick={onCta}
                  className={`block text-center py-3 px-4 rounded-xl font-semibold transition-colors ${
                    tier.highlighted
                      ? "bg-white text-blue-600 hover:bg-gray-100"
                      : "bg-blue-600 text-white hover:bg-blue-700"
                  }`}
                >
                  {tier.cta_text}
                </a>
              )}
            </div>
          ))}
        </div>
        {data.guarantee_text && (
          <p className="text-center text-sm opacity-60 mt-6">{data.guarantee_text}</p>
        )}
      </div>
    </section>
  );
}
