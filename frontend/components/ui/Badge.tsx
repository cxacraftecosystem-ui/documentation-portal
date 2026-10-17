import type { ReactNode } from "react";

/**
 * A small worded mark beside a value: where an answer came from ("From transcript", "From last
 * process") or what still needs doing ("Unnamed step"). One recipe so the marks read as one family.
 *
 * `note` is a fact about the value; `attention` is something somebody should come back to. Both carry
 * a word, never colour alone. The tones are literal (they do not invert), each pair at 4.5:1 or better.
 */
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
  /** `dd` inside a description list; `span` everywhere else. */
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
