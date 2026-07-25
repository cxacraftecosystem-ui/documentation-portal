"use client";

/**
 * A plain `<div>` with the liquid-glass backdrop filter attached (see `ui/LiquidGlass`).
 *
 * Deliberately dressing-free: the tint, border and shadow stay in the caller's className so
 * each surface keeps its own themed tokens, and so the frosted look a surface already has
 * survives as the fallback on Safari/Firefox (where the filter no-ops and `LiquidGlass` swaps
 * in a plain blur plus the `lg-fallback` class).
 *
 * Two hard constraints, both verified in Chromium rather than assumed:
 *  - the caller's background MUST be translucent — the filter refracts what is *behind* the
 *    element, and an opaque fill paints straight over the result;
 *  - glass inside glass is blind. An ancestor with its own `backdrop-filter` (or `opacity` < 1)
 *    becomes the backdrop root, so a nested surface can only refract that ancestor's flat wash.
 *    Do not nest these; give the outer surface the glass and let the inner one stay frosted.
 *
 * Each instance builds its own canvas displacement map and SVG filter, so this belongs on
 * cards and panels — never on list rows or anything else that renders in bulk.
 */

import { useRef } from "react";

import { useLiquidGlass, type LiquidGlassOptions } from "@/components/ui/LiquidGlass";

/**
 * Large surfaces (the auth card): a wide, soft refraction band, and a fallback blur matching
 * the `.glass-card` recipe so the two paths look like the same material.
 */
export const GLASS_PANEL: LiquidGlassOptions = {
  scale: -100,
  chroma: 5,
  border: 0.035,
  mapBlur: 12,
  blur: 3,
  saturate: 1.45,
  fallbackBlur: 18
};

/**
 * Card-sized surfaces (dashboard tiles). `border` is a fraction of the shorter side, so a
 * ~150px-tall tile needs a bigger fraction than a panel to end up with a comparable rim.
 */
export const GLASS_TILE: LiquidGlassOptions = {
  scale: -60,
  chroma: 4,
  border: 0.08,
  mapBlur: 8,
  blur: 2,
  saturate: 1.3,
  fallbackBlur: 14
};

export function GlassSurface({
  options,
  children,
  ...rest
}: React.HTMLAttributes<HTMLDivElement> & { options?: LiquidGlassOptions }) {
  const surface = useRef<HTMLDivElement>(null);
  useLiquidGlass(surface, options);

  return (
    <div ref={surface} {...rest}>
      {children}
    </div>
  );
}
