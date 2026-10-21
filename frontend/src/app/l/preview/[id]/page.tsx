"use client";
import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import AuthGuard from "@/components/admin/AuthGuard";
import LandingRenderer, { LandingBlock } from "@/components/LandingRenderer";
import { apiFetch } from "@/lib/api";

/**
 * Roadmap Phase 10: authenticated preview of any variant (DRAFT / REVIEW / ACTIVE).
 * The public /l/{slug} route serves ACTIVE variants only; this one reads the variant
 * through the admin API and renders it with tracking, form and rewards disabled.
 */
type Variant = { id: string; tenantId: string; name: string; status: string; blocks: string | LandingBlock[] };

function parseBlocks(raw: string | LandingBlock[] | undefined): LandingBlock[] {
  if (Array.isArray(raw)) return raw;
  try {
    return JSON.parse(raw ?? "[]");
  } catch {
    return [];
  }
}

export default function PreviewPage() {
  const { id } = useParams<{ id: string }>();
  const [variant, setVariant] = useState<Variant | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    apiFetch<Variant>(`/api/v1/landings/${id}`)
      .then(setVariant)
      .catch((e: any) => setError(e?.message ?? "Could not load variant"));
  }, [id]);

  return (
    <AuthGuard>
      <div
        className="fixed top-0 inset-x-0 z-50 bg-amber-400 text-amber-950 text-xs font-semibold tracking-wide text-center px-4 py-1.5"
        style={{ paddingTop: "calc(env(safe-area-inset-top, 0px) + 6px)" }}
      >
        PREVIEW · {variant?.status ?? "…"} · events are not recorded
        {variant && <span className="font-normal opacity-80"> · {variant.name}</span>}
      </div>
      <div style={{ paddingTop: "calc(env(safe-area-inset-top, 0px) + 28px)" }}>
        {error && <p className="text-red-600 text-sm p-8">{error}</p>}
        {!error && !variant && <p className="text-gray-400 text-sm p-8">Loading preview…</p>}
        {variant && (
          <LandingRenderer
            blocks={parseBlocks(variant.blocks)}
            tenantId={variant.tenantId ?? ""}
            sessionId=""
            variantId={variant.id}
            decisionId=""
            preview
          />
        )}
      </div>
    </AuthGuard>
  );
}
