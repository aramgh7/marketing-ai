"use client";
import { safeCtaHref } from "@/lib/urls";

export interface HeroBlockData {
  headline: string;
  subheadline?: string;
  cta_text?: string;
  cta_url?: string;
  background_color?: string;
  text_color?: string;
  image_url?: string;
}

interface Props {
  blockId: string;
  data: HeroBlockData;
  onCta?: () => void;
}

export default function HeroBlock({ blockId, data, onCta }: Props) {
  const bg = data.background_color ?? "#ffffff";
  const color = data.text_color ?? "#111827";

  return (
    <section
      data-block-id={blockId}
      data-block-type="HERO"
      style={{ background: bg, color }}
      className="flex flex-col items-center justify-center px-6 py-20 text-center min-h-[60vh]"
    >
      {data.image_url && (
        <img
          src={data.image_url}
          alt=""
          className="mb-8 max-h-64 object-contain"
        />
      )}
      <h1 className="text-4xl font-bold tracking-tight max-w-3xl mb-4">
        {data.headline}
      </h1>
      {data.subheadline && (
        <p className="text-xl max-w-2xl mb-8 opacity-80">{data.subheadline}</p>
      )}
      {data.cta_text && (
        <a
          href={safeCtaHref(data.cta_url)}
          onClick={onCta}
          className="inline-block bg-blue-600 text-white text-lg font-semibold px-8 py-4 rounded-xl shadow-lg hover:bg-blue-700 transition-colors"
        >
          {data.cta_text}
        </a>
      )}
    </section>
  );
}
