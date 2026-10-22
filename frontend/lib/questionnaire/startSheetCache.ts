/**
 * What the start sheet last read, kept on this device so a session can be started with no signal
 * (audit M4): the workshops, the instruments, the artisans seen, and each instrument's sections with
 * their question lineages (for the offline coverage line). A per-viewer convenience in localStorage;
 * every read and write survives storage being blocked, and the sheet works without it online.
 */

import type { TitleWorkshop } from "@/lib/naming";
import { STORAGE_PREFIX, titleWorkshop as portalTitleWorkshop, type PortalForm, type PortalWorkshop } from "@/lib/questionnairePortal";
import type { Artisan } from "@/lib/types";

const KEY = `${STORAGE_PREFIX}_start_lists`;
/** Artisans kept: the most recently seen first. */
export const CACHED_ARTISANS = 400;

export type CachedWorkshop = PortalWorkshop;
export type CachedArtisan = Pick<Artisan, "id" | "name" | "localName" | "place"> & { craft?: { name: string } | null; workshopId?: string | null };
export type StartLists = {
  workshops: CachedWorkshop[];
  instruments: PortalForm[];
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

export function trimWorkshop(w: PortalWorkshop): CachedWorkshop {
  return { ...w };
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
 * (`app/questionnaire/titles.py`); each portal says how its workshop reads (`lib/questionnairePortal`).
 */
export function titleWorkshop(w: PortalWorkshop | null | undefined): TitleWorkshop | null {
  return portalTitleWorkshop(w);
}
