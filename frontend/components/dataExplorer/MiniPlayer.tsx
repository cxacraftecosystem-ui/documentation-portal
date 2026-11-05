"use client";

import { Pause, Play, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

import { linesOf } from "@/components/dataExplorer/PreviewDialog";
import { lineAt, SEEK_STEP_SECONDS, shortClock } from "@/lib/dataExplorer/preview";
import { fetchTranscript } from "@/lib/transcript/api";
import type { Transcript } from "@/lib/transcript/contract";

export type MiniPlayerTrack = { mediaId: string; name: string; src: string; startMs?: number };

const ROUND = "grid h-10 w-10 shrink-0 place-items-center rounded-full text-xs text-white transition hover:bg-white/10";

function session(): MediaSession | null {
  if (typeof navigator === "undefined" || !("mediaSession" in navigator)) return null;
  return navigator.mediaSession;
}

export function MiniPlayer({ track, onExpand, onClose, onPosition }: { track: MiniPlayerTrack | null; onExpand: () => void; onClose: () => void; onPosition?: (ms: number, durationMs: number | null) => void }) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [position, setPosition] = useState(track?.startMs ?? 0);
  const [duration, setDuration] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const [transcript, setTranscript] = useState<{ id: string; value: Transcript | null } | null>(null);
  const mediaId = track?.mediaId ?? "";

  useEffect(() => {
    if (!mediaId) return;
    let cancelled = false;
    fetchTranscript(mediaId)
      .then((value) => {
        if (!cancelled) setTranscript({ id: mediaId, value });
      })
      .catch(() => {
        if (!cancelled) setTranscript({ id: mediaId, value: null });
      });
    return () => {
      cancelled = true;
    };
  }, [mediaId]);

  const lines = useMemo(() => (transcript && transcript.id === mediaId ? linesOf(transcript.value) : []), [transcript, mediaId]);
  const at = lineAt(lines, position);
  const line = at >= 0 ? lines[at] : null;
  const spoken = line ? `${line.speakerName ? `${line.speakerName}: ` : ""}“${(line.english || line.text).trim()}”` : null;

  const seek = (ms: number) => {
    const audio = audioRef.current;
    if (!audio) return;
    const limit = Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : Infinity;
    audio.currentTime = Math.max(0, Math.min(limit, ms / 1000));
    setPosition(audio.currentTime * 1000);
  };

  const toggle = () => {
    const audio = audioRef.current;
    if (!audio) return;
    if (audio.paused) void audio.play().catch(() => undefined);
    else audio.pause();
  };

  const handlers = useRef({ seek, toggle });
  useEffect(() => {
    handlers.current = { seek, toggle };
  });

  useEffect(() => {
    const media = session();
    if (!media || !track) return;
    if (typeof MediaMetadata !== "undefined") media.metadata = new MediaMetadata({ title: track.name, artist: spoken ?? undefined, album: "Data" });
  }, [track, spoken]);

  useEffect(() => {
    const media = session();
    if (!media || !track) return;
    const set = (action: MediaSessionAction, handler: MediaSessionActionHandler | null) => {
      try {
        media.setActionHandler(action, handler);
      } catch {
        return;
      }
    };
    set("play", () => void audioRef.current?.play().catch(() => undefined));
    set("pause", () => audioRef.current?.pause());
    set("seekbackward", (details) => handlers.current.seek((audioRef.current?.currentTime ?? 0) * 1000 - (details.seekOffset ?? SEEK_STEP_SECONDS) * 1000));
    set("seekforward", (details) => handlers.current.seek((audioRef.current?.currentTime ?? 0) * 1000 + (details.seekOffset ?? SEEK_STEP_SECONDS) * 1000));
    set("seekto", (details) => {
      if (typeof details.seekTime === "number") handlers.current.seek(details.seekTime * 1000);
    });
    return () => {
      for (const action of ["play", "pause", "seekbackward", "seekforward", "seekto"] as MediaSessionAction[]) set(action, null);
      media.metadata = null;
      media.playbackState = "none";
    };
  }, [track]);

  useEffect(() => {
    const media = session();
    if (media) media.playbackState = playing ? "playing" : "paused";
  }, [playing]);

  if (!track) return null;
  const progress = duration && duration > 0 ? Math.min(100, (position / duration) * 100) : 0;

  return (
    <div
      role="region"
      aria-label="Now playing"
      className="fixed inset-x-3 z-40 mx-auto max-w-xl overflow-hidden rounded-[18px] bg-[#1e1b2e] text-white shadow-[0_8px_28px_rgba(30,27,46,.3)] sm:inset-x-auto sm:left-1/2 sm:w-[min(36rem,calc(100vw-2rem))] sm:-translate-x-1/2"
      style={{ bottom: "calc(var(--bottom-nav-h, 0px) + 0.75rem + env(safe-area-inset-bottom, 0px))" }}
      data-testid="mini-player"
    >
      <audio
        ref={audioRef}
        src={track.src}
        autoPlay
        preload="auto"
        onLoadedMetadata={(event) => {
          const audio = event.currentTarget;
          if (Number.isFinite(audio.duration)) setDuration(audio.duration * 1000);
          if (track.startMs && track.startMs > 0) audio.currentTime = track.startMs / 1000;
        }}
        onTimeUpdate={(event) => {
          const audio = event.currentTarget;
          setPosition(audio.currentTime * 1000);
          onPosition?.(audio.currentTime * 1000, Number.isFinite(audio.duration) ? audio.duration * 1000 : null);
          const media = session();
          if (media?.setPositionState && Number.isFinite(audio.duration) && audio.duration > 0) {
            try {
              media.setPositionState({ duration: audio.duration, position: Math.min(audio.currentTime, audio.duration), playbackRate: audio.playbackRate });
            } catch {
              return;
            }
          }
        }}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
      />
      <div className="h-[3px] bg-[#3a3651]" aria-hidden>
        <div className="h-full bg-[#c090f5]" style={{ width: `${progress}%` }} />
      </div>
      <div className="flex items-center gap-2.5 px-3 py-2.5">
        <span aria-hidden className="grid h-10 w-10 shrink-0 place-items-center rounded-[10px] bg-[#fef3c7] font-bold text-[#92400e]">
          ♪
        </span>
        <button type="button" onClick={onExpand} className="min-w-0 flex-1 text-left" aria-label={`Open ${track.name}`}>
          <span className="block truncate text-[13px] font-semibold">{track.name}</span>
          <span className="block truncate text-xs text-[#cfcbe0]" aria-live="off">
            {spoken ?? `${playing ? "Playing" : "Paused"} · ${shortClock(position)}${duration ? ` of ${shortClock(duration)}` : ""}`}
          </span>
        </button>
        <button type="button" className={ROUND} aria-label={`Back ${SEEK_STEP_SECONDS} seconds`} onClick={() => seek(position - SEEK_STEP_SECONDS * 1000)}>
          ↺10
        </button>
        <button type="button" className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-[#c090f5] text-[#2a0f47] transition hover:bg-[#d4b0f8]" aria-label={playing ? "Pause" : "Play"} onClick={toggle}>
          {playing ? <Pause className="h-4 w-4" fill="currentColor" aria-hidden /> : <Play className="h-4 w-4" fill="currentColor" aria-hidden />}
        </button>
        {!playing ? (
          <button
            type="button"
            className={ROUND}
            aria-label="Stop and close player"
            onClick={() => {
              audioRef.current?.pause();
              onClose();
            }}
          >
            <X className="h-4 w-4" aria-hidden />
          </button>
        ) : null}
      </div>
    </div>
  );
}
