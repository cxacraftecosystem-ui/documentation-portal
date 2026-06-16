"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  Boxes,
  ClipboardCheck,
  ClipboardList,
  Eye,
  EyeOff,
  Gauge,
  Hammer,
  Landmark,
  LogOut,
  Menu,
  MapPinned,
  Search,
  ShieldCheck,
  Sparkle,
  Users,
  X
} from "lucide-react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { canManageCrafts, canManageWorkshops } from "@/lib/permissions";

type NavItem = {
  href: string;
  label: string;
  icon: typeof Gauge;
  admin?: boolean;
  /** Render at the end of the nav (low-friction placement for less-used destinations). */
  atEnd?: boolean;
};

const baseNav: NavItem[] = [
  { href: "/dashboard", label: "Dashboard", icon: Gauge },
  { href: "/artisans", label: "Artisans", icon: Users },
  { href: "/products", label: "Products", icon: Boxes },
  { href: "/tools", label: "Tools", icon: Hammer },
  { href: "/questionnaire", label: "Questionnaire", icon: ClipboardList },
  { href: "/search", label: "Search", icon: Search },
  { href: "/review", label: "Review", admin: true, icon: ClipboardCheck },
  { href: "/users", label: "Users", admin: true, icon: ShieldCheck },
  // Crafts and workshops sit at the end so the everyday capture flow stays front and centre.
  { href: "/crafts", label: "Crafts", icon: Landmark, atEnd: true },
  { href: "/workshops", label: "Workshops", icon: MapPinned, atEnd: true }
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, loading, logout } = useAuth();
  const { adminMode, canAdmin, toggleAdminView } = useAdminView();
  const router = useRouter();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, router, user]);

  // Close the overlay menu on route change.
  useEffect(() => {
    setMenuOpen(false);
  }, [pathname]);

  if (loading) {
    return <main className="flex min-h-screen items-center justify-center bg-field-50 text-sm text-ink-muted">Loading repository...</main>;
  }

  if (!user) return null;

  // Admin destinations only appear when admin view is on; crafts/workshops move to the end.
  const visibleNav = baseNav.filter((item) => !item.admin || adminMode);
  const orderedNav = [...visibleNav.filter((item) => !item.atEnd), ...visibleNav.filter((item) => item.atEnd)];
  const quickNav = orderedNav; // horizontal bar mirrors the full menu order

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

  const navLink = (item: NavItem, compact = false) => {
    const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
    return (
      <Link
        key={item.href}
        href={item.href}
        onClick={() => setMenuOpen(false)}
        className={`group inline-flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition active:scale-[0.98] ${
          active ? "bg-field-200 text-ink" : "text-ink-muted hover:bg-field-100 hover:text-ink"
        }`}
      >
        <item.icon className="h-4 w-4 shrink-0" aria-hidden />
        <span className={compact ? "max-w-0 overflow-hidden whitespace-nowrap opacity-0 transition-all duration-200 group-hover:max-w-32 group-hover:opacity-100" : ""}>
          {item.label}
        </span>
      </Link>
    );
  };

  return (
    <div className="min-h-screen bg-field-50">
      <header className="sticky top-0 z-30 border-b border-[#e6dfd8] bg-field-50/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4 px-4 py-3">
          <Link href="/dashboard" className="flex min-w-fit items-center gap-2 text-base font-semibold text-ink">
            <span className="grid h-8 w-8 place-items-center rounded-full border border-[#e6dfd8] bg-field-200 text-field-600">
              <Sparkle className="h-4 w-4" aria-hidden />
            </span>
            <span className="font-serif text-xl font-normal">Field Repository</span>
          </Link>
          <nav className="hidden flex-1 items-center justify-center gap-1 lg:flex">{quickNav.map((item) => navLink(item))}</nav>
          <div className="flex items-center gap-2">
            {canAdmin ? (
              <button
                type="button"
                onClick={toggleAdminView}
                title={adminMode ? "Admin view is on" : "Admin view is off"}
                className={`hidden items-center gap-1.5 rounded-md border px-2.5 py-1.5 text-xs font-medium transition sm:inline-flex ${
                  adminMode ? "border-field-500 bg-field-200 text-ink" : "border-[#e6dfd8] bg-field-50 text-ink-muted hover:bg-field-100"
                }`}
              >
                {adminMode ? <Eye className="h-3.5 w-3.5" aria-hidden /> : <EyeOff className="h-3.5 w-3.5" aria-hidden />}
                Admin view
              </button>
            ) : null}
            <div className="hidden text-right text-xs sm:block">
              <div className="font-medium text-ink">{user.name}</div>
              <div className="text-ink-muted">{adminMode ? user.role : user.role === "RESEARCHER" ? "Researcher" : `${user.role} · standard view`}</div>
            </div>
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => setMenuOpen((value) => !value)}
              aria-label="Toggle navigation menu"
              aria-expanded={menuOpen}
            >
              {menuOpen ? <X className="h-4 w-4" aria-hidden /> : <Menu className="h-4 w-4" aria-hidden />}
              <span className="hidden sm:inline">Menu</span>
            </button>
          </div>
        </div>
        <AnimatePresence>
          {menuOpen ? (
            <motion.nav
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: "auto" }}
              exit={{ opacity: 0, height: 0 }}
              transition={{ duration: 0.18, ease: "easeOut" }}
              className="overflow-hidden border-t border-[#ebe6df] bg-field-50"
            >
              <div className="mx-auto grid max-w-7xl gap-1 px-4 py-3 sm:grid-cols-2 lg:grid-cols-3">
                {orderedNav.map((item) => navLink(item))}
              </div>
              <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-2 border-t border-[#ebe6df] px-4 py-3">
                {canAdmin ? (
                  <button type="button" onClick={toggleAdminView} className="field-button-secondary">
                    {adminMode ? <Eye className="h-4 w-4" aria-hidden /> : <EyeOff className="h-4 w-4" aria-hidden />}
                    {adminMode ? "Admin view: on" : "Admin view: off"}
                  </button>
                ) : null}
                <div className="ml-auto text-right text-xs sm:hidden">
                  <div className="font-medium text-ink">{user.name}</div>
                  <div className="text-ink-muted">{user.role}</div>
                </div>
                <button onClick={handleLogout} className="field-button-secondary">
                  <LogOut className="h-4 w-4" aria-hidden />
                  Logout
                </button>
              </div>
            </motion.nav>
          ) : null}
        </AnimatePresence>
      </header>
      <motion.main
        key={pathname}
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.22, ease: "easeOut" }}
        className="mx-auto max-w-7xl px-4 py-8"
      >
        {children}
      </motion.main>
    </div>
  );
}
