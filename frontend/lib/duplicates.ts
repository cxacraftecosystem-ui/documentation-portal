import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import type { PageResult } from "@/lib/types";

export type DuplicateBand = "VERY_LIKELY" | "LIKELY" | "POSSIBLE";

export type DuplicateReason = { code: string; label: string };

export type DuplicateCandidate = {
  artisan: {
    id: string;
    name: string;
    localName?: string | null;
    place?: string | null;
    state?: string | null;
    district?: string | null;
    craft?: string | null;
    workshop?: string | null;
    birthYear?: number | null;
    photoUrl?: string | null;
    createdAt?: string | null;
  };
  score: number;
  band: DuplicateBand;
  reasons: DuplicateReason[];
};

export type PossibleDuplicateHold = {
  code: "artisan_possible_duplicate";
  message: string;
  candidates: DuplicateCandidate[];
  existingArtisan?: { id: string; name: string; place?: string | null; craft?: string | null };
};

export type DuplicateCheckInput = {
  name: string;
  localName?: string | null;
  gender?: string | null;
  dateOfBirth?: string | null;
  phone?: string | null;
  place?: string | null;
  state?: string | null;
  district?: string | null;
  village?: string | null;
  craftId?: string | null;
  craftName?: string | null;
  aadhaar?: string | null;
  pehchanCardNumber?: string | null;
  excludeArtisanId?: string | null;
};

export const SHOW_THRESHOLD = 0.6;
export const HOLD_THRESHOLD = 0.75;
export const CARD_MAX_ROWS = 3;

export function checkInputFrom(values: DuplicateCheckInput): DuplicateCheckInput | null {
  const name = (values.name ?? "").trim();
  if (name.length < 2) return null;
  const clean = (value: string | null | undefined) => {
    const text = (value ?? "").trim();
    return text ? text : null;
  };
  return {
    name,
    localName: clean(values.localName),
    gender: clean(values.gender),
    dateOfBirth: clean(values.dateOfBirth),
    phone: clean(values.phone),
    place: clean(values.place),
    state: clean(values.state),
    district: clean(values.district),
    village: clean(values.village),
    craftId: clean(values.craftId),
    craftName: clean(values.craftName),
    aadhaar: clean(values.aadhaar),
    pehchanCardNumber: clean(values.pehchanCardNumber),
    excludeArtisanId: clean(values.excludeArtisanId)
  };
}

export function checkKey(input: DuplicateCheckInput | null): string {
  return input ? JSON.stringify(input) : "";
}

export function candidatesToShow(candidates: DuplicateCandidate[], dismissed: Iterable<string> = []): DuplicateCandidate[] {
  const gone = new Set(dismissed);
  return candidates
    .filter((c) => c.score >= SHOW_THRESHOLD && !gone.has(c.artisan.id))
    .sort((a, b) => b.score - a.score)
    .slice(0, CARD_MAX_ROWS);
}

export function acknowledgedIds(dismissed: Iterable<string>): string[] {
  return Array.from(new Set(Array.from(dismissed).filter(Boolean))).slice(0, 20);
}

export function candidateSubtitle(candidate: DuplicateCandidate): string {
  const a = candidate.artisan;
  return [a.place || a.district, a.craft, a.birthYear ? `b. ${a.birthYear}` : null].filter(Boolean).join(" · ");
}

export async function checkDuplicates(input: DuplicateCheckInput): Promise<DuplicateCandidate[]> {
  const result = await apiFetch<{ candidates: DuplicateCandidate[] }>("/artisan-duplicates/check", {
    method: "POST",
    body: JSON.stringify(input)
  });
  return Array.isArray(result?.candidates) ? result.candidates : [];
}

export function possibleDuplicateHold(error: unknown): PossibleDuplicateHold | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  return holdFromPayload(error.payload);
}

export function holdFromPayload(payload: unknown): PossibleDuplicateHold | null {
  if (!payload || typeof payload !== "object" || !("detail" in payload)) return null;
  const detail = (payload as { detail: unknown }).detail;
  if (!detail || typeof detail !== "object") return null;
  const hold = detail as PossibleDuplicateHold;
  return hold.code === "artisan_possible_duplicate" && Array.isArray(hold.candidates) ? hold : null;
}

export type DuplicateStatus = "OPEN" | "NOT_DUPLICATE" | "LINKED";

export type PairSide = {
  id: string;
  name: string;
  place?: string | null;
  district?: string | null;
  craft?: string | null;
  createdAt?: string | null;
};

export type DuplicatePair = {
  id: string;
  kind: "WITHIN" | "CROSS_PORTAL";
  score: number;
  band: DuplicateBand;
  reasons: DuplicateReason[];
  status: DuplicateStatus;
  source: "SCAN" | "CREATE" | "IMPORT";
  creatorSaidDifferent: boolean;
  a: PairSide | null;
  b: PairSide | null;
  remote: { artisanId: string; matchedBy: string } | null;
  createdAt: string;
};

export type PairPage = PageResult<DuplicatePair> & { counts: Record<DuplicateBand, number> };

export function listPairs(params: {
  status?: DuplicateStatus;
  kind?: "WITHIN" | "CROSS_PORTAL";
  band?: DuplicateBand | null;
  artisanId?: string | null;
  page?: number;
  pageSize?: number;
}): Promise<PairPage> {
  return apiFetch<PairPage>(
    `/artisan-duplicates${buildQuery({
      status: params.status ?? "OPEN",
      kind: params.kind,
      band: params.band ?? undefined,
      artisanId: params.artisanId ?? undefined,
      page: params.page ?? 1,
      pageSize: params.pageSize ?? 20
    })}`
  );
}

export function getPair(id: string): Promise<DuplicatePair> {
  return apiFetch<DuplicatePair>(`/artisan-duplicates/${encodeURIComponent(id)}`);
}

export function mergeHref(pair: Pick<DuplicatePair, "a" | "b">): string | null {
  if (!pair.a || !pair.b) return null;
  const older = (pair.a.createdAt ?? "") <= (pair.b.createdAt ?? "");
  const keep = older ? pair.a : pair.b;
  const merge = older ? pair.b : pair.a;
  return `/admin/artisan-merge${buildQuery({ keep: keep.id, merge: merge.id })}`;
}

export function dismiss(id: string, note: string | null = null): Promise<DuplicatePair> {
  return apiFetch(`/artisan-duplicates/${encodeURIComponent(id)}/dismiss`, {
    method: "POST",
    body: JSON.stringify({ note })
  });
}

export function reopen(id: string): Promise<DuplicatePair> {
  return apiFetch(`/artisan-duplicates/${encodeURIComponent(id)}/reopen`, { method: "POST" });
}

export async function openPairFor(artisanId: string): Promise<DuplicatePair | null> {
  const page = await listPairs({ status: "OPEN", artisanId, pageSize: 1 });
  return page.items[0] ?? null;
}

export const BAND_LABEL: Record<DuplicateBand, string> = {
  VERY_LIKELY: "Very likely",
  LIKELY: "Likely",
  POSSIBLE: "Possible"
};
