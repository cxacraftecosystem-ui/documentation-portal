"use client";

import type { ReactNode } from "react";

/**
 * The standard action cluster every list row ends with: separate rounded buttons, one per action,
 * never a dropdown or a bare text link. Edit sits first in the brand outline, Discuss (comments +
 * edit history) next in the neutral outline, and a destructive action last in red — that one is
 * admin-only and must always confirm before it fires.
 *
 * Pages import `rowAction(tone)` for the class string (so a row can render a `<Link>`, a `<button>`
 * or an `<a>` and still look identical) and `RowActions` for the right-aligned wrapper. Keeping the
 * classes here is what stops each page from drifting its own variant of the same three buttons.
 */

const BASE =
  "inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border px-3 py-1.5 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-60";

const TONE = {
  /** Primary row action — edit/open the record. */
  edit: "border-field-300 text-field-700 hover:bg-field-100",
  /** Secondary row actions — Discuss, Assign, and anything else non-destructive. */
  neutral: "border-line-200 text-ink-700 hover:bg-surface-50",
  /** Destructive row action — delete/reject. Gate on admin view and confirm before running. */
  danger: "border-red-200 text-red-700 hover:bg-red-50",
  /** Affirmative decision — approve. */
  positive: "border-success-600/30 text-success-600 hover:bg-success-100"
} as const;

export type RowActionTone = keyof typeof TONE;

/** Class string for one row-action button. */
export function rowAction(tone: RowActionTone = "neutral", extra?: string): string {
  return `${BASE} ${TONE[tone]}${extra ? ` ${extra}` : ""}`;
}

/** Right-aligned wrapper for a row's action buttons; wraps rather than overflowing on small screens. */
export function RowActions({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap justify-end gap-2">{children}</div>;
}
