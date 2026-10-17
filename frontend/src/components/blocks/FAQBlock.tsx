"use client";

import { useState } from "react";

export interface FAQItem {
  question: string;
  answer: string;
}

export interface FAQBlockData {
  heading?: string;
  items: FAQItem[];
  background_color?: string;
  text_color?: string;
}

interface Props {
  blockId: string;
  data: FAQBlockData;
}

export default function FAQBlock({ blockId, data }: Props) {
  const [open, setOpen] = useState<number | null>(null);
  const bg = data.background_color ?? "#ffffff";
  const color = data.text_color ?? "#111827";

  return (
    <section
      data-block-id={blockId}
      data-block-type="FAQ"
      style={{ background: bg, color }}
      className="px-6 py-16"
    >
      <div className="max-w-3xl mx-auto">
        {data.heading && (
          <h2 className="text-3xl font-bold mb-10 text-center">{data.heading}</h2>
        )}
        <div className="space-y-3">
          {data.items.map((item, i) => (
            <div
              key={i}
              className="border border-gray-200 rounded-xl overflow-hidden"
            >
              <button
                className="w-full flex items-center justify-between px-5 py-4 text-left font-semibold hover:bg-gray-50 transition-colors"
                onClick={() => setOpen(open === i ? null : i)}
                aria-expanded={open === i}
              >
                <span>{item.question}</span>
                <span className="text-xl text-gray-400">{open === i ? "−" : "+"}</span>
              </button>
              {open === i && (
                <div className="px-5 pb-5 text-base opacity-80">{item.answer}</div>
              )}
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
