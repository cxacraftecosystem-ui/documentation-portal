"use client";

import { Download, RotateCw } from "lucide-react";

import { cn } from "@/lib/utils";

export function MediaActionButton({
  kind,
  onClick,
  label,
  className
}: {
  kind: "download" | "retry";
  onClick: () => void;
  label: string;
  className?: string;
}) {
  const Icon = kind === "download" ? Download : RotateCw;
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      data-media-action={kind}
      onClick={(event) => {
        event.stopPropagation();
        onClick();
      }}
      className={cn(
        "inline-flex min-h-9 shrink-0 items-center justify-center gap-1.5 rounded-md border px-3 text-sm font-semibold transition pointer-coarse:min-h-11 pointer-coarse:px-4",
        kind === "download"
          ? "border-purple-200 bg-purple-50 text-purple-800 hover:bg-purple-100 dark:border-purple-700 dark:bg-purple-900 dark:text-purple-100 dark:hover:bg-purple-800"
          : "border-red-200 bg-error-100 text-red-800 hover:bg-red-100 dark:border-red-800 dark:bg-red-950 dark:text-red-100 dark:hover:bg-red-900",
        className
      )}
    >
      <Icon className="h-4 w-4" aria-hidden />
      {kind === "download" ? "Download" : "Retry"}
    </button>
  );
}
