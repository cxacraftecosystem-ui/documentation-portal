"use client";

/**
 * A plain multi-line box with a microphone, and deliberately NOT a rich-text editor.
 *
 * WHY THIS EXISTS ALONGSIDE `RichTextField`. The decision the user made was "only the larger text
 * boxes need the rich text features" — and a handful of boxes on these forms are multi-line without
 * being narrative. An artisan's postal address is the clear case: it is three lines, so a researcher
 * standing in a courtyard genuinely wants to speak it rather than thumb it in, but a bold word or a
 * bulleted list in an address is meaningless, and a formatting toolbar there would be an invitation
 * to store a document in a column that `record_fields.py:257` prints straight into the artisan CSV
 * and the XLSX workbook as a delivery address, and that the review panel edits as plain text.
 *
 * So the two capabilities are separable and this is the dictation-only half. It is a real
 * `<textarea>` with a real `name`, so `FormData`, `textValue`, the browser's own spellcheck and
 * every existing form behaviour are exactly as they were; the only addition is the button under it.
 *
 * COMMITTING APPENDS, NEVER REPLACES. The recogniser is stopped and started many times across a long
 * answer, and a commit that overwrote the box would delete everything already in it the moment
 * somebody paused for breath. The join is a single space unless the box already ends in whitespace,
 * because without it a paragraph dictated in five goes comes out as "…the warpis sized…".
 */

import { useId, useState } from "react";

import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";

export function DictatedTextArea({
  name,
  label,
  defaultValue,
  helper,
  disabled,
  rows,
  className,
  onDirty
}: {
  name: string;
  label: string;
  defaultValue?: string | null;
  helper?: string;
  disabled?: boolean;
  rows?: number;
  className?: string;
  /** The form's `markDirty`. A dictated phrase is a React state write, not a typed `input` event. */
  onDirty?: () => void;
}) {
  const reactId = useId();
  const helpId = `dta-${reactId}-help`;

  /**
   * CONTROLLED, and it has to be: dictation writes into the box from outside the keyboard, and an
   * uncontrolled textarea would need a ref plus a manual `input` dispatch to keep React and the DOM
   * agreeing about what is in it. The forms read the value through `FormData` at submit time either
   * way, so nothing downstream can tell the difference.
   */
  const [value, setValue] = useState(defaultValue ?? "");

  /**
   * `onDirty` is fired from the two places that CHANGE the value, never from an effect watching it.
   *
   * An effect would fire once on mount, when the box is seeded from `defaultValue` on an edit form,
   * and every edit form would then pop the unsaved-changes dialog on the way out of a record nobody
   * touched. Researchers learn to click through that dialog, and then it stops protecting anything.
   */
  function update(next: string) {
    setValue(next);
    onDirty?.();
  }

  return (
    <div className="grid min-w-0 gap-1">
      {/*
        A `<label htmlFor>` and not the `Field` wrapper: `Field` is a `<label>` element, and the
        dictation button below is a second control inside it. Clicking "Dictate" would then also
        focus the textarea, which on a phone throws the on-screen keyboard up over the very readout
        the researcher is trying to watch.
      */}
      <label className="field-label" htmlFor={`${reactId}-box`}>
        {label}
      </label>
      {helper ? (
        <p id={helpId} className="text-xs text-ink-muted">
          {helper}
        </p>
      ) : null}
      <textarea
        id={`${reactId}-box`}
        name={name}
        rows={rows}
        disabled={disabled}
        aria-describedby={helper ? helpId : undefined}
        className={`field-input min-h-24 ${className ?? ""}`}
        value={value}
        onChange={(event) => update(event.target.value)}
      />
      <OnDeviceDictationButton
        fieldLabel={label}
        disabled={disabled}
        onCommit={(phrase) => {
          const joiner = !value || /\s$/.test(value) ? "" : " ";
          update(`${value}${joiner}${phrase}`);
        }}
      />
    </div>
  );
}
