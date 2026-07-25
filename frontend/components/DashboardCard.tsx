"use client";

import Link from "next/link";
import { Pencil, Plus, type LucideIcon } from "lucide-react";

import { GLASS_TILE, GlassSurface } from "@/components/ui/GlassSurface";

/**
 * One dashboard action tile (Android `DashboardActionCard` parity): a small dark-purple
 * icon tile, the display-font label, a filled purple "New" action and — where the record
 * type is editable — an outlined "Update" action leading to the list page.
 *
 * The card is liquid glass: `bg-card/70` instead of an opaque fill, because the filter
 * refracts what is behind the tile and an opaque background would hide it. The dashboard
 * paints a mesh under the grid to give it something worth bending.
 */
export function DashboardCard({
  label,
  icon: Icon,
  newHref,
  updateHref
}: {
  label: string;
  icon: LucideIcon;
  newHref: string;
  updateHref?: string;
}) {
  return (
    <GlassSurface
      options={GLASS_TILE}
      className="flex flex-col gap-2 rounded-lg border border-line-200 bg-card/70 p-3 shadow-sm"
    >
      <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
        <Icon className="h-5 w-5 text-white" aria-hidden />
      </div>
      <div className="font-display text-base font-bold leading-snug text-ink-900">{label}</div>
      <div className="mt-auto flex flex-col gap-1.5 pt-1">
        <Link className="field-button h-9 min-h-0 px-3 text-xs" href={newHref}>
          <Plus className="h-3.5 w-3.5" aria-hidden />
          New
        </Link>
        {updateHref ? (
          <Link className="field-button-secondary h-9 min-h-0 px-3 text-xs" href={updateHref}>
            <Pencil className="h-3.5 w-3.5" aria-hidden />
            Update
          </Link>
        ) : null}
      </div>
    </GlassSurface>
  );
}
