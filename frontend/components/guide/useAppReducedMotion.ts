"use client";

import { useReducedMotion } from "framer-motion";

import { useThemePreferences } from "@/components/ThemeProvider";

/**
 * The reduced-motion answer for JavaScript-driven animation: the OR of BOTH switches.
 *
 * There are two of them and they are deliberately unioned (see `lib/preferences.ts` →
 * `Preferences.reducedMotion`: "ORs with the OS setting; it can never switch the OS preference
 * off"):
 *
 *  1. The **OS** preference, `prefers-reduced-motion: reduce`. This is all that framer-motion's
 *     own `useReducedMotion()` can see — it subscribes to that media query and nothing else.
 *  2. The **app** preference, the Settings toggle owned by `ThemeProvider`, which stamps
 *     `data-reduced-motion="true"` on `<html>`.
 *
 * CSS already honours both: `app/globals.css` pairs the `@media (prefers-reduced-motion: reduce)`
 * block with a `:root[data-reduced-motion="true"]` block, because a media query cannot be OR-ed
 * with a selector. JavaScript animation has no such twin — framer-motion never reads the attribute
 * — so a user who turned reduced motion on in Settings still got the full scroll spring, pointer
 * tracking and layout animations. This hook is the missing half: pass its result wherever a
 * component previously wrote `useReducedMotion() ?? false`.
 *
 * Safe to call anywhere under the root layout: `ThemeProvider` wraps the whole app in
 * `app/layout.tsx`. Server-side and on the first client render both sources read false (the OS
 * query is unknown, the stored preferences have not been read yet), so the markup matches and the
 * mount effect corrects it a tick later — the same sequence every other themed surface follows.
 */
export function useAppReducedMotion(): boolean {
  const osPrefersReduced = useReducedMotion() ?? false;
  const { preferences } = useThemePreferences();
  return osPrefersReduced || preferences.reducedMotion;
}
