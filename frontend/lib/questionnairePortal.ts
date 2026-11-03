import { mostRecentRecordableWorkshop } from "@/components/forms/WorkshopSelect";
import { apiFetch, buildQuery } from "@/lib/api";
import type { TitleWorkshop } from "@/lib/naming";
import type { PageResult, Workshop } from "@/lib/types";

export const PORTAL = "documentation-portal" as const;
export const STORAGE_PREFIX = "field_repo_qv2";
export const BUNDLE_EXTENSION = ".frwq";
export const BUNDLE_PREFIX = "FieldRepository";
export const WORKSHOP_LABEL = "Workshop";
export const NO_WORKSHOP_LABEL = "Field visit (no workshop)";
export const WORKSHOP_PARAM = "workshopId" as const;

export type PortalWorkshop = {
  id: string;
  title: string;
  startDate: string | null;
  createdAt: string | null;
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

export async function listWorkshops(): Promise<PortalWorkshop[]> {
  const page = await apiFetch<PageResult<WorkshopRow>>(`/workshops${buildQuery({ page: 1, pageSize: 100 })}`);
  rows = page.items ?? [];
  return rows.map(portalWorkshop);
}

export async function defaultWorkshopId(workshops: PortalWorkshop[]): Promise<string | null> {
  const known = rows.filter((row) => workshops.some((w) => w.id === row.id));
  return mostRecentRecordableWorkshop(known)?.id ?? mostRecentWorkshop(workshops)?.id ?? null;
}

export function mostRecentWorkshop(workshops: PortalWorkshop[]): PortalWorkshop | null {
  const today = new Date().toISOString().slice(0, 10);
  const open = workshops.filter((w) => w.canRecord && (!w.startDate || w.startDate.slice(0, 10) <= today));
  open.sort((a, b) => (b.startDate ?? "").localeCompare(a.startDate ?? "") || (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
  return open[0] ?? null;
}

export function titleWorkshop(w: PortalWorkshop | null | undefined): TitleWorkshop | null {
  if (!w) return null;
  return { locality: w.locality, district: w.district, state: w.state, craft: w.craft };
}

export function workshopScope(id: string | null | undefined): { workshopId: string | null; designWorkshopId: null } {
  return { workshopId: id || null, designWorkshopId: null };
}

export function artisanFilter(id: string | null | undefined): Record<string, string | undefined> {
  return { workshopId: id || undefined };
}

export function workshopHref(id: string): string {
  return `/workshops/${encodeURIComponent(id)}`;
}

export type PortalForm = {
  id: string;
  title: string;
  kind: string | null;
  isDefault: boolean;
  isShared: boolean;
  ownerId: string | null;
};

type FormRow = { id: string; title: string; kind?: string | null; isDefault?: boolean; isShared?: boolean; ownerId?: string | null };

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

export const FORMS_HREF = "/questionnaires";
export function formHref(id: string): string {
  return `/questionnaires/${encodeURIComponent(id)}`;
}

export async function searchPeople(search: string): Promise<{ id: string; name: string }[]> {
  const people = await apiFetch<{ id: string; name: string }[]>(`/users/directory${buildQuery({ search: search || undefined })}`);
  return people.map((r) => ({ id: r.id, name: r.name }));
}

export async function coverageScope(workshopId: string): Promise<{ workshopId?: string; artisanIds?: string[] }> {
  return { workshopId };
}

export { AnswerTextEditor, answerEditorValue, answerParts } from "@/components/questionnaires/AnswerTextEditor";

export { FilterMultiSelect, type FilterMultiSelectProps } from "@/components/questionnaires/FilterMultiSelect";
