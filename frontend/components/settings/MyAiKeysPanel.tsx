"use client";

import { useCallback, useEffect, useState } from "react";
import { ChevronDown, ExternalLink, KeyRound, Loader2, Trash2 } from "lucide-react";

import { apiFetch } from "@/lib/api";

/**
 * **A researcher's OWN provider keys.** One card, one row per provider, and an accordion per provider
 * explaining how to get a key.
 *
 * ── WHAT THIS PANEL IS FOR, AND HOW IT DIFFERS FROM THE ONE BELOW IT ──────────────────────────
 *
 * `ApiKeysPanel` manages the DEPLOYMENT's keys and is master-admin only: those are the
 * organisation's credentials and the organisation's bill. This one is the opposite in every respect
 * — it is visible to every signed-in account, it acts only on the caller's own rows, and the key it
 * saves is billed to that person's own card at their own provider. Nothing here can read or write
 * anybody else's key, because the server takes the owner from the token and never from the request.
 *
 * There is deliberately **no reveal control**. The admin panel has one, because a master admin
 * sometimes has to compare a stored key against a provider dashboard. Nobody has that need for
 * somebody else's personal key, and the owner can always paste a new one — so a saved key leaves
 * the server only as a call made on its owner's behalf, and this panel shows the last four
 * characters and nothing more.
 *
 * ── THE CAPABILITY LINE IS NOT DECORATION ─────────────────────────────────────────────────────
 *
 * Each model row prints what that model can actually be used for, and the panel says plainly when a
 * provider cannot do something at all — Claude cannot transcribe audio, because no Claude model
 * accepts a sound file. Without that, a researcher who pastes a Claude key reasonably assumes their
 * recordings are now on their own account, and finds out otherwise only from a bill that never
 * arrives. The server enforces the same rule; this is where a person can see it before choosing.
 */

type AiModel = {
  id: string;
  label: string;
  note: string;
  tasks: string[];
  inputPricePerMTok: number | null;
  outputPricePerMTok: number | null;
};

type Provider = {
  provider: string;
  label: string;
  keyPrefix: string | null;
  consoleUrl: string;
  pricingUrl: string;
  howTo: string[];
  defaultModel: string;
  models: AiModel[];
};

type Catalogue = { pricesCheckedOn: string; tasks: string[]; providers: Provider[] };

type KeyState = {
  provider: string;
  label: string;
  configured: boolean;
  unreadable: boolean;
  hint: string | null;
  model: string;
  modelKnown: boolean;
  lastStatus: string;
  lastCheckedAt: string | null;
  lastError: string | null;
};

/** The designer-facing name of each job. The server sends the enum; this is the only place it is
 *  turned into words, so the six names read the same on every row. */
const TASK_LABELS: Record<string, string> = {
  PROOFREAD: "Proofread",
  EXPAND: "Expand",
  SUMMARISE: "Summarise",
  TRANSLATE: "Translate",
  TRANSCRIBE: "Transcribe audio",
  CAPTION: "Describe photos"
};

function taskList(tasks: string[]): string {
  return tasks.map((task) => TASK_LABELS[task] ?? task).join(" · ");
}

function priceLine(model: AiModel, checkedOn: string): string | null {
  if (model.inputPricePerMTok === null || model.outputPricePerMTok === null) return null;
  // The date travels with the figure, every time it is printed. These go stale — providers
  // re-price, and two of the current rates are introductory — and a stale price shown as current is
  // a small lie told to somebody deciding how to spend their own money.
  return `About $${model.inputPricePerMTok} in / $${model.outputPricePerMTok} out per million words-ish, checked ${checkedOn}`;
}

function StatusPill({ state }: { state: KeyState }) {
  if (state.unreadable) {
    return <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-900">Paste it again</span>;
  }
  if (!state.configured) {
    return <span className="rounded-full bg-surface-200 px-2 py-0.5 text-xs text-ink-500">Not set</span>;
  }
  if (state.lastStatus === "OK") {
    return <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-xs text-emerald-900">Working</span>;
  }
  if (state.lastStatus === "FAILED") {
    return <span className="rounded-full bg-rose-100 px-2 py-0.5 text-xs text-rose-900">Not working</span>;
  }
  return <span className="rounded-full bg-surface-200 px-2 py-0.5 text-xs text-ink-500">Untested</span>;
}

function HowToGetAKey({ provider }: { provider: Provider }) {
  return (
    <details className="mt-3 rounded-md border border-line-200 bg-surface-50">
      <summary className="flex cursor-pointer list-none items-center justify-between gap-2 px-3 py-2 text-sm font-medium text-ink-700">
        How to get a {provider.label} key
        <ChevronDown className="h-4 w-4 shrink-0 transition-transform [details[open]_&]:rotate-180" aria-hidden />
      </summary>
      <div className="border-t border-line-200 px-3 py-3">
        <ol className="list-decimal space-y-2 pl-5 text-sm leading-6 text-ink-500">
          {provider.howTo.map((step) => (
            <li key={step}>{step}</li>
          ))}
        </ol>
        <div className="mt-3 flex flex-wrap gap-4 text-sm">
          <a
            href={provider.consoleUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex items-center gap-1 font-medium text-purple-700 hover:underline"
          >
            Open {provider.label} keys page <ExternalLink className="h-3.5 w-3.5" aria-hidden />
          </a>
          <a
            href={provider.pricingUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex items-center gap-1 font-medium text-purple-700 hover:underline"
          >
            Current prices <ExternalLink className="h-3.5 w-3.5" aria-hidden />
          </a>
        </div>
      </div>
    </details>
  );
}

function ProviderRow({
  provider,
  state,
  checkedOn,
  onChanged
}: {
  provider: Provider;
  state: KeyState;
  checkedOn: string;
  onChanged: (next: KeyState) => void;
}) {
  const [key, setKey] = useState("");
  const [model, setModel] = useState(state.model);
  const [busy, setBusy] = useState<null | "save" | "test" | "remove">(null);
  const [message, setMessage] = useState<string | null>(null);

  // Follow the server when it changes the row under us (a save, a test, a fresh load). Without this
  // the select would keep showing whatever was picked before a Remove, which reads as if the choice
  // had survived the delete.
  useEffect(() => setModel(state.model), [state.model]);

  const run = async (
    action: "save" | "test" | "remove",
    request: () => Promise<KeyState>,
    done: string
  ) => {
    setBusy(action);
    setMessage(null);
    try {
      const next = await request();
      onChanged(next);
      if (action === "save") setKey("");
      setMessage(done);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "That did not work.");
    } finally {
      setBusy(null);
    }
  };

  const transcribes = provider.models.some((m) => m.tasks.includes("TRANSCRIBE"));

  return (
    <li className="rounded-lg border border-line-200 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h3 className="font-display font-bold text-ink-900">{provider.label}</h3>
          <StatusPill state={state} />
        </div>
        {state.hint ? <span className="text-xs text-ink-500">Ends …{state.hint}</span> : null}
      </div>

      {state.unreadable ? (
        <p className="mt-2 rounded-md bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-900">
          This key can no longer be decrypted — the server&apos;s encryption key changed after it was
          saved. Paste it again to fix it. Nothing is using it meanwhile.
        </p>
      ) : null}
      {state.lastError && !state.unreadable ? (
        <p className="mt-2 rounded-md bg-rose-50 px-3 py-2 text-xs leading-5 text-rose-900">{state.lastError}</p>
      ) : null}
      {!state.modelKnown ? (
        <p className="mt-2 rounded-md bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-900">
          The model saved here is not one this app offers any more. Pick another below — until you
          do, your key runs whichever current model fits each job.
        </p>
      ) : null}

      <label className="mt-3 block text-xs font-medium text-ink-700" htmlFor={`key-${provider.provider}`}>
        {state.configured ? "Replace the key" : "Paste your key"}
      </label>
      <input
        id={`key-${provider.provider}`}
        type="password"
        autoComplete="off"
        spellCheck={false}
        value={key}
        onChange={(event) => setKey(event.target.value)}
        placeholder={provider.keyPrefix ? `${provider.keyPrefix}…` : "Your API key"}
        className="mt-1 w-full rounded-md border border-line-200 bg-card px-3 py-2 font-mono text-sm text-ink-900"
      />

      <label className="mt-3 block text-xs font-medium text-ink-700" htmlFor={`model-${provider.provider}`}>
        Model
      </label>
      <select
        id={`model-${provider.provider}`}
        value={model}
        onChange={(event) => setModel(event.target.value)}
        className="mt-1 w-full rounded-md border border-line-200 bg-card px-3 py-2 text-sm text-ink-900"
      >
        {provider.models.map((option) => (
          <option key={option.id} value={option.id}>
            {option.label}
            {option.id === provider.defaultModel ? " (recommended)" : ""}
          </option>
        ))}
      </select>
      {(() => {
        const chosen = provider.models.find((m) => m.id === model);
        if (!chosen) return null;
        const price = priceLine(chosen, checkedOn);
        return (
          <p className="mt-1.5 text-xs leading-5 text-ink-500">
            {chosen.note} <span className="block">Used for: {taskList(chosen.tasks)}.</span>
            {price ? <span className="block">{price}</span> : null}
          </p>
        );
      })()}

      {!transcribes ? (
        <p className="mt-2 text-xs leading-5 text-ink-500">
          {provider.label} cannot transcribe audio — none of its models accepts a sound file — so
          recordings keep using whatever this server is set up with, whatever you save here.
        </p>
      ) : null}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button
          type="button"
          disabled={busy !== null || (!key.trim() && model === state.model)}
          onClick={() =>
            run(
              "save",
              () =>
                apiFetch<KeyState>(`/me/ai-keys/${provider.provider}`, {
                  method: "PUT",
                  body: JSON.stringify({ key: key.trim() || undefined, model })
                }),
              key.trim() ? "Saved. Press Test to check it works." : "Model saved."
            )
          }
          className="field-button disabled:opacity-50"
        >
          {busy === "save" ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" aria-hidden /> : null}
          Save
        </button>
        <button
          type="button"
          disabled={busy !== null || !state.configured}
          onClick={() =>
            run(
              "test",
              () => apiFetch<KeyState>(`/me/ai-keys/${provider.provider}/test`, { method: "POST" }),
              "Tested."
            )
          }
          className="field-button-secondary disabled:opacity-50"
        >
          {busy === "test" ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" aria-hidden /> : null}
          Test
        </button>
        {state.configured || state.unreadable ? (
          <button
            type="button"
            disabled={busy !== null}
            onClick={() =>
              run(
                "remove",
                () => apiFetch<KeyState>(`/me/ai-keys/${provider.provider}`, { method: "DELETE" }),
                "Removed. This work goes back to the server's own key."
              )
            }
            className="inline-flex items-center gap-1.5 text-sm text-ink-500 hover:text-rose-700"
          >
            <Trash2 className="h-4 w-4" aria-hidden /> Remove
          </button>
        ) : null}
        {message ? <span className="text-xs text-ink-500">{message}</span> : null}
      </div>

      <HowToGetAKey provider={provider} />
    </li>
  );
}

export function MyAiKeysPanel() {
  const [catalogue, setCatalogue] = useState<Catalogue | null>(null);
  const [keys, setKeys] = useState<KeyState[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [cat, mine] = await Promise.all([
        apiFetch<Catalogue>("/ai/providers"),
        apiFetch<KeyState[]>("/me/ai-keys")
      ]);
      setCatalogue(cat);
      setKeys(mine);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not load your AI keys.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  if (error) {
    return (
      <section className="panel p-5">
        <p className="text-sm text-rose-700">{error}</p>
      </section>
    );
  }
  if (!catalogue || !keys) {
    return (
      <section className="panel p-5 text-sm text-ink-500">Loading your AI keys…</section>
    );
  }

  return (
    <section className="panel p-5">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <KeyRound className="h-4 w-4" aria-hidden />
        </span>
        <h2 className="font-display font-bold text-ink-900">My AI keys</h2>
      </div>
      <p className="mt-1.5 text-sm leading-6 text-ink-500">
        Bring your own key and the AI work you ask for — proofreading, expanding, summarising,
        translating, transcribing and photo descriptions — runs on your account with your provider,
        at your choice of model, and is billed to you. Leave this empty and everything works exactly
        as it does now, on the key this server is set up with.
      </p>
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        Your key is stored encrypted, is used only for work you personally ask for, and is never
        shown to anyone — including administrators. Background jobs are never billed to you.
      </p>
      <ul className="mt-4 space-y-3">
        {catalogue.providers.map((provider) => {
          const state = keys.find((k) => k.provider === provider.provider);
          if (!state) return null;
          return (
            <ProviderRow
              key={provider.provider}
              provider={provider}
              state={state}
              checkedOn={catalogue.pricesCheckedOn}
              onChanged={(next) =>
                setKeys((current) =>
                  (current ?? []).map((k) => (k.provider === next.provider ? next : k))
                )
              }
            />
          );
        })}
      </ul>
    </section>
  );
}
