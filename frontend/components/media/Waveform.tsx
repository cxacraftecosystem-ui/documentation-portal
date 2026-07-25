"use client";

import { useEffect, useRef, useState } from "react";

/**
 * WhatsApp-style live voice waveform.
 *
 * Amplitude comes from `getByteTimeDomainData` — the raw sample envelope, which is what makes this a
 * waveform (bars mirrored around a centre line) rather than the single-scalar level meter it replaces.
 * Pitch comes from the spectral centroid of `getByteFrequencyData`, and drives both the bar colour
 * (along the brand purple ramp) and a small height bias, so a bright voice reads differently from a
 * low rumble at the same loudness. Driven by requestAnimationFrame, which — unlike setInterval — is
 * frame-synced and pauses in background tabs instead of being throttled to jerky updates.
 */

const BAR_WIDTH = 3; // CSS px
const BAR_GAP = 2;
const MIN_BAR = 3;
const PITCH_FLOOR_HZ = 90;
const PITCH_CEILING_HZ = 4000;

// Purple ramp stops (tailwind.config.ts, OKLCH hue 305) resolved to sRGB: canvas fillStyle support
// for oklch() is not universal, and a rejected fillStyle silently keeps the previous colour.
const PITCH_RAMP = ["#5f1c93", "#762cb1", "#9148d2", "#aa69e9", "#c090f5"].map((hex) => [
  parseInt(hex.slice(1, 3), 16),
  parseInt(hex.slice(3, 5), 16),
  parseInt(hex.slice(5, 7), 16)
]);
const CENTRE_LINE = "#e4e2ef"; // line-200

type Bar = { amplitude: number; pitch: number };

function clamp01(value: number) {
  return Math.min(1, Math.max(0, value));
}

/** Blend along the purple ramp: deep purple for low voices, light purple for bright ones. */
function colourForPitch(pitch: number) {
  const position = clamp01(pitch) * (PITCH_RAMP.length - 1);
  const low = PITCH_RAMP[Math.floor(position)];
  const high = PITCH_RAMP[Math.min(PITCH_RAMP.length - 1, Math.ceil(position))];
  const mix = position - Math.floor(position);
  const channel = (index: number) => Math.round(low[index] + (high[index] - low[index]) * mix);
  return `rgb(${channel(0)}, ${channel(1)}, ${channel(2)})`;
}

/** Rounded caps, with a manual fallback for engines without CanvasRenderingContext2D.roundRect. */
function drawBar(context: CanvasRenderingContext2D, x: number, y: number, width: number, height: number) {
  const radius = Math.min(width / 2, height / 2);
  if (typeof context.roundRect === "function") {
    context.beginPath();
    context.roundRect(x, y, width, height, radius);
    context.fill();
    return;
  }
  context.beginPath();
  context.arc(x + radius, y + radius, radius, 0, Math.PI * 2);
  context.fill();
  context.beginPath();
  context.arc(x + radius, y + height - radius, radius, 0, Math.PI * 2);
  context.fill();
  context.fillRect(x, y + radius, width, Math.max(0, height - radius * 2));
}

function usePrefersReducedMotion() {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    setReduced(query.matches);
    const onChange = (event: MediaQueryListEvent) => setReduced(event.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return reduced;
}

export function Waveform({
  stream,
  height = 40,
  className = "",
  label = "Live recording waveform",
  releaseStreamOnUnmount = false
}: {
  /** The live microphone stream; null while not recording (the analyser is torn down). */
  stream: MediaStream | null;
  height?: number;
  className?: string;
  label?: string;
  /**
   * Stop the stream's tracks on unmount. Off by default: the recorder that opened the microphone
   * owns the track lifetime and stops it in its own cleanup, and stopping it from here could cut a
   * recording short.
   */
  releaseStreamOnUnmount?: boolean;
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const reduced = usePrefersReducedMotion();
  const [level, setLevel] = useState(0);

  useEffect(() => {
    if (!stream) return;
    const AudioCtor =
      window.AudioContext ?? (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtor) return;

    const audioContext = new AudioCtor();
    audioContext.resume().catch(() => undefined);
    const analyser = audioContext.createAnalyser();
    analyser.fftSize = 1024;
    analyser.smoothingTimeConstant = 0.7;
    const source = audioContext.createMediaStreamSource(stream);
    source.connect(analyser);
    const timeData = new Uint8Array(analyser.fftSize);
    const frequencyData = new Uint8Array(analyser.frequencyBinCount);
    const binHz = audioContext.sampleRate / analyser.fftSize;

    let frameHandle: number | null = null;
    let levelTimer: number | null = null;
    const history: Bar[] = [];

    function sample(): Bar {
      // AMPLITUDE — sample values swing around 128; RMS carries the body, peak the transients.
      analyser.getByteTimeDomainData(timeData);
      let peak = 0;
      let sumSquares = 0;
      for (let index = 0; index < timeData.length; index += 1) {
        const value = (timeData[index] - 128) / 128;
        sumSquares += value * value;
        const magnitude = Math.abs(value);
        if (magnitude > peak) peak = magnitude;
      }
      const rms = Math.sqrt(sumSquares / timeData.length);

      // PITCH — spectral centroid, mapped logarithmically across the speech range.
      analyser.getByteFrequencyData(frequencyData);
      let weighted = 0;
      let total = 0;
      for (let index = 0; index < frequencyData.length; index += 1) {
        weighted += index * frequencyData[index];
        total += frequencyData[index];
      }
      const centroidHz = total > 0 ? (weighted / total) * binHz : 0;
      const pitch =
        centroidHz > 0
          ? clamp01(
              Math.log2(Math.max(centroidHz, PITCH_FLOOR_HZ) / PITCH_FLOOR_HZ) /
                Math.log2(PITCH_CEILING_HZ / PITCH_FLOOR_HZ)
            )
          : 0;

      return { amplitude: clamp01(Math.max(rms * 2.6, peak * 1.1)), pitch };
    }

    if (reduced) {
      // prefers-reduced-motion: no scrolling animation at all — a slow numeric level instead.
      levelTimer = window.setInterval(() => setLevel(Math.round(sample().amplitude * 100)), 250);
    } else {
      const frame = () => {
        frameHandle = window.requestAnimationFrame(frame);
        const canvas = canvasRef.current;
        if (!canvas) return;
        const cssWidth = canvas.clientWidth;
        const cssHeight = canvas.clientHeight;
        if (!cssWidth || !cssHeight) return;

        // devicePixelRatio-aware backing store, then draw in CSS pixels.
        const ratio = window.devicePixelRatio || 1;
        const pixelWidth = Math.round(cssWidth * ratio);
        const pixelHeight = Math.round(cssHeight * ratio);
        if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
          canvas.width = pixelWidth;
          canvas.height = pixelHeight;
        }
        const context = canvas.getContext("2d");
        if (!context) return;
        context.setTransform(ratio, 0, 0, ratio, 0, 0);
        context.clearRect(0, 0, cssWidth, cssHeight);

        const capacity = Math.max(8, Math.floor(cssWidth / (BAR_WIDTH + BAR_GAP)));
        history.push(sample());
        if (history.length > capacity) history.splice(0, history.length - capacity);

        const centre = cssHeight / 2;
        const usable = Math.max(MIN_BAR, cssHeight - 2);
        context.fillStyle = CENTRE_LINE;
        context.fillRect(0, centre - 0.5, cssWidth, 1);

        // Newest bar hugs the right edge, so the history scrolls leftwards as it fills.
        history.forEach((bar, index) => {
          const x = cssWidth - (history.length - index) * (BAR_WIDTH + BAR_GAP);
          const biased = clamp01(bar.amplitude * (0.85 + bar.pitch * 0.35));
          const barHeight = Math.max(MIN_BAR, biased * usable);
          context.fillStyle = colourForPitch(bar.pitch);
          drawBar(context, x, centre - barHeight / 2, BAR_WIDTH, barHeight);
        });
      };
      frameHandle = window.requestAnimationFrame(frame);
    }

    return () => {
      if (frameHandle !== null) window.cancelAnimationFrame(frameHandle);
      if (levelTimer !== null) window.clearInterval(levelTimer);
      source.disconnect();
      analyser.disconnect();
      audioContext.close().catch(() => undefined);
      if (releaseStreamOnUnmount) stream.getTracks().forEach((track) => track.stop());
    };
  }, [stream, reduced, releaseStreamOnUnmount]);

  if (reduced) {
    return (
      <div className={`flex min-w-0 flex-1 items-center gap-2 ${className}`} role="img" aria-label={`${label}: ${level}%`}>
        <div className="h-2 min-w-0 flex-1 overflow-hidden rounded-full bg-line-200">
          <div className="h-full rounded-full bg-purple-700" style={{ width: `${level}%` }} />
        </div>
        <span className="shrink-0 text-xs font-medium tabular-nums text-ink-500">{level}%</span>
      </div>
    );
  }

  return (
    <canvas
      ref={canvasRef}
      className={`block w-full min-w-0 flex-1 ${className}`}
      style={{ height }}
      role="img"
      aria-label={label}
    />
  );
}

function formatElapsed(ms: number) {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

/**
 * Android-parity live recording strip: blinking red dot, elapsed time, live waveform. Shared by the
 * attach-media recorder and the questionnaire per-question recorder so both read identically.
 */
export function RecordingStrip({
  stream,
  elapsedMs,
  label = "Recording"
}: {
  stream: MediaStream | null;
  elapsedMs: number;
  label?: string;
}) {
  return (
    <div className="flex items-center gap-2.5 rounded-md border border-line-200 bg-card p-3">
      <span className="h-3 w-3 shrink-0 animate-pulse rounded-full bg-error-600" aria-hidden />
      <span className="shrink-0 text-xs font-medium text-ink-700" role="timer">
        {label}&nbsp;&nbsp;{formatElapsed(elapsedMs)}
      </span>
      <Waveform stream={stream} height={36} label={`${label} waveform`} />
    </div>
  );
}
