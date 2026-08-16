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
 * 2. the chosen craft's own roster, asked for with the `craftId` the endpoint has always accepted
 *    (`routes/artisans.py:234-235`) — which turns a hundred-row window on the whole table into, in
 *    practice, the complete answer for the craft in hand;
 * 3. the record's own artisan, looked up by id when neither page holds them, so that "this artisan
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
 * this product family has repeatedly shipped two answers to one question.
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
  /** The chosen craft's artisan roster's cut, or null when it holds every one of them. */
  craftArtisanCut: ListCut | null;
  /**
   * WHICH craft the loaded roster belongs to — not a boolean.
   *
   * "No artisans are linked to this craft yet" is a claim about the repository, and printing it off
   * the previous craft's rows while the new craft's request is still in flight makes that claim
   * before the answer exists. A caller must test `artisansLoadedForCraft === craftId` before saying
   * anything about emptiness.
   */
  artisansLoadedForCraft: string | null;
};

export function useCraftAndArtisanOptions({
  craftId,
  artisanId
}: {
  craftId: string;
  artisanId: string;
}): CraftAndArtisanOptions {
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [referenceState, setReferenceState] = useState<CarryScopeState>("pending");
  const [craftCut, setCraftCut] = useState<ListCut | null>(null);
  const [craftArtisanCut, setCraftArtisanCut] = useState<ListCut | null>(null);
  const [artisansLoadedForCraft, setArtisansLoadedForCraft] = useState<string | null>(null);

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

  // (2) The chosen craft's roster, from the server. This is the request that actually closes the
  // defect: without it the artisan dropdown can only ever show the intersection of one craft with
  // the newest hundred rows of the whole artisan table.
  useEffect(() => {
    if (!craftId) return;
    let cancelled = false;
    listResource<Artisan>("/artisans", { craftId, pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setArtisans((previous) => mergeById(previous, result.items));
        setCraftArtisanCut(listCut(result, "artisans of this craft"));
        setArtisansLoadedForCraft(craftId);
      })
      .catch(() => {
        // Leave what is already loaded on screen and say nothing new: the mount load's artisans are
        // still a legitimate, narrower offer, and `artisansLoadedForCraft` deliberately stays put so
        // the caller does not print "no artisans are linked to this craft" off a failure.
      });
    return () => {
      cancelled = true;
    };
  }, [craftId]);

  // (3) The record's own artisan, whatever page they are on — see `useRecordOffPage` for why a
  // picker that cannot draw its own current value is worse than one that is merely short.
  const offPageArtisan = useRecordOffPage<Artisan>("/artisans", artisanId, artisans);
  const allArtisans = useMemo(
    () => (offPageArtisan ? mergeById(artisans, [offPageArtisan]) : artisans),
    [artisans, offPageArtisan]
  );

  return { artisans: allArtisans, crafts, referenceState, craftCut, craftArtisanCut, artisansLoadedForCraft };
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
