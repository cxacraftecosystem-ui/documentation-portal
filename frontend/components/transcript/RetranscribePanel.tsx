"use client";

import { useState } from "react";
import { Languages, Loader2 } from "lucide-react";

import type { Transcript } from "@/lib/transcript/contract";
import { retranscribeEstimate, SPOKEN_AUTO, spokenChoiceOf } from "@/lib/transcript/spokenLanguage";
import { ts } from "@/lib/transcript/strings";

import { Actions, PRIMARY, SECONDARY } from "./parts";
import { SpokenLanguagePicker } from "./SpokenLanguagePicker";

export type RetranscribePanelProps = {
  transcript: Transcript;
  busy: boolean;
  onConfirm: (spokenLanguage: string) => void;
  onClose: () => void;
};

export function RetranscribePanel({ transcript, busy, onConfirm, onClose }: RetranscribePanelProps) {
  const spoken = transcript.spokenLanguage;
  const [value, setValue] = useState<string>(() => spokenChoiceOf(spoken?.chosen) ?? SPOKEN_AUTO);
  const estimate = retranscribeEstimate(transcript);
  return (
    <div className="grid gap-3 rounded-md border border-line-200 bg-surface-50 p-3" role="group" aria-label={ts("retranscribeTitle")} data-retranscribe-panel>
      <p className="flex items-center gap-2 text-sm font-medium text-ink-900">
        <Languages className="h-4 w-4 text-purple-700 dark:text-purple-300" aria-hidden />
        {ts("wrongLanguage")}
      </p>
      <SpokenLanguagePicker value={value} onChange={setValue} disabled={busy} />
      <div className="grid gap-0.5 text-xs text-ink-500" data-retranscribe-estimate>
        {estimate.map((line) => (
          <p key={line}>{line}</p>
        ))}
        <p>{ts("retranscribeKeeps")}</p>
      </div>
      <Actions>
        <button type="button" className={SECONDARY} onClick={onClose} disabled={busy}>
          {ts("cancel")}
        </button>
        <button type="button" className={PRIMARY} onClick={() => onConfirm(value)} disabled={busy} data-testid="retranscribe-confirm">
          {busy ? <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden /> : null}
          {ts("retranscribeConfirm")}
        </button>
      </Actions>
    </div>
  );
}
