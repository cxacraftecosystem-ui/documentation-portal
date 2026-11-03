"use client";

import Link from "next/link";
import { ClipboardCheck, KeyRound, Settings as SettingsIcon, UsersRound, type LucideIcon } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { adminChromeVisible, useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { AppSettingsPanel } from "@/components/settings/AppSettingsPanel";
import { ChangePasswordCard } from "@/components/settings/ChangePasswordCard";
import { EmailNotificationsCard } from "@/components/settings/EmailNotificationsCard";
import { GetTheAppPanel } from "@/components/settings/GetTheAppPanel";
import { PublishAppUpdatePanel } from "@/components/settings/PublishAppUpdatePanel";
import { TwoStepVerificationCard } from "@/components/settings/TwoStepVerificationCard";
import { WeeklyDigestCard } from "@/components/settings/WeeklyDigestCard";
import { MyAiKeysPanel } from "@/components/settings/MyAiKeysPanel";
import { AccessibilityCard, AppearanceCard } from "@/components/settings/PersonalSettingsCards";
import { WorkshopAccessRequestPanel } from "@/components/settings/WorkshopAccessRequestPanel";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";
import type { User } from "@/lib/types";

type AdminLink = {
  label: string;
  description: string;
  href: string;
  icon: LucideIcon;
  visible: (user: User | null | undefined) => boolean;
};

const ADMIN_LINKS: AdminLink[] = [
  {
    label: "Task assignment",
    description: "Assign and track work.",
    href: "/settings/tasks",
    icon: ClipboardCheck,
    visible: isAdmin
  },
  {
    label: "Workshop access",
    description: "Requests and rosters.",
    href: "/workshop-access/manage",
    icon: UsersRound,
    visible: isAdmin
  },
  {
    label: "API keys",
    description: "Transcription providers and keys.",
    href: "/settings/api-keys",
    icon: KeyRound,
    visible: isAdmin
  }
];

export default function SettingsPage() {
  const { user, loading } = useAuth();
  const { adminMode } = useAdminView();
  const chrome = adminChromeVisible(user, adminMode);
  const admin = isAdmin(user) && chrome;
  const master = isMasterAdmin(user) && chrome;
  const links = ADMIN_LINKS.filter((link) => link.visible(user) && chrome);

  const header = (
    <PageHeader
      title="Settings"
      description={
        admin
          ? "Display and admin tools."
          : "Display and workshop access."
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
        <div className="grid items-start gap-4 md:grid-cols-2">
          <AppearanceCard />
          <AccessibilityCard />
        </div>

        <ChangePasswordCard />

        <TwoStepVerificationCard />

        <EmailNotificationsCard />
        <WeeklyDigestCard />

        <GetTheAppPanel />

        <MyAiKeysPanel />

        <WorkshopAccessRequestPanel />

        {links.length ? (
          <section className="panel p-5">
            <h2 className="font-display font-bold text-ink-900">Administration</h2>
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

        {master ? <AppSettingsPanel /> : null}
        {master ? <PublishAppUpdatePanel /> : null}
      </div>
    </>
  );
}
