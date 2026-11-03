"use client";

import { useEffect, useState } from "react";

import type { DropdownOption } from "@/components/ui/Dropdown";
import { apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { User, Workshop } from "@/lib/types";
import { hasTypedTitle, workshopDisplayTitle } from "@/lib/workshopTitle";

export type WorkshopAccessLevel = "VIEW" | "CONTRIBUTE" | "EDIT";
export type WorkshopAccessStatus = "PENDING" | "GRANTED" | "DENIED" | "REVOKED";

export type WorkshopAccessLevelInfo = { level: WorkshopAccessLevel; description: string };

export type WorkshopAccessRow = {
  id: string;
  workshopId: string;
  userId: string;
  accessLevel: WorkshopAccessLevel;
  status: WorkshopAccessStatus;
  requestNote?: string | null;
  decisionNote?: string | null;
  decidedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  workshop?: Workshop | null;
  user?: User | null;
  assignedBy?: User | null;
  requestedBy?: User | null;
  decidedBy?: User | null;
};

export type RequestableWorkshop = {
  id: string;
  title: string;
  place?: string | null;
  date?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  accessStatus: WorkshopAccessStatus | null;
  accessLevel: WorkshopAccessLevel | null;
  restricted: boolean;
};

export type WorkshopAccessRequestResult = {
  outcomes: Array<{ workshopId: string; outcome: "CREATED" | "RE_REQUESTED" | "ALREADY_PENDING" | "ALREADY_GRANTED" }>;
  requests: WorkshopAccessRow[];
};

export const DEFAULT_ACCESS_LEVEL: WorkshopAccessLevel = "CONTRIBUTE";

export const LEVEL_LABELS: Record<WorkshopAccessLevel, string> = {
  VIEW: "View",
  CONTRIBUTE: "Contribute",
  EDIT: "Edit"
};

export const FALLBACK_ACCESS_LEVELS: WorkshopAccessLevelInfo[] = [
  { level: "VIEW", description: "Read only." },
  { level: "CONTRIBUTE", description: "View and add records." },
  {
    level: "EDIT",
    description:
      "Add records and edit others’ (tracked)."
  }
];

export function useWorkshopAccessLevels() {
  const [levels, setLevels] = useState<WorkshopAccessLevelInfo[]>(FALLBACK_ACCESS_LEVELS);

  useEffect(() => {
    let cancelled = false;
    apiFetch<WorkshopAccessLevelInfo[]>("/workshops/access-levels")
      .then((rows) => {
        if (!cancelled && rows.length) setLevels(rows);
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return levels;
}

export function levelOptions(levels: WorkshopAccessLevelInfo[]): DropdownOption[] {
  return levels.map((entry) => ({ value: entry.level, label: LEVEL_LABELS[entry.level] ?? entry.level }));
}

const LEVEL_TONE: Record<WorkshopAccessLevel, string> = {
  VIEW: "border-line-200 bg-surface-50 text-ink-700",
  CONTRIBUTE: "border-purple-300 bg-purple-50 text-purple-700",
  EDIT: "border-purple-700/30 bg-purple-100 text-purple-800"
};

const STATUS_TONE: Record<WorkshopAccessStatus, string> = {
  PENDING: "border-amber-500/30 bg-amber-100 text-amber-800",
  GRANTED: "border-success-600/25 bg-success-100 text-success-600",
  DENIED: "border-error-600/25 bg-error-100 text-red-800",
  REVOKED: "border-line-200 bg-surface-50 text-ink-500"
};

const STATUS_LABELS: Record<WorkshopAccessStatus, string> = {
  PENDING: "Pending",
  GRANTED: "Granted",
  DENIED: "Denied",
  REVOKED: "Revoked"
};

export function AccessLevelBadge({ level }: { level: WorkshopAccessLevel }) {
  return (
    <span className={`inline-block rounded-full border px-2.5 py-1 text-xs font-medium ${LEVEL_TONE[level] ?? LEVEL_TONE.VIEW}`}>
      {LEVEL_LABELS[level] ?? level}
    </span>
  );
}

export function AccessStatusBadge({ status }: { status: WorkshopAccessStatus }) {
  return (
    <span
      className={`inline-block rounded-full border px-2.5 py-1 text-xs font-medium ${STATUS_TONE[status] ?? STATUS_TONE.REVOKED}`}
    >
      {STATUS_LABELS[status] ?? status}
    </span>
  );
}

export function AccessLevelLegend({ levels }: { levels: WorkshopAccessLevelInfo[] }) {
  return (
    <dl className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3 sm:grid-cols-3">
      {levels.map((entry) => (
        <div key={entry.level}>
          <dt className="text-xs font-semibold uppercase tracking-wide text-purple-700 dark:text-purple-300">
            {LEVEL_LABELS[entry.level] ?? entry.level}
          </dt>
          <dd className="mt-1 text-xs leading-5 text-ink-500">{entry.description}</dd>
        </div>
      ))}
    </dl>
  );
}

type WorkshopNameParts = {
  title?: string | null;
  startDate?: string | null;
  date?: string | null;
  createdAt?: string | null;
};

export function workshopOccurrence(workshop: WorkshopNameParts): string {
  return workshop.startDate ?? workshop.date ?? workshop.createdAt ?? "";
}

export function workshopLabel(workshop: WorkshopNameParts | null | undefined): string {
  if (!workshop || !hasTypedTitle(workshop)) return workshopDisplayTitle(workshop);
  const title = workshopDisplayTitle(workshop);
  const when = formatDate(workshopOccurrence(workshop) || null);
  return when === "-" ? title : `${title} · ${when}`;
}

export function sortWorkshopsByOccurrence<T extends WorkshopNameParts>(workshops: T[]): T[] {
  return [...workshops].sort((a, b) => workshopOccurrence(b).localeCompare(workshopOccurrence(a)));
}

const STANDING_NOTE: Record<WorkshopAccessStatus, string> = {
  GRANTED: "you already have this",
  PENDING: "waiting on an admin",
  DENIED: "declined, you can ask again",
  REVOKED: "revoked, you can ask again"
};

export function requestableWorkshopLabel(workshop: RequestableWorkshop): string {
  const place = workshop.place?.trim();
  const base = place ? `${workshopLabel(workshop)} · ${place}` : workshopLabel(workshop);
  if (workshop.accessStatus) {
    const level = workshop.accessStatus === "GRANTED" && workshop.accessLevel ? ` ${LEVEL_LABELS[workshop.accessLevel]}` : "";
    return `${base} — ${STANDING_NOTE[workshop.accessStatus]}${level}`;
  }
  return workshop.restricted ? base : `${base} — open to everyone`;
}

export function requestableOptions(workshops: RequestableWorkshop[]): DropdownOption[] {
  return workshops.map((workshop) => ({
    value: workshop.id,
    label: requestableWorkshopLabel(workshop),
    disabled: workshop.accessStatus === "GRANTED"
  }));
}

export function personName(person: User | null | undefined): string {
  return person?.name?.trim() || person?.email?.trim() || "Unknown user";
}
