/**
 * COPY THAT WORKS ON A LAPTOP AND ON A PHONE.
 *
 * The async Clipboard API first. It is missing on plain http, refused inside some embedded browsers,
 * and on older iOS Safari it rejects outside a direct tap, so the fallback is the selection route:
 * a read-only, off-screen textarea, focused and selected (with `setSelectionRange`, which iOS needs
 * on a read-only field) and copied with `execCommand("copy")`. The caller says "Copied" only when one of them
 * reported success.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== "undefined" && navigator.clipboard && typeof window !== "undefined" && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // fall through to the selection route
  }
  return copyBySelection(text);
}

export function copyBySelection(text: string): boolean {
  if (typeof document === "undefined") return false;
  const active = document.activeElement as HTMLElement | null;
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.setAttribute("aria-hidden", "true");
  area.style.position = "fixed";
  area.style.top = "0";
  area.style.left = "-9999px";
  area.style.opacity = "0";
  // 16px keeps iOS from zooming the page when the field takes the selection.
  area.style.fontSize = "16px";
  document.body.appendChild(area);
  let ok = false;
  try {
    // Focus, then select: `execCommand("copy")` copies the FOCUSED field's selection, and iOS
    // honours only `setSelectionRange` on a read-only field (clipboard.js's sequence).
    area.focus({ preventScroll: true });
    area.select();
    area.setSelectionRange(0, area.value.length);
    ok = document.execCommand("copy");
    window.getSelection()?.removeAllRanges();
  } catch {
    ok = false;
  } finally {
    area.remove();
    active?.focus?.({ preventScroll: true });
  }
  return ok;
}
