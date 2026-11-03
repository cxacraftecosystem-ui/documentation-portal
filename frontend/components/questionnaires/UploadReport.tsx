"use client";

import { AlertTriangle, CheckCircle2, Info, ShieldAlert } from "lucide-react";

import type { QWorkbookProvenance, QWorkbookReport } from "@/components/questionnaires/workbookApi";

function tallies(report: QWorkbookReport): Array<{ label: string; value: number }> {
  return [
    { label: "questions added", value: report.created },
    { label: "questions updated", value: report.updated ?? 0 },
    { label: "sections in the workbook", value: report.sections },
    { label: "sections added", value: report.sectionsCreated ?? 0 },
    { label: "left unchanged", value: report.unchanged },
    { label: "reworded into new questions", value: report.superseded },
    { label: "retired", value: report.retired },
    { label: "removed", value: report.removed },
    { label: "sections switched off", value: report.sectionsRetired ?? 0 },
    { label: "assigned tasks still naming a switched-off section", value: report.assignedTasksAffected ?? 0 },
    { label: "answers in the file, not imported", value: report.answersSkipped ?? 0 },
    { label: "answers imported", value: report.answersImported ?? 0 },
    { label: "uploaded sessions", value: report.sessionsCreated ?? 0 }
  ].filter((entry) => entry.value > 0);
}

function skinFor(provenance: QWorkbookProvenance): {
  box: string;
  heading: string;
  body: string;
  title: string;
  icon: React.ReactNode;
} {
  if (provenance.action === "answersImported") {
    return {
      box: "border-line-200 bg-surface-50",
      heading: "text-ink-900",
      body: "text-ink-700",
      title: "Answers imported",
      icon: <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-success-600" aria-hidden />
    };
  }
  if (provenance.action === "answersNotImported") {
    return {
      box: "border-amber-500/30 bg-amber-100",
      heading: "text-amber-800",
      body: "text-amber-800",
      title: "The answers typed into this workbook were not stored",
      icon: <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
    };
  }
  return {
    box: "border-amber-500/30 bg-amber-100",
    heading: "text-amber-800",
    body: "text-amber-800",
    title: "Something about this workbook needs saying",
    icon: <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-800" aria-hidden />
  };
}

export function UploadReport({ report, className }: { report: QWorkbookReport; className?: string }) {
  const counts = tallies(report);
  const provenance = report.provenance ?? null;
  const problems = provenance
    ? report.problems.filter((problem) => problem.reason !== provenance.reason)
    : report.problems;
  const errors = problems.filter((problem) => problem.severity === "error");
  const warnings = problems.filter((problem) => problem.severity !== "error");
  const details = report.details ?? [];
  const skin = provenance ? skinFor(provenance) : null;

  return (
    <section className={`panel grid gap-4 p-4 ${className ?? ""}`}>
      <div>
        <h2 className="font-display text-lg font-bold text-ink-900">What the upload did</h2>
        <p className="mt-1 text-sm leading-6 text-ink-muted">
          {counts.length ? (
            counts.map((entry, index) => (
              <span key={entry.label}>
                {index ? " · " : ""}
                <strong className="font-semibold text-ink-900">{entry.value}</strong> {entry.label}
              </span>
            ))
          ) : (
            <>No changes.</>
          )}
        </p>
        {report.versionAfter !== report.versionBefore ? (
          <p className="mt-1 text-xs leading-5 text-ink-500">
            Version {report.versionBefore} → {report.versionAfter}.
          </p>
        ) : null}
      </div>

      {provenance && skin ? (
        <div className={`grid gap-2 rounded-md border p-3 ${skin.box}`}>
          <div className="flex items-start gap-2">
            {skin.icon}
            <div className="min-w-0">
              <p className={`text-sm font-semibold ${skin.heading}`}>{skin.title}</p>
              <p className={`mt-1 text-sm leading-6 ${skin.body}`}>{provenance.reason}</p>
            </div>
          </div>
        </div>
      ) : null}

      {details.length ? (
        <div className="grid gap-2">
          <h3 className="field-label">What the recorded answers protected</h3>
          <ul className="grid gap-2">
            {details.map((detail) => (
              <li
                key={`${detail.action}-${detail.questionId}`}
                className="rounded-md border border-line-200 bg-surface-50 p-3"
              >
                <div className="flex items-start gap-2">
                  <Info className="mt-0.5 h-4 w-4 shrink-0 text-field-600 dark:text-purple-300" aria-hidden />
                  <div className="min-w-0">
                    <p className="text-sm text-ink-900">{detail.reason}</p>
                    {detail.before ? (
                      <p className="mt-1 text-xs leading-5 text-ink-500">
                        Kept, with its answers: <span className="text-ink-700">{detail.before}</span>
                      </p>
                    ) : null}
                    {detail.after ? (
                      <p className="text-xs leading-5 text-ink-500">
                        Added as a new question: <span className="text-ink-700">{detail.after}</span>
                      </p>
                    ) : null}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {errors.length ? <ProblemList tone="error" title="Rows that could not be read" problems={errors} /> : null}
      {warnings.length ? (
        <ProblemList tone="warning" title="Rows the import had to assume something about" problems={warnings} />
      ) : null}

      {!errors.length && !warnings.length ? (
        <p className="flex items-center gap-2 text-sm text-ink-700">
          <CheckCircle2 className="h-4 w-4 text-success-600" aria-hidden />
          Every row in the workbook was read. Nothing was skipped.
        </p>
      ) : null}
    </section>
  );
}

function ProblemList({
  tone,
  title,
  problems
}: {
  tone: "error" | "warning";
  title: string;
  problems: QWorkbookReport["problems"];
}) {
  const skin =
    tone === "error"
      ? "border-red-200 bg-error-100 text-red-800"
      : "border-amber-500/30 bg-amber-100 text-amber-800";
  return (
    <div className="grid gap-2">
      <h3 className="field-label">
        {title} ({problems.length})
      </h3>
      <ul className="grid gap-2">
        {problems.map((problem, index) => (
          <li
            key={`${problem.sheet ?? ""}-${problem.row ?? index}-${index}`}
            className={`rounded-md border p-3 text-sm ${skin}`}
          >
            <div className="flex items-start gap-2">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
              <div className="min-w-0">
                <p className="font-semibold">
                  {problem.row !== null && problem.row !== undefined ? `Row ${problem.row}` : "This workbook"}
                  {problem.sheet ? ` · sheet "${problem.sheet}"` : ""}
                </p>
                <p className="mt-0.5 leading-6">{problem.reason}</p>
                {problem.value ? <p className="mt-1 wrap-break-word text-xs opacity-80">Cell text: {problem.value}</p> : null}
              </div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
