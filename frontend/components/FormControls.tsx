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

/**
 * Drop-in replacement for the browser <select>: same API (name / value / defaultValue / onChange /
 * disabled and <option> children) so existing forms are unchanged, but rendered as the app's themed
 * dropdown. A hidden input mirrors the value so uncontrolled forms still submit via FormData.
 */
export function Select({
  name,
  value,
  defaultValue,
  onChange,
  disabled,
  className,
  children,
  "aria-label": ariaLabel
}: SelectHTMLAttributes<HTMLSelectElement>) {
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
      {name ? <input type="hidden" name={name} value={current} /> : null}
      <Dropdown
        value={current}
        onChange={handleChange}
        options={options}
        disabled={disabled}
        className={className}
        ariaLabel={typeof ariaLabel === "string" ? ariaLabel : undefined}
      />
    </>
  );
}
