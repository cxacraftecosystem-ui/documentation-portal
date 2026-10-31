"use client";

import { useState } from "react";
import { Loader2 } from "lucide-react";

import { askTranscript } from "@/lib/transcript/api";
import type { AskAnswer } from "@/lib/transcript/contract";
import { clock } from "@/lib/transcript/format";
import { ts } from "@/lib/transcript/strings";

import { PRIMARY, TimeChip } from "./parts";
import { TranscriptMarkdown } from "./TranscriptMarkdown";

/**
 * Ask this interview (design §10.2). Answered from this transcript only; each answer cites the turns
 * it used as timestamp chips that play the audio. When the transcript does not cover the question
 * the answer says so and carries no chips.
 */
export function AskPanel({ mediaId, onSeek }: { mediaId: string; onSeek: (ms: number) => void }) {
  const [question, setQuestion] = useState("");
  const [asking, setAsking] = useState(false);
  const [answer, setAnswer] = useState<AskAnswer | null>(null);
  const [error, setError] = useState<string | null>(null);
  const inputId = `ask-${mediaId}`;

  async function ask(event: React.FormEvent) {
    event.preventDefault();
    const text = question.trim();
    if (text.length < 3) return;
    setAsking(true);
    setError(null);
    try {
      setAnswer(await askTranscript(mediaId, text));
    } catch (failure) {
      setError(failure instanceof Error && failure.message ? failure.message : ts("loadFailed"));
    } finally {
      setAsking(false);
    }
  }

  return (
    <div className="grid gap-3 rounded-lg border border-line-200 bg-surface-50 p-3 sm:p-4" data-ask-panel>
      <form className="flex gap-2 max-sm:flex-col" onSubmit={ask}>
        <label className="sr-only" htmlFor={inputId}>
          {ts("askTitle")}
        </label>
        <input
          id={inputId}
          className="min-w-0 flex-1 rounded-md border border-line-200 bg-card px-3 py-2.5 text-sm text-ink-900 placeholder:text-ink-300 focus:border-purple-600 focus:outline-hidden focus:ring-4 focus:ring-purple-600/15"
          placeholder={ts("askPlaceholder")}
          value={question}
          maxLength={500}
          onChange={(event) => setQuestion(event.target.value)}
        />
        <button type="submit" className={PRIMARY} disabled={asking || question.trim().length < 3}>
          {asking ? <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden /> : null}
          {ts("askSend")}
        </button>
      </form>
      <div aria-live="polite" className="grid gap-2">
        {asking ? <p className="text-sm text-ink-500">{ts("askThinking")}</p> : null}
        {error ? <p className="text-sm text-error-600">{error}</p> : null}
        {answer && !asking ? (
          <div className="grid gap-2" data-ask-answer data-covered={answer.covered ? "true" : "false"}>
            <TranscriptMarkdown text={answer.covered ? answer.answer : answer.answer || ts("askNotCovered")} />
            {answer.covered && answer.citations.length ? (
              <div className="grid gap-1.5">
                <p className="text-xs font-medium text-ink-500">{ts("askFromTurns")}</p>
                <div className="flex flex-wrap gap-1.5" data-citations>
                  {answer.citations.map((citation) => (
                    <TimeChip key={`${citation.turnId}-${citation.startMs}`} ms={citation.startMs} label={clock(citation.startMs)} onSeek={onSeek} variant="chip" />
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  );
}
