"use client";

import { useSyncExternalStore } from "react";

/** The words on a library button that needs the server (UNIFY 4.3). */
export const NEEDS_CONNECTION = "Needs a connection";

function subscribe(onChange: () => void): () => void {
  window.addEventListener("online", onChange);
  window.addEventListener("offline", onChange);
  return () => {
    window.removeEventListener("online", onChange);
    window.removeEventListener("offline", onChange);
  };
}

/**
 * Whether the browser says it is online. Workbook upload and download, deletes, restores, sharing,
 * the default and bundles need a connection; their buttons are disabled with "Needs a connection"
 * while this is false. True on the server and the first render, so nothing flashes disabled.
 */
export function useOnline(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => navigator.onLine !== false,
    () => true
  );
}
