"use client";

import { useState } from "react";

import { analyzeMeasurementImage } from "@/lib/media";

export type GridDimension = "length" | "breadth" | "height";
export type GridGroup = "lengthBreadth" | "height";
export type GridFiles = Partial<Record<GridGroup, File>>;

/**
 * "Document using grid": length and breadth are read from a single top-down photo of the object on a
 * 1-inch grid sheet; height (optional) is read from its own side-on photo. The vision model's estimate
 * auto-fills the matching field(s). Captured files are reported up so the parent can also store them as
 * media on save. The numeric values are always editable afterwards.
 */
export function GridMeasurement({
  includeHeight = true,
  onLengthBreadth,
  onHeight,
  onFilesChange
}: {
  includeHeight?: boolean;
  onLengthBreadth: (length: string | null, breadth: string | null) => void;
  onHeight: (inches: string) => void;
  onFilesChange: (files: GridFiles) => void;
}) {
  const [enabled, setEnabled] = useState<Set<GridGroup>>(new Set());
  const [files, setFiles] = useState<GridFiles>({});
  const [status, setStatus] = useState<Partial<Record<GridGroup, string>>>({});

  function toggle(group: GridGroup, on: boolean) {
    setEnabled((prev) => {
      const next = new Set(prev);
      if (on) next.add(group);
      else next.delete(group);
      return next;
    });
  }

  const isNum = (value: unknown) => value !== null && value !== undefined && Number.isFinite(Number(value)) && Number(value) > 0;

  async function pick(group: GridGroup, file: File | null) {
    if (!file) return;
    const nextFiles = { ...files, [group]: file };
    setFiles(nextFiles);
    onFilesChange(nextFiles);
    setStatus((prev) => ({ ...prev, [group]: "Analyzing…" }));
    try {
      if (group === "lengthBreadth") {
        const result = await analyzeMeasurementImage(file);
        const length = result.analysis?.lengthInches;
        const breadth = result.analysis?.breadthInches;
        onLengthBreadth(isNum(length) ? String(length) : null, isNum(breadth) ? String(breadth) : null);
        const parts: string[] = [];
        if (isNum(length)) parts.push(`L ${length}"`);
        if (isNum(breadth)) parts.push(`B ${breadth}"`);
        setStatus((prev) => ({
          ...prev,
          [group]: parts.length ? `Measured ${parts.join(" · ")} — fields filled` : "Couldn't read a value — enter it manually"
        }));
      } else {
        const result = await analyzeMeasurementImage(file, "height");
        const value = result.analysis?.valueInches;
        if (isNum(value)) {
          onHeight(String(value));
          setStatus((prev) => ({ ...prev, [group]: `Measured ${value}" — field filled` }));
        } else {
          setStatus((prev) => ({
            ...prev,
            [group]: result.available ? "Couldn't read a value — enter it manually" : result.message ?? "Measurement unavailable"
          }));
        }
      }
    } catch {
      setStatus((prev) => ({ ...prev, [group]: "Analysis failed — enter it manually" }));
    }
  }

  function renderGroup(group: GridGroup, label: string, hint: string) {
    return (
      <div className="grid gap-2">
        <label className="flex items-center gap-2 text-sm text-ink">
          <input type="checkbox" checked={enabled.has(group)} onChange={(event) => toggle(group, event.target.checked)} />
          {label}
        </label>
        {enabled.has(group) ? (
          <div className="grid gap-1 pl-6">
            <p className="text-xs text-ink-muted">{hint}</p>
            <input className="field-input" type="file" accept="image/*" capture="environment" onChange={(event) => pick(group, event.target.files?.[0] ?? null)} />
            {status[group] ? <p className="text-xs text-ink-muted">{status[group]}</p> : null}
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <section className="grid gap-3 rounded-lg border border-[#e6dfd8] bg-field-100 p-4">
      <div>
        <h3 className="font-serif text-lg text-ink">Document using grid</h3>
        <p className="mt-1 text-sm text-ink-muted">
          Place the object on a 1-inch grid sheet. Length and breadth are read from a single top-down photo; height needs its
          own side-on photo. The measured inches auto-fill the matching field(s) (still editable).
        </p>
      </div>
      {renderGroup("lengthBreadth", "Length & breadth (one photo)", "Top-down photo of the object on the grid — fills both length and breadth.")}
      {includeHeight ? renderGroup("height", "Height (one photo)", "Side-on photo of the object against the grid — fills height.") : null}
    </section>
  );
}
