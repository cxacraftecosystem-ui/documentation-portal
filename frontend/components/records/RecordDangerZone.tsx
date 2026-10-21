"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Trash2 } from "lucide-react";
import { useAuth } from "@/components/AuthProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { DANGER_ZONE, dangerZoneVisible } from "@/components/records/dangerZoneRules";
import { apiFetch } from "@/lib/api";

/**
 * The last card on a record's page: its Delete, behind the app's red confirm. Byte-identical in
 * both portals; the words and the gate are `dangerZoneRules.ts`. Draws nothing for a reader who may
 * not delete.
 */
export function RecordDangerZone({
  noun,
  endpoint,
  afterHref,
  body,
  confirmBody
}: {
  /** The record's kind as a reader says it — "artisan", "file". */
  noun: string;
  /** The record's own route, e.g. `/artisans/{id}`; Delete is a `DELETE` to it. */
  endpoint: string;
  /** Where to go once it is gone — the list it came from. */
  afterHref: string;
  /** In place of "Permanently deletes…", for a record that is not deleted permanently. */
  body?: string;
  /** In place of the confirm's "This permanently deletes the record." */
  confirmBody?: string;
}) {
  const { user } = useAuth();
  const confirm = useConfirm();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!dangerZoneVisible(user)) return null;

  async function remove() {
    const ok = await confirm(deleteConfirm(DANGER_ZONE.confirmTitle(noun), confirmBody ?? DANGER_ZONE.confirmBody));
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      await apiFetch(endpoint, { method: "DELETE" });
      router.replace(afterHref);
    } catch (err) {
      setBusy(false);
      setError(err instanceof Error && err.message ? err.message : DANGER_ZONE.failed);
    }
  }

  return (
    <section aria-labelledby="record-danger-zone" className="panel grid gap-3 border-red-200 p-4 dark:border-red-400/40">
      <h2 id="record-danger-zone" className="display-title text-lg">
        {DANGER_ZONE.title}
      </h2>
      <p className="text-sm text-ink-500">{body ?? DANGER_ZONE.body(noun)}</p>
      {error ? (
        <p role="alert" className="text-sm text-red-700 dark:text-red-400">
          {error}
        </p>
      ) : null}
      {/* One button, centred — the button-row rule, written out so this file is identical in both portals. */}
      <div className="flex justify-center">
        <button type="button" className="field-danger" data-danger onClick={remove} disabled={busy}>
          <Trash2 className="h-4 w-4" aria-hidden />
          {busy ? DANGER_ZONE.deleting : DANGER_ZONE.button(noun)}
        </button>
      </div>
    </section>
  );
}
