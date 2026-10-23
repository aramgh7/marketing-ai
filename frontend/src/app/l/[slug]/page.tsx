import { cookies, headers } from "next/headers";
import { notFound } from "next/navigation";
import LandingRenderer, { LandingBlock } from "@/components/LandingRenderer";
import { requestHost, tenantHostFor } from "@/lib/hosts";

// Server-side: use internal Docker network URL if available (avoids loopback in containers)
const API_BASE =
  process.env.API_INTERNAL_URL ??
  process.env.NEXT_PUBLIC_API_URL ??
  "http://localhost:8080/api/v1";

interface VariantData {
  id: string;
  tenantId: string;
  slug: string;
  name: string;
  blocks: string | LandingBlock[]; // @JsonRawValue -> already an array; older payloads may be a string
}

/** Roadmap Phase 10: on a tenant's custom domain the slug is scoped to that tenant. */
async function fetchVariant(slug: string): Promise<VariantData | null> {
  const tenantHost = tenantHostFor(requestHost(await headers()));
  const q = tenantHost ? `?host=${encodeURIComponent(tenantHost)}` : "";
  try {
    const res = await fetch(`${API_BASE}/public/l/${slug}${q}`, {
      next: { revalidate: 60 }, // ISR: revalidate every 60s
      signal: AbortSignal.timeout(5_000), // audit F-8: a hung API must not hang the landing
    });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

interface PageProps {
  params: Promise<{ slug: string }>;
}

export default async function LandingPage({ params }: PageProps) {
  const { slug } = await params;
  const variant = await fetchVariant(slug);

  if (!variant) notFound();

  const cookieStore = await cookies();
  const sessionId =
    cookieStore.get("_sid")?.value ?? crypto.randomUUID();
  const variantId =
    cookieStore.get("_variant")?.value ?? variant.id;
  const decisionId =
    cookieStore.get("_decision")?.value ?? "";

  // Audit 7.1: JSON.parse on an already-parsed array threw and left the landing blank.
  let parsedBlocks: LandingBlock[] = [];
  try {
    parsedBlocks = Array.isArray(variant.blocks)
      ? variant.blocks
      : JSON.parse(variant.blocks ?? "[]");
  } catch {
    parsedBlocks = [];
  }

  return (
    <LandingRenderer
      blocks={parsedBlocks}
      tenantId={variant.tenantId}
      sessionId={sessionId}
      variantId={variantId}
      decisionId={decisionId}
    />
  );
}

export async function generateMetadata({ params }: PageProps) {
  const { slug } = await params;
  const variant = await fetchVariant(slug);
  return {
    title: variant?.name ?? "Landing Page",
    robots: "noindex", // landing pages should not be indexed by default
  };
}
