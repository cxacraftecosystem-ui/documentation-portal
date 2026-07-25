"use client";

import { useEffect, useState } from "react";

import type { DropdownOption } from "@/components/ui/Dropdown";
import { apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { User, Workshop } from "@/lib/types";

/**
 * Shared vocabulary for the workshop-access screens (the request panel on /settings and the admin
 * queue + roster on /settings/workshop-access).
 *
 * One WorkshopAssignment row carries BOTH sides of the conversation — an admin's grant and a user's
 * request — so every endpoint in the family returns the same shape and every screen here reads it
 * through `WorkshopAccessRow`. `status` is the only thing that confers access: PENDING is a question,
 * not permission, and DENIED/REVOKED rows are kept rather than deleted so a refusal stays on record.
 */

export type WorkshopAccessLevel = "VIEW" | "CONTRIBUTE" | "EDIT";
export type WorkshopAccessStatus = "PENDING" | "GRANTED" | "DENIED" | "REVOKED";

export type WorkshopAccessLevelInfo = { level: WorkshopAccessLevel; description: string };

/** A WorkshopAssignment as `/workshops/{id}/assignments` and `/workshops/access-requests*` return it. */
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
  /** Present on the cross-workshop views (the queue and "my requests"); null on a per-workshop list. */
  workshop?: Workshop | null;
  user?: User | null;
  assignedBy?: User | null;
  requestedBy?: User | null;
  decidedBy?: User | null;
};

/**
 * One row of `GET /workshops/requestable` — a workshop the caller may ASK about, plus their own
 * standing on it.
 *
 * A DIFFERENT shape from `Workshop` on purpose, and the difference is the point. `GET /workshops` is
 * visibility-filtered: below professor it returns only what you created, so the request picker built
 * on it was empty for every newly onboarded user — precisely the people the feature exists for, and
 * precisely the workshops (other people's) worth asking about. `/workshops/requestable` is not
 * filtered, so it carries ONLY what is needed to name a workshop — no description, notes, artisans or
 * records — because the caller may have no access to its contents yet. Keep it that way; do not widen
 * this type to `Workshop`.
 */
export type RequestableWorkshop = {
  id: string;
  title: string;
  place?: string | null;
  date?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  /** The caller's own assignment row status; null when they have never asked and were never assigned. */
  accessStatus: WorkshopAccessStatus | null;
  /** The level on that same row — on a DENIED row this is the level refused, not one held. */
  accessLevel: WorkshopAccessLevel | null;
  /** Whether an admin has curated this workshop's roster at all. Open workshops need no request. */
  restricted: boolean;
};

/** Answer from `POST /workshops/access-requests` — one outcome per workshop asked for. */
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

/**
 * The ladder as the backend defines it (`GET /workshops/access-levels`), duplicated here as the
 * fallback so a grant/request UI never hands out a level it cannot explain — an admin choosing
 * between three unlabelled enum values is how the wrong level gets granted.
 */
export const FALLBACK_ACCESS_LEVELS: WorkshopAccessLevelInfo[] = [
  { level: "VIEW", description: "Read only — see this workshop's data. Cannot create or change records in it." },
  { level: "CONTRIBUTE", description: "Everything in View, plus add records in this workshop (the normal level)." },
  {
    level: "EDIT",
    description:
      "Everything in Contribute, plus change other people's records in this workshop (every change is tracked in the edit history)."
  }
];

/** The level ladder with its human definitions; falls back to the local copy if the call fails. */
export function useWorkshopAccessLevels() {
  const [levels, setLevels] = useState<WorkshopAccessLevelInfo[]>(FALLBACK_ACCESS_LEVELS);

  useEffect(() => {
    let cancelled = false;
    apiFetch<WorkshopAccessLevelInfo[]>("/workshops/access-levels")
      .then((rows) => {
        if (!cancelled && rows.length) setLevels(rows);
      })
      .catch(() => {
        /* keep the local copy — a missing legend must not break granting */
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
  DENIED: "border-error-600/25 bg-error-100 text-error-600",
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

/** What each level actually means, spelled out wherever one is handed out or asked for. */
export function AccessLevelLegend({ levels }: { levels: WorkshopAccessLevelInfo[] }) {
  return (
    <dl className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3 sm:grid-cols-3">
      {levels.map((entry) => (
        <div key={entry.level}>
          <dt className="text-xs font-semibold uppercase tracking-wide text-purple-700">
            {LEVEL_LABELS[entry.level] ?? entry.level}
          </dt>
          <dd className="mt-1 text-xs leading-5 text-ink-500">{entry.description}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * The parts of a workshop these helpers actually read. Structural rather than `Workshop` so the same
 * naming and ordering serve `RequestableWorkshop`, which deliberately carries fewer fields — a
 * workshop must be named the same way whether or not the reader may see inside it.
 */
type WorkshopNameParts = {
  title?: string | null;
  startDate?: string | null;
  date?: string | null;
  createdAt?: string | null;
};

/**
 * The date a workshop HAPPENED — Android parity with `WorkshopDetailDto.occurrenceDate()`
 * (`startDate ?: date ?: createdAt`).
 */
export function workshopOccurrence(workshop: WorkshopNameParts): string {
  return workshop.startDate ?? workshop.date ?? workshop.createdAt ?? "";
}

/** Clean human label for a workshop — title plus the day it ran, never an id. */
export function workshopLabel(workshop: WorkshopNameParts | null | undefined): string {
  if (!workshop) return "Untitled workshop";
  const title = workshop.title?.trim() || "Untitled workshop";
  const when = formatDate(workshopOccurrence(workshop) || null);
  return when === "-" ? title : `${title} · ${when}`;
}

/** Most recent by occurrence first. ISO-8601 strings compare chronologically, as on Android. */
export function sortWorkshopsByOccurrence<T extends WorkshopNameParts>(workshops: T[]): T[] {
  return [...workshops].sort((a, b) => workshopOccurrence(b).localeCompare(workshopOccurrence(a)));
}

/**
 * What the caller's standing on a workshop means for the request they are about to file, said in the
 * option label itself.
 *
 * The picker can only render strings, and a picker that offers to request something you already hold
 * is a picker that lies: the backend answers ALREADY_GRANTED and changes nothing, so the user comes
 * away thinking they asked. GRANTED is therefore stated and the option disabled (see
 * `requestableOptions`); PENDING is stated because asking again is a no-op the queue will not thank
 * you for; DENIED and REVOKED say plainly that asking again is allowed, because it is — the backend
 * re-opens those rows as fresh requests.
 */
const STANDING_NOTE: Record<WorkshopAccessStatus, string> = {
  GRANTED: "you already have this",
  PENDING: "waiting on an admin",
  // No em-dash inside these: the label already joins the standing on with one, and "Ajrakhpur —
  // declined — you can ask again" reads as two separate afterthoughts rather than one sentence.
  DENIED: "declined, you can ask again",
  REVOKED: "revoked, you can ask again"
};

/** Title · place · date, then the caller's standing. Place disambiguates two same-titled seasons. */
export function requestableWorkshopLabel(workshop: RequestableWorkshop): string {
  const place = workshop.place?.trim();
  const base = place ? `${workshopLabel(workshop)} · ${place}` : workshopLabel(workshop);
  if (workshop.accessStatus) {
    const level = workshop.accessStatus === "GRANTED" && workshop.accessLevel ? ` ${LEVEL_LABELS[workshop.accessLevel]}` : "";
    return `${base} — ${STANDING_NOTE[workshop.accessStatus]}${level}`;
  }
  // No row at all. On an uncurated workshop everyone already holds Contribute, so say so rather than
  // letting somebody queue for access they have had all along.
  return workshop.restricted ? base : `${base} — open to everyone`;
}

/** Picker options for the request panel: already-granted workshops are shown but not selectable. */
export function requestableOptions(workshops: RequestableWorkshop[]): DropdownOption[] {
  return workshops.map((workshop) => ({
    value: workshop.id,
    label: requestableWorkshopLabel(workshop),
    disabled: workshop.accessStatus === "GRANTED"
  }));
}

/** Name a person without ever leaking an id: their name, else their email, else a neutral fallback. */
export function personName(person: User | null | undefined): string {
  return person?.name?.trim() || person?.email?.trim() || "Unknown user";
}
