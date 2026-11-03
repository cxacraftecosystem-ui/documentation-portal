"use client";

import { useReducedMotion } from "framer-motion";

import { useThemePreferences } from "@/components/ThemeProvider";

export function useAppReducedMotion(): boolean {
  const osPrefersReduced = useReducedMotion() ?? false;
  const { preferences } = useThemePreferences();
  return osPrefersReduced || preferences.reducedMotion;
}
