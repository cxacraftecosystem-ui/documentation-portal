/**
 * What the start sheet last read, kept on this device so a session can be started with no signal
 * (audit M4): the workshops, the instruments, the artisans seen, and each instrument's sections with
 * their question lineages (for the offline coverage line). A per-viewer convenience in localStorage;
 * every read and write survives storage being blocked, and the sheet works without it online.
 */

import type { TitleWorkshop } from "@/lib/naming";
import type { Artisan, Questionnaire, Workshop } from "@/lib/types";

const KEY = "field_repo_qv2_start_lists";
/** Artisans kept: the most recently seen first. */
export const CACHED_ARTISANS = 400;

type WorkshopExtras = { location?: { village?: string | null; district?: string | null; state?: string | null } | null; crafts?: { craft?: { name?: string | null } | null }[] | null };
export type CachedWorkshop = Pick<Workshop, "id" | "title" | "place" | "date" | "startDate" | "endDate" | "status" | "questionnaireId" | "canRecord" | "createdAt"> & WorkshopExtras;
export type CachedArtisan = Pick<Artisan, "id" | "name" | "localName" | "place"> & { craft?: { name: string } | null; workshopId?: string | null };
export type StartLists = {
  workshops: CachedWorkshop[];
  instruments: Questionnaire[];
  artisans: CachedArtisan[];
  /** questionnaireId → sectionId → active question lineage ids. */
  sections: Record<string, Record<string, string[]>>;
  at: string | null;
};

const EMPTY: StartLists = { workshops: [], instruments: [], artisans: [], sections: {}, at: null };

export function readStartLists(): StartLists {
  try {
    const raw = window.localStorage.getItem(KEY);
    if (!raw) return EMPTY;
    const parsed = JSON.parse(raw) as Partial<StartLists>;
    return {
      workshops: Array.isArray(parsed.workshops) ? parsed.workshops : [],
      instruments: Array.isArray(parsed.instruments) ? parsed.instruments : [],
      artisans: Array.isArray(parsed.artisans) ? parsed.artisans : [],
      sections: parsed.sections && typeof parsed.sections === "object" ? parsed.sections : {},
      at: typeof parsed.at === "string" ? parsed.at : null
    };
  } catch {
    return EMPTY;
  }
}

export function trimWorkshop(w: Workshop & WorkshopExtras): CachedWorkshop {
  return {
    id: w.id,
    title: w.title,
    place: w.place,
    date: w.date,
    startDate: w.startDate ?? null,
    endDate: w.endDate ?? null,
    status: w.status,
    questionnaireId: w.questionnaireId ?? null,
    canRecord: w.canRecord,
    createdAt: w.createdAt,
    location: w.location ? { village: w.location.village ?? null, district: w.location.district ?? null, state: w.location.state ?? null } : null,
    crafts: (w.crafts ?? []).map((c) => ({ craft: c.craft?.name ? { name: c.craft.name } : null }))
  };
}

export function trimArtisan(a: Artisan & { workshopId?: string | null }): CachedArtisan {
  return { id: a.id, name: a.name, localName: a.localName ?? null, place: a.place, craft: a.craft?.name ? { name: a.craft.name } : null, workshopId: a.workshopId ?? null };
}

/** Merges what was just read into what is held; artisans newest first, capped. */
export function mergeStartLists(held: StartLists, next: Partial<StartLists>, now: Date = new Date()): StartLists {
  const artisans = next.artisans
    ? [...next.artisans, ...held.artisans.filter((a) => !next.artisans!.some((n) => n.id === a.id))].slice(0, CACHED_ARTISANS)
    : held.artisans;
  return {
    workshops: next.workshops ?? held.workshops,
    instruments: next.instruments ?? held.instruments,
    artisans,
    sections: { ...held.sections, ...(next.sections ?? {}) },
    at: now.toISOString()
  };
}

export function writeStartLists(next: Partial<StartLists>): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(mergeStartLists(readStartLists(), next)));
  } catch {
    // Storage full or blocked: the sheet still works online.
  }
}

/** Held artisans matching a search, for the picker with no signal. */
export function searchCachedArtisans(artisans: CachedArtisan[], query: string, workshopId: string | null): CachedArtisan[] {
  const q = query.trim().toLowerCase();
  return artisans.filter(
    (a) => (!workshopId || !a.workshopId || a.workshopId === workshopId) && (!q || `${a.name} ${a.localName ?? ""} ${a.place}`.toLowerCase().includes(q))
  );
}

/**
 * The workshop half of the title preview, built exactly as the server builds it
 * (`services/questionnaire_v2/titles.py` `workshop_fields`): locality is the village, else the
 * place when no district or state is known; the craft only when the workshop has exactly one.
 */
export function titleWorkshop(w: (Pick<Workshop, "place"> & WorkshopExtras) | null | undefined): TitleWorkshop | null {
  if (!w) return null;
  const loc = w.location ?? null;
  const crafts = [...new Set((w.crafts ?? []).map((c) => c.craft?.name).filter((n): n is string => Boolean(n)))].sort();
  const locality = loc?.village || (loc?.district || loc?.state ? null : w.place || null);
  return { locality: locality ?? null, district: loc?.district ?? null, state: loc?.state ?? null, craft: crafts.length === 1 ? crafts[0] : null };
}
