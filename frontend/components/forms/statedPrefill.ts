/**
 * C17 (owner decision 2026-10-17): a product, tool or interview opens with its ARTISAN'S stated
 * address in the "Location of …" card — marked with where it came from, and the researcher's to
 * change. Until now that card was never filled automatically, so state, district and village were
 * retyped on every child record (about fifty taps a sitting) and the spellings drifted.
 *
 * The pure half, so the rule is testable without a browser. `LocationFields` holds the React half.
 */

export type StatedAddress = { state: string; district: string; village: string; pincode: string };

/** The record whose stated address is offered — the artisan — named for the "From …" line. */
export type StatedAddressSource = {
  /** Changes when the source changes (the artisan's id), which is what re-offers the address. */
  key: string;
  /** Who it came from, as the line under the heading prints it: "From Ramesh Chhipa". */
  label: string;
  state?: string | null;
  district?: string | null;
  village?: string | null;
  pincode?: string | null;
};

export const EMPTY_STATED: StatedAddress = { state: "", district: "", village: "", pincode: "" };

const FIELDS = ["state", "district", "village", "pincode"] as const;
type StatedField = (typeof FIELDS)[number];

export function statedFromSource(source: StatedAddressSource): StatedAddress {
  return {
    state: (source.state ?? "").trim(),
    district: (source.district ?? "").trim(),
    village: (source.village ?? "").trim(),
    pincode: (source.pincode ?? "").replace(/\D/g, "").slice(0, 6)
  };
}

/**
 * The address to write, or null when the card must be left alone.
 *
 * A field may be written when it is EMPTY, when it still holds exactly what the LAST prefill wrote
 * (the researcher picked a different artisan), or when only this device's own passive GPS guess put
 * it there (`deviceFilled`) — an artisan's stated address is a better answer about the subject than
 * where the phone happened to be. A field a person typed or chose blocks the whole prefill: the card
 * never mixes one artisan's village with another person's district.
 */
export function statedPrefillFor(
  source: StatedAddressSource | null | undefined,
  current: StatedAddress,
  lastPrefill: StatedAddress | null,
  deviceFilled: readonly string[] = []
): StatedAddress | null {
  if (!source) return null;
  const offered = statedFromSource(source);
  if (FIELDS.every((field) => !offered[field])) return null;
  const free = (field: StatedField) =>
    !current[field] ||
    (lastPrefill !== null && current[field] === lastPrefill[field]) ||
    deviceFilled.includes(field);
  if (!FIELDS.every(free)) return null;
  if (FIELDS.every((field) => current[field] === offered[field])) return null;
  return offered;
}

/** True while every box still holds what the prefill wrote — the "From …" line is honest only then. */
export function prefillStillStands(current: StatedAddress, prefill: StatedAddress | null): boolean {
  return prefill !== null && FIELDS.every((field) => current[field] === prefill[field]);
}

/** The stated address of an artisan record as the source this card takes. */
export function artisanStatedSource(
  artisan:
    | {
        id: string;
        name?: string | null;
        location?: { state?: string | null; district?: string | null; village?: string | null; pincode?: string | null } | null;
      }
    | null
    | undefined
): StatedAddressSource | null {
  if (!artisan?.location) return null;
  const { state, district, village, pincode } = artisan.location;
  if (!state && !district && !village && !pincode) return null;
  return { key: artisan.id, label: artisan.name?.trim() || "the artisan", state, district, village, pincode };
}
