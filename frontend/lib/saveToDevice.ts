export const OBJECT_URL_LIFETIME_MS = 60_000;

export function saveBlobToDevice(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  saveUrlToDevice(url, fileName);
  window.setTimeout(() => URL.revokeObjectURL(url), OBJECT_URL_LIFETIME_MS);
}

const SAFE_DOWNLOAD_PROTOCOLS = new Set(["http:", "https:", "blob:"]);

export function isSafeDownloadUrl(url: string): boolean {
  try {
    return SAFE_DOWNLOAD_PROTOCOLS.has(new URL(url.trim(), "https://portal.invalid").protocol);
  } catch {
    return false;
  }
}

export function saveUrlToDevice(url: string, fileName: string, options?: { newTab?: boolean }): void {
  if (!isSafeDownloadUrl(url)) return;
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
