"use client";

import { useEffect, useId, type RefObject } from "react";

const SVG_NS = "http://www.w3.org/2000/svg";

export type LiquidGlassOptions = {
  scale?: number;
  chroma?: number;
  border?: number;
  mapBlur?: number;
  blur?: number;
  saturate?: number;
  radius?: number | null;
  fallbackBlur?: number;
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
    const probe = document.createElement("canvas");
    probe.width = probe.height = 4;
    probe.getContext("2d")!.getImageData(0, 0, 1, 1);
    cachedSupport = true;
  } catch {
    cachedSupport = false;
  }
  return cachedSupport;
}

let sharedDefs: SVGDefsElement | null = null;

function ensureDefs(): SVGDefsElement {
  if (sharedDefs?.isConnected) return sharedDefs;
  const svg = document.createElementNS(SVG_NS, "svg");
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
  filter.setAttribute("color-interpolation-filters", "sRGB");

  const feImage = document.createElementNS(SVG_NS, "feImage");
  feImage.setAttribute("x", "0");
  feImage.setAttribute("y", "0");
  feImage.setAttribute("result", "map");
  feImage.setAttribute("preserveAspectRatio", "none");
  filter.appendChild(feImage);

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

export function useLiquidGlass<T extends HTMLElement>(
  ref: RefObject<T | null>,
  options: LiquidGlassOptions = {}
) {
  const reactId = useId();
  const filterId = `lg-${reactId.replace(/[^a-zA-Z0-9_-]/g, "")}`;
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
  }, [ref, filterId, optionsKey]);
}
