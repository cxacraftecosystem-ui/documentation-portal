import type { HTMLAttributes } from "react";
import { cn } from "@/lib/utils";

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
