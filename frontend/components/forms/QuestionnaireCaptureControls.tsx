"use client";

import { useCallback, useEffect, useState } from "react";

import { Field, Select } from "@/components/FormControls";
import { Toggle } from "@/components/settings/Toggle";

/**
 * How a section is captured. The two values, their labels and the toggle caption below are copied
 * verbatim from the Android questionnaire form (MainActivity's "Recording mode" dropdown), because a
 * field team that hears the same control described two ways ends up with two mental models of it.
 */
export type RecordingMode = "INDIVIDUAL" | "SECTION";

export type CapturePrefs = {
  recordingMode: RecordingMode;
  /** True = show only the record button; the written-answer boxes stay out of the way. */
  hideAnswers: boolean;
};

/**
 * One take for the whole section is how these interviews are actually conducted: the researcher sits
 * down with the artisan and talks through the section, rather than starting and stopping a recorder
 * between every question. Per-question capture stays one click away for the times it is wanted, but
 * it is not the shape of the work and so it is not the default.
 */
export const DEFAULT_CAPTURE_PREFS: CapturePrefs = { recordingMode: "SECTION", hideAnswers: true };

/**
 * localStorage key, alongside `field_repo_token` (lib/api) and `field_repo_preferences`.
 *
 * localStorage rather than the server: this is a working style, not account data, and an interview
 * is the one screen most likely to be open with no connection at all — a preference that needs a
 * round trip to apply is a preference that resets on every rural site. One key for the device rather
 * than one per account, so it also survives the token expiring mid-interview.
 */
const STORAGE_KEY = "field_repo_questionnaire_capture";

/** Coerce anything (stale storage, a hand-edited value) into a complete, valid CapturePrefs. */
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
    // Corrupt JSON or a locked-down storage sandbox — the defaults are the right answer either way.
    return { ...DEFAULT_CAPTURE_PREFS };
  }
}

function writePrefs(prefs: CapturePrefs): void {
  try {
    if (typeof window === "undefined") return;
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // Private mode / quota — the choice still applies for the rest of this sitting.
  }
}

/**
 * The capture preferences, remembered across sections, reloads and days. Read in an effect rather
 * than during render because the page is prerendered on the server, where there is no storage: a
 * lazy initialiser would render one HTML on the server and another in the browser.
 */
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

/** The two controls that decide what a section looks like while it is being answered. */
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
      <div className="flex items-start gap-3 md:pt-6">
        <div className="min-w-0 flex-1">
          <span className="block text-sm text-ink">Do not display answer text boxes</span>
          <span className="mt-0.5 block text-xs text-ink-muted">
            On by default — show only the record button. Toggle off to type written answers.
          </span>
        </div>
        <Toggle
          checked={prefs.hideAnswers}
          label="Do not display answer text boxes"
          onChange={(next) => onChange({ hideAnswers: next })}
        />
      </div>
    </div>
  );
}
