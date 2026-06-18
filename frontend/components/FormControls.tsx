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
