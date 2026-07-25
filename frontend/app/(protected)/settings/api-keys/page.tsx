"use client";

import { KeyRound } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { ApiKeysPanel } from "@/components/settings/ApiKeysPanel";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { isMasterAdmin } from "@/lib/permissions";

/**
 * /settings/api-keys — MASTER ADMIN ONLY.
 *
 * Every `/api/secrets` route is behind `require_master_admin`, not `require_admin`: ordinary admins
 * manage people and records, while handing out live provider credentials (the reveal endpoint returns
 * plaintext) is a different class of power. The link to this page is hidden from everybody else, and
 * this guard catches the bookmark.
 *
 * Admin view is the separate, softer gate and is enforced above the page: the route is listed in
 * ADMIN_CHROME_ROUTES, so a master admin browsing with admin view off gets AppShell's "hidden while
 * admin view is off" panel instead and this component never mounts. The role guard below stays,
 * because the toggle narrows and never widens.
 */
export default function ApiKeysPage() {
  const { user, loading } = useAuth();

  const header = (
    <PageHeader
      title="API keys"
      description="The provider keys the repository runs on — rotate one here and it is live everywhere immediately, without a restart."
      icon={<KeyRound className="h-5 w-5" aria-hidden />}
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

  if (!isMasterAdmin(user)) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Master admin access required"
          body="Provider keys can only be read or changed by the master admin. Ask them if a key needs rotating."
        />
      </>
    );
  }

  return (
    <>
      {header}
      <ApiKeysPanel />
    </>
  );
}
