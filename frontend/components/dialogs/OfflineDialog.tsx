"use client";

/**
 * "You are offline" — the web's answer to the Android offline outbox.
 *
 * The Android app queues a save to a local outbox when the network is gone and drains it when the
 * network returns, so a researcher in a village with no signal keeps working and finds out nothing.
 * The web has no outbox: a submit made while offline just fails, and until now it failed with a
 * generic red error at the bottom of the form that reads like the server rejected the record.
 *
 * A page cannot grow an outbox from a dialog, so this closes the honesty gap instead of pretending
 * the gap is not there. The moment the connection drops the researcher is told, in the same breath,
 * what does and does not survive: the form on screen is intact, saving is what will not work, and
 * the Android app is the tool that can queue. Silence would let them keep filling in a long form and
 * discover all of it at the Save button.
 *
 * It is dismissable — someone who knows they are offline and wants to keep typing must be able to get
 * the dialog out of the way — and it re-arms on the next disconnect, not on every render.
 */

import { CloudOff } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { useToast } from "@/components/ui/Toast";

export function OfflineDialog({ open, onDismiss }: { open: boolean; onDismiss: () => void }) {
  return (
    <FieldDialog
      open={open}
      onClose={onDismiss}
      role="alertdialog"
      tone="warning"
      icon={<CloudOff className="h-4 w-4" aria-hidden />}
      // Warning, not danger: a backdrop click is a fine way to say "yes, I know".
      dismissOnBackdrop
      zIndex={106}
      title="You are offline"
      description={
        <>
          This device has lost its connection. Anything you have already typed stays on screen, but saving,
          uploading and searching will fail until the connection comes back.
        </>
      }
      footer={
        <button type="button" className="field-button" onClick={onDismiss}>
          Continue offline
        </button>
      }
    >
      <p className="mt-2 text-sm leading-6 text-ink-500">
        The web app cannot queue a save for later. If you are documenting somewhere with no signal, use the
        Android app — it keeps an offline outbox and uploads everything once you are back in range.
      </p>
    </FieldDialog>
  );
}

/** Raises {@link OfflineDialog} on disconnect and confirms recovery with a toast. Mounted once. */
export function OfflineWatcher() {
  const [open, setOpen] = useState(false);
  const { toast } = useToast();
  // Only announce "back online" to someone who was told they went offline in the first place.
  const wasOffline = useRef(false);

  const goOffline = useCallback(() => {
    wasOffline.current = true;
    setOpen(true);
  }, []);

  const goOnline = useCallback(() => {
    setOpen(false);
    if (!wasOffline.current) return;
    wasOffline.current = false;
    toast({ id: "connection", title: "Back online", description: "Saving and uploading work again.", tone: "success" });
  }, [toast]);

  useEffect(() => {
    // A client-side navigation can land on a protected page while already offline, so check once on
    // mount as well as listening for the transition.
    if (typeof navigator !== "undefined" && navigator.onLine === false) goOffline();
    window.addEventListener("offline", goOffline);
    window.addEventListener("online", goOnline);
    return () => {
      window.removeEventListener("offline", goOffline);
      window.removeEventListener("online", goOnline);
    };
  }, [goOffline, goOnline]);

  return <OfflineDialog open={open} onDismiss={() => setOpen(false)} />;
}
