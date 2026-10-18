/**
 * A9 — WHERE A MISCELLANEOUS-MEDIA UPLOAD GOES, before anybody picks.
 *
 * The page reset its record type and entry after every batch and ignored the record being worked
 * on, so each batch began with two picks. Now it opens on, in order: the record named in the link
 * (`?linkedType=product&linkedId=…`, which every record's "Add media" can hand it), else the
 * narrowest record the researcher was last working in (the carry-forward bag), else nothing. After a
 * batch the choice stays as it was.
 */
export type MediaTarget = { type: string; id: string; label: string | null };

type CarryLike = {
  artisanId?: string | null;
  artisanName?: string | null;
  craftId?: string | null;
  craftName?: string | null;
  productId?: string | null;
  productName?: string | null;
  toolId?: string | null;
  toolName?: string | null;
  processId?: string | null;
  processName?: string | null;
  workshopId?: string | null;
  workshopName?: string | null;
} | null;

const KNOWN_TYPES = new Set(["artisan", "workshop", "craft", "tool", "product", "process", "questionnaire", "media"]);

export function initialMediaTarget(params: URLSearchParams | null, carry: CarryLike): MediaTarget | null {
  const type = params?.get("linkedType") ?? "";
  if (KNOWN_TYPES.has(type)) return { type, id: params?.get("linkedId") ?? "", label: null };
  if (!carry) return null;
  const order: Array<[string, string | null | undefined, string | null | undefined]> = [
    ["process", carry.processId, carry.processName],
    ["tool", carry.toolId, carry.toolName],
    ["product", carry.productId, carry.productName],
    ["artisan", carry.artisanId, carry.artisanName],
    ["craft", carry.craftId, carry.craftName],
    ["workshop", carry.workshopId, carry.workshopName]
  ];
  for (const [kind, id, label] of order) if (id) return { type: kind, id, label: label ?? null };
  return null;
}

/** The originals of the files a renamed batch reported failed — so a retry sends only those. */
export function failedOriginals(originals: readonly File[], renamed: readonly File[], failedNames: readonly string[]): File[] {
  const failed = new Set(failedNames);
  return originals.filter((_, index) => renamed[index] && failed.has(renamed[index].name));
}
