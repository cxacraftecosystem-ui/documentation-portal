"use client";

import type { ReactNode } from "react";
import { ButtonRow } from "@/components/ui/ButtonRow";

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

/*
 * UX audit 2026-10-16. DC-02: every tone carries a `dark:` ink, because purple, red and green are
 * literal colours that do not invert (Edit measured 1.73:1 on a dark card). DC-07: on a coarse
 * pointer (a phone) the row action is a 44px target; a mouse keeps the compact desktop density.
 */
const BASE =
  "inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border px-3 py-1.5 text-xs font-semibold transition disabled:cursor-not-allowed disabled:opacity-60 pointer-coarse:min-h-11";

const TONE = {
  /** Primary row action — edit/open the record. */
  edit: "border-field-300 text-field-700 hover:bg-field-100 dark:border-purple-400/50 dark:text-purple-300",
  /** Secondary row actions — Discuss, Assign, and anything else non-destructive. */
  neutral: "border-line-200 text-ink-700 hover:bg-surface-50",
  /** Destructive row action — delete/reject. Gate on admin view and confirm before running. */
  danger: "border-red-200 text-red-700 hover:bg-red-50 dark:border-red-400/50 dark:text-red-300 dark:hover:bg-red-950/40",
  /** Affirmative decision — approve. */
  positive: "border-success-600/30 text-success-600 hover:bg-success-100 dark:border-green-400/50 dark:text-green-300 dark:hover:bg-green-950/40"
} as const;

export type RowActionTone = keyof typeof TONE;

/** Class string for one row-action button. */
export function rowAction(tone: RowActionTone = "neutral", extra?: string): string {
  return `${BASE} ${TONE[tone]}${extra ? ` ${extra}` : ""}`;
}

/**
 * A row's action buttons, laid out by `ButtonRow`: flush right beside the row on wide screens,
 * centred (one), paired (two) or stacked (three or more) on a phone. Pass a delete as `destructive`
 * so it is always last.
 */
export function RowActions({ children, destructive }: { children?: ReactNode; destructive?: ReactNode }) {
  return (
    <ButtonRow align="end" size="sm" destructive={destructive}>
      {children}
    </ButtonRow>
  );
}
