"use client";

import { KeyRound } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { ApiKeysPanel } from "@/components/settings/ApiKeysPanel";
import { ProviderOrderPanel } from "@/components/settings/ProviderOrderPanel";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";

/**
 * /settings/api-keys — ADMIN and above, with the page's two halves gated separately.
 *
 * The KEYS themselves stay master-admin: every `/api/secrets` route is behind
 * `require_master_admin`, because handing out live provider credentials (the reveal endpoint
 * returns plaintext) is a different class of power from running workshops.
 *
 * RANKING those same providers is not. Deciding whether a Hindi interview goes to ElevenLabs or to
 * Deepgram first is an editorial judgement about transcription accuracy, and it belongs to the
 * admins who run the workshops — so `/settings/transcription-providers` is behind `require_admin`
 * and the ladder renders for them here, alongside (or instead of) the key list. This is where it
 * was asked for, next to the providers it ranks; nothing about the key VALUES is exposed by it,
 * only whether each engine has one and what the provider said the last time we asked.
 *
 * That second half is why an ADMIN may TEST a key they are not allowed to read. A verdict is not a
 * credential — it is the difference between a ranking that describes what happens to the next
 * recording and one that merely hopes — and an admin who cannot ask "does this actually work?" is
 * being asked to rank engines blind. The value, the last-four hint and the reveal button all stay
 * on the master admin's side of the line, below.
 *
 * Professors (rank 40) are below ADMIN (50) and see neither half — they get the restricted panel.
 *
 * Admin view is the separate, softer gate and is enforced above the page: the route is listed in
 * ADMIN_CHROME_ROUTES, so an admin browsing with admin view off gets AppShell's "hidden while admin
 * view is off" panel instead and this component never mounts. The role guards below stay, because
 * the toggle narrows and never widens.
 */
export default function ApiKeysPage() {
  const { user, loading } = useAuth();
  const admin = isAdmin(user);
  const master = isMasterAdmin(user);

  const header = (
    <PageHeader
      title="API keys"
      description={
        master
          ? "The provider keys the repository runs on — rotate one here and it is live everywhere immediately, without a restart."
          : "Which speech-to-text provider transcribes field recordings first. You can test whether each provider's key works; the keys themselves are held by the master admin."
      }
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

  if (!admin) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body="Provider keys and the transcription provider order are managed by the repository's admins. Ask one of them if a key needs rotating."
        />
      </>
    );
  }

  return (
    <>
      {header}
      <div className="grid gap-4">
        {/* The ranking first: it is the control most admins come here for, and the only one they can
            all use. The key list below it is the master admin's. */}
        <ProviderOrderPanel />
        {master ? <ApiKeysPanel /> : null}
      </div>
    </>
  );
}
