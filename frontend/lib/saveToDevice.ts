export const OBJECT_URL_LIFETIME_MS = 60_000;

export function saveBlobToDevice(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  saveUrlToDevice(url, fileName);
  window.setTimeout(() => URL.revokeObjectURL(url), OBJECT_URL_LIFETIME_MS);
}

export function saveUrlToDevice(url: string, fileName: string, options?: { newTab?: boolean }): void {
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName || "download";
  if (options?.newTab) {
    anchor.target = "_blank";
    anchor.rel = "noreferrer";
  }
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}
