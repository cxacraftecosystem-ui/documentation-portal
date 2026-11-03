export function nextCompletionStatus(current: string | null | undefined): "COMPLETED" | "NEEDS_REVIEW" | "NEEDS_REDO" | null {
  if (!current) return "COMPLETED";
  if (current === "COMPLETED") return "NEEDS_REVIEW";
  if (current === "NEEDS_REVIEW") return "NEEDS_REDO";
  return null;
}

export function completionVerdictLabel(status: string | null | undefined): string {
  if (status === "COMPLETED") return "marked complete";
  if (status === "NEEDS_REVIEW") return "requires review";
  if (status === "NEEDS_REDO") return "needs to be redone";
  return "not complete";
}
