"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion, useMotionValueEvent, useScroll } from "framer-motion";
import {
  Activity,
  Boxes,
  Brush,
  ClipboardCheck,
  ClipboardList,
  Compass,
  Eye,
  EyeOff,
  FolderTree,
  Gauge,
  GitBranch,
  Image as ImageIcon,
  LogOut,
  Menu as MenuIcon,
  MessageSquare,
  Search,
  Settings as SettingsIcon,
  Share2,
  SlidersHorizontal,
  UserCog,
  Users,
  Wrench,
  X,
  type LucideIcon
} from "lucide-react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { HoveredLink, MenuItem } from "@/components/ui/navbar-menu";
import { FieldRepoLogo } from "@/components/FieldRepoLogo";
import {
  canCreateRecords,
  canDownloadDataset,
  canManageCrafts,
  canManageUsers,
  canManageWorkshops,
  canReview,
  isAdmin,
  roleLabel
} from "@/lib/permissions";
import { cn } from "@/lib/utils";
import type { User } from "@/lib/types";

/**
 * Dropdown groups in the desktop bar, in render order. `null` = a standalone link in the bar
 * (Dashboard and the Walkthrough, the two places a newcomer starts). "Account" sits last because it
 * holds what belongs to the person rather than to the repository — their own settings and feedback.
 */
const NAV_GROUPS = ["Record", "Browse", "Admin", "Account"] as const;
type NavGroup = (typeof NAV_GROUPS)[number];

type NavItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  group: NavGroup | null;
  /**
   * The entitlement this destination needs. When it returns false the entry is NOT RENDERED —
   * never rendered disabled — so the menu only ever offers what the API would actually allow.
   */
  can: (user: User) => boolean;
  /** The backend dependency `can` mirrors (app/core/deps.py); keep the two in step. */
  gate: string;
  /** Admin-tier chrome: admins additionally need admin view ON. Never widens `can`. */
  adminSurface?: boolean;
};

const everyone = () => true;

/**
 * The single source of truth for navigation. Every destination carries its own predicate, so the
 * gate lives next to the item instead of being scattered across the JSX, and one list drives both
 * the desktop dropdowns and the full sheet. Labels are the EXACT Android drawer/action names
 * (MainActivity EntryMode.actionTitle) so both clients speak one language.
 */
export const NAV_ITEMS: NavItem[] = [
  { href: "/dashboard", label: "Dashboard", icon: Gauge, group: null, can: everyone, gate: "get_current_user" },
  // Onboarding, deliberately ungated: the Walkthrough teaches the documentation process itself, so
  // it has to reach the people who have not earned any capability yet — a crowdsource volunteer on
  // their first day needs it MORE than an admin does. It is a static page that calls no API.
  { href: "/guide", label: "Walkthrough", icon: Compass, group: null, can: everyone, gate: "none (static page)" },

  // Record — every entry here creates something, so it follows the CREATE dependency, not the list
  // one. Hiding an entry therefore never hides the DATA behind it: the list endpoints stay open to
  // any signed-in user, and "Browse records" / "View Data" remain the read route to the same records.
  { href: "/artisans", label: "Record artisan", icon: Users, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/products", label: "Record product", icon: Boxes, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/processes", label: "Document process", icon: GitBranch, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  { href: "/tools", label: "Record tool", icon: Wrench, group: "Record", can: canCreateRecords, gate: "require_record_creator" },
  // Answering an interview is open to everyone — volunteers contribute answers and media.
  { href: "/questionnaire", label: "Take interview", icon: ClipboardList, group: "Record", can: everyone, gate: "get_current_user" },
  { href: "/media", label: "Upload media", icon: ImageIcon, group: "Record", can: everyone, gate: "get_current_user" },
  { href: "/crafts", label: "Add craft", icon: Brush, group: "Record", can: canManageCrafts, gate: "require_craft_manager" },
  { href: "/workshops", label: "Record workshop", icon: Users, group: "Record", can: canManageWorkshops, gate: "require_workshop_manager" },

  // Browse
  { href: "/activity", label: "My Activity", icon: Activity, group: "Browse", can: everyone, gate: "get_current_user" },
  // Everyone can be a task assignee; the "assign" half of the page is gated inside it.
  { href: "/tasks", label: "Tasks", icon: ClipboardCheck, group: "Browse", can: everyone, gate: "get_current_user" },
  { href: "/search", label: "Browse records", icon: Search, group: "Browse", can: everyone, gate: "get_current_user" },
  { href: "/data", label: "View Data", icon: FolderTree, group: "Browse", can: canDownloadDataset, gate: "require_dataset_downloader" },
  { href: "/sharing", label: "Share data access", icon: Share2, group: "Browse", can: everyone, gate: "get_current_user" },
  // Linking a tool to an artisan needs a tool or an artisan of your own — both need record creation.
  // The endpoint itself only requires a login and then checks ownership per artisan, so this is the
  // closest STATIC mirror of a dynamic rule: nobody below Field Contributor owns either side.
  { href: "/tools?assign=1", label: "Assign tools to artisans", icon: Wrench, group: "Browse", can: canCreateRecords, gate: "get_current_user + owner/EDIT-grant/admin per artisan" },

  // Admin — capability holders below admin (professors, grantees) keep these permanently; admins,
  // who own the toggle, see them only while admin view is ON.
  { href: "/review", label: "Review", icon: Eye, group: "Admin", can: canReview, gate: "require_reviewer", adminSurface: true },
  { href: "/admin", label: "Settings hub", icon: SlidersHorizontal, group: "Admin", can: isAdmin, gate: "require_admin", adminSurface: true },
  { href: "/users", label: "Manage users", icon: UserCog, group: "Admin", can: canManageUsers, gate: "require_professor", adminSurface: true },

  // Account — personal, so nothing here is role-gated.
  // /settings is TWO pages in one: the left column is the repository's global configuration
  // (require_master_admin, and it renders its own lock panel for everyone else) while the right
  // column is this account's theme, reduced motion, larger text and high contrast, saved through
  // PUT /preferences/me, which asks for nothing but a login. Gating the entry on isMasterAdmin left
  // every other user with NO route to their own accessibility switches, so it is `everyone` — the
  // master admin's global column is also linked from the /admin hub tile "App settings".
  { href: "/settings", label: "Settings", icon: SettingsIcon, group: "Account", can: everyone, gate: "get_current_user (PUT /preferences/me)" },
  { href: "/feedback", label: "Give app feedback", icon: MessageSquare, group: "Account", can: everyone, gate: "get_current_user (PUT /feedback/me)" }
];

/**
 * Entitlement first, admin view second: `can` is consulted before the toggle is even looked at, so
 * switching admin view ON can never surface a destination the API would 403 — it only hides admin
 * chrome from an admin browsing as an ordinary user.
 */
export function isNavItemVisible(item: NavItem, user: User | null | undefined, adminMode: boolean): boolean {
  if (!user || !item.can(user)) return false;
  if (item.adminSurface && isAdmin(user)) return adminMode;
  return true;
}

const spring = { type: "spring" as const, stiffness: 260, damping: 30 };

export function DynamicIslandNav() {
  const { user, logout } = useAuth();
  const { adminMode, canAdmin, toggleAdminView } = useAdminView();
  const router = useRouter();
  const pathname = usePathname();
  const [active, setActive] = useState<string | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [compact, setCompact] = useState(false);
  const { scrollY } = useScroll();
  const sheetRef = useRef<HTMLDivElement | null>(null);
  const menuButtonRef = useRef<HTMLButtonElement | null>(null);

  // Compact while travelling downward, expand at the top or on any upward scroll —
  // so the full menu is always one flick away without returning to the top.
  useMotionValueEvent(scrollY, "change", (latest) => {
    const previous = scrollY.getPrevious() ?? 0;
    if (latest < 24) setCompact(false);
    else if (latest > previous + 2) setCompact(true);
    else if (latest < previous - 2) setCompact(false);
  });

  useEffect(() => {
    setSheetOpen(false);
    setActive(null);
  }, [pathname]);

  // Keyboard path: opening the sheet moves focus into it, Escape closes it and hands focus back to
  // the button that opened it — the desktop dropdowns are pointer-only, so this sheet is the
  // keyboard route to every destination.
  useEffect(() => {
    if (!sheetOpen) return;
    sheetRef.current?.querySelector<HTMLElement>("a, button")?.focus();
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      setSheetOpen(false);
      menuButtonRef.current?.focus();
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [sheetOpen]);

  // One filtered list feeds both renderers, so a hidden entry cannot reappear in the other menu.
  const visibleItems = useMemo(
    () => NAV_ITEMS.filter((item) => isNavItemVisible(item, user, adminMode)),
    [user, adminMode]
  );
  const rootItems = visibleItems.filter((item) => item.group === null);
  const visibleGroups = NAV_GROUPS.map((label) => ({
    label,
    items: visibleItems.filter((item) => item.group === label)
  })).filter((group) => group.items.length > 0);

  if (!user) return null;

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

  const isActivePath = (href: string) => {
    const base = href.split("?")[0];
    return pathname === base || pathname.startsWith(`${base}/`);
  };

  return (
    <>
      <div className="pointer-events-none fixed inset-x-0 top-3 z-50 flex justify-center px-3">
        <motion.header
          layout
          transition={spring}
          onMouseLeave={() => setActive(null)}
          className={cn(
            "pointer-events-auto flex items-center gap-1 rounded-full border border-line-200 bg-card/85 shadow-island backdrop-blur-xl",
            compact ? "px-3 py-1.5" : "px-4 py-2"
          )}
        >
          {/* Brand: swoops in from the left every time the island compacts on a downward scroll. */}
          <motion.div
            key={compact ? "brand-compact" : "brand-full"}
            initial={{ x: -32, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            transition={spring}
          >
            <Link href="/dashboard" className="flex min-w-fit items-center gap-2 pr-2 text-ink-900">
              <FieldRepoLogo className={cn("rounded-lg", compact ? "h-7 w-7" : "h-8 w-8")} />
              <span className={cn("whitespace-nowrap font-display font-bold tracking-tight", compact ? "text-sm" : "text-base")}>
                Field Repository
              </span>
            </Link>
          </motion.div>

          {/* Desktop navigation, hidden while compact. */}
          <AnimatePresence initial={false}>
            {!compact ? (
              <motion.nav
                initial={{ opacity: 0, width: 0 }}
                animate={{ opacity: 1, width: "auto" }}
                exit={{ opacity: 0, width: 0 }}
                transition={{ duration: 0.22, ease: "easeOut" }}
                className="hidden items-center gap-5 overflow-visible whitespace-nowrap pl-2 pr-1 lg:flex"
                aria-label="Primary"
              >
                {rootItems.map((item) => (
                  <Link
                    key={item.href}
                    href={item.href}
                    aria-current={isActivePath(item.href) ? "page" : undefined}
                    className={cn(
                      "text-sm font-medium transition",
                      isActivePath(item.href) ? "text-purple-700" : "text-ink-700 hover:text-ink-900"
                    )}
                  >
                    {item.label}
                  </Link>
                ))}
                {visibleGroups.map((group) => (
                  <MenuItem key={group.label} setActive={setActive} active={active} item={group.label}>
                    <div className="flex flex-col space-y-3 text-sm">
                      {group.items.map((item) => (
                        <HoveredLink key={item.href} href={item.href}>
                          <span className="inline-flex items-center gap-2">
                            <item.icon className="h-3.5 w-3.5 text-ink-300" aria-hidden />
                            {item.label}
                          </span>
                        </HoveredLink>
                      ))}
                    </div>
                  </MenuItem>
                ))}
              </motion.nav>
            ) : null}
          </AnimatePresence>

          <div className="ml-1 flex items-center gap-1.5">
            {/* Offered to admins only — and it can merely hide admin chrome, never unlock it. */}
            {canAdmin && !compact ? (
              <button
                type="button"
                onClick={toggleAdminView}
                aria-pressed={adminMode}
                title={adminMode ? "Admin view: ON" : "Admin view: OFF"}
                className={cn(
                  "hidden items-center gap-1.5 rounded-full border px-2.5 py-1.5 text-xs font-medium transition sm:inline-flex",
                  adminMode
                    ? "border-purple-300 bg-purple-50 text-purple-700"
                    : "border-line-200 bg-card text-ink-500 hover:bg-surface-50"
                )}
              >
                {adminMode ? <Eye className="h-3.5 w-3.5" aria-hidden /> : <EyeOff className="h-3.5 w-3.5" aria-hidden />}
                {adminMode ? "Admin view: ON" : "Admin view: OFF"}
              </button>
            ) : null}
            <button
              ref={menuButtonRef}
              type="button"
              onClick={() => setSheetOpen((value) => !value)}
              aria-label="Toggle navigation menu"
              aria-expanded={sheetOpen}
              className="inline-flex h-9 w-9 items-center justify-center rounded-full border border-line-200 bg-card text-ink-900 transition hover:bg-surface-50"
            >
              {sheetOpen ? <X className="h-4 w-4" aria-hidden /> : <MenuIcon className="h-4 w-4" aria-hidden />}
            </button>
          </div>
        </motion.header>
      </div>

      {/* Full navigation sheet: keyboard-reachable path to every destination the user qualifies for. */}
      <AnimatePresence>
        {sheetOpen ? (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.18 }}
            className="fixed inset-0 z-40 bg-ink-900/20 backdrop-blur-sm"
            onClick={() => setSheetOpen(false)}
          >
            <motion.div
              ref={sheetRef}
              role="dialog"
              aria-modal="true"
              aria-label="Navigation"
              initial={{ y: -16, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: -16, opacity: 0 }}
              transition={spring}
              onClick={(event) => event.stopPropagation()}
              className="mx-auto mt-20 w-[min(680px,92vw)] rounded-xl border border-line-200 bg-card p-6 shadow-lg"
            >
              <div className="grid gap-1 sm:grid-cols-2">
                {visibleItems.map((item) => (
                  <Link
                    key={item.href}
                    href={item.href}
                    onClick={() => setSheetOpen(false)}
                    aria-current={isActivePath(item.href) ? "page" : undefined}
                    className={cn(
                      "inline-flex items-center gap-2.5 rounded-md px-3 py-2.5 text-sm font-medium transition",
                      isActivePath(item.href) ? "bg-purple-50 text-purple-700" : "text-ink-700 hover:bg-surface-50 hover:text-ink-900"
                    )}
                  >
                    <item.icon className="h-4 w-4 shrink-0 text-ink-300" aria-hidden />
                    {item.label}
                  </Link>
                ))}
              </div>
              <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-line-200 pt-4">
                {canAdmin ? (
                  <button type="button" onClick={toggleAdminView} aria-pressed={adminMode} className="field-button-secondary">
                    {adminMode ? <Eye className="h-4 w-4" aria-hidden /> : <EyeOff className="h-4 w-4" aria-hidden />}
                    {adminMode ? "Admin view: ON" : "Admin view: OFF"}
                  </button>
                ) : null}
                <div className="ml-auto text-right text-xs">
                  <div className="font-medium text-ink-900">{user.name}</div>
                  <div className="text-ink-500">{roleLabel(user.role)}</div>
                </div>
                <button onClick={handleLogout} className="field-danger">
                  <LogOut className="h-4 w-4" aria-hidden />
                  Logout
                </button>
              </div>
            </motion.div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </>
  );
}
