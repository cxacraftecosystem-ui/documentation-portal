"use client";

/**
 * Settings → API keys → Transcription. The shared transcription service's switches and what it is
 * doing (GET/PUT /settings/transcription), identical in both portals.
 *
 * The pause ships ON (owner hold, 18 Oct 2026) and only a master admin turns it off. Admins read.
 * "Retry failed" reports first (dry run) and is refused by the server while paused.
 */

import { AudioLines } from "lucide-react";
import { useEffect, useState } from "react";

import { Toggle } from "@/components/ui/Toggle";
import { ApiError, apiFetch, describeApiDetail } from "@/lib/api";

type ProviderRow = {
  id: string;
  name: string;
  enabled: boolean;
  configured: boolean;
  inChain: boolean;
  pricePerHourUsd: number;
};

type UsageRow = {
  provider: string;
  dayMinutes: number;
  daySpendUsd: number;
  monthMinutes: number;
  monthSpendUsd: number;
};

type Budget = { provider: string; period: "DAY" | "MONTH"; minutesCap: number | null; spendCapUsd: number | null };

type Failure = { provider: string; outcome: string; httpStatus: number | null; at: string };

type TranscriptionState = {
  paused: boolean;
  providers: ProviderRow[];
  window: { enabled: boolean; start: string; end: string; timezone: string };
  mode: string;
  /** ElevenLabs Voice Isolator on newly transcribed recordings (0.0.5). Off until switched on. */
  audioIsolation?: { enabled: boolean; keyConfigured: boolean };
  queue?: Record<string, number>;
  usage?: UsageRow[];
  cooldowns?: { provider: string; until: string }[];
  budgets?: Budget[];
  failures?: Failure[];
};

type RetryAnswer = { dryRun: boolean; failed: number; wouldRequeue: number; requeued: number };

const OUTCOME_WORDS: Record<string, string> = {
  throttled: "Busy",
  cooling: "Cooling down",
  budget: "Over budget",
  key: "Key not accepted",
  too_large: "Too large",
  failed: "Failed",
  unreachable: "Unreachable"
};

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return describeApiDetail((error.payload as { detail?: unknown })?.detail, error.message);
  return fallback;
}

function when(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

export function TranscriptionPanel({ canChange }: { canChange: boolean }) {
  const [state, setState] = useState<TranscriptionState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState<string | null>(null);
  const [budgetDraft, setBudgetDraft] = useState<Record<string, string>>({});
  const [retry, setRetry] = useState<RetryAnswer | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiFetch<TranscriptionState>("/settings/transcription")
      .then((next) => {
        if (!cancelled) setState(next);
      })
      .catch((caught) => {
        if (!cancelled) setError(errorText(caught, "Couldn't load transcription settings."));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function save(key: string, body: Record<string, unknown>) {
    setSaving(key);
    try {
      const next = await apiFetch<TranscriptionState>("/settings/transcription", {
        method: "PUT",
        body: JSON.stringify(body)
      });
      setState(next);
      setError(null);
    } catch (caught) {
      setError(errorText(caught, "Couldn't save."));
    } finally {
      setSaving(null);
    }
  }

  function toggleProvider(id: string, enabled: boolean) {
    if (!state) return;
    const disabled = state.providers.filter((p) => (p.id === id ? !enabled : !p.enabled)).map((p) => p.id);
    void save(`provider:${id}`, { disabledProviders: disabled });
  }

  function saveBudget(provider: string) {
    const raw = (budgetDraft[provider] ?? "").trim();
    const minutes = raw === "" ? null : Number(raw);
    if (minutes !== null && (!Number.isFinite(minutes) || minutes < 0)) {
      setError("Enter whole minutes, or leave it empty for no limit.");
      return;
    }
    void save(`budget:${provider}`, {
      budgets: [{ provider, period: "MONTH", minutesCap: minutes === null ? null : Math.round(minutes), spendCapUsd: null }]
    });
  }

  async function checkRetry() {
    setSaving("retry");
    try {
      setRetry(
        await apiFetch<RetryAnswer>("/settings/transcription/retry-failed", {
          method: "POST",
          body: JSON.stringify({ dryRun: true })
        })
      );
      setError(null);
    } catch (caught) {
      setError(errorText(caught, "Couldn't check failed transcriptions."));
    } finally {
      setSaving(null);
    }
  }

  const monthBudget = (provider: string) =>
    state?.budgets?.find((b) => b.provider === provider && b.period === "MONTH")?.minutesCap ?? null;
  const usageOf = (provider: string) => state?.usage?.find((u) => u.provider === provider);
  const queued = state?.queue?.QUEUED ?? 0;
  const failed = state?.queue?.FAILED ?? 0;

  return (
    <section className="panel grid gap-4 p-5" aria-labelledby="transcription-panel-title">
      <div className="flex items-start gap-3">
        <span className="grid h-9 w-9 place-items-center rounded-md bg-field-200 text-field-600" aria-hidden>
          <AudioLines className="h-5 w-5" />
        </span>
        <div className="min-w-0">
          <h2 id="transcription-panel-title" className="display-title text-lg">
            Transcription
          </h2>
          <p className="text-sm text-ink-500">
            {state ? `${queued} queued · ${failed} failed` : "Loading…"}
          </p>
        </div>
      </div>

      {error ? (
        <p role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      ) : null}

      {state ? (
        <>
          <Toggle
            label="Transcription is on"
            description={
              state.paused ? "Paused. Recordings wait in the queue." : "Recordings are sent in the order below."
            }
            checked={!state.paused}
            disabled={!canChange}
            busy={saving === "pause"}
            onChange={(on) => void save("pause", { paused: !on })}
          />

          {state.audioIsolation ? (
            <Toggle
              label="Remove background noise"
              description={
                state.audioIsolation.enabled
                  ? "On. Each new recording is cleaned with ElevenLabs Voice Isolator after it is transcribed. The original is kept."
                  : state.audioIsolation.keyConfigured
                    ? "Off. Turn on to clean new recordings with ElevenLabs Voice Isolator, billed by ElevenLabs."
                    : "Off. Needs an ElevenLabs key with the Voice Isolator permission."
              }
              checked={state.audioIsolation.enabled}
              disabled={!canChange}
              busy={saving === "isolation"}
              onChange={(on) => void save("isolation", { audioIsolation: on })}
            />
          ) : null}

          <div className="grid gap-2">
            <h3 className="field-label">Providers</h3>
            <ul className="grid gap-2">
              {state.providers.map((provider) => {
                const usage = usageOf(provider.id);
                const cooling = state.cooldowns?.find((c) => c.provider === provider.id);
                const status = [
                  provider.configured ? null : "No key",
                  cooling ? `Busy until ${when(cooling.until)}` : null,
                  usage ? `${usage.monthMinutes} min this month · $${usage.monthSpendUsd.toFixed(2)}` : null
                ]
                  .filter(Boolean)
                  .join(" · ");
                return (
                  <li key={provider.id} className="rounded-md border border-line-200 bg-card p-3">
                    <Toggle
                      label={provider.name}
                      description={status || undefined}
                      checked={provider.enabled}
                      disabled={!canChange}
                      busy={saving === `provider:${provider.id}`}
                                onChange={(on) => toggleProvider(provider.id, on)}
                    />
                    {canChange ? (
                      <div className="mt-2 flex flex-wrap items-end gap-2">
                        <label className="grid min-w-0 gap-1" htmlFor={`budget-${provider.id}`}>
                          <span className="field-label">Monthly minutes</span>
                          <input
                            id={`budget-${provider.id}`}
                            className="field-input w-36"
                            inputMode="numeric"
                            placeholder="No limit"
                            value={budgetDraft[provider.id] ?? String(monthBudget(provider.id) ?? "")}
                            onChange={(event) =>
                              setBudgetDraft((draft) => ({ ...draft, [provider.id]: event.target.value }))
                            }
                          />
                        </label>
                        {/* a form-row button aligned to its input, not an action row */}
                        <button
                          type="button"
                          className="field-button-secondary"
                          disabled={saving === `budget:${provider.id}`}
                          onClick={() => saveBudget(provider.id)}
                        >
                          Save
                        </button>
                      </div>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          </div>

          <p className="text-sm text-ink-500">
            {state.window.enabled
              ? `Runs ${state.window.start}–${state.window.end} (${state.window.timezone}).`
              : "Runs at any hour when on."}
          </p>

          {state.failures && state.failures.length ? (
            <details className="rounded-md border border-line-200 bg-surface-50 p-3">
              <summary className="cursor-pointer text-sm font-medium text-ink-900">
                Recent problems ({state.failures.length})
              </summary>
              <ul className="mt-2 grid gap-1 text-sm text-ink-700">
                {state.failures.map((failure, index) => (
                  <li key={`${failure.at}-${index}`}>
                    {when(failure.at)} · {state.providers.find((p) => p.id === failure.provider)?.name ?? failure.provider} ·{" "}
                    {OUTCOME_WORDS[failure.outcome] ?? failure.outcome}
                  </li>
                ))}
              </ul>
            </details>
          ) : null}

          {canChange ? (
            <div className="grid justify-items-center gap-2">
              <button type="button" className="field-button-secondary" disabled={saving === "retry"} onClick={() => void checkRetry()}>
                Check failed transcriptions
              </button>
              {retry ? (
                <p className="text-sm text-ink-500" role="status">
                  {`${retry.wouldRequeue} of ${retry.failed} could be retried${state.paused ? " once transcription is on." : "."}`}
                </p>
              ) : null}
            </div>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
