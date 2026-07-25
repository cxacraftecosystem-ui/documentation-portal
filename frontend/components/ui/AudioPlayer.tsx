"use client";

import { Pause, Play } from "lucide-react";
import { useEffect, useRef, useState } from "react";

function formatTime(seconds: number) {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
  const whole = Math.floor(seconds);
  const m = Math.floor(whole / 60);
  const s = whole % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

/**
 * Themed inline audio player (play/pause, seek bar, elapsed/total time) — replaces the
 * grey browser `<audio controls>` everywhere recordings are played back.
 */
export function AudioPlayer({ src, className }: { src: string; className?: string }) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [duration, setDuration] = useState(0);
  const [current, setCurrent] = useState(0);

  // A new src is a new recording: reset the transport.
  useEffect(() => {
    setPlaying(false);
    setCurrent(0);
    setDuration(0);
  }, [src]);

  function readDuration() {
    const audio = audioRef.current;
    if (audio && Number.isFinite(audio.duration)) setDuration(audio.duration);
  }

  function toggle() {
    const audio = audioRef.current;
    if (!audio) return;
    if (audio.paused) void audio.play().catch(() => setPlaying(false));
    else audio.pause();
  }

  function seek(next: number) {
    const audio = audioRef.current;
    if (!audio) return;
    audio.currentTime = next;
    setCurrent(next);
  }

  const max = duration > 0 ? duration : Math.max(current, 1);
  const pct = Math.min(100, Math.max(0, (current / max) * 100));
  const fill = `linear-gradient(to right, oklch(0.47 0.198 305) 0%, oklch(0.47 0.198 305) ${pct}%, #e4e2ef ${pct}%, #e4e2ef 100%)`;

  return (
    <div className={`flex items-center gap-3 rounded-md border border-line-200 bg-card px-3 py-2 ${className ?? ""}`}>
      <audio
        ref={audioRef}
        src={src}
        preload="metadata"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        onLoadedMetadata={readDuration}
        onDurationChange={readDuration}
        onTimeUpdate={() => {
          const audio = audioRef.current;
          if (!audio) return;
          setCurrent(audio.currentTime);
          // Streams (e.g. fresh WebM recordings) report Infinity until played through.
          if (Number.isFinite(audio.duration)) setDuration(audio.duration);
        }}
        className="hidden"
      />
      <button
        type="button"
        aria-label={playing ? "Pause" : "Play"}
        title={playing ? "Pause" : "Play"}
        onClick={toggle}
        className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-purple-700 text-white shadow-sm transition hover:bg-purple-800"
      >
        {playing ? <Pause className="h-4 w-4" aria-hidden /> : <Play className="ml-0.5 h-4 w-4" aria-hidden />}
      </button>
      <span className="w-10 shrink-0 text-right text-xs tabular-nums text-ink-500">{formatTime(current)}</span>
      <input
        type="range"
        aria-label="Seek"
        min={0}
        max={max}
        step={0.1}
        value={Math.min(current, max)}
        onChange={(event) => seek(Number(event.target.value))}
        className="audio-range min-w-0 flex-1"
        style={{ "--audio-range-fill": fill } as React.CSSProperties}
      />
      <span className="w-10 shrink-0 text-xs tabular-nums text-ink-500">{duration > 0 ? formatTime(duration) : "--:--"}</span>
    </div>
  );
}
