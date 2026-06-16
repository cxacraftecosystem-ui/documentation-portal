"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";

type AdminViewValue = {
  /** True only when the user has admin rights AND has admin view turned on. Gate admin UI on this. */
  adminMode: boolean;
  /** Whether the user is currently in admin view (false for non-admins). */
  adminView: boolean;
  /** Whether the user has any admin capability at all. */
  canAdmin: boolean;
  setAdminView: (value: boolean) => void;
  toggleAdminView: () => void;
};

const AdminViewContext = createContext<AdminViewValue | undefined>(undefined);
const STORAGE_PREFIX = "field_repo_admin_view";

export function AdminViewProvider({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const canAdmin = isAdmin(user);
  // null = not yet initialised for this user; resolved in the effect below.
  const [adminView, setAdminViewState] = useState<boolean | null>(null);

  useEffect(() => {
    if (!user || !canAdmin) {
      setAdminViewState(false);
      return;
    }
    const stored = typeof window !== "undefined" ? window.localStorage.getItem(`${STORAGE_PREFIX}:${user.id}`) : null;
    if (stored === "on") setAdminViewState(true);
    else if (stored === "off") setAdminViewState(false);
    // Default: master admin opens in admin view; other admins open normally.
    else setAdminViewState(isMasterAdmin(user));
  }, [user, canAdmin]);

  const setAdminView = useCallback(
    (value: boolean) => {
      if (!canAdmin) return;
      setAdminViewState(value);
      if (user && typeof window !== "undefined") {
        window.localStorage.setItem(`${STORAGE_PREFIX}:${user.id}`, value ? "on" : "off");
      }
    },
    [canAdmin, user]
  );

  const value = useMemo<AdminViewValue>(() => {
    const view = canAdmin && (adminView ?? isMasterAdmin(user));
    return {
      adminView: view,
      adminMode: view,
      canAdmin,
      setAdminView,
      toggleAdminView: () => setAdminView(!view)
    };
  }, [adminView, canAdmin, user, setAdminView]);

  return <AdminViewContext.Provider value={value}>{children}</AdminViewContext.Provider>;
}

export function useAdminView(): AdminViewValue {
  const ctx = useContext(AdminViewContext);
  if (!ctx) throw new Error("useAdminView must be used inside AdminViewProvider");
  return ctx;
}
