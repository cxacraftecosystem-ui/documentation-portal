import { placeField, sessionTitle } from "@/lib/naming";

/**
 * The fields a workshop's generated title is built from; every one is optional. A design workshop
 * carries the code, cluster, district and state; a legacy workshop carries `date` and `place`.
 */
export type WorkshopTitleFields = {
  title?: string | null;
  workshopCode?: string | null;
  craftName?: string | null;
  clusterName?: string | null;
  district?: string | null;
  state?: string | null;
  startDate?: string | null;
  date?: string | null;
  place?: string | null;
};

/** Whether the workshop has a title its people typed (which is never rewritten). */
export function hasTypedTitle(workshop: WorkshopTitleFields | null | undefined): boolean {
  return Boolean(workshop?.title?.trim());
}

/**
 * The title a workshop is shown under: the one its people typed, never rewritten, or — only when it
 * is blank — the generated default `Workshop_Date_Place_Craft_Code` (NAMING 2.4), which replaces the
 * old "Untitled workshop" / "Untitled design workshop" that every blank workshop shared. Identical
 * in both portals.
 */
export function workshopDisplayTitle(workshop: WorkshopTitleFields | null | undefined): string {
  const typed = workshop?.title?.trim();
  if (typed) return typed;
  const place =
    workshop?.place?.trim() ||
    placeField({ locality: workshop?.clusterName, district: workshop?.district, state: workshop?.state }, false);
  return sessionTitle({
    entity: "Workshop",
    date: workshop?.startDate ?? workshop?.date ?? null,
    place,
    craft: workshop?.craftName,
    subject: workshop?.workshopCode
  });
}
