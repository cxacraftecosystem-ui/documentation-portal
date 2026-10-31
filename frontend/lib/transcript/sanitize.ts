/**
 * What the sanitised renderer lets through as a link target: http(s) and mailto only. Anything else
 * (`javascript:`, `data:`, `vbscript:`, a relative path) becomes "" and the link is drawn as text.
 */
export const SAFE_URL = /^(https?:|mailto:)/i;

export function safeUrl(url: string): string {
  const trimmed = url.trim();
  return SAFE_URL.test(trimmed) ? trimmed : "";
}

/** Elements the renderer refuses outright (an image would fetch a URL of the writer's choosing). */
export const DISALLOWED_ELEMENTS = ["img"];
