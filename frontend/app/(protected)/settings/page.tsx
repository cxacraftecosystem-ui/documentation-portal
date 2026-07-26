"use client";

import Link from "next/link";
import { ClipboardCheck, KeyRound, Settings as SettingsIcon, UsersRound, type LucideIcon } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { adminChromeVisible, useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { AppSettingsPanel } from "@/components/settings/AppSettingsPanel";
import { GetTheAppPanel } from "@/components/settings/GetTheAppPanel";
import { ProviderOrderPanel } from "@/components/settings/ProviderOrderPanel";
import { PublishAppUpdatePanel } from "@/components/settings/PublishAppUpdatePanel";
import { AccessibilityCard, AppearanceCard } from "@/components/settings/PersonalSettingsCards";
import { WorkshopAccessRequestPanel } from "@/components/settings/WorkshopAccessRequestPanel";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";
import type { User } from "@/lib/types";

/**
 * /settings — this ACCOUNT's settings first, administration second.
 *
 * The page used to run two columns, with the master admin's global configuration filling the left
 * one and a locked "you may not do this" placeholder standing in its place for everyone else. That
 * told ordinary users about powers they will never have and squeezed the two settings they DO own
 * into a narrow rail. Now:
 *
 *  - Appearance and Accessibility sit side by side at full width for everybody (stacked on a phone);
 *  - the "Request workshop access" panel is open to everyone, because asking is not an admin act;
 *  - admin destinations are HIDDEN, not disabled, and only rendered for accounts the API would
 *    actually let through — a non-admin sees no trace that these pages exist.
 *
 * Gating uses `isAdmin` / `isMasterAdmin` from lib/permissions, the same predicates the backend
 * dependencies mirror (`require_admin` / `require_master_admin`), ANDed with `adminChromeVisible`
 * so the admin half also disappears while an admin browses with admin view off. The route itself
 * stays open to everyone: what is left — Appearance, Accessibility and Request workshop access —
 * belongs to the account rather than to the repository, and is exactly what an ordinary user sees.
 */

type AdminLink = {
  label: string;
  description: string;
  href: string;
  icon: LucideIcon;
  visible: (user: User | null | undefined) => boolean;
};

const ADMIN_LINKS: AdminLink[] = [
  {
    // The board is admin-guarded and lives under /settings, but it was reachable ONLY from a button
    // on /tasks — so an admin told "it is in settings" found no trace of it here. Same title and
    // icon the page itself uses, so the card and its destination read as one thing.
    label: "Task assignment",
    description: "Hand documentation work to the people below you, then hold it to account.",
    href: "/settings/tasks",
    icon: ClipboardCheck,
    visible: isAdmin
  },
  {
    label: "Workshop access",
    description: "Approve access requests and manage who may work in each workshop, at what level.",
    href: "/settings/workshop-access",
    icon: UsersRound,
    visible: isAdmin
  },
  {
    label: "API keys",
    description: "Rotate, test and reveal the provider keys the repository runs on.",
    href: "/settings/api-keys",
    icon: KeyRound,
    visible: isMasterAdmin
  }
];

export default function SettingsPage() {
  const { user, loading } = useAuth();
  const { adminMode } = useAdminView();
  // Role first, toggle second — every `visible` predicate below is `isAdmin` or `isMasterAdmin`, so
  // ANDing the toggle in can only ever remove a card, never add one for a user without the rights.
  const chrome = adminChromeVisible(user, adminMode);
  const admin = isAdmin(user) && chrome;
  const master = isMasterAdmin(user) && chrome;
  const links = ADMIN_LINKS.filter((link) => link.visible(user) && chrome);

  const header = (
    <PageHeader
      title="Settings"
      description={
        admin
          ? "How this account looks and reads, plus the repository administration you are entitled to."
          : "How this account looks and reads, and the workshops you can ask to work in."
      }
      icon={<SettingsIcon className="h-5 w-5" aria-hidden />}
    />
  );

  if (loading) {
    return (
      <>
        {header}
        <div className="panel p-4 text-sm text-ink-500">Loading...</div>
      </>
    );
  }

  return (
    <>
      {header}
      <div className="grid gap-4">
        {/* The two settings every account owns — a pair on anything wider than a phone. */}
        <div className="grid items-start gap-4 md:grid-cols-2">
          <AppearanceCard />
          <AccessibilityCard />
        </div>

        {/* Everyone sees this: the two apps are one product and each is better at half the job. */}
        <GetTheAppPanel />

        <WorkshopAccessRequestPanel />

        {links.length ? (
          <section className="panel p-5">
            <h2 className="font-display font-bold text-ink-900">Repository administration</h2>
            <p className="mt-1 text-sm text-ink-500">Settings that apply to everyone, not just this account.</p>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              {links.map((link) => (
                <Link
                  className="group flex flex-col gap-2 rounded-lg border border-line-200 bg-card p-4 shadow-sm transition hover:border-purple-300 hover:shadow-md"
                  href={link.href}
                  key={link.href}
                >
                  <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
                    <link.icon className="h-5 w-5 text-white" aria-hidden />
                  </div>
                  <div className="font-display text-base font-bold leading-snug text-ink-900">{link.label}</div>
                  <p className="text-xs leading-5 text-ink-500">{link.description}</p>
                </Link>
              ))}
            </div>
          </section>
        ) : null}

        {/* Transcription and the off-peak window stay here, where they have always been — now gated
            rather than replaced by a lock panel for everyone below the master admin. */}
        {master ? <AppSettingsPanel /> : null}
        {master ? <ProviderOrderPanel /> : null}
        {master ? <PublishAppUpdatePanel /> : null}
      </div>
    </>
  );
}
