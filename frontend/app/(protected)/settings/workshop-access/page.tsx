"use client";

import { UsersRound } from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { WorkshopAccessQueuePanel } from "@/components/settings/WorkshopAccessQueuePanel";
import { WorkshopRosterPanel } from "@/components/settings/WorkshopRosterPanel";
import { isAdmin } from "@/lib/permissions";

/**
 * /settings/workshop-access — ADMIN and MASTER ADMIN.
 *
 * The two halves of the same conversation on one screen: the cross-workshop queue of people ASKING,
 * and the per-workshop roster of people who HAVE it. They share one WorkshopAssignment row per
 * (workshop, user), so approving in the queue changes what the roster shows and vice versa —
 * `refreshToken` is bumped by either panel to make the other re-read rather than go stale.
 *
 * Granting access is an admin act, so the route is admin chrome (ADMIN_CHROME_ROUTES): with admin
 * view off AppShell replaces it with the "hidden while admin view is off" panel, which points at
 * /settings — where ASKING for access stays open to everyone, admin view or not. The `isAdmin`
 * guard below is untouched by the toggle.
 */
export default function WorkshopAccessPage() {
  const { user, loading } = useAuth();
  const [refreshToken, setRefreshToken] = useState(0);

  const header = (
    <PageHeader
      title="Workshop access"
      description="Approve or decline requests to work in a workshop, and manage each workshop's roster and access levels."
      icon={<UsersRound className="h-5 w-5" aria-hidden />}
    />
  );

  if (loading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }

  if (!isAdmin(user)) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body="Only admins and the master admin can grant workshop access. You can ask for access to a workshop from Settings."
        />
      </>
    );
  }

  const bump = () => setRefreshToken((current) => current + 1);

  return (
    <>
      {header}
      <div className="grid gap-4">
        <WorkshopAccessQueuePanel onChanged={bump} refreshToken={refreshToken} />
        <WorkshopRosterPanel onChanged={bump} refreshToken={refreshToken} />
      </div>
    </>
  );
}
