"use client";

import { useRef } from "react";

import { useLiquidGlass, type LiquidGlassOptions } from "@/components/ui/LiquidGlass";

export const GLASS_PANEL: LiquidGlassOptions = {
  scale: -100,
  chroma: 5,
  border: 0.035,
  mapBlur: 12,
  blur: 3,
  saturate: 1.45,
  fallbackBlur: 18
};

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
