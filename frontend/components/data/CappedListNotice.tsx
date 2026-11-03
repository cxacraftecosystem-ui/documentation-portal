"use client";

import { cappedListNotice, type CutReach, type ListCut } from "@/components/data/cappedList";

export function CappedListNotice({
  cuts,
  reach = "none",
  id,
  className
}: {
  cuts: Array<ListCut | string | null>;
  reach?: CutReach;
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
        <p className="text-xs leading-5 text-ink-500" key={sentence}>
          {sentence}
        </p>
      ))}
    </div>
  );
}
