import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * The responsive navigation, pinned as source (REQUIREMENTS_2026-10-20 §4). PURE NODE.
 *
 * One breakpoint decides the mode — `lg`, 1024 CSS px — and it is decided by CSS alone: the desktop
 * island carries `max-lg:hidden`, the search header and the bottom island carry `lg:hidden`, and no
 * navigation component reads the window's width. The live twin, `nav-responsive.spec.ts`, drives the
 * same rules in a browser at 390, 820 and 1440 px.
 *
 * It also pins the desktop island's ORIGINAL expanded size — the pill, the logo, the name and the
 * strip — so the island cannot be shrunk again without this file being changed on purpose.
 */

const ROOT = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(ROOT, ...parts), "utf8");

const NAV = read("components", "DynamicIslandNav.tsx");
const BAR = read("components", "nav", "BottomIslandNav.tsx");
const SHEET = read("components", "nav", "IslandMoreSheet.tsx");
const HEADER = read("components", "nav", "AppSearchHeader.tsx");
const PANEL = read("components", "nav", "AppSearchPanel.tsx");
const SCAN = read("components", "scan", "TopBarScanButton.tsx");
const SHELL = read("components", "AppShell.tsx");
const CSS = read("app", "globals.css");

test("the desktop island keeps its original expanded size", () => {
  expect(NAV).toContain('compact ? "px-3 py-1.5" : "px-4 py-2"');
  expect(NAV).toContain('compact ? "h-7 w-7" : "h-8 w-8"');
  expect(NAV).toContain('compact ? "text-sm" : "text-base"');
  expect(NAV).toContain('className="hidden items-center gap-5 overflow-visible whitespace-nowrap pl-2 pr-1 lg:flex"');
});

test("the island is drawn from lg up only, and the phone chrome below lg only", () => {
  expect(NAV).toContain('className="nav-island-frame pointer-events-none fixed inset-x-0 top-3 z-50 flex justify-center max-lg:hidden"');
  expect(BAR).toContain('"bottom-island-frame pointer-events-none fixed inset-x-0 z-50 flex justify-center lg:hidden"');
  expect(HEADER).toMatch(/"app-search-header-frame [^"]*lg:hidden"/);
  expect(SHEET).toContain('"fixed inset-0 z-90 lg:hidden"');
  // The one width the island reads is the scrollbar's, for the sheet's scroll lock — not a mode.
  const navWithoutGutter = NAV.replace("const gutter = window.innerWidth - root.clientWidth;", "");
  for (const [name, source] of Object.entries({ NAV: navWithoutGutter, BAR, SHEET, HEADER, PANEL })) {
    expect(source, `${name} must not read the window width`).not.toMatch(/innerWidth|matchMedia/);
  }
});

test("the admin-view control is back to its pill, still a switch, with the bell after it", () => {
  const pill = NAV.indexOf('data-testid="island-admin-view"');
  const bell = NAV.indexOf("<NotificationBell active={isActivePath(\"/notifications\")} />");
  const menu = NAV.indexOf('aria-label="Toggle navigation menu"');
  expect(pill).toBeGreaterThan(0);
  expect(NAV.slice(pill, bell)).toContain("inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1.5 text-xs font-medium");
  expect(NAV.slice(pill, bell)).toContain("<EyeOff");
  expect(NAV.slice(pill, bell)).toContain('role="switch"');
  expect(NAV.slice(pill, bell)).toContain("aria-checked={adminMode}");
  expect(NAV.slice(pill, bell)).toContain("Admin view");
  expect(bell).toBeGreaterThan(pill);
  expect(menu).toBeGreaterThan(bell);
  // Offered to every account the provider says has the control (perms adds `available`).
  expect(NAV).toContain("(adminView as { available?: boolean }).available ?? canAdmin");
});

test("the bottom island is Android's: five slots at most, More, a divider and the menu button", () => {
  expect(BAR).toContain('aria-label="Main"');
  expect(BAR).toContain("max-w-[560px] rounded-[28px] border border-line-200 bg-card shadow-island");
  expect(BAR).toContain("splitIsland(islandSlots(roots, groups))");
  expect(BAR).toContain('aria-label="Open menu"');
  expect(BAR).toContain('aria-current={active ? "page" : undefined}');
  expect(BAR).toContain("min-h-12");
  expect(BAR).toContain('style={{ bottom: "calc(0.75rem + env(safe-area-inset-bottom, 0px))" }}');
  // It hides for the keyboard and for open search, never on scroll.
  expect(BAR).toContain("const visible = !hidden && !keyboardOpen;");
  expect(BAR).not.toMatch(/addEventListener\("scroll"/);
});

test("the More sheet is a modal bottom sheet titled More", () => {
  expect(SHEET).toContain('role="dialog"');
  expect(SHEET).toContain('aria-modal="true"');
  expect(SHEET).toContain("aria-label={ISLAND_MORE_LABEL}");
  expect(SHEET).toContain("bg-black/[0.32]");
  expect(SHEET).toContain("max-h-[80dvh] w-full max-w-[560px]");
  expect(SHEET).toContain("rounded-t-[28px]");
  expect(SHEET).toContain('event.key === "Escape"');
});

test("the search header carries the scan button, the name and the search glyph", () => {
  expect(HEADER).toContain("<TopBarScanButton />");
  expect(HEADER).toContain("aria-label={`${appName}. Search`}");
  expect(HEADER).toContain("min-h-[60px]");
  expect(HEADER).toContain("inert={!shown}");
  expect(HEADER).toContain("quickReturnStep(");
  expect(NAV).toContain('const APP_NAME = "Field Repository";');
});

test("Browse carries Scan a code, as in the designer portal's menu", () => {
  expect(NAV).toContain('{ href: "/scan", label: "Scan a code", icon: QrCode, group: "Browse", can: everyone, gate: "get_current_user" }');
  expect(NAV.indexOf('href: "/scan"')).toBeGreaterThan(NAV.indexOf('href: "/search"'));
});

test("the menu sheet ends above the bottom island", () => {
  expect(CSS).toContain("html.has-bottom-nav .nav-sheet {");
  expect(CSS).toContain("max-height: calc(100dvh - var(--nav-sheet-top) - var(--bottom-nav-h) - 0.5rem);");
});

test("the scan button keeps the frozen signature", () => {
  expect(SCAN).toContain("export function TopBarScanButton({ className }: { className?: string }): JSX.Element");
  expect(SCAN).toContain('aria-label="Scan a code"');
});

test("the search panel follows Android's AppSearchPanel", () => {
  expect(PANEL).toContain("APP_SEARCH_DEBOUNCE_MS = 300");
  expect(PANEL).toContain("UNIFIED_MIN_CHARS");
  expect(PANEL).toContain("pushRecentSearch(");
  expect(PANEL).toContain("Offline — showing what's on this device.");
  expect(PANEL).toContain("router.push(`/search?q=${encodeURIComponent(q)}`)");
});

test("the page clears the header and the bar below lg, and the island from lg up", () => {
  expect(SHELL).toContain(
    'className="relative z-10 mx-auto max-w-7xl px-4 pb-[calc(4.5rem+env(safe-area-inset-bottom))] pt-[calc(60px+1.25rem)] lg:pb-12 lg:pt-24"'
  );
});

test("the upload tray and the toasts sit above the bar", () => {
  expect(CSS).toMatch(/@media \(width < 64rem\) \{\s*html\.has-bottom-nav \{\s*--bottom-nav-h: calc\(60px \+ 0\.75rem \+ env\(safe-area-inset-bottom, 0px\)\);/);
  expect(read("components", "media", "UploadTray.tsx")).toContain('bottom: "var(--bottom-nav-h, 0px)"');
  expect(read("components", "ui", "Toast.tsx")).toContain('bottom: "calc(var(--bottom-nav-h, 0px) + 1rem)"');
  expect(BAR).toContain('root.classList.add("has-bottom-nav")');
});

test("the notification count reaches the bar from the bell's own store", () => {
  expect(NAV).toContain("useUnreadNotificationCount(Boolean(user))");
  expect(NAV).toContain("badge: item.href === NOTIFICATIONS_HREF ? unreadNotifications : 0");
});
