/**
 * The craft list's write calls and the admin vocabulary (REQUIREMENTS_2026-10-20 §2.3, §2.6).
 *
 * Every write invalidates the cached catalogue, so the next picker that opens reads the new list.
 */

import { apiFetch } from "@/lib/api";
import { invalidateCraftCatalogue, type CraftCatalogueCategory, type GiStatus } from "@/lib/craftCatalogue";
import { isAdmin } from "@/lib/permissions";
import type { Craft, User } from "@/lib/types";

/** The 28 states and 8 union territories, as the master list spells them, then "All India". */
export const CRAFT_STATE_OPTIONS: readonly string[] = [
  "Andhra Pradesh",
  "Arunachal Pradesh",
  "Assam",
  "Bihar",
  "Chhattisgarh",
  "Goa",
  "Gujarat",
  "Haryana",
  "Himachal Pradesh",
  "Jharkhand",
  "Karnataka",
  "Kerala",
  "Madhya Pradesh",
  "Maharashtra",
  "Manipur",
  "Meghalaya",
  "Mizoram",
  "Nagaland",
  "Odisha",
  "Punjab",
  "Rajasthan",
  "Sikkim",
  "Tamil Nadu",
  "Telangana",
  "Tripura",
  "Uttar Pradesh",
  "Uttarakhand",
  "West Bengal",
  "Andaman and Nicobar Islands",
  "Chandigarh",
  "Dadra and Nagar Haveli and Daman and Diu",
  "Delhi",
  "Jammu and Kashmir",
  "Ladakh",
  "Lakshadweep",
  "Puducherry",
  "All India"
];

export const GI_LABEL: Record<GiStatus, string> = { GI: "GI", NON_GI: "Non GI" };

/**
 * Who manages the categories, merges and adopts: the content admins (§5.1). The perms stream
 * publishes `isContentAdmin` in `lib/permissions`; until it lands this reads the same set.
 */
export function canAdministerCraftList(user: User | null | undefined): boolean {
  // The Field Repository's role list has no Ministry Admin yet, so the role is read as a plain string
  // here; the Designer Repository compares it directly. This is the one line the two copies differ on.
  return Boolean(user) && (isAdmin(user) || (user?.role as string | undefined) === "MINISTRY_ADMIN");
}

export type CraftWriteBody = {
  name: string;
  subCategoryId: string;
  localName?: string | null;
  stateOfOrigin: string[];
  stateOfPractice: string[];
  giStatus: GiStatus;
  description?: string | null;
  workshopId?: string | null;
  extraMetadata?: Record<string, unknown>;
};

async function write<T>(path: string, method: "POST" | "PATCH" | "DELETE", body?: unknown): Promise<T> {
  const answer = await apiFetch<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body) });
  invalidateCraftCatalogue();
  return answer;
}

export const createCraft = (body: CraftWriteBody) => write<Craft>("/crafts", "POST", body);
export const updateCraft = (id: string, body: Partial<CraftWriteBody>) => write<Craft>(`/crafts/${encodeURIComponent(id)}`, "PATCH", body);
export const deleteCraft = (id: string) => write<void>(`/crafts/${encodeURIComponent(id)}`, "DELETE");
export const mergeCraft = (id: string, intoId: string) => write<Craft>(`/crafts/${encodeURIComponent(id)}/merge`, "POST", { intoId });
export const adoptCraft = (id: string, subCategoryId: string) =>
  write<Craft>(`/crafts/${encodeURIComponent(id)}/adopt`, "POST", { subCategoryId });

export const listCraftCategories = () => apiFetch<CraftCatalogueCategory[]>("/craft-categories");
export const createCraftCategory = (name: string) => write<CraftCatalogueCategory>("/craft-categories", "POST", { name });
export const updateCraftCategory = (id: string, body: { name?: string; sortOrder?: number }) =>
  write<CraftCatalogueCategory>(`/craft-categories/${encodeURIComponent(id)}`, "PATCH", body);
export const deleteCraftCategory = (id: string) => write<void>(`/craft-categories/${encodeURIComponent(id)}`, "DELETE");
export const createCraftSubCategory = (name: string, categoryId: string) =>
  write<unknown>("/craft-sub-categories", "POST", { name, categoryId });
export const updateCraftSubCategory = (id: string, body: { name?: string; categoryId?: string; sortOrder?: number }) =>
  write<unknown>(`/craft-sub-categories/${encodeURIComponent(id)}`, "PATCH", body);
export const deleteCraftSubCategory = (id: string) => write<void>(`/craft-sub-categories/${encodeURIComponent(id)}`, "DELETE");

/** One row of `GET /crafts/review`: the mapping script's dry run, as the Needs review tab reads it. */
export type CraftReviewEntry = {
  kind: "craft" | "freeText";
  id: string;
  /** The Prisma delegate the row lives in: `craft`, `artisan`, `productdocumentation`, `tooldocumentation`… */
  model: string;
  /** The craft's own name, or the record's leftover typed craft text. */
  text: string;
  /** What the mapping would do: `inPlace`, `merge`, `adopt`, `legacy`, `proposed` or `unmatched`. */
  action: string;
  /** The suggested or planned craft, as "slug (#serial)", and its id. */
  target: string | null;
  targetId: string | null;
  subCategoryId: string | null;
  /** The fuzzy match's score (0–1) when `action` is `proposed`. */
  score: number | null;
  references: number;
};

export type CraftReviewQueueData = { crafts: CraftReviewEntry[]; records: CraftReviewEntry[] };

export async function loadCraftReviewQueue(): Promise<CraftReviewQueueData> {
  const body = await apiFetch<Partial<CraftReviewQueueData> | null>("/crafts/review");
  return { crafts: body?.crafts ?? [], records: body?.records ?? [] };
}

/** The record types whose leftover craft text can be given a craft here, by delegate. */
export const PICK_CRAFT_RECORDS: Record<string, { type: "artisan" | "product" | "tool"; noun: string; path: string }> = {
  artisan: { type: "artisan", noun: "Artisan", path: "/artisans" },
  productdocumentation: { type: "product", noun: "Product", path: "/products" },
  tooldocumentation: { type: "tool", noun: "Tool", path: "/tools" }
};

/** Give a record with leftover craft text a craft from the list. A tool's crafts are its `craftIds`. */
export function pickCraftForRecord(model: string, id: string, craftId: string): Promise<unknown> {
  const target = PICK_CRAFT_RECORDS[model];
  if (!target) return Promise.reject(new Error("This record can't take a craft here."));
  const body = target.type === "tool" ? { craftIds: [craftId] } : { craftId };
  return write<unknown>(`${target.path}/${encodeURIComponent(id)}`, "PATCH", body);
}

/**
 * The one write that moves item `index` one place up (-1) or down (+1); null at either end.
 *
 * The server reads `sortOrder` as a 1-based position and renumbers the rest around it
 * (`craft_categories._reorder`), so a single PATCH moves the row. Two swapped writes would leave
 * two rows on the same number if the second failed.
 */
export function reorderMove<T extends { id: string }>(
  items: readonly T[],
  index: number,
  step: -1 | 1
): { id: string; sortOrder: number } | null {
  const other = index + step;
  if (index < 0 || index >= items.length || other < 0 || other >= items.length) return null;
  return { id: items[index].id, sortOrder: other + 1 };
}
