"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

export const FLASH_MS = 1400;

const EDGE_PADDING = 24;

function viewportHeight(): number {
  return window.visualViewport?.height ?? window.innerHeight;
}

export type RevealRow = {
  registerRow: (key: string) => (node: HTMLElement | null) => void;
  containerRef: React.MutableRefObject<HTMLElement | null>;
  reveal: (key: string | null | undefined) => void;
  flashKey: string | null;
};

export function useRevealRow(): RevealRow {
  const reducedMotion = useAppReducedMotion();
  const rows = useRef(new Map<string, HTMLElement>());
  const containerRef = useRef<HTMLElement | null>(null);
  const [flashKey, setFlashKey] = useState<string | null>(null);
  const flashTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const frame = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (flashTimer.current) clearTimeout(flashTimer.current);
      if (frame.current !== null) cancelAnimationFrame(frame.current);
    },
    []
  );

  const registerRow = useCallback(
    (key: string) => (node: HTMLElement | null) => {
      if (node) rows.current.set(key, node);
      else rows.current.delete(key);
    },
    []
  );

  const reveal = useCallback(
    (key: string | null | undefined) => {
      if (!key) return;
      setFlashKey(null);
      if (flashTimer.current) clearTimeout(flashTimer.current);

      if (frame.current !== null) cancelAnimationFrame(frame.current);
      frame.current = requestAnimationFrame(() => {
        frame.current = null;
        const row = rows.current.get(key);
        if (row) scrollRowIntoView(row, containerRef.current, reducedMotion);
        setFlashKey(key);
        flashTimer.current = setTimeout(() => setFlashKey(null), FLASH_MS);
      });
    },
    [reducedMotion]
  );

  return { registerRow, containerRef, reveal, flashKey };
}

function scrollRowIntoView(
  row: HTMLElement,
  container: HTMLElement | null,
  reducedMotion: boolean
): void {
  const behavior: ScrollBehavior = reducedMotion ? "auto" : "smooth";

  if (container && container.scrollHeight > container.clientHeight + 1) {
    const box = container.getBoundingClientRect();
    const bandTop = Math.max(box.top, 0);
    const bandBottom = Math.min(box.bottom, viewportHeight());
    if (bandBottom - bandTop < 1) {
      row.scrollIntoView({ behavior, block: "nearest", inline: "nearest" });
      return;
    }
    const rowBox = row.getBoundingClientRect();
    const above = rowBox.top < bandTop + EDGE_PADDING;
    const below = rowBox.bottom > bandBottom - EDGE_PADDING;
    if (!above && !below) return;
    const bandCentre = (bandTop + bandBottom) / 2;
    const delta = rowBox.top + rowBox.height / 2 - bandCentre;
    const target = Math.max(
      0,
      Math.min(container.scrollTop + delta, container.scrollHeight - container.clientHeight)
    );
    container.scrollTo({ top: target, behavior });
    return;
  }

  row.scrollIntoView({ behavior, block: "nearest", inline: "nearest" });
}
