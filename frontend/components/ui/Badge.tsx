import type { ReactNode } from "react";

export type BadgeTone = "note" | "attention";

const TONE_CLASS: Record<BadgeTone, string> = {
  note: "border-purple-300 bg-purple-50 text-purple-800",
  attention: "border-amber-500 bg-amber-100 text-amber-800"
};

export function Badge({
  tone = "note",
  as: Tag = "span",
  className,
  children
}: {
  tone?: BadgeTone;
  as?: "span" | "dd";
  className?: string;
  children: ReactNode;
}) {
  return (
    <Tag
      className={`w-fit rounded-full border px-2 py-0.5 text-xs font-medium ${TONE_CLASS[tone]}${className ? ` ${className}` : ""}`}
    >
      {children}
    </Tag>
  );
}
