"use client";

import { useEffect, useMemo, useState } from "react";

import { cutOf, LIST_PAGE_CEILING, mergeById, type ListCut } from "@/components/data/cappedList";
import type { CarryScopeState } from "@/components/forms/CarryContextBanner";
import type { DropdownOption } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { Artisan } from "@/lib/types";

export const ARTISAN_PAGE_BUDGET = 5;

export function artisanScopeNoun(workshopId: string): string {
  return workshopId ? "artisans at this workshop" : "artisans";
}

export function workshopArtisanParams(workshopId: string, page: number): Record<string, string | number | undefined> {
  return {
    workshopIds: workshopId || undefined,
    page,
    pageSize: LIST_PAGE_CEILING
  };
}

export function workshopScopeSettling(workshop: {
  loading: boolean;
  touched: boolean;
  workshopId: string;
  workshops: readonly unknown[];
}): boolean {
  if (workshop.loading) return true;
  if (workshop.touched || workshop.workshopId) return false;
  return workshop.workshops.length > 0;
}

export function artisanPickerOptionLabel(artisan: Artisan): string {
  return `${artisan.name} - ${artisan.craft?.name ?? "No craft"} - ${artisan.place}`;
}

export function artisanPickerOptions({
  scoped,
  known,
  selectedIds
}: {
  scoped: readonly Artisan[];
  known: readonly Artisan[];
  selectedIds: readonly string[];
}): DropdownOption[] {
  const offered = new Set(scoped.map((artisan) => artisan.id));
  const rescued = selectedIds
    .filter((id) => id && !offered.has(id))
    .map((id) => known.find((artisan) => artisan.id === id))
    .filter((artisan): artisan is Artisan => Boolean(artisan));
  return mergeById(scoped as Artisan[], rescued).map((artisan) => ({
    value: artisan.id,
    label: artisanPickerOptionLabel(artisan)
  }));
}

export function primaryInterviewArtisanId(selectedIds: readonly string[]): string {
  return selectedIds.find((id) => Boolean(id)) ?? "";
}

export function artisanSetKey(selectedIds: readonly string[]): string {
  return [...new Set(selectedIds.filter(Boolean))].sort().join(",");
}

export function artisansNotAtWorkshop({
  selectedIds,
  offeredIds,
  loadedForWorkshop,
  workshopId,
  cut
}: {
  selectedIds: readonly string[];
  offeredIds: readonly string[];
  loadedForWorkshop: string | null;
  workshopId: string;
  cut: ListCut | null;
}): string[] {
  if (loadedForWorkshop !== workshopId) return [];
  if (cut) return [];
  const offered = new Set(offeredIds);
  return selectedIds.filter((id) => Boolean(id) && !offered.has(id));
}

export const OUT_OF_WORKSHOP_NAMES_SHOWN = 4;

export function outOfWorkshopNotice(names: readonly string[]): string {
  const clean = names.map((name) => name.trim()).filter(Boolean);
  if (clean.length === 0) return "";
  const shown = clean.slice(0, OUT_OF_WORKSHOP_NAMES_SHOWN);
  const remainder = clean.length - shown.length;
  const list = shown.join(", ") + (remainder > 0 ? ` and ${remainder} more` : "");
  const subject = clean.length === 1 ? `${list} is` : `${list} are`;
  return (
    `${subject} new to this workshop. Saving links them to it.`
  );
}

export type WorkshopArtisans = {
  scoped: Artisan[];
  known: Artisan[];
  loadedForWorkshop: string | null;
  cut: ListCut | null;
  failed: boolean;
  referenceState: CarryScopeState;
};

export function useWorkshopArtisans({
  workshopId,
  settling
}: {
  workshopId: string;
  settling: boolean;
}): WorkshopArtisans {
  const [scoped, setScoped] = useState<Artisan[]>([]);
  const [known, setKnown] = useState<Artisan[]>([]);
  const [loadedForWorkshop, setLoadedForWorkshop] = useState<string | null>(null);
  const [cut, setCut] = useState<ListCut | null>(null);
  const [failed, setFailed] = useState(false);
  const [referenceState, setReferenceState] = useState<CarryScopeState>("pending");

  useEffect(() => {
    let cancelled = false;
    listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setKnown((previous) => mergeById(previous, result.items));
        setReferenceState("loaded");
      })
      .catch(() => {
        if (!cancelled) setReferenceState("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (settling) return;
    let cancelled = false;
    setScoped([]);
    setLoadedForWorkshop(null);
    setCut(null);
    setFailed(false);
    (async () => {
      try {
        const first = await listResource<Artisan>("/artisans", workshopArtisanParams(workshopId, 1));
        if (cancelled) return;
        const lastPage = Math.min(first.pages || 1, ARTISAN_PAGE_BUDGET);
        const rest =
          lastPage > 1
            ? await Promise.all(
                Array.from({ length: lastPage - 1 }, (_, index) =>
                  listResource<Artisan>("/artisans", workshopArtisanParams(workshopId, index + 2))
                )
              )
            : [];
        if (cancelled) return;
        const rows = [first, ...rest].flatMap((result) => result.items);
        setScoped(rows);
        setKnown((previous) => mergeById(previous, rows));
        setCut(cutOf(rows.length, first.total, artisanScopeNoun(workshopId)));
        setLoadedForWorkshop(workshopId);
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [workshopId, settling]);

  return useMemo(
    () => ({ scoped, known, loadedForWorkshop, cut, failed, referenceState }),
    [scoped, known, loadedForWorkshop, cut, failed, referenceState]
  );
}

