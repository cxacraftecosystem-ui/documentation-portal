"use client";

import { AlertTriangle, ChevronLeft, CloudOff } from "lucide-react";
import { useState } from "react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { ProgressBar } from "@/components/ui/ProgressBar";
import type { Recording, SessionReview } from "@/lib/questionnaire/api";
import type { QuestionProgress } from "@/lib/questionnaire/coverageCache";
import { qs } from "@/lib/questionnaire/qs";

export type StillToAsk = { sectionId: string; code: string; title: string; left: number; total: number };

export const STILL_TO_ASK_SHOWN = 5;

export function stillToAsk(
  sections: { id: string; code: string; title: string }[],
  progress: QuestionProgress,
  review: SessionReview | null
): StillToAsk[] {
  if (review) {
    return review.stillToAsk
      .filter((s) => s.total > s.answered)
      .map((s) => ({ sectionId: s.sectionId, code: s.code, title: s.title, left: s.total - s.answered, total: s.total }));
  }
  return sections
    .map((s) => ({ s, p: progress.sections[s.id] }))
    .filter(({ p }) => p && p.total > 0 && p.answered < p.total)
    .map(({ s, p }) => ({ sectionId: s.id, code: s.code, title: s.title, left: p.total - p.answered, total: p.total }));
}

export function stillToAskLine(row: Pick<StillToAsk, "left" | "total">): string {
  return row.left >= row.total ? qs("notAskedCount", { count: row.total }) : qs("leftCount", { count: row.left });
}

export type FailedUpload = { key: string; code: string; onRetry: () => void };

export function ReviewFinish({
  answered,
  total,
  sectionsDone,
  names,
  pending,
  decisions,
  failed,
  still,
  open,
  onChoose,
  onOpenSection,
  onKeepGoing,
  onFinish
}: {
  answered: number;
  total: number;
  sectionsDone: number;
  names: string;
  pending: number;
  decisions: Recording[];
  failed: FailedUpload[];
  still: StillToAsk[];
  open: boolean;
  onChoose: (recording: Recording) => void;
  onOpenSection: (sectionId: string) => void;
  onKeepGoing: () => void;
  onFinish: () => void;
}) {
  const [all, setAll] = useState(false);
  const shown = all ? still : still.slice(0, STILL_TO_ASK_SHOWN);
  const percent = total > 0 ? Math.round((answered / total) * 100) : 0;
  const codeOf = (r: Recording) => r.targets[0]?.displayCode ?? r.targets[0]?.label ?? qs("wholeSession");
  return (
    <div className="mx-auto grid w-full max-w-[60rem] gap-6" data-review-finish>
      <div className="flex items-center gap-2">
        <button
          type="button"
          aria-label={qs("backToInterview")}
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-line-200 bg-card text-ink-900 transition hover:border-purple-300"
          onClick={onKeepGoing}
        >
          <ChevronLeft className="h-5 w-5" aria-hidden />
        </button>
        <h1 className="display-title ml-2 text-[1.875rem] leading-9">{qs("review")}</h1>
      </div>

      <section className="panel flex flex-wrap items-center gap-x-8 gap-y-5 p-6" data-review-summary>
        <div className="grid min-w-0 flex-[1_1_20rem] gap-2.5">
          <span className="font-display text-[1.375rem] font-bold leading-[1.875rem] text-ink-900">{qs("questionsAnswered", { answered, total })}</span>
          <ProgressBar percent={percent} className="h-2" aria-hidden />
          <span className="text-sm text-ink-700">{qs("reviewSummary", { sections: sectionsDone, names, pending })}</span>
        </div>
        <ButtonRow className="max-sm:w-full">
          <button type="button" className="field-button-secondary min-h-12 px-6 font-semibold" onClick={onKeepGoing}>
            {qs("keepGoing")}
          </button>
          {open ? (
            <button type="button" className="field-button min-h-12 px-7 font-semibold" onClick={onFinish} data-review-finish-button>
              {qs("finish")}
            </button>
          ) : null}
        </ButtonRow>
      </section>

      {decisions.length || failed.length ? (
        <section className="grid gap-2" data-needs-you>
          <h2 className="field-label">{qs("needsYou")}</h2>
          {decisions.map((r) => (
            <button
              key={r.id}
              type="button"
              className="panel flex flex-wrap items-center gap-x-4 gap-y-2 px-6 py-4 text-left transition hover:border-purple-300"
              onClick={() => onChoose(r)}
              data-needs-decision={r.id}
            >
              <AlertTriangle className="h-5 w-5 shrink-0 text-amber-800 dark:text-amber-100" aria-hidden />
              <span className="grid min-w-0 flex-[1_1_15rem] gap-0.5">
                <span className="text-base font-semibold text-ink-900">{qs("hasTwoTakes", { code: codeOf(r) })}</span>
                <span className="text-[0.8125rem] text-ink-500">{qs("chooseWhichToKeep")}</span>
              </span>
              <span className="text-sm font-semibold text-purple-700 dark:text-purple-300">{qs("choose")}</span>
            </button>
          ))}
          {failed.map((f) => (
            <div key={f.key} className="panel flex flex-wrap items-center gap-x-4 gap-y-2 px-6 py-4" data-failed-upload={f.key}>
              <CloudOff className="h-5 w-5 shrink-0 text-error-600" aria-hidden />
              <span className="grid min-w-0 flex-[1_1_15rem] gap-0.5">
                <span className="text-base font-semibold text-ink-900">{qs("takeDidNotUpload", { code: f.code })}</span>
                <span className="text-[0.8125rem] text-ink-500">{qs("uploadFailed")}</span>
              </span>
              <button type="button" className="min-h-11 text-sm font-semibold text-purple-700 underline dark:text-purple-300" onClick={f.onRetry}>
                {qs("retry")}
              </button>
            </div>
          ))}
        </section>
      ) : null}

      {still.length ? (
        <section className="grid gap-2" data-still-to-ask>
          <h2 className="field-label">{qs("stillToAsk", { count: still.length })}</h2>
          <ul className="panel overflow-hidden">
            {shown.map((row) => {
              const started = row.left < row.total;
              return (
                <li key={row.sectionId} className="border-b border-line-200 last:border-b-0">
                  <button type="button" className="flex min-h-15 w-full items-center gap-4 px-6 text-left transition hover:bg-field-100" onClick={() => onOpenSection(row.sectionId)}>
                    <span
                      aria-hidden
                      className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-sm font-display text-[0.9375rem] font-bold ${
                        started ? "bg-field-100 text-ink-900" : "border border-line-200 text-ink-700"
                      }`}
                    >
                      {row.code}
                    </span>
                    <span className="min-w-0 flex-1 text-[0.9375rem] font-semibold text-ink-900">{row.title}</span>
                    <span className="shrink-0 text-[0.8125rem] text-ink-500">{stillToAskLine(row)}</span>
                  </button>
                </li>
              );
            })}
          </ul>
          {still.length > shown.length ? (
            <div className="flex justify-center pt-1">
              <button type="button" className="field-button-secondary min-h-11 rounded-full px-5 font-semibold" onClick={() => setAll(true)}>
                {qs("showAll", { count: still.length })}
              </button>
            </div>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}
