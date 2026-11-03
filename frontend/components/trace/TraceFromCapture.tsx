"use client";

import { useMemo } from "react";

import { TracePanel } from "@/components/trace/TracePanel";

export function TraceFromCapture({
  files,
  onFilesChange,
  disabled
}: {
  files: File[];
  onFilesChange: (files: File[]) => void;
  disabled?: boolean;
}) {
  const image = useMemo(() => {
    for (let index = files.length - 1; index >= 0; index -= 1) {
      const candidate = files[index];
      if (candidate.type.startsWith("image/")) return candidate;
      if (candidate.type === "" && /\.(jpe?g|png|webp|bmp|gif)$/i.test(candidate.name)) return candidate;
    }
    return null;
  }, [files]);

  return (
    <TracePanel
      image={image}
      imageName={image?.name}
      disabled={disabled}
      onAttach={(file) => {
        onFilesChange([...files, file]);
      }}
    />
  );
}
