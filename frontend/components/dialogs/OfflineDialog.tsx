"use client";

/**
 * "You are offline" — the web's answer to the Android offline outbox.
 *
 * The web now has the same outbox the Android app has always had (`lib/offline`), so a save made
 * with no signal is queued on the device and sent when the connection returns. This dialog is the
 * announcement, not the mechanism: the moment the connection drops the researcher is told, rather
 * than discovering it at the Save button after twenty minutes of typing.
 *
 * What it must NOT do is imply the work is lost. It says what is true: the form is intact, saving
 * queues instead of failing, and the things that genuinely cannot work offline — uploading a file
 * that is not attached to a queued save, and searching the repository — will not work.
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
          This device has lost its connection. Anything you have already typed stays on screen, and saving
          still works &mdash; the entry is held on this device and sent the moment the connection returns.
        </>
      }
      footer={
        <button type="button" className="field-button" onClick={onDismiss}>
          Continue offline
        </button>
      }
    >
      <p className="mt-2 text-sm leading-6 text-ink-500">
        Queued entries are listed at the top of the page until they send, and they live in THIS browser
        &mdash; do not clear its data or hand the laptop on while the outbox still has something in it.
        Searching the repository and opening records you have not already loaded still need a connection.
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
