"use client";

/**
 * Apple-style liquid-glass refraction, ported to React from the reference
 * `liquid-glass.js` module.
 *
 * The effect is an SVG displacement filter driven through `backdrop-filter`, so the
 * *backdrop* behind the element is refracted rather than the element itself. The
 * displacement map is a canvas-generated gradient pair (red ramp = X offset, blue ramp
 * = Y offset) with a blurred, inset neutral-grey rounded rect painted over the middle —
 * that neutral core cancels displacement in the interior, confining the bulge to an edge
 * band whose curvature is set by the blur radius.
 *
 * Three displacement passes run at staggered scales with the channels isolated by
 * `feColorMatrix` and recombined with `screen` blends, which is what produces the faint
 * prism fringe along the rim.
 *
 * Only Chromium applies SVG filters via `backdrop-filter`; Safari and Firefox silently
 * no-op, so they fall back to a plain frosted blur and get the `lg-fallback` class for
 * any CSS that wants to compensate.
 *
 * Visual dressing (tint, inner highlight, glare, shadow) is intentionally NOT set here —
 * it lives in the `.liquid-glass` CSS recipe in globals.css so surfaces stay themeable.
 *
 * Technique per https://aave.com/design/building-glass-for-the-web
 * and https://github.com/rizroze/liquid-glass.
 */

import { useEffect, useId, type RefObject } from "react";

const SVG_NS = "http://www.w3.org/2000/svg";

export type LiquidGlassOptions = {
  /** Displacement strength; negative reads as a magnifying bulge. */
  scale?: number;
  /** Per-channel scale stagger driving the prism fringe; 0 disables it. */
  chroma?: number;
  /** Neutral inset as a fraction of the smaller side. */
  border?: number;
  /** Edge-curvature softness (px) of the map's grey inset. */
  mapBlur?: number;
  /** Backdrop blur (px) behind the glass interior. */
  blur?: number;
  /** Backdrop saturation boost. */
  saturate?: number;
  /** Corner radius override (px); defaults to the element's computed border-radius. */
  radius?: number | null;
  /** Frosted blur (px) used where SVG-filtered backdrops are unsupported. */
  fallbackBlur?: number;
  /** Set false to leave the element untouched (e.g. when reduced transparency is on). */
  enabled?: boolean;
};

const DEFAULTS: Required<Omit<LiquidGlassOptions, "radius" | "enabled">> & {
  radius: number | null;
  enabled: boolean;
} = {
  scale: -112,
  chroma: 6,
  border: 0.07,
  mapBlur: 12,
  blur: 3,
  saturate: 1.5,
  radius: null,
  fallbackBlur: 16,
  enabled: true,
};

/** Chromium-only capability probe; evaluated lazily so it never runs during SSR. */
let cachedSupport: boolean | null = null;

function supportsRefraction(): boolean {
  if (cachedSupport !== null) return cachedSupport;
  if (typeof window === "undefined") return false;
  const ua = navigator.userAgent;
  const isSafari = /Safari/.test(ua) && !/Chrome|Chromium|Edg/.test(ua);
  const isFirefox = /Firefox/.test(ua);
  if (isSafari || isFirefox) {
    cachedSupport = false;
    return cachedSupport;
  }
  if (!window.CSS?.supports?.("backdrop-filter", "url(#lg)")) {
    cachedSupport = false;
    return cachedSupport;
  }
  try {
    // A tainted/blocked canvas would make the displacement map unreadable.
    const probe = document.createElement("canvas");
    probe.width = probe.height = 4;
    probe.getContext("2d")!.getImageData(0, 0, 1, 1);
    cachedSupport = true;
  } catch {
    cachedSupport = false;
  }
  return cachedSupport;
}

/** The shared <defs> host every instance's filter is appended into. */
let sharedDefs: SVGDefsElement | null = null;

function ensureDefs(): SVGDefsElement {
  if (sharedDefs?.isConnected) return sharedDefs;
  const svg = document.createElementNS(SVG_NS, "svg");
  // 0x0 keeps it renderable — display:none would break feImage resolution.
  svg.setAttribute("width", "0");
  svg.setAttribute("height", "0");
  svg.setAttribute("aria-hidden", "true");
  svg.style.position = "absolute";
  svg.style.pointerEvents = "none";
  const defs = document.createElementNS(SVG_NS, "defs");
  svg.appendChild(defs);
  document.body.appendChild(svg);
  sharedDefs = defs;
  return defs;
}

/**
 * Build the displacement map as a data URI.
 *
 * A red left→right ramp encodes X displacement and a blue top→bottom ramp encodes Y;
 * `difference` compositing keeps both because the channels are disjoint. The blurred,
 * inset 50%-grey rounded rect then neutralises the interior.
 */
function makeMap(w: number, h: number, radius: number, border: number, mapBlur: number): string {
  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d")!;

  const gx = ctx.createLinearGradient(0, 0, w, 0);
  gx.addColorStop(0, "rgb(0,0,0)");
  gx.addColorStop(1, "rgb(255,0,0)");
  ctx.fillStyle = gx;
  ctx.fillRect(0, 0, w, h);

  const gy = ctx.createLinearGradient(0, 0, 0, h);
  gy.addColorStop(0, "rgb(0,0,0)");
  gy.addColorStop(1, "rgb(0,0,255)");
  ctx.globalCompositeOperation = "difference";
  ctx.fillStyle = gy;
  ctx.fillRect(0, 0, w, h);

  ctx.globalCompositeOperation = "source-over";
  const inset = border * Math.min(w, h);
  ctx.filter = `blur(${mapBlur}px)`;
  ctx.fillStyle = "rgba(128,128,128,0.93)";
  ctx.beginPath();
  ctx.roundRect(inset, inset, w - inset * 2, h - inset * 2, Math.max(radius - inset, 2));
  ctx.fill();
  ctx.filter = "none";

  return canvas.toDataURL();
}

type FilterParts = { filter: SVGFilterElement; feImage: SVGFEImageElement };

function buildFilter(id: string, scales: [number, number, number]): FilterParts {
  const filter = document.createElementNS(SVG_NS, "filter");
  filter.setAttribute("id", id);
  filter.setAttribute("x", "0");
  filter.setAttribute("y", "0");
  filter.setAttribute("width", "100%");
  filter.setAttribute("height", "100%");
  // Load-bearing: filters default to linearRGB, which re-maps the map's neutral grey 128
  // to ~0.216 and injects a constant phantom displacement across the whole surface.
  filter.setAttribute("color-interpolation-filters", "sRGB");

  const feImage = document.createElementNS(SVG_NS, "feImage");
  feImage.setAttribute("x", "0");
  feImage.setAttribute("y", "0");
  feImage.setAttribute("result", "map");
  feImage.setAttribute("preserveAspectRatio", "none");
  filter.appendChild(feImage);

  // Keep exactly one channel per pass so the three offsets can be screened back together.
  const keep = [
    "1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0",
    "0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0",
    "0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0",
  ];
  const channels: string[] = [];
  for (let i = 0; i < 3; i++) {
    const disp = document.createElementNS(SVG_NS, "feDisplacementMap");
    disp.setAttribute("in", "SourceGraphic");
    disp.setAttribute("in2", "map");
    disp.setAttribute("scale", String(scales[i]));
    disp.setAttribute("xChannelSelector", "R");
    disp.setAttribute("yChannelSelector", "B");
    disp.setAttribute("result", `d${i}`);
    filter.appendChild(disp);

    const cm = document.createElementNS(SVG_NS, "feColorMatrix");
    cm.setAttribute("in", `d${i}`);
    cm.setAttribute("type", "matrix");
    cm.setAttribute("values", keep[i]);
    cm.setAttribute("result", `c${i}`);
    filter.appendChild(cm);
    channels.push(`c${i}`);
  }

  const blend1 = document.createElementNS(SVG_NS, "feBlend");
  blend1.setAttribute("in", channels[0]);
  blend1.setAttribute("in2", channels[1]);
  blend1.setAttribute("mode", "screen");
  blend1.setAttribute("result", "c01");
  filter.appendChild(blend1);

  const blend2 = document.createElementNS(SVG_NS, "feBlend");
  blend2.setAttribute("in", "c01");
  blend2.setAttribute("in2", channels[2]);
  blend2.setAttribute("mode", "screen");
  filter.appendChild(blend2);

  ensureDefs().appendChild(filter);
  return { filter, feImage };
}

/**
 * Set (or clear, with "") both the standard and the -webkit- backdrop-filter.
 *
 * Safari still requires the prefixed property, but `webkitBackdropFilter` is absent from
 * this TypeScript DOM lib, so it goes through setProperty rather than a cast.
 */
function setBackdrop(el: HTMLElement, value: string): void {
  if (value) {
    el.style.backdropFilter = value;
    el.style.setProperty("-webkit-backdrop-filter", value);
  } else {
    el.style.removeProperty("backdrop-filter");
    el.style.removeProperty("-webkit-backdrop-filter");
  }
}

function resolveRadius(el: HTMLElement, w: number, h: number, override: number | null): number {
  if (override != null) return override;
  const raw = getComputedStyle(el).borderTopLeftRadius || "0px";
  const value = parseFloat(raw) || 0;
  return raw.trim().endsWith("%") ? (value / 100) * Math.min(w, h) : value;
}

/**
 * Apply liquid glass to the element behind `ref`.
 *
 * Re-runs only when the option *values* change (they are compared structurally), so a
 * caller passing an inline options object does not thrash the filter on every render.
 */
export function useLiquidGlass<T extends HTMLElement>(
  ref: RefObject<T | null>,
  options: LiquidGlassOptions = {}
) {
  const reactId = useId();
  // A DOM id must survive the React `:r0:` colon syntax, which is not a valid CSS ident.
  const filterId = `lg-${reactId.replace(/[^a-zA-Z0-9_-]/g, "")}`;
  // Structural digest of the options: lets the effect depend on option *values* without
  // re-running on every render just because the caller passed a fresh object literal.
  // Parsed back inside the effect so no ref is written during render.
  const optionsKey = JSON.stringify(options);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    const o: Required<Omit<LiquidGlassOptions, "radius">> & { radius: number | null } = {
      ...DEFAULTS,
      ...(JSON.parse(optionsKey) as LiquidGlassOptions),
    };
    if (!o.enabled) return;

    if (!supportsRefraction()) {
      setBackdrop(el, `blur(${o.fallbackBlur}px) saturate(${o.saturate})`);
      el.classList.add("lg-fallback");
      return () => {
        setBackdrop(el, "");
        el.classList.remove("lg-fallback");
      };
    }

    const scales: [number, number, number] = [o.scale, o.scale + o.chroma, o.scale + 2 * o.chroma];
    const parts = buildFilter(filterId, scales);

    const refresh = () => {
      const w = el.offsetWidth;
      const h = el.offsetHeight;
      if (!w || !h) return;
      const radius = resolveRadius(el, w, h, o.radius);
      parts.feImage.setAttribute("href", makeMap(w, h, radius, o.border, o.mapBlur));
      parts.feImage.setAttribute("width", String(w));
      parts.feImage.setAttribute("height", String(h));
    };

    refresh();
    setBackdrop(el, `url(#${filterId}) blur(${o.blur}px) saturate(${o.saturate})`);
    el.classList.add("lg-active");

    // Regenerating the map is a canvas round-trip, so coalesce resize bursts.
    let timer: ReturnType<typeof setTimeout> | null = null;
    const observer = new ResizeObserver(() => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(refresh, 120);
    });
    observer.observe(el);

    return () => {
      observer.disconnect();
      if (timer) clearTimeout(timer);
      parts.filter.remove();
      setBackdrop(el, "");
      el.classList.remove("lg-active");
    };
    // optionsKey is the structural digest of `options`; filterId is stable per instance.
  }, [ref, filterId, optionsKey]);
}
