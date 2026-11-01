"use client";

import { useCallback, useEffect, useState } from "react";

/** The page's top and bottom chrome (the island or the search header, the bottom bar, the pill). */
const TOP_CLEAR = 96;
const BOTTOM_CLEAR = 120;

function lessMotion(): boolean {
  if (typeof window === "undefined") return true;
  return document.documentElement.dataset.reducedMotion === "true" || window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
}

function typing(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  return Boolean(element && (element.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(element.tagName)));
}

/**
 * Bring the line being spoken into view when it has left the reading band, centred, scrolling the
 * page or whichever container holds the transcript (a sheet, a dialog).
 */
export function revealTurn(turnId: string, smooth: boolean): void {
  const element = document.querySelector<HTMLElement>(`[data-follow-turn="${CSS.escape(turnId)}"]`);
  if (!element) return;
  const rect = element.getBoundingClientRect();
  if (rect.top >= TOP_CLEAR && rect.bottom <= window.innerHeight - BOTTOM_CLEAR) return;
  element.scrollIntoView({ block: "center", behavior: smooth ? "smooth" : "auto" });
}

/**
 * AUTO-FOLLOW (owner, 1 Nov 2026): while the recording plays, the transcript keeps the current line
 * in view. It starts with playback, and stops the moment the reader takes the page back — a touch
 * or a press on the screen, or a scroll against the reading direction (a wheel or a key upwards).
 * `resume` is the "Follow along" pill. Only the reader's own input stops it: the scrolls this hook
 * makes raise no wheel, touch or key event.
 */
export function useAutoFollow(activeTurnId: string | null, playing: boolean) {
  const [following, setFollowing] = useState(false);

  // Playback starting is the reader asking to listen: follow from there.
  useEffect(() => {
    if (playing) setFollowing(true);
  }, [playing]);

  useEffect(() => {
    if (!playing || !following) return;
    const stop = () => setFollowing(false);
    const onWheel = (event: WheelEvent) => {
      if (event.deltaY < 0) stop();
    };
    const onPointer = (event: PointerEvent) => {
      if (event.pointerType === "touch" || event.pointerType === "pen") stop();
    };
    const onKey = (event: KeyboardEvent) => {
      if ((event.key === "ArrowUp" || event.key === "PageUp" || event.key === "Home") && !typing(event.target)) stop();
    };
    window.addEventListener("wheel", onWheel, { passive: true });
    window.addEventListener("touchstart", stop, { passive: true });
    window.addEventListener("pointerdown", onPointer, { passive: true });
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("wheel", onWheel);
      window.removeEventListener("touchstart", stop);
      window.removeEventListener("pointerdown", onPointer);
      window.removeEventListener("keydown", onKey);
    };
  }, [playing, following]);

  useEffect(() => {
    if (!playing || !following || !activeTurnId) return;
    revealTurn(activeTurnId, !lessMotion());
  }, [activeTurnId, playing, following]);

  const resume = useCallback(() => setFollowing(true), []);

  return { following: playing && following, paused: playing && !following, resume };
}
