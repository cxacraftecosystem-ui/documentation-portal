import type { HTMLAttributes } from "react";
import { cn } from "@/lib/utils";

/**
 * THE progress bar — every determinate bar in the app draws through this, so the look is decided
 * once, in `globals.css` (`.progress-track` / `.progress-fill` and the `--progress-*` tokens).
 *
 * The track is a TINTED token and never the card's own white (owner report 2026-10-17: the
 * voice-note bar vanished into its card), and the fill clears WCAG 1.4.11's 3:1 against both the
 * track and the card in light and in dark. At least 4px tall, round ends. Android draws the same
 * rule through `ui/FieldProgress.kt`.
 *
 * - `percent` is clamped to 0–100.
 * - `tone="done"` is the finished/passing fill (success-600 light / success-400 dark).
 * - `className` adds to the track (a height such as `h-2`, a margin, a max width); `fillClassName`
 *   adds to the fill (a width transition). Every other prop — `role`, `aria-*`, `aria-hidden` —
 *   lands on the track, so the caller keeps whatever semantics the bar already had.
 */
export function ProgressBar({
  percent,
  tone = "brand",
  className,
  fillClassName,
  ...rest
}: {
  percent: number;
  tone?: "brand" | "done";
  className?: string;
  fillClassName?: string;
} & Omit<HTMLAttributes<HTMLDivElement>, "children">) {
  const width = Number.isFinite(percent) ? Math.max(0, Math.min(100, percent)) : 0;
  return (
    <div {...rest} className={cn("progress-track", className)}>
      <div
        className={cn("progress-fill", fillClassName)}
        data-tone={tone === "done" ? "done" : undefined}
        style={{ width: `${width}%` }}
      />
    </div>
  );
}
