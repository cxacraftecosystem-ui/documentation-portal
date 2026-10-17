/**
 * The duplicate-artisan merge tool (C24) — the API calls and the pure rules the page draws from.
 *
 * The server decides everything that matters: which fields differ, the default pick, what would move,
 * what blocks a merge, and whether a merge can still be undone (`backend/app/services/artisan_merge.py`).
 * This module only words it. Admins only; the page sits under `/admin`, whose guard already says so.
 */

import { apiFetch, buildQuery } from "@/lib/api";

export type MergeChoice = "keep" | "merge" | "both";

export type MergeSide = {
  id: string;
  name: string | null;
  place: string | null;
  craft: string | null;
  status: string;
  createdAt: string | null;
  createdByName: string | null;
};

export type MergeField = {
  key: string;
  label: string;
  keep: string | null;
  merge: string | null;
  same: boolean;
  default: MergeChoice;
  allowBoth: boolean;
};

export type MergeMove = { key: string; label: string; count: number; folded: number };

export type MergeBlocker = { code: string; message: string };

export type MergePreview = {
  keep: MergeSide;
  merge: MergeSide;
  fields: MergeField[];
  moves: MergeMove[];
  blockers: MergeBlocker[];
  warnings: string[];
  canMerge: boolean;
  undoDays: number;
};

export type MergeSummary = {
  id: string;
  status: "IN_PROGRESS" | "DONE" | "UNDONE";
  survivorId: string;
  survivorName: string;
  mergedId: string;
  mergedName: string;
  mergedByName: string | null;
  createdAt: string | null;
  undoneAt: string | null;
  undoneByName: string | null;
  undoUntil: string | null;
  canUndo: boolean;
  moves: MergeMove[];
  kept: string[];
};

export type MergeCandidate = {
  keepId: string;
  mergeId: string;
  reason: string;
  keep: { id: string; name: string; place: string | null };
  merge: { id: string; name: string; place: string | null };
};

export function fetchMergeCandidates() {
  return apiFetch<{ items: MergeCandidate[]; truncated: boolean }>("/artisans/merge/candidates");
}

export function fetchMergePreview(keepId: string, mergeId: string) {
  return apiFetch<MergePreview>(`/artisans/merge/preview${buildQuery({ keepId, mergeId })}`);
}

export function postMerge(keepId: string, mergeId: string, picks: Record<string, MergeChoice>) {
  return apiFetch<MergeSummary>("/artisans/merge", {
    method: "POST",
    body: JSON.stringify({ keepId, mergeId, picks })
  });
}

export function fetchMergeHistory() {
  return apiFetch<{ items: MergeSummary[]; truncated: boolean; undoDays: number }>("/artisans/merge/history");
}

export function postUndoMerge(mergeId: string) {
  return apiFetch<MergeSummary>(`/artisans/merge/${encodeURIComponent(mergeId)}/undo`, { method: "POST" });
}

/** "1 product record" / "3 product records" — the count first, the noun agreeing with it. */
export function plural(count: number, singular: string, pluralForm = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : pluralForm}`;
}

/** The pick the page starts from: the server's default for every field that differs. */
export function defaultPicks(fields: MergeField[]): Record<string, MergeChoice> {
  const picks: Record<string, MergeChoice> = {};
  for (const field of fields) {
    if (!field.same) picks[field.key] = field.default;
  }
  return picks;
}

/** What the kept record will read for one field, given the pick. */
export function resultValue(field: MergeField, choice: MergeChoice | undefined): string | null {
  const pick = field.same ? "keep" : (choice ?? field.default);
  if (pick === "merge") return field.merge;
  if (pick === "both") {
    const parts = [field.keep, field.merge].filter((part): part is string => Boolean(part && part.trim()));
    return parts.length ? Array.from(new Set(parts)).join(" · ") : null;
  }
  return field.keep;
}

/** The one-line account of what moves, for the confirm dialog and the history. */
export function movesSentence(moves: MergeMove[]): string {
  if (!moves.length) return "Nothing else is linked to it.";
  return `Moves ${moves.map((move) => move.label).join(", ")}.`;
}

/** "Undo until 19 Nov" — or why not. */
export function undoState(summary: MergeSummary, now: Date = new Date()): { canUndo: boolean; label: string } {
  if (summary.status === "UNDONE") return { canUndo: false, label: "Undone" };
  if (summary.status === "IN_PROGRESS") return { canUndo: summary.canUndo, label: "Did not finish — undo it" };
  if (!summary.canUndo || !summary.undoUntil) return { canUndo: false, label: "Can no longer be undone" };
  const until = new Date(summary.undoUntil);
  if (Number.isNaN(until.getTime()) || until.getTime() < now.getTime()) {
    return { canUndo: false, label: "Can no longer be undone" };
  }
  return {
    canUndo: true,
    label: `Undo until ${until.toLocaleDateString(undefined, { day: "numeric", month: "short" })}`
  };
}
