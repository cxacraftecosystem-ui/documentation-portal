"use client";

import { useEffect, useMemo, useRef, useState } from "react";

import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import type { CarryScopeState } from "@/components/forms/CarryContextBanner";
import { apiFetch, listResource } from "@/lib/api";
import { useCraftCatalogue } from "@/lib/craftCatalogue";
import { sharedListResource } from "@/lib/sharedList";
import type { Artisan, Craft } from "@/lib/types";

export type CraftRef = Pick<Craft, "id" | "name">;

export function useRecordOffPage<T extends { id: string }>(
  endpoint: string,
  id: string,
  rows: readonly T[]
): T | null {
  const [record, setRecord] = useState<T | null>(null);
  const attempted = useRef(new Set<string>());

  useEffect(() => {
    if (!id || rows.some((row) => row.id === id)) return;
    if (attempted.current.has(id)) return;
    attempted.current.add(id);
    let cancelled = false;
    apiFetch<T>(`${endpoint}/${id}`)
      .then((row) => {
        if (!cancelled) setRecord(row);
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint, id, rows]);

  return record && record.id === id ? record : null;
}

export function useRecordsOffPage<T extends { id: string }>(
  endpoint: string,
  ids: readonly string[],
  rows: readonly T[],
  ready = true
): T[] {
  const [records, setRecords] = useState<Record<string, T>>({});
  const attempted = useRef(new Set<string>());
  const missingKey = ids.filter((id) => id && !rows.some((row) => row.id === id)).join(",");
  const idKey = ids.filter(Boolean).join(",");

  useEffect(() => {
    if (!ready || !missingKey) return;
    for (const id of missingKey.split(",")) {
      if (attempted.current.has(id)) continue;
      attempted.current.add(id);
      apiFetch<T>(`${endpoint}/${id}`)
        .then((row) => setRecords((current) => ({ ...current, [id]: row })))
        .catch(() => {
        });
    }
  }, [endpoint, missingKey, ready]);

  return useMemo(
    () => (idKey ? idKey.split(",").map((id) => records[id]).filter((row): row is T => Boolean(row)) : []),
    [idKey, records]
  );
}

export type CraftAndArtisanOptions = {
  artisans: Artisan[];
  crafts: CraftRef[];
  referenceState: CarryScopeState;
  craftCut: ListCut | null;
  craftArtisanCut: ListCut | null;
  artisansLoadedForCrafts: string | null;
};

export function craftsKey(craftIds: readonly string[]): string {
  return [...new Set(craftIds.filter(Boolean))].sort().join(",");
}

export function useCraftAndArtisanOptions({
  craftIds,
  artisanIds
}: {
  craftIds: readonly string[];
  artisanIds: readonly string[];
}): CraftAndArtisanOptions {
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const catalogue = useCraftCatalogue();
  const crafts = useMemo<CraftRef[]>(() => catalogue.catalogue?.crafts ?? [], [catalogue.catalogue]);
  const [artisanState, setArtisanState] = useState<CarryScopeState>("pending");
  const craftState: CarryScopeState =
    catalogue.catalogue ? "loaded" : catalogue.loading ? "pending" : "unavailable";
  const referenceState: CarryScopeState =
    artisanState === "unavailable" || craftState === "unavailable"
      ? "unavailable"
      : artisanState === "loaded" && craftState === "loaded"
        ? "loaded"
        : "pending";
  const [craftArtisanCut, setCraftArtisanCut] = useState<ListCut | null>(null);
  const [artisansLoadedForCrafts, setArtisansLoadedForCrafts] = useState<string | null>(null);
  const craftKey = craftsKey(craftIds);

  useEffect(() => {
    let cancelled = false;
    sharedListResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })
      .then((artisanResult) => {
        if (cancelled) return;
        setArtisans((previous) => mergeById(previous, artisanResult.items));
        setArtisanState("loaded");
      })
      .catch(() => {
        if (!cancelled) setArtisanState("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!craftKey) return;
    let cancelled = false;
    const wanted = craftKey.split(",");
    listResource<Artisan>("/artisans", {
      craftIds: craftKey,
      craftId: wanted.length === 1 ? wanted[0] : undefined,
      pageSize: LIST_PAGE_CEILING
    })
      .then((result) => {
        if (cancelled) return;
        setArtisans((previous) => mergeById(previous, result.items));
        setCraftArtisanCut(listCut(result, wanted.length === 1 ? "artisans of this craft" : "artisans of these crafts"));
        setArtisansLoadedForCrafts(craftKey);
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [craftKey]);

  const offPageArtisans = useRecordsOffPage<Artisan>("/artisans", artisanIds, artisans, artisanState !== "pending");
  const allArtisans = useMemo(
    () => (offPageArtisans.length ? mergeById(artisans, offPageArtisans) : artisans),
    [artisans, offPageArtisans]
  );

  return { artisans: allArtisans, crafts, referenceState, craftCut: null, craftArtisanCut, artisansLoadedForCrafts };
}

export function craftChangeClearsArtisan({
  nextCraftId,
  artisanId,
  artisans
}: {
  nextCraftId: string;
  artisanId: string;
  artisans: readonly Artisan[];
}): boolean {
  if (!nextCraftId || !artisanId) return false;
  const known = artisans.find((artisan) => artisan.id === artisanId);
  return Boolean(known) && known?.craftId !== nextCraftId;
}

export function craftsChangeClearsArtisans({
  nextCraftIds,
  removedCraftIds,
  artisanIds,
  artisans
}: {
  nextCraftIds: readonly string[];
  removedCraftIds: readonly string[];
  artisanIds: readonly string[];
  artisans: readonly Artisan[];
}): string[] {
  return artisanIds.filter((id) => {
    const known = artisans.find((artisan) => artisan.id === id);
    if (!known || !known.craftId) return false;
    return removedCraftIds.includes(known.craftId) && !nextCraftIds.includes(known.craftId);
  });
}

export function craftNameFor(artisan: Artisan, selectedCrafts: readonly CraftRef[]): string {
  const hydrated = artisan.craft?.name?.trim();
  if (hydrated) return hydrated;
  const selected = selectedCrafts.find((craft) => craft.id === artisan.craftId)?.name?.trim();
  return selected || "";
}

function orderFold(text: string): string {
  return text.trim().toLowerCase();
}

function compareUtf16(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function sortArtisansByCraft(artisans: readonly Artisan[], selectedCrafts: readonly CraftRef[]): Artisan[] {
  const keyed = artisans.map((artisan) => {
    const craftKey = orderFold(craftNameFor(artisan, selectedCrafts));
    return { artisan, unknown: craftKey === "" ? 1 : 0, craftKey, nameKey: orderFold(artisan.name) };
  });
  keyed.sort(
    (a, b) =>
      a.unknown - b.unknown ||
      compareUtf16(a.craftKey, b.craftKey) ||
      compareUtf16(a.nameKey, b.nameKey) ||
      compareUtf16(a.artisan.name, b.artisan.name) ||
      compareUtf16(a.artisan.id, b.artisan.id)
  );
  return keyed.map((entry) => entry.artisan);
}

export function artisanForCard(artisanId: string): Promise<Artisan> {
  return apiFetch<Artisan>(`/artisans/${encodeURIComponent(artisanId)}`);
}
