"use client";

/**
 * The render half of `components/data/cappedList.ts` — the line that says a list stopped short.
 *
 * Nothing is decided here. Which sentence, and whether there is one at all, is
 * {@link cappedListNotice}'s job for the reason its own header gives (one of its states cannot be
 * produced by any live database, so it must be reachable without a browser). This component exists
 * so that every screen draws that sentence the same way instead of several slightly different ones,
 * and so that a picker can point `aria-describedby` at it — an incomplete list is a fact a
 * screen-reader user needs at the control, not somewhere above it.
 *
 * It renders NOTHING when there is nothing to say, which is the common case. Do not wrap it in a
 * `<div className="mt-2">` that survives an empty notice: an empty box under every complete picker
 * is padding for a screen that has none to spare.
 */

import { cappedListNotice, type CutReach, type ListCut } from "@/components/data/cappedList";

export function CappedListNotice({
  cuts,
  reach = "none",
  id,
  className
}: {
  /**
   * The cuts to report, in the order the reader meets the controls. `null` entries are the normal
   * case (that list was complete) and are dropped — callers pass `listCut(...)` results straight in
   * without filtering, so a picker that stops being capped stops printing without an edit here.
   *
   * A plain STRING is a sentence some other decider in `cappedList.ts` has already worded. `""` is
   * dropped exactly like `null`. What must NOT happen is a caller assembling its own wording and
   * passing it as a string: the whole argument for `cappedList.ts` is that several screens
   * describing one cut in several sentences teaches a reader that none of them means much — the
   * decision stays in that module.
   */
  cuts: Array<ListCut | string | null>;
  reach?: CutReach;
  /**
   * Only for a SINGLE-cut call site that wires `describedBy` on its picker. With several cuts the
   * ids would collide, so the component refuses to invent per-line ids rather than guessing.
   */
  id?: string;
  className?: string;
}) {
  const sentences = cuts
    .map((cut) => (typeof cut === "string" ? cut : cappedListNotice(cut, reach)))
    .filter((sentence) => sentence.length > 0);
  if (sentences.length === 0) return null;
  return (
    <div className={className ?? "mt-1 grid gap-0.5"} id={sentences.length === 1 ? id : undefined}>
      {sentences.map((sentence) => (
        // `text-ink-500` at `text-xs`: the same weight as the other advisory lines under these
        // pickers, so a reader who has met one recognises the other.
        <p className="text-xs leading-5 text-ink-500" key={sentence}>
          {sentence}
        </p>
      ))}
    </div>
  );
}
