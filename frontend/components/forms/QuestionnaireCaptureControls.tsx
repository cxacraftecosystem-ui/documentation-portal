"use client";

import { useCallback, useEffect, useState } from "react";

import { Field, Select } from "@/components/FormControls";
import { Toggle } from "@/components/settings/Toggle";

export type RecordingMode = "INDIVIDUAL" | "SECTION";

export type CapturePrefs = {
  recordingMode: RecordingMode;
  hideAnswers: boolean;
};

export const DEFAULT_CAPTURE_PREFS: CapturePrefs = { recordingMode: "SECTION", hideAnswers: true };

const STORAGE_KEY = "field_repo_questionnaire_capture";

function normalize(value: unknown): CapturePrefs {
  if (!value || typeof value !== "object") return { ...DEFAULT_CAPTURE_PREFS };
  const raw = value as Partial<Record<keyof CapturePrefs, unknown>>;
  return {
    recordingMode: raw.recordingMode === "INDIVIDUAL" ? "INDIVIDUAL" : "SECTION",
    hideAnswers: raw.hideAnswers !== false
  };
}

function readPrefs(): CapturePrefs {
  try {
    if (typeof window === "undefined") return { ...DEFAULT_CAPTURE_PREFS };
    const raw = window.localStorage.getItem(STORAGE_KEY);
    return raw ? normalize(JSON.parse(raw)) : { ...DEFAULT_CAPTURE_PREFS };
  } catch {
    return { ...DEFAULT_CAPTURE_PREFS };
  }
}

function writePrefs(prefs: CapturePrefs): void {
  try {
    if (typeof window === "undefined") return;
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
  }
}

export function useCapturePrefs(): { prefs: CapturePrefs; update: (patch: Partial<CapturePrefs>) => void } {
  const [prefs, setPrefs] = useState<CapturePrefs>(DEFAULT_CAPTURE_PREFS);

  useEffect(() => {
    setPrefs(readPrefs());
  }, []);

  const update = useCallback((patch: Partial<CapturePrefs>) => {
    setPrefs((current) => {
      const next = { ...current, ...patch };
      writePrefs(next);
      return next;
    });
  }, []);

  return { prefs, update };
}

export function QuestionnaireCaptureControls({
  prefs,
  onChange
}: {
  prefs: CapturePrefs;
  onChange: (patch: Partial<CapturePrefs>) => void;
}) {
  return (
    <div className="grid gap-4 rounded-lg border border-line-200 bg-field-100 p-3 md:grid-cols-2 md:items-start">
      <Field label="Recording mode">
        <Select
          value={prefs.recordingMode}
          onChange={(event) => onChange({ recordingMode: event.target.value as RecordingMode })}
          aria-label="Recording mode"
        >
          <option value="INDIVIDUAL">Record individual questions</option>
          <option value="SECTION">Record the entire section at once</option>
        </Select>
      </Field>
      <div className="md:pt-5">
        <Toggle
          checked={!prefs.hideAnswers}
          label="Show answer text boxes"
          description="Turn on to type answers."
          onChange={(next) => onChange({ hideAnswers: !next })}
        />
      </div>
    </div>
  );
}
