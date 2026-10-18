/**
 * The duplicate-artisan finder, as the web sees it.
 *
 * THE SERVER IS THE ONLY SCORER (`backend/app/services/artisan_duplicates.py`). This module never
 * compares two artisans itself: it asks `POST /artisan-duplicates/check` while the form is being
 * filled, reads the 409 `artisan_possible_duplicate` hold when a save is refused, and drives the
 * admin review and merge screens. The pure helpers below decide only what to show and what to send.
 *
 * WHAT A CREATE SENDS. Every create from the artisan form carries `duplicateAcknowledged`: `[]` when
 * nothing was shown, otherwise the ids the person said were a different person. A body WITHOUT the
 * field is never held by the server (that is how older outbox entries keep replaying), so a client
 * that leaves it out has opted out of the hold — this one never does.
 */

import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import type { PageResult } from "@/lib/types";

export type DuplicateBand = "VERY_LIKELY" | "LIKELY" | "POSSIBLE";

export type DuplicateReason = { code: string; label: string };

/** One likely duplicate, as `/check` and the 409 hold return it. Never an identifier value. */
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

/** The 409 `detail` from POST /artisans when the new record looks like one already recorded. */
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

/** The thresholds, for display only: the server decides. Mirrors `artisan_duplicates.py`. */
export const SHOW_THRESHOLD = 0.6;
export const HOLD_THRESHOLD = 0.75;
/** How many candidates the form card lists. The server sends at most five. */
export const CARD_MAX_ROWS = 3;

// ── The form ──────────────────────────────────────────────────────────────────────────────────

/** The check body from what the form holds. `null` when there is not yet a name worth checking. */
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

/** A stable key for a check body, so the same values are not asked twice in a row. */
export function checkKey(input: DuplicateCheckInput | null): string {
  return input ? JSON.stringify(input) : "";
}

/** The rows the form card shows: best first, at least the show threshold, never a dismissed one. */
export function candidatesToShow(candidates: DuplicateCandidate[], dismissed: Iterable<string> = []): DuplicateCandidate[] {
  const gone = new Set(dismissed);
  return candidates
    .filter((c) => c.score >= SHOW_THRESHOLD && !gone.has(c.artisan.id))
    .sort((a, b) => b.score - a.score)
    .slice(0, CARD_MAX_ROWS);
}

/**
 * `duplicateAcknowledged` for a create: every id the person was shown and called a different
 * person, de-duplicated, at most twenty (the server's cap). `[]` when nothing was shown — never
 * `undefined`, which would opt the save out of the hold altogether.
 */
export function acknowledgedIds(dismissed: Iterable<string>): string[] {
  return Array.from(new Set(Array.from(dismissed).filter(Boolean))).slice(0, 20);
}

/** "Bhuj · Ajrakh", what the card prints under a candidate's name. */
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

/** The hold behind a 409, or null when the 409 (or the failure) was something else. */
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

// ── The admin queue ───────────────────────────────────────────────────────────────────────────

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

export type MergeChoice = "A" | "B" | "BOTH";

export type MergeField = {
  key: string;
  label: string;
  a: string | null;
  b: string | null;
  default: MergeChoice;
  options: MergeChoice[];
  /** The default under each survivor, so switching the survivor re-seeds untouched rows. */
  defaults?: { A: MergeChoice; B: MergeChoice };
  locked?: boolean;
};

export type MergeBlocker = {
  code: string;
  message: string;
  action?: { kind: "merge_interviews"; sourceId: string; targetId: string };
};

export type RelatedCounts = Record<string, number>;

export type MergePreview = {
  id: string;
  kind: "WITHIN" | "CROSS_PORTAL";
  score: number;
  band: DuplicateBand;
  reasons: DuplicateReason[];
  status: DuplicateStatus;
  a: PairSide & { updatedAt?: string | null; status?: string | null };
  b: PairSide & { updatedAt?: string | null; status?: string | null };
  defaultSurvivor: "A" | "B";
  fields: MergeField[];
  related: { a: RelatedCounts; b: RelatedCounts };
  blockers: MergeBlocker[];
  warnings: Array<{ code: string; message: string }>;
  consent?: { result: "GRANTED" | "WITHDRAWN"; from: "A" | "B"; recordedAt?: string | null } | null;
  linkedFromDesignerPortal?: { a: number; b: number };
};

export type MergeRecord = {
  id: string;
  survivor: { id: string; name: string | null };
  merged: { id: string; name: string | null };
  mergedBy: { id: string | null; name: string | null };
  createdAt: string;
  undoUntil: string;
  undoneAt: string | null;
  canUndo: boolean;
};

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

export function getPair(id: string): Promise<MergePreview> {
  return apiFetch<MergePreview>(`/artisan-duplicates/${encodeURIComponent(id)}`);
}

export function merge(
  id: string,
  body: {
    survivor: "A" | "B";
    choices: Record<string, MergeChoice>;
    expectedUpdatedAt?: { a?: string | null; b?: string | null };
    reason?: string | null;
  }
): Promise<{ merge: { id: string; survivorId: string; mergedId: string; undoUntil: string }; artisan: { id: string; name: string }; warnings: unknown[] }> {
  return apiFetch(`/artisan-duplicates/${encodeURIComponent(id)}/merge`, {
    method: "POST",
    body: JSON.stringify(body)
  });
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

export function listMerges(params: { page?: number; pageSize?: number } = {}): Promise<PageResult<MergeRecord>> {
  return apiFetch<PageResult<MergeRecord>>(
    `/artisan-merges${buildQuery({ page: params.page ?? 1, pageSize: params.pageSize ?? 20 })}`
  );
}

export function undoMerge(id: string): Promise<{ merge: MergeRecord; restoredArtisanId: string; keptSurvivorEdits: string[] }> {
  return apiFetch(`/artisan-merges/${encodeURIComponent(id)}/undo`, { method: "POST" });
}

/** `GET /artisan-duplicates?artisanId=` — is there an open pair naming this artisan? */
export async function openPairFor(artisanId: string): Promise<DuplicatePair | null> {
  const page = await listPairs({ status: "OPEN", artisanId, pageSize: 1 });
  return page.items[0] ?? null;
}

// ── The merge table ───────────────────────────────────────────────────────────────────────────

/** The side each field takes under `survivor` before the admin touches anything. */
export function defaultChoices(fields: MergeField[], survivor: "A" | "B"): Record<string, MergeChoice> {
  const out: Record<string, MergeChoice> = {};
  for (const field of fields) {
    out[field.key] = field.defaults?.[survivor] ?? field.default;
  }
  return out;
}

/** What a field may be set to under `survivor`. A locked row offers only the side that holds it. */
export function optionsFor(field: MergeField, survivor: "A" | "B"): MergeChoice[] {
  if (field.locked) return [field.defaults?.[survivor] ?? field.default];
  return field.options;
}

/**
 * Re-seed the choices for a new survivor: rows the admin changed keep their choice, the rest follow
 * the new survivor's defaults. Locked rows always follow the record that holds the value.
 */
export function reseedChoices(
  fields: MergeField[],
  previous: Record<string, MergeChoice>,
  touched: Set<string>,
  survivor: "A" | "B"
): Record<string, MergeChoice> {
  const next = defaultChoices(fields, survivor);
  for (const field of fields) {
    if (field.locked || !touched.has(field.key)) continue;
    const choice = previous[field.key];
    if (choice && field.options.includes(choice)) next[field.key] = choice;
  }
  return next;
}

/** The body's `choices`: only unlocked fields, only legal values. Locked rows are the server's. */
export function choicesToSend(fields: MergeField[], choices: Record<string, MergeChoice>): Record<string, MergeChoice> {
  const out: Record<string, MergeChoice> = {};
  for (const field of fields) {
    if (field.locked) continue;
    const choice = choices[field.key];
    if (choice && field.options.includes(choice)) out[field.key] = choice;
  }
  return out;
}

/** What the merged record will read for one row, for the "Result" column. */
export function resultValue(field: MergeField, choice: MergeChoice): string | null {
  if (choice === "BOTH") {
    const parts = [field.a, field.b].filter((value): value is string => Boolean(value && value.trim()));
    return parts.length === 2 && parts[0] === parts[1] ? parts[0] : parts.join("\n") || null;
  }
  return choice === "A" ? field.a : field.b;
}

export const BAND_LABEL: Record<DuplicateBand, string> = {
  VERY_LIKELY: "Very likely",
  LIKELY: "Likely",
  POSSIBLE: "Possible"
};

export const RELATED_LABEL: Record<string, string> = {
  media: "Media",
  products: "Products",
  tools: "Tools",
  workshops: "Workshops",
  interviews: "Interviews",
  comments: "Comments",
  revisions: "Edits",
  reviewLogs: "Reviews"
};

/** The `artisan_merged` 404 from GET /artisans/{id}: where the record lives now. */
export function mergedSurvivorId(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 404) return null;
  const payload = error.payload;
  if (!payload || typeof payload !== "object" || !("detail" in payload)) return null;
  const detail = (payload as { detail: unknown }).detail;
  if (!detail || typeof detail !== "object") return null;
  const record = detail as { code?: string; survivorId?: string };
  return record.code === "artisan_merged" && typeof record.survivorId === "string" ? record.survivorId : null;
}
