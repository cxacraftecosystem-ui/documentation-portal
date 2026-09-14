/**
 * Hand a file this page made in memory to the browser's own download machinery.
 *
 * ── WHY A FIFTH COPY OF AN ANCHOR CLICK IS BEING ADDED TO THIS REPOSITORY ──────────────────────
 *
 * It is not, quite. There are already four — `DownloadCsvButton.tsx`, `MediaLightbox.saveToDevice`,
 * `questionnaires/workbookApi.ts`, and the CSV path in the dataset pages — and every one of them
 * exists to save a file the SERVER produced: they fetch a URL, get a blob, and click an anchor at it.
 * None of them is callable with a `File` that never had a URL, which is the only kind this panel
 * makes, so the choice was between a fifth copy and threading a new argument through a file another
 * workstream is editing right now.
 *
 * ── THE ONE LINE THAT IS DIFFERENT, AND IT IS A BUG FIX RATHER THAN A PREFERENCE ───────────────
 *
 * **The object URL is revoked on the NEXT TASK, not in the same tick as the synthetic click.**
 * `MediaLightbox.saveToDevice` calls `URL.revokeObjectURL(objectUrl)` on the line after
 * `anchor.click()`, which races the browser's own read of that URL — and Safari in particular ends
 * up downloading nothing at all, with no error anywhere on the page or in the console. A
 * `setTimeout(…, 0)` puts the revoke after the browser has taken the bytes.
 *
 * That is a real defect in an existing file and this port may not edit it, so the fix lives here with
 * the reason attached rather than being discovered again by whoever next writes one of these. If the
 * shared helper is ever hardened, delete this and call it.
 *
 * ── WHAT THIS FUNCTION IS NOT ─────────────────────────────────────────────────────────────────
 *
 * It is not an upload and it is not an attach. Nothing it does reaches the record, the upload queue
 * or the offline draft store; the only thing that outlives the call is a file in the reader's own
 * downloads folder. `TracePanel`'s first property depends on that being true of every path out of
 * this feature except `onAttach`.
 */
export function saveBlobToDisk(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}
