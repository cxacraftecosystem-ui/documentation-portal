"use client";

import Link from "next/link";
import { ArrowRight, Pencil, Plus, type LucideIcon } from "lucide-react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { GLASS_TILE, GlassSurface } from "@/components/ui/GlassSurface";

/**
 * One dashboard action tile (Android `DashboardActionCard` parity): a small dark-purple
 * icon tile, the display-font label, a filled purple "New" action and — where the record
 * type is editable — an outlined "Update" action leading to the list page.
 *
 * The card is liquid glass: `bg-card/70` instead of an opaque fill, because the filter
 * refracts what is behind the tile and an opaque background would hide it. The dashboard
 * paints a mesh under the grid to give it something worth bending.
 *
 * THE WHOLE CARD IS THE PRIMARY ACTION (owner ruling, 2026-10-16): a click anywhere on it does what
 * its filled button says — "New", "Open", "Upload", "Manage" — and "Update" is the only way to the
 * update screen. It is one real link, not a clickable `<div>`: the primary `<Link>` stretches an
 * invisible `::after` over the card, so the card is announced and focused as that one link, Enter
 * opens it, and the global focus ring draws on the button. "Update" is lifted above the stretched
 * area (`relative z-10`) and stays a separate, separately focusable link — two links side by side,
 * never one inside the other. Android draws the same card with `Modifier.clickable` on the whole
 * `ElevatedCard` (`DashboardActionCard`).
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
      className="relative flex cursor-pointer flex-col gap-2 rounded-lg border border-line-200 bg-card/70 p-3 shadow-sm transition hover:border-purple-300 hover:shadow-md"
    >
      <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
        <Icon className="h-5 w-5 text-white" aria-hidden />
      </div>
      <div className="font-display text-base font-bold leading-snug text-ink-900">{label}</div>
      {/* New and Update share one line (owner rule: two buttons, one line). A phone's card is about
          135px inside, so the icons drop below `sm` and the two words fit side by side. */}
      <ButtonRow size="sm" className="mt-auto pt-1">
        <Link
          className="field-button h-9 min-h-0 pointer-coarse:h-11 px-2 text-xs sm:px-3 after:absolute after:inset-0 after:rounded-lg after:content-['']"
          href={newHref}
          aria-label={`${newLabel} — ${label}`}
        >
          {/* "Open"/"Manage" are not creations, so they do not get the plus. Android draws the same
              distinction (`primaryIcon`), and a plus on a button that only navigates is a lie. */}
          {newLabel === "Open" || newLabel === "Manage" ? (
            <ArrowRight className="h-3.5 w-3.5 max-sm:hidden" aria-hidden />
          ) : (
            <Plus className="h-3.5 w-3.5 max-sm:hidden" aria-hidden />
          )}
          {newLabel}
        </Link>
        {updateHref ? (
          <Link className="field-button-secondary relative z-10 h-9 min-h-0 pointer-coarse:h-11 px-2 text-xs sm:px-3" href={updateHref} aria-label={`Update — ${label}`}>
            <Pencil className="h-3.5 w-3.5 max-sm:hidden" aria-hidden />
            Update
          </Link>
        ) : null}
      </ButtonRow>
    </GlassSurface>
  );
}
