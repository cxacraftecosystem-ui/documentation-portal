"use client";

import { Plus, X } from "lucide-react";
import { useRef, useState } from "react";

/** Android parity (MainActivity.kt splitNumbered): stored newline-joined string → editable rows. */
function splitNumbered(value: string | null | undefined): string[] {
  const rows = (value ?? "")
    .split("\n")
    .map((row) => row.trim())
    .filter(Boolean);
  return rows.length ? rows : [""];
}

/** Android parity (joinNumbered): rows → newline-joined stored string, blank rows dropped. */
function joinNumbered(items: string[]): string {
  return items
    .map((row) => row.trim())
    .filter(Boolean)
    .join("\n");
}

/**
 * Web twin of Android's NumberedListInput (MainActivity.kt): a required, numbered multi-point input
 * used for an artisan's Do's (positive prompt) and Don'ts (negative prompt). Each row is one
 * numbered bullet; pressing Enter inside a row splits it into a new bullet, rows can be removed
 * individually, and "Add point" appends an empty one. The rows serialize to the same
 * newline-joined string as Android via a zero-size mirror input under `name`, so the existing
 * FormData handlers (`requiredText(form, "dos"/"donts")`) are unchanged.
 */
export function DosDontsField({
  name,
  label,
  helper,
  defaultValue,
  required = true
}: {
  name: string;
  label: string;
  helper?: string;
  defaultValue?: string | null;
  required?: boolean;
}) {
  const [items, setItems] = useState<string[]>(() => splitNumbered(defaultValue));
  const rowRefs = useRef<Array<HTMLInputElement | null>>([]);
  const joined = joinNumbered(items);

  function update(next: string[]) {
    setItems(next.length ? next : [""]);
  }

  function focusRow(index: number) {
    // After React commits the new row.
    requestAnimationFrame(() => rowRefs.current[index]?.focus());
  }

  function changeRow(index: number, raw: string) {
    if (raw.includes("\n")) {
      // Pasted multi-line text: commit the first segment here, push the rest to new bullets.
      const segments = raw.split("\n");
      const next = [...items];
      next[index] = segments[0].trim();
      next.splice(index + 1, 0, ...segments.slice(1).map((segment) => segment.trim()));
      update(next);
    } else {
      update(items.map((item, j) => (j === index ? raw : item)));
    }
  }

  function addRowAfter(index: number) {
    const next = [...items];
    next.splice(index + 1, 0, "");
    update(next);
    focusRow(index + 1);
  }

  return (
    <div className="relative grid content-start gap-1.5">
      <span className="field-label">
        {label}
        {required ? " *" : ""}
      </span>
      {helper ? <p className="text-xs text-ink-muted">{helper}</p> : null}
      {items.map((item, index) => (
        <div key={index} className="flex items-center gap-2">
          <span className="w-5 shrink-0 text-right text-sm text-ink-muted">{index + 1}.</span>
          <input
            ref={(el) => {
              rowRefs.current[index] = el;
            }}
            className="field-input min-w-0 flex-1"
            type="text"
            value={item}
            onChange={(event) => changeRow(index, event.target.value)}
            onKeyDown={(event) => {
              // Enter commits this point and starts the next one (instead of submitting the form).
              if (event.key === "Enter") {
                event.preventDefault();
                addRowAfter(index);
              }
            }}
          />
          {items.length > 1 ? (
            <button
              type="button"
              aria-label="Remove point"
              className="shrink-0 rounded-md p-2 text-ink-muted transition hover:bg-field-100 hover:text-error-600"
              onClick={() => update(items.filter((_, j) => j !== index))}
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          ) : null}
        </div>
      ))}
      <button
        type="button"
        className="field-button-secondary inline-flex items-center gap-1 justify-self-start"
        onClick={() => addRowAfter(items.length - 1)}
      >
        <Plus className="h-4 w-4" aria-hidden />
        Add point
      </button>
      {/* Zero-size (not hidden) mirror: submits the newline-joined value under the existing field
          name AND makes `required` participate in native form validation.

          It MUST be a <textarea>, not an <input type="text">. An input's value sanitization
          algorithm strips CR/LF, so `joined` arrived in FormData as "point onepoint two" — every
          multi-point Do's/Don'ts was stored as one run-on string, which `splitNumbered` could then
          never split back into rows. A textarea preserves newlines and still participates in
          constraint validation, so `required` behaves exactly as before. (MultiNoteField gets away
          with an input because its mirror is type="hidden", which is not sanitized.) */}
      <textarea
        name={name}
        value={joined}
        required={required}
        onChange={() => undefined}
        tabIndex={-1}
        aria-hidden="true"
        className="pointer-events-none absolute h-0 w-0 resize-none border-0 p-0 opacity-0"
      />
    </div>
  );
}
