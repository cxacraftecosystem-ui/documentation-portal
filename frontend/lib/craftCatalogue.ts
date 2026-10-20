"use client";

/**
 * The craft list every picker reads — `GET /crafts/catalogue` (REQUIREMENTS_2026-10-20 §2.3).
 *
 * Byte-identical in designer-portal and documentation-portal (the portals will merge); a parity
 * test in each repo pins it. It depends only on modules both repos carry: `lib/api` and
 * `lib/unifiedSearch`.
 *
 * - One fetch per tab, shared by every `CraftSelect` on the page; revalidated with the server's
 *   `ETag` (a 304 costs no body) at most once a minute while the tab is open.
 * - The last good answer is kept in this browser so a picker still opens offline. A first run
 *   offline with no copy throws, and the picker says "Connect once to load the craft list."
 * - The search rules (fold, rank, group) are pure functions below, so the e2e unit specs hold them
 *   without a browser, and Android's `CraftPicker.kt` ports the same rules.
 */

import { useCallback, useEffect, useState } from "react";

import { ApiError, apiFetchWithHeaders } from "@/lib/api";
import { appSearchScore } from "@/lib/unifiedSearch";

export const CRAFT_CATALOGUE_PATH = "/crafts/catalogue";

/** Where the last good catalogue is kept in this browser. */
export const CRAFT_CATALOGUE_STORAGE_KEY = "craft_catalogue_v1";

/** A revalidation inside this window reuses the answer already in memory. */
export const CRAFT_CATALOGUE_REVALIDATE_MS = 60_000;

export const CRAFT_NO_MATCH = (query: string) => `No craft matches “${query}”.`;
export const CRAFT_ADD_TO_LIST = "Add it to the list";
export const CRAFT_OFFLINE_FIRST_RUN = "Connect once to load the craft list.";
export const CRAFT_NOT_IN_LIST = "Pick a craft from the list.";

export type GiStatus = "GI" | "NON_GI";
export type CraftStatus = "ACTIVE" | "LEGACY" | "MERGED";

export type CraftCatalogueSubCategory = {
  id: string;
  slug: string;
  name: string;
  sortOrder: number;
  craftCount: number;
};

export type CraftCatalogueCategory = {
  id: string;
  slug: string;
  name: string;
  sortOrder: number;
  subCategories: CraftCatalogueSubCategory[];
};

export type CatalogueCraft = {
  id: string;
  slug: string;
  name: string;
  localName: string | null;
  categoryId: string | null;
  subCategoryId: string | null;
  sourceSerial: number | null;
  stateOfOrigin: string[];
  stateOfPractice: string[];
  giStatus: GiStatus | null;
  isCatchAll: boolean;
  craftCode: string | null;
  aliases: string[];
  /** Only with `?includeInactive=true` (content admins). */
  status?: CraftStatus;
  mergedIntoId?: string | null;
  referenceCount?: number;
};

export type CraftCatalogue = {
  version: string;
  categories: CraftCatalogueCategory[];
  crafts: CatalogueCraft[];
};

type Stored = { etag: string | null; fetchedAt: number; catalogue: CraftCatalogue };

// ---------------------------------------------------------------------------------------------
// Pure rules
// ---------------------------------------------------------------------------------------------

/** Case- and accent-folded, `&` read as "and", punctuation and hyphens as spaces, runs collapsed. */
export function foldCraftText(text: string | null | undefined): string {
  return (text ?? "")
    .normalize("NFKD")
    .replace(/\p{M}+/gu, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim()
    .replace(/\s+/g, " ");
}

export type CraftIndex = {
  catalogue: CraftCatalogue;
  byId: Map<string, CatalogueCraft>;
  subCategoryById: Map<string, CraftCatalogueSubCategory & { categoryId: string }>;
  categoryById: Map<string, CraftCatalogueCategory>;
  /** Catalogue order of each craft, the tie-break for every ranking. */
  position: Map<string, number>;
};

const indexes = new WeakMap<CraftCatalogue, CraftIndex>();

export function craftIndex(catalogue: CraftCatalogue): CraftIndex {
  const cached = indexes.get(catalogue);
  if (cached) return cached;
  const byId = new Map<string, CatalogueCraft>();
  const position = new Map<string, number>();
  catalogue.crafts.forEach((craft, index) => {
    byId.set(craft.id, craft);
    position.set(craft.id, index);
  });
  const categoryById = new Map<string, CraftCatalogueCategory>();
  const subCategoryById = new Map<string, CraftCatalogueSubCategory & { categoryId: string }>();
  for (const category of catalogue.categories) {
    categoryById.set(category.id, category);
    for (const sub of category.subCategories) subCategoryById.set(sub.id, { ...sub, categoryId: category.id });
  }
  const built = { catalogue, byId, subCategoryById, categoryById, position };
  indexes.set(catalogue, built);
  return built;
}

export function craftCategoryName(index: CraftIndex, craft: CatalogueCraft): string {
  const sub = craft.subCategoryId ? index.subCategoryById.get(craft.subCategoryId) : undefined;
  const categoryId = craft.categoryId ?? sub?.categoryId ?? null;
  return (categoryId && index.categoryById.get(categoryId)?.name) || "";
}

export function craftSubCategoryName(index: CraftIndex, craft: CatalogueCraft): string {
  return (craft.subCategoryId && index.subCategoryById.get(craft.subCategoryId)?.name) || "";
}

/** "Category › Sub-category" — the heading a craft is listed under. */
export function craftPath(index: CraftIndex, craft: CatalogueCraft): string {
  const category = craftCategoryName(index, craft);
  const sub = craftSubCategoryName(index, craft);
  if (category && sub) return `${category} › ${sub}`;
  return category || sub || "Not on the list";
}

/** The muted line under a craft's name: its states of practice. */
export function craftStatesLine(craft: CatalogueCraft): string {
  return craft.stateOfPractice.join(", ");
}

/**
 * How well `craft` answers `query`; lower is better, `null` is no match.
 *
 * The name ranks first (0-3, `appSearchScore`'s exact / prefix / word prefix / substring), then
 * local name, slug and aliases (10-13), then states and sub-category (20-23). A query of several
 * words that no single field holds still matches when every word is found somewhere (30).
 */
export function craftSearchScore(index: CraftIndex, craft: CatalogueCraft, query: string): number | null {
  const q = foldCraftText(query);
  if (!q) return 0;
  const name = appSearchScore(foldCraftText(craft.name), q);
  if (name < 4) return name;
  const second = [craft.localName, craft.slug, ...craft.aliases].map((text) => appSearchScore(foldCraftText(text), q));
  const bestSecond = Math.min(4, ...second);
  if (bestSecond < 4) return 10 + bestSecond;
  const third = [...craft.stateOfPractice, ...craft.stateOfOrigin, craftSubCategoryName(index, craft), craftCategoryName(index, craft)].map(
    (text) => appSearchScore(foldCraftText(text), q)
  );
  const bestThird = Math.min(4, ...third);
  if (bestThird < 4) return 20 + bestThird;
  const words = q.split(" ");
  if (words.length > 1) {
    const haystack = foldCraftText(
      [
        craft.name,
        craft.localName,
        craft.slug,
        ...craft.aliases,
        ...craft.stateOfPractice,
        ...craft.stateOfOrigin,
        craftSubCategoryName(index, craft),
        craftCategoryName(index, craft)
      ].join(" ")
    );
    if (words.every((word) => haystack.includes(word))) return 30;
  }
  return null;
}

/** The crafts that answer `query`, best first; catalogue order breaks ties. Empty query = all. */
export function searchCraftCatalogue(catalogue: CraftCatalogue, query: string): CatalogueCraft[] {
  const index = craftIndex(catalogue);
  if (!foldCraftText(query)) return catalogue.crafts.slice();
  const scored: { craft: CatalogueCraft; score: number; at: number }[] = [];
  for (const craft of catalogue.crafts) {
    const score = craftSearchScore(index, craft, query);
    if (score === null) continue;
    scored.push({ craft, score, at: index.position.get(craft.id) ?? 0 });
  }
  scored.sort((a, b) => a.score - b.score || a.at - b.at);
  return scored.map((row) => row.craft);
}

export type CraftGroup = { key: string; heading: string; crafts: CatalogueCraft[] };

/**
 * `crafts` under their "Category › Sub-category" headings. A group appears where its first member
 * does, so a ranked list keeps its best match on top and still reads by heading.
 */
export function groupCrafts(catalogue: CraftCatalogue, crafts: readonly CatalogueCraft[]): CraftGroup[] {
  const index = craftIndex(catalogue);
  const groups = new Map<string, CraftGroup>();
  for (const craft of crafts) {
    const key = craft.subCategoryId ?? craft.categoryId ?? "none";
    let group = groups.get(key);
    if (!group) {
      group = { key, heading: craftPath(index, craft), crafts: [] };
      groups.set(key, group);
    }
    group.crafts.push(craft);
  }
  return [...groups.values()];
}

/**
 * The one craft whose name, slug or alias folds to `text` — for a prefill or an old record's free
 * text. Two or more candidates is no answer: guessing a craft is worse than asking.
 */
export function findCraftByText(catalogue: CraftCatalogue, text: string | null | undefined): CatalogueCraft | null {
  const q = foldCraftText(text);
  if (!q) return null;
  const exact = catalogue.crafts.filter((craft) => foldCraftText(craft.name) === q || foldCraftText(craft.slug) === q);
  if (exact.length === 1) return exact[0];
  if (exact.length > 1) return null;
  const alias = catalogue.crafts.filter((craft) => craft.aliases.some((a) => foldCraftText(a) === q));
  return alias.length === 1 ? alias[0] : null;
}

/** The catalogue minus nothing, as one sentence of counts: "455 crafts in 9 categories". */
export function catalogueCountLine(catalogue: CraftCatalogue): string {
  const crafts = catalogue.crafts.length;
  const categories = catalogue.categories.length;
  return `${crafts} ${crafts === 1 ? "craft" : "crafts"} in ${categories} ${categories === 1 ? "category" : "categories"}`;
}

/** Reads a stored or fetched body defensively: a missing array is empty, never a crash. */
export function normaliseCatalogue(raw: unknown): CraftCatalogue | null {
  if (!raw || typeof raw !== "object") return null;
  const body = raw as Partial<CraftCatalogue>;
  if (!Array.isArray(body.crafts) || !Array.isArray(body.categories)) return null;
  return {
    version: typeof body.version === "string" ? body.version : "",
    categories: body.categories.map((category) => ({
      ...category,
      subCategories: Array.isArray(category.subCategories) ? category.subCategories : []
    })),
    crafts: body.crafts.map((craft) => ({
      ...craft,
      localName: craft.localName ?? null,
      categoryId: craft.categoryId ?? null,
      subCategoryId: craft.subCategoryId ?? null,
      sourceSerial: craft.sourceSerial ?? null,
      stateOfOrigin: Array.isArray(craft.stateOfOrigin) ? craft.stateOfOrigin : [],
      stateOfPractice: Array.isArray(craft.stateOfPractice) ? craft.stateOfPractice : [],
      giStatus: craft.giStatus ?? null,
      isCatchAll: Boolean(craft.isCatchAll),
      craftCode: craft.craftCode ?? null,
      aliases: Array.isArray(craft.aliases) ? craft.aliases : []
    }))
  };
}

// ---------------------------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------------------------

let memory: Stored | null = null;
let inFlight: Promise<CraftCatalogue> | null = null;
const listeners = new Set<(catalogue: CraftCatalogue) => void>();

function readStored(): Stored | null {
  if (memory) return memory;
  try {
    if (typeof window === "undefined") return null;
    const text = window.localStorage.getItem(CRAFT_CATALOGUE_STORAGE_KEY);
    if (!text) return null;
    const parsed = JSON.parse(text) as Partial<Stored>;
    const catalogue = normaliseCatalogue(parsed.catalogue);
    if (!catalogue) return null;
    // fetchedAt 0: a stored copy is shown at once and still revalidated on first use.
    memory = { etag: typeof parsed.etag === "string" ? parsed.etag : null, fetchedAt: 0, catalogue };
    return memory;
  } catch {
    return null;
  }
}

function writeStored(next: Stored) {
  memory = next;
  try {
    window.localStorage.setItem(CRAFT_CATALOGUE_STORAGE_KEY, JSON.stringify(next));
  } catch {
    // Storage full or blocked: memory still serves this tab.
  }
  for (const listener of listeners) listener(next.catalogue);
}

/** The catalogue already on hand (this tab or this browser), without a request. */
export function peekCraftCatalogue(): CraftCatalogue | null {
  return readStored()?.catalogue ?? null;
}

/**
 * The catalogue, revalidated against the server. `force` skips the one-minute window — use it after
 * an admin write. Offline with a stored copy answers that copy; offline with none throws.
 */
export function loadCraftCatalogue(options: { force?: boolean } = {}): Promise<CraftCatalogue> {
  const stored = readStored();
  if (stored && !options.force && Date.now() - stored.fetchedAt < CRAFT_CATALOGUE_REVALIDATE_MS) {
    return Promise.resolve(stored.catalogue);
  }
  if (inFlight) return inFlight;
  const headers: Record<string, string> = {};
  if (stored?.etag) headers["If-None-Match"] = stored.etag;
  inFlight = apiFetchWithHeaders<unknown>(CRAFT_CATALOGUE_PATH, { headers })
    .then(({ body, headers: answer }) => {
      const catalogue = normaliseCatalogue(body);
      if (!catalogue) throw new Error("The craft list could not be read.");
      writeStored({ etag: answer.get("etag"), fetchedAt: Date.now(), catalogue });
      return catalogue;
    })
    .catch((error: unknown) => {
      if (stored && error instanceof ApiError && error.status === 304) {
        writeStored({ ...stored, fetchedAt: Date.now() });
        return stored.catalogue;
      }
      // Offline, or the server could not answer: the stored copy keeps every picker working.
      if (stored) return stored.catalogue;
      if (!(error instanceof ApiError)) throw new Error(CRAFT_OFFLINE_FIRST_RUN);
      throw error;
    })
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}

/** After an admin write: the next read goes to the server. */
export function invalidateCraftCatalogue() {
  if (memory) memory = { ...memory, fetchedAt: 0 };
}

/** Admin read: every craft, LEGACY and MERGED included, with reference counts. Never cached. */
export async function loadFullCraftCatalogue(): Promise<CraftCatalogue> {
  const { body } = await apiFetchWithHeaders<unknown>(`${CRAFT_CATALOGUE_PATH}?includeInactive=true`);
  const catalogue = normaliseCatalogue(body);
  if (!catalogue) throw new Error("The craft list could not be read.");
  return catalogue;
}

export type CraftCatalogueState = {
  catalogue: CraftCatalogue | null;
  error: string | null;
  loading: boolean;
  reload: () => void;
};

/** The catalogue for a component: the stored copy at once, then the revalidated one. */
export function useCraftCatalogue(): CraftCatalogueState {
  const [catalogue, setCatalogue] = useState<CraftCatalogue | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const run = useCallback((force: boolean) => {
    let cancelled = false;
    setLoading(true);
    loadCraftCatalogue({ force })
      .then((next) => {
        if (cancelled) return;
        setCatalogue(next);
        setError(null);
      })
      .catch((reason: unknown) => {
        if (cancelled) return;
        setError(reason instanceof Error ? reason.message : CRAFT_OFFLINE_FIRST_RUN);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const listener = (next: CraftCatalogue) => setCatalogue(next);
    listeners.add(listener);
    const stored = peekCraftCatalogue();
    if (stored) setCatalogue(stored);
    const cancel = run(false);
    return () => {
      listeners.delete(listener);
      cancel();
    };
  }, [run]);

  const reload = useCallback(() => {
    run(true);
  }, [run]);

  return { catalogue, error, loading, reload };
}
