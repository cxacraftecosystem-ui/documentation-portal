"use client";

import type { QuestionnaireQuestion } from "@/lib/types";

export function QuestionHelpText({
  question,
  className
}: {
  question: QuestionnaireQuestion;
  className?: string;
}) {
  const help = (question.helpText ?? "").trim();
  if (!help) return null;
  return (
    <p className={`text-xs leading-5 text-ink-500 ${className ?? ""}`}>{help}</p>
  );
}

export function RequiredByInstrument({
  question
}: {
  question: QuestionnaireQuestion;
}) {
  if (!question.isRequired) return null;
  return (
    <span className="ml-1 align-middle text-[11px] font-medium uppercase tracking-wide text-amber-800 dark:text-amber-200">
      required
    </span>
  );
}
