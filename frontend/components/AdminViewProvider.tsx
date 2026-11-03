"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { isAdmin, isContentAdmin, isMasterAdmin } from "@/lib/permissions";
import type { User } from "@/lib/types";

type AdminViewValue = {
  adminMode: boolean;
  adminView: boolean;
  canDeleteRecords: boolean;
  canAdmin: boolean;
  available: boolean;
  adminViewResolved: boolean;
  setAdminView: (value: boolean) => void;
  toggleAdminView: () => void;
};

const AdminViewContext = createContext<AdminViewValue | undefined>(undefined);
const STORAGE_PREFIX = "field_repo_admin_view";

type ResolvedView = { userId: string; view: boolean };

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
  }
}

export function AdminViewProvider({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const canAdmin = isContentAdmin(user);
  const [resolved, setResolved] = useState<ResolvedView | null>(null);

  useEffect(() => {
    if (!user) {
      setResolved(null);
      return;
    }
    if (!canAdmin) {
      writePreference(`${STORAGE_PREFIX}:${user.id}`, null);
      setResolved({ userId: user.id, view: false });
      return;
    }
    const stored = readPreference(`${STORAGE_PREFIX}:${user.id}`);
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
    const view = canAdmin && settled !== null && settled.view;
    return {
      adminView: view,
      adminMode: view,
      canDeleteRecords: canAdmin,
      canAdmin,
      available: canAdmin,
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

export type AdminChromeRoute = {
  path: string;
  label: string;
  blurb: string;
  alternative?: { href: string; label: string };
};

export const ADMIN_CHROME_ROUTES: AdminChromeRoute[] = [
  {
    path: "/admin",
    label: "The settings hub",
    blurb: "Admin tools."
  },
  {
    path: "/admin/access-roster",
    label: "The access roster",
    blurb:
      "Who may sign in."
  },
  {
    path: "/users",
    label: "User management",
    blurb: "Roles and accounts."
  },
  {
    path: "/settings/api-keys",
    label: "Managed API keys",
    blurb: "Service keys."
  },
  {
    path: "/workshop-access/manage",
    label: "Workshop access administration",
    blurb: "Requests and rosters.",
    alternative: { href: "/workshop-access/request", label: "Request workshop access" }
  },
  {
    path: "/settings/tasks",
    label: "The task assignment board",
    blurb: "Assign and track work.",
    alternative: { href: "/tasks", label: "Go to my tasks" }
  }
];

export function adminChromeVisible(user: User | null | undefined, adminMode: boolean): boolean {
  return !isAdmin(user) || adminMode;
}

export function adminChromeRouteFor(pathname: string): AdminChromeRoute | null {
  let best: AdminChromeRoute | null = null;
  for (const route of ADMIN_CHROME_ROUTES) {
    if (pathname !== route.path && !pathname.startsWith(`${route.path}/`)) continue;
    if (!best || route.path.length > best.path.length) best = route;
  }
  return best;
}
