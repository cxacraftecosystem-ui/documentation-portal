"use client";

/**
 * What an upload actually did, shown in full.
 *
 * THIS PANEL IS THE FEATURE, not diagnostics for it. The backend route says so in its own docstring
 * and it is worth restating here, because this is the component a later change is most likely to
 * quietly trim: an admin who uploads eighty-one questions and is shown seventy-nine, with no way to
 * find out which two are missing or why, does not trust the import again — and every likely cause (a
 * merged cell, a formula Excel never calculated, "maybe" typed in the Required column) is invisible
 * from the result. So every problem the parser reported is printed, with its Excel row number, and
 * every question the edit rule superseded or retired is named with the server's own sentence.
 *
 * The row numbers are 1-based worksheet rows exactly as Excel's gutter shows them, which is what
 * makes them worth printing at all: "row 34" means press Ctrl+G and type 34.
 *
 * The counts are drawn even when everything went perfectly. A silent success and a success that
 * quietly dropped six rows must not look the same, and the only way to tell them apart is to always
 * say how many of each thing happened.
 */

import { AlertTriangle, CheckCircle2, Info, ShieldAlert } from "lucide-react";

import type { QWorkbookProvenance, QWorkbookReport } from "@/components/questionnaires/workbookApi";

/**
 * The counts worth printing, in the order an admin reads them, paired with the wording each one
 * needs. Held as data rather than as a dozen hand-written spans so a count cannot be added to the
 * report and silently left off the screen — the failure this whole panel exists to prevent, one
 * level down.
 */
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
    // Printed as its own count rather than folded into the provenance sentence below, because a
    // number an admin can compare against what they saw in Excel is what turns "the app decided
    // something" into "the app decided this much".
    { label: "answers in the file, not imported", value: report.answersSkipped ?? 0 }
  ].filter((entry) => entry.value > 0);
}

/**
 * The heading, tone and icon for one provenance outcome.
 *
 * A LOOKUP RATHER THAN A TERNARY, and that is a bug fix rather than tidying. The equivalent block in
 * the sibling application used to read `action === "answersNotImported" ? A : B`, so the moment a
 * THIRD action existed every other value fell into B — and B's heading made a claim about whose name
 * some answers had been recorded under. A heading that is confidently wrong about authorship is the
 * exact failure the provenance field was added to prevent, made by the panel that exists to prevent
 * it.
 *
 * The DEFAULT is therefore the cautious one. An action this component has never heard of gets the
 * amber treatment and the server's own sentence, which is honest about not knowing rather than
 * confidently wrong.
 */
function skinFor(provenance: QWorkbookProvenance): {
  box: string;
  heading: string;
  body: string;
  title: string;
  icon: React.ReactNode;
} {
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
  // The provenance sentence is ALSO pushed into `problems` by the server, so that a client which
  // renders only the problem list still tells the admin about it. This panel renders both, so the
  // copy in the problem list is dropped here — printing one sentence twice, once in its own block
  // and once under "rows the import had to assume something about", reads as two separate events.
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
            <>Nothing in the workbook differed from what is already stored, so nothing was changed.</>
          )}
        </p>
        {report.versionAfter !== report.versionBefore ? (
          <p className="mt-1 text-xs leading-5 text-ink-500">
            This questionnaire moved from version {report.versionBefore} to {report.versionAfter}. The version counts
            edits made after answers existed, so anyone holding an older copy of the form — a phone that has not
            refreshed, an interview part-way through — can tell theirs is out of date.
          </p>
        ) : null}
      </div>

      {/*
        WHAT HAPPENED TO THE ANSWERS THE WORKBOOK ALREADY CARRIED.

        An answer in this repository belongs to an interview: it names the artisan who gave it, where
        it was recorded and who recorded it. A spreadsheet column headed "Answer" supplies none of
        that, so the import counts what was typed there and stores none of it. Silence is not an
        option — somebody who typed forty answers into the sheet and sees nothing about them
        afterwards will conclude they were imported. `reason` is written on the server to be shown
        as-is; this is the third place in the stack that could paraphrase the rule and the one where
        paraphrasing it would cost an admin their understanding of where answers live.
      */}
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

      {/*
        The edit rule's own account of itself, printed VERBATIM.

        `reason` is written on the server to be shown as-is, and paraphrasing it here would make this
        the fourth place that explains the supersede/retire rule slightly differently. An admin whose
        six reworded questions came back as six NEW questions has to be told that happened and that
        the recorded answers are safe — a question count cannot say it.
      */}
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
                  <Info className="mt-0.5 h-4 w-4 shrink-0 text-field-600" aria-hidden />
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

      {/*
        Errors before warnings, and both with the row number first. An "error" is a row where NOTHING
        was stored; a "warning" is a row that was stored but something had to be assumed. Those are
        two different jobs for the admin — one is a question they have lost, the other is a question
        that may say something they did not mean — so they are never merged into one list.
      */}
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
  // amber-100/amber-800 and error-100/error-600 are the brand rungs; amber-50 and amber-200 are
  // stock Tailwind and do not pair with them (the config deep-merges the two scales).
  const skin =
    tone === "error"
      ? "border-red-200 bg-error-100 text-error-600"
      : "border-amber-500/30 bg-amber-100 text-amber-800";
  return (
    <div className="grid gap-2">
      <h3 className="field-label">
        {title} ({problems.length})
      </h3>
      {/*
        NO "and 12 more". There is no cap on this list and there must not be one. A cap is how an
        admin ends up looking at the first five of forty problems and concluding the import went
        mostly fine — and the parser has already capped what it reports at the source
        (MAX_QUESTIONS / MAX_SECTIONS in app/services/questionnaire_xlsx.py), so a second cap here
        would hide rows that survived the first. If the list is long, the workbook is wrong, and the
        length is the message. The same rule forbids replacing `reason` with a category chip: the
        sentence is written on the server to be read, and a chip is a summary wearing a badge.
      */}
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
                {problem.value ? <p className="mt-1 break-words text-xs opacity-80">Cell text: {problem.value}</p> : null}
              </div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
