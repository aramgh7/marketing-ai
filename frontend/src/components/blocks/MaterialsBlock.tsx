"use client";
import { useRef } from "react";
import { Tracker } from "@/lib/tracker";

/**
 * Path B "MATERIALS_FIRST" (roadmap 8-14; vision §19.18.1): founder video + round materials
 * shown before any call is offered. Every open fires MATERIAL_VIEW (first-party event, a
 * funnel stage for path B's engagement gate); the embedded video reports VIDEO_PROGRESS.
 */
export interface MaterialItem {
  id: string;
  title: string;
  url: string;
  kind?: "pdf" | "video" | "link" | "deck";
  description?: string;
}

export interface MaterialsBlockData {
  heading?: string;
  intro?: string;
  video_url?: string;        // https embed (YouTube/Vimeo/Wistia) — founder video
  video_title?: string;
  materials: MaterialItem[];
  background_color?: string;
  text_color?: string;
}

interface Props {
  blockId: string;
  data: MaterialsBlockData;
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

const KIND_ICON: Record<string, string> = { pdf: "📄", video: "🎬", deck: "📊", link: "🔗" };

export default function MaterialsBlock({ blockId, data }: Props) {
  const bg = data.background_color ?? "#ffffff";
  const color = data.text_color ?? "#111827";
  const videoUrl = safeHttpUrl(data.video_url);
  const videoOpened = useRef(false);

  const trackMaterial = (m: MaterialItem) => {
    Tracker.track("MATERIAL_VIEW", { material_key: m.id, kind: m.kind ?? "link", title: m.title },
      { blockEventId: blockId });
  };

  return (
    <section
      data-block-id={blockId}
      data-block-type="materials"
      style={{ background: bg, color }}
      className="px-6 py-16"
    >
      <div className="max-w-4xl mx-auto">
        {data.heading && <h2 className="text-3xl font-bold mb-4 text-center">{data.heading}</h2>}
        {data.intro && <p className="text-lg opacity-80 mb-8 text-center">{data.intro}</p>}

        {videoUrl && (
          <div className="mb-10">
            <div className="aspect-video w-full rounded-xl overflow-hidden shadow-lg">
              <iframe
                src={videoUrl}
                title={data.video_title ?? "Founder video"}
                className="w-full h-full border-0"
                allow="autoplay; encrypted-media; picture-in-picture"
                allowFullScreen
                onLoad={() => {
                  if (videoOpened.current) return;
                  videoOpened.current = true;
                  Tracker.track("MATERIAL_VIEW", { material_key: "founder_video", kind: "video" },
                    { blockEventId: blockId });
                }}
              />
            </div>
          </div>
        )}

        <ul className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {(data.materials ?? []).map((m) => {
            const href = safeHttpUrl(m.url);
            if (!href) return null;
            return (
              <li key={m.id} className="border border-gray-200 rounded-xl p-4 hover:shadow-md transition-shadow">
                <a
                  href={href}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={() => trackMaterial(m)}
                  className="flex items-start gap-3"
                >
                  <span className="text-2xl" aria-hidden>{KIND_ICON[m.kind ?? "link"] ?? "🔗"}</span>
                  <span>
                    <span className="block font-semibold">{m.title}</span>
                    {m.description && <span className="block text-sm opacity-70 mt-1">{m.description}</span>}
                  </span>
                </a>
              </li>
            );
          })}
        </ul>
      </div>
    </section>
  );
}
