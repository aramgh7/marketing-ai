"use client";

/** id of the lead <form>; qualifier blocks rendered elsewhere bind to it via the `form` attribute */
export const LEAD_FORM_ID = "mai-lead-form";

export interface CTABlockData {
  heading: string;
  subheading?: string;
  cta_text: string;
  cta_url?: string;
  form_id?: string;
  urgency_text?: string;
  background_color?: string;
  text_color?: string;
}

interface Props {
  blockId: string;
  data: CTABlockData;
  onFormStart?: () => void;
  onFormSubmit?: (values: Record<string, string>) => void;
}

export default function CTABlock({ blockId, data, onFormStart, onFormSubmit }: Props) {
  const bg = data.background_color ?? "#1e40af";
  const color = data.text_color ?? "#ffffff";

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    const values: Record<string, string> = {};
    new FormData(form).forEach((v, k) => {
      values[k] = String(v);
    });
    onFormSubmit?.(values);
  };

  return (
    <section
      id={data.form_id ?? "form"}
      data-block-id={blockId}
      data-block-type="CTA"
      style={{ background: bg, color }}
      className="px-6 py-20 text-center"
    >
      <div className="max-w-2xl mx-auto">
        <h2 className="text-3xl font-bold mb-4">{data.heading}</h2>
        {data.subheading && (
          <p className="text-lg mb-8 opacity-80">{data.subheading}</p>
        )}
        {data.urgency_text && (
          <p className="text-sm font-semibold bg-yellow-400 text-yellow-900 inline-block px-3 py-1 rounded-full mb-6">
            ⏰ {data.urgency_text}
          </p>
        )}
        <form
          id={LEAD_FORM_ID}
          className="flex flex-col gap-4 max-w-md mx-auto"
          onSubmit={handleSubmit}
        >
          <input
            name="name"
            type="text"
            placeholder="Your name"
            required
            onFocus={onFormStart}
            className="px-4 py-3 rounded-xl text-gray-900 text-base border border-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
          <input
            name="email"
            type="email"
            placeholder="Work email"
            required
            className="px-4 py-3 rounded-xl text-gray-900 text-base border border-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
          <input
            name="phone"
            type="tel"
            placeholder="Phone (optional)"
            className="px-4 py-3 rounded-xl text-gray-900 text-base border border-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
          <button
            type="submit"
            className="bg-white text-blue-700 font-bold text-lg px-8 py-4 rounded-xl shadow-lg hover:bg-gray-100 transition-colors"
          >
            {data.cta_text}
          </button>
        </form>
      </div>
    </section>
  );
}
