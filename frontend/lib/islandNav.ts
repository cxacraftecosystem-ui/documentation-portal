/**
 * The bottom island's pure rules on the web — a port of Android's `ui/IslandBottomNav.kt` and of the
 * quick-return rule in `ui/AppSearch.kt`.
 *
 * Below 1024 CSS px both web apps draw the handset's navigation: a search header at the top and a
 * bar of at most five slots at the bottom. Which slots show, what goes behind "More", when "More" is
 * lit, the short words the bar prints and the room a word has are decided here, by the same rules
 * and the same numbers as the Kotlin, so a reader who moves between the phone app and the phone
 * browser meets one bar. `e2e/island-nav-unit.spec.ts` mirrors `IslandBottomNavTest.kt` case for case.
 *
 * Pure: no React, no DOM. Shared byte-identical by the designer portal and the documentation portal.
 */

/** How many slots the bar shows before the overflow goes behind "More". */
export const ISLAND_MAX_VISIBLE = 5;

/** The overflow slot's label — also its accessible name. */
export const ISLAND_MORE_LABEL = "More";

/**
 * The words the bar prints. The long names print short; the full name stays the slot's accessible
 * name. A label not in this map prints as it is. Menus and the "More" sheet keep the full names.
 */
export const ISLAND_SHORT_LABELS: Readonly<Record<string, string>> = {
  Dashboard: "Home",
  Walkthrough: "Tour",
  Record: "Record",
  Browse: "Browse",
  Admin: "Admin",
  Account: "Account"
};

/** The word the bar prints for `label`. */
export function islandShortLabel(label: string): string {
  return Object.prototype.hasOwnProperty.call(ISLAND_SHORT_LABELS, label) ? ISLAND_SHORT_LABELS[label] : label;
}

/** The bar's label size in px, and the smallest it shrinks to before it draws glyphs. Android's 11sp. */
export const ISLAND_LABEL_MAX_PX = 11;
export const ISLAND_LABEL_MIN_PX = 11;
const ISLAND_LABEL_STEP_PX = 0.5;

/** Geometry, in CSS px — Android's dp, one for one. */
export const ISLAND_TOUCH_TARGET = 48;
export const ISLAND_BAR_PADDING = 6;
export const ISLAND_SLOT_PADDING = 2;
export const ISLAND_DIVIDER_GAP = 4;
export const ISLAND_MAX_WIDTH = 560;

/**
 * The largest label size, from `maxPx` down to `minPx` in half-pixel steps, at which the widest label
 * fits `available` — or null, and the bar draws glyphs only. Every slot uses the one size.
 */
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

/**
 * The room, in px, a slot's word has on a bar `barWidth` px wide carrying `slotCount` slots: the bar
 * less its padding, the menu button and its divider, split evenly, less each slot's own padding.
 */
export function islandLabelRoom(barWidth: number, slotCount: number): number {
  const fixed = ISLAND_BAR_PADDING * 2 + ISLAND_TOUCH_TARGET + ISLAND_DIVIDER_GAP * 2 + 1;
  return (Math.min(barWidth, ISLAND_MAX_WIDTH) - fixed) / Math.max(slotCount, 1) - ISLAND_SLOT_PADDING * 2;
}

/** One destination in the island. `badge` is an unread count drawn on it (0 or absent: none). */
export type IslandEntry<I = unknown> = { label: string; href: string; icon: I; badge?: number };

/** One dropdown slot and the destinations behind it — Record, Browse, Admin, Account. */
export type IslandGroup<I = unknown> = { label: string; entries: IslandEntry<I>[] };

/** One top-level slot: a loose destination, or a group that opens a menu. */
export type IslandSlot<I = unknown> =
  | { kind: "root"; label: string; entry: IslandEntry<I> }
  | { kind: "group"; label: string; group: IslandGroup<I> };

/**
 * The slots in render order: the loose entries first, then every group that has anything in it.
 * An empty group is dropped HERE, before the count, so it can never push a real slot behind "More".
 */
export function islandSlots<I>(roots: IslandEntry<I>[], groups: IslandGroup<I>[]): IslandSlot<I>[] {
  return [
    ...roots.map((entry) => ({ kind: "root" as const, label: entry.label, entry })),
    ...groups
      .filter((group) => group.entries.length > 0)
      .map((group) => ({ kind: "group" as const, label: group.label, group }))
  ];
}

/** What the bar draws directly (`shown`) and what lives behind "More" (`overflow`). */
export type IslandSplit<T> = { shown: T[]; overflow: T[]; hasMore: boolean };

/**
 * The five-slot rule. Up to `maxVisible` items are all shown. Past that, the first `maxVisible - 1`
 * are shown in their order and the rest go behind "More", which takes the last slot.
 */
export function splitIsland<T>(items: T[], maxVisible: number = ISLAND_MAX_VISIBLE): IslandSplit<T> {
  if (maxVisible < 2) throw new RangeError("the island needs room for at least one destination and More");
  if (items.length <= maxVisible) return { shown: [...items], overflow: [], hasMore: false };
  const shown = items.slice(0, maxVisible - 1);
  const overflow = items.slice(maxVisible - 1);
  return { shown, overflow, hasMore: overflow.length > 0 };
}

/** Whether `slot` is where the reader is: its own label, or a group holding that label. */
export function isIslandSlotActive<I>(slot: IslandSlot<I>, currentLabel: string | null | undefined): boolean {
  if (currentLabel == null) return false;
  if (slot.kind === "root") return slot.entry.label === currentLabel;
  return slot.group.entries.some((entry) => entry.label === currentLabel);
}

/** "More" is lit exactly when the screen the reader is on lives behind it. */
export function isIslandMoreActive<I>(split: IslandSplit<IslandSlot<I>>, currentLabel: string | null | undefined): boolean {
  return split.overflow.some((slot) => isIslandSlotActive(slot, currentLabel));
}

/** The unread total a slot carries: its own entry's, or the sum of its group's. */
export function islandSlotBadge<I>(slot: IslandSlot<I>): number {
  const entries = slot.kind === "root" ? [slot.entry] : slot.group.entries;
  return entries.reduce((sum, entry) => sum + Math.max(0, entry.badge ?? 0), 0);
}

/** The unread total behind "More". */
export function islandMoreBadge<I>(split: IslandSplit<IslandSlot<I>>): number {
  return split.overflow.reduce((sum, slot) => sum + islandSlotBadge(slot), 0);
}

/** The pill's text for a count: nothing at 0, "99+" past 99. */
export function islandBadgeText(count: number): string | null {
  if (!Number.isFinite(count) || count <= 0) return null;
  return count > 99 ? "99+" : String(Math.floor(count));
}

/** A slot's accessible name: its full label, and the count in words when there is one. */
export function islandSlotName(label: string, badge: number): string {
  return badge > 0 ? `${label}, ${badge} unread` : label;
}

/**
 * The on-screen keyboard is open when the visual viewport is more than 150px shorter than the
 * layout viewport. The bar hides then: a form being typed into needs that strip more than the menu.
 */
export const ISLAND_KEYBOARD_DROP = 150;

export function islandKeyboardOpen(layoutHeight: number, visualHeight: number): boolean {
  if (!Number.isFinite(layoutHeight) || !Number.isFinite(visualHeight)) return false;
  return layoutHeight - visualHeight > ISLAND_KEYBOARD_DROP;
}

// ---------------------------------------------------------------------------------------------
// Quick return — the search header hides on the way down and comes back on any move up.
// Android's `quickReturnStep`, with scroll positions in place of nested-scroll deltas.
// ---------------------------------------------------------------------------------------------

/** Downward travel, in px, before the header hides. */
export const QUICK_RETURN_HIDE_AFTER = 24;
/** The slide's length. Reduced motion gets zero. */
export const QUICK_RETURN_SLIDE_MS = 180;

export type QuickReturnStep = { shown: boolean; travel: number };

/**
 * One scroll event. `delta` is the change in `scrollY` (positive = the page moved down, toward its
 * end). Pinned or at the top → shown. Any upward scroll → shown at once. A downward scroll adds to
 * the travel and hides the header once the travel passes `hideAfter` and the page has moved past
 * the header's own height. No movement changes nothing.
 */
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
