/**
 * The admin's verdicts on the "Check completion" matrix, in the order and words of Android's
 * picker (Completed, Requires review, Needs to be redone). The web could set only two of the three
 * before 2026-10-20.
 */
/** The admin's next completion verdict when a cell is pressed (Android's three, then cleared). */
export function nextCompletionStatus(current: string | null | undefined): "COMPLETED" | "NEEDS_REVIEW" | "NEEDS_REDO" | null {
  if (!current) return "COMPLETED";
  if (current === "COMPLETED") return "NEEDS_REVIEW";
  if (current === "NEEDS_REVIEW") return "NEEDS_REDO";
  return null;
}

/** A verdict in the words Android's picker uses. */
export function completionVerdictLabel(status: string | null | undefined): string {
  if (status === "COMPLETED") return "marked complete";
  if (status === "NEEDS_REVIEW") return "requires review";
  if (status === "NEEDS_REDO") return "needs to be redone";
  return "not complete";
}

