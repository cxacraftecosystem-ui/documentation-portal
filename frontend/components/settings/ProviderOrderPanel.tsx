"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  CircleDashed,
  GripVertical,
  Loader2,
  Lock,
  RotateCw,
  ShieldQuestion,
  XCircle
} from "lucide-react";

import { ApiError, apiFetch } from "@/lib/api";
import { ButtonRow } from "@/components/ui/ButtonRow";

const BLURBS: Record<string, string> = {
  elevenlabs: "Best for Indian languages and accents. Slower, costliest.",
  deepgram: "Fastest and cheapest. Weaker in noise.",
  whisper: "Reliable fallback. No speaker labels."
};

type KeyState = "NO_KEY" | "UNTESTED" | "PASSING" | "FAILING";

type Provider = {
  id: string;
  name: string;
  keyName: string;
  keyLabel: string;
  configured: boolean;
  keyState: KeyState;
  rankable: boolean;
  frozenReason: string | null;
  testedAt: string | null;
  testError: string | null;
};

type ProviderOrderState = {
  providers: Provider[];
  effectiveChain: string[];
  verifiedChain: string[];
  normalised: boolean;
  normalisedNote: string | null;
};

const BUILT_IN_DEFAULT: Provider[] = [
  { id: "elevenlabs", name: "ElevenLabs", keyLabel: "ElevenLabs Scribe", keyName: "ELEVENLABS_API_KEY" },
  { id: "deepgram", name: "Deepgram", keyLabel: "Deepgram", keyName: "DEEPGRAM_API_KEY" },
  { id: "whisper", name: "Whisper (OpenAI)", keyLabel: "OpenAI", keyName: "OPENAI_API_KEY" },
  { id: "muse", name: "Meta Muse", keyLabel: "Meta Muse", keyName: "META_MODEL_API_KEY" }
].map((entry) => ({
  ...entry,
  configured: false,
  keyState: "UNTESTED" as KeyState,
  rankable: false,
  frozenReason: null,
  testedAt: null,
  testError: null
}));

type Trouble = {
  headline: string;
  advice: string;
  retryable: boolean;
};

function describeTrouble(error: unknown, action: string): Trouble {
  const status = error instanceof ApiError ? error.status : 0;
  const serverSentence = error instanceof Error ? error.message : "";

  if (error instanceof Error && error.name === "ApiUnconfiguredError") {
    return {
      headline: "Couldn’t load the transcription order.",
      advice: serverSentence,
      retryable: false
    };
  }

  if (status === 404) {
    return {
      headline: "The transcription order isn’t available right now.",
      advice:
        "Showing the default order. Try again later.",
      retryable: true
    };
  }
  if (status === 403) {
    return {
      headline: "You can’t change this ranking.",
      advice: "Needs the Admin role.",
      retryable: false
    };
  }
  if (status === 401) {
    return {
      headline: "Your session has ended.",
      advice: "Sign in again.",
      retryable: false
    };
  }
  if (status >= 500) {
    return {
      headline: "Something went wrong.",
      advice: "Try again in a minute.",
      retryable: true
    };
  }
  if (status === 0) {
    return {
      headline: "Couldn’t connect.",
      advice: `Check your connection and try again.`,
      retryable: true
    };
  }
  return {
    headline: `Couldn’t ${action} the transcription order.`,
    advice: serverSentence || "Try again.",
    retryable: true
  };
}

function sameOrder(a: Provider[], b: Provider[]): boolean {
  return a.length === b.length && a.every((provider, index) => provider.id === b[index].id);
}

function reorder(list: Provider[], from: number, to: number): Provider[] {
  const next = [...list];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  return next;
}

function violations(list: Provider[]): number {
  let frozenSoFar = 0;
  let total = 0;
  for (const provider of list) {
    if (provider.rankable) total += frozenSoFar;
    else frozenSoFar += 1;
  }
  return total;
}

function movePermitted(list: Provider[], from: number, to: number): boolean {
  if (to < 0 || to >= list.length || from === to) return false;
  return violations(reorder(list, from, to)) <= violations(list);
}

function testedAgo(iso: string | null): string {
  if (!iso) return "";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const seconds = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (seconds < 60) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"} ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  return `${Math.round(hours / 24)} days ago`;
}

const KEY_STATE_BADGE: Record<KeyState, { label: string; className: string; Icon: typeof CheckCircle2 }> = {
  PASSING: { label: "Tested and working", className: "text-success-600", Icon: CheckCircle2 },
  FAILING: { label: "Test failed", className: "text-error-600", Icon: XCircle },
  UNTESTED: { label: "Key present, never tested", className: "text-amber-800 dark:text-amber-200", Icon: ShieldQuestion },
  NO_KEY: { label: "No API key", className: "text-ink-500", Icon: CircleDashed }
};

function join(names: string[]): string {
  if (names.length <= 1) return names[0] ?? "";
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

export function ProviderOrderPanel() {
  const [providers, setProviders] = useState<Provider[]>(BUILT_IN_DEFAULT);
  const [saved, setSaved] = useState<Provider[]>(BUILT_IN_DEFAULT);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState<string | null>(null);
  const [trouble, setTrouble] = useState<Trouble | null>(null);
  const [actionTrouble, setActionTrouble] = useState<Trouble | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const [dragId, setDragId] = useState<string | null>(null);
  const [overId, setOverId] = useState<string | null>(null);

  const buttons = useRef(new Map<string, HTMLButtonElement | null>());
  const focusAfterMove = useRef<string | null>(null);

  const apply = useCallback((state: ProviderOrderState) => {
    setProviders(state.providers);
    setSaved(state.providers);
    setTrouble(null);
  }, []);

  const mergeVerdicts = useCallback((state: ProviderOrderState) => {
    const fresh = new Map(state.providers.map((provider) => [provider.id, provider]));
    const merge = (list: Provider[]) => list.map((provider) => fresh.get(provider.id) ?? provider);
    setProviders(merge);
    setSaved(merge);
    setTrouble(null);
  }, []);

  const load = useCallback(() => {
    setLoading(true);
    setActionTrouble(null);
    apiFetch<ProviderOrderState>("/settings/transcription-providers")
      .then(apply)
      .catch((error) => {
        setProviders(BUILT_IN_DEFAULT);
        setSaved(BUILT_IN_DEFAULT);
        setTrouble(describeTrouble(error, "load"));
      })
      .finally(() => setLoading(false));
  }, [apply]);

  useEffect(load, [load]);

  useEffect(() => {
    const key = focusAfterMove.current;
    if (!key) return;
    focusAfterMove.current = null;
    buttons.current.get(key)?.focus();
  }, [providers]);

  const live = !trouble;

  function move(from: number, to: number) {
    if (to < 0 || to >= providers.length || from === to) return;
    if (!movePermitted(providers, from, to)) {
      const blocked = providers[from];
      setAnnouncement(
        `${blocked.name} cannot move there yet. ${blocked.frozenReason ?? "It has not passed a provider test."}`
      );
      setWarning(
        `${blocked.name} must pass a test first. ${
          blocked.frozenReason ?? ""
        }`.trim()
      );
      return;
    }
    const next = reorder(providers, from, to);
    const moved = providers[from];
    setProviders(next);
    setNotice(null);
    setWarning(null);
    setAnnouncement(`${moved.name} moved to position ${to + 1} of ${next.length}.`);
    const direction = to > from ? "down" : "up";
    const usable = to === 0 ? "down" : to === next.length - 1 ? "up" : direction;
    focusAfterMove.current = `${moved.id}:${usable}`;
  }

  async function save() {
    setSaving(true);
    setActionTrouble(null);
    setNotice(null);
    setWarning(null);
    try {
      const updated = await apiFetch<ProviderOrderState>("/settings/transcription-providers", {
        method: "PUT",
        body: JSON.stringify({ order: providers.map((provider) => provider.id) })
      });
      apply(updated);
      if (updated.normalised && updated.normalisedNote) {
        setWarning(updated.normalisedNote);
        setAnnouncement(`Saved with changes. ${updated.normalisedNote}`);
      } else {
        setNotice("Order saved.");
        setAnnouncement("Order saved.");
      }
    } catch (error) {
      setActionTrouble(describeTrouble(error, "save"));
    } finally {
      setSaving(false);
    }
  }

  async function test(provider: Provider) {
    setTesting(provider.id);
    setActionTrouble(null);
    setNotice(null);
    setWarning(null);
    try {
      const updated = await apiFetch<ProviderOrderState>(
        `/settings/transcription-providers/${provider.id}/test`,
        { method: "POST" }
      );
      mergeVerdicts(updated);
      const after = updated.providers.find((candidate) => candidate.id === provider.id);
      if (after?.keyState === "PASSING") {
        setNotice(`${provider.name} passed.`);
        setAnnouncement(`${provider.name} passed its test and is now rankable.`);
      } else {
        setWarning(
          `${provider.name} did not pass: ${
            after?.testError ?? "the provider refused the key"
          }.`
        );
        setAnnouncement(`${provider.name} failed its test.`);
      }
    } catch (error) {
      setActionTrouble(describeTrouble(error, "test"));
    } finally {
      setTesting(null);
    }
  }

  function reset() {
    setProviders(saved);
    setNotice(null);
    setWarning(null);
    setAnnouncement("Order reset to the last saved ranking.");
  }

  const dirty = !sameOrder(providers, saved);

  const banners = useMemo(() => {
    if (!live || loading) return [] as { tone: "warn" | "info"; body: ReactNode }[];
    const out: { tone: "warn" | "info"; body: ReactNode }[] = [];
    const runnable = saved.filter((provider) => provider.configured);
    const verified = saved.filter((provider) => provider.rankable);

    if (runnable.length === 0) {
      out.push({
        tone: "warn",
        body: (
          <>
            No engine has an API key, so nothing can be transcribed yet.
          </>
        )
      });
    } else if (verified.length === 0) {
      out.push({
        tone: "info",
        body: (
          <>
            Nothing tested yet. Press <span className="font-semibold">Test</span> on your first choice.
          </>
        )
      });
    } else if (!saved[0]?.configured) {
      out.push({
        tone: "warn",
        body: (
          <>
            <span className="font-semibold">{saved[0]?.name}</span> has no key, so{" "}
            <span className="font-semibold">{runnable[0]?.name}</span> runs first. Add the {saved[0]?.keyLabel} key.
          </>
        )
      });
    }

    if (violations(saved) > 0) {
      const stragglers = saved.filter((provider) => !provider.rankable);
      out.push({
        tone: "warn",
        body: (
          <>
            Untested {join(stragglers.map((provider) => provider.name))} will move below{" "}
            {join(verified.map((provider) => provider.name))} on the next save.
          </>
        )
      });
    }

    const failing = saved.filter((provider) => provider.keyState === "FAILING");
    if (failing.length > 0) {
      out.push({
        tone: "warn",
        body: (
          <>
            {join(failing.map((provider) => provider.name))} failed {failing.length === 1 ? "its" : "their"} test and
            slow{failing.length === 1 ? "s" : ""} every job. Replace or remove the key.
          </>
        )
      });
    }
    return out;
  }, [live, loading, saved]);

  const runnableChain = saved.filter((provider) => provider.configured);

  return (
    <section className="panel grid gap-4 p-5" data-testid="provider-order-panel">
      <div>
        <h2 className="font-display text-base font-bold text-ink-900">Transcription provider order</h2>
        <p className="mt-1 text-sm leading-6 text-ink-500">
          Drag to rank. The top engine runs first; the next takes over if it fails. Applies to everyone.
        </p>
      </div>

      {trouble ? (
        <div
          className="grid gap-2 rounded-md border border-amber-100 bg-amber-100/50 px-3 py-3 text-sm text-amber-800"
          data-testid="provider-order-trouble"
          role="status"
        >
          <p className="flex items-start gap-2 font-semibold">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            {trouble.headline}
          </p>
          <p className="leading-6">{trouble.advice}</p>
          {trouble.retryable ? (
            <div>
              <button
                type="button"
                className="field-button-secondary min-h-8 px-3 py-1.5 text-xs"
                onClick={load}
                disabled={loading}
              >
                <RotateCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} aria-hidden />
                Try again
              </button>
            </div>
          ) : null}
        </div>
      ) : null}

      {loading ? (
        <p className="flex items-center gap-2 text-sm text-ink-500">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          Loading the current order…
        </p>
      ) : (
        <>
          {trouble ? (
            <p className="text-xs font-medium text-ink-500" data-testid="provider-order-stale-note">
              {
                "Showing the default order."
              }
            </p>
          ) : null}
          <ol
            className={`grid gap-2 ${trouble ? "pointer-events-none opacity-60" : ""}`}
            data-testid="provider-order-list"
          >
            {providers.map((provider, index) => {
              const badge = KEY_STATE_BADGE[provider.keyState];
              const upBlocked = index > 0 && !movePermitted(providers, index, index - 1);
              const downBlocked = index < providers.length - 1 && !movePermitted(providers, index, index + 1);
              const reasonId = `provider-frozen-${provider.id}`;
              return (
                <li
                  key={provider.id}
                  draggable={live}
                  data-provider={provider.id}
                  data-key-state={provider.keyState}
                  data-rankable={provider.rankable ? "yes" : "no"}
                  onDragStart={(event) => {
                    setDragId(provider.id);
                    event.dataTransfer.effectAllowed = "move";
                    event.dataTransfer.setData("text/plain", provider.id);
                  }}
                  onDragOver={(event) => {
                    event.preventDefault();
                    const from = providers.findIndex((candidate) => candidate.id === dragId);
                    const permitted = from < 0 || movePermitted(providers, from, index);
                    event.dataTransfer.dropEffect = permitted ? "move" : "none";
                    if (provider.id !== overId) setOverId(provider.id);
                  }}
                  onDragLeave={() => setOverId((current) => (current === provider.id ? null : current))}
                  onDrop={(event) => {
                    event.preventDefault();
                    const from = providers.findIndex((candidate) => candidate.id === dragId);
                    if (from >= 0) move(from, index);
                    setDragId(null);
                    setOverId(null);
                  }}
                  onDragEnd={() => {
                    setDragId(null);
                    setOverId(null);
                  }}
                  className={`flex items-start gap-3 rounded-md border bg-card px-3 py-3 transition ${
                    overId === provider.id && dragId && dragId !== provider.id
                      ? "border-purple-400 bg-purple-50"
                      : "border-line-200"
                  } ${dragId === provider.id ? "opacity-50" : ""}`}
                >
                  <GripVertical className="mt-1 h-4 w-4 shrink-0 cursor-grab text-ink-300" aria-hidden />
                  <span
                    className={`mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-full text-xs font-bold ${
                      provider.rankable ? "bg-purple-700 text-white" : "bg-surface-50 text-ink-500 ring-1 ring-line-200"
                    }`}
                    aria-hidden
                  >
                    {index + 1}
                  </span>

                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                      <span className="text-sm font-semibold text-ink-900">{provider.name}</span>
                      {index === 0 && provider.configured && !trouble ? (
                        <span className="rounded-sm bg-purple-100 px-1.5 py-0.5 text-[11px] font-semibold text-purple-700">
                          Tried first
                        </span>
                      ) : null}
                      {trouble ? (
                        <span className="text-xs font-medium text-ink-500">Key state unknown</span>
                      ) : (
                        <span className={`inline-flex items-center gap-1 text-xs font-medium ${badge.className}`}>
                          <badge.Icon className="h-3.5 w-3.5" aria-hidden />
                          {badge.label}
                          {provider.testedAt ? (
                            <span className="font-normal text-ink-500">· {testedAgo(provider.testedAt)}</span>
                          ) : null}
                        </span>
                      )}
                    </div>
                    <p className="mt-1 text-xs leading-5 text-ink-500">{BLURBS[provider.id] ?? provider.keyLabel}</p>
                    {!trouble && provider.frozenReason ? (
                      <p
                        id={reasonId}
                        className="mt-1.5 flex items-start gap-1.5 rounded-sm bg-surface-50 px-2 py-1.5 text-xs leading-5 text-ink-700"
                      >
                        <Lock className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
                        <span>{provider.frozenReason}</span>
                      </p>
                    ) : null}
                  </div>

                  <div className="flex shrink-0 flex-col items-end gap-1.5">
                    <button
                      type="button"
                      className="inline-flex min-h-7 items-center gap-1.5 rounded-sm border border-line-200 bg-card px-2 py-1 text-[11px] font-semibold text-ink-700 transition hover:border-purple-300 hover:bg-purple-50 hover:text-purple-700 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700 disabled:cursor-not-allowed disabled:opacity-40"
                      onClick={() => test(provider)}
                      disabled={!live || testing !== null}
                      aria-label={`Test the ${provider.keyLabel} key for ${provider.name}`}
                      data-testid={`test-${provider.id}`}
                    >
                      {testing === provider.id ? (
                        <>
                          <Loader2 className="h-3 w-3 animate-spin" aria-hidden />
                          Testing…
                        </>
                      ) : (
                        "Test"
                      )}
                    </button>

                    <div className="flex gap-1">
                      <MoveButton
                        direction="up"
                        provider={provider}
                        blocked={upBlocked}
                        atEnd={index === 0}
                        live={live}
                        reasonId={reasonId}
                        register={(node) => buttons.current.set(`${provider.id}:up`, node)}
                        onMove={() => move(index, index - 1)}
                      />
                      <MoveButton
                        direction="down"
                        provider={provider}
                        blocked={downBlocked}
                        atEnd={index === providers.length - 1}
                        live={live}
                        reasonId={reasonId}
                        register={(node) => buttons.current.set(`${provider.id}:down`, node)}
                        onMove={() => move(index, index + 1)}
                      />
                    </div>
                  </div>
                </li>
              );
            })}
          </ol>
        </>
      )}

      <p aria-live="polite" className="sr-only">
        {announcement}
      </p>

      {banners.map((banner, index) => (
        <div
          key={index}
          className={`flex items-start gap-2 rounded-md border px-3 py-2.5 text-sm ${
            banner.tone === "warn"
              ? "border-amber-100 bg-amber-100/50 text-amber-800"
              : "border-line-200 bg-surface-50 text-ink-700"
          }`}
          data-testid={`provider-banner-${banner.tone}`}
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <span>{banner.body}</span>
        </div>
      ))}

      {actionTrouble ? (
        <div
          className="grid gap-1.5 rounded-md border border-error-100 bg-error-100/50 px-3 py-2.5 text-sm text-red-800"
          data-testid="provider-order-action-trouble"
          role="alert"
        >
          <p className="font-semibold">{actionTrouble.headline}</p>
          <p className="leading-6">{actionTrouble.advice}</p>
        </div>
      ) : null}
      {warning ? (
        <div className="rounded-md border border-amber-100 bg-amber-100/50 px-3 py-2.5 text-sm text-amber-800" role="status">
          {warning}
        </div>
      ) : null}
      {notice ? (
        <div className="rounded-md border border-success-100 bg-success-100/50 px-3 py-2.5 text-sm text-success-600" role="status">
          {notice}
        </div>
      ) : null}

      <ButtonRow align="start">
        <button type="button" className="field-button" disabled={!live || !dirty || saving} onClick={save}>
          {saving ? "Saving…" : "Save order"}
        </button>
        {dirty && live ? (
          <button type="button" className="field-button-secondary" onClick={reset}>
            Reset
          </button>
        ) : null}
      </ButtonRow>
      <div className="flex flex-wrap items-center gap-3">
        {live && !dirty && !loading && runnableChain.length > 0 ? (
          <p className="text-xs text-ink-500" data-testid="effective-chain">
            Currently transcribing with{" "}
            <span className="font-medium text-ink-700">
              {runnableChain.map((provider) => provider.name).join(" → ")}
            </span>
            .
          </p>
        ) : null}
      </div>
    </section>
  );
}

function MoveButton({
  direction,
  provider,
  blocked,
  atEnd,
  live,
  reasonId,
  register,
  onMove
}: {
  direction: "up" | "down";
  provider: Provider;
  blocked: boolean;
  atEnd: boolean;
  live: boolean;
  reasonId: string;
  register: (node: HTMLButtonElement | null) => void;
  onMove: () => void;
}) {
  const Icon = direction === "up" ? ChevronUp : ChevronDown;
  const label = blocked
    ? `Move ${provider.name} ${direction} — needs a passing test`
    :
      `Move ${provider.name} ${direction}`;
  return (
    <button
      type="button"
      ref={register}
      className="grid h-7 w-7 place-items-center rounded-sm border border-line-200 text-ink-500 transition hover:border-purple-300 hover:bg-purple-50 hover:text-purple-700 focus-visible:outline-solid focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700 disabled:cursor-not-allowed disabled:opacity-30 disabled:hover:border-line-200 disabled:hover:bg-transparent aria-disabled:cursor-not-allowed aria-disabled:opacity-30 aria-disabled:hover:border-line-200 aria-disabled:hover:bg-transparent"
      disabled={atEnd || !live}
      aria-disabled={blocked || undefined}
      aria-describedby={blocked && provider.frozenReason ? reasonId : undefined}
      aria-label={label}
      title={blocked ? (provider.frozenReason ?? label) : undefined}
      data-testid={`move-${provider.id}-${direction}`}
      onClick={onMove}
    >
      <Icon className="h-4 w-4" aria-hidden />
    </button>
  );
}
