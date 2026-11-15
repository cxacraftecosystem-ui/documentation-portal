"use client";

import { Mic, Pause, Play, Square } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { Waveform } from "@/components/media/Waveform";
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

let active: { label: string; stop: () => Promise<void>; elapsed: () => number } | null = null;
export function activeRecording() {
  return active;
}

export const takesInHand = new Set<string>();

function clock(ms: number): string {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export const BYTES_PER_MINUTE = 1_000_000;

export function minutesOfSpace(estimate: { quota?: number; usage?: number } | null | undefined): number | null {
  if (!estimate || typeof estimate.quota !== "number" || typeof estimate.usage !== "number") return null;
  return Math.max(0, Math.floor((estimate.quota - estimate.usage) / BYTES_PER_MINUTE));
}

export function micBlocked(error: unknown): boolean {
  const name = error instanceof Error || (typeof DOMException !== "undefined" && error instanceof DOMException) ? (error as { name: string }).name : "";
  return name === "NotAllowedError" || name === "SecurityError" || name === "PermissionDeniedError";
}

export function Recorder({
  target,
  onFinished,
  onCancel,
  autoStart = false,
  heading,
  onMicOff
}: {
  target: RecorderTarget;
  onFinished: (take: FinishedTake) => void;
  onCancel?: () => void;
  autoStart?: boolean;
  heading?: string;
  onMicOff?: () => void;
}) {
  const [state, setState] = useState<"idle" | "recording" | "paused" | "saving">("idle");
  const [elapsed, setElapsed] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [spaceMinutes, setSpaceMinutes] = useState<number | null>(null);
  const started = useRef(false);
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
      if (recorderRef.current && recorderRef.current.state !== "inactive") recorderRef.current.stop();
      void wakeRef.current?.release();
      if (active?.label === target.label) active = null;
    },
    [target.label]
  );

  useEffect(() => {
    if (!autoStart || started.current) return;
    started.current = true;
    void start();
  });

  useEffect(() => {
    if (state !== "recording") return;
    let cancelled = false;
    const check = () => {
      const storage = (navigator as Navigator & { storage?: { estimate?: () => Promise<{ quota?: number; usage?: number }> } }).storage;
      void storage
        ?.estimate?.()
        .then((estimate) => {
          if (!cancelled) setSpaceMinutes(minutesOfSpace(estimate));
        })
        .catch(() => undefined);
    };
    check();
    const timer = window.setInterval(check, 60_000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [state]);

  async function start() {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      setStream(stream);
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
        setStream(null);
        void (async () => {
          await writes.current;
          const chunks = await recorderStore.chunks(draft.takeKey);
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
      if (onMicOff && micBlocked(err)) {
        onMicOff();
        return;
      }
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

  const lowSpace = spaceMinutes !== null && spaceMinutes < 30;

  if (heading) {
    return (
      <div className="grid gap-2.5" data-recorder-state={state} role="region" aria-label={heading}>
        <div className="flex items-center gap-3">
          <span aria-hidden className={`h-2.5 w-2.5 shrink-0 rounded-full ${state === "recording" ? "bg-error-600 motion-safe:animate-pulse" : "border-2 border-error-600"}`} />
          <span className="min-w-0 flex-1 truncate text-[0.8125rem] font-semibold text-error-600 dark:text-red-400">{heading}</span>
          <span className="font-display text-2xl font-bold tabular-nums text-ink-900" role="timer" aria-live="off">
            {clock(elapsed)}
          </span>
        </div>
        {state === "recording" || state === "paused" ? <Waveform stream={stream} height={32} label={heading} /> : null}
        {lowSpace ? (
          <p className="text-[0.8125rem] font-medium text-amber-800 dark:text-amber-100" data-low-space>
            {qs("spaceLeft", { minutes: spaceMinutes })}
          </p>
        ) : null}
        {state === "idle" ? (
          <ButtonRow fill>
            {onCancel ? (
              <button type="button" className="field-button-secondary min-h-12!" onClick={onCancel}>
                {qs("cancel")}
              </button>
            ) : null}
            <button type="button" className="field-button min-h-12!" onClick={() => void start()}>
              <Mic className="h-4 w-4" aria-hidden />
              {qs("record")}
            </button>
          </ButtonRow>
        ) : (
          <ButtonRow fill>
            {state === "paused" ? (
              <button type="button" className="field-button-secondary min-h-12!" onClick={resume}>
                <Play className="h-4 w-4" aria-hidden />
                {qs("resume")}
              </button>
            ) : (
              <button type="button" className="field-button-secondary min-h-12!" disabled={state === "saving"} onClick={pause}>
                <Pause className="h-4 w-4" aria-hidden />
                {qs("pause")}
              </button>
            )}
            <button type="button" className="field-button min-h-12! bg-error-600! text-white! hover:bg-error-600/90!" disabled={state === "saving"} onClick={stop}>
              <Square className="h-4 w-4 fill-current" aria-hidden />
              {qs("stop")}
            </button>
          </ButtonRow>
        )}
        {error ? <p className="text-center text-sm text-error-600">{error}</p> : null}
        <p className="text-center text-xs text-ink-500 sm:hidden">{qs("keepTabOpen")}</p>
      </div>
    );
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
