"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { KeyRound, ShieldCheck, UsersRound, type LucideIcon } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { isAdmin } from "@/lib/permissions";

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
      "Request access and track your requests.",
    icon: KeyRound
  },
  {
    href: "/workshop-access/manage",
    label: "Manage workshop access",
    description:
      "Requests and rosters.",
    icon: ShieldCheck
  }
];

export default function WorkshopAccessPage() {
  const { user, loading } = useAuth();
  const { adminMode, adminViewResolved } = useAdminView();
  const router = useRouter();

  const fork = isAdmin(user) && adminMode;
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
        description="Access for you and others."
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
