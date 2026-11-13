"use client";

import { useEffect, useId, useState } from "react";

import { SearchableSelect } from "@/components/ui/SearchableSelect";
import type { SpokenLanguageSuggestion } from "@/lib/transcript/contract";
import {
  defaultSpokenLanguage,
  fetchSpokenLanguageSuggestion,
  SPOKEN_LANGUAGE_OPTIONS,
  spokenChoiceOf,
  suggestionHint
} from "@/lib/transcript/spokenLanguage";
import { ts } from "@/lib/transcript/strings";

export type SpokenLanguageChoice = {
  value: string;
  choose: (value: string) => void;
  suggestion: SpokenLanguageSuggestion["suggested"];
};

export function useSpokenLanguageChoice(
  artisanIds: readonly string[],
  options: { preset?: string | null; fallback?: string | null } = {}
): SpokenLanguageChoice {
  const preset = spokenChoiceOf(options.preset);
  const fallback = options.fallback ?? null;
  const [value, setValue] = useState<string>(() => preset ?? defaultSpokenLanguage(null, fallback));
  const [touched, setTouched] = useState(preset !== null);
  const [suggestion, setSuggestion] = useState<SpokenLanguageSuggestion["suggested"]>(null);
  const key = [...new Set(artisanIds.filter(Boolean))].sort().join(",");

  useEffect(() => {
    if (preset === null || touched) return;
    setTouched(true);
    setValue(preset);
  }, [preset, touched]);

  useEffect(() => {
    let cancelled = false;
    fetchSpokenLanguageSuggestion(key ? key.split(",") : [])
      .then((answer) => {
        if (cancelled) return;
        setSuggestion(answer.suggested);
        if (!touched) setValue(defaultSpokenLanguage(answer.suggested, fallback));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [key, touched, fallback]);

  return {
    value,
    suggestion,
    choose: (next: string) => {
      setTouched(true);
      setValue(spokenChoiceOf(next) ?? defaultSpokenLanguage(null));
    }
  };
}

export type SpokenLanguagePickerProps = {
  value: string;
  onChange: (value: string) => void;
  suggestion?: SpokenLanguageSuggestion["suggested"];
  label?: string;
  disabled?: boolean;
  className?: string;
};

export function SpokenLanguagePicker({ value, onChange, suggestion = null, label, disabled, className }: SpokenLanguagePickerProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const hint = suggestion && spokenChoiceOf(suggestion.code) === value ? suggestionHint(suggestion) : null;
  const options = SPOKEN_LANGUAGE_OPTIONS.map((option) => ({ value: option.value, label: option.label }));
  return (
    <div className={className ? `grid min-w-0 gap-1 ${className}` : "grid min-w-0 gap-1"} data-spoken-language-picker>
      <label htmlFor={id} className="field-label">
        {label ?? ts("spokenLanguage")}
      </label>
      <SearchableSelect
        id={id}
        value={value}
        onChange={onChange}
        options={options}
        searchable
        advanceOnSelect={false}
        disabled={disabled}
        ariaLabel={label ?? ts("spokenLanguage")}
        describedBy={hint ? hintId : undefined}
        emptyLabel={ts("spokenLanguageNoMatch")}
        placeholder={ts("spokenLanguageSearch")}
      />
      {hint ? (
        <p id={hintId} className="text-xs text-ink-500" data-spoken-language-hint>
          {hint}
        </p>
      ) : null}
    </div>
  );
}
