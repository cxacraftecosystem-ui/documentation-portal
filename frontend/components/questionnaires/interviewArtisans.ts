"use client";

/**
 * WHO AN INTERVIEW IS WITH — one control, one workshop, and the rules behind both.
 *
 * ── THE TWO DEFECTS THIS FILE CLOSES, IN THE OWNER'S OWN WORDS ───────────────────────────────────
 *
 *   (1) "when the workshop is already selected in the dropdown, why are artisans from other
 *        workshops showing up in primary artisans and other artisans multi-select searchable
 *        drop-down?"
 *   (3) "why are there primary and other artisans two different dropdowns in the web application?
 *        Why can it not be a multi-select dropdown that covers both?"
 *
 * Both were true of the capture form as it shipped in 0.0.5. It ran ONE request at mount —
 * `listResource<Artisan>("/artisans", { pageSize: 100 })` inside `loadMeta()` — with no workshop
 * parameter of any kind, fed the whole answer to a single-select "Primary artisan" AND to a
 * multi-select "Additional artisans", and never asked again when the workshop moved. So every
 * artisan in the deployment was offered at every workshop, twice, out of a list that stops at the
 * hundredth row of an endpoint ordered `createdAt desc` (`backend/app/api/routes/artisans.py`
 * `list_artisans`, `order={"createdAt": "desc"}`) with nothing on screen saying so.
 *
 * ── WHY "PRIMARY" WAS NEVER A THING ──────────────────────────────────────────────────────────────
 *
 * `model QuestionnaireInterviewArtisan` (`backend/prisma/schema.prisma:1470`) is
 * `@@id([interviewId, artisanId])` plus `createdAt`. There is no rank, no ordinal and no `isPrimary`
 * column, and the page already flattened the two controls into ONE de-duplicated set before every
 * call it made — the shared-entry lookup and the create both took `artisanIds`. The split therefore
 * bought nothing at all and cost a real thing: a researcher who ticked somebody under "Additional"
 * and left "Primary" blank got the RESP block prefilled from nobody.
 *
 * ── WHAT STILL NEEDS ONE ARTISAN, AND THE RULE ───────────────────────────────────────────────────
 *
 * Two things do, and only two: the RESP respondent block (name / craft / place / gender / contact —
 * one person's details, on one form) and the carry-forward bag banked after a save, which names the
 * artisan the researcher is still sitting with. {@link primaryInterviewArtisanId} answers both with
 * **the head of the selected list, in the researcher's own tick order**.
 *
 * THAT IS THIS REPOSITORY'S EXISTING RULE, not a new one invented here. `_resolve_tool_links`
 * derives a tool's scalar artisan as `data["artisanId"] = artisan_ids[0]`
 * (`backend/app/api/routes/tools.py:418`), and the Android tool sheet mirrors it with
 * `val artisanId = artisanIds.firstOrNull().orEmpty()` (`MainActivity.kt:6766`). One product, one
 * answer to "which of these several people is the record's own".
 *
 * IT IS STABLE ACROSS RE-RENDERS because the selection is an ORDERED array in React state and
 * `SearchableMultiSelect` only ever appends on tick (`onChange([...values, option.value])`) and
 * filters on untick (`onChange(values.filter(...))`) — see `components/ui/SearchableSelect.tsx`.
 * Nothing re-sorts it, so element 0 does not move unless the researcher removes the person at
 * element 0, which is their own act and not a silent change. The REJECTED alternative was a sticky
 * `primaryRef` that pinned the first artisan ever ticked: it survives its own artisan being
 * unticked, which means the carry bag and the RESP block would go on naming somebody the interview
 * no longer covers — and it is the "primary" concept the owner asked us to delete, smuggled back in
 * as a ref where no control shows it and nobody can correct it.
 *
 * ── SCOPING, AND WHY IT IS THE SERVER'S JOB AND NOT A `.filter()` ────────────────────────────────
 *
 * An artisan belongs to a workshop THREE ways, and all three count —
 * `services/record_filters.artisan_workshop_clause`: the `Artisan.workshopId` column, the
 * `WorkshopArtisan` roster that carried the link before that column existed, and *having sat in an
 * interview taken at the workshop*. A browser holds only the first of those (`artisan.workshopId` on
 * the DTO), so a client-side filter would silently drop everybody linked the other two ways — on a
 * questionnaire form, the third group is precisely the people most likely to be wanted, and the
 * second is every artisan recorded before the column existed. It would also disagree with Android,
 * which asks the server (`ConsolidatedQuestionnaireScreen.kt`:
 * `repository.artisans(workshopIds = scope.workshopIds)`). So the scope goes on the wire.
 *
 * ── PLURAL `workshopIds` AND NOT SINGULAR `workshopId`, DELIBERATELY ─────────────────────────────
 *
 * `GET /artisans` accepts both, and **they are not the same filter**. The singular narrows on the
 * column OR the roster (two of the three readings above); the plural goes through
 * `artisan_workshop_clause` and counts the interview membership as well. `list_artisans` ANDs every
 * filter it is given, so sending BOTH would silently intersect them down to the singular's narrower
 * answer — and that is the one thing that would break parity, because Android sends the plural.
 *
 * This is the opposite call from `components/forms/recordPickers`, which deliberately sends
 * `craftId` alongside `craftIds`, and the difference is worth stating because the next reader will
 * meet both. There, the two spellings are exactly equivalent for a one-element selection, so sending
 * both makes the request byte-identical against an API that predates the plural. Here they are not
 * equivalent, so "belt and braces" would be a wrong narrowing rather than a safe one. The
 * forward-compatibility risk the singular covered there does not arise: `workshopIds` has been on
 * `/artisans` since before 0.0.5 and Android 0.0.5 already ships calls to it.
 *
 * ── NO WORKSHOP SELECTED MEANS EVERY WORKSHOP ────────────────────────────────────────────────────
 *
 * See {@link workshopArtisanParams}. Stated there because that is the function that spells it.
 *
 * ── ANDROID PARITY ───────────────────────────────────────────────────────────────────────────────
 *
 * Every rule in this file is a contract both clients owe, and the whole reason it is a file of
 * exported functions rather than logic inside `page.tsx` is that a rule nobody can name is a rule
 * the other client cannot copy. THE CONTRACT, in full, with the Kotlin that owes each line:
 *
 *   1. THE OFFER IS THE WORKSHOP'S ROSTER, fetched with the plural `workshopIds` = the selected
 *      workshop, or unscoped when none is selected.
 *      Android: `rememberQuestionnaireArtisanOptions` → `repository.artisansPage(workshopIds = …)`.
 *   2. IT IS PAGED TO {@link ARTISAN_PAGE_BUDGET} PAGES and then reported honestly.
 *      Android: `FieldRepository.ARTISAN_PAGE_BUDGET`, the same number for the same reason.
 *   3. THE FIRST REQUEST IS HELD until the workshop picker has settled on its own default.
 *      Web: {@link workshopScopeSettling}. Android: `WorkshopPickerState.settled`.
 *   4. A WORKSHOP CHANGE INVALIDATES THE OFFER IMMEDIATELY — before the await, not when the
 *      replacement lands. In the window between two workshops the picker offers NOBODY and says
 *      "Loading artisans…". It never shows the previous workshop's roster and never falls back to
 *      the repository-wide list; either of those IS the reported defect, merely briefer.
 *      Android: `scoped = null` before the request, and a base that is `emptyList()` while null.
 *   5. A FAILED ROSTER REQUEST SAYS SO and offers nobody. Not the previous workshop's people, not
 *      everybody, not silence over an empty box.
 *      Web: {@link WorkshopArtisans.failed}. Android: `QuestionnaireArtisanOptions.failed`.
 *   6. A WORKSHOP CHANGE NEVER UNTICKS ANYBODY. The roster is the OFFER; it is not a proof that a
 *      tick is wrong. A ticked artisan the roster does not hold is drawn anyway and NAMED in a
 *      sentence under the picker ({@link artisansNotAtWorkshop}, {@link outOfWorkshopNotice}).
 *      Android: the `keep`/`mergeArtisansById` rescue, and the same sentence from
 *      `ui/RecordPickers.outOfWorkshopNotice`.
 *   7. THE OPTIONS ARE THE SERVER'S ROWS IN THE SERVER'S OWN ORDER (`createdAt desc`) — neither
 *      client re-sorts.
 *   8. THE INTERVIEW LIST IS SCOPED BY THE SAME WORKSHOP.
 *   9. ANYTHING NEEDING ONE ARTISAN TAKES ELEMENT 0 of the selection.
 *
 * ── WHY RULE 6 IS THE WAY ROUND IT IS, BECAUSE IT USED TO BE THE OTHER WAY ───────────────────────
 *
 * Until this pass the web DROPPED a ticked artisan the new workshop's complete roster did not hold,
 * and Android KEPT them. One gesture, two clients, two different rows in
 * `QuestionnaireInterviewArtisan` — and the drop was silent: no banner, no sentence, no undo. The
 * two behaviours were each defended at length in their own file, and neither file mentioned the
 * other.
 *
 * KEEPING WON, on three counts.
 *
 *   • **The roster cannot prove a tick wrong.** `artisan_workshop_clause` counts three links, and
 *     the third is *having sat in an interview taken at the workshop* — a link THIS FORM CREATES.
 *     An artisan who travelled to this workshop is, by construction, absent from its roster until
 *     the interview naming them is filed. Unticking them is the form refusing to record the one
 *     fact it exists to record.
 *   • **The failure mode of dropping is a record saved EMPTY.** `/questionnaire?artisanId=…` from
 *     the artisans page, and the carry-forward bag after a tool or a product, both routinely name
 *     somebody filed at another workshop. Both went in ticked and came out unticked about a second
 *     later, with the RESP block blanking itself on the way past, and `artisanIds: []` on the POST.
 *     The failure mode of keeping is a record saved with an artisan the researcher can SEE is
 *     ticked and can untick in one tap.
 *   • **Silence was the actual defect.** A form that edits a researcher's selection on their behalf
 *     must say so — the rule this repository already applies to truncated lists in
 *     `components/data/cappedList`. Once you are obliged to print a sentence either way, the
 *     sentence that keeps the data is the better one.
 *
 * The rejected alternative was to keep the drop and make it loud ("Ramesh Kumar was removed from
 * this interview"). It loses the handoff anyway — the researcher must go and re-tick somebody the
 * form has just decided against — and it has to be implemented twice, identically, in two languages,
 * where the version of it that keeps the tick has to be implemented in neither: it is what both
 * clients do when nothing removes anything.
 *
 * `e2e/questionnaire-artisan-scope-unit.spec.ts` asserts the web half and
 * `app/src/test/java/com/fieldrepository/app/data/QuestionnaireScopeTest.kt` the Kotlin half. If you
 * change a rule here and no Kotlin test changes with it, you have just created the disagreement the
 * owner is most tired of.
 */

import { useEffect, useMemo, useState } from "react";

import { cutOf, LIST_PAGE_CEILING, mergeById, type ListCut } from "@/components/data/cappedList";
import type { CarryScopeState } from "@/components/forms/CarryContextBanner";
import type { DropdownOption } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { Artisan } from "@/lib/types";

/**
 * HOW MANY PAGES OF THE ARTISAN LIST THIS FORM WILL WALK, and why it walks any at all.
 *
 * `pageSize` is clamped to `MAX_PAGE_SIZE = 100` server-side (`backend/app/services/pagination.py`)
 * and the route declares `Query(20, ge=1, le=100)` on top of it, so 100 is refused-past and not a
 * tunable — a single request cannot hold more, however it is asked. On a form whose entire purpose
 * is linking an interview to the RIGHT person, "the artisan you want may simply not be in the list"
 * is not a defect that can be papered over with a sentence: a researcher who cannot find the person
 * in front of them cannot file the interview at all.
 *
 * So this picker PAGES, and then still says what it could not reach. Four pages past the first is
 * 500 artisans, which covers the largest table the sibling deployment has ever counted behind this
 * picker (749 artisans across every workshop; one workshop's share of that is far smaller) while
 * bounding the worst case at five requests — and pages 2..N go out together, so it is two round
 * trips, not five.
 *
 * RAISING THIS ALONE WOULD FIX NOTHING, which is `components/data/cappedList`'s standing rule and
 * the reason the notice ships beside it: moving a cut is not telling anybody where the cut is.
 */
export const ARTISAN_PAGE_BUDGET = 5;

/**
 * The plural noun the capped-list sentence is built around — "Showing 100 of 240 …".
 *
 * It names the SCOPE and not just the record type, because the two sentences answer different
 * questions: under a workshop the reader needs to know that this WORKSHOP has more people than are
 * listed, not that the repository does.
 */
export function artisanScopeNoun(workshopId: string): string {
  return workshopId ? "artisans at this workshop" : "artisans";
}

/**
 * The query for one page of the artisan picker's options.
 *
 * ── "NO WORKSHOP SELECTED" SHOWS EVERY ARTISAN, AND HERE IS THE ARGUMENT ─────────────────────────
 *
 * The alternative — an empty picker under "choose a workshop first" — was rejected on three counts.
 *
 * 1. **It would block a legal record.** `submit` sends `workshopId: workshop.workshopId || null` and
 *    the column is nullable: an interview that belongs to no workshop is a real thing this product
 *    stores. Refusing to offer anybody until a workshop is picked would make that record
 *    unfileable, and the researcher's only way out would be to attach the interview to a workshop it
 *    was not taken at — corrupting the very scoping this change exists to establish.
 * 2. **"Absent" already means "all" everywhere else in this stack.** `resolve_workshop_ids` returns
 *    `None` for an absent or empty `workshopIds` and documents it in as many words: *"``None``
 *    (absent, empty, or all-blank) means DO NOT FILTER … the default state of the control is 'all
 *    workshops' and must not be spelled the same way as a mistake."* Android's
 *    `repository.artisans(workshopIds = null)` sends nothing and gets everybody. Inventing a second
 *    meaning for the empty scope on ONE screen of ONE client is exactly how two surfaces come to
 *    answer one question differently.
 * 3. **It is not the misleading case.** An unnarrowed list under no workshop claims nothing it
 *    cannot support: the reader narrowed nothing, so they are shown everything, and the capped-list
 *    sentence tells them what "everything" left out. The misleading list is the one that LOOKS
 *    narrowed and is not — a workshop named in the box above and strangers in the box below. That is
 *    the defect, and it is fixed by scoping when there IS a scope, not by blanking when there is
 *    none.
 *
 * `undefined` rather than `""` for the absent scope: `buildQuery` drops `undefined`, `null` and `""`
 * alike (`lib/api.ts`), so both spellings happen to work today — `undefined` is the one that says
 * "not asked for" rather than "asked for, blankly".
 */
export function workshopArtisanParams(workshopId: string, page: number): Record<string, string | number | undefined> {
  return {
    // See the file header: the PLURAL alone. The singular `workshopId` is a NARROWER filter on this
    // route, not an equivalent one, and `list_artisans` ANDs them.
    workshopIds: workshopId || undefined,
    page,
    pageSize: LIST_PAGE_CEILING
  };
}

/**
 * Has the workshop picker finished choosing for itself?
 *
 * `useWorkshopSelection` opens a CREATE form on the most recent workshop the user may actually
 * submit to, and it finds that workshop asynchronously: it loads `/workshops`, then walks the first
 * few in occurrence order asking `/workshops/{id}/submission-check` until one answers yes
 * (`components/forms/WorkshopSelect.tsx`). Between mount and the end of that walk `workshopId` is
 * `""` — which is indistinguishable, to anything reading the value, from a researcher who has
 * deliberately chosen no workshop.
 *
 * FIRING THE ARTISAN REQUEST DURING THAT WINDOW is the bug this guard exists to prevent, and it is
 * not hypothetical: it is two requests where one would do, and for as long as the second is in
 * flight the picker shows the repository-wide list under a workshop name that has just appeared in
 * the box above — the exact screen the owner reported. `CompletionMatrixPanel` on this same page
 * holds its own first request for the same reason (`scope.settling`), and Android holds its
 * artisan load on `if (!scope.settled) return@LaunchedEffect`.
 *
 * The settled test is "the probe cannot still be running": it has loaded the workshop list, and
 * either it has landed on a workshop, or the user has touched the control, or there are no workshops
 * to land on. The last arm matters — on a deployment with no workshops at all the probe never runs
 * and `workshopId` stays `""` forever, so a guard that only asked "is `workshopId` empty" would hold
 * the artisan list hostage for the whole session.
 */
export function workshopScopeSettling(workshop: {
  loading: boolean;
  touched: boolean;
  workshopId: string;
  workshops: readonly unknown[];
}): boolean {
  if (workshop.loading) return true;
  if (workshop.touched || workshop.workshopId) return false;
  return workshop.workshops.length > 0;
}

/**
 * One artisan as a row in the picker: "Name - Craft - Place".
 *
 * The separator is " - " and NOT the " · " every other artisan picker in this app uses, because
 * `e2e/dropdown-option-labels.spec.ts` asserts this exact shape for this exact control and a reader
 * comparing two screens should not have to wonder whether the difference is meaningful. Keep the
 * label and the spec in step; the label is what a researcher searches the list by, so the craft and
 * the place are in it deliberately — two artisans in a district routinely share a name.
 */
export function artisanPickerOptionLabel(artisan: Artisan): string {
  return `${artisan.name} - ${artisan.craft?.name ?? "No craft"} - ${artisan.place}`;
}

/**
 * THE OPTIONS THE ONE ARTISAN CONTROL OFFERS.
 *
 * `scoped` is the workshop's own roster as the server returned it, in the server's order. That is
 * the whole offer — nothing from the repository-wide reference load is folded in, and THAT IS THE
 * FIX: the defect was precisely a repository-wide list standing in for a workshop's.
 *
 * The one exception, and it is not an offer: an id that is ALREADY TICKED and that the roster does
 * not hold is drawn anyway, out of `known`. A multi-select renders its trigger from the labels of
 * the options that match its values (`SearchableMultiSelect.chosenLabels`), so a ticked id with no
 * option makes the control say "Nothing selected" over a selection that is about to be submitted —
 * a blank chip for a person who IS on the record. Drawing a ticked row is the opposite of offering
 * an unticked one, and the spec asserts both halves.
 *
 * THAT ROW IS NOT A GAP THAT CLOSES ITSELF, and this is the difference from what this comment used
 * to say. It used to describe the rescue as covering a moment — the beat before the selection was
 * narrowed to the workshop. Nothing narrows the selection any more (rule 6 in the header): a deep
 * link (`/questionnaire?artisanId=…`), a carried context, or a researcher who ticked somebody and
 * then corrected the workshop all leave a permanent ticked row the roster does not hold, and it
 * stays drawn for as long as it stays ticked. {@link artisansNotAtWorkshop} is what names those rows
 * in a sentence, so the reader is told rather than left to notice.
 *
 * NO EXTRA REQUEST IS MADE FOR THAT ROW. `known` is everything this page has already loaded — the
 * repository-wide reference page plus every scoped page — so the rescue costs nothing. The rejected
 * alternative was `useRecordsOffPage("/artisans", …)` from `components/forms/recordPickers`, which
 * fetches a missing id one by one: right for an EDIT form that must draw a link it already holds in
 * the database, wrong here, where the same window in which a row is missing is the window in which
 * the selection is about to be narrowed, so every one of those requests would be for a row that is
 * being dropped as it lands.
 */
export function artisanPickerOptions({
  scoped,
  known,
  selectedIds
}: {
  /** The workshop's roster, server order. */
  scoped: readonly Artisan[];
  /** Every artisan row this page has loaded, for labels only. */
  known: readonly Artisan[];
  /** The ticked ids, in the researcher's own order. */
  selectedIds: readonly string[];
}): DropdownOption[] {
  const offered = new Set(scoped.map((artisan) => artisan.id));
  const rescued = selectedIds
    .filter((id) => id && !offered.has(id))
    .map((id) => known.find((artisan) => artisan.id === id))
    .filter((artisan): artisan is Artisan => Boolean(artisan));
  return mergeById(scoped as Artisan[], rescued).map((artisan) => ({
    value: artisan.id,
    label: artisanPickerOptionLabel(artisan)
  }));
}

/**
 * The one artisan anything single-valued uses: the RESP prefill and the carry bag. `""` when nobody
 * is ticked. See the file header for why it is element 0 and why that is stable.
 */
export function primaryInterviewArtisanId(selectedIds: readonly string[]): string {
  return selectedIds.find((id) => Boolean(id)) ?? "";
}

/**
 * The SET key the shared-entry lookup is keyed on: sorted, blank-free, comma-joined.
 *
 * SORTED, because the interview is stored once per exact SET of artisans and ticking A then B is the
 * same interview as ticking B then A — the server's own `artisanSetKey` sorts for the same reason.
 * This is deliberately NOT the order the ids are SENT in: that order is the researcher's and is what
 * {@link primaryInterviewArtisanId} reads, so the two must not be collapsed into one array.
 *
 * Pulled out as a named function rather than left inline so that "what makes two selections the same
 * interview" has one definition a test can stand in front of — the "a shared entry already exists
 * for this set of artisans" banner is keyed on it, and that banner is the only thing stopping a
 * second researcher from starting a duplicate sitting.
 */
export function artisanSetKey(selectedIds: readonly string[]): string {
  return [...new Set(selectedIds.filter(Boolean))].sort().join(",");
}

/**
 * WHICH TICKED ARTISANS THE WORKSHOP'S ROSTER DOES NOT ACCOUNT FOR — a SENTENCE, not a deletion.
 *
 * ── WHAT THIS FUNCTION REPLACED, AND WHY IT IS NOT THAT ─────────────────────────────────────────
 *
 * `artisansToKeepForWorkshop` stood here and returned the survivors of a workshop change, and
 * `useArtisanSelectionScope` wrote them straight back into the form's state. It silently unticked
 * people. The argument for it was the one written directly below — a researcher who ticks somebody
 * at last week's workshop and then corrects the workshop should not file the interview naming last
 * week's people — and the argument is REAL. It is answered here by saying so out loud instead, for
 * the three reasons the file header sets out at length (rule 6): the roster cannot prove a tick
 * wrong, because filing this very interview is one of the three ways an artisan comes to belong to a
 * workshop; the silent drop emptied the `?artisanId=` handoff and the carry-forward bag, which are
 * the two paths that most often name somebody filed elsewhere; and Android never dropped, so the two
 * clients saved different rows for one gesture.
 *
 * ── THE RULING IS THE SAME THREE-WAY ONE. ONLY THE CONSEQUENCE CHANGED ──────────────────────────
 *
 * "Absent from the list" has three causes and only one of them is "this workshop does not know
 * them", so this function stays silent unless it is certain — `craftChangeClearsArtisan`'s rule
 * (`components/forms/recordPickers`) applied to a workshop instead of a craft:
 *
 *   • The roster for THIS workshop has not landed (`loadedForWorkshop !== workshopId`). The page
 *     knows nothing at all, so it says nothing. Without this arm the sentence would appear on every
 *     mount and on every workshop change, for the length of a round trip, naming everybody — and a
 *     warning that is usually wrong is a warning nobody reads. It also covers the failed request,
 *     where `loadedForWorkshop` stays null on purpose.
 *   • The roster IS CUT — it stopped at {@link ARTISAN_PAGE_BUDGET} pages with more rows behind it.
 *     An artisan absent from a truncated list may be perfectly well linked here and simply past the
 *     cut, and `components/data/cappedList` exists to stop a pagination boundary being reported as a
 *     fact about the world. The capped-list notice is already on screen saying the list is short;
 *     this one stays quiet rather than contradicting it.
 *   • Otherwise the roster is the complete answer for this workshop, so absence means absence — and
 *     THAT is worth a sentence.
 *
 * Returns the names' ids IN THE ORDER GIVEN, so the sentence lists people in the researcher's own
 * tick order and reads the same on two screens.
 */
export function artisansNotAtWorkshop({
  selectedIds,
  offeredIds,
  loadedForWorkshop,
  workshopId,
  cut
}: {
  selectedIds: readonly string[];
  /** The ids on the loaded roster. */
  offeredIds: readonly string[];
  /** Which workshop the loaded roster belongs to; null before the first load, and after a failure. */
  loadedForWorkshop: string | null;
  /** The workshop now selected ("" = none). */
  workshopId: string;
  /** The roster's cut, or null when it is whole. */
  cut: ListCut | null;
}): string[] {
  if (loadedForWorkshop !== workshopId) return [];
  if (cut) return [];
  const offered = new Set(offeredIds);
  return selectedIds.filter((id) => Boolean(id) && !offered.has(id));
}

/**
 * THE SENTENCE UNDER THE PICKER naming the ticked artisans this workshop's roster does not hold, or
 * "" when there is nothing to say.
 *
 * WHY IT DOES NOT READ AS A WARNING. Nothing is wrong yet, and in the commonest case nothing is
 * wrong at all: an interview taken at this workshop with somebody whose own record is filed at
 * another one is an ordinary event, and filing it is precisely what creates the link that would have
 * put them on this roster. So the sentence states the fact, states what saving will do, and stops.
 * Wording it as "these artisans do not belong here" would push a researcher into unticking a
 * perfectly good selection to make a message go away.
 *
 * ONE FUNCTION, TWO CLIENTS. `ui/RecordPickers.outOfWorkshopNotice` is the Kotlin twin, word for
 * word — the same reason `listCutNotice` has one. Two screens describing the same situation in two
 * different sentences is how a researcher learns that neither of them means much.
 *
 * NAMES AND NOT A COUNT. "1 artisan is not recorded at this workshop" makes the reader open the
 * control and compare it against a roster to find out who; the names are already in hand
 * (`knownArtisans`), and the whole point of the sentence is that it can be acted on without looking
 * anything up. The list is capped at four with a remainder, because a selection of thirty would
 * otherwise print a paragraph.
 */
export const OUT_OF_WORKSHOP_NAMES_SHOWN = 4;

export function outOfWorkshopNotice(names: readonly string[]): string {
  const clean = names.map((name) => name.trim()).filter(Boolean);
  if (clean.length === 0) return "";
  const shown = clean.slice(0, OUT_OF_WORKSHOP_NAMES_SHOWN);
  const remainder = clean.length - shown.length;
  const list = shown.join(", ") + (remainder > 0 ? ` and ${remainder} more` : "");
  const subject = clean.length === 1 ? `${list} is` : `${list} are`;
  return (
    `${subject} not recorded at this workshop yet. They stay ticked — saving this interview here ` +
    `is what links them to it. Untick anyone who should not be on it.`
  );
}

export type WorkshopArtisans = {
  /** The selected workshop's roster, server order. What the picker offers. */
  scoped: Artisan[];
  /** Every artisan row loaded so far, for labels only. Never narrowed. */
  known: Artisan[];
  /**
   * Which workshop `scoped` belongs to — and the flag that says "this roster is about the workshop on
   * screen" rather than about some other one.
   *
   * NULL IS THE COMMON CASE AND NOT JUST THE FIRST ONE: it is null before the first load, null again
   * from the instant the workshop changes (contract rule 4 clears the roster before awaiting), and
   * null after a failed request. Every consumer therefore has to treat "null" and "some other
   * workshop" alike, which is what both `artisansNotAtWorkshop` and the picker's `emptyLabel` do.
   */
  loadedForWorkshop: string | null;
  /** What the roster could not reach, or null when it holds every row. */
  cut: ListCut | null;
  /**
   * Did the roster request for the workshop now on screen FAIL?
   *
   * Contract rule 5. It is not the same fact as "the roster is empty" and it is not the same fact as
   * "the roster has not landed", and a picker that cannot tell the three apart prints "No artisans
   * are recorded at this workshop yet" over a dropped request — a claim about the repository made
   * out of a claim about the network. Cleared at the start of every attempt, so a workshop change is
   * a fresh question and not an inherited verdict.
   */
  failed: boolean;
  /**
   * "Have the reference lists arrived?" — handed straight to `carryScope`, which treats "not visible
   * to me" and "no signal" differently. Moved ONLY by the repository-wide mount load; see the hook.
   */
  referenceState: CarryScopeState;
};

/**
 * THE ARTISAN OPTIONS FOR ONE WORKSHOP, RE-FETCHED WHENEVER THE WORKSHOP MOVES.
 *
 * ── WHY THERE ARE TWO LOADS AND NOT ONE ──────────────────────────────────────────────────────────
 *
 * (A) A repository-wide page at mount, unscoped, used for NOTHING the researcher sees. Its one job
 *     is `carryScope("artisan", …)`: `useCarryContext` reads that id list to decide whether a
 *     carried artisan is still REACHABLE, and prunes the carried record — and everything hanging off
 *     it — when it is not (`components/forms/CarryContextBanner`). Handing it the workshop-scoped
 *     list instead would answer a different question with the same array: an artisan who is alive,
 *     visible and simply documented at another workshop would be pruned as though they had been
 *     deleted. Worse, the carried bag also carries a WORKSHOP, which `onApply` writes into the
 *     picker — so the prune would happen while the form was still on its default workshop and would
 *     destroy the very context that was about to move it. `useCraftAndArtisanOptions` keeps its own
 *     mount load for exactly this reason and says so.
 *
 *     Page one only. That is what this page has always done, so carry's reach is unchanged by this
 *     work; an artisan past the hundredth row of the whole table is not offered as a carried prefill
 *     today either. Paging it would be a change to a different feature's behaviour, made silently
 *     from here.
 *
 * (B) The workshop-scoped roster, re-fetched on every settled change of the workshop, PAGED up to
 *     {@link ARTISAN_PAGE_BUDGET} and then reported honestly. This is the picker's offer.
 *
 * When no workshop is selected the two requests are identical, and that is accepted rather than
 * deduplicated: the alternative is a shared cache entry whose two readers have different lifetimes
 * and different meanings, to save one GET in the one state this form is rarely in (it opens on a
 * workshop by default).
 *
 * ── REPLACE, NEVER MERGE ─────────────────────────────────────────────────────────────────────────
 *
 * `scoped` is ASSIGNED from each answer. `mergeById` is the right rule for a record form's picker,
 * where three requests describe one world and a narrower answer must not look like a shorter one —
 * and it is exactly the wrong rule here, where a narrower answer is the entire point. Merging would
 * leave the previous workshop's artisans on offer at the next workshop, which is the reported defect
 * reintroduced by a helper written to prevent a different one. `known` is the array that merges.
 */
export function useWorkshopArtisans({
  workshopId,
  settling
}: {
  /** The selected workshop ("" = none). */
  workshopId: string;
  /** True while the workshop picker is still choosing its own default — see {@link workshopScopeSettling}. */
  settling: boolean;
}): WorkshopArtisans {
  const [scoped, setScoped] = useState<Artisan[]>([]);
  const [known, setKnown] = useState<Artisan[]>([]);
  const [loadedForWorkshop, setLoadedForWorkshop] = useState<string | null>(null);
  const [cut, setCut] = useState<ListCut | null>(null);
  const [failed, setFailed] = useState(false);
  const [referenceState, setReferenceState] = useState<CarryScopeState>("pending");

  // (A) The reachability probe. Never rendered; see the header.
  useEffect(() => {
    let cancelled = false;
    listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setKnown((previous) => mergeById(previous, result.items));
        setReferenceState("loaded");
      })
      .catch(() => {
        if (!cancelled) setReferenceState("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // (B) The offer.
  useEffect(() => {
    if (settling) return;
    let cancelled = false;
    /*
      INVALIDATED HERE, BEFORE THE AWAIT — contract rule 4, and the line this hook was missing.

      `scoped` describes ONE workshop and the workshop it describes has just changed. Assigning the
      replacement on success and leaving the old rows up until then looks like caution and is not:
      for the whole length of a field connection's round trip the picker lists the PREVIOUS
      workshop's people under the new workshop's name, with `cut` still saying "Showing 100 of 240
      artisans at this workshop" about a workshop nobody is looking at. That is the owner's first
      report exactly — *"the workshop is already selected … why are artisans from other workshops
      showing up"* — and it was reachable on every single workshop change, not only on a failure.

      `loadedForWorkshop` goes with them, and it is the important half. It is the flag every consumer
      reads to tell "this roster is about the workshop on screen" from "this roster is about some
      other workshop": the picker's `emptyLabel` prints "Loading artisans…" off it, and
      {@link artisansNotAtWorkshop} refuses to name anybody while it disagrees with `workshopId`. A
      stale roster left standing under a stale label is a lie that type-checks.

      SHOWING NOBODY IS SAFE, which is what makes clearing possible at all. Ticked rows are drawn
      from `known` by {@link artisanPickerOptions} whatever the roster holds, so a selection never
      disappears from the control; nothing unticks anybody (rule 6); and the empty state has its own
      sentence. The rejected alternative — hold the previous rows and dim the control — keeps the
      wrong names on screen and merely apologises for them, and would have had to be built twice.
    */
    setScoped([]);
    setLoadedForWorkshop(null);
    setCut(null);
    setFailed(false);
    (async () => {
      try {
        const first = await listResource<Artisan>("/artisans", workshopArtisanParams(workshopId, 1));
        if (cancelled) return;
        // Pages two onward go out TOGETHER rather than one after another. `total` and `pages` came
        // back with page one, so the remaining page numbers are known exactly — waiting for each
        // answer to learn the next number would turn a field connection's latency into a multiple of
        // itself for no extra information. `Promise.all` preserves input order, so concatenating the
        // results keeps the server's own `createdAt desc` ordering intact end to end, which is the
        // ordering half of the Android parity contract.
        const lastPage = Math.min(first.pages || 1, ARTISAN_PAGE_BUDGET);
        const rest =
          lastPage > 1
            ? await Promise.all(
                Array.from({ length: lastPage - 1 }, (_, index) =>
                  listResource<Artisan>("/artisans", workshopArtisanParams(workshopId, index + 2))
                )
              )
            : [];
        if (cancelled) return;
        const rows = [first, ...rest].flatMap((result) => result.items);
        setScoped(rows);
        setKnown((previous) => mergeById(previous, rows));
        setCut(cutOf(rows.length, first.total, artisanScopeNoun(workshopId)));
        setLoadedForWorkshop(workshopId);
      } catch {
        /*
          SAID, NOT SWALLOWED — contract rule 5.

          There is nothing left to "leave standing": the invalidation above already dropped the
          previous workshop's rows, deliberately. What remains is an empty offer, and an empty offer
          with no explanation is the worst of the three states this picker can be in — it reads as
          "this workshop has nobody in it", which is a claim about the repository assembled out of a
          dropped packet. One flag, one sentence, and a workshop the researcher can re-pick to ask
          again.

          STILL NOT THE PAGE'S ERROR BANNER. That banner is driven by the instrument load, which is
          the failure that stops the form working at all; a roster that could not be narrowed stops
          the form OFFERING, which is a smaller thing and belongs beside the control it is about.
          Guarded on `cancelled` so a request superseded by the next workshop cannot paint a failure
          over the answer that replaced it.
        */
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [workshopId, settling]);

  return useMemo(
    () => ({ scoped, known, loadedForWorkshop, cut, failed, referenceState }),
    [scoped, known, loadedForWorkshop, cut, failed, referenceState]
  );
}

