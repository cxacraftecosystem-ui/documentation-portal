export const SAFE_URL = /^(https?:|mailto:)/i;

export function safeUrl(url: string): string {
  const trimmed = url.trim();
  return SAFE_URL.test(trimmed) ? trimmed : "";
}

export const DISALLOWED_ELEMENTS = ["img"];
