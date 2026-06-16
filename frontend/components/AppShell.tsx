"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import {
  Boxes,
  ClipboardCheck,
  ClipboardList,
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

import { useAuth } from "@/components/AuthProvider";
import { isAdmin } from "@/lib/permissions";

const baseNav = [
  { href: "/dashboard", label: "Dashboard", icon: Gauge },
  { href: "/artisans", label: "Artisans", icon: Users },
  { href: "/crafts", label: "Crafts", icon: Landmark },
  { href: "/workshops", label: "Workshops", icon: MapPinned },
  { href: "/products", label: "Products", icon: Boxes },
  { href: "/tools", label: "Tools", icon: Hammer },
  { href: "/questionnaire", label: "Questionnaire", icon: ClipboardList },
  { href: "/search", label: "Search", icon: Search },
  { href: "/review", label: "Review", admin: true, icon: ClipboardCheck },
  { href: "/users", label: "Users", admin: true, icon: ShieldCheck }
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, router, user]);

  if (loading) {
    return <main className="flex min-h-screen items-center justify-center bg-field-50 text-sm text-ink-muted">Loading repository...</main>;
  }

  if (!user) return null;

  const navItems = baseNav.filter((item) => !item.admin || isAdmin(user));
  const navLink = (item: (typeof navItems)[number], compact = false) => {
    const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
    return (
      <Link
        key={item.href}
        href={item.href}
        onClick={() => setMobileOpen(false)}
        className={`group inline-flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition ${
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
      <header className="sticky top-0 z-20 border-b border-[#e6dfd8] bg-field-50/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4 px-4 py-3">
          <Link href="/dashboard" className="flex min-w-fit items-center gap-2 text-base font-semibold text-ink">
            <span className="grid h-8 w-8 place-items-center rounded-full border border-[#e6dfd8] bg-field-200 text-field-600">
              <Sparkle className="h-4 w-4" aria-hidden />
            </span>
            <span className="font-serif text-xl font-normal">Field Repository</span>
          </Link>
          <nav className="hidden flex-1 items-center justify-center gap-1 lg:flex">
            {navItems.map((item) => navLink(item))}
          </nav>
          <nav className="hidden flex-1 items-center justify-center gap-1 md:flex lg:hidden">
            {navItems.map((item) => navLink(item, true))}
          </nav>
          <div className="flex items-center gap-3">
            <div className="hidden text-right text-xs sm:block">
              <div className="font-medium text-ink">{user.name}</div>
              <div className="text-ink-muted">{user.role}</div>
            </div>
            <button
              onClick={async () => {
                await logout();
                router.replace("/login");
              }}
              className="field-button-secondary hidden sm:inline-flex"
            >
              <LogOut className="h-4 w-4" aria-hidden />
              Logout
            </button>
            <button type="button" className="field-button-secondary md:hidden" onClick={() => setMobileOpen((value) => !value)} aria-label="Toggle menu">
              {mobileOpen ? <X className="h-4 w-4" aria-hidden /> : <Menu className="h-4 w-4" aria-hidden />}
            </button>
          </div>
        </div>
        {mobileOpen ? (
          <nav className="grid gap-1 border-t border-[#ebe6df] px-4 py-3 md:hidden">
            {navItems.map((item) => navLink(item))}
            <button
              onClick={async () => {
                await logout();
                router.replace("/login");
              }}
              className="field-button-secondary justify-start"
            >
              <LogOut className="h-4 w-4" aria-hidden />
              Logout
            </button>
          </nav>
        ) : null}
      </header>
      <main className="mx-auto max-w-7xl px-4 py-8">{children}</main>
    </div>
  );
}
