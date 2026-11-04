"use client";

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
  join = "line",
  placeholder,
  className,
  explainWhenUnavailable,
  onDirty,
  onValueChange
}: {
  name: string;
  label: string;
  defaultValue?: string | null;
  helper?: string;
  disabled?: boolean;
  maxLength?: number;
  listKind?: RichBlockKind;
  join?: PlainBlockJoin;
  placeholder?: string;
  className?: string;
  onDirty?: () => void;
  explainWhenUnavailable?: boolean;
  onValueChange?: (value: string) => void;
}) {
  const reactId = useId();
  const labelId = `rtf-${reactId}-label`;
  const helpId = `rtf-${reactId}-help`;

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
      <input type="hidden" name={name} value={submitValue} />
      <RichTextEditor
        value={initialValue}
        onChange={(doc: StoredRichDoc | null) => {
          const encoded = encodeStoredRichText(doc, join);
          setSubmitValue(encoded);
          onValueChange?.(encoded);
          onDirty?.();
        }}
        disabled={disabled}
        ariaLabelledBy={labelId}
        ariaLabel={label}
        explainWhenUnavailable={explainWhenUnavailable}
        maxLength={maxLength}
        listKind={listKind}
        {...(placeholder ? { placeholder } : {})}
      />
    </div>
  );
}
