"use client";

import { Children, isValidElement, useMemo, useState, type ReactNode, type SelectHTMLAttributes } from "react";

import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";

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
    <label className="grid gap-1">
      <span className="field-label">
        {label}
        {required ? " *" : ""}
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
          <div key={index} className="flex items-start gap-2">
            <textarea
              className="field-input min-h-16 flex-1"
              rows={2}
              value={note}
              placeholder={notes.length > 1 ? `Note ${index + 1}` : "Note"}
              onChange={(event) => setNotes((prev) => prev.map((n, j) => (j === index ? event.target.value : n)))}
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

/** Flatten the <option> children of a <Select> into themed-dropdown options. */
function optionsFromChildren(children: ReactNode): DropdownOption[] {
  const options: DropdownOption[] = [];
  Children.forEach(children, (child) => {
    if (!isValidElement(child) || child.type !== "option") return;
    const props = child.props as { value?: string | number; children?: ReactNode; disabled?: boolean };
    const label =
      typeof props.children === "string" || typeof props.children === "number"
        ? String(props.children)
        : props.value !== undefined
          ? String(props.value)
          : "";
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
