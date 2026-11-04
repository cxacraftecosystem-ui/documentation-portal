export const ISLAND_MAX_VISIBLE = 5;

export const ISLAND_MORE_LABEL = "More";

export const ISLAND_SHORT_LABELS: Readonly<Record<string, string>> = {
  Dashboard: "Home",
  Walkthrough: "Tour",
  Record: "Record",
  Browse: "Browse",
  Admin: "Admin",
  Account: "Account"
};

export function islandShortLabel(label: string): string {
  return Object.prototype.hasOwnProperty.call(ISLAND_SHORT_LABELS, label) ? ISLAND_SHORT_LABELS[label] : label;
}

export const ISLAND_LABEL_MAX_PX = 11;
export const ISLAND_LABEL_MIN_PX = 11;
const ISLAND_LABEL_STEP_PX = 0.5;

export const ISLAND_TOUCH_TARGET = 48;
export const ISLAND_BAR_PADDING = 6;
export const ISLAND_SLOT_PADDING = 2;
export const ISLAND_DIVIDER_GAP = 4;
export const ISLAND_MAX_WIDTH = 560;

export function islandLabelSize(
  available: number,
  widestAt: (size: number) => number,
  maxPx: number = ISLAND_LABEL_MAX_PX,
  minPx: number = ISLAND_LABEL_MIN_PX
): number | null {
  let size = maxPx;
  while (size >= minPx - 0.001) {
    if (widestAt(size) <= available) return size;
    size -= ISLAND_LABEL_STEP_PX;
  }
  return null;
}

export function islandLabelRoom(barWidth: number, slotCount: number): number {
  const fixed = ISLAND_BAR_PADDING * 2 + ISLAND_TOUCH_TARGET + ISLAND_DIVIDER_GAP * 2 + 1;
  return (Math.min(barWidth, ISLAND_MAX_WIDTH) - fixed) / Math.max(slotCount, 1) - ISLAND_SLOT_PADDING * 2;
}

export type IslandEntry<I = unknown> = { label: string; href: string; icon: I; badge?: number };

export type IslandGroup<I = unknown> = { label: string; entries: IslandEntry<I>[] };

export type IslandSlot<I = unknown> =
  | { kind: "root"; label: string; entry: IslandEntry<I> }
  | { kind: "group"; label: string; group: IslandGroup<I> };

export function islandSlots<I>(roots: IslandEntry<I>[], groups: IslandGroup<I>[]): IslandSlot<I>[] {
  return [
    ...roots.map((entry) => ({ kind: "root" as const, label: entry.label, entry })),
    ...groups
      .filter((group) => group.entries.length > 0)
      .map((group) => ({ kind: "group" as const, label: group.label, group }))
  ];
}

export type IslandSplit<T> = { shown: T[]; overflow: T[]; hasMore: boolean };

export function splitIsland<T>(items: T[], maxVisible: number = ISLAND_MAX_VISIBLE): IslandSplit<T> {
  if (maxVisible < 2) throw new RangeError("the island needs room for at least one destination and More");
  if (items.length <= maxVisible) return { shown: [...items], overflow: [], hasMore: false };
  const shown = items.slice(0, maxVisible - 1);
  const overflow = items.slice(maxVisible - 1);
  return { shown, overflow, hasMore: overflow.length > 0 };
}

export function isIslandSlotActive<I>(slot: IslandSlot<I>, currentLabel: string | null | undefined): boolean {
  if (currentLabel == null) return false;
  if (slot.kind === "root") return slot.entry.label === currentLabel;
  return slot.group.entries.some((entry) => entry.label === currentLabel);
}

export function isIslandMoreActive<I>(split: IslandSplit<IslandSlot<I>>, currentLabel: string | null | undefined): boolean {
  return split.overflow.some((slot) => isIslandSlotActive(slot, currentLabel));
}

export function islandSlotBadge<I>(slot: IslandSlot<I>): number {
  const entries = slot.kind === "root" ? [slot.entry] : slot.group.entries;
  return entries.reduce((sum, entry) => sum + Math.max(0, entry.badge ?? 0), 0);
}

export function islandMoreBadge<I>(split: IslandSplit<IslandSlot<I>>): number {
  return split.overflow.reduce((sum, slot) => sum + islandSlotBadge(slot), 0);
}

export function islandBadgeText(count: number): string | null {
  if (!Number.isFinite(count) || count <= 0) return null;
  return count > 99 ? "99+" : String(Math.floor(count));
}

export function islandSlotName(label: string, badge: number): string {
  return badge > 0 ? `${label}, ${badge} unread` : label;
}

export const ISLAND_KEYBOARD_DROP = 150;

export function islandKeyboardOpen(layoutHeight: number, visualHeight: number): boolean {
  if (!Number.isFinite(layoutHeight) || !Number.isFinite(visualHeight)) return false;
  return layoutHeight - visualHeight > ISLAND_KEYBOARD_DROP;
}

export const QUICK_RETURN_HIDE_AFTER = 24;
export const QUICK_RETURN_SLIDE_MS = 180;

export type QuickReturnStep = { shown: boolean; travel: number };

export function quickReturnStep(
  state: QuickReturnStep,
  delta: number,
  scrollY: number,
  headerHeight: number,
  pinned: boolean,
  hideAfter: number = QUICK_RETURN_HIDE_AFTER
): QuickReturnStep {
  if (pinned || scrollY <= 0) return { shown: true, travel: 0 };
  if (delta < 0) return { shown: true, travel: 0 };
  if (delta > 0) {
    const travel = state.travel + delta;
    const hide = travel >= hideAfter && scrollY > headerHeight;
    return { shown: state.shown && !hide, travel: hide ? 0 : travel };
  }
  return state;
}
