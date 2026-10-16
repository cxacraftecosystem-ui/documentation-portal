"use client";

/**
 * WHAT EACH OF THE TWO DOWNLOADS DOES WHEN IT IS UPLOADED BACK — said on screen, before anybody
 * presses anything.
 *
 * THIS PANEL IS A SAFETY CONTROL, NOT AN EXPLAINER. A questionnaire produces two .xlsx files with
 * the same title on them, that land in the same Downloads folder, and that look identical in a file
 * picker. The difference is not what they contain — neither contains an answer — it is what
 * uploading one DOES:
 *
 *   * THIS QUESTIONNAIRE (.xlsx) carries the Question IDs. Uploading it back EDITS the instrument
 *     every researcher in the field is answering this week.
 *   * The QUESTION SET has the ids blanked. Uploading it creates a NEW questionnaire beside it.
 *
 * Choosing the wrong one is not a mistake the app can undo, and the server cannot prevent it either:
 * an admin is entitled to both files. The only thing standing between somebody and that mistake is
 * knowing, at the moment they choose, which file is which — and the filename suffix carries that
 * difference and nothing else does. So this is drawn wherever either download is offered, and it
 * names both rather than only the one being pressed.
 *
 * IT ALSO STATES WHAT AN UPLOAD DOES WITH ANSWERS, because that is the same fact read backwards and
 * it is the one people guess wrong: a workbook with the Answer column filled in imports its
 * QUESTIONS and reports its answers back uncounted-into-the-database. An admin who types forty
 * answers into a sheet and finds none of them on the Consolidated page must be able to find out why
 * without asking anybody.
 *
 * Tokens only — `ink-*`, `line-200`, `surface-50` and the brand `amber-100`/`amber-800` rungs (never
 * `amber-50`/`amber-200`, which are stock Tailwind and do not pair with them). Nothing here inverts
 * incorrectly in dark mode as a result.
 */

import { useId, useState } from "react";
import { ChevronDown, FilePlus2, FileSpreadsheet, Pencil, Upload } from "lucide-react";

/**
 * THE SUMMARY IS ALWAYS ON SCREEN AND THE DETAIL IS BEHIND A DISCLOSURE, which is the one shape that
 * serves both readers.
 *
 * A four-paragraph panel pinned to the top of an admin page is read once and scrolled past for ever
 * afterwards, so the sentence that matters would be invisible on exactly the hundredth visit, which
 * is the visit where the wrong file gets uploaded. A disclosure that starts CLOSED with a neutral
 * label hides the warning from the person who has never met it.
 *
 * So the load-bearing sentence — which file edits the live instrument and which starts a new one —
 * is never collapsed, and only the elaboration is. Plain conditional rendering rather than an
 * animated height: there is nothing here worth a motion branch, and an un-animated disclosure needs
 * no reduced-motion counterpart to get wrong.
 */
export function ArtefactNotice({ className }: { className?: string }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();

  return (
    <section className={`panel grid gap-4 p-4 ${className ?? ""}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="font-display text-lg font-bold text-ink-900">Two downloads</h2>
          <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-muted">
            <span className="font-medium text-ink-900">This questionnaire</span> edits this one.{" "}
            <span className="font-medium text-ink-900">Question set</span> starts a new one. Neither contains answers.
          </p>
        </div>
        <button
          type="button"
          className="field-button-secondary"
          aria-expanded={open}
          aria-controls={open ? panelId : undefined}
          onClick={() => setOpen((current) => !current)}
        >
          <ChevronDown className={`h-4 w-4 ${open ? "rotate-180" : ""}`} aria-hidden />
          {open ? "Hide details" : "Details"}
        </button>
      </div>

      {open ? (
        <div id={panelId} className="grid gap-4">
          <ArtefactDetail />
        </div>
      ) : null}
    </section>
  );
}

/** The elaboration behind the disclosure. Its own component so the shell above stays one screen. */
function ArtefactDetail() {
  return (
    <>
      <div className="grid gap-3 md:grid-cols-2">
        <article className="grid gap-2 rounded-md border border-amber-500/30 bg-amber-100 p-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-amber-800">
            <Pencil className="h-4 w-4 shrink-0" aria-hidden />
            This questionnaire (.xlsx) — edits the live instrument
          </h3>
          <p className="text-sm leading-6 text-amber-800">
            Uploading it changes the live questions. Don’t edit or delete the Question ID column, or rows import as
            new questions.
          </p>
        </article>

        <article className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
            <FilePlus2 className="h-4 w-4 shrink-0 text-field-600" aria-hidden />
            Question set — starts a new questionnaire
          </h3>
          <p className="text-sm leading-6 text-ink-700">
            Active questions without IDs. Uploading it creates a new questionnaire.
          </p>
        </article>
      </div>

      <div className="grid gap-2 rounded-md border border-line-200 p-3">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
          <Upload className="h-4 w-4 shrink-0 text-field-600 dark:text-purple-300" aria-hidden />
          Answers in an uploaded file
        </h3>
        <ul className="grid gap-1.5 text-sm leading-6 text-ink-700">
          <li className="flex items-start gap-2">
            <FileSpreadsheet className="mt-1 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
            <span>
              <span className="font-medium text-ink-900">Counted, not saved.</span> Record answers on the
              Questionnaire page.
            </span>
          </li>
        </ul>
      </div>
    </>
  );
}
