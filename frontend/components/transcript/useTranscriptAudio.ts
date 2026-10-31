"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/**
 * The recording behind a transcript: one `<audio>` the viewer owns, so a timestamp, a citation chip
 * or a tapped word can seek it, and karaoke can read where it is.
 *
 * `timeupdate` fires only about four times a second, so while playing a frame loop reads
 * `currentTime` instead; the word highlight then keeps pace with speech. The loop runs only while
 * playing and stops with the component.
 */
export function useTranscriptAudio(src: string | null | undefined) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [currentMs, setCurrentMs] = useState(0);
  const [durationMs, setDurationMs] = useState(0);
  const frame = useRef<number | null>(null);

  useEffect(() => {
    setPlaying(false);
    setCurrentMs(0);
    setDurationMs(0);
  }, [src]);

  useEffect(() => {
    if (!playing) return;
    const tick = () => {
      const audio = audioRef.current;
      if (audio) setCurrentMs(Math.round(audio.currentTime * 1000));
      frame.current = requestAnimationFrame(tick);
    };
    frame.current = requestAnimationFrame(tick);
    return () => {
      if (frame.current) cancelAnimationFrame(frame.current);
    };
  }, [playing]);

  const seek = useCallback((ms: number, play = true) => {
    const audio = audioRef.current;
    setCurrentMs(ms);
    if (!audio) return;
    audio.currentTime = ms / 1000;
    if (play && audio.paused) void audio.play().catch(() => setPlaying(false));
  }, []);

  const toggle = useCallback(() => {
    const audio = audioRef.current;
    if (!audio) return;
    if (audio.paused) void audio.play().catch(() => setPlaying(false));
    else audio.pause();
  }, []);

  const bind = {
    ref: audioRef,
    src: src ?? undefined,
    preload: "metadata" as const,
    onPlay: () => setPlaying(true),
    onPause: () => setPlaying(false),
    onEnded: () => setPlaying(false),
    onTimeUpdate: () => {
      const audio = audioRef.current;
      if (audio) setCurrentMs(Math.round(audio.currentTime * 1000));
    },
    onLoadedMetadata: () => {
      const audio = audioRef.current;
      if (audio && Number.isFinite(audio.duration)) setDurationMs(Math.round(audio.duration * 1000));
    },
    onDurationChange: () => {
      const audio = audioRef.current;
      if (audio && Number.isFinite(audio.duration)) setDurationMs(Math.round(audio.duration * 1000));
    }
  };

  return { bind, playing, currentMs, durationMs, seek, toggle, hasAudio: Boolean(src) };
}

export type TranscriptAudio = ReturnType<typeof useTranscriptAudio>;
