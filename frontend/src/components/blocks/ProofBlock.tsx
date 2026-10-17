"use client";

export interface Testimonial {
  name: string;
  role?: string;
  company?: string;
  quote: string;
  avatar_url?: string;
  result?: string;
}

export interface ProofBlockData {
  heading?: string;
  testimonials: Testimonial[];
  background_color?: string;
  text_color?: string;
}

interface Props {
  blockId: string;
  data: ProofBlockData;
}

export default function ProofBlock({ blockId, data }: Props) {
  const bg = data.background_color ?? "#ffffff";
  const color = data.text_color ?? "#111827";

  return (
    <section
      data-block-id={blockId}
      data-block-type="PROOF"
      style={{ background: bg, color }}
      className="px-6 py-16"
    >
      <div className="max-w-5xl mx-auto">
        {data.heading && (
          <h2 className="text-3xl font-bold mb-10 text-center">{data.heading}</h2>
        )}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {data.testimonials.map((t, i) => (
            <div
              key={i}
              className="rounded-2xl border border-gray-200 p-6 shadow-sm"
            >
              {t.result && (
                <div className="text-2xl font-bold text-blue-600 mb-3">{t.result}</div>
              )}
              <blockquote className="text-base italic mb-4 opacity-80">
                &ldquo;{t.quote}&rdquo;
              </blockquote>
              <div className="flex items-center gap-3">
                {t.avatar_url && (
                  <img
                    src={t.avatar_url}
                    alt={t.name}
                    className="w-10 h-10 rounded-full object-cover"
                  />
                )}
                <div>
                  <div className="font-semibold">{t.name}</div>
                  {(t.role || t.company) && (
                    <div className="text-sm opacity-60">
                      {[t.role, t.company].filter(Boolean).join(", ")}
                    </div>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
