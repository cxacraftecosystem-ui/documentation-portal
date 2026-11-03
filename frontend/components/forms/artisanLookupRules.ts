export type ArtisanLookupStart = { name: string; aadhaar: string };

export function lookupKind(query: string): "aadhaar" | "name" | "empty" {
  const trimmed = query.trim();
  if (!trimmed) return "empty";
  return /^[\d\s]{12,14}$/.test(trimmed) && trimmed.replace(/\s/g, "").length === 12 ? "aadhaar" : "name";
}

export function lookupStart(query: string): ArtisanLookupStart {
  const kind = lookupKind(query);
  if (kind === "aadhaar") return { name: "", aadhaar: query.replace(/\s/g, "") };
  if (kind === "name") return { name: query.trim(), aadhaar: "" };
  return { name: "", aadhaar: "" };
}
