"use client";

/**
 * The speech-to-text provider ladder, reorderable by dragging.
 *
 * Three providers can transcribe field audio and they are not interchangeable: ElevenLabs is the
 * most accurate on Indian-language speech, Deepgram is the fastest and cheapest, Whisper is the
 * fallback that always works. Which one should be tried FIRST depends on the workshop — a Hindi
 * interview and a English tool survey want different answers — and that is a judgement the person
 * running the repository makes, not one to bury in a constant.
 *
 * Top of the list is tried first. A provider whose key is not configured is skipped wherever it
 * sits, so ranking states a preference rather than a requirement; the row says so, because a
 * researcher who drags Deepgram to the top and sees ElevenLabs transcripts anyway would otherwise
 * conclude the control is broken.
 *
 * Drag OR keyboard: every row has Move up / Move down buttons carrying the same action. A
 * drag-only reorder is unusable with a keyboard, unreachable with a screen reader, and awkward on
 * a phone — and this screen is opened on a phone in the field as often as on a laptop.
 */

import { useEffect, useRef, useState } from "react";
import { ChevronDown, ChevronUp, GripVertical, Loader2 } from "lucide-react";

import { apiFetch } from "@/lib/api";

/** Provider ids, as the backend's transcription chain knows them. */
const PROVIDERS: Record<string, { name: string; blurb: string }> = {
  elevenlabs: {
    name: "ElevenLabs",
    blurb: "Strongest on Indian-language and accented speech. Slower and the most expensive per minute."
  },
  deepgram: {
    name: "Deepgram",
    blurb: "Fastest and cheapest. Very good on clear audio, weaker on heavy background noise."
  },
  whisper: {
    name: "Whisper (OpenAI)",
    blurb: "The dependable fallback. Handles almost anything, with no diarisation of its own."
  }
};

const DEFAULT_ORDER = ["elevenlabs", "deepgram", "whisper"];

type Settings = { sttProviderOrder?: string[] | null };

/** Drop unknown ids, then append anything the server did not mention — always a full permutation. */
function normalise(order: string[] | null | undefined): string[] {
  const known = (order ?? []).filter((id) => id in PROVIDERS);
  const seen = new Set(known);
  return [...known, ...DEFAULT_ORDER.filter((id) => !seen.has(id))];
}

function sameOrder(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((id, index) => id === b[index]);
}

export function ProviderOrderPanel() {
  const [order, setOrder] = useState<string[]>(DEFAULT_ORDER);
  const [saved, setSaved] = useState<string[]>(DEFAULT_ORDER);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const dragging = useRef<number | null>(null);

  useEffect(() => {
    apiFetch<Settings>("/settings")
      .then((settings) => {
        const next = normalise(settings.sttProviderOrder);
        setOrder(next);
        setSaved(next);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load the provider order"))
      .finally(() => setLoading(false));
  }, []);

  function move(from: number, to: number) {
    if (to < 0 || to >= order.length || from === to) return;
    const next = [...order];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    setOrder(next);
    setNotice(null);
  }

  async function save() {
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const updated = await apiFetch<Settings>("/settings", {
        method: "PUT",
        body: JSON.stringify({ sttProviderOrder: order })
      });
      const next = normalise(updated.sttProviderOrder ?? order);
      setOrder(next);
      setSaved(next);
      setNotice("Saved. The next transcription job uses this order — nothing needs restarting.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save the provider order");
    } finally {
      setSaving(false);
    }
  }

  const dirty = !sameOrder(order, saved);

  return (
    <section className="panel grid gap-3 p-4">
      <div>
        <h2 className="font-display text-base font-bold text-ink-900">Transcription provider order</h2>
        <p className="mt-0.5 text-xs leading-5 text-ink-500">
          Drag to rank them, or use the arrows. The top provider is tried first; if it fails or has no key
          configured, the next one down takes the job.
        </p>
      </div>

      {loading ? (
        <p className="flex items-center gap-2 text-sm text-ink-500">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          Loading…
        </p>
      ) : (
        <ol className="grid gap-2">
          {order.map((id, index) => {
            const provider = PROVIDERS[id];
            return (
              <li
                key={id}
                draggable
                onDragStart={() => {
                  dragging.current = index;
                }}
                onDragOver={(event) => {
                  // Without preventDefault the drop never fires — the browser's default is "reject".
                  event.preventDefault();
                }}
                onDrop={(event) => {
                  event.preventDefault();
                  if (dragging.current !== null) move(dragging.current, index);
                  dragging.current = null;
                }}
                onDragEnd={() => {
                  dragging.current = null;
                }}
                className="flex items-start gap-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2.5"
              >
                <GripVertical className="mt-0.5 h-4 w-4 shrink-0 cursor-grab text-ink-300" aria-hidden />
                <span
                  className="mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full bg-purple-700 text-[11px] font-bold text-white"
                  aria-hidden
                >
                  {index + 1}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium text-ink-900">
                    {provider.name}
                    {index === 0 ? <span className="ml-2 text-xs font-normal text-purple-700">tried first</span> : null}
                  </div>
                  <p className="mt-0.5 text-xs leading-5 text-ink-500">{provider.blurb}</p>
                </div>
                <div className="flex shrink-0 flex-col">
                  <button
                    type="button"
                    className="grid h-5 w-5 place-items-center rounded text-ink-500 hover:bg-purple-50 hover:text-purple-700 disabled:opacity-30"
                    disabled={index === 0}
                    aria-label={`Move ${provider.name} up`}
                    onClick={() => move(index, index - 1)}
                  >
                    <ChevronUp className="h-3.5 w-3.5" aria-hidden />
                  </button>
                  <button
                    type="button"
                    className="grid h-5 w-5 place-items-center rounded text-ink-500 hover:bg-purple-50 hover:text-purple-700 disabled:opacity-30"
                    disabled={index === order.length - 1}
                    aria-label={`Move ${provider.name} down`}
                    onClick={() => move(index, index + 1)}
                  >
                    <ChevronDown className="h-3.5 w-3.5" aria-hidden />
                  </button>
                </div>
              </li>
            );
          })}
        </ol>
      )}

      {error ? (
        <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}
      {notice ? (
        <div className="rounded-md border border-success-100 bg-success-100/40 px-3 py-2 text-sm text-success-600">
          {notice}
        </div>
      ) : null}

      <div className="flex items-center gap-3">
        <button type="button" className="field-button" disabled={!dirty || saving} onClick={save}>
          {saving ? "Saving…" : "Save order"}
        </button>
        {dirty ? (
          <button type="button" className="text-xs font-semibold text-ink-500" onClick={() => setOrder(saved)}>
            Reset
          </button>
        ) : null}
      </div>

      {/* Said plainly, because otherwise a reorder that appears to do nothing looks like a bug. */}
      <p className="text-xs text-ink-500">
        A provider with no key in <span className="font-medium text-ink-700">API keys</span> is skipped wherever it
        sits in this list.
      </p>
    </section>
  );
}
