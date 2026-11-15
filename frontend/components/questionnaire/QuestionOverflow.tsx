"use client";

import { MoreVertical } from "lucide-react";
import { useRef, useState } from "react";

import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import type { RecordBy } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";

export type OverflowAction = "record" | "upload" | "type" | "prefill";

export function overflowActions(recordBy: RecordBy): OverflowAction[] {
  return recordBy === "QUESTION" ? ["type"] : ["record", "upload", "type"];
}

const LABEL: Record<OverflowAction, "recordThisQuestion" | "uploadForThisQuestion" | "typeAnAnswer" | "fillFromProfile"> = {
  record: "recordThisQuestion",
  upload: "uploadForThisQuestion",
  type: "typeAnAnswer",
  prefill: "fillFromProfile"
};

export function QuestionOverflow({
  code,
  recordBy,
  onAction,
  disabled = false,
  hide = [],
  extra = []
}: {
  code: string;
  recordBy: RecordBy;
  onAction: (action: OverflowAction) => void;
  disabled?: boolean;
  hide?: OverflowAction[];
  extra?: OverflowAction[];
}) {
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement | null>(null);
  const actions = [...overflowActions(recordBy), ...extra].filter((a) => !hide.includes(a));
  if (!actions.length) return null;
  return (
    <>
      <button
        ref={anchor}
        type="button"
        aria-label={qs("moreFor", { code })}
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={disabled}
        className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-ink-500 transition hover:bg-field-100 hover:text-ink-900 disabled:opacity-50"
        onClick={() => setOpen(!open)}
        data-question-more={code}
      >
        <MoreVertical className="h-5 w-5" aria-hidden />
      </button>
      <AnchoredPopover open={open} onClose={() => setOpen(false)} anchorRef={anchor} label={qs("moreFor", { code })} maxWidth={280} className="p-1.5">
        <div role="menu" aria-label={qs("moreFor", { code })} className="grid gap-0.5" data-question-menu={code}>
          {actions.map((action) => (
            <button
              key={action}
              type="button"
              role="menuitem"
              className="flex min-h-11 items-center rounded-sm px-3 text-left text-sm font-medium text-ink-900 transition hover:bg-field-100"
              onClick={() => {
                setOpen(false);
                onAction(action);
              }}
            >
              {qs(LABEL[action])}
            </button>
          ))}
        </div>
      </AnchoredPopover>
    </>
  );
}
