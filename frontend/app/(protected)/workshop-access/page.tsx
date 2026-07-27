"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { KeyRound, ShieldCheck, UsersRound, type LucideIcon } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { isAdmin } from "@/lib/permissions";

/**
 * /workshop-access — a fork that only one kind of account ever sees as a fork.
 *
 * "Workshop access" used to open the admin console (/settings/workshop-access) for everybody who
 * clicked it, which meant a researcher or volunteer — exactly the people the feature exists for —
 * got a padlock, while the page they were actually entitled to sat unadvertised inside /settings.
 * The Android client had this right all along: its Workshop access screen is the USER's screen,
 * "Request workshop access" over "My workshop access", and the queue lives in the admin hub.
 *
 * So the road forks only for someone who owns both ends of it:
 *  - an admin in admin view picks — their own standing, or the repository's queue and rosters;
 *  - everybody else, an admin with admin view OFF included, is sent straight to the page that is
 *    theirs. Not a teaser, not a lock: the toggle makes an admin browse as an ordinary user, and an
 *    ordinary user has one workshop-access page, so that is what they land on.
 *
 * The admin console's own role check is not here — it is on /workshop-access/manage, from
 * ROUTE_REDIRECTS — because a typed URL skips this page entirely.
 */

type Choice = {
  href: string;
  label: string;
  description: string;
  icon: LucideIcon;
};

const CHOICES: Choice[] = [
  {
    href: "/workshop-access/request",
    label: "Request workshop access",
    description:
      "Ask to work in one or more workshops, at the level you need — and see where every request you have made got to.",
    icon: KeyRound
  },
  {
    href: "/workshop-access/manage",
    label: "Manage workshop access",
    description:
      "The request queue across every workshop, plus each roster and its access levels. Admins and the master admin.",
    icon: ShieldCheck
  }
];

export default function WorkshopAccessPage() {
  const { user, loading } = useAuth();
  const { adminMode, adminViewResolved } = useAdminView();
  const router = useRouter();

  const fork = isAdmin(user) && adminMode;
  /**
   * An admin's stored admin-view preference arrives a commit after the account does. Redirecting on
   * that first commit would bounce an admin browsing in admin view off their own fork before it ever
   * painted, so the decision waits — for a non-admin there is nothing to wait for.
   */
  const settled = !loading && (!isAdmin(user) || adminViewResolved);

  useEffect(() => {
    if (settled && !fork) router.replace("/workshop-access/request");
  }, [fork, router, settled]);

  if (!settled || !fork) {
    return <section className="panel p-6 text-sm text-ink-500">Opening workshop access…</section>;
  }

  return (
    <>
      <PageHeader
        title="Workshop access"
        description="Your own standing in a workshop, and — because you are an admin — everybody else's."
        icon={<UsersRound className="h-5 w-5" aria-hidden />}
      />
      <div className="grid gap-3 sm:grid-cols-2">
        {CHOICES.map((choice) => (
          <Link
            className="group flex flex-col gap-2 rounded-lg border border-line-200 bg-card p-5 shadow-sm transition hover:border-purple-300 hover:shadow-md"
            href={choice.href}
            key={choice.href}
          >
            <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
              <choice.icon className="h-5 w-5 text-white" aria-hidden />
            </div>
            <div className="font-display text-base font-bold leading-snug text-ink-900">{choice.label}</div>
            <p className="text-xs leading-5 text-ink-500">{choice.description}</p>
          </Link>
        ))}
      </div>
    </>
  );
}
