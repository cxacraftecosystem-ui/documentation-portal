export type StatedAddress = { state: string; district: string; village: string; pincode: string };

export type StatedAddressSource = {
  key: string;
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

export function prefillStillStands(current: StatedAddress, prefill: StatedAddress | null): boolean {
  return prefill !== null && FIELDS.every((field) => current[field] === prefill[field]);
}

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
