"use client";

import { FieldRepoLogo } from "@/components/FieldRepoLogo";
import { GLASS_PANEL, GlassSurface } from "@/components/ui/GlassSurface";

/**
 * The frosted card on the mesh backdrop that every password screen outside the app draws in — the
 * sign-in card's right half on its own. Used by `/set-password`, `/forgot-password` and the forced
 * password change, so the four auth screens cannot come to look like four products.
 */
export function AuthCard({
  title,
  subtitle,
  children
}: {
  title: string;
  subtitle?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-bg-0 px-6 py-6 md:px-10">
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <div className="absolute inset-0 grad-mesh opacity-60" />
        <div className="absolute -left-16 top-12 h-72 w-72 rounded-full bg-purple-300/30 blur-3xl" />
        <div className="absolute -right-12 bottom-8 h-80 w-80 rounded-full bg-amber-500/15 blur-3xl" />
      </div>
      <GlassSurface options={GLASS_PANEL} className="glass-card relative z-10 w-full max-w-md rounded-xl p-6 shadow-lg md:p-8">
        <div className="mb-6 flex flex-col items-center gap-2 text-center">
          <FieldRepoLogo className="h-12 w-12 rounded-xl shadow-sm" />
          <h1 className="font-display text-2xl font-bold text-ink-900">{title}</h1>
          {subtitle ? <p className="text-sm leading-6 text-ink-500">{subtitle}</p> : null}
        </div>
        {children}
      </GlassSurface>
    </main>
  );
}
