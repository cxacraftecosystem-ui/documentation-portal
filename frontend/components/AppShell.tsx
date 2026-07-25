"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { motion } from "framer-motion";
import { Lock } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { DynamicIslandNav } from "@/components/DynamicIslandNav";
import { roleLabel, routeGuardFor } from "@/lib/permissions";

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, router, user]);

  if (loading) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-bg-0 text-sm text-ink-500">
        Opening the repository…
      </main>
    );
  }

  if (!user) return null;

  /**
   * Page-level enforcement for the whole protected tree. Hiding a nav entry only removes the link —
   * /users, /review, /data and the create forms are still one typed URL away — so the guard table in
   * lib/permissions.ts is applied HERE, above every page, and the page never renders at all when the
   * user fails it. Pages that also guard themselves are simply defended twice.
   */
  const guard = routeGuardFor(pathname);
  const blocked = Boolean(guard && !guard.can(user));

  return (
    <div className="min-h-screen bg-bg-0">
      {/* The island is a floating pill and comes first in the tab order — give the keyboard a way
          past it straight to the page content. Visible only while focused. */}
      <a
        href="#main-content"
        className="sr-only left-3 top-3 z-[60] rounded-md bg-purple-700 px-3 py-2 text-sm font-medium text-white focus:not-sr-only focus:fixed"
      >
        Skip to content
      </a>
      <DynamicIslandNav />
      <motion.main
        id="main-content"
        tabIndex={-1}
        key={pathname}
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.22, ease: "easeOut" }}
        className="mx-auto max-w-7xl px-4 pb-12 pt-24"
      >
        {blocked && guard ? <RouteLocked title={guard.title} message={guard.message} role={roleLabel(user.role)} /> : children}
      </motion.main>
    </div>
  );
}

/**
 * What a user sees instead of a page they may not open. It names their tier and points at the two
 * routes that are always available — the dashboard and the walkthrough — rather than dead-ending.
 */
function RouteLocked({ title, message, role }: { title: string; message: string; role: string }) {
  return (
    <section className="panel px-6 py-14 text-center" aria-live="polite">
      <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
        <Lock className="h-5 w-5" aria-hidden />
      </div>
      <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">{title}</h1>
      <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">{message}</p>
      <p className="mt-3 text-xs text-ink-500">
        You are signed in as <span className="font-medium text-ink-700">{role}</span>. An admin can raise your access.
      </p>
      <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
        <Link href="/dashboard" className="field-button">
          Back to dashboard
        </Link>
        <Link href="/guide" className="field-button-secondary">
          Open the walkthrough
        </Link>
      </div>
    </section>
  );
}
