"use client";

export interface ProblemBlockData {
  heading?: string;
  pain_points: string[];
  background_color?: string;
  text_color?: string;
}

interface Props {
  blockId: string;
  data: ProblemBlockData;
}

export default function ProblemBlock({ blockId, data }: Props) {
  const bg = data.background_color ?? "#f9fafb";
  const color = data.text_color ?? "#111827";

  return (
    <section
      data-block-id={blockId}
      data-block-type="PROBLEM"
      style={{ background: bg, color }}
      className="px-6 py-16"
    >
      <div className="max-w-3xl mx-auto">
        {data.heading && (
          <h2 className="text-3xl font-bold mb-8 text-center">{data.heading}</h2>
        )}
        <ul className="space-y-4">
          {data.pain_points.map((point, i) => (
            <li key={i} className="flex items-start gap-3">
              <span className="mt-1 flex-shrink-0 w-6 h-6 rounded-full bg-red-100 text-red-600 flex items-center justify-center text-sm font-bold">
                ✗
              </span>
              <span className="text-lg">{point}</span>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
