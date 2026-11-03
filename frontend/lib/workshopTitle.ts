import { placeField, sessionTitle } from "@/lib/naming";

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

export function hasTypedTitle(workshop: WorkshopTitleFields | null | undefined): boolean {
  return Boolean(workshop?.title?.trim());
}

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
