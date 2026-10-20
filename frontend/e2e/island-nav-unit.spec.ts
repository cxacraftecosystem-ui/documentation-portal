import { expect, test } from "@playwright/test";

import { NAV_GROUPS, NAV_ITEMS, isNavItemVisible } from "@/components/DynamicIslandNav";
import {
  ISLAND_LABEL_MAX_PX,
  ISLAND_LABEL_MIN_PX,
  ISLAND_MAX_VISIBLE,
  ISLAND_MORE_LABEL,
  ISLAND_SHORT_LABELS,
  QUICK_RETURN_HIDE_AFTER,
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
  quickReturnStep,
  splitIsland,
  type IslandEntry,
  type IslandGroup,
  type IslandSlot,
  type QuickReturnStep
} from "@/lib/islandNav";
import { ROLE_RANK } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * The bottom island's pure rules on the web — `lib/islandNav.ts` — mirrored case for case from
 * Android's `IslandBottomNavTest.kt` (and the quick-return cases of `AppSearchTest.kt`), so the phone
 * app and the phone browser draw one bar. PURE NODE: no page, no sign-in, no API.
 */

// ── splitIsland, on plain values ───────────────────────────────────────────────────────────────

test("five or fewer are all shown and there is no More", () => {
  for (let n = 0; n <= 5; n += 1) {
    const items = Array.from({ length: n }, (_, i) => `d${i + 1}`);
    const split = splitIsland(items);
    expect(split.shown).toEqual(items);
    expect(split.overflow).toEqual([]);
    expect(split.hasMore, `no More with ${n} items`).toBe(false);
  }
});

test("six shows the first four in order and puts two behind More", () => {
  const split = splitIsland(["a", "b", "c", "d", "e", "f"]);
  expect(split.shown).toEqual(["a", "b", "c", "d"]);
  expect(split.overflow).toEqual(["e", "f"]);
  expect(split.hasMore).toBe(true);
});

test("the bar never draws more than five slots, More included", () => {
  for (let n = 6; n <= 30; n += 1) {
    const items = Array.from({ length: n }, (_, i) => i + 1);
    const split = splitIsland(items);
    expect(split.shown.length, `four shown for ${n}`).toBe(4);
    expect(split.overflow.length, `the rest behind More for ${n}`).toBe(n - 4);
    expect([...split.shown, ...split.overflow]).toEqual(items);
  }
});

test("a cap below two is refused", () => {
  expect(() => splitIsland([1, 2, 3], 1)).toThrow();
});

// ── islandSlots and the active rules ───────────────────────────────────────────────────────────

const entry = (label: string, badge = 0): IslandEntry<string> => ({ label, href: `/${label}`, icon: "star", badge });
const roots = [entry("Dashboard"), entry("Walkthrough")];
const groups: IslandGroup<string>[] = [
  { label: "Record", entries: [entry("Record artisan")] },
  { label: "Browse", entries: [entry("Map"), entry("Tasks")] },
  { label: "Admin", entries: [entry("Settings hub")] },
  { label: "Account", entries: [entry("Settings"), entry("Notifications")] }
];

test("slots are the roots then the non-empty groups, in order", () => {
  const slots = islandSlots(roots, [...groups, { label: "Empty", entries: [] }]);
  expect(slots.map((slot) => slot.label)).toEqual(["Dashboard", "Walkthrough", "Record", "Browse", "Admin", "Account"]);
});

test("an empty group never pushes a real one behind More", () => {
  const withoutAdmin = groups.map((group) => (group.label === "Admin" ? { label: "Admin", entries: [] } : group));
  const split = splitIsland(islandSlots(roots, withoutAdmin));
  expect(split.hasMore).toBe(false);
  expect(split.shown.map((slot) => slot.label)).toEqual(["Dashboard", "Walkthrough", "Record", "Browse", "Account"]);
});

test("More is lit only when the current destination is behind it", () => {
  const split = splitIsland(islandSlots(roots, groups));
  expect(split.overflow.map((slot) => slot.label)).toEqual(["Admin", "Account"]);
  expect(isIslandMoreActive(split, "Settings hub")).toBe(true);
  expect(isIslandMoreActive(split, "Settings")).toBe(true);
  expect(isIslandMoreActive(split, "Dashboard")).toBe(false);
  expect(isIslandMoreActive(split, "Map")).toBe(false);
  expect(isIslandMoreActive(split, null)).toBe(false);
  expect(isIslandMoreActive(split, "Nowhere")).toBe(false);
});

test("a slot is active for its own label or for an entry of its group", () => {
  const slots = islandSlots(roots, groups);
  const dashboard = slots.find((slot) => slot.label === "Dashboard")!;
  const browse = slots.find((slot) => slot.label === "Browse")!;
  expect(isIslandSlotActive(dashboard, "Dashboard")).toBe(true);
  expect(isIslandSlotActive(dashboard, "Map")).toBe(false);
  expect(isIslandSlotActive(browse, "Tasks")).toBe(true);
  // A group's own label is not a destination, so it never lights the group.
  expect(isIslandSlotActive(browse, "Browse")).toBe(false);
  expect(isIslandSlotActive(browse, null)).toBe(false);
});

test("More with nothing hidden is never lit", () => {
  const split = splitIsland(islandSlots(roots, groups.slice(0, 2)));
  expect(split.hasMore).toBe(false);
  expect(isIslandMoreActive(split, "Dashboard")).toBe(false);
});

// ── against the real menu model, role by role ──────────────────────────────────────────────────

const user = (role: UserRole): User => ({ id: `u-${role}`, email: `${role}@example.org`, name: role, role }) as User;

/** The slots the web builds for `role`: the same projection of `NAV_ITEMS` the bar renders. */
function slotsFor(role: UserRole, adminMode: boolean): IslandSlot<string>[] {
  const items = NAV_ITEMS.filter((item) => isNavItemVisible(item, user(role), adminMode));
  const rootEntries = items.filter((item) => item.group === null).map((item) => entry(item.label));
  const groupEntries = NAV_GROUPS.map((label) => ({
    label,
    entries: items.filter((item) => item.group === label).map((item) => entry(item.label))
  }));
  return islandSlots(rootEntries, groupEntries);
}

test("a volunteer has five slots and no More", () => {
  const split = splitIsland(slotsFor("CROWDSOURCE_VOLUNTEER", false));
  expect(split.hasMore).toBe(false);
  expect(split.shown.map((slot) => slot.label)).toEqual(["Dashboard", "Walkthrough", "Record", "Browse", "Account"]);
});

test("an admin with admin view on gets four slots and More holding Admin and Account", () => {
  const split = splitIsland(slotsFor("MASTER_ADMIN", true));
  expect(split.shown.map((slot) => slot.label)).toEqual(["Dashboard", "Walkthrough", "Record", "Browse"]);
  expect(split.overflow.map((slot) => slot.label)).toEqual(["Admin", "Account"]);
  expect(isIslandMoreActive(split, "Settings hub")).toBe(true);
  expect(isIslandMoreActive(split, "Dashboard")).toBe(false);
  // The bar prints Home, Tour, Record, Browse and More.
  expect([...split.shown.map((slot) => islandShortLabel(slot.label)), ISLAND_MORE_LABEL]).toEqual([
    "Home",
    "Tour",
    "Record",
    "Browse",
    "More"
  ]);
});

test("a researcher has five slots and no More", () => {
  const split = splitIsland(slotsFor("RESEARCHER", false));
  expect(split.hasMore).toBe(false);
  expect(split.shown.map((slot) => islandShortLabel(slot.label))).toEqual(["Home", "Tour", "Record", "Browse", "Account"]);
});

test("an admin with admin view off keeps only the Admin entries that are not admin chrome", () => {
  const split = splitIsland(slotsFor("ADMIN", false));
  const admin = [...split.shown, ...split.overflow].find((slot) => slot.label === "Admin");
  const labels = admin && admin.kind === "group" ? admin.group.entries.map((e) => e.label) : [];
  expect(labels).not.toContain("Settings hub");
  expect(labels).not.toContain("Manage users");
});

test("role filtering decides what is behind More, never the other way round", () => {
  for (const role of Object.keys(ROLE_RANK) as UserRole[]) {
    for (const adminMode of [true, false]) {
      const slots = slotsFor(role, adminMode);
      const split = splitIsland(slots);
      expect([...split.shown, ...split.overflow].map((slot) => slot.label), `${role}/${adminMode}`).toEqual(
        slots.map((slot) => slot.label)
      );
      expect(split.shown.length + (split.hasMore ? 1 : 0), `${role}/${adminMode} draws at most five`).toBeLessThanOrEqual(
        ISLAND_MAX_VISIBLE
      );
      const behindMore = split.overflow.flatMap((slot) =>
        slot.kind === "root" ? [slot.label] : slot.group.entries.map((e) => e.label)
      );
      const visible = new Set(NAV_ITEMS.filter((item) => isNavItemVisible(item, user(role), adminMode)).map((item) => item.label));
      for (const label of behindMore) expect(visible.has(label), `${role}/${adminMode}: ${label}`).toBe(true);
    }
  }
});

// ── the bar's words ────────────────────────────────────────────────────────────────────────────

test("the long names print short and every other label prints as it is", () => {
  expect(islandShortLabel("Dashboard")).toBe("Home");
  expect(islandShortLabel("Walkthrough")).toBe("Tour");
  expect(islandShortLabel("Record")).toBe("Record");
  expect(islandShortLabel("Browse")).toBe("Browse");
  expect(islandShortLabel("Admin")).toBe("Admin");
  expect(islandShortLabel("Account")).toBe("Account");
  expect(islandShortLabel(ISLAND_MORE_LABEL)).toBe("More");
  expect(islandShortLabel("Something new")).toBe("Something new");
  expect(islandShortLabel("constructor")).toBe("constructor");
});

test("every short word is no longer than the longest group name", () => {
  const longest = Math.max(...NAV_GROUPS.map((label) => label.length));
  for (const word of Object.values(ISLAND_SHORT_LABELS)) expect(word.length, word).toBeLessThanOrEqual(longest);
});

const widest = (perPx: number) => (px: number) => px * perPx;

test("labels stay at full size where they fit", () => {
  expect(islandLabelSize(60, widest(4))).toBe(ISLAND_LABEL_MAX_PX);
});

test("a caller's own floor still shrinks in half steps before the words are given up", () => {
  expect(islandLabelSize(43, widest(4), ISLAND_LABEL_MAX_PX, 10)).toBe(10.5);
  expect(islandLabelSize(40, widest(4), ISLAND_LABEL_MAX_PX, 10)).toBe(10);
});

test("below the smallest size the bar draws glyphs only", () => {
  expect(islandLabelSize(43.9, widest(4))).toBeNull();
});

test("the bar prints at 11px and never under it", () => {
  expect(ISLAND_LABEL_MAX_PX).toBe(11);
  expect(ISLAND_LABEL_MIN_PX).toBe(11);
});

const accountAt = (px: number) => 3.85 * px;

test("on 360px and 320px phones a full bar prints its words at full size", () => {
  for (const width of [360, 320]) {
    const room = islandLabelRoom(width, ISLAND_MAX_VISIBLE);
    expect(islandLabelSize(room, accountAt), `at ${width}px`).toBe(ISLAND_LABEL_MAX_PX);
  }
});

test("on a phone too narrow for the words they shrink or give way to glyphs, never overflow", () => {
  for (const width of [300, 280, 240]) {
    const room = islandLabelRoom(width, ISLAND_MAX_VISIBLE);
    const size = islandLabelSize(room, accountAt);
    if (size !== null) expect(accountAt(size), `at ${width}px`).toBeLessThanOrEqual(room);
  }
});

test("the room a slot has grows with the phone and is capped with the bar", () => {
  expect(islandLabelRoom(360, 6)).toBeGreaterThan(islandLabelRoom(320, 6));
  expect(islandLabelRoom(560, 6)).toBe(islandLabelRoom(900, 6));
});

// ── web-only: the unread badge and the keyboard ────────────────────────────────────────────────

test("the unread count rides on the slot that holds Notifications, and on More when it is behind it", () => {
  const withUnread = groups.map((group) =>
    group.label === "Account" ? { ...group, entries: [entry("Settings"), entry("Notifications", 3)] } : group
  );
  const split = splitIsland(islandSlots(roots, withUnread));
  const account = split.overflow.find((slot) => slot.label === "Account")!;
  expect(islandSlotBadge(account)).toBe(3);
  expect(islandMoreBadge(split)).toBe(3);
  expect(split.shown.every((slot) => islandSlotBadge(slot) === 0)).toBe(true);
  expect(islandSlotName("More", 3)).toBe("More, 3 unread");
  expect(islandSlotName("More", 0)).toBe("More");
});

test("the badge is never a bare zero and caps at 99+", () => {
  expect(islandBadgeText(0)).toBeNull();
  expect(islandBadgeText(-1)).toBeNull();
  expect(islandBadgeText(7)).toBe("7");
  expect(islandBadgeText(150)).toBe("99+");
});

test("the bar hides while the on-screen keyboard is open", () => {
  expect(islandKeyboardOpen(844, 844)).toBe(false);
  expect(islandKeyboardOpen(844, 700)).toBe(false);
  expect(islandKeyboardOpen(844, 500)).toBe(true);
});

// ── quick return (AppSearchTest's cases, in scroll positions) ──────────────────────────────────

const header = 72;
const step = (s: QuickReturnStep, delta: number, y: number, pinned = false) => quickReturnStep(s, delta, y, header, pinned);

test("scrolling down hides the header only after the travel threshold and past its height", () => {
  let s: QuickReturnStep = { shown: true, travel: 0 };
  s = step(s, 20, 400);
  expect(s.shown, "20px down is below the threshold").toBe(true);
  s = step(s, 40, 440);
  expect(s.shown, "60px down, page past the header: hidden").toBe(false);
});

test("it never hides while the page has not moved past the header's own height", () => {
  let s: QuickReturnStep = { shown: true, travel: 0 };
  s = step(s, 60, 60);
  expect(s.shown).toBe(true);
});

test("any upward scroll brings it back at once, even mid-page", () => {
  let s: QuickReturnStep = { shown: true, travel: 0 };
  s = step(s, 200, 200);
  expect(s.shown).toBe(false);
  s = step(s, -10, 190);
  expect(s.shown).toBe(true);
});

test("at the top, or pinned by open search, it is always shown", () => {
  expect(step({ shown: false, travel: 0 }, 0, 0).shown).toBe(true);
  expect(step({ shown: false, travel: 0 }, 300, 900, true).shown).toBe(true);
  expect(QUICK_RETURN_HIDE_AFTER).toBe(24);
});
