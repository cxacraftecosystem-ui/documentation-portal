"use client";

import { RichTextField } from "@/components/richtext/RichTextField";
import { decodeStoredRichText } from "@/components/richtext/storedRichText";
import { fromStored, toPlain } from "@/lib/richText";

/**
 * Field Repository's answer box for the shared questionnaire (decision C8): the portal's own
 * rich-text editor, writing designer-portal's stored format, which this portal's editor shares.
 *
 * The shared `AnswerBox` keeps the editor's stored string and sends it as two things: the canonical
 * plain `text` (coverage, the lock, search and exports read it) and, when the answer is formatted,
 * the document as `richText`. An unformatted answer is prose, so it is sent as text alone.
 *
 * `onCommit` fires when focus leaves the whole control (the editor, its toolbar and its microphone),
 * not when it moves between them: pressing Bold must not save half a thought.
 */
export function AnswerTextEditor({
  value,
  onChange,
  onCommit,
  readOnly,
  ariaLabel,
  required
}: {
  value: string;
  onChange: (value: string) => void;
  onCommit: () => void;
  readOnly?: boolean;
  ariaLabel: string;
  required?: boolean;
}) {
  return (
    <div
      className="grid gap-1"
      data-answer-editor
      data-locked={readOnly || undefined}
      aria-required={required || undefined}
      onBlur={(event) => {
        const next = event.relatedTarget as Node | null;
        if (next && event.currentTarget.contains(next)) return;
        onCommit();
      }}
    >
      <RichTextField
        name="answer"
        label={ariaLabel}
        defaultValue={value}
        disabled={readOnly}
        explainWhenUnavailable={false}
        onValueChange={onChange}
      />
    </div>
  );
}

/** The two halves a stored editor value is sent as (C8): plain `text`, and the document when formatted. */
export function answerParts(stored: string): { text: string; richText: string | null } {
  const decoded = decodeStoredRichText(stored);
  if (decoded && typeof decoded === "object") return { text: toPlain(fromStored(decoded)).trim(), richText: stored };
  return { text: stored, richText: null };
}

/** What the editor opens with for an answer: its document when it has one, else its text. */
export function answerEditorValue(answer: { text?: string | null; richText?: unknown } | null | undefined): string {
  const rich = answer?.richText;
  if (typeof rich === "string" && rich.trim()) return rich;
  if (rich && typeof rich === "object") return JSON.stringify(rich);
  return answer?.text ?? "";
}
