import { cookies } from "next/headers";
import { notFound } from "next/navigation";
import LandingRenderer, { LandingBlock } from "@/components/LandingRenderer";

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
  blocks: string; // raw JSON string
}

async function fetchVariant(slug: string): Promise<VariantData | null> {
  try {
    const res = await fetch(`${API_BASE}/public/l/${slug}`, {
      next: { revalidate: 60 }, // ISR: revalidate every 60s
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

  let parsedBlocks: LandingBlock[] = [];
  try {
    parsedBlocks = JSON.parse(variant.blocks ?? "[]");
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
