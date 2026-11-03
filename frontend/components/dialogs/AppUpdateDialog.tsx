"use client";

import { RefreshCw } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";

const STALE_BUILD_PATTERNS = [
  "chunkloaderror",
  "loading chunk",
  "loading css chunk",
  "failed to fetch dynamically imported module",
  "error loading dynamically imported module",
  "importing a module script failed"
];

function looksLikeStaleBuild(value: unknown): boolean {
  const message =
    value instanceof Error
      ? `${value.name} ${value.message}`
      : typeof value === "string"
        ? value
        : "";
  if (!message) return false;
  const lower = message.toLowerCase();
  return STALE_BUILD_PATTERNS.some((pattern) => lower.includes(pattern));
}

export function AppUpdateDialog({ open, reloading, onReload }: { open: boolean; reloading: boolean; onReload: () => void }) {
  return (
    <FieldDialog
      open={open}
      onClose={() => undefined}
      dismissible={false}
      dismissOnBackdrop={false}
      role="alertdialog"
      tone="warning"
      icon={<RefreshCw className="h-4 w-4" aria-hidden />}
      busy={reloading}
      zIndex={108}
      title="Update required"
      description={
        <>
          A new version of Field Repository is available. Reload to continue.
        </>
      }
      footer={
        <button type="button" className="field-button" disabled={reloading} onClick={onReload}>
          {reloading ? "Reloading…" : "Reload now"}
        </button>
      }
    >
      <p className="mt-2 text-sm leading-6 text-ink-500">
        Saved work is safe. Copy any unsaved text first.
      </p>
    </FieldDialog>
  );
}

export function AppUpdateWatcher() {
  const [stale, setStale] = useState(false);
  const [reloading, setReloading] = useState(false);
  const raised = useRef(false);

  useEffect(() => {
    function raise() {
      if (raised.current) return;
      raised.current = true;
      setStale(true);
    }
    function onError(event: ErrorEvent) {
      if (looksLikeStaleBuild(event.error) || looksLikeStaleBuild(event.message)) raise();
    }
    function onRejection(event: PromiseRejectionEvent) {
      if (looksLikeStaleBuild(event.reason)) raise();
    }
    window.addEventListener("error", onError);
    window.addEventListener("unhandledrejection", onRejection);
    return () => {
      window.removeEventListener("error", onError);
      window.removeEventListener("unhandledrejection", onRejection);
    };
  }, []);

  return (
    <AppUpdateDialog
      open={stale}
      reloading={reloading}
      onReload={() => {
        setReloading(true);
        window.location.reload();
      }}
    />
  );
}
