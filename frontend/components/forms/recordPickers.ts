"use client";

/**
 * THE CRAFT AND ARTISAN PICKERS THE RECORD FORMS SHARE — and the ceiling they used to hide.
 *
 * ProductForm and ToolForm ask the identical question of the API ("which crafts, and which artisans
 * of the chosen craft") and had the identical bug in it, twice over, character for character. This
 * hook is that question asked once.
 *
 * WHAT WAS WRONG. Both forms did a single `listResource("/artisans", { pageSize: 100 })` at mount
 * and then filtered the result by craft in the browser. `pageSize` is clamped to `MAX_PAGE_SIZE =
 * 100` server-side (`backend/app/services/pagination.py`) so 100 is the ceiling and not a tunable,
 * and `GET /artisans` orders `createdAt desc` (`routes/artisans.py:259`). So the dropdown held the
 * newest hundred rows of the WHOLE table and the craft filter then cut into THAT: a craft whose
 * people were entered before the newest hundred offered nothing at all, under the sentence "No
 * artisans are linked to this craft yet." — a statement about the repository that neither form had
 * any basis for. `total` was on the wire the whole time, discarded.
 *
 * HOW BADLY IT BITES depends on the table, and the sibling repository running this same schema
 * counted its Postgres on 2026-08-15: **749 artisans over 178 crafts**, i.e. 649 artisans
 * unpickable and a crafts list cut by 78. Those are quoted as evidence of the shape, not as a
 * measurement of this deployment — its database was not reachable when this was ported. The
 * argument holds at any size past 100, and the fix costs nothing below it.
 *
 * WHAT THIS HOOK DOES INSTEAD, in three requests that only ever ADD rows:
 *
 * 1. the mount load, kept as it was, because `carryScope` reads this array to decide whether a
 *    carried record is reachable from this form and that judgement is about the repository, not
 *    about one craft;
 * 2. the chosen crafts' own roster, asked for in ONE request with the PLURAL `craftIds` the endpoint
 *    grew for the tool form's multi-craft picker (`routes/artisans.py`, `resolve_craft_ids` in
 *    `services/record_filters.py`) — which turns a hundred-row window on the whole table into, in
 *    practice, the complete answer for the crafts in hand. It reads "crafts" plural throughout
 *    because ToolForm now links several at once; ProductForm passes a one-element list and the
 *    request it produces is byte-for-byte the one it always made (see the singular `craftId` sent
 *    beside the plural below, which is what makes that true against an API that predates the
 *    parameter);
 * 3. the records' own artisans, looked up by id when neither page holds them, so that "this artisan
 *    is not in the list" and "this artisan does not practise that craft" stop being the same
 *    observation. They were the same observation, and the craft-change handlers in both forms read
 *    the first as the second and cleared the link.
 *
 * WHAT IT REPORTS. `craftCut` and `craftArtisanCut` are `null` whenever the list is whole, which is
 * the normal case for a craft's roster and progressively not the case for the crafts list itself. A
 * cut list must say so — see `components/data/cappedList` for why that is a rule here rather than a
 * nicety.
 *
 * ANDROID PARITY. `MainActivity.kt` runs the same forms against the same endpoints and had the same
 * defects; they are fixed there in the same terms — `craftChangeClearsArtisan`, `listCutNotice`,
 * `mergeArtisansById` and `rememberArtisanPicker` in
 * `android/app/src/main/java/com/fieldrepository/app/ui/RecordPickers.kt`, asserted by
 * `android/app/src/test/java/com/fieldrepository/app/RecordPickersTest.kt`. If you change a rule
 * here, change it there — a picker that behaves differently on the phone than in the browser is how
 * this product family has repeatedly shipped two answers to one question. The plural rules added for
 * the multi-craft tool form — {@link craftsChangeClearsArtisans} and {@link sortArtisansByCraft} —
 * have Kotlin twins under the same names and the same test file.
 */

import { useEffect, useMemo, useRef, useState } from "react";

import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import type { CarryScopeState } from "@/components/forms/CarryContextBanner";
import { apiFetch, listResource } from "@/lib/api";
import type { Artisan, Craft } from "@/lib/types";

/**
 * THE RECORD THIS FORM IS ALREADY POINTING AT, fetched by id when no loaded page holds it.
 *
 * A picker holds one page of at most 100 rows. The record being EDITED does not care about that: a
 * product filed last season points at an artisan who may be nowhere near the newest hundred, and a
 * picker that cannot draw its own current value is not merely incomplete — it is wrong, and every
 * "is this still valid?" test written against its array answers about page one instead of about the
 * repository. That is what let a craft correction silently unlink an artisan.
 *
 * Returns the row, or null when the id is empty, already on a loaded page, or unreachable. Callers
 * `mergeById` it into their options; nothing here mutates the page.
 *
 * **The ref is not an optimisation.** `rows` must be a dependency (a page arriving late has to
 * re-test the guard), so without a record of what has already been attempted a 403 or a 404 would
 * re-fire the request on every merge, forever.
 */
export function useRecordOffPage<T extends { id: string }>(
  endpoint: string,
  id: string,
  rows: readonly T[]
): T | null {
  const [record, setRecord] = useState<T | null>(null);
  const attempted = useRef(new Set<string>());

  useEffect(() => {
    if (!id || rows.some((row) => row.id === id)) return;
    if (attempted.current.has(id)) return;
    attempted.current.add(id);
    let cancelled = false;
    apiFetch<T>(`${endpoint}/${id}`)
      .then((row) => {
        if (!cancelled) setRecord(row);
      })
      .catch(() => {
        // Not visible to this account, or gone. Deliberately NOT an error banner: nothing is broken
        // — the link is intact and the name beside the picker still says who it points at. This form
        // simply cannot offer to change it, which is the honest state to be in.
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint, id, rows]);

  // A row fetched for a DIFFERENT id must never be merged into the options: the researcher has
  // moved on and it would appear as an option that is neither on a page nor selected.
  return record && record.id === id ? record : null;
}

/**
 * {@link useRecordOffPage} FOR A MULTI-SELECT, which is the same rule over a list rather than a
 * second rule.
 *
 * A multi-select holding N ids has N chances to be pointing at a row no loaded page holds, not one:
 * a tool linked to three crafts opens with all three ticked, and `GET /crafts` is clamped to 100
 * rows ordered NAME ASCENDING, so any craft sorting past the cut is missing from the picker while
 * the link is perfectly intact in the database. Drawing a blank chip for a craft the record
 * genuinely holds is the same defect the singular hook was written for, multiplied — and the repair
 * it invites (pick the craft again) is the one action that really does rewrite the links.
 *
 * Returns the rows it managed to fetch, in the order of `ids`, with the unreachable ones simply
 * absent. Callers `mergeById` them into their options; nothing here mutates a page.
 *
 * **Keyed on the JOINED ids, not on the array.** A caller passing an inline `[a, b]` hands a new
 * array identity on every render, so an array in the dependency list would re-fire the effect
 * forever. The `attempted` ref is load-bearing for the same reason it is in the singular hook: a 403
 * or a 404 must be asked once and not once per merge.
 */
export function useRecordsOffPage<T extends { id: string }>(
  endpoint: string,
  ids: readonly string[],
  rows: readonly T[]
): T[] {
  const [records, setRecords] = useState<Record<string, T>>({});
  const attempted = useRef(new Set<string>());
  const missingKey = ids.filter((id) => id && !rows.some((row) => row.id === id)).join(",");
  const idKey = ids.filter(Boolean).join(",");

  /*
    NO `cancelled` FLAG HERE, AND THAT IS THE DIFFERENCE FROM THE SINGULAR HOOK RATHER THAN AN
    OVERSIGHT.

    Up there, the effect is keyed on ONE id: when it changes, an in-flight answer describes a record
    the form has moved off, so discarding it is correct. Here the key is the whole missing SET, and
    it changes whenever the researcher ticks anything — so a cleanup that cancelled would throw away
    the answer for a craft that is STILL ticked, and `attempted` would never ask again. The picker
    would then be permanently missing a row the record holds, with no error and nothing to retry.

    Letting the write land instead is safe in both directions: the store is keyed by id, so a late
    arrival is still the right row for that id, and the memo below returns only the ids currently
    asked for — a row for something since unticked is simply not in the answer. A `setState` after
    unmount is a no-op in React 18+.
  */
  useEffect(() => {
    if (!missingKey) return;
    for (const id of missingKey.split(",")) {
      if (attempted.current.has(id)) continue;
      attempted.current.add(id);
      apiFetch<T>(`${endpoint}/${id}`)
        .then((row) => setRecords((current) => ({ ...current, [id]: row })))
        .catch(() => {
          // Not visible to this account, or gone — never an error banner, for the reason written out
          // in the singular hook above. The link is intact and the name beside the picker still says
          // who it points at; this form simply cannot offer to change it.
        });
    }
  }, [endpoint, missingKey]);

  // Rows fetched for ids the researcher has since UNTICKED are dropped rather than left merged in:
  // an option that is neither on a page nor selected is an option nobody can account for.
  return useMemo(
    () => (idKey ? idKey.split(",").map((id) => records[id]).filter((row): row is T => Boolean(row)) : []),
    [idKey, records]
  );
}

export type CraftAndArtisanOptions = {
  /** Every artisan this form has learned about, from all three requests. Never narrowed. */
  artisans: Artisan[];
  crafts: Craft[];
  /**
   * "Have the reference lists arrived?" — handed straight to `carryScope`, which treats "not
   * visible to me" and "no signal" differently. Only the MOUNT load moves it: the craft-scoped
   * request and the by-id lookup are refinements, and letting either of them report "unavailable"
   * would prune a carried record because one follow-up request failed.
   */
  referenceState: CarryScopeState;
  /** The crafts dropdown's cut, or null when it holds every craft. */
  craftCut: ListCut | null;
  /** The chosen crafts' artisan roster's cut, or null when it holds every one of them. */
  craftArtisanCut: ListCut | null;
  /**
   * WHICH crafts the loaded roster belongs to — not a boolean.
   *
   * "No artisans are linked to this craft yet" is a claim about the repository, and printing it off
   * the previous selection's rows while the new one's request is still in flight makes that claim
   * before the answer exists. A caller must test `artisansLoadedForCrafts === craftsKey(craftIds)`
   * before saying anything about emptiness.
   *
   * IT IS THE SORTED, COMMA-JOINED SELECTION and not a set, because it has to be comparable with one
   * `===` in a render. {@link craftsKey} is the only thing that may build it: ticking A then B and
   * ticking B then A are the same roster and must not look like two.
   */
  artisansLoadedForCrafts: string | null;
};

/**
 * The cache/claim key for a set of ticked crafts: sorted, blank-free, comma-joined.
 *
 * SORTED, so that the same selection reached in a different tick order is the same key — the request
 * it stands for is identical, and a second key for it would mean a second request and a stale
 * `artisansLoadedForCrafts` comparison for as long as it was in flight. This is deliberately NOT the
 * order the crafts are saved in: {@link useCraftAndArtisanOptions} is about what has been LOADED,
 * while the wire's `craftIds` order is the researcher's own and is preserved by the form.
 */
export function craftsKey(craftIds: readonly string[]): string {
  return [...new Set(craftIds.filter(Boolean))].sort().join(",");
}

export function useCraftAndArtisanOptions({
  craftIds,
  artisanIds
}: {
  /** Every ticked craft, in the researcher's own order. One element from the single-select forms. */
  craftIds: readonly string[];
  /** Every ticked artisan, so each of them gets the by-id rescue and not just the first. */
  artisanIds: readonly string[];
}): CraftAndArtisanOptions {
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [referenceState, setReferenceState] = useState<CarryScopeState>("pending");
  const [craftCut, setCraftCut] = useState<ListCut | null>(null);
  const [craftArtisanCut, setCraftArtisanCut] = useState<ListCut | null>(null);
  const [artisansLoadedForCrafts, setArtisansLoadedForCrafts] = useState<string | null>(null);
  // The craft selection reaches the effect below as ONE string, never as the array itself: a caller
  // building `craftId ? [craftId] : []` inline hands a fresh array identity on every render, and an
  // array in a dependency list is a request that never stops firing. `useRecordsOffPage` does the
  // same for the artisan ids, inside itself.
  const craftKey = craftsKey(craftIds);

  // (1) The repository-wide reference load. Unchanged in shape from what both forms did, because
  // `carryScope` depends on this array meaning "everything this form can see".
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING }),
      listResource<Craft>("/crafts", { pageSize: LIST_PAGE_CEILING })
    ])
      .then(([artisanResult, craftResult]) => {
        if (cancelled) return;
        setArtisans((previous) => mergeById(previous, artisanResult.items));
        setCrafts(craftResult.items);
        setCraftCut(listCut(craftResult, "crafts"));
        setReferenceState("loaded");
      })
      .catch(() => {
        if (!cancelled) setReferenceState("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // (2) The chosen crafts' roster, from the server, in ONE request. This is the request that
  // actually closes the defect: without it the artisan dropdown can only ever show the intersection
  // of the chosen crafts with the newest hundred rows of the whole artisan table.
  //
  // ONE REQUEST AND NOT ONE PER CRAFT. `pageSize` is clamped to 100 per response either way, so N
  // requests would not raise the ceiling — they would only multiply it by however many crafts are
  // ticked while making the cut impossible to report honestly, and "Select all 178" would fire 178
  // of them. The plural `craftIds` exists on `/artisans` for exactly this caller.
  useEffect(() => {
    if (!craftKey) return;
    let cancelled = false;
    const wanted = craftKey.split(",");
    listResource<Artisan>("/artisans", {
      craftIds: craftKey,
      // ── THE SINGULAR IS SENT TOO, AND ONLY WHEN IT CANNOT NARROW ANYTHING THE PLURAL DOES NOT ──
      // The web deploys to Vercel and the API to EC2, separately, so a NEWER web build can meet an
      // OLDER API — and an undeclared query parameter is not refused by FastAPI, it is IGNORED. A
      // server that predates `craftIds` would therefore answer the newest hundred artisans of the
      // WHOLE table under a request that looks filtered, which is the silent-emptiness failure this
      // hook exists to end, wearing a "no artisans are linked to this craft" sentence. Sending the
      // singular alongside it makes the single-craft case — every ProductForm save, and most tool
      // ones — byte-for-byte the request this hook has always made, whatever the API knows. With
      // several crafts ticked there is no singular value that is not a wrong narrowing, so it is
      // omitted and an old API degrades to the pre-plural behaviour rather than to a wrong one.
      // BOTH NARROW when both are understood (`routes/artisans.list_artisans`), and they agree by
      // construction here: the singular is only ever the sole element of the plural.
      craftId: wanted.length === 1 ? wanted[0] : undefined,
      pageSize: LIST_PAGE_CEILING
    })
      .then((result) => {
        if (cancelled) return;
        setArtisans((previous) => mergeById(previous, result.items));
        setCraftArtisanCut(listCut(result, wanted.length === 1 ? "artisans of this craft" : "artisans of these crafts"));
        setArtisansLoadedForCrafts(craftKey);
      })
      .catch(() => {
        // Leave what is already loaded on screen and say nothing new: the mount load's artisans are
        // still a legitimate, narrower offer, and `artisansLoadedForCrafts` deliberately stays put so
        // the caller does not print "no artisans are linked to this craft" off a failure.
      });
    return () => {
      cancelled = true;
    };
  }, [craftKey]);

  // (3) The record's own artisans, whatever page they are on — see `useRecordOffPage` for why a
  // picker that cannot draw its own current value is worse than one that is merely short, and
  // `useRecordsOffPage` for why a multi-select needs that rescue once per ticked id.
  const offPageArtisans = useRecordsOffPage<Artisan>("/artisans", artisanIds, artisans);
  const allArtisans = useMemo(
    () => (offPageArtisans.length ? mergeById(artisans, offPageArtisans) : artisans),
    [artisans, offPageArtisans]
  );

  return { artisans: allArtisans, crafts, referenceState, craftCut, craftArtisanCut, artisansLoadedForCrafts };
}

/**
 * Should a craft change clear the artisan link?
 *
 * ONLY when this form actually knows the artisan practises a different craft. Both record forms
 * asked `!artisans.some((a) => a.id === artisanId && a.craftId === next)`, which is false for two
 * unrelated reasons — the craft differs, or the artisan is not in the loaded array at all — and
 * treated both as "wrong craft". Against a 100-row page of a longer table the second reason is the
 * ordinary one on any older record: opening a product or a tool to CORRECT ITS CRAFT blanked the
 * artisan field, and `artisanId` is in the backend's `CLEARABLE_KEYS`
 * (`backend/app/services/records.py:249-265`) while both forms submit `artisanId: artisanId || null`
 * — so the save wrote an explicit null and destroyed the artisan link under a 200 with nothing on
 * screen saying so.
 *
 * When the artisan cannot be found even after the by-id lookup, the link is KEPT. That is the safe
 * direction and the choice is deliberate: an artisan wrongly left linked is visible on the form and
 * one click from being corrected; an artisan silently unlinked is neither.
 */
export function craftChangeClearsArtisan({
  nextCraftId,
  artisanId,
  artisans
}: {
  nextCraftId: string;
  artisanId: string;
  artisans: readonly Artisan[];
}): boolean {
  if (!nextCraftId || !artisanId) return false;
  const known = artisans.find((artisan) => artisan.id === artisanId);
  return Boolean(known) && known?.craftId !== nextCraftId;
}

/**
 * THE SAME RULE FOR A MULTI-SELECT: which ticked artisans a craft DESELECTION must drop.
 *
 * The singular above answers a yes/no about one link because the single-select forms hold one; the
 * tool form holds a list, and the question it has to answer is narrower than "does this selection
 * still match?" — deselecting one craft out of three must drop exactly the artisans of that craft
 * and leave every other one ticked. Returns the ids to REMOVE, so the caller filters rather than
 * recomputing, and an artisan the form cannot account for is never in the answer.
 *
 * The FOUR reasons an artisan is KEPT, and the order matters:
 *
 *  * **not in the loaded array at all** — "not on the list" and "not of that craft" are different
 *    observations, and reading the first as the second is the silent link deletion the singular rule
 *    was written for. Against a 100-row page of a longer table this is the ORDINARY case on an older
 *    record;
 *  * **no craft recorded on their row** — nothing here knows they are wrong, and a record with a
 *    null `craftId` is a fact about that artisan, not about this form. THE SINGULAR RULE ANSWERS THE
 *    OTHER WAY on this one case, and the difference is deliberate: a single-select that keeps an
 *    artisan of no craft leaves the form asserting one link that contradicts the other, with no room
 *    to show both. A multi-select has that room — the row stays ticked and visible, one click from
 *    being corrected — so the safe direction here is the opposite of the safe direction there;
 *  * **their craft is not one of the crafts this gesture REMOVED** — see the paragraph below, which
 *    is the defect this argument list was rewritten around;
 *  * **still covered by a craft that is still ticked** — a craft can be reachable twice over, and an
 *    artisan of a craft that survives elsewhere in the selection has lost nothing.
 *
 * Everything else — known, with a craft, that craft just removed, and not ticked anywhere else — is
 * dropped. Same safe direction as the singular: an artisan wrongly left ticked is on screen and one
 * click from being corrected; an artisan silently unticked is neither.
 *
 * ── `removedCraftIds` IS NOT A CONVENIENCE, IT IS THE WHOLE RULE ───────────────────────────────
 * This function used to ask only `!nextCraftIds.includes(known.craftId)` — *is this artisan of a
 * craft that is not ticked* — and answered the headline requirement wrongly for everyone whose craft
 * was never ticked in the first place. The tool's artisans do not all arrive through its craft
 * picker: "Assign a tool to multiple artisans" writes `ToolArtisan` rows for anybody, of any craft,
 * and the form draws every one of them ticked. So a tool linked to crafts [Bandhani, Block printing]
 * and, through that panel, to a POTTER, lost the potter the moment a researcher unticked Block
 * printing — his craft was not in the next list, he was returned as dropped, the PATCH carried the
 * shortened list, and `_replace_artisan_links` deleted his row under a 200 with nothing on screen
 * saying an assignment had been removed. Unticking one craft must drop exactly that craft's people.
 *
 * `removedCraftIds` is everything the previous selection held that `nextCraftIds` does not, which
 * only the caller can compute: this function never sees the selection it is being asked about the
 * change TO. Both clauses are kept: the craft must be one that just went away AND must not still be
 * ticked under another entry of the selection.
 *
 * ── AND THE EMPTY-TICK-LIST QUESTION, SETTLED IN THE SAME WAVE AS THE HANDSET ───────────────
 * This docblock used to claim an asymmetry with the singular as deliberate: *"`craftChangeClearsArtisan`
 * answers `false` when the new craft is blank ... Here an EMPTY `nextCraftIds` is not the same
 * statement: the researcher has unticked every craft, which is exactly the act of saying those
 * artisans no longer belong — so every artisan whose craft this form knows IS dropped."* The
 * Kotlin twin disagreed, with an `if (nextCraftIds.none { it.isNotBlank() }) emptyList()` arm and a
 * paragraph asking whichever side landed second to say so rather than quietly deleting the other.
 *
 * NEITHER SIDE HAS THE ARM ANY MORE, and it is the half that went — see `ui/RecordPickers.kt`, which
 * retires its own with its own quotation and its own argument. Unticking the last craft is not a
 * case here: `removedCraftIds` holds that craft, so ITS artisans are dropped and nobody else's,
 * exactly as when one craft of three goes. What made the old behaviour indefensible was never the
 * empty list — it was dropping people whose craft the gesture never touched, which is what the
 * argument above closes.
 *
 * THE ORDER-DEPENDENCE THE ARM WAS ALSO ARGUING AGAINST IS GONE, and gone properly rather than
 * masked: the answer is now a function of which crafts were REMOVED, so untick-A-then-tick-B and
 * tick-B-then-untick-A remove the same set (`{A}`) and produce the same record. Under the old rule
 * they did not, for the same two gestures.
 *
 * THE OUTSTANDING HALF OF THE PARITY, NAMED RATHER THAN ASSUMED: `removedCraftIds` is this side's
 * argument and the Kotlin twin must grow it too, with the cases below mirrored into
 * `RecordPickersTest.kt`. Until it does, the handset still drops an artisan whose craft was never
 * ticked and the browser does not — which is the same divergence in a new place. Change both in the
 * one commit: a rule two clients apply differently protects nobody, which is the whole reason this
 * pair of functions is tested twice from one table of cases.
 */
export function craftsChangeClearsArtisans({
  nextCraftIds,
  removedCraftIds,
  artisanIds,
  artisans
}: {
  nextCraftIds: readonly string[];
  removedCraftIds: readonly string[];
  artisanIds: readonly string[];
  artisans: readonly Artisan[];
}): string[] {
  return artisanIds.filter((id) => {
    const known = artisans.find((artisan) => artisan.id === id);
    if (!known || !known.craftId) return false;
    return removedCraftIds.includes(known.craftId) && !nextCraftIds.includes(known.craftId);
  });
}

/**
 * The craft name to print beside an artisan, from the two places it can come from.
 *
 * The hydrated `artisan.craft` first (the API includes it), then the ticked craft rows this form
 * already holds, then nothing. "Nothing" is a real answer and is rendered as such — an artisan whose
 * craft this client cannot name is not quietly filed under the first craft in the list.
 */
export function craftNameFor(artisan: Artisan, selectedCrafts: readonly Craft[]): string {
  const hydrated = artisan.craft?.name?.trim();
  if (hydrated) return hydrated;
  const selected = selectedCrafts.find((craft) => craft.id === artisan.craftId)?.name?.trim();
  return selected || "";
}

/**
 * Lower-cased and trimmed, for ORDERING only.
 *
 * DELIBERATELY NOT `SearchableSelect`'s `fold`, which also strips diacritics via NFD. That one
 * exists so typing "ahmedabad" reaches "Ahmedābād", which is the right rule for SEARCHING and the
 * wrong one for ordering: stripping the mark makes two distinct names tie and hands the decision to
 * the next element of the sort key, and Android's `SearchableSelect` has no NFD-stripping
 * counterpart at all — so the browser and the handset would order the same roster differently, which
 * is the defect class this whole file is about.
 *
 * `toLowerCase()` and not `toLocaleLowerCase()`: ECMAScript's is the locale-INDEPENDENT Unicode
 * default case conversion, which is what Kotlin's no-argument `lowercase()` (i.e. `Locale.ROOT`)
 * also is. A locale-aware pair would disagree on a Turkish dotted I on a phone set to Turkish.
 */
function orderFold(text: string): string {
  return text.trim().toLowerCase();
}

/**
 * Compare two strings by UTF-16 CODE UNIT, which is what Kotlin's `String.compareTo` also does.
 *
 * `localeCompare` and `Intl.Collator` are FORBIDDEN here, as are `java.text.Collator`,
 * `String.CASE_INSENSITIVE_ORDER` and `compareTo(other, ignoreCase = true)` on the Kotlin side.
 * Every one of them is ICU-version-dependent, locale-dependent, or compares char-by-char in both
 * cases — and each would make the browser and the handset order a Devanagari or Gujarati craft name
 * differently on the same data, which nobody would notice until two researchers compared screens.
 */
function compareUtf16(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * THE ARTISAN ROSTER FOR A MULTI-CRAFT PICKER: by craft name A→Z, then by artisan name A→Z.
 *
 * The order is the answer to "who practises the crafts I ticked", and a picker that lists 60 people
 * in `createdAt desc` — which is what `GET /artisans` returns and what no client may depend on — is
 * a list nobody can scan. Sorting by craft first is what makes the list read as groups even on the
 * surfaces that cannot draw a group heading: this repository's web `SelectOption` is
 * `{value, label, disabled?}` with no `group` field, and neither handset's has one either, so the
 * grouping has to fall out of the ORDER and out of a label that names the craft first.
 *
 * The key is a five-tuple and the last element is the artisan's cuid, so the order is TOTAL: it does
 * not matter whether the sort is stable, and two clients cannot disagree about a tie.
 *
 *     (craft-is-unknown, folded craft name, folded artisan name, artisan name, artisan id)
 *
 * UNKNOWN CRAFTS SORT LAST, never first. An empty string sorts before everything under a plain
 * comparison, which would open the list with the rows this client can say the least about.
 *
 * Kotlin twin: `sortArtisansByCraft` in `ui/RecordPickers.kt`, same key, same collation rules — see
 * {@link orderFold} and {@link compareUtf16} for why the collation is spelled out rather than
 * delegated to either platform's locale machinery.
 */
export function sortArtisansByCraft(artisans: readonly Artisan[], selectedCrafts: readonly Craft[]): Artisan[] {
  const keyed = artisans.map((artisan) => {
    const craftKey = orderFold(craftNameFor(artisan, selectedCrafts));
    return { artisan, unknown: craftKey === "" ? 1 : 0, craftKey, nameKey: orderFold(artisan.name) };
  });
  keyed.sort(
    (a, b) =>
      a.unknown - b.unknown ||
      compareUtf16(a.craftKey, b.craftKey) ||
      compareUtf16(a.nameKey, b.nameKey) ||
      compareUtf16(a.artisan.name, b.artisan.name) ||
      compareUtf16(a.artisan.id, b.artisan.id)
  );
  return keyed.map((entry) => entry.artisan);
}
