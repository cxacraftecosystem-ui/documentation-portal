"use client";

import { useEffect, useState } from "react";
import { useReducedMotion } from "framer-motion";

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
    const observer = new MutationObserver(sync);
    observer.observe(root, { attributes: true, attributeFilter: [REDUCED_MOTION_ATTRIBUTE] });
    return () => observer.disconnect();
  }, []);

  return osReduced || appReduced;
}

export const HERO_EASE = [0.16, 1, 0.3, 1] as const;

type Offset = { y?: number; yPercent?: number; rotate?: number };

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
