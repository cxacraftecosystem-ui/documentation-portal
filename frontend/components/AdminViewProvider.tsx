"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";
import type { User } from "@/lib/types";

/**
 * Admin view is a NARROWING switch, never a widening one: it lets an admin browse the app as an
 * ordinary user by hiding admin chrome. Nothing may be unlocked by turning it on — every gate
 * still checks the role/capability first (see NAV_ITEMS in DynamicIslandNav, ROUTE_GUARDS in
 * lib/permissions, and `adminChromeVisible` below), so a non-admin can neither reach the toggle nor
 * gain anything from a forged `adminMode`: `canAdmin` is recomputed from the server-issued role on
 * every render and the stored preference is wiped for anyone who is not an admin.
 */
type AdminViewValue = {
  /** True only when the user has admin rights AND has admin view turned on. Gate admin UI on this. */
  adminMode: boolean;
  /** Whether the user is currently in admin view (false for non-admins). */
  adminView: boolean;
  /** Whether the user has any admin capability at all — the toggle is offered to these users only. */
  canAdmin: boolean;
  /**
   * False until the stored preference for THIS account has actually been read. `user` arrives from
   * an async /me call, so there is one commit where the account is known but its preference is not;
   * anything that would LOCK a page on admin view has to wait for this rather than decide on the
   * default and flash the wrong answer. Everything that merely HIDES chrome can ignore it, because
   * `adminMode` is false until it resolves — the safe direction.
   */
  adminViewResolved: boolean;
  setAdminView: (value: boolean) => void;
  toggleAdminView: () => void;
};

const AdminViewContext = createContext<AdminViewValue | undefined>(undefined);
const STORAGE_PREFIX = "field_repo_admin_view";

/** The stored preference once read, tagged with the account it was read for. */
type ResolvedView = { userId: string; view: boolean };

/**
 * localStorage throws rather than returning null when a browser has storage blocked. The preference
 * is not worth an exception — and an exception thrown in the effect below would leave the view
 * unresolved forever, which is a stalled page on every admin route. Failing to "no preference
 * stored" degrades to the default (master admin in admin view, other admins out of it).
 */
function readPreference(key: string): string | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writePreference(key: string, value: string | null) {
  try {
    if (typeof window === "undefined") return;
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    // A preference that cannot be persisted still applies for this session.
  }
}

export function AdminViewProvider({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const canAdmin = isAdmin(user);
  // null = not yet read for this user; resolved in the effect below. Tagging it with the user id is
  // what stops one account's preference from being applied for a frame after a switch of accounts.
  const [resolved, setResolved] = useState<ResolvedView | null>(null);

  useEffect(() => {
    if (!user) {
      setResolved(null);
      return;
    }
    if (!canAdmin) {
      // Drop any preference left behind by a demotion (or a hand-edited value) so it cannot come
      // back if the account is promoted again — admin view is re-earned, not remembered.
      writePreference(`${STORAGE_PREFIX}:${user.id}`, null);
      setResolved({ userId: user.id, view: false });
      return;
    }
    const stored = readPreference(`${STORAGE_PREFIX}:${user.id}`);
    // Default: master admin opens in admin view; other admins open normally.
    const view = stored === "on" ? true : stored === "off" ? false : isMasterAdmin(user);
    setResolved({ userId: user.id, view });
  }, [user, canAdmin]);

  const setAdminView = useCallback(
    (value: boolean) => {
      if (!canAdmin || !user) return;
      setResolved({ userId: user.id, view: value });
      writePreference(`${STORAGE_PREFIX}:${user.id}`, value ? "on" : "off");
    },
    [canAdmin, user]
  );

  const value = useMemo<AdminViewValue>(() => {
    const settled = user && resolved && resolved.userId === user.id ? resolved : null;
    // Unresolved reads as OFF, so no admin chrome can be painted before we know the preference.
    const view = canAdmin && settled !== null && settled.view;
    return {
      adminView: view,
      adminMode: view,
      canAdmin,
      adminViewResolved: settled !== null,
      setAdminView,
      toggleAdminView: () => setAdminView(!view)
    };
  }, [canAdmin, resolved, setAdminView, user]);

  return <AdminViewContext.Provider value={value}>{children}</AdminViewContext.Provider>;
}

export function useAdminView(): AdminViewValue {
  const ctx = useContext(AdminViewContext);
  if (!ctx) throw new Error("useAdminView must be used inside AdminViewProvider");
  return ctx;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Admin chrome — the half of the app the toggle may hide.
 *
 * Hiding a nav entry is not narrowing: /admin, /users, /settings/api-keys and the rest are all one
 * typed URL, bookmark or Back button away. The list below is therefore the single place that says
 * WHICH routes are admin chrome, and AppShell enforces it above every page in the (protected) tree
 * the same way it enforces ROUTE_GUARDS.
 *
 * What is deliberately NOT here:
 *  - /review — Field Contributors and above review, so it is a capability an admin holds as a
 *    reviewer, not admin chrome. Locking it on the toggle would stop an admin reviewing at all.
 *  - /tasks — everyone can be an assignee. Only the ASSIGNING board (/settings/tasks) is chrome.
 *  - /data — professors and dataset grantees, /search, /sharing and every record page: an ordinary
 *    user reaches these too, so narrowing to "what an ordinary user sees" leaves them untouched.
 *  - /settings itself — Appearance, Accessibility and Request workshop access belong to the
 *    account, not to the repository. Only its administration grid and the master-admin app
 *    settings panel are chrome, and the page hides those sections itself.
 * ──────────────────────────────────────────────────────────────────────────── */

export type AdminChromeRoute = {
  /** The path this rule covers, together with everything nested beneath it. */
  path: string;
  /** How the lock panel names the page. */
  label: string;
  /** One line on what is behind the switch, so the panel explains rather than just refuses. */
  blurb: string;
  /** The ordinary-user route that does the nearest equivalent job, where one exists. */
  alternative?: { href: string; label: string };
};

export const ADMIN_CHROME_ROUTES: AdminChromeRoute[] = [
  {
    path: "/admin",
    label: "The settings hub",
    blurb: "It gathers reviews, recovered recordings, feedback, tool assignment and user management in one place."
  },
  {
    // Professor+ by role, so this rule can only ever fire for an ADMIN — a professor has no toggle
    // and `adminChromeVisible` returns true for them, which is what keeps them out of this branch.
    path: "/users",
    label: "User management",
    blurb: "Roles, promotions, capability grants and account administration live there."
  },
  {
    path: "/settings/api-keys",
    label: "Managed API keys",
    blurb: "Rotating, testing and revealing the provider keys the repository runs on."
  },
  {
    path: "/workshop-access/manage",
    label: "Workshop access administration",
    blurb: "The cross-workshop request queue, plus each workshop's roster and access levels.",
    // /workshop-access itself is NOT chrome: it belongs to every account, and an admin with the
    // toggle off is sent to the request page there exactly as an ordinary user is. Only the console
    // is chrome, so this is the one path listed.
    alternative: { href: "/workshop-access/request", label: "Request workshop access" }
  },
  {
    path: "/settings/tasks",
    label: "The task assignment board",
    blurb: "Handing documentation work to the people below you, then holding it to account.",
    alternative: { href: "/tasks", label: "Go to my tasks" }
  }
];

/**
 * Does an admin-tier surface show, given the toggle?
 *
 * This answers ONLY the admin-view half of the question and must always be ANDed with the role or
 * capability check that already guards the surface — on its own it returns true for everyone
 * WITHOUT the toggle (professors, capability grantees, ordinary users), because they have no way to
 * switch admin view back on and must never be narrowed by a control they do not own. That shape is
 * also the invariant: a forged `adminMode` can only ever satisfy this half, never the role half.
 */
export function adminChromeVisible(user: User | null | undefined, adminMode: boolean): boolean {
  return !isAdmin(user) || adminMode;
}

/** The most specific admin-chrome rule covering `pathname`, or null when the route is not chrome. */
export function adminChromeRouteFor(pathname: string): AdminChromeRoute | null {
  let best: AdminChromeRoute | null = null;
  for (const route of ADMIN_CHROME_ROUTES) {
    if (pathname !== route.path && !pathname.startsWith(`${route.path}/`)) continue;
    if (!best || route.path.length > best.path.length) best = route;
  }
  return best;
}
