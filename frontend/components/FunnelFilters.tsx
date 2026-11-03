"use client";

import { useEffect, useRef, useState } from "react";

import { workshopOccurrenceDate } from "@/components/forms/WorkshopSelect";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { Dropdown } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { Artisan, Workshop } from "@/lib/types";

export type FunnelWorkshop = Workshop & {
  crafts?: Array<{ craftId?: string; craft?: { id: string; name: string } }>;
};

export type FunnelValue = { workshopId: string; craftId: string; artisanId: string };

export const EMPTY_FUNNEL: FunnelValue = { workshopId: "", craftId: "", artisanId: "" };

const occurredAt = workshopOccurrenceDate;

export function FunnelFilters({
  value,
  onChange,
  showArtisan = false,
  restore
}: {
  value: FunnelValue;
  onChange: (next: FunnelValue, workshop: FunnelWorkshop | null) => void;
  showArtisan?: boolean;
  restore?: () => FunnelValue | null;
}) {
  const [workshops, setWorkshops] = useState<FunnelWorkshop[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const initialised = useRef(false);

  useEffect(() => {
    if (initialised.current) return;
    initialised.current = true;
    (async () => {
      let workshopRows: FunnelWorkshop[] = [];
      try {
        const workshopResult = await listResource<FunnelWorkshop>("/workshops", { pageSize: 100 });
        workshopRows = [...workshopResult.items].sort((a, b) => occurredAt(b).localeCompare(occurredAt(a)));
      } catch {
      }
      setWorkshops(workshopRows);
      const latest = workshopRows[0] ?? null;
      const back = restore?.() ?? null;
      const backWorkshop = back?.workshopId ? (workshopRows.find((row) => row.id === back.workshopId) ?? null) : null;
      if (back && (!back.workshopId || backWorkshop)) {
        onChange(back, backWorkshop);
        return;
      }
      onChange({ workshopId: latest?.id ?? "", craftId: "", artisanId: "" }, latest);
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
      <CraftSelect
        ariaLabel="Filter by craft"
        value={value.craftId}
        placeholder="All crafts"
        allowedIds={workshopCraftIds.size ? [...workshopCraftIds] : undefined}
        onChange={(craftId) => selectCraft(craftId)}
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
