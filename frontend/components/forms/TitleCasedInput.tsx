"use client";

import { useId, useState, type InputHTMLAttributes } from "react";

import { TextInput } from "@/components/FormControls";
import { titleCasePreview } from "@/lib/format";

export function TitleCasedInput({
  onChange,
  "aria-describedby": describedBy,
  ...props
}: InputHTMLAttributes<HTMLInputElement>) {
  const hintId = `${useId()}-title-case`;
  const [typed, setTyped] = useState(() => String(props.defaultValue ?? props.value ?? ""));
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
