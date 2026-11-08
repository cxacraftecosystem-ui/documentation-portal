"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { UsersRound } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { WorkshopAccessQueuePanel } from "@/components/settings/WorkshopAccessQueuePanel";
import { WorkshopRosterPanel } from "@/components/settings/WorkshopRosterPanel";
import { routeRedirectFor } from "@/lib/permissions";

export default function WorkshopAccessManagePage() {
  const { user, loading } = useAuth();
  const router = useRouter();
  const [refreshToken, setRefreshToken] = useState(0);

  const redirect = loading ? null : routeRedirectFor(user, "/workshop-access/manage");

  useEffect(() => {
    if (redirect) router.replace(redirect);
  }, [redirect, router]);

  if (loading || redirect) {
    return <section className="panel p-6 text-sm text-ink-500">Opening workshop access…</section>;
  }

  const bump = () => setRefreshToken((current) => current + 1);

  return (
    <>
      <PageHeader
        title="Manage workshop access"
        description="Requests and rosters."
        icon={<UsersRound className="h-5 w-5" aria-hidden />}
      />
      <div className="grid min-w-0 grid-cols-[minmax(0,1fr)] gap-4">
        <WorkshopAccessQueuePanel onChanged={bump} refreshToken={refreshToken} />
        <WorkshopRosterPanel onChanged={bump} refreshToken={refreshToken} />
      </div>
    </>
  );
}
