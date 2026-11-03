"use client";

import { useId } from "react";

import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { TextInput } from "@/components/FormControls";
import { TitleCasedInput } from "@/components/forms/TitleCasedInput";
import { appendDictatedPhrase, clampToColumn, columnFullSentence } from "@/components/richtext/dictatedValue";
import { RequiredMark } from "@/components/ui/RequiredMark";

export function DictatedTextInput({
  name,
  label,
  value,
  onChange,
  placeholder,
  helper,
  required,
  disabled,
  maxLength,
  className,
  explainWhenUnavailable,
  id,
  titleCased,
  "aria-invalid": ariaInvalid,
  "aria-describedby": ariaDescribedBy
}: {
  name?: string;
  label: string;
  value: string;
  onChange: (next: string) => void;
  placeholder?: string;
  helper?: string;
  required?: boolean;
  disabled?: boolean;
  maxLength?: number;
  className?: string;
  explainWhenUnavailable?: boolean;
  id?: string;
  titleCased?: boolean;
  "aria-invalid"?: boolean;
  "aria-describedby"?: string;
}) {
  const reactId = useId();
  const boxId = id ?? `${reactId}-box`;
  const helpId = `${reactId}-help`;
  const fullId = `${reactId}-full`;

  const full = maxLength !== undefined && value.length >= maxLength;
  function update(next: string) {
    onChange(clampToColumn(next, maxLength));
  }

  const describedBy =
    [ariaDescribedBy, helper ? helpId : null, full ? fullId : null].filter(Boolean).join(" ") || undefined;

  const Control: (props: React.InputHTMLAttributes<HTMLInputElement>) => React.ReactElement = titleCased
    ? TitleCasedInput
    : TextInput;

  return (
    <div className={`grid min-w-0 gap-1 ${className ?? ""}`}>
      <label className="field-label" htmlFor={boxId}>
        {label}
        <RequiredMark when={required} />
      </label>
      {helper ? (
        <p id={helpId} className="text-xs leading-5 text-ink-muted">
          {helper}
        </p>
      ) : null}
      <Control
        id={boxId}
        name={name}
        type="text"
        value={value}
        placeholder={placeholder}
        required={required}
        disabled={disabled}
        maxLength={maxLength}
        aria-invalid={ariaInvalid}
        aria-describedby={describedBy}
        onChange={(event) => update(event.currentTarget.value)}
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
