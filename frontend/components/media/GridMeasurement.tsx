"use client";

import { useState } from "react";

import { analyzeMeasurementImage } from "@/lib/media";

export type GridDimension = "length" | "breadth" | "height";

const LABELS: Record<GridDimension, string> = { length: "Length", breadth: "Breadth", height: "Height" };

/**
 * "Document using grid": tick the dimensions to capture, upload a grid-sheet photo for each, and the
 * vision model's estimate auto-fills the matching field. Captured files are reported up so the parent
 * can also store them as media on save. The numeric value is always editable afterwards.
 */
export function GridMeasurement({
  dimensions,
  onValue,
  onFilesChange
}: {
  dimensions: GridDimension[];
  onValue: (dimension: GridDimension, inches: string) => void;
  onFilesChange: (files: Partial<Record<GridDimension, File>>) => void;
}) {
  const [enabled, setEnabled] = useState<Set<GridDimension>>(new Set());
  const [files, setFiles] = useState<Partial<Record<GridDimension, File>>>({});
  const [status, setStatus] = useState<Partial<Record<GridDimension, string>>>({});

  function toggle(dimension: GridDimension, on: boolean) {
    setEnabled((prev) => {
      const next = new Set(prev);
      if (on) next.add(dimension);
      else next.delete(dimension);
      return next;
    });
  }

  async function pick(dimension: GridDimension, file: File | null) {
    if (!file) return;
    const nextFiles = { ...files, [dimension]: file };
    setFiles(nextFiles);
    onFilesChange(nextFiles);
    setStatus((prev) => ({ ...prev, [dimension]: "Analyzing…" }));
    try {
      const result = await analyzeMeasurementImage(file, dimension);
      const value = result.analysis?.valueInches;
      if (value !== null && value !== undefined && Number.isFinite(Number(value))) {
        onValue(dimension, String(value));
        setStatus((prev) => ({ ...prev, [dimension]: `Measured ${value}" — field filled` }));
      } else {
        setStatus((prev) => ({
          ...prev,
          [dimension]: result.available ? "Couldn't read a value — enter it manually" : result.message ?? "Measurement unavailable"
        }));
      }
    } catch {
      setStatus((prev) => ({ ...prev, [dimension]: "Analysis failed — enter it manually" }));
    }
  }

  return (
    <section className="grid gap-3 rounded-lg border border-[#e6dfd8] bg-field-100 p-4">
      <div>
        <h3 className="font-serif text-lg text-ink">Document using grid</h3>
        <p className="mt-1 text-sm text-ink-muted">
          Place the object on a 1-inch grid sheet, tick the dimensions you want, and upload a photo for each. The measured
          inches auto-fill the matching field (still editable).
        </p>
      </div>
      {dimensions.map((dimension) => (
        <div key={dimension} className="grid gap-2">
          <label className="flex items-center gap-2 text-sm text-ink">
            <input type="checkbox" checked={enabled.has(dimension)} onChange={(event) => toggle(dimension, event.target.checked)} />
            {LABELS[dimension]}
          </label>
          {enabled.has(dimension) ? (
            <div className="grid gap-1 pl-6">
              <input className="field-input" type="file" accept="image/*" capture="environment" onChange={(event) => pick(dimension, event.target.files?.[0] ?? null)} />
              {status[dimension] ? <p className="text-xs text-ink-muted">{status[dimension]}</p> : null}
            </div>
          ) : null}
        </div>
      ))}
    </section>
  );
}
