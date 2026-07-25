"use client";

import { useEffect, useState } from "react";
import { useReducedMotion } from "framer-motion";

/**
 * Reduced motion, as THIS app defines it.
 *
 * There are two independent sources and they must be OR-ed, never chosen between:
 *   - the OS preference (`prefers-reduced-motion: reduce`), which framer-motion's useReducedMotion
 *     already tracks and which app/globals.css already honours for CSS animations; and
 *   - the in-app Settings toggle, which lib/preferences.ts stamps onto <html> as
 *     `data-reduced-motion="true"` (before first paint, from the boot script in app/layout.tsx).
 *
 * The landing page is public and prerendered, so it cannot read ThemeProvider — it has no auth and
 * no provider context. Reading the attribute the boot script wrote is the same information without
 * the dependency, and it keeps this route static.
 *
 * Note for callers: reduced motion must change DURATIONS, never the `initial` state. The server
 * always renders as if motion were allowed, so an initial state that depended on this hook would
 * hydrate to different inline styles than the HTML it is replacing.
 */

const REDUCED_MOTION_ATTRIBUTE = "data-reduced-motion";

function appReducedMotion(): boolean {
  if (typeof document === "undefined") return false;
  return document.documentElement.getAttribute(REDUCED_MOTION_ATTRIBUTE) === "true";
}

export function useHeroReducedMotion(): boolean {
  const osReduced = useReducedMotion() ?? false;
  const [appReduced, setAppReduced] = useState(appReducedMotion);

  useEffect(() => {
    const root = document.documentElement;
    const sync = () => setAppReduced(appReducedMotion());
    sync();
    // Flipping "Reduce motion" in Settings and coming back here takes effect without a reload.
    const observer = new MutationObserver(sync);
    observer.observe(root, { attributes: true, attributeFilter: [REDUCED_MOTION_ATTRIBUTE] });
    return () => observer.disconnect();
  }, []);

  return osReduced || appReduced;
}

/** The house easing curve — the same cubic used by every other section on this page. */
export const HERO_EASE = [0.16, 1, 0.3, 1] as const;

type Offset = { y?: number; yPercent?: number; rotate?: number };

/**
 * One entrance step, as framer-motion props: fade up from `offset` into the element's natural
 * position after `delay` seconds. Reduced motion zeroes the delay and the duration, so the element
 * simply appears — while `initial` stays identical to what the server rendered.
 */
export function heroEntrance(reduce: boolean, delay: number, duration: number, offset: Offset = {}) {
  const animate: Offset & { opacity: number } = { opacity: 1 };
  if (offset.y !== undefined) animate.y = 0;
  if (offset.yPercent !== undefined) animate.yPercent = 0;
  if (offset.rotate !== undefined) animate.rotate = 0;

  return {
    initial: { opacity: 0, ...offset },
    animate,
    transition: { duration: reduce ? 0 : duration, delay: reduce ? 0 : delay, ease: HERO_EASE }
  };
}
