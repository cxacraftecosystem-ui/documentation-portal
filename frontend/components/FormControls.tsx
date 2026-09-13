"use client";

import { Children, isValidElement, useMemo, useState, type ReactNode, type SelectHTMLAttributes } from "react";

import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";
import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { RequiredMark } from "@/components/ui/RequiredMark";

export function Field({
  label,
  children,
  required
}: {
  label: string;
  children: React.ReactNode;
  required?: boolean;
}) {
  return (
    // `min-w-0`: a grid item will not shrink below its content's intrinsic width unless told to, so
    // without this any wide child — a dropdown holding a long workshop name, a long placeholder, an
    // unbroken URL — widens the column and spills over the field beside it. Applied here rather
    // than per control so the whole form inherits it.
    <label className="grid min-w-0 gap-1">
      <span className="field-label">
        {label}
        {/*
          THE LAST OF THE SEVEN HAND-WRITTEN ASTERISKS, AND THE ONE THAT MATTERED MOST.

          `Field` is the label wrapper nearly every box in this product goes through, so the
          conditional string literal that used to sit on this line WAS most of the required marks on
          screen. It is now `components/ui/RequiredMark.tsx`, which is what makes the mark's colour a
          one-line decision instead of a seven-file hunt — see that file for why the colour could not
          land until this call site, `review/ReviewEditPanel.tsx` and `tasks/TaskPrimitives.tsx` were
          all converted in the same breath. Converting two of the three and shipping the red would
          have put a red asterisk on Name and Place beside an ink one on Craft and Status, on the
          same artisan form, at the same time.
        */}
        <RequiredMark when={required} />
      </span>
      {children}
    </label>
  );
}

export function TextInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={`field-input ${props.className ?? ""}`} />;
}

export function TextArea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} className={`field-input min-h-24 ${props.className ?? ""}`} />;
}

/**
 * Multiple free-text notes with an "Add note" button and per-note remove. Each note is its own
 * textarea; they are submitted via FormData under a single hidden input (joined by a blank line), so
 * the existing single `notes` column/handlers are unchanged. Splits an existing note back on blank
 * lines for editing.
 *
 * ── THE MIRROR STAYS `type="hidden"`, AND IT IS THE ONE CONTROL IN THIS FILE THAT MAY ───────────
 *
 * `Select`'s mirror a hundred lines below is a zero-size `type="text"` on purpose: hidden inputs are
 * exempt from constraint validation, so a `required` Select would never block a submit. This control
 * takes no `required` and no call site marks a note group mandatory, so there is nothing to
 * validate and nothing to exempt. Do not "make the two consistent" — they are consistent with the
 * rule, which is about validation and not about markup.
 *
 * ── A MICROPHONE PER NOTE, AND NO FORMATTING TOOLBAR ────────────────────────────────────────────
 *
 * `ProcessForm`'s `MultiNoteInput` is the line-for-line precedent and this is deliberately the same
 * control with the same three arguments, because these are the same column shape:
 *
 *  - **Per ROW rather than one for the group.** A single microphone over several textareas has to
 *    guess which note the phrase belongs in, and its only defensible guess (the last one) is wrong
 *    exactly when somebody is going back to fill in note two.
 *  - **`appendDictatedPhrase`, never a local joiner.** The recogniser stops and starts across a long
 *    answer, so a commit APPENDS to what is in the box; without the shared space rule a note
 *    dictated in three goes comes out as "…the warpis sized…". That rule lives in
 *    `components/richtext/dictatedValue.ts` and is shared with `DictatedTextInput`,
 *    `DictatedTextArea` and `ProcessForm` — both of the first two already name this control in the
 *    comment above their own commit.
 *  - **`explainWhenUnavailable={false}` on every row.** A note group can be eight textareas tall, so
 *    the default would draw eight copies of the Firefox "this browser cannot dictate" paragraph
 *    inside one field, which is how a true sentence becomes wallpaper. The rule that comes with
 *    that flag is that the SURROUNDING form must carry the sentence once: `/workshops` mounts
 *    `DictationUnavailableNotice` at the top of its field grid. `/questionnaire` mounts this control
 *    too and does NOT yet mount that notice — named here, and in the record-parity spec, rather than
 *    left to look like a decision; that page belongs to another lane.
 *
 * NO RICH TEXT HERE, for the reason the joining is right above this comment: the rows are joined
 * with a blank line and torn apart again on the way back in, by this control and by Android's
 * `MultiNoteInput` in `MainActivity.kt`, against the same column. A document in one of these rows
 * comes back as one note containing JSON.
 */
export function MultiNoteField({
  name = "notes",
  defaultValue,
  label = "Notes"
}: {
  name?: string;
  defaultValue?: string | null;
  label?: string;
}) {
  const [notes, setNotes] = useState<string[]>(() => {
    const split = (defaultValue ?? "")
      .split(/\n\s*\n/)
      .map((s) => s.trim())
      .filter(Boolean);
    return split.length ? split : [""];
  });
  const joined = notes
    .map((s) => s.trim())
    .filter(Boolean)
    .join("\n\n");
  return (
    <div className="grid gap-1">
      <span className="field-label">{label}</span>
      <input type="hidden" name={name} value={joined} />
      <div className="grid gap-2">
        {notes.map((note, index) => (
          /*
            THE ROW WRAPS, AND THE BOX HAS A FLOOR. `flex-1` alone is `flex: 1 1 0%`, so a third
            control in this row would have compressed the textarea towards nothing on a phone — the
            microphone's own interim readout ("Listening… speak now.") refuses to shrink, and the
            only thing left to take the space from is the box the researcher is dictating INTO.
            `min-w-[16rem]` gives the box a floor and `flex-wrap` lets the controls drop to their own
            line underneath instead, which is the phone layout and the one this control is used on.
          */
          <div key={index} className="flex flex-wrap items-start gap-2">
            <textarea
              className="field-input min-h-16 min-w-[16rem] flex-1"
              rows={2}
              value={note}
              placeholder={notes.length > 1 ? `Note ${index + 1}` : "Note"}
              onChange={(event) => setNotes((prev) => prev.map((n, j) => (j === index ? event.target.value : n)))}
            />
            {/*
              BETWEEN THE BOX AND REMOVE, NOT AFTER IT. The tab order down a note row then reads
              write · dictate · delete, so the destructive control stays last and a reader reaching
              for the microphone with the keyboard never passes through it.
            */}
            <OnDeviceDictationButton
              fieldLabel={notes.length > 1 ? `${label}, note ${index + 1}` : label}
              explainWhenUnavailable={false}
              onCommit={(phrase) =>
                setNotes((prev) => prev.map((n, j) => (j === index ? appendDictatedPhrase(n, phrase) : n)))
              }
            />
            {notes.length > 1 ? (
              <button
                type="button"
                className="field-button-secondary shrink-0"
                onClick={() => setNotes((prev) => prev.filter((_, j) => j !== index))}
              >
                Remove
              </button>
            ) : null}
          </div>
        ))}
      </div>
      <button
        type="button"
        className="field-button-secondary justify-self-start"
        onClick={() => setNotes((prev) => [...prev, ""])}
      >
        + Add note
      </button>
    </div>
  );
}

/**
 * The readable text of an option's children, whatever shape they arrive in.
 *
 * WHY THIS IS NOT `typeof children === "string"`, which is what it replaced. A label written the
 * way every list in this app writes one — `{artisan.name} · {artisan.place}` — compiles to an
 * ARRAY of children, not a string, so that test failed and the label fell through to
 * `String(props.value)`: the record's CUID. Three live dropdowns offered `cmg...` where a name
 * belonged, two of them REQUIRED artisan pickers, and on /questionnaire the artisan picked is what
 * decides `artisanSetKey` — which interview a submission folds into. "Pick the right artisan" was
 * being asked of somebody reading twenty-five random characters, and picking the wrong one merges
 * an interview into the wrong set.
 *
 * Recursion, rather than one more special case for arrays, is the point: an option whose label is
 * wrapped in a <span> or a fragment reads correctly too, instead of being the next shape that
 * silently degrades to an id. Anything with no text of its own — null, a boolean, an <img> —
 * contributes nothing and lets the caller fall back.
 */
function optionText(node: ReactNode): string {
  if (node === null || node === undefined || typeof node === "boolean") return "";
  if (typeof node === "string") return node;
  if (typeof node === "number" || typeof node === "bigint") return String(node);
  if (Array.isArray(node)) return (node as ReactNode[]).map(optionText).join("");
  if (isValidElement(node)) return optionText((node.props as { children?: ReactNode }).children);
  return "";
}

/** Flatten the <option> children of a <Select> into themed-dropdown options. */
function optionsFromChildren(children: ReactNode): DropdownOption[] {
  const options: DropdownOption[] = [];
  Children.forEach(children, (child) => {
    if (!isValidElement(child) || child.type !== "option") return;
    const props = child.props as { value?: string | number; children?: ReactNode; disabled?: boolean };
    // A label split over two source lines keeps the newline and the indent between them; a browser
    // <select> collapses that and so must this, or the dropdown shows the author's formatting.
    const text = optionText(props.children).replace(/\s+/g, " ").trim();
    // The value is still the fallback, for an <option> that genuinely carries no text — but it is
    // now the last resort rather than the usual outcome.
    const label = text || (props.value !== undefined ? String(props.value) : "");
    const value = props.value !== undefined ? String(props.value) : label;
    options.push({ value, label, disabled: props.disabled });
  });
  return options;
}

/** Same call-site API as a browser <select> (select-flavoured value/onChange, <option> children),
 * while the remaining props land on the mirror <input> that actually lives in the form. */
type SelectProps = Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "defaultValue" | "onChange" | "type" | "children"> & {
  value?: SelectHTMLAttributes<HTMLSelectElement>["value"];
  defaultValue?: SelectHTMLAttributes<HTMLSelectElement>["value"];
  onChange?: React.ChangeEventHandler<HTMLSelectElement>;
  children?: ReactNode;
};

/**
 * Drop-in replacement for the browser <select>: same API (name / value / defaultValue / onChange /
 * disabled and <option> children) so existing forms are unchanged, but rendered as the app's themed
 * dropdown. A visually hidden input mirrors the value so uncontrolled forms still submit via
 * FormData, and mirrors `required` so native form validation works. Any remaining props are spread
 * onto that underlying input instead of being dropped.
 */
export function Select({
  name,
  value,
  defaultValue,
  onChange,
  disabled,
  required,
  className,
  children,
  "aria-label": ariaLabel,
  ...rest
}: SelectProps) {
  const options = useMemo(() => optionsFromChildren(children), [children]);
  const isControlled = value !== undefined;
  const [internal, setInternal] = useState<string>(() => {
    if (defaultValue !== undefined) return String(defaultValue);
    if (value !== undefined) return String(value);
    return options[0]?.value ?? "";
  });
  const current = isControlled ? String(value) : internal;

  function handleChange(next: string) {
    if (!isControlled) setInternal(next);
    onChange?.({ target: { value: next, name } } as unknown as React.ChangeEvent<HTMLSelectElement>);
  }

  return (
    <>
      <Dropdown
        value={current}
        onChange={handleChange}
        options={options}
        disabled={disabled}
        className={className}
        ariaLabel={typeof ariaLabel === "string" ? ariaLabel : undefined}
      />
      {name ? (
        // Not type="hidden": hidden inputs are exempt from constraint validation, so a required
        // Select would never block submission. A zero-size text input submits the value AND
        // participates in native validation.
        <input
          {...rest}
          type="text"
          name={name}
          value={current}
          required={required}
          onChange={() => undefined}
          tabIndex={-1}
          aria-hidden="true"
          className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
        />
      ) : null}
    </>
  );
}
