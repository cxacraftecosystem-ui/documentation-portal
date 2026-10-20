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
  // The cast keeps this line the same in the Field Repository, whose ladder has no Ministry Admin yet.
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

/** The `sortOrder` that moves item `index` one place up (-1) or down (+1); null at either end. */
export function swapSortOrder<T extends { id: string; sortOrder: number }>(
  items: readonly T[],
  index: number,
  step: -1 | 1
): { id: string; sortOrder: number }[] | null {
  const other = index + step;
  if (index < 0 || other < 0 || other >= items.length) return null;
  return [
    { id: items[index].id, sortOrder: items[other].sortOrder },
    { id: items[other].id, sortOrder: items[index].sortOrder }
  ];
}
