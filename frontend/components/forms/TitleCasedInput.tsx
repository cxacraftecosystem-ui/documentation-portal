"use client";

import { useId, useState, type InputHTMLAttributes } from "react";

import { TextInput } from "@/components/FormControls";
import { titleCasePreview } from "@/lib/format";

/**
 * A name-like text box that says what the API will actually store.
 *
 * The server title-cases every column in `TITLE_CASE_FIELDS` on WRITE (see
 * `backend/app/services/text_format.py`), so a researcher who types "kutch-bhuj" gets "Kutch-Bhuj"
 * in the database. Android has always shown that up front ("Will be saved as …"); the web showed
 * nothing, so the value changed silently AFTER saving and the form and the record disagreed. This is
 * the web half of that mirror — `lib/format.titleCase` is a faithful port of the server rule, so the
 * hint and the stored value never diverge.
 *
 * Deliberately quiet: the hint only appears when the normalised value DIFFERS from what was typed,
 * so a correctly-typed name adds no noise to a form that already has thirty fields.
 *
 * Drop-in for `<TextInput>` — same props, controlled or uncontrolled. Uncontrolled call sites
 * (`defaultValue`) keep working because the component tracks what was typed only to render the hint;
 * the input itself still submits through FormData exactly as before.
 */
export function TitleCasedInput({
  onChange,
  "aria-describedby": describedBy,
  ...props
}: InputHTMLAttributes<HTMLInputElement>) {
  const hintId = `${useId()}-title-case`;
  const [typed, setTyped] = useState(() => String(props.defaultValue ?? props.value ?? ""));
  // A controlled call site owns the value; an uncontrolled one is shadowed here for the hint alone.
  const current = props.value !== undefined ? String(props.value) : typed;
  const preview = titleCasePreview(current);

  return (
    <>
      <TextInput
        {...props}
        aria-describedby={[describedBy, preview ? hintId : null].filter(Boolean).join(" ") || undefined}
        onChange={(event) => {
          setTyped(event.currentTarget.value);
          onChange?.(event);
        }}
      />
      {preview ? (
        <span className="text-xs text-ink-muted" id={hintId}>
          Will be saved as &ldquo;{preview}&rdquo;
        </span>
      ) : null}
    </>
  );
}
