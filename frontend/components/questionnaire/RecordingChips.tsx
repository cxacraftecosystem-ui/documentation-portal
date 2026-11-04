"use client";

import { CalendarDays, Clock, FileText, Hash, Languages, MapPin, Mic, User, Users } from "lucide-react";

import { displayChips, sessionChips, type ChipFilter, type ChipKind, type DisplayChip, type RecordingChips as Chips } from "@/lib/questionnaire/chips";
import { isChipActive, type Filters } from "@/lib/questionnaire/filters";

export type RecordingChipsProps = {
  chips: Chips | null | undefined;
  onChip?: (filter: ChipFilter, chip: DisplayChip) => void;
  filters?: Filters;
  variant?: "recording" | "session";
  size?: "sm" | "md";
  maxTargets?: number;
  maxPeople?: number;
  people?: boolean;
  className?: string;
};

const ICONS: Record<ChipKind, typeof Hash> = {
  target: Hash,
  moreTargets: Hash,
  questionnaire: FileText,
  artisan: User,
  guest: User,
  morePeople: Users,
  date: CalendarDays,
  captured: Mic,
  workshop: MapPin,
  language: Languages,
  duration: Clock
};

export function RecordingChips({ chips, onChip, filters, variant = "recording", size = "sm", maxTargets, maxPeople, people, className }: RecordingChipsProps) {
  const list: DisplayChip[] = variant === "session" ? sessionChips(chips, { maxPeople, people }) : displayChips(chips, { maxTargets, maxPeople, people });
  if (!list.length) return null;
  const text = size === "md" ? "text-xs" : "text-[11px] leading-4";
  const pad = size === "md" ? "px-2.5 py-1" : "px-2 py-0.5";
  return (
    <ul className={`flex flex-wrap gap-1.5 ${className ?? ""}`} data-recording-chips>
      {list.map((chip, index) => {
        const Icon = ICONS[chip.kind];
        const active = isChipActive(filters ?? {}, chip.filter);
        const base = `inline-flex max-w-[16rem] items-center gap-1 rounded-full border ${pad} ${text}`;
        const body = (
          <>
            <Icon className="h-3 w-3 shrink-0 opacity-70" aria-hidden />
            <span className="truncate">{chip.label}</span>
          </>
        );
        const data = { "data-chip-kind": chip.kind, "data-chip-filter": chip.filter ? `${chip.filter.key}=${chip.filter.value}` : undefined };
        return (
          <li key={`${chip.kind}-${index}`} className="min-w-0">
            {chip.filter && onChip ? (
              <button
                type="button"
                aria-pressed={active}
                title={chip.title ?? chip.label}
                onClick={(event) => {
                  event.stopPropagation();
                  onChip(chip.filter!, chip);
                }}
                className={`${base} transition ${
                  active
                    ? "border-purple-300 bg-purple-50 text-purple-800"
                    : "border-line-200 bg-card text-ink-700 hover:border-purple-300 hover:bg-purple-50"
                }`}
                {...data}
              >
                {body}
              </button>
            ) : (
              <span title={chip.title ?? chip.label} className={`${base} border-line-200 ${chip.filter ? "bg-card text-ink-700" : "bg-surface-50 text-ink-500"}`} {...data}>
                {body}
              </span>
            )}
          </li>
        );
      })}
    </ul>
  );
}
