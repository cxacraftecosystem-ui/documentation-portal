"use client";

import { useEffect, useState } from "react";

import { Toggle } from "@/components/settings/Toggle";
import { apiFetch } from "@/lib/api";

/**
 * The repository-wide processing configuration: how audio becomes text, and when the heavy work is
 * allowed to run. Master admin only — `GET/PUT /settings` is behind `require_master_admin`, so this
 * component is mounted only after `isMasterAdmin(user)` and never merely disabled.
 */

/** Mirror of the backend AppSettingDto (GET/PUT /settings, master-admin only). */
type AppSettings = {
  transcriptionMode: string;
  batchWindowEnabled: boolean;
  batchWindowStart: string;
  batchWindowEnd: string;
  batchTimezone: string;
};

const TRANSCRIPTION_MODES: Array<{ value: string; label: string; helper: string }> = [
  { value: "RAW", label: "Raw transcript only", helper: "Fastest, lowest cost — the plain speech-to-text." },
  { value: "REFINED", label: "Refined conversation", helper: "Cleaned into a readable interviewer/interviewee dialogue." },
  { value: "REFINED_TRANSLATED", label: "Refined + translated to English", helper: "Refined, then translated to English (default)." }
];

export function AppSettingsPanel() {
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<AppSettings>("/settings")
      .then(setSettings)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load settings"));
  }, []);

  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!settings) return;
    setSaving(true);
    setSuccess(null);
    setError(null);
    try {
      const updated = await apiFetch<AppSettings>("/settings", { method: "PUT", body: JSON.stringify(settings) });
      setSettings(updated);
      setSuccess("Settings saved");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save settings");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="grid gap-4">
      {error ? (
        <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}
      {success ? (
        <div className="rounded-md border border-emerald-200 bg-success-100 px-3 py-2 text-sm text-success-600">{success}</div>
      ) : null}
      {!settings ? (
        <div className="panel p-4 text-sm text-ink-500">Loading settings...</div>
      ) : (
        <form className="grid gap-4" onSubmit={save}>
          <section className="panel p-5">
            <h2 className="font-display font-bold text-ink-900">Transcription mode</h2>
            <p className="mt-1 text-xs leading-5 text-ink-500">
              How recorded audio is turned into text after upload. Refinement/translation use AI and always await your
              approval before they become the saved transcript.
            </p>
            <div className="mt-3 grid gap-2 md:grid-cols-3">
              {TRANSCRIPTION_MODES.map((mode) => {
                const active = settings.transcriptionMode === mode.value;
                return (
                  <label
                    className={`flex cursor-pointer items-start gap-3 rounded-md border p-3 transition ${
                      active ? "border-purple-300 bg-purple-50" : "border-line-200 bg-card hover:border-purple-300"
                    }`}
                    key={mode.value}
                  >
                    <input
                      checked={active}
                      className="mt-1 accent-purple-700"
                      name="transcriptionMode"
                      onChange={() => setSettings({ ...settings, transcriptionMode: mode.value })}
                      type="radio"
                      value={mode.value}
                    />
                    <span>
                      <span className="block text-sm font-medium text-ink-900">{mode.label}</span>
                      <span className="block text-xs leading-5 text-ink-500">{mode.helper}</span>
                    </span>
                  </label>
                );
              })}
            </div>
          </section>

          <section className="panel p-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 className="font-display font-bold text-ink-900">Process during an off-peak window</h2>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  When on, transcription &amp; refinement run only between the times below, so the heavy work happens when
                  nobody is uploading. When off, they run immediately.
                </p>
              </div>
              <Toggle
                checked={settings.batchWindowEnabled}
                label="Process during an off-peak window"
                onChange={(next) => setSettings({ ...settings, batchWindowEnabled: next })}
              />
            </div>
            <div className="mt-4 grid gap-3 sm:grid-cols-3">
              <div>
                <label className="field-label" htmlFor="batch-start">
                  Window start
                </label>
                <input
                  className="field-input mt-1.5"
                  disabled={!settings.batchWindowEnabled}
                  id="batch-start"
                  onChange={(event) => setSettings({ ...settings, batchWindowStart: event.target.value })}
                  type="time"
                  value={settings.batchWindowStart}
                />
              </div>
              <div>
                <label className="field-label" htmlFor="batch-end">
                  Window end
                </label>
                <input
                  className="field-input mt-1.5"
                  disabled={!settings.batchWindowEnabled}
                  id="batch-end"
                  onChange={(event) => setSettings({ ...settings, batchWindowEnd: event.target.value })}
                  type="time"
                  value={settings.batchWindowEnd}
                />
              </div>
              <div>
                <label className="field-label" htmlFor="batch-timezone">
                  Timezone
                </label>
                <input
                  className="field-input mt-1.5"
                  disabled={!settings.batchWindowEnabled}
                  id="batch-timezone"
                  onChange={(event) => setSettings({ ...settings, batchTimezone: event.target.value })}
                  placeholder="Asia/Kolkata"
                  type="text"
                  value={settings.batchTimezone}
                />
              </div>
            </div>
          </section>

          <div>
            <button className="field-button" disabled={saving} type="submit">
              {saving ? "Saving..." : "Save settings"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
