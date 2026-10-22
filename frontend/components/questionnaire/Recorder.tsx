"use client";

import { Mic, Pause, Play, Square } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { mintKey } from "@/lib/questionnaire/api";
import { assembleTake, pickRecorderMime, recorderStore, TIMESLICE_MS, type TakeDraft } from "@/lib/questionnaire/recorderStore";
import { qs } from "@/lib/questionnaire/qs";

export type FinishedTake = {
  takeKey: string;
  clientClipKey: string;
  blob: Blob;
  mimeType: string;
  extension: string;
  durationMs: number;
  deviceCapturedAt: string;
};

export type RecorderTarget = {
  label: string;
  targets: TakeDraft["targets"];
  roster: string[];
  sessionKey: string;
};

/** One recording at a time per tab; a second Record press asks P3 first. */
let active: { label: string; stop: () => Promise<void>; elapsed: () => number } | null = null;
export function activeRecording() {
  return active;
}

/**
 * Takes stopped in this tab whose hand-over to the outbox has not finished yet. Their drafts stay
 * open until the outbox write succeeds (audit H17), so P13 must not offer them in THIS tab meanwhile;
 * after a crash or reload the set is empty and P13 offers them, which is the point.
 */
export const takesInHand = new Set<string>();

function clock(ms: number): string {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/**
 * The recorder (DESIGN §4.3, §6.2): MediaRecorder with a 10-second timeslice; each chunk goes to
 * IndexedDB as it arrives (the first carries the header), so a killed tab still leaves a playable
 * take for P13. The draft is written before recording starts. A Screen Wake Lock is held while it
 * runs. Starts offline — nothing here waits for the network.
 */
export function Recorder({
  target,
  onFinished,
  onCancel
}: {
  target: RecorderTarget;
  onFinished: (take: FinishedTake) => void;
  onCancel?: () => void;
}) {
  const [state, setState] = useState<"idle" | "recording" | "paused" | "saving">("idle");
  const [elapsed, setElapsed] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const draftRef = useRef<TakeDraft | null>(null);
  const indexRef = useRef(0);
  const startedRef = useRef(0);
  const pausedTotal = useRef(0);
  const pausedAt = useRef<number | null>(null);
  const wakeRef = useRef<{ release: () => Promise<void> } | null>(null);
  const writes = useRef<Promise<unknown>>(Promise.resolve());

  useEffect(() => {
    if (state !== "recording") return;
    const timer = window.setInterval(() => {
      setElapsed(Date.now() - startedRef.current - pausedTotal.current);
    }, 250);
    return () => window.clearInterval(timer);
  }, [state]);

  useEffect(
    () => () => {
      // Leaving the component mid-take stops cleanly; the chunks already written are the take.
      if (recorderRef.current && recorderRef.current.state !== "inactive") recorderRef.current.stop();
      void wakeRef.current?.release();
      if (active?.label === target.label) active = null;
    },
    [target.label]
  );

  async function start() {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const { mimeType, extension } = pickRecorderMime((t) => typeof MediaRecorder !== "undefined" && MediaRecorder.isTypeSupported(t));
      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      const draft: TakeDraft = {
        takeKey: mintKey("take"),
        sessionKey: target.sessionKey,
        clientClipKey: mintKey("clip"),
        deviceCapturedAt: new Date().toISOString(),
        mimeType: recorder.mimeType || mimeType,
        targetLabel: target.label,
        targets: target.targets,
        roster: target.roster,
        startedAt: Date.now(),
        finishedAt: null
      };
      draftRef.current = draft;
      indexRef.current = 0;
      await recorderStore.saveDraft(draft);
      recorder.ondataavailable = (event) => {
        if (!event.data || event.data.size === 0) return;
        const index = indexRef.current++;
        writes.current = writes.current.then(() =>
          recorderStore.addChunk({ takeKey: draft.takeKey, index, blob: event.data, at: Date.now() })
        );
      };
      recorder.onstop = () => {
        stream.getTracks().forEach((track) => track.stop());
        void (async () => {
          await writes.current;
          const chunks = await recorderStore.chunks(draft.takeKey);
          // NOT marked finished here (audit H17). The draft stays open until the take is in the
          // outbox — the caller discards it then — so a crash between Stop and that write leaves a
          // draft P13 offers back instead of chunks nothing will ever look at.
          takesInHand.add(draft.takeKey);
          const durationMs = Date.now() - startedRef.current - pausedTotal.current;
          active = null;
          void wakeRef.current?.release();
          setState("idle");
          onFinished({
            takeKey: draft.takeKey,
            clientClipKey: draft.clientClipKey,
            blob: assembleTake(chunks, draft.mimeType),
            mimeType: draft.mimeType,
            extension,
            durationMs,
            deviceCapturedAt: draft.deviceCapturedAt
          });
        })();
      };
      recorderRef.current = recorder;
      startedRef.current = Date.now();
      pausedTotal.current = 0;
      recorder.start(TIMESLICE_MS);
      setState("recording");
      active = {
        label: target.label,
        elapsed: () => Date.now() - startedRef.current - pausedTotal.current,
        stop: () =>
          new Promise<void>((resolve) => {
            const r = recorderRef.current;
            if (!r || r.state === "inactive") return resolve();
            r.addEventListener("stop", () => resolve(), { once: true });
            r.stop();
          })
      };
      try {
        const lock = await (navigator as Navigator & { wakeLock?: { request: (t: "screen") => Promise<{ release: () => Promise<void> }> } }).wakeLock?.request("screen");
        wakeRef.current = lock ?? null;
      } catch {
        wakeRef.current = null;
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  function pause() {
    recorderRef.current?.pause();
    pausedAt.current = Date.now();
    setState("paused");
  }
  function resume() {
    if (pausedAt.current) pausedTotal.current += Date.now() - pausedAt.current;
    pausedAt.current = null;
    recorderRef.current?.resume();
    setState("recording");
  }
  function stop() {
    if (pausedAt.current) pausedTotal.current += Date.now() - pausedAt.current;
    pausedAt.current = null;
    setState("saving");
    recorderRef.current?.stop();
  }

  if (state === "idle") {
    return (
      <div className="grid gap-2">
        <ButtonRow>
          {onCancel ? (
            <button type="button" className="field-button-secondary" onClick={onCancel}>
              {qs("cancel")}
            </button>
          ) : null}
          <button type="button" className="field-button" onClick={() => void start()}>
            <Mic className="h-4 w-4" aria-hidden />
            {qs("record")}
          </button>
        </ButtonRow>
        {error ? <p className="text-center text-sm text-error-600">{error}</p> : null}
      </div>
    );
  }

  return (
    <div className="grid gap-2" data-recorder-state={state}>
      <p className="text-center font-display text-2xl tabular-nums text-ink-900" aria-live="off">
        {clock(elapsed)}
      </p>
      <p className="text-center text-xs text-ink-500">{target.label}</p>
      <ButtonRow>
        {state === "paused" ? (
          <button type="button" className="field-button-secondary" onClick={resume}>
            <Play className="h-4 w-4" aria-hidden />
            {qs("resume")}
          </button>
        ) : (
          <button type="button" className="field-button-secondary" disabled={state === "saving"} onClick={pause}>
            <Pause className="h-4 w-4" aria-hidden />
            {qs("pause")}
          </button>
        )}
        <button type="button" className="field-button" disabled={state === "saving"} onClick={stop}>
          <Square className="h-4 w-4" aria-hidden />
          {qs("stop")}
        </button>
      </ButtonRow>
      <p className="text-center text-xs text-ink-500 sm:hidden">{qs("keepTabOpen")}</p>
    </div>
  );
}

export { clock as recorderClock };
