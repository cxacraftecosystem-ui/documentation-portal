"use client";

import { useId, useState } from "react";

import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { appendDictatedPhrase, clampToColumn, columnFullSentence } from "@/components/richtext/dictatedValue";
import { RequiredMark } from "@/components/ui/RequiredMark";

export function DictatedTextArea({
  name,
  label,
  defaultValue,
  helper,
  disabled,
  required,
  rows,
  maxLength,
  className,
  explainWhenUnavailable = true,
  onDirty
}: {
  name: string;
  label: string;
  defaultValue?: string | null;
  helper?: string;
  disabled?: boolean;
  required?: boolean;
  rows?: number;
  maxLength?: number;
  className?: string;
  explainWhenUnavailable?: boolean;
  onDirty?: () => void;
}) {
  const reactId = useId();
  const helpId = `dta-${reactId}-help`;
  const fullId = `dta-${reactId}-full`;

  const [value, setValue] = useState(defaultValue ?? "");
  const full = maxLength !== undefined && value.length >= maxLength;

  function update(next: string) {
    setValue(clampToColumn(next, maxLength));
    onDirty?.();
  }

  const describedBy = [helper ? helpId : null, full ? fullId : null].filter(Boolean).join(" ") || undefined;

  return (
    <div className="grid min-w-0 gap-1">
      <label className="field-label" htmlFor={`${reactId}-box`}>
        {label}
        <RequiredMark when={required} />
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
        required={required}
        disabled={disabled}
        maxLength={maxLength}
        aria-describedby={describedBy}
        className={`field-input min-h-24 ${className ?? ""}`}
        value={value}
        onChange={(event) => update(event.target.value)}
      />
      <OnDeviceDictationButton
        fieldLabel={label}
        disabled={disabled}
        explainWhenUnavailable={explainWhenUnavailable}
        onCommit={(phrase) => update(appendDictatedPhrase(value, phrase))}
      />
      {full && maxLength !== undefined ? (
        <p id={fullId} className="text-xs leading-5 text-ink-500">
          {columnFullSentence(maxLength)}
        </p>
      ) : null}
    </div>
  );
}
