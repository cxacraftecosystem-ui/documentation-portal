"use client";

import Link from "next/link";

import { useAuth } from "@/components/AuthProvider";
import { DynamicIslandNav } from "@/components/DynamicIslandNav";
import { FieldRepoLogo } from "@/components/FieldRepoLogo";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { roleLabel } from "@/lib/permissions";

export function DeadEndFrame({
  nav,
  children
}: {
  nav: boolean;
  children: React.ReactNode;
}) {
  const { user, loading } = useAuth();

  const brand = !nav || (!loading && !user);

  return (
    <div className="min-h-screen bg-bg-0">
      {nav ? (
        <>
          <a
            href="#main-content"
            className="sr-only left-3 top-3 z-60 rounded-md bg-purple-700 px-3 py-2 text-sm font-medium text-white focus:not-sr-only focus:fixed"
          >
            Skip to content
          </a>
          <DynamicIslandNav />
        </>
      ) : null}
      <main id="main-content" tabIndex={-1} className="mx-auto max-w-7xl px-4 pb-12 pt-24">
        {brand ? (
          <div className="mb-8 flex items-center justify-center gap-2 text-ink-900">
            <FieldRepoLogo className="h-8 w-8 rounded-lg" />
            <span className="font-display text-base font-bold tracking-tight">Field Repository</span>
          </div>
        ) : null}
        {children}
      </main>
    </div>
  );
}

export function DeadEndPanel({
  icon,
  tone,
  title,
  children
}: {
  icon: React.ReactNode;
  tone: "notice" | "alert";
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="panel px-6 py-14 text-center">
      <div
        className={
          tone === "alert"
            ? "mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-error-100 text-red-800"
            : "mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700"
        }
      >
        {icon}
      </div>
      <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">{title}</h1>
      {children}
    </section>
  );
}

export function DeadEndText({ children }: { children: React.ReactNode }) {
  return <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">{children}</p>;
}

export function DeadEndDetail({ label, value }: { label: string; value: string }) {
  return (
    <p className="mx-auto mt-4 max-w-lg rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500">
      {label} <code className="break-all font-mono text-ink-700">{value}</code>
    </p>
  );
}

export function DeadEndWayBack({
  identity,
  children
}: {
  identity?: boolean;
  children?: React.ReactNode;
}) {
  const { user, loading } = useAuth();

  const lead = !children;

  return (
    <>
      {identity && !loading ? (
        <p className="mt-3 text-xs text-ink-500">
          {user ? (
            <>
              You are signed in as <span className="font-medium text-ink-700">{roleLabel(user.role)}</span>.
            </>
          ) : (
            "You are not signed in. Sign in to see records."
          )}
        </p>
      ) : null}
      <ActionGroup className="mt-7">
        {children}
        {loading ? null : user ? (
          <>
            <Link href="/dashboard" className={lead ? "field-button" : "field-button-secondary"}>
              Back to dashboard
            </Link>
            <Link href="/search" className="field-button-secondary">
              Search
            </Link>
          </>
        ) : (
          <>
            <Link href="/login" className={lead ? "field-button" : "field-button-secondary"}>
              Sign in
            </Link>
            <Link href="/" className="field-button-secondary">
              Go to the home page
            </Link>
          </>
        )}
      </ActionGroup>
      {loading ? <p className="mt-3 text-xs text-ink-500">Checking your session…</p> : null}
    </>
  );
}
