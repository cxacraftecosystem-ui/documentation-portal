/**
 * Hand a blob this page holds in memory to the browser's own download machinery.
 *
 * ── WHY THIS EXISTS AT ALL, WHEN THE ANCHOR CLICK IS FOUR LINES ───────────────────────────────
 *
 * Because those four lines were written SEVEN times in this codebase and six of them carried the
 * same bug. `MediaLightbox`, `DownloadCsvButton`, `questionnaires/workbookApi`, both dataset export
 * paths on the data page, the folder report, and the shared-data zip on the sharing page each built
 * an object URL, clicked an anchor at it, and revoked the URL ON THE NEXT LINE.
 *
 * ── THE BUG, WHICH IS SILENT AND PLATFORM-DEPENDENT ───────────────────────────────────────────
 *
 * `anchor.click()` only SCHEDULES the download. The browser reads the object URL afterwards, so
 * revoking it synchronously is a race against that read. Chrome usually wins the race, which is
 * exactly what makes this so easy to ship and so hard to notice — and SAFARI USUALLY LOSES IT,
 * downloading nothing at all with NO ERROR ANYWHERE: no rejected promise, no console entry, nothing
 * a `catch` can reach. The researcher presses Save, and the file simply does not arrive. There is no
 * failure to report, so nobody reports one; it reads as "the export button doesn't work on my Mac".
 *
 * Found on 2026-09-14 while porting the offline tracer, whose own export path would have been the
 * seventh copy.
 *
 * ── THE FIX, AND WHY IT IS A TIMEOUT AND NOT A DELETION ───────────────────────────────────────
 *
 * The revoke moves to a later task. A zero delay is enough: it only has to land after the current
 * one, by which point the download has been handed to the browser. Dropping the revoke entirely
 * would also fix the download and would leak the blob — a dataset zip can be hundreds of megabytes —
 * for the life of the document, so the URL is still released, just not while it is being read.
 *
 * ── WHAT THIS IS NOT ──────────────────────────────────────────────────────────────────────────
 *
 * It is not an upload and it is not an attach. Nothing here reaches a record, the upload queue or
 * the offline draft store; the only thing that outlives the call is a file in the reader's own
 * downloads folder. The tracer's first property — that the only way anything leaves that panel for
 * the product is `onAttach` — depends on that being true of this function.
 */
export function saveBlobToDevice(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  saveUrlToDevice(url, fileName);
  // ⚠ NOT `URL.revokeObjectURL(url)` ON THIS LINE. See the header: that is the race, and it is the
  // reason this module exists.
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

/**
 * The anchor click itself, for a URL the caller owns and will release (or never needs to).
 *
 * Separated so {@link saveBlobToDevice} has one place to put the revoke and callers with a plain
 * https URL — the CORS fallback in `MediaLightbox`, say — are not handed an object-URL lifetime they
 * did not ask for.
 *
 * `download` is a HINT, not a guarantee: a cross-origin URL ignores it and the browser navigates
 * instead, which is why the callers that can fetch the bytes first do exactly that and pass a blob.
 */
export function saveUrlToDevice(url: string, fileName: string, options?: { newTab?: boolean }): void {
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName || "download";
  if (options?.newTab) {
    anchor.target = "_blank";
    anchor.rel = "noreferrer";
  }
  // APPENDED BEFORE THE CLICK, and removed after. Firefox has historically ignored a synthetic click
  // on an anchor that is not in the document, and an element removed in the same tick is still fine
  // because the click has already been dispatched by then.
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}
