export type ListReturn = {
  query: string;
  page: number;
  funnel?: { workshopId: string; craftId: string; artisanId: string } | null;
  completeness?: "incomplete" | "required" | "";
  scrollY: number;
  savedAt: number;
};

export const LIST_RETURN_MAX_AGE_MS = 30 * 60 * 1000;

const STORAGE_PREFIX = "field_repo_list_return:";

export function encodeListReturn(state: Omit<ListReturn, "savedAt">, now = Date.now()): string {
  return JSON.stringify({ ...state, savedAt: now });
}

export function decodeListReturn(raw: string | null | undefined, now = Date.now()): ListReturn | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<ListReturn>;
    if (typeof parsed !== "object" || parsed === null) return null;
    const savedAt = Number(parsed.savedAt);
    if (!Number.isFinite(savedAt) || now - savedAt > LIST_RETURN_MAX_AGE_MS || savedAt > now + 60_000) return null;
    const page = Number(parsed.page);
    const scrollY = Number(parsed.scrollY);
    const funnel = parsed.funnel;
    return {
      query: typeof parsed.query === "string" ? parsed.query : "",
      page: Number.isInteger(page) && page >= 1 ? page : 1,
      scrollY: Number.isFinite(scrollY) && scrollY > 0 ? scrollY : 0,
      funnel:
        funnel && typeof funnel === "object"
          ? {
              workshopId: typeof funnel.workshopId === "string" ? funnel.workshopId : "",
              craftId: typeof funnel.craftId === "string" ? funnel.craftId : "",
              artisanId: typeof funnel.artisanId === "string" ? funnel.artisanId : ""
            }
          : null,
      completeness: parsed.completeness === "incomplete" || parsed.completeness === "required" ? parsed.completeness : undefined,
      savedAt
    };
  } catch {
    return null;
  }
}

export function rememberListReturn(key: string, state: Omit<ListReturn, "savedAt">): void {
  try {
    window.sessionStorage.setItem(STORAGE_PREFIX + key, encodeListReturn(state));
  } catch {
  }
}

export function takeListReturn(key: string): ListReturn | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_PREFIX + key);
    window.sessionStorage.removeItem(STORAGE_PREFIX + key);
    return decodeListReturn(raw);
  } catch {
    return null;
  }
}
