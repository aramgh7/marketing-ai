"use client";
import React, { useState, useCallback } from "react";

const BLOCK_TYPES = [
  "HERO", "PROBLEM", "PROOF", "PRICING", "CTA", "FAQ",
  "booking_calendar", "accreditation_checkbox", "disclaimer_506c",
  "social_proof_investors", "materials", "data_room", "webinar_reg",
];

const LAYOUT_VARIANTS = ["centered", "left-text-right-image", "full-bleed-video", "split-screen"];

interface TemplateBlock {
  block_type: string;
  position: number;
  layout_variant: string;
  slot_overrides: Record<string, unknown>;
}

interface Template {
  name: string;
  description: string;
  default_bg_type: string;
  tags: string[];
  blocks: TemplateBlock[];
}

interface TemplateBuilderProps {
  initial?: Partial<Template>;
  onSave: (template: Template) => void;
}

export default function TemplateBuilder({ initial, onSave }: TemplateBuilderProps) {
  const [name, setName] = useState(initial?.name ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [bgType, setBgType] = useState(initial?.default_bg_type ?? "color");
  const [tags, setTags] = useState<string[]>(initial?.tags ?? []);
  const [blocks, setBlocks] = useState<TemplateBlock[]>(initial?.blocks ?? []);
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);
  const [tagInput, setTagInput] = useState("");
  const [dragOver, setDragOver] = useState<number | null>(null);

  const addBlock = useCallback((type: string) => {
    setBlocks((prev) => [
      ...prev,
      { block_type: type, position: prev.length, layout_variant: "centered", slot_overrides: {} },
    ]);
  }, []);

  const removeBlock = useCallback((idx: number) => {
    setBlocks((prev) => {
      const next = prev.filter((_, i) => i !== idx).map((b, i) => ({ ...b, position: i }));
      setSelectedIdx(null);
      return next;
    });
  }, []);

  const moveBlock = useCallback((from: number, to: number) => {
    setBlocks((prev) => {
      const arr = [...prev];
      const [item] = arr.splice(from, 1);
      arr.splice(to, 0, item);
      return arr.map((b, i) => ({ ...b, position: i }));
    });
    setSelectedIdx(to);
  }, []);

  const setLayoutVariant = useCallback((idx: number, variant: string) => {
    setBlocks((prev) =>
      prev.map((b, i) => (i === idx ? { ...b, layout_variant: variant } : b))
    );
  }, []);

  const addTag = () => {
    const t = tagInput.trim();
    if (t && !tags.includes(t)) {
      setTags((prev) => [...prev, t]);
      setTagInput("");
    }
  };

  const removeTag = (tag: string) => setTags((prev) => prev.filter((t) => t !== tag));

  const handleSave = () => {
    if (!name.trim()) { alert("Template name is required"); return; }
    onSave({ name: name.trim(), description, default_bg_type: bgType, tags, blocks });
  };

  const selected = selectedIdx !== null ? blocks[selectedIdx] : null;

  return (
    <div style={{ display: "grid", gridTemplateColumns: "220px 1fr 260px", gap: "1rem", height: "calc(100vh - 120px)", fontFamily: "system-ui, sans-serif" }}>

      {/* Left — Block palette */}
      <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, padding: "1rem", overflowY: "auto", background: "#f9fafb" }}>
        <div style={{ fontWeight: 600, marginBottom: "0.75rem", fontSize: 13, textTransform: "uppercase", letterSpacing: 1, color: "#6b7280" }}>Block Palette</div>
        {BLOCK_TYPES.map((type) => (
          <button
            key={type}
            onClick={() => addBlock(type)}
            style={{
              display: "block", width: "100%", textAlign: "left", padding: "6px 10px",
              marginBottom: 4, borderRadius: 6, border: "1px solid #d1d5db",
              background: "#fff", cursor: "pointer", fontSize: 13,
            }}
          >
            + {type}
          </button>
        ))}
      </div>

      {/* Center — Canvas */}
      <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, padding: "1rem", overflowY: "auto", background: "#fff" }}>
        <div style={{ marginBottom: "1rem" }}>
          <input
            value={name} onChange={(e) => setName(e.target.value)}
            placeholder="Template name *"
            style={{ width: "100%", padding: "8px 12px", borderRadius: 6, border: "1px solid #d1d5db", fontSize: 14, marginBottom: 6 }}
          />
          <input
            value={description} onChange={(e) => setDescription(e.target.value)}
            placeholder="Description (optional)"
            style={{ width: "100%", padding: "8px 12px", borderRadius: 6, border: "1px solid #d1d5db", fontSize: 13, color: "#6b7280" }}
          />
        </div>

        {blocks.length === 0 && (
          <div style={{ textAlign: "center", color: "#9ca3af", padding: "3rem 1rem", border: "2px dashed #e5e7eb", borderRadius: 8 }}>
            Add blocks from the palette →
          </div>
        )}

        {blocks.map((block, idx) => (
          <div
            key={idx}
            draggable
            onDragStart={() => setDragOver(idx)}
            onDragOver={(e) => { e.preventDefault(); setDragOver(idx); }}
            onDrop={(e) => { e.preventDefault(); if (dragOver !== null && dragOver !== idx) moveBlock(dragOver, idx); setDragOver(null); }}
            onClick={() => setSelectedIdx(idx)}
            style={{
              padding: "10px 14px", marginBottom: 6, borderRadius: 8,
              border: selectedIdx === idx ? "2px solid #3b82f6" : "1px solid #e5e7eb",
              background: dragOver === idx ? "#eff6ff" : selectedIdx === idx ? "#f0f9ff" : "#fff",
              cursor: "pointer", display: "flex", justifyContent: "space-between", alignItems: "center",
            }}
          >
            <div>
              <span style={{ fontWeight: 600, fontSize: 13 }}>{block.block_type}</span>
              <span style={{ marginLeft: 8, fontSize: 11, color: "#6b7280", background: "#f3f4f6", padding: "2px 6px", borderRadius: 4 }}>
                {block.layout_variant}
              </span>
            </div>
            <div style={{ display: "flex", gap: 4 }}>
              {idx > 0 && (
                <button onClick={(e) => { e.stopPropagation(); moveBlock(idx, idx - 1); }}
                  style={{ padding: "2px 6px", fontSize: 11, border: "1px solid #d1d5db", borderRadius: 4, cursor: "pointer", background: "#fff" }}>↑</button>
              )}
              {idx < blocks.length - 1 && (
                <button onClick={(e) => { e.stopPropagation(); moveBlock(idx, idx + 1); }}
                  style={{ padding: "2px 6px", fontSize: 11, border: "1px solid #d1d5db", borderRadius: 4, cursor: "pointer", background: "#fff" }}>↓</button>
              )}
              <button onClick={(e) => { e.stopPropagation(); removeBlock(idx); }}
                style={{ padding: "2px 6px", fontSize: 11, border: "1px solid #fecaca", borderRadius: 4, cursor: "pointer", background: "#fef2f2", color: "#ef4444" }}>✕</button>
            </div>
          </div>
        ))}

        <div style={{ marginTop: "1rem", paddingTop: "1rem", borderTop: "1px solid #f3f4f6" }}>
          <button
            onClick={handleSave}
            style={{ padding: "10px 24px", background: "#3b82f6", color: "#fff", border: "none", borderRadius: 8, cursor: "pointer", fontWeight: 600, fontSize: 14 }}
          >
            Save Template
          </button>
        </div>
      </div>

      {/* Right — Block config or Template settings */}
      <div style={{ border: "1px solid #e5e7eb", borderRadius: 8, padding: "1rem", overflowY: "auto", background: "#f9fafb" }}>
        <div style={{ fontWeight: 600, marginBottom: "0.75rem", fontSize: 13, textTransform: "uppercase", letterSpacing: 1, color: "#6b7280" }}>
          {selected ? `${selected.block_type} Config` : "Template Settings"}
        </div>

        {selected ? (
          <>
            <label style={{ fontSize: 12, color: "#6b7280", display: "block", marginBottom: 4 }}>Layout variant</label>
            <select
              value={selected.layout_variant}
              onChange={(e) => setLayoutVariant(selectedIdx!, e.target.value)}
              style={{ width: "100%", padding: "8px", borderRadius: 6, border: "1px solid #d1d5db", fontSize: 13, marginBottom: "1rem" }}
            >
              {LAYOUT_VARIANTS.map((v) => <option key={v} value={v}>{v}</option>)}
            </select>
            <button onClick={() => setSelectedIdx(null)} style={{ fontSize: 12, color: "#6b7280", background: "none", border: "none", cursor: "pointer", padding: 0 }}>
              ← Back to template settings
            </button>
          </>
        ) : (
          <>
            <label style={{ fontSize: 12, color: "#6b7280", display: "block", marginBottom: 4 }}>Background type</label>
            <select
              value={bgType} onChange={(e) => setBgType(e.target.value)}
              style={{ width: "100%", padding: "8px", borderRadius: 6, border: "1px solid #d1d5db", fontSize: 13, marginBottom: "1rem" }}
            >
              {["color", "gradient", "image", "video"].map((v) => <option key={v} value={v}>{v}</option>)}
            </select>

            <label style={{ fontSize: 12, color: "#6b7280", display: "block", marginBottom: 4 }}>Tags</label>
            <div style={{ display: "flex", gap: 4, marginBottom: 6 }}>
              <input
                value={tagInput} onChange={(e) => setTagInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addTag(); } }}
                placeholder="e.g. investor"
                style={{ flex: 1, padding: "6px 8px", borderRadius: 6, border: "1px solid #d1d5db", fontSize: 12 }}
              />
              <button onClick={addTag} style={{ padding: "6px 10px", borderRadius: 6, border: "1px solid #d1d5db", cursor: "pointer", fontSize: 12, background: "#fff" }}>+</button>
            </div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
              {tags.map((tag) => (
                <span key={tag} style={{ background: "#dbeafe", color: "#1d4ed8", padding: "2px 8px", borderRadius: 12, fontSize: 11, cursor: "pointer" }}
                  onClick={() => removeTag(tag)}>
                  {tag} ✕
                </span>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
