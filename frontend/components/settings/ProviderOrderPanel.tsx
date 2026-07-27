"use client";

/**
 * The speech-to-text provider ladder, reorderable by dragging OR by keyboard.
 *
 * Three engines can transcribe field audio and they are not interchangeable: ElevenLabs is the most
 * accurate on Indian-language speech, Deepgram is the fastest and cheapest, Whisper is the fallback
 * that always works. Which one is tried FIRST depends on the material — a Hindi weavers' interview
 * and an English tool survey want different answers — and that is a judgement the people running
 * the workshops make, not one to bury in a constant.
 *
 * ADMIN and MASTER_ADMIN only, and the server agrees: `/settings/transcription-providers` sits
 * behind `require_admin`, so hiding this panel is a courtesy rather than the actual control.
 *
 * FREEZING — the rule this screen exists to make true
 * ---------------------------------------------------
 * An engine is rankable only once it has PASSED a live test against its provider. Having a key is
 * not the same as having a key that works, and the gap between the two is exactly where the ranking
 * starts lying: put ElevenLabs first with an expired key and every recording pays for a rejected
 * call before quietly falling through to Deepgram, while this panel goes on saying ElevenLabs is
 * doing the work. So an unproven engine sinks below the proven ones, its Move up is refused with the
 * reason spelled out on the row, and the same partition is applied by the server on save — a rule
 * only the browser enforces is a suggestion, and the API is reachable without the browser.
 *
 * The freeze is deliberately inert when nothing is proven: on a deployment where no key has ever
 * been tested every engine is equal, so the ranking is completely free and nothing is reshuffled.
 * The constraint only appears once there is a verified engine to be unfair to.
 *
 * DEGRADING HONESTLY
 * ------------------
 * This endpoint is newer than some of the servers it will meet, and the first thing a user saw when
 * that happened was the panel's heading followed by the word "Not Found" — which reads as a broken
 * page and tells nobody what to do. A failed load now says which of the three things went wrong
 * (the server has not been redeployed / your role is too low / the server itself errored), what it
 * means, and who can fix it. The list stays on screen, visibly inert and labelled as the built-in
 * default rather than the live ranking, because an admin who cannot change the order should still be
 * able to see what the order is.
 *
 * Three things this screen must never do, each learned the hard way:
 *
 *  - Be drag-only. Dragging is unreachable by keyboard, hostile to a screen reader and miserable on
 *    a phone, and this is an admin control that must not become a trap. Every row therefore carries
 *    real Move up / Move down buttons, focus follows the row that moved, and each move is announced.
 *  - Hide which engines actually have a key. A ranking whose top entry cannot run looks broken from
 *    the outside, so key state and the last test verdict come back from the server (resolved by the
 *    same code the transcription chain uses) and an unconfigured leader is called out by name.
 *  - Pretend the order is saved when it is not. Nothing persists until Save; the button stays
 *    disabled until something actually changed, and the confirmation says when it takes effect.
 */

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

/** Editorial copy stays here; names and key state come from the server so the two cannot drift. */
const BLURBS: Record<string, string> = {
  elevenlabs: "Strongest on Indian-language and accented speech. Slower, and the most expensive per minute.",
  deepgram: "Fastest and cheapest. Very good on clear audio, weaker under heavy background noise.",
  whisper: "The dependable fallback. Handles almost anything, with no diarisation of its own."
};

/** Mirrors `app_settings.STT_KEY_*`. */
type KeyState = "NO_KEY" | "UNTESTED" | "PASSING" | "FAILING";

type Provider = {
  id: string;
  name: string;
  keyName: string;
  keyLabel: string;
  /** A key resolves, so the pipeline WILL call this engine — whether or not the key is any good. */
  configured: boolean;
  keyState: KeyState;
  /** Only a passing test earns this. */
  rankable: boolean;
  frozenReason: string | null;
  testedAt: string | null;
  testError: string | null;
};

type ProviderOrderState = {
  providers: Provider[];
  /** What the pipeline would really do right now — engines with no key at all already dropped. */
  effectiveChain: string[];
  /** The subset of that which has actually answered a test. */
  verifiedChain: string[];
  /** True when the server had to sink an unproven engine to keep the freeze true. */
  normalised: boolean;
  normalisedNote: string | null;
};

/**
 * What to draw when the server cannot be asked at all. These are the engines compiled into the
 * transcription chain, in the order that applies before anyone ranks anything — shown so a failed
 * load still explains what the screen is for, and marked as unverified throughout, because a panel
 * that could not reach the server knows nothing about anybody's keys and must not imply otherwise.
 */
const BUILT_IN_DEFAULT: Provider[] = [
  { id: "elevenlabs", name: "ElevenLabs", keyLabel: "ElevenLabs Scribe", keyName: "ELEVENLABS_API_KEY" },
  { id: "deepgram", name: "Deepgram", keyLabel: "Deepgram", keyName: "DEEPGRAM_API_KEY" },
  { id: "whisper", name: "Whisper (OpenAI)", keyLabel: "OpenAI", keyName: "OPENAI_API_KEY" }
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
  /** One line naming what happened, in the user's terms. */
  headline: string;
  /** What it means and who can fix it. */
  advice: string;
  /** The bit an engineer needs, kept out of the sentence above. */
  technical: string;
  /** Whether pressing the same button again could plausibly work. */
  retryable: boolean;
};

/**
 * Turn a failed request into something a non-engineer can act on.
 *
 * The default — showing `ApiError.message` — is how "Not Found" ended up as the entire explanation
 * for an endpoint that had not been deployed yet. FastAPI's 404 body carries the literal string
 * "Not Found" and nothing else, so there is no sentence to surface: it has to be written here,
 * against the status, or it does not exist.
 */
function describeTrouble(error: unknown, action: string): Trouble {
  const status = error instanceof ApiError ? error.status : 0;
  const serverSentence = error instanceof Error ? error.message : "";
  const technical = `HTTP ${status || "—"} from /settings/transcription-providers${
    serverSentence ? ` — “${serverSentence}”` : ""
  }`;

  // The build shipped without an API address; lib/api already writes a full explanation for that.
  if (error instanceof Error && error.name === "ApiUnconfiguredError") {
    return {
      headline: "This site does not know where its data service is.",
      advice: serverSentence,
      technical,
      retryable: false
    };
  }

  if (status === 404) {
    return {
      headline: "This server does not have the provider ranking yet.",
      advice:
        `The screen is newer than the API it is talking to — the address it asked for simply is not there. ` +
        `Nothing is wrong with your account or your recordings, and no setting has been lost. Whoever deploys ` +
        `the backend needs to release the current version; until they do, the order below is the app's built-in ` +
        `default rather than the live one, and cannot be changed from here.`,
      technical,
      retryable: true
    };
  }
  if (status === 403) {
    return {
      headline: "Your account is not allowed to see or change this ranking.",
      advice:
        `Choosing which engine transcribes recordings needs the Admin role or above. Ask a master admin either ` +
        `to raise your role or to make the change for you — this is a permission, not a fault, so retrying will ` +
        `give the same answer.`,
      technical,
      retryable: false
    };
  }
  if (status === 401) {
    return {
      headline: "Your session has ended.",
      advice: `Sign in again and come back to this page; the ranking itself is untouched.`,
      technical,
      retryable: false
    };
  }
  if (status >= 500) {
    return {
      headline: "The server ran into a problem of its own.",
      advice:
        `This one is on the API side, not on anything you did, and it is not fixable from this screen. Give it a ` +
        `minute and press Try again. If it keeps happening, send whoever looks after the backend the line below ` +
        `and roughly what time it was — that is enough for them to find it in the logs.`,
      technical,
      retryable: true
    };
  }
  if (status === 0) {
    return {
      headline: "The page could not reach the server at all.",
      advice:
        `No answer came back, which is usually this device's internet connection or the API being down entirely. ` +
        `Check you are online and press Try again.`,
      technical,
      retryable: true
    };
  }
  return {
    headline: `The server refused to ${action} the provider order.`,
    advice: serverSentence || `It gave no reason. Press Try again, and tell an administrator if it persists.`,
    technical,
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

/**
 * How many (frozen above proven) pairs this order contains. Mirrors `app_settings.order_violations`.
 *
 * Counted rather than asserted because a stored order can go illegal without anybody touching it —
 * a key expires overnight and yesterday's legal ranking is illegal by morning. Judging a move by
 * whether it makes this number WORSE is what lets an admin repair such an order by hand, instead of
 * every control on the row being dead because the list arrived already in breach.
 */
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
  UNTESTED: { label: "Key present, never tested", className: "text-amber-800", Icon: ShieldQuestion },
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
  /** Set while the panel is showing the built-in default because the server could not be asked. */
  const [trouble, setTrouble] = useState<Trouble | null>(null);
  /** A failure that did NOT cost us the live data — a save or a test that was refused. */
  const [actionTrouble, setActionTrouble] = useState<Trouble | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const [dragId, setDragId] = useState<string | null>(null);
  const [overId, setOverId] = useState<string | null>(null);

  // Where to put focus after a reorder re-renders the list. Without this a keyboard user loses their
  // place on every single move, which is exactly the trap the arrows exist to prevent.
  const buttons = useRef(new Map<string, HTMLButtonElement | null>());
  const focusAfterMove = useRef<string | null>(null);

  const apply = useCallback((state: ProviderOrderState) => {
    setProviders(state.providers);
    setSaved(state.providers);
    setTrouble(null);
  }, []);

  /**
   * Fold a fresh verdict into the rows WITHOUT touching the order on screen.
   *
   * Testing a provider is not a reason to throw away a reorder the admin has not saved yet, and
   * replacing the list wholesale with the server's stored order would do exactly that. Only the
   * per-engine facts are taken; positions stay where the admin left them.
   */
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
      // Refusing silently would read as a broken button. Say which engine is stuck and why.
      const blocked = providers[from];
      setAnnouncement(
        `${blocked.name} cannot move there yet. ${blocked.frozenReason ?? "It has not passed a provider test."}`
      );
      setWarning(
        `${blocked.name} stays below the engines that have passed a test. ${
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
    // Follow the row. When it lands on either end its own arrow goes disabled, so hand focus to the
    // arrow that can still be pressed rather than letting it fall back to the document body.
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
        setNotice("Saved. The next transcription job uses this order — nothing needs restarting.");
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
        // The thaw is the point: it has already happened in the rows above by the time this reads.
        setNotice(`${provider.name} answered. It is verified now and can be ranked wherever you like.`);
        setAnnouncement(`${provider.name} passed its test and is now rankable.`);
      } else {
        setWarning(
          `${provider.name} did not pass: ${
            after?.testError ?? "the provider refused the key"
          }. It stays below the engines that did.`
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
            None of these engines has an API key, so recordings cannot be transcribed at all yet. The ranking is
            still saved and starts applying the moment a key is added and passes its test.
          </>
        )
      });
    } else if (verified.length === 0) {
      out.push({
        tone: "info",
        body: (
          <>
            Nothing here has been tested yet, so nothing is frozen — rank the engines however you like. Press{" "}
            <span className="font-semibold">Test</span> on the one you want first: until an engine has answered,
            this list is a preference rather than a promise.
          </>
        )
      });
    } else if (!saved[0]?.configured) {
      out.push({
        tone: "warn",
        body: (
          <>
            <span className="font-semibold">{saved[0]?.name}</span> is ranked first but has no API key, so the next
            recording actually goes to <span className="font-semibold">{runnable[0]?.name}</span>. Add the{" "}
            {saved[0]?.keyLabel} key to make this ranking count.
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
            This saved order puts {join(stragglers.map((provider) => provider.name))} above{" "}
            {join(verified.map((provider) => provider.name))}, which {verified.length === 1 ? "has" : "have"} passed
            a test. The next save moves the unverified{" "}
            {stragglers.length === 1 ? "one" : "ones"} below — the pipeline already prefers whatever actually
            answers, so the list will simply stop disagreeing with it.
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
            {join(failing.map((provider) => provider.name))} still {failing.length === 1 ? "has" : "have"} a key, so
            every job keeps trying {failing.length === 1 ? "it" : "them"} and waiting for the refusal before moving
            on. Replacing or removing the key is worth doing even though transcription still finishes.
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
          Drag a provider to rank it, or use the arrows. The one at the top is tried first; if it fails, or has
          no API key, the next one down takes the job. An engine can only be ranked once it has passed a test —
          press <span className="font-medium text-ink-700">Test</span> to ask the provider whether its key works.
          This applies to everyone, not just this browser.
        </p>
      </div>

      {/* A failed load does not empty the screen. It explains itself and leaves the list visible. */}
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
          <p className="font-mono text-[11px] leading-5 text-ink-500">{trouble.technical}</p>
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
                "Showing the app’s built-in default order. This is not the live ranking, and none of the controls below will do anything until the panel can reach the server."
              }
            </p>
          ) : null}
          {/* Inert rather than absent: an admin who cannot change the order can still read it. Every
              control inside is separately `disabled`, which is what carries the state to a screen
              reader — `aria-disabled` on a list has no meaning and is ignored. */}
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
                    // Firefox starts no drag at all unless some data is set on the transfer.
                    event.dataTransfer.setData("text/plain", provider.id);
                  }}
                  onDragOver={(event) => {
                    // Without preventDefault the drop never fires — the browser's default is "reject".
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
                      className="inline-flex min-h-7 items-center gap-1.5 rounded-sm border border-line-200 bg-card px-2 py-1 text-[11px] font-semibold text-ink-700 transition hover:border-purple-300 hover:bg-purple-50 hover:text-purple-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700 disabled:cursor-not-allowed disabled:opacity-40"
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

      {/* Every reorder — and every refusal — is spoken, because a screen-reader user pressing an
          arrow otherwise gets no confirmation that anything happened at all. */}
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
          className="grid gap-1.5 rounded-md border border-error-100 bg-error-100/50 px-3 py-2.5 text-sm text-error-600"
          data-testid="provider-order-action-trouble"
          role="alert"
        >
          <p className="font-semibold">{actionTrouble.headline}</p>
          <p className="leading-6">{actionTrouble.advice}</p>
          <p className="font-mono text-[11px] leading-5 text-ink-500">{actionTrouble.technical}</p>
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

      <div className="flex flex-wrap items-center gap-3">
        <button type="button" className="field-button" disabled={!live || !dirty || saving} onClick={save}>
          {saving ? "Saving…" : "Save order"}
        </button>
        {dirty && live ? (
          <button
            type="button"
            className="rounded-sm px-2 py-1 text-xs font-semibold text-ink-500 transition hover:text-purple-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700"
            onClick={reset}
          >
            Reset
          </button>
        ) : null}
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

/**
 * One reorder arrow.
 *
 * A move refused by the freeze is `aria-disabled`, not `disabled`, so the button stays focusable and
 * a keyboard or screen-reader user can reach it and be told WHY — a truly disabled control is skipped
 * by the tab order and explains nothing to the person most likely to need the explanation. Pressing
 * it announces the reason instead of doing nothing. A row that is simply at the top or the bottom has
 * nothing to explain, so that case is a plain `disabled`.
 */
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
    ? `Move ${provider.name} ${direction} — not allowed until it passes a provider test`
    : // Just the direction. Naming the destination position ("up to position 0" on a disabled first
      // row) reads as nonsense; where it landed is announced instead.
      `Move ${provider.name} ${direction}`;
  return (
    <button
      type="button"
      ref={register}
      className="grid h-7 w-7 place-items-center rounded-sm border border-line-200 text-ink-500 transition hover:border-purple-300 hover:bg-purple-50 hover:text-purple-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-purple-700 disabled:cursor-not-allowed disabled:opacity-30 disabled:hover:border-line-200 disabled:hover:bg-transparent aria-disabled:cursor-not-allowed aria-disabled:opacity-30 aria-disabled:hover:border-line-200 aria-disabled:hover:bg-transparent"
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
