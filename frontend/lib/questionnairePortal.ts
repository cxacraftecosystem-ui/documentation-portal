/**
 * THIS PORTAL'S SIDE OF THE SHARED QUESTIONNAIRE SCREENS (Field Repository).
 *
 * `lib/questionnaire/**` and `components/questionnaire/**` are byte-identical in designer-portal and
 * Field Repository (`shared/questionnaire.sha256`), synced from designer-portal. Everything that
 * differs between the two portals is answered here, under this one name, and each portal keeps its
 * own copy of this file. A shared screen imports nothing else portal-own
 * (`shared/questionnaire-web-imports.json`).
 *
 * Field Repository: a session is filed under a WORKSHOP (`workshopId`); the default is the most
 * recent workshop the researcher may record in (B30); the title's workshop half is the workshop's
 * village (else its place), district, state and single craft — what `app/questionnaire/titles.py`
 * reads for a workshop.
 */

import { mostRecentRecordableWorkshop } from "@/components/forms/WorkshopSelect";
import { apiFetch, buildQuery } from "@/lib/api";
import type { TitleWorkshop } from "@/lib/naming";
import type { PageResult, Workshop } from "@/lib/types";

export const PORTAL = "documentation-portal" as const;
/** Where the shared screens keep their per-device lists; Field Repository's existing key prefix. */
export const STORAGE_PREFIX = "field_repo_qv2";
/** The bundle file this portal writes (`shared/questionnaire-bundle.json`); both are read. */
export const BUNDLE_EXTENSION = ".frwq";
export const BUNDLE_PREFIX = "FieldRepository";
/** How the start sheet names the workshop field. */
export const WORKSHOP_LABEL = "Workshop";
/** The option for a session filed under no workshop (a field visit). */
export const NO_WORKSHOP_LABEL = "Field visit (no workshop)";
/** The query parameter a session, a coverage grid and an artisan list are scoped with. */
export const WORKSHOP_PARAM = "workshopId" as const;

/** A workshop as the shared screens read it. */
export type PortalWorkshop = {
  id: string;
  title: string;
  /** The day it ran (yyyy-mm-dd), for the default rule and the label. */
  startDate: string | null;
  createdAt: string | null;
  /** The workshop's type (its type default applies, owner amendment 2). */
  kind: string | null;
  locality: string | null;
  district: string | null;
  state: string | null;
  craft: string | null;
  canRecord: boolean;
};

export type WorkshopRow = Workshop & {
  canRecord?: boolean;
  location?: { village?: string | null; district?: string | null; state?: string | null } | null;
  crafts?: { craft?: { name?: string | null } | null }[];
};

/** A workshop row as the shared screens read it (exported for the title spec). */
export function portalWorkshop(row: WorkshopRow): PortalWorkshop {
  const loc = row.location ?? null;
  const crafts = [...new Set((row.crafts ?? []).map((c) => c.craft?.name).filter((n): n is string => Boolean(n)))].sort();
  const locality = loc?.village || (loc?.district || loc?.state ? null : row.place || null);
  return {
    id: row.id,
    title: row.title,
    startDate: (row.startDate ?? row.date ?? null) || null,
    createdAt: row.createdAt ?? null,
    kind: row.workshopType ?? null,
    locality: locality ?? null,
    district: loc?.district ?? null,
    state: loc?.state ?? null,
    craft: crafts.length === 1 ? crafts[0] : null,
    canRecord: row.canRecord !== false
  };
}

let rows: WorkshopRow[] = [];

/** The workshops this account may see (the server scopes the list). */
export async function listWorkshops(): Promise<PortalWorkshop[]> {
  const page = await apiFetch<PageResult<WorkshopRow>>(`/workshops${buildQuery({ page: 1, pageSize: 100 })}`);
  rows = page.items ?? [];
  return rows.map(portalWorkshop);
}

/** B30: the most recent workshop the researcher may record in, by the day it ran (IST). */
export async function defaultWorkshopId(workshops: PortalWorkshop[]): Promise<string | null> {
  const known = rows.filter((row) => workshops.some((w) => w.id === row.id));
  return mostRecentRecordableWorkshop(known)?.id ?? mostRecentWorkshop(workshops)?.id ?? null;
}

/** The most recent workshop that may still be recorded in, by the day it ran, then by when it was entered. */
export function mostRecentWorkshop(workshops: PortalWorkshop[]): PortalWorkshop | null {
  const today = new Date().toISOString().slice(0, 10);
  const open = workshops.filter((w) => w.canRecord && (!w.startDate || w.startDate.slice(0, 10) <= today));
  open.sort((a, b) => (b.startDate ?? "").localeCompare(a.startDate ?? "") || (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
  return open[0] ?? null;
}

/** The title's workshop half, as the server builds it for a workshop. */
export function titleWorkshop(w: PortalWorkshop | null | undefined): TitleWorkshop | null {
  if (!w) return null;
  return { locality: w.locality, district: w.district, state: w.state, craft: w.craft };
}

/** `{workshopId}` for a request body or query, or nothing when no workshop is chosen. */
export function workshopScope(id: string | null | undefined): { workshopId: string | null; designWorkshopId: null } {
  return { workshopId: id || null, designWorkshopId: null };
}

/** The artisan list's filter for one workshop. */
export function artisanFilter(id: string | null | undefined): Record<string, string | undefined> {
  return { workshopId: id || undefined };
}

/** Where a workshop's own page is. */
export function workshopHref(id: string): string {
  return `/workshops/${encodeURIComponent(id)}`;
}

/** A form as the start sheet and the coverage page list it. */
export type PortalForm = {
  id: string;
  title: string;
  kind: string | null;
  isDefault: boolean;
  isShared: boolean;
  ownerId: string | null;
};

type FormRow = { id: string; title: string; kind?: string | null; isDefault?: boolean; isShared?: boolean; ownerId?: string | null };

/** Every live form (`GET /questionnaires` answers the whole live list here). */
export async function listForms(): Promise<PortalForm[]> {
  const page = await apiFetch<PageResult<FormRow> | FormRow[]>("/questionnaires");
  const list = Array.isArray(page) ? page : (page.items ?? []);
  return list.map((r) => ({
    id: r.id,
    title: r.title,
    kind: r.kind ?? null,
    isDefault: Boolean(r.isDefault),
    isShared: Boolean(r.isShared),
    ownerId: r.ownerId ?? null
  }));
}

/** The forms library page and one form's page. */
export const FORMS_HREF = "/questionnaires";
export function formHref(id: string): string {
  return `/questionnaires/${encodeURIComponent(id)}`;
}

/** A colleague to add as a session researcher (B6): the directory every signed-in user may read. */
export async function searchPeople(search: string): Promise<{ id: string; name: string }[]> {
  const people = await apiFetch<{ id: string; name: string }[]>(`/users/directory${buildQuery({ search: search || undefined })}`);
  return people.map((r) => ({ id: r.id, name: r.name }));
}

/** The workshop coverage page's scope (B12): a workshop is a coverage column of its own here. */
export async function coverageScope(workshopId: string): Promise<{ workshopId?: string; artisanIds?: string[] }> {
  return { workshopId };
}

/**
 * The answer box's editor (decision C8): this portal's rich-text editor, sending the canonical plain
 * text with the document beside it; see `AnswerTextEditor`.
 */
export { AnswerTextEditor, answerEditorValue, answerParts } from "@/components/questionnaires/AnswerTextEditor";
