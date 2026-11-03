"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  CircleUserRound,
  Ellipsis,
  FolderOpen,
  LayoutGrid,
  Menu as MenuIcon,
  NotebookPen,
  ShieldCheck,
  type LucideIcon
} from "lucide-react";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { IslandMoreSheet } from "@/components/nav/IslandMoreSheet";
import {
  ISLAND_MORE_LABEL,
  islandBadgeText,
  islandKeyboardOpen,
  islandLabelRoom,
  islandLabelSize,
  islandMoreBadge,
  islandShortLabel,
  islandSlotBadge,
  islandSlotName,
  islandSlots,
  isIslandMoreActive,
  isIslandSlotActive,
  splitIsland,
  type IslandEntry,
  type IslandGroup,
  type IslandSlot
} from "@/lib/islandNav";
import { cn } from "@/lib/utils";

export function islandGroupIcon(label: string): LucideIcon {
  switch (label) {
    case "Record":
      return NotebookPen;
    case "Browse":
      return FolderOpen;
    case "Admin":
      return ShieldCheck;
    case "Account":
      return CircleUserRound;
    default:
      return LayoutGrid;
  }
}

const SLOT_CLASS =
  "relative flex min-h-12 w-full flex-col items-center justify-center gap-0.5 rounded-[20px] px-0.5 py-1 transition-colors duration-[180ms]";

function slotTone(active: boolean): string {
  return active ? "bg-field-100 text-purple-700" : "text-ink-700 hover:bg-surface-50";
}

function SlotBadge({ count }: { count: number }) {
  const text = islandBadgeText(count);
  if (!text) return null;
  return (
    <span
      aria-hidden
      className="absolute -right-2.5 -top-1.5 inline-flex min-w-[1.125rem] items-center justify-center rounded-full bg-purple-700 px-1 text-[10px] font-semibold leading-[1.125rem] text-white ring-2 ring-card"
    >
      {text}
    </span>
  );
}

function SlotFace({ label, icon: Icon, badge, showLabel }: { label: string; icon: LucideIcon; badge: number; showLabel: boolean }) {
  return (
    <>
      <span className="relative inline-flex">
        <Icon className={showLabel ? "h-5 w-5" : "h-[22px] w-[22px]"} aria-hidden />
        <SlotBadge count={badge} />
      </span>
      {showLabel ? (
        <span aria-hidden className="max-w-full truncate text-[11px] font-medium leading-tight">
          {islandShortLabel(label)}
        </span>
      ) : null}
    </>
  );
}

export function BottomIslandNav({
  roots,
  groups,
  currentLabel,
  onOpenMenu,
  menuOpen,
  hidden = false
}: {
  roots: IslandEntry<LucideIcon>[];
  groups: IslandGroup<LucideIcon>[];
  currentLabel: string | null;
  onOpenMenu: (button: HTMLButtonElement) => void;
  menuOpen: boolean;
  hidden?: boolean;
}) {
  const reduce = useAppReducedMotion();
  const pathname = usePathname();
  const [openGroup, setOpenGroup] = useState<string | null>(null);
  const [moreOpen, setMoreOpen] = useState(false);
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  const [showLabels, setShowLabels] = useState(true);
  const barRef = useRef<HTMLDivElement | null>(null);
  const moreButtonRef = useRef<HTMLButtonElement | null>(null);

  const split = splitIsland(islandSlots(roots, groups));
  const moreActive = isIslandMoreActive(split, currentLabel);
  const slotLabels = [...split.shown.map((slot) => islandShortLabel(slot.label)), ...(split.hasMore ? [ISLAND_MORE_LABEL] : [])];
  const labelKey = slotLabels.join("|");

  useEffect(() => {
    const root = document.documentElement;
    root.classList.add("has-bottom-nav");
    return () => root.classList.remove("has-bottom-nav");
  }, []);

  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const read = () => setKeyboardOpen(islandKeyboardOpen(window.innerHeight, viewport.height));
    read();
    viewport.addEventListener("resize", read);
    return () => viewport.removeEventListener("resize", read);
  }, []);

  useEffect(() => {
    setOpenGroup(null);
    setMoreOpen(false);
  }, [pathname]);

  const visible = !hidden && !keyboardOpen;
  useEffect(() => {
    if (!visible) setOpenGroup(null);
  }, [visible]);

  useLayoutEffect(() => {
    const bar = barRef.current;
    if (!bar) return;
    const labels = labelKey.split("|").filter(Boolean);
    const measure = () => {
      const canvas = document.createElement("canvas");
      const context = canvas.getContext("2d");
      if (!context) return;
      const family = getComputedStyle(bar).fontFamily || "sans-serif";
      const room = islandLabelRoom(bar.getBoundingClientRect().width, Math.max(labels.length, 1));
      const size = islandLabelSize(room, (px) => {
        context.font = `500 ${px}px ${family}`;
        return labels.reduce((widest, label) => Math.max(widest, context.measureText(label).width), 0);
      });
      setShowLabels(size !== null);
    };
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(bar);
    return () => observer.disconnect();
  }, [labelKey, visible]);

  useEffect(() => {
    if (!openGroup) return;
    const onPointer = (event: PointerEvent) => {
      const target = event.target as Node | null;
      if (target && barRef.current?.contains(target)) return;
      setOpenGroup(null);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpenGroup(null);
    };
    window.addEventListener("pointerdown", onPointer);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("pointerdown", onPointer);
      window.removeEventListener("keydown", onKey);
    };
  }, [openGroup]);

  const moreBadge = islandMoreBadge(split);

  return (
    <>
      <div
        className="bottom-island-frame pointer-events-none fixed inset-x-0 z-50 flex justify-center lg:hidden"
        style={{ bottom: "calc(0.75rem + env(safe-area-inset-bottom, 0px))" }}
      >
        <AnimatePresence initial={false}>
          {visible ? (
            <motion.nav
              key="bottom-island"
              aria-label="Main"
              data-testid="bottom-island-nav"
              initial={reduce ? { opacity: 1, y: 0 } : { opacity: 0, y: "50%" }}
              animate={{ opacity: 1, y: 0 }}
              exit={reduce ? { opacity: 0, y: 0 } : { opacity: 0, y: "50%" }}
              transition={reduce ? { duration: 0 } : { duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
              className="pointer-events-auto w-full max-w-[560px] rounded-[28px] border border-line-200 bg-card shadow-island"
            >
              <div ref={barRef} className="flex items-center p-[6px]">
                {split.shown.map((slot, index) => (
                  <div key={slot.label} className="relative min-w-0 flex-1">
                    <IslandSlotControl
                      slot={slot}
                      active={isIslandSlotActive(slot, currentLabel)}
                      showLabel={showLabels}
                      open={openGroup === slot.label}
                      alignEnd={index >= split.shown.length / 2}
                      currentLabel={currentLabel}
                      onToggle={() => setOpenGroup((value) => (value === slot.label ? null : slot.label))}
                      onNavigate={() => setOpenGroup(null)}
                    />
                  </div>
                ))}
                {split.hasMore ? (
                  <div className="relative min-w-0 flex-1">
                    <button
                      ref={moreButtonRef}
                      type="button"
                      onClick={() => {
                        setOpenGroup(null);
                        setMoreOpen(true);
                      }}
                      aria-haspopup="dialog"
                      aria-expanded={moreOpen}
                      aria-label={islandSlotName(ISLAND_MORE_LABEL, moreBadge)}
                      className={cn(SLOT_CLASS, slotTone(moreActive))}
                    >
                      <SlotFace label={ISLAND_MORE_LABEL} icon={Ellipsis} badge={moreBadge} showLabel={showLabels} />
                    </button>
                  </div>
                ) : null}
                <span aria-hidden className="mx-1 h-6 w-px shrink-0 bg-line-200" />
                <button
                  type="button"
                  onClick={(event) => {
                    setOpenGroup(null);
                    onOpenMenu(event.currentTarget);
                  }}
                  aria-label="Open menu"
                  aria-expanded={menuOpen}
                  className="inline-flex h-12 w-12 shrink-0 items-center justify-center rounded-full text-purple-700 transition hover:bg-purple-50"
                >
                  <MenuIcon className="h-5 w-5" aria-hidden />
                </button>
              </div>
            </motion.nav>
          ) : null}
        </AnimatePresence>
      </div>
      <IslandMoreSheet
        open={moreOpen && split.hasMore}
        slots={split.overflow}
        currentLabel={currentLabel}
        groupIcon={islandGroupIcon}
        returnFocusTo={moreButtonRef}
        onClose={() => setMoreOpen(false)}
      />
    </>
  );
}

function IslandSlotControl({
  slot,
  active,
  showLabel,
  open,
  alignEnd,
  currentLabel,
  onToggle,
  onNavigate
}: {
  slot: IslandSlot<LucideIcon>;
  active: boolean;
  showLabel: boolean;
  open: boolean;
  alignEnd: boolean;
  currentLabel: string | null;
  onToggle: () => void;
  onNavigate: () => void;
}) {
  const badge = islandSlotBadge(slot);
  if (slot.kind === "root") {
    return (
      <Link
        href={slot.entry.href}
        aria-current={active ? "page" : undefined}
        aria-label={islandSlotName(slot.label, badge)}
        className={cn(SLOT_CLASS, slotTone(active))}
      >
        <SlotFace label={slot.label} icon={slot.entry.icon} badge={badge} showLabel={showLabel} />
      </Link>
    );
  }
  const menuId = `island-menu-${slot.label.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
  return (
    <>
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={islandSlotName(slot.label, badge)}
        className={cn(SLOT_CLASS, slotTone(active || open))}
      >
        <SlotFace label={slot.label} icon={islandGroupIcon(slot.label)} badge={badge} showLabel={showLabel} />
      </button>
      {open ? (
        <div
          id={menuId}
          className={cn(
            "absolute bottom-full mb-3 max-h-[calc(100dvh-11rem)] w-max min-w-[13rem] max-w-[min(20rem,calc(100vw-1.5rem))] overflow-y-auto overscroll-contain rounded-xl border border-line-200 bg-card p-1.5 shadow-lg",
            alignEnd ? "right-0" : "left-0"
          )}
        >
          <ul className="grid gap-0.5">
            {slot.group.entries.map((entry) => {
              const here = entry.label === currentLabel;
              const text = islandBadgeText(entry.badge ?? 0);
              return (
                <li key={entry.href}>
                  <Link
                    href={entry.href}
                    onClick={onNavigate}
                    aria-current={here ? "page" : undefined}
                    aria-label={islandSlotName(entry.label, entry.badge ?? 0)}
                    className={cn(
                      "flex min-h-12 items-center gap-3 rounded-lg px-3 text-sm font-medium transition",
                      here ? "bg-purple-50 text-purple-700" : "text-ink-700 hover:bg-surface-50 hover:text-ink-900"
                    )}
                  >
                    <entry.icon className="h-4 w-4 shrink-0" aria-hidden />
                    <span className="truncate">{entry.label}</span>
                    {text ? (
                      <span aria-hidden className="ml-auto rounded-full bg-purple-700 px-1.5 text-[10px] font-semibold leading-[1.125rem] text-white">
                        {text}
                      </span>
                    ) : null}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}
    </>
  );
}
