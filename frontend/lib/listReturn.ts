/**
 * COMING BACK FROM A RECORD TO THE SAME PLACE IN THE LIST.
 *
 * Opening a record's view is a navigation, so the list page unmounts and its search box, page number,
 * filters and scroll position go with it. Pressing Back then landed on page one of the default
 * workshop, at the top — a researcher working down page four of a filtered list lost their place
 * every time they looked at one row. Now that a row click opens a view instead of an inline panel,
 * that happens on every click, so the list writes down where it was at the moment a record is opened
 * and reads it back once when it mounts again.
 *
 * ONE-SHOT ON PURPOSE. The note is taken (read and deleted) on the next mount, so it only ever
 * restores the trip it was written for. Opening the list fresh from the menu an hour later starts
 * from the defaults, as it always has. A note older than `LIST_RETURN_MAX_AGE_MS` is ignored for the
 * same reason. `sessionStorage` rather than `localStorage`: the place belongs to this tab, and a
 * second tab working another list must not inherit it.
 */

export type ListReturn = {
  query: string;
  page: number;
  /** The funnel filter (workshop / craft / artisan), for the lists that carry one. */
  funnel?: { workshopId: string; craftId: string; artisanId: string } | null;
  /** The completeness filter (`incomplete` / `required`), for the five lists with a meter. */
  completeness?: "incomplete" | "required" | "";
  scrollY: number;
  savedAt: number;
};

/** Half an hour: long enough to read a record properly, short enough not to resurrect yesterday's list. */
export const LIST_RETURN_MAX_AGE_MS = 30 * 60 * 1000;

const STORAGE_PREFIX = "field_repo_list_return:";

export function encodeListReturn(state: Omit<ListReturn, "savedAt">, now = Date.now()): string {
  return JSON.stringify({ ...state, savedAt: now });
}

/** Parses a stored note, or null when it is missing, malformed or stale. Never throws. */
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

/** Writes the note for `key`. Storage can throw (private window, blocked site data); losing the note is fine. */
export function rememberListReturn(key: string, state: Omit<ListReturn, "savedAt">): void {
  try {
    window.sessionStorage.setItem(STORAGE_PREFIX + key, encodeListReturn(state));
  } catch {
    // Nothing to do: the list simply opens at its defaults.
  }
}

/** Reads and deletes the note for `key`. */
export function takeListReturn(key: string): ListReturn | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_PREFIX + key);
    window.sessionStorage.removeItem(STORAGE_PREFIX + key);
    return decodeListReturn(raw);
  } catch {
    return null;
  }
}
