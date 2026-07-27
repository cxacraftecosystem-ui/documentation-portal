"use client";

import { useEffect, useRef, useState } from "react";

import { workshopOccurrenceDate } from "@/components/forms/WorkshopSelect";
import { Dropdown } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { Artisan, Craft, Workshop } from "@/lib/types";

/** Workshop rows from the list API include linked artisans and crafts (crafts not yet in the TS type). */
export type FunnelWorkshop = Workshop & {
  crafts?: Array<{ craftId?: string; craft?: { id: string; name: string } }>;
};

export type FunnelValue = { workshopId: string; craftId: string; artisanId: string };

export const EMPTY_FUNNEL: FunnelValue = { workshopId: "", craftId: "", artisanId: "" };

/**
 * When a workshop actually happened: its start date, falling back to the legacy single `date`
 * column and finally to when the row was created. Sorting on this is what makes "most recent
 * workshop" mean the latest FIELD work rather than the last row someone typed in.
 *
 * Re-exported from `forms/WorkshopSelect` rather than re-implemented: the record forms' workshop
 * picker, this funnel and Android's `WorkshopDetailDto.occurrenceDate()` must agree on which
 * workshop is "current", otherwise the filter and the form disagree about the same list.
 */
const occurredAt = workshopOccurrenceDate;

/**
 * Cascading list filters: Workshop -> Craft -> Artisan. Selecting a workshop narrows the craft
 * options to the workshop's linked crafts (when it has any); selecting a craft reloads the artisan
 * options from /artisans?craftId=. The workshop filter DEFAULTS to the most recent workshop by
 * occurrence date — `onChange` fires once after the workshops load with that default (or "All"
 * when none exist), so parents should wait for the first call before fetching their list.
 */
export function FunnelFilters({
  value,
  onChange,
  showArtisan = false
}: {
  value: FunnelValue;
  /** Next filter value plus the selected workshop row (null = all workshops) for client-side narrowing. */
  onChange: (next: FunnelValue, workshop: FunnelWorkshop | null) => void;
  showArtisan?: boolean;
}) {
  const [workshops, setWorkshops] = useState<FunnelWorkshop[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const initialised = useRef(false);

  // Load workshops + crafts once and default the workshop filter to the workshop that happened last.
  //
  // This default is deliberate — a researcher standing in a workshop wants that workshop's records —
  // but it has one failure mode worth naming, because it already bit once. `workshopId` is a recent
  // column and a row that predates it is NULL, which matches no workshop. Every list carrying this
  // funnel then renders EMPTY over a full corpus, and an empty list is indistinguishable from having
  // no data: a researcher opening "Update" to fix a tool concludes their work is gone. The records
  // were backfilled onto their workshop to fix that, so if it recurs the question to ask is whether
  // something new is being created without a workshop rather than whether this default is wrong.
  useEffect(() => {
    if (initialised.current) return;
    initialised.current = true;
    (async () => {
      let workshopRows: FunnelWorkshop[] = [];
      let craftRows: Craft[] = [];
      try {
        const [workshopResult, craftResult] = await Promise.all([
          listResource<FunnelWorkshop>("/workshops", { pageSize: 100 }),
          listResource<Craft>("/crafts", { pageSize: 100 })
        ]);
        // The API orders workshops by createdAt; the filter wants the most recently HELD workshop
        // first, so re-sort on the occurrence date (startDate ?? date ?? createdAt), newest first.
        workshopRows = [...workshopResult.items].sort((a, b) => occurredAt(b).localeCompare(occurredAt(a)));
        craftRows = craftResult.items;
      } catch {
        // Filters degrade to "All" — the parent list still loads unfiltered.
      }
      setWorkshops(workshopRows);
      setCrafts(craftRows);
      const latest = workshopRows[0] ?? null;
      onChange({ workshopId: latest?.id ?? "", craftId: "", artisanId: "" }, latest);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Artisan options follow the selected craft (backend filter); the workshop narrows them client-side.
  useEffect(() => {
    if (!showArtisan) return;
    let active = true;
    listResource<Artisan>("/artisans", { craftId: value.craftId || undefined, pageSize: 100 })
      .then((result) => {
        if (active) setArtisans(result.items);
      })
      .catch(() => {
        if (active) setArtisans([]);
      });
    return () => {
      active = false;
    };
  }, [showArtisan, value.craftId]);

  const selectedWorkshop = value.workshopId ? (workshops.find((workshop) => workshop.id === value.workshopId) ?? null) : null;

  const workshopCraftIds = new Set(
    (selectedWorkshop?.crafts ?? [])
      .map((link) => link.craft?.id ?? link.craftId)
      .filter((id): id is string => Boolean(id))
  );
  const craftOptions = workshopCraftIds.size ? crafts.filter((craft) => workshopCraftIds.has(craft.id)) : crafts;

  const workshopArtisanIds = new Set((selectedWorkshop?.artisans ?? []).map((link) => link.artisan.id));
  const artisanOptions = workshopArtisanIds.size ? artisans.filter((artisan) => workshopArtisanIds.has(artisan.id)) : artisans;

  function selectWorkshop(workshopId: string) {
    const workshop = workshopId ? (workshops.find((item) => item.id === workshopId) ?? null) : null;
    onChange({ workshopId, craftId: "", artisanId: "" }, workshop);
  }

  function selectCraft(craftId: string) {
    onChange({ ...value, craftId, artisanId: "" }, selectedWorkshop);
  }

  function selectArtisan(artisanId: string) {
    onChange({ ...value, artisanId }, selectedWorkshop);
  }

  return (
    // advanceOnSelect={false} on all three: these NARROW THE LIST BELOW rather than fill in a form
    // field, so the auto-advance the Dropdown does by default is wrong here — it threw focus onto the
    // next filter (workshop -> craft), which then swallowed the arrow keys of anyone still adjusting
    // the filter they had just changed. See the prop's own documentation in ui/Dropdown.
    <div className="mb-4 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
      <Dropdown
        ariaLabel="Filter by workshop"
        value={value.workshopId}
        onChange={selectWorkshop}
        advanceOnSelect={false}
        options={[
          { value: "", label: "All workshops" },
          ...workshops.map((workshop) => ({ value: workshop.id, label: workshop.title }))
        ]}
      />
      <Dropdown
        ariaLabel="Filter by craft"
        value={value.craftId}
        onChange={selectCraft}
        advanceOnSelect={false}
        options={[{ value: "", label: "All crafts" }, ...craftOptions.map((craft) => ({ value: craft.id, label: craft.name }))]}
      />
      {showArtisan ? (
        <Dropdown
          ariaLabel="Filter by artisan"
          value={value.artisanId}
          onChange={selectArtisan}
          advanceOnSelect={false}
          options={[
            { value: "", label: "All artisans" },
            ...artisanOptions.map((artisan) => ({ value: artisan.id, label: `${artisan.name} · ${artisan.place}` }))
          ]}
        />
      ) : null}
    </div>
  );
}
