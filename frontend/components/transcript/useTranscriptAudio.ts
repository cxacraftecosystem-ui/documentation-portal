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
  /** Where a sample stops by itself (a speaker's few seconds); null while playing freely. */
  const stopAt = useRef<number | null>(null);
  const [sampling, setSampling] = useState(false);

  const reached = useCallback((ms: number) => {
    const audio = audioRef.current;
    if (stopAt.current !== null && ms >= stopAt.current) {
      stopAt.current = null;
      setSampling(false);
      audio?.pause();
    }
  }, []);

  useEffect(() => {
    setPlaying(false);
    setCurrentMs(0);
    setDurationMs(0);
  }, [src]);

  useEffect(() => {
    if (!playing) return;
    const tick = () => {
      const audio = audioRef.current;
      if (audio) {
        const ms = Math.round(audio.currentTime * 1000);
        setCurrentMs(ms);
        reached(ms);
      }
      frame.current = requestAnimationFrame(tick);
    };
    frame.current = requestAnimationFrame(tick);
    return () => {
      if (frame.current) cancelAnimationFrame(frame.current);
    };
  }, [playing, reached]);

  const seek = useCallback((ms: number, play = true) => {
    const audio = audioRef.current;
    setCurrentMs(ms);
    if (!audio) return;
    audio.currentTime = ms / 1000;
    if (play && audio.paused) void audio.play().catch(() => setPlaying(false));
  }, []);

  /** Play from `startMs` and stop at `endMs` by itself: a speaker's sample. Calling it again stops. */
  const playRange = useCallback((startMs: number, endMs: number) => {
    const audio = audioRef.current;
    if (!audio) return;
    if (stopAt.current !== null && !audio.paused) {
      stopAt.current = null;
      setSampling(false);
      audio.pause();
      return;
    }
    stopAt.current = endMs;
    setSampling(true);
    setCurrentMs(startMs);
    audio.currentTime = startMs / 1000;
    void audio.play().catch(() => {
      stopAt.current = null;
      setSampling(false);
      setPlaying(false);
    });
  }, []);

  const toggle = useCallback(() => {
    const audio = audioRef.current;
    if (!audio) return;
    stopAt.current = null;
    setSampling(false);
    if (audio.paused) void audio.play().catch(() => setPlaying(false));
    else audio.pause();
  }, []);

  const bind = {
    ref: audioRef,
    src: src ?? undefined,
    preload: "metadata" as const,
    onPlay: () => setPlaying(true),
    onPause: () => {
      setPlaying(false);
      setSampling(false);
    },
    onEnded: () => {
      setPlaying(false);
      setSampling(false);
      stopAt.current = null;
    },
    onTimeUpdate: () => {
      const audio = audioRef.current;
      if (audio) {
        const ms = Math.round(audio.currentTime * 1000);
        setCurrentMs(ms);
        reached(ms);
      }
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

  return { bind, playing, sampling, currentMs, durationMs, seek, toggle, playRange, hasAudio: Boolean(src) };
}

export type TranscriptAudio = ReturnType<typeof useTranscriptAudio>;
