"use client";

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
      dismissOnBackdrop
      zIndex={106}
      title="You are offline"
      description={
        <>
          You can keep saving — entries send when you’re back online.
        </>
      }
      footer={
        <button type="button" className="field-button" onClick={onDismiss}>
          Continue offline
        </button>
      }
    >
      <p className="mt-2 text-sm leading-6 text-ink-500">
        Queued entries live in this browser — don’t clear its data until they’ve sent.
      </p>
    </FieldDialog>
  );
}

export function OfflineWatcher() {
  const [open, setOpen] = useState(false);
  const { toast } = useToast();
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
