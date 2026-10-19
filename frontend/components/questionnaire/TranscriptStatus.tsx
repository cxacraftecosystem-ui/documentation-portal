"use client";

import type { Recording } from "@/lib/questionnaireApi";
import { transcriptView } from "@/lib/questionnaireView";
import { qs } from "@/lib/strings/qs";

const TONE: Record<string, string> = {
  neutral: "text-ink-500",
  progress: "text-purple-700",
  done: "text-success-600",
  error: "text-error-600"
};

/**
 * A take's transcript state (DESIGN §3.14). Paused reads calmly in the neutral style, never as an
 * error, and the Retry control is disabled with the reason; a take refused for consent offers
 * "Change consent" inline. No control here can start a transcription while the pause is on.
 */
export function TranscriptStatus({
  recording,
  paused,
  onRetry,
  onRecordConsent
}: {
  recording: Pick<Recording, "transcript" | "consent">;
  paused: boolean;
  onRetry?: () => void;
  onRecordConsent?: () => void;
}) {
  const view = transcriptView(recording, paused);
  return (
    <span className={`inline-flex flex-wrap items-center gap-2 text-xs ${TONE[view.tone]}`} data-transcript-status={view.tone}>
      <span>{view.label}</span>
      {view.offerConsent && onRecordConsent ? (
        <button type="button" className="font-medium text-purple-700 underline" onClick={onRecordConsent}>
          {qs("recordConsent")}
        </button>
      ) : null}
      {onRetry && (view.retryEnabled || view.retryReason) ? (
        <button
          type="button"
          className="font-medium text-purple-700 underline disabled:cursor-not-allowed disabled:text-ink-300 disabled:no-underline"
          disabled={!view.retryEnabled}
          title={view.retryReason ?? undefined}
          aria-describedby={undefined}
          onClick={view.retryEnabled ? onRetry : undefined}
        >
          {qs("retry")}
        </button>
      ) : null}
      {view.retryReason ? <span className="sr-only">{view.retryReason}</span> : null}
    </span>
  );
}
