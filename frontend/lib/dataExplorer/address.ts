export type AskedAddress = { search: string; from: string } | null;

let asked: AskedAddress = null;

function liveSearch(): string {
  return typeof window === "undefined" ? "" : window.location.search.replace(/^\?/, "");
}

export function searchOf(href: string): string {
  const at = href.indexOf("?");
  if (at < 0) return "";
  const hash = href.indexOf("#", at);
  return href.slice(at + 1, hash < 0 ? undefined : hash);
}

export function settleAddress(pending: AskedAddress, live: string): { pending: AskedAddress; search: string } {
  if (pending && live === pending.from) return { pending, search: pending.search };
  return { pending: null, search: live };
}

export function addressSearch(fallback = ""): string {
  if (typeof window === "undefined") return fallback.replace(/^\?/, "");
  const settled = settleAddress(asked, liveSearch());
  asked = settled.pending;
  return settled.search;
}

export function askAddress(href: string): string {
  if (typeof window !== "undefined") asked = { search: searchOf(href), from: liveSearch() };
  return href;
}
