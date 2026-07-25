"use client";

/**
 * "Update required", the web counterpart of the Android non-dismissable update prompt.
 *
 * Android needs one because an installed APK can go on running against an API that has moved: the
 * user is genuinely stuck until they install the new build, so that dialog has no "Later", ignores
 * back, and ignores taps outside.
 *
 * The web looks like it needs no such thing — a reload always fetches the current build — but there
 * is one case where it does, and it is the same case: a tab that has been open across a deploy is
 * running code whose chunks no longer exist on the origin. The moment it lazy-loads anything it has
 * not already downloaded (a route, a dialog, a map) the import rejects and the interaction simply
 * dies with nothing on screen. That is exactly "this build can no longer function", so it gets
 * exactly the Android treatment: a dialog with one way out, Reload, and no dismissal at all.
 *
 * The trigger is the failure itself rather than a version poll, which keeps this honest — the prompt
 * appears when the stale build has actually broken, never speculatively.
 */

import { RefreshCw } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";

/** Signatures every browser uses for "a JS chunk that should be there is not". */
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

/** The dialog on its own, for callers that already know the build is stale. */
export function AppUpdateDialog({ open, reloading, onReload }: { open: boolean; reloading: boolean; onReload: () => void }) {
  return (
    <FieldDialog
      open={open}
      // Non-dismissable: there is nothing useful the user can do in a broken build, so onClose is
      // deliberately inert and `dismissible={false}` removes Escape, the close button and the backdrop.
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
          A new version of Field Repository has been deployed since this tab was opened, and this one can
          no longer load parts of the app. Reload to continue.
        </>
      }
      footer={
        <button type="button" className="field-button" disabled={reloading} onClick={onReload}>
          {reloading ? "Reloading…" : "Reload now"}
        </button>
      }
    >
      <p className="mt-2 text-sm leading-6 text-ink-500">
        Anything already saved is safe. Text still sitting in an open form is not — copy it out first if you
        need it.
      </p>
    </FieldDialog>
  );
}

/**
 * Watches for the stale-build failure and raises {@link AppUpdateDialog}. Mounted once, in the
 * protected layout.
 */
export function AppUpdateWatcher() {
  const [stale, setStale] = useState(false);
  const [reloading, setReloading] = useState(false);
  // Once raised it stays raised; a second chunk failure must not restart the dialog's entrance.
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
