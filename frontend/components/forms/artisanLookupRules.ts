/** C18 — the lookup-first rules for a new artisan, pure so they are tested without a browser. */

/** What the lookup hands the form when the researcher goes on to record a new artisan. */
export type ArtisanLookupStart = { name: string; aadhaar: string };

/**
 * Twelve digits (spaces allowed) are an Aadhaar number; anything else is a name to search for.
 * Pure, so the rule is tested without a browser.
 */
export function lookupKind(query: string): "aadhaar" | "name" | "empty" {
  const trimmed = query.trim();
  if (!trimmed) return "empty";
  return /^[\d\s]{12,14}$/.test(trimmed) && trimmed.replace(/\s/g, "").length === 12 ? "aadhaar" : "name";
}

/** The start a "Record a new artisan" carries: the typed name, or the typed Aadhaar digits. */
export function lookupStart(query: string): ArtisanLookupStart {
  const kind = lookupKind(query);
  if (kind === "aadhaar") return { name: "", aadhaar: query.replace(/\s/g, "") };
  if (kind === "name") return { name: query.trim(), aadhaar: "" };
  return { name: "", aadhaar: "" };
}
