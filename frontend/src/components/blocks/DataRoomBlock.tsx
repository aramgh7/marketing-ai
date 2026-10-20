"use client";
import { useState } from "react";
import { Tracker } from "@/lib/tracker";

/**
 * Data-room-light for path B (roadmap 8-14; vision §19.18.1): round documents behind a light
 * gate. Documents flagged `requires_accreditation` are shown only after the visitor confirms
 * accredited-investor status (self-assertion wording — never a question, per META_PERSONAL_ATTR).
 * Every document open fires DATAROOM_OPEN (first-party event; path B engagement gate).
 */
export interface DataRoomDocument {
  id: string;
  title: string;
  url: string;
  requires_accreditation?: boolean;
  description?: string;
}

export interface DataRoomBlockData {
  heading?: string;
  intro?: string;
  documents: DataRoomDocument[];
  gate_text?: string;          // self-assertion sentence shown with the checkbox
  background_color?: string;
  text_color?: string;
}

interface Props {
  blockId: string;
  data: DataRoomBlockData;
}

function safeHttpUrl(url?: string): string | null {
  if (!url) return null;
  try {
    const u = new URL(url);
    return u.protocol === "https:" || u.protocol === "http:" ? u.toString() : null;
  } catch {
    return null;
  }
}

export default function DataRoomBlock({ blockId, data }: Props) {
  const bg = data.background_color ?? "#f9fafb";
  const color = data.text_color ?? "#111827";
  const [unlocked, setUnlocked] = useState(false);
  const gated = (data.documents ?? []).some((d) => d.requires_accreditation);

  const open = (d: DataRoomDocument) => {
    Tracker.track("DATAROOM_OPEN", { dataroom_id: d.id, title: d.title, gated: !!d.requires_accreditation },
      { blockEventId: blockId });
  };

  return (
    <section
      data-block-id={blockId}
      data-block-type="data_room"
      style={{ background: bg, color }}
      className="px-6 py-16"
    >
      <div className="max-w-3xl mx-auto">
        {data.heading && <h2 className="text-3xl font-bold mb-4 text-center">{data.heading}</h2>}
        {data.intro && <p className="text-lg opacity-80 mb-8 text-center">{data.intro}</p>}

        {gated && !unlocked && (
          <label className="flex items-start gap-3 cursor-pointer bg-white border border-gray-200 rounded-xl p-4 mb-6">
            <input
              type="checkbox"
              className="mt-1 h-4 w-4"
              onChange={(e) => {
                setUnlocked(e.target.checked);
                if (e.target.checked) {
                  Tracker.track("DATAROOM_OPEN", { dataroom_id: "__gate__", gate: "accreditation_confirmed" },
                    { blockEventId: blockId });
                }
              }}
            />
            <span className="text-sm">
              {data.gate_text ??
                "I confirm that I am an accredited investor as defined under SEC Rule 501(a) and wish to review the round materials."}
            </span>
          </label>
        )}

        <ul className="divide-y divide-gray-200 bg-white border border-gray-200 rounded-xl">
          {(data.documents ?? []).map((d) => {
            const href = safeHttpUrl(d.url);
            const locked = !!d.requires_accreditation && !unlocked;
            return (
              <li key={d.id} className="p-4 flex items-center justify-between gap-4">
                <div>
                  <div className="font-semibold">{d.title}</div>
                  {d.description && <div className="text-sm opacity-70">{d.description}</div>}
                </div>
                {locked || !href ? (
                  <span className="text-xs uppercase tracking-wide opacity-60">🔒 accredited investors</span>
                ) : (
                  <a
                    href={href}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={() => open(d)}
                    className="text-sm font-medium text-blue-700 hover:underline whitespace-nowrap"
                  >
                    Open →
                  </a>
                )}
              </li>
            );
          })}
        </ul>
      </div>
    </section>
  );
}
