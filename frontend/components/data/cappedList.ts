/**
 * ONE PAGE OF A LIST, RENDERED AS THOUGH IT WERE THE WHOLE LIST — and the sentence that stops it.
 *
 * WHY THIS FILE EXISTS, because it is the whole point of it.
 *
 * Every list route in this application answers `{ items, total, page, pageSize, pages }` and clamps
 * `pageSize` to `MAX_PAGE_SIZE = 100` (`backend/app/services/pagination.py`). The record forms ask
 * for `pageSize: 100`, keep `.items`, and throw `total` away. A hundred is not a generous default
 * that somebody forgot to raise — it is the ceiling, so those lists cannot be widened from the
 * client even in principle, and none of them said anything at all about the cut.
 *
 * THE CEILING IS NOT THEORETICAL. This module and the pickers built on it were written after the
 * sibling repository — the same schema, the same forms, the same `MAX_PAGE_SIZE` — counted its own
 * Postgres on 2026-08-15 and found every table behind a picker past the ceiling, most of them by an
 * order of magnitude:
 *
 *     MediaFile 2530 · ProductDocumentation 878 · Artisan 749 · Workshop 196 · Craft 178 ·
 *     ToolDocumentation 177 · Process 177 · QuestionnaireInterview 0
 *
 * Those are that deployment's numbers and are quoted as EVIDENCE, not as a claim about this one —
 * whose database was not reachable from the machine this port was made on. The argument does not
 * depend on them: `/artisans` orders `createdAt desc`, so the artisan dropdown holds the newest
 * hundred rows of the table whatever its size, and the craft filter then cuts into THAT. Note the
 * last two entries above. Workshops and crafts were the two lists people assumed were "small enough
 * not to matter"; they were not. And `QuestionnaireInterview` at 0 is precisely why no assumption
 * about size may be made from what a screen looks like today: the same picker is empty on one
 * database and cut on the next one.
 *
 * THE RULE THIS FILE ENFORCES: *a list that quietly stops is indistinguishable from a place with no
 * records, so every cap, truncation or skipped row must say so on screen.* This module is the one
 * place that decides whether there is anything to say and what the words are; the call sites hand
 * it a `PageResult` and render whatever comes back.
 *
 * WHY A PURE FUNCTION AND NOT A TERNARY IN EACH PANEL. One of the states below cannot be produced
 * by any live database, so a decision buried in JSX is only ever exercised by somebody looking at a
 * screen. Several call sites also means several chances to word it differently, and two screens
 * describing the same cut in two different sentences is how a researcher learns that neither of
 * them means much. Do not inline these branches back into a component.
 */

import type { PageResult } from "@/lib/types";

/**
 * The largest page any list route in this application will serve.
 *
 * `normalize_pagination` does `min(page_size, MAX_PAGE_SIZE)` with `MAX_PAGE_SIZE = 100`
 * (`backend/app/services/pagination.py`), and the list routes declare
 * `pageSize: int = Query(20, ge=1, le=100)` on top of that — so 100 is refused-past, not merely
 * defaulted. Exported so a call site asks for the ceiling by name rather than repeating the
 * literal, and so that the day the server raises it there is one number to change here and a grep
 * that finds every caller.
 *
 * **Raising this alone fixes nothing.** It moves the cut; it does not tell anybody where the cut
 * is. The notice below is the part that has to ship with it.
 */
export const LIST_PAGE_CEILING = 100;

/**
 * A list that stopped short of its own `total`, or `null` when it did not.
 *
 * `null` is the common answer and the whole point of the type: a complete list has nothing to
 * explain, and a standing note about pagination on every visit is padding these screens do not
 * need. Making "nothing to say" a distinct value rather than an empty string keeps that decision
 * here instead of in every caller's `&&`.
 *
 * `noun` is the plural the sentence is built around ("artisans", "products"). It is the caller's,
 * not derived from the endpoint path, because the words on screen are the label the researcher
 * reads and a route name is not a label.
 */
export type ListCut = {
  /** Plural noun for the records, lower case, as it should read mid-sentence. */
  noun: string;
  /** How many rows this client actually holds. */
  loaded: number;
  /** How many the server says exist under the same filters. */
  total: number;
};

/**
 * Was this answer cut, and by how much?
 *
 * Deliberately takes the WHOLE `PageResult` rather than two numbers: the defect being closed is
 * exactly that call sites reached for `.items` and dropped the envelope on the floor, so the helper
 * that fixes it should be the one that wants the envelope. Passing `result.items.length` rather
 * than `pageSize` is also load-bearing — a short final page is not a cut, and `pageSize` would
 * report one.
 */
export function listCut<T>(result: PageResult<T>, noun: string): ListCut | null {
  return cutOf(result.items.length, result.total, noun);
}

/**
 * The same question asked of two loose numbers, for a caller whose rows have already been mapped
 * out of their envelope. Exported rather than left private so nobody re-derives "is this cut" with
 * a `>` in a render — the `Number.isFinite` guard is the reason: `total` is a plain cast off the
 * wire (`apiFetch` does not parse a schema), and an older deployment that omits it must make the
 * screen say NOTHING rather than claim a cut of `NaN`.
 */
export function cutOf(loaded: number, total: number, noun: string): ListCut | null {
  const known = Number.isFinite(total) ? total : loaded;
  if (known <= loaded) return null;
  return { noun, loaded, total: known };
}

/**
 * How the rows past the cut can be got at — which changes the sentence, because a sentence that
 * tells somebody to do something impossible is worse than one that admits the limit.
 *
 * - `"none"`: this control holds one page and there is no second one. A `<select>` cannot reach
 *   past the array it was handed, and neither can a ComboBox that filters it locally
 *   (`components/ui/SearchableSelect`). Every record picker in these forms is this.
 * - `"pager"`: a `Pagination` control is on screen and moving it re-requests from the server.
 *
 * There is deliberately no `"search"` arm. Giving these pickers the server-side `search=` the list
 * routes already accept means threading a search term out of a shared primitive, which this change
 * does not own. Writing "search to reach the rest" over a box that only filters what is already
 * loaded would be the same lie one layer down.
 */
export type CutReach = "none" | "pager";

/**
 * THE ONE SENTENCE UNDER A CAPPED LIST, or "" when the screen must say nothing.
 *
 * Four states, ordered so the impossible-looking one is tested first:
 *
 * 1. **Nothing loaded although the server says rows exist.** Not reachable from a picker today —
 *    page one of a non-empty list always holds rows — but it is reachable the moment a caller
 *    passes a `page` past the end, and it is the state where silence does the most damage: the
 *    control renders "no entries" over a repository holding hundreds. It gets its own words, and it
 *    never tells the reader to search or to page, because neither would help.
 * 2. **Cut, with a pager on screen.** Say the arithmetic and point at the pager.
 * 3. **Cut, with no way past it from here.** Say the arithmetic and say plainly that typing in this
 *    box searches only what is shown — otherwise the empty result of that typing reads as a fact
 *    about the repository, which is the entire defect.
 * 4. **Not cut.** Silence.
 *
 * The numbers are always both printed. "Showing the first 100" alone still leaves the reader
 * guessing whether that is most of the corpus or an eighth of it, and the difference is whether
 * they go looking elsewhere or conclude the record was never created.
 */
export function cappedListNotice(cut: ListCut | null, reach: CutReach = "none"): string {
  if (!cut) return "";
  if (cut.loaded === 0) {
    return `None of the ${cut.total} ${cut.noun} could be listed here — this is not an empty repository.`;
  }
  if (reach === "pager") {
    return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — use the pager to reach the rest, which are not searched by the box above.`;
  }
  return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — the other ${cut.total - cut.loaded} are not on this list, and typing here searches only the ${cut.loaded} shown.`;
}

/**
 * Add rows to a picker's option list without ever removing one — the other half of living with a
 * ceiling.
 *
 * A picker that can only hold one page has to be allowed to hold SEVERAL pages: the repository-wide
 * page it loaded at mount, the narrower page it fetched once a craft was chosen, and the single row
 * it looked up by id because the record being edited pointed at it. Those three overlap, arrive in
 * any order, and none of them is authoritative over the others.
 *
 * **Additive on purpose.** Replacing the array with the newest answer is what would make an edit
 * form forget the artisan it was editing the moment a craft was picked, and these arrays are also
 * handed to `carryScope`, where a missing id is read as "this record is not reachable from this
 * form" and the carried prefill is dropped. A narrower list must therefore never be allowed to look
 * like a shorter world.
 *
 * First writer wins on a duplicate id, so a full row already on screen is not swapped for a
 * differently-shaped one mid-interaction.
 */
export function mergeById<T extends { id: string }>(previous: readonly T[], incoming: readonly T[]): T[] {
  if (incoming.length === 0) return previous as T[];
  const seen = new Set(previous.map((row) => row.id));
  const added = incoming.filter((row) => !seen.has(row.id));
  return added.length === 0 ? (previous as T[]) : [...previous, ...added];
}
