"use client";
import { useEffect, useRef } from "react";

/** id of the lead <form>; qualifier blocks rendered elsewhere bind to it via the `form` attribute */
export const LEAD_FORM_ID = "mai-lead-form";

/** Audit F-17: honeypot input — hidden from people, filled in by bots; a non-empty value is never sent. */
export const HONEYPOT_FIELD = "website";

export type SubmitState =
  | { status: "idle" }
  | { status: "sending" }
  | { status: "done" }
  | { status: "error"; message: string };

export interface CTABlockData {
  heading: string;
  subheading?: string;
  cta_text: string;
  cta_url?: string;
  form_id?: string;
  urgency_text?: string;
  background_color?: string;
  text_color?: string;
  /** shown after a successful submit (default text otherwise) */
  success_text?: string;
}

interface Props {
  blockId: string;
  data: CTABlockData;
  onFormStart?: () => void;
  /** resolves to true when the lead was accepted (the form is then cleared) */
  onFormSubmit?: (values: Record<string, string>) => Promise<boolean> | boolean | void;
  submitState?: SubmitState;
}

export default function CTABlock({ blockId, data, onFormStart, onFormSubmit, submitState = { status: "idle" } }: Props) {
  const bg = data.background_color ?? "#1e40af";
  const color = data.text_color ?? "#ffffff";
  const formRef = useRef<HTMLFormElement>(null);
  const sending = submitState.status === "sending";

  // Clear the fields once the lead is accepted, so a second click cannot submit a duplicate.
  useEffect(() => {
    if (submitState.status === "done") formRef.current?.reset();
  }, [submitState.status]);

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (sending) return;
    const form = e.currentTarget;
    const values: Record<string, string> = {};
    new FormData(form).forEach((v, k) => {
      values[k] = String(v);
    });
    void onFormSubmit?.(values);
  };

  const inputCls =
    "px-4 py-3 rounded-xl text-gray-900 text-base border border-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-400 disabled:opacity-60";

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
          ref={formRef}
          className="flex flex-col gap-4 max-w-md mx-auto"
          onSubmit={handleSubmit}
          aria-busy={sending}
        >
          <input
            name="name"
            type="text"
            placeholder="Your name"
            required
            disabled={sending}
            onFocus={onFormStart}
            className={inputCls}
          />
          <input
            name="email"
            type="email"
            placeholder="Work email"
            required
            disabled={sending}
            className={inputCls}
          />
          <input
            name="phone"
            type="tel"
            placeholder="Phone (optional)"
            disabled={sending}
            className={inputCls}
          />
          {/* honeypot: off-screen, not focusable, ignored by autofill */}
          <div aria-hidden="true" style={{ position: "absolute", left: "-10000px", width: 1, height: 1, overflow: "hidden" }}>
            <label>
              Website
              <input name={HONEYPOT_FIELD} type="text" tabIndex={-1} autoComplete="off" defaultValue="" />
            </label>
          </div>
          <button
            type="submit"
            disabled={sending}
            className="bg-white text-blue-700 font-bold text-lg px-8 py-4 rounded-xl shadow-lg hover:bg-gray-100 transition-colors disabled:opacity-60"
          >
            {sending ? "Sending…" : data.cta_text}
          </button>
          {submitState.status === "done" && (
            <p role="status" className="text-sm font-medium bg-green-100 text-green-900 rounded-lg px-3 py-2">
              {data.success_text ?? "Thank you — we received your request and will be in touch shortly."}
            </p>
          )}
          {submitState.status === "error" && (
            <p role="alert" className="text-sm font-medium bg-red-100 text-red-900 rounded-lg px-3 py-2">
              {submitState.message}
            </p>
          )}
        </form>
      </div>
    </section>
  );
}
