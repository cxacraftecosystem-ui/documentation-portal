"use client";

import { useState } from "react";
import { Loader2, Wand2 } from "lucide-react";

import { autodraft } from "@/lib/transcript/api";
import type { AutoDraftResult } from "@/lib/transcript/contract";
import { clock } from "@/lib/transcript/format";
import { ts } from "@/lib/transcript/strings";

import { Actions, Badge, PRIMARY, SECONDARY, TimeChip } from "./parts";

/**
 * Fill the questionnaire from a take (design §10.3). The server maps the turns to the form's open
 * questions and drafts each answer in the original and in English, citing the turns. They land in
 * the questionnaire's own drafts flow, so they are accepted or edited in the form's answer boxes
 * ("Review in the form") and are never saved from here; Dismiss hides one from this list. Locked
 * answers and "recordings preferred" questions are skipped by the server and listed.
 */
export function FillFromRecording({ mediaId, onSeek, onReview }: { mediaId: string; onSeek: (ms: number) => void; onReview?: () => void }) {
  const [result, setResult] = useState<AutoDraftResult | null>(null);
  const [dismissed, setDismissed] = useState<Set<string>>(() => new Set());
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function draft() {
    setWorking(true);
    setError(null);
    try {
      setResult(await autodraft(mediaId));
    } catch (failure) {
      setError(failure instanceof Error && failure.message ? failure.message : ts("loadFailed"));
    } finally {
      setWorking(false);
    }
  }

  const drafted = result?.drafted.filter((item) => !dismissed.has(item.questionId)) ?? [];
  const skipped = result?.skipped.filter((item) => item.reason === "LOCKED" || item.reason === "RECORDING_PREFERRED") ?? [];

  return (
    <div className="grid gap-3 rounded-lg border border-line-200 bg-surface-50 p-3 sm:p-4" data-fill-from-recording>
      <h3 className="font-display text-sm font-bold text-ink-900">{ts("autodraftTitle")}</h3>
      {result === null ? (
        <Actions>
          <button type="button" className={SECONDARY} onClick={draft} disabled={working} data-testid="fill-from-recording">
            {working ? <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden /> : <Wand2 className="h-4 w-4" aria-hidden />}
            {working ? ts("autodraftWorking") : ts("autodraftAction")}
          </button>
        </Actions>
      ) : result.drafted.length === 0 ? (
        <p className="text-sm text-ink-500">{ts("autodraftNone")}</p>
      ) : (
        <>
          <p className="text-sm text-ink-700" role="status">
            {ts("autodraftDone", { count: drafted.length })}
          </p>
          <ul className="grid gap-2">
            {drafted.map((item) => (
              <li key={item.questionId} className="grid gap-2 rounded-md border border-line-200 bg-card p-3" data-draft={item.questionId}>
                <p className="whitespace-pre-wrap text-sm text-ink-900">{item.answerOriginal}</p>
                {item.answerEnglish && item.answerEnglish !== item.answerOriginal ? (
                  <p className="whitespace-pre-wrap text-sm text-ink-700" lang="en">
                    {item.answerEnglish}
                  </p>
                ) : null}
                <div className="flex flex-wrap items-center gap-1.5">
                  {item.citations.map((citation) => (
                    <TimeChip key={`${citation.turnId}-${citation.startMs}`} ms={citation.startMs} label={clock(citation.startMs)} onSeek={onSeek} variant="chip" />
                  ))}
                  <button
                    type="button"
                    className="ml-auto min-h-9 rounded-md px-2 text-xs font-medium text-ink-500 hover:text-ink-900 max-sm:min-h-11"
                    onClick={() => setDismissed((current) => new Set(current).add(item.questionId))}
                    data-dismiss-draft={item.questionId}
                  >
                    {ts("dismiss")}
                  </button>
                </div>
              </li>
            ))}
          </ul>
          {skipped.length ? (
            <div className="flex flex-wrap gap-1.5" data-skipped>
              {skipped.map((item) => (
                <Badge key={item.questionId} tone="warn">
                  {item.reason === "LOCKED" ? ts("autodraftSkippedLocked") : ts("autodraftSkippedRecordingPreferred")}
                </Badge>
              ))}
            </div>
          ) : null}
          {onReview && drafted.length ? (
            <Actions>
              <button type="button" className={PRIMARY} onClick={onReview} data-testid="review-drafts">
                {ts("autodraftReview")}
              </button>
            </Actions>
          ) : null}
        </>
      )}
      {error ? <p className="text-sm text-error-600">{error}</p> : null}
    </div>
  );
}
