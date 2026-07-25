import { AppShell } from "@/components/AppShell";
import { AppUpdateWatcher } from "@/components/dialogs/AppUpdateDialog";
import { ConfirmProvider } from "@/components/dialogs/ConfirmDialog";
import { OfflineWatcher } from "@/components/dialogs/OfflineDialog";
import { OutboxBanner } from "@/components/OutboxBanner";

/**
 * Every protected page renders inside AppShell, which owns the two things that must apply to all of
 * them: the sign-in redirect, and the route guards declared in lib/permissions.ts (ROUTE_GUARDS).
 * Enforcing access here rather than page by page is why a direct URL to /users, /review, /data or a
 * create form cannot render content the API would refuse.
 *
 * It is also where the app-wide dialogs are mounted, once each — same reasoning as the single
 * ToastProvider in the root layout:
 *
 * - `ConfirmProvider` gives every page below it `useConfirm()`, the themed replacement for
 *   `window.confirm()`. A second, nested provider would shadow this one and open its dialog behind
 *   whatever the outer one had already put on screen, so pages must never mount their own.
 * - `AppUpdateWatcher` raises the non-dismissable "Update required" prompt when this tab's build has
 *   gone stale across a deploy — the web's version of the Android required-update dialog.
 * - `OfflineWatcher` announces a lost connection the moment it drops, so a researcher does not fill
 *   in a long form before discovering it at the Save button.
 * - `OutboxBanner` is the other half of that: saves made offline go to the IndexedDB outbox
 *   (`lib/offline`), and this is where they are visible and where they drain. It renders inside the
 *   shell, above the page, because an outbox nobody can see is worse than no outbox — the researcher
 *   believes the record is filed when it is sitting in one laptop's browser storage.
 *
 * All four live here rather than in the root layout because they are for signed-in work: the login
 * and landing pages have nothing to confirm, nothing to save offline, and no lazy chunks to fail on.
 */
export default function ProtectedLayout({ children }: { children: React.ReactNode }) {
  return (
    <ConfirmProvider>
      <AppShell>
        <OutboxBanner />
        {children}
      </AppShell>
      <AppUpdateWatcher />
      <OfflineWatcher />
    </ConfirmProvider>
  );
}
