"use client";
import React, { useState } from "react";

const L1_VALUES = [
  "founder_portrait", "team_photo", "property_aerial", "property_interior",
  "chart_graph", "document", "abstract_visual", "product_shot",
];
const L2_VALUES = [
  "hero_background", "hero_face", "proof_visual", "social_proof_avatar",
  "materials_thumbnail", "ad_creative_primary", "ad_creative_secondary",
];
const L3_VALUES = [
  "professional", "warm_approachable", "aspirational",
  "technical_data_driven", "urgent", "calm_trustworthy",
];

export interface Asset {
  id: string;
  s3_key: string;
  mime_type: string;
  tags_l1: string[];
  tags_l2: string[];
  tags_l3: string[];
  tag_confirmed_at: string | null;
  auto_tag_suggested?: { tags_l1: string[]; tags_l2: string[]; tags_l3: string[] };
  created_at: string;
}

export interface AssetLibraryProps {
  assets: Asset[];
  onTagsConfirmed: (assetId: string, tags: { tags_l1: string[]; tags_l2: string[]; tags_l3: string[] }) => void;
}

function TagPicker({
  label, values, selected, onToggle,
}: { label: string; values: string[]; selected: string[]; onToggle: (v: string) => void }) {
  return (
    <div style={{ marginBottom: "0.75rem" }}>
      <div style={{ fontSize: 11, fontWeight: 600, color: "#6b7280", textTransform: "uppercase", letterSpacing: 0.5, marginBottom: 4 }}>{label}</div>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
        {values.map((v) => (
          <button
            key={v}
            onClick={() => onToggle(v)}
            style={{
              padding: "3px 10px", borderRadius: 12, fontSize: 11, cursor: "pointer",
              background: selected.includes(v) ? "#dbeafe" : "#f3f4f6",
              color: selected.includes(v) ? "#1d4ed8" : "#374151",
              border: selected.includes(v) ? "1px solid #93c5fd" : "1px solid #e5e7eb",
              fontWeight: selected.includes(v) ? 600 : 400,
            }}
          >
            {v}
          </button>
        ))}
      </div>
    </div>
  );
}

function AssetCard({ asset, onSaveTags }: { asset: Asset; onSaveTags: (tags: { tags_l1: string[]; tags_l2: string[]; tags_l3: string[] }) => void }) {
  const suggested = asset.auto_tag_suggested;
  const [l1, setL1] = useState<string[]>(asset.tags_l1.length ? asset.tags_l1 : (suggested?.tags_l1 ?? []));
  const [l2, setL2] = useState<string[]>(asset.tags_l2.length ? asset.tags_l2 : (suggested?.tags_l2 ?? []));
  const [l3, setL3] = useState<string[]>(asset.tags_l3.length ? asset.tags_l3 : (suggested?.tags_l3 ?? []));
  const [saved, setSaved] = useState(!!asset.tag_confirmed_at);

  const toggle = (setter: React.Dispatch<React.SetStateAction<string[]>>) => (v: string) => {
    setter((prev) => prev.includes(v) ? prev.filter((x) => x !== v) : [...prev, v]);
    setSaved(false);
  };

  const isImage = asset.mime_type.startsWith("image/");
  const isVideo = asset.mime_type.startsWith("video/");

  return (
    <div style={{ border: "1px solid #e5e7eb", borderRadius: 10, overflow: "hidden", background: "#fff" }}>
      <div style={{ background: "#f9fafb", height: 140, display: "flex", alignItems: "center", justifyContent: "center", position: "relative" }}>
        {isImage ? (
          <img
            src={`/api/v1/assets/${asset.id}/preview`}
            alt=""
            style={{ maxHeight: "100%", maxWidth: "100%", objectFit: "contain" }}
            onError={(e) => { (e.target as HTMLImageElement).style.display = "none"; }}
          />
        ) : isVideo ? (
          <video src={`/api/v1/assets/${asset.id}/preview`} style={{ maxHeight: "100%", maxWidth: "100%" }} muted />
        ) : (
          <span style={{ fontSize: 32 }}>📄</span>
        )}
        {suggested && !asset.tag_confirmed_at && (
          <span style={{ position: "absolute", top: 6, right: 6, background: "#fef3c7", color: "#d97706", padding: "2px 6px", borderRadius: 6, fontSize: 10, fontWeight: 600 }}>
            AI suggested
          </span>
        )}
        {saved && (
          <span style={{ position: "absolute", top: 6, left: 6, background: "#dcfce7", color: "#16a34a", padding: "2px 6px", borderRadius: 6, fontSize: 10, fontWeight: 600 }}>
            ✓ tagged
          </span>
        )}
      </div>
      <div style={{ padding: "0.75rem" }}>
        <div style={{ fontSize: 11, color: "#9ca3af", marginBottom: "0.5rem", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {asset.s3_key.split("/").pop()}
        </div>
        <TagPicker label="Type (L1)" values={L1_VALUES} selected={l1} onToggle={toggle(setL1)} />
        <TagPicker label="Role (L2)" values={L2_VALUES} selected={l2} onToggle={toggle(setL2)} />
        <TagPicker label="Mood (L3)" values={L3_VALUES} selected={l3} onToggle={toggle(setL3)} />
        <button
          onClick={() => { onSaveTags({ tags_l1: l1, tags_l2: l2, tags_l3: l3 }); setSaved(true); }}
          style={{ width: "100%", padding: "8px", background: "#3b82f6", color: "#fff", border: "none", borderRadius: 6, cursor: "pointer", fontSize: 13, fontWeight: 600 }}
        >
          Confirm Tags
        </button>
      </div>
    </div>
  );
}

export default function AssetLibrary({ assets, onTagsConfirmed }: AssetLibraryProps) {
  const [l1Filter, setL1Filter] = useState<string | null>(null);
  const [l2Filter, setL2Filter] = useState<string | null>(null);
  const [l3Filter, setL3Filter] = useState<string | null>(null);

  const filtered = assets.filter((a) => {
    if (l1Filter && !a.tags_l1.includes(l1Filter)) return false;
    if (l2Filter && !a.tags_l2.includes(l2Filter)) return false;
    if (l3Filter && !a.tags_l3.includes(l3Filter)) return false;
    return true;
  });

  return (
    <div>
      <div style={{ display: "flex", gap: "1rem", marginBottom: "1rem", flexWrap: "wrap", alignItems: "center" }}>
        <select value={l1Filter ?? ""} onChange={(e) => setL1Filter(e.target.value || null)}
          style={{ padding: "6px 10px", borderRadius: 6, border: "1px solid #d1d5db", fontSize: 13 }}>
          <option value="">All types (L1)</option>
          {L1_VALUES.map((v) => <option key={v} value={v}>{v}</option>)}
        </select>
        <select value={l2Filter ?? ""} onChange={(e) => setL2Filter(e.target.value || null)}
          style={{ padding: "6px 10px", borderRadius: 6, border: "1px solid #d1d5db", fontSize: 13 }}>
          <option value="">All roles (L2)</option>
          {L2_VALUES.map((v) => <option key={v} value={v}>{v}</option>)}
        </select>
        <select value={l3Filter ?? ""} onChange={(e) => setL3Filter(e.target.value || null)}
          style={{ padding: "6px 10px", borderRadius: 6, border: "1px solid #d1d5db", fontSize: 13 }}>
          <option value="">All moods (L3)</option>
          {L3_VALUES.map((v) => <option key={v} value={v}>{v}</option>)}
        </select>
        <span style={{ fontSize: 13, color: "#6b7280" }}>{filtered.length} assets</span>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(300px, 1fr))", gap: "1rem" }}>
        {filtered.map((asset) => (
          <AssetCard key={asset.id} asset={asset} onSaveTags={(tags) => onTagsConfirmed(asset.id, tags)} />
        ))}
        {filtered.length === 0 && (
          <div style={{ gridColumn: "1/-1", textAlign: "center", color: "#9ca3af", padding: "3rem" }}>
            No assets match the current filters.
          </div>
        )}
      </div>
    </div>
  );
}
