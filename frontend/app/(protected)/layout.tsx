import { AppShell } from "@/components/AppShell";
import { AppUpdateWatcher } from "@/components/dialogs/AppUpdateDialog";
import { ConfirmProvider } from "@/components/dialogs/ConfirmDialog";
import { OfflineWatcher } from "@/components/dialogs/OfflineDialog";
import { OutboxBanner } from "@/components/OutboxBanner";
import { UnsavedChangesProvider } from "@/components/UnsavedChangesGuard";

export default function ProtectedLayout({ children }: { children: React.ReactNode }) {
  return (
    <ConfirmProvider>
      <UnsavedChangesProvider>
        <AppShell>
          <OutboxBanner />
          {children}
        </AppShell>
      </UnsavedChangesProvider>
      <AppUpdateWatcher />
      <OfflineWatcher />
    </ConfirmProvider>
  );
}
