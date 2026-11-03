"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { Search } from "lucide-react";

import { TopBarScanButton } from "@/components/scan/TopBarScanButton";
import { quickReturnStep, type QuickReturnStep } from "@/lib/islandNav";
import { cn } from "@/lib/utils";

export function AppSearchHeader({
  appName,
  logo,
  onOpenSearch,
  searchOpen
}: {
  appName: string;
  logo: (className: string) => ReactNode;
  onOpenSearch: (button: HTMLButtonElement) => void;
  searchOpen: boolean;
}) {
  const [step, setStep] = useState<QuickReturnStep>({ shown: true, travel: 0 });
  const [floating, setFloating] = useState(false);
  const headerRef = useRef<HTMLElement | null>(null);
  const pinnedRef = useRef(searchOpen);

  useEffect(() => {
    pinnedRef.current = searchOpen;
    if (searchOpen) setStep({ shown: true, travel: 0 });
  }, [searchOpen]);

  useEffect(() => {
    let last = window.scrollY;
    let frame = 0;
    const onScroll = () => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        const now = window.scrollY;
        const delta = now - last;
        const pinned = pinnedRef.current;
        const height = (headerRef.current?.offsetHeight ?? 60) + 12;
        setStep((state) => quickReturnStep(state, delta, now, height, pinned));
        setFloating(now > 0);
        last = now;
      });
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
  }, []);

  const shown = step.shown || searchOpen;

  return (
    <div
      className={cn(
        "app-search-header-frame pointer-events-none fixed inset-x-0 top-3 z-50 flex justify-center transition-[translate,opacity] duration-[180ms] ease-out lg:hidden",
        shown ? "translate-y-0 opacity-100" : "-translate-y-[calc(100%+1rem)] opacity-0"
      )}
      data-shown={shown ? "true" : "false"}
      inert={!shown}
    >
      <header
        ref={headerRef}
        data-testid="app-search-header"
        className={cn(
          "pointer-events-auto relative flex min-h-[60px] w-full items-center justify-center rounded-[28px] border border-line-200 bg-card",
          floating ? "shadow-island" : "shadow-none"
        )}
      >
        <button
          type="button"
          onClick={(event) => onOpenSearch(event.currentTarget)}
          aria-label={`${appName}. Search`}
          aria-expanded={searchOpen}
          className="absolute inset-0 rounded-[28px]"
        />
        <span aria-hidden className="pointer-events-none flex min-w-0 items-center gap-2.5 px-14">
          {logo("h-[34px] w-[34px] shrink-0 rounded-[10px]")}
          <span className="truncate font-display text-lg font-bold tracking-tight text-ink-900">{appName}</span>
        </span>
        <span className="absolute inset-y-0 left-1.5 flex items-center">
          <TopBarScanButton />
        </span>
        <span aria-hidden className="pointer-events-none absolute inset-y-0 right-1.5 flex w-12 items-center justify-center text-purple-700">
          <Search className="h-5 w-5" />
        </span>
      </header>
    </div>
  );
}
