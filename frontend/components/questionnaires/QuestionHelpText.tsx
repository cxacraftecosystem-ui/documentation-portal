"use client";

import type { QuestionnaireQuestion } from "@/lib/types";

/**
 * The pro-forma's "Help text" and "Required" columns, on the screen the answers are given on.
 *
 * WHY THIS EXISTS AT ALL. The pro-forma asks an admin to type guidance under a question — "count
 * from the first year they worked unsupervised", "ask about the vat, not the colour" — and the
 * import stores it. If nothing renders it, the app has asked a question and then ignored the answer:
 * the admin types eighty-one help texts, the database holds eighty-one help texts, and no researcher
 * ever sees one. That is the defect the migration adding the column spends two paragraphs forbidding,
 * one layer out.
 *
 * ── THE INTERSECTION THAT USED TO BE HERE IS GONE, AS ITS OWN DOCSTRING INSTRUCTED ──────────────
 *
 * This signature read `QuestionnaireQuestion & { helpText?: string | null; isRequired?: boolean }`
 * for exactly as long as `lib/types.ts` did not declare the two fields: the server sent them, the
 * type had not caught up, and the intersection let this component render them without editing a type
 * owned by another change in flight. That change has landed — `QuestionnaireQuestion` now carries
 * `helpText`, `isRequired`, `retiredAt` and `supersededById`, each with the argument for its own
 * optionality — so the bridge is removed rather than left standing.
 *
 * WHAT REMOVING IT BUYS, because "it compiled either way" was true of the bridge too. An intersection
 * of a named type with an inline shape accepts a `question` object that satisfies EITHER half, so a
 * caller passing something shaped like `{ helpText }` and nothing else type-checked here. With the
 * plain type, the parameter is the record the API actually returns: a misspelling reaching in
 * (`question.helpTxt`) is a compile error at the property access rather than a line that silently
 * renders nothing, which is the whole failure mode this component was written against.
 */

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

/**
 * The mandatory marker, from the same two columns.
 *
 * SEPARATE FROM THE HELP TEXT because they sit in different places on the screen: the marker belongs
 * beside the prompt, in the label, and the guidance belongs under it. One component rendering both
 * would force whichever of the two it was placed for into the wrong position.
 *
 * `components/ui/RequiredMark.tsx` is the repository's asterisk and is NOT used here on purpose: it
 * is drawn from a boolean the FORM owns ("this box must be filled in before you can save"), and this
 * one is a fact the INSTRUMENT states ("the ministry asks for this"). The questionnaire page does not
 * block a save on it — a researcher in a courtyard with an artisan who will not answer question 54
 * must still be able to record the other eighty — so a marker that looked identical to a validation
 * asterisk would promise an enforcement that does not exist. It is a quiet word instead.
 */
export function RequiredByInstrument({
  question
}: {
  question: QuestionnaireQuestion;
}) {
  if (!question.isRequired) return null;
  return (
    <span className="ml-1 align-middle text-[11px] font-medium uppercase tracking-wide text-amber-800">
      required
    </span>
  );
}
