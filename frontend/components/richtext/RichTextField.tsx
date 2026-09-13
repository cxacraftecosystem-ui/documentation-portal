"use client";

/**
 * A larger narrative box on a record form: the rich-text editor, an on-device microphone, and a
 * hidden input so the form that contains it does not have to learn anything new.
 *
 * WHY A WRAPPER AND NOT FOUR COPIES OF THE WIRING. The artisan, product, tool and process forms all
 * submit through `FormData` — `textValue(form, "remarks")` and friends read named controls out of
 * the form element at submit time. `RichTextEditor` is a `contenteditable`, which `FormData` cannot
 * see. Every call site would otherwise need its own piece of state, its own hidden input, its own
 * encode call and its own label plumbing, and the SEVENTH copy would be the one that forgot the
 * encode and stringified a document into a column that `products.py:89-91` searches with a raw
 * `contains`. One component, seven one-line call sites, one place where the encode rule lives.
 *
 * IT DOES NOT USE `Field`. `Field` renders a `<label>`, and this control is not one control: it is a
 * toolbar of eight groups of buttons, a find bar with two text inputs, a file picker and an editing
 * surface. A `<label>` wrapping several controls has no defined behaviour, and in practice a click
 * on the Bold button would also move focus to the editor and lose the selection being bolded. So
 * the label is a `<span>` tied on with `aria-labelledby`.
 *
 * THE MICROPHONE IS INSIDE THE EDITOR, not beside it, and that is deliberate: a dictated phrase has
 * to be inserted into the document model AT THE CARET, with the surrounding run's marks and as one
 * undoable step. A button outside could only append a string to the whole value, which is what
 * `DictatedTextArea` does next door for the plain boxes and precisely what would flatten a formatted
 * field here. There is no prop to get this wrong with: the editor mounts the on-device microphone
 * unconditionally, and nothing it hears leaves the device.
 */

import { useId, useState } from "react";

import { RichTextEditor } from "@/components/richtext/RichTextEditor";
import {
  decodeStoredRichText,
  encodeStoredRichText,
  type PlainBlockJoin
} from "@/components/richtext/storedRichText";
import type { RichBlockKind, StoredRichDoc } from "@/lib/richText";

export function RichTextField({
  name,
  label,
  defaultValue,
  helper,
  disabled,
  maxLength,
  listKind,
  /** See {@link PlainBlockJoin} — "paragraph" for any column a multi-note control also reads. */
  join = "line",
  placeholder,
  /**
   * Grid placement, and on these forms it is almost always `md:col-span-2`.
   *
   * The record forms lay their fields out two to a row, which is right for a name and a date and
   * wrong for this: the toolbar carries eight groups of controls and wraps to four rows inside a
   * half-width column, so the chrome ends up taller than the box it belongs to. Spanning both
   * columns is not decoration — it is what keeps the formatting controls on one line.
   */
  className,
  explainWhenUnavailable,
  onDirty,
  onValueChange
}: {
  /** The FormData key the containing form already reads. Unchanged from the `<TextArea>` it replaces. */
  name: string;
  label: string;
  /** The stored column value, exactly as the API returned it: prose, a JSON document, or null. */
  defaultValue?: string | null;
  helper?: string;
  disabled?: boolean;
  maxLength?: number;
  listKind?: RichBlockKind;
  join?: PlainBlockJoin;
  placeholder?: string;
  className?: string;
  /**
   * The form's `markDirty`.
   *
   * Belt and braces: the forms already carry `onInput={markDirty}` on the `<form>` element and a
   * `contenteditable` fires bubbling `input` events, so typing is caught. A DICTATED phrase is not
   * typed — it arrives through `insertText` and rebuilds the surface from the model, which does not
   * fire `input` — and neither does a toolbar press. Without this, a researcher who only ever
   * dictated into a field could navigate away and be told there was nothing to lose.
   */
  onDirty?: () => void;
  /**
   * Let the editor's dictation button draw its own "this browser cannot dictate" sentence, or not.
   *
   * FORWARDED, NOT DECIDED HERE. `OnDeviceDictationButton` says once, under itself, that the browser
   * has no recogniser, which is right for a lone control and wrong for a form that mounts four of
   * them: `ProductForm` would print the form-level notice PLUS one copy under each of its four
   * rich-text boxes on Firefox. Every record form renders `DictationUnavailableNotice` once at the
   * top and passes `false` to each control below it — this editor was the one control that could not
   * be told.
   *
   * DEFAULTS TO UNDEFINED, which the button reads as its own default of true, so a caller that says
   * nothing behaves exactly as it did before this prop existed.
   */
  explainWhenUnavailable?: boolean;
  /**
   * The encoded column value, reported on every change, for a form that does not submit through
   * `FormData`.
   *
   * ADDITIVE AND OPTIONAL, and the hidden input below stays regardless: all seven existing call
   * sites read their value with `textValue(form, name)` at submit time and must go on working
   * untouched. Removing the hidden input to "simplify now that there is a callback" would make
   * Artisan ×1, Product ×4 and Tool ×2 POST `null` for seven prose columns — which the API accepts
   * happily, so nothing anywhere would refuse and the researcher's words would simply be gone.
   *
   * `ProcessForm` is the form that needs this — it builds its request body out of React state
   * (`components/forms/ProcessForm.tsx:600-616`) and never constructs a `FormData` at all, so a
   * hidden input is invisible to it and a `name=`-only child silently submits nothing.
   *
   * IT REPORTS THE ENCODED STRING, NOT THE DOCUMENT, which is the same value the hidden input
   * carries and the same one the API stores. Handing back the document would make the caller
   * responsible for `encodeStoredRichText` and its `join` argument, which is exactly the knowledge
   * this component exists to hold in one place.
   *
   * The caller must NOT feed the reported string back in as `defaultValue` on the next render —
   * read the note on `initialValue` below for the caret it would throw to position zero.
   */
  onValueChange?: (value: string) => void;
}) {
  const reactId = useId();
  const labelId = `rtf-${reactId}-label`;
  const helpId = `rtf-${reactId}-help`;

  /**
   * The document the editor starts with, parsed exactly ONCE and then never handed back.
   *
   * This is not laziness, it is the caret. `RichTextEditor` re-seeds itself whenever the `value` it
   * is given describes a different document from the one it last emitted (`storedSignature`). If
   * this component fed its own encoded string back in as `value`, every keystroke would round-trip
   * document → plain text → document, and any spelling difference in that round trip — an empty
   * paragraph, which `fromPlainText` drops — would read as an external change and throw the caret to
   * position zero mid-sentence. The editor owns the live document; this component owns the string
   * that gets submitted; neither reads the other's copy.
   */
  const [initialValue] = useState<unknown>(() => decodeStoredRichText(defaultValue));
  const [submitValue, setSubmitValue] = useState<string>(() => (defaultValue ?? "").trim());

  return (
    <div className={`grid min-w-0 gap-1 ${className ?? ""}`}>
      <span id={labelId} className="field-label">
        {label}
      </span>
      {helper ? (
        <p id={helpId} className="text-xs text-ink-muted">
          {helper}
        </p>
      ) : null}
      {/*
        The only thing the containing form sees. `name` is the same key the `<textarea>` used, so
        `textValue(form, name)` at submit time is untouched — which is why adding rich text to these
        forms needed no change to any payload builder except the three that machine-append an EXIF
        remark (see `appendStoredParagraph`).
      */}
      <input type="hidden" name={name} value={submitValue} />
      <RichTextEditor
        value={initialValue}
        onChange={(doc: StoredRichDoc | null) => {
          // ORDER MATTERS AND IS NOT ARBITRARY: `setSubmitValue` first, so the hidden input and the
          // reported string can never disagree; `onValueChange` before `onDirty`, so a guard that
          // reads the reported value sees the new one rather than the previous render's.
          const encoded = encodeStoredRichText(doc, join);
          setSubmitValue(encoded);
          onValueChange?.(encoded);
          onDirty?.();
        }}
        disabled={disabled}
        ariaLabelledBy={labelId}
        // Also the name the microphone reads out: "Dictate Remarks in English (India)".
        ariaLabel={label}
        // Forwarded so a form that already says it once at the top can stop each editor saying it
        // again — see the prop's own note on `RichTextEditor`.
        explainWhenUnavailable={explainWhenUnavailable}
        maxLength={maxLength}
        listKind={listKind}
        {...(placeholder ? { placeholder } : {})}
      />
    </div>
  );
}
