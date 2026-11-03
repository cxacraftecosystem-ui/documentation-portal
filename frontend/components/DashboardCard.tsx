"use client";

import Link from "next/link";
import { ArrowRight, Pencil, Plus, type LucideIcon } from "lucide-react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { GLASS_TILE, GlassSurface } from "@/components/ui/GlassSurface";

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
      <ButtonRow size="sm" fill className="mt-auto pt-1">
        <Link
          className="field-button h-9 min-h-0 pointer-coarse:h-11 px-2 text-xs sm:px-3 after:absolute after:inset-0 after:rounded-lg after:content-['']"
          href={newHref}
          aria-label={`${newLabel} — ${label}`}
        >
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
