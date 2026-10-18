import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  appendPage,
  badgeText,
  bellLabel,
  markedRead,
  markOnePath,
  MARK_READ_PATH,
  notificationsPath,
  NOTIFICATIONS_HREF,
  safeLink,
  UNREAD_COUNT_PATH,
  whenWords,
  type AppNotification
} from "@/lib/inAppNotifications";

/**
 * THE IN-APP ACTIVITY LIST (C25) — the request paths both portals and both handsets share, the
 * bell's words, and the list's bookkeeping. The paths are pinned here because the designer
 * portal and the two Android clients are written against the same four routes.
 */

function notice(id: string, read = false, minutesAgo = 0): AppNotification {
  return {
    id,
    kind: "REVIEW_SENT_BACK",
    title: `Notice ${id}`,
    body: null,
    link: "/tasks",
    read,
    readAt: read ? "2026-10-17T00:00:00Z" : null,
    createdAt: new Date(Date.UTC(2026, 9, 17, 12) - minutesAgo * 60_000).toISOString()
  };
}

test("the four routes are the shared contract", () => {
  expect(notificationsPath()).toBe("/notifications?limit=30");
  expect(notificationsPath({ before: "n9", unreadOnly: true, limit: 10 })).toBe(
    "/notifications?limit=10&before=n9&unreadOnly=true"
  );
  expect(UNREAD_COUNT_PATH).toBe("/notifications/unread-count");
  expect(markOnePath("n 1")).toBe("/notifications/n%201/read");
  expect(MARK_READ_PATH).toBe("/notifications/read");
  expect(NOTIFICATIONS_HREF).toBe("/notifications");
});

test("the bell says its count in words and caps the pill", () => {
  expect(bellLabel(0)).toBe("Notifications");
  expect(bellLabel(1)).toBe("Notifications, 1 unread");
  expect(bellLabel(7)).toBe("Notifications, 7 unread");
  expect(badgeText(0)).toBeNull();
  expect(badgeText(-1)).toBeNull();
  expect(badgeText(42)).toBe("42");
  expect(badgeText(120)).toBe("99+");
});

test("only an app path is followed", () => {
  expect(safeLink("/tasks")).toBe("/tasks");
  expect(safeLink("https://elsewhere.example")).toBeNull();
  expect(safeLink("//elsewhere.example")).toBeNull();
  expect(safeLink(null)).toBeNull();
});

test("times read as a person would say them", () => {
  const now = new Date(Date.UTC(2026, 9, 17, 12));
  expect(whenWords(notice("a", false, 0).createdAt, now)).toBe("Just now");
  expect(whenWords(notice("a", false, 5).createdAt, now)).toBe("5 min ago");
  expect(whenWords(notice("a", false, 180).createdAt, now)).toBe("3 h ago");
  expect(whenWords(notice("a", false, 30 * 60).createdAt, now)).toBe("Yesterday");
  expect(whenWords(notice("a", false, 5 * 24 * 60).createdAt, now)).toMatch(/2026/);
});

test("marking read touches only the unread ones named, and paging never repeats a notice", () => {
  const list = [notice("a"), notice("b", true), notice("c")];
  const one = markedRead(list, new Set(["a"]), "2026-10-17T12:00:00Z");
  expect(one.map((n) => n.read)).toEqual([true, true, false]);
  expect(one[1].readAt).toBe("2026-10-17T00:00:00Z");
  expect(markedRead(list, "all", "x").every((n) => n.read)).toBe(true);
  expect(appendPage(list, [notice("c"), notice("d")]).map((n) => n.id)).toEqual(["a", "b", "c", "d"]);
});

test("the bell is in the island and the page is in the menu", () => {
  const nav = readFileSync(join(__dirname, "..", "components", "DynamicIslandNav.tsx"), "utf8");
  expect(nav).toContain('<NotificationBell active={isActivePath("/notifications")} />');
  expect(nav).toContain('{ href: "/notifications", label: "Notifications", icon: Bell, group: "Account", can: everyone');
  const page = readFileSync(join(__dirname, "..", "app", "(protected)", "notifications", "page.tsx"), "utf8");
  expect(page).toContain("setUnreadNotificationCount");
  expect(page).toContain("Mark all as read");
});
