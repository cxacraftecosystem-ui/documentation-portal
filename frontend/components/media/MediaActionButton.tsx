"use client";

import { Download, RotateCw } from "lucide-react";

import { cn } from "@/lib/utils";

/**
 * The visible action buttons on a media card — "Retry" and "Download" — drawn ONE way everywhere
 * (capture tiles, the upload tray, the outbox banner, upload-progress rows).
 *
 * Owner, 2026-10-17: these were hard to see. They were 20px-tall outline chips in 12px type on a
 * tinted tile. Now each is a TONAL button with an icon AND a word: purple-800 on purple-50 in light,
 * purple-100 on purple-900 in dark (both far above 4.5:1), a 1px border of the next rung for the
 * edge, 36px tall on a mouse and 44px on a touch screen, 14px semibold type. "Retry" uses the same
 * shape with the error rung, because it sits next to a failure line and must read as the way out.
 *
 * Always `type="button"` (every caller sits inside a record `<form>`) and it stops propagation, so a
 * click never also opens the lightbox behind it.
 */
export function MediaActionButton({
  kind,
  onClick,
  label,
  className
}: {
  kind: "download" | "retry";
  onClick: () => void;
  /** The accessible name, e.g. "Download photo.jpg". The visible word stays short. */
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
