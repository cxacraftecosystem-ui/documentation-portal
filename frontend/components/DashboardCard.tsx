"use client";

import Link from "next/link";
import { ArrowRight, Pencil, Plus, type LucideIcon } from "lucide-react";

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
  updateHref,
  newLabel = "New"
}: {
  label: string;
  icon: LucideIcon;
  newHref: string;
  updateHref?: string;
  /**
   * The primary action's wording, from the Android app's `EntryMode.createButtonLabel()`.
   *
   * Every tile used to say "New", which is wrong for more than a third of them: you do not create
   * a new View Data, and "New" on the Users tile reads as "add a user" when the button opens the
   * list. The two apps are the same product and a researcher moves between them mid-workshop, so
   * the wording has to be the same word.
   */
  newLabel?: string;
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
          {/* "Open"/"Manage" are not creations, so they do not get the plus. Android draws the same
              distinction (`primaryIcon`), and a plus on a button that only navigates is a lie. */}
          {newLabel === "Open" || newLabel === "Manage" ? (
            <ArrowRight className="h-3.5 w-3.5" aria-hidden />
          ) : (
            <Plus className="h-3.5 w-3.5" aria-hidden />
          )}
          {newLabel}
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
