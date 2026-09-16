"use client";

/**
 * THE TWO-LEVEL RECORD PICKER THAT SITS ON TOP OF EVERY UPDATE SURFACE.
 *
 * A workshop dropdown, and under it the records OF that workshop, searchable. Pick one and it opens
 * for editing. The rules it obeys — the labels, the destinations, the sentences, the debounce, and
 * whether a keystroke is allowed to cost a request at all — are the PURE FUNCTIONS in the first
 * half of this file, below; the component at the bottom is only the wiring, the requests and the
 * markup. Read the second header, over `RECORD_KINDS`, for the argument behind the rules —
 * including the one about why this control decides nothing whatever about permission.
 *
 * ONE FILE RATHER THAN TWO, and not by preference: `recordSwitcher.ts` beside `RecordSwitcher.tsx`
 * are the same path to Windows and to macOS, and TypeScript refuses the pair outright (TS1149).
 * It is also how the neighbour does it — `forms/recordPickers.ts` holds `craftChangeClearsArtisan`
 * and `useCraftAndArtisanOptions` together — and it is what makes this file the one-to-one twin of
 * Android's `ui/RecordSwitcher.kt`, which is a single file for the same reason.
 *
 * ── WHY THE WORKSHOP DROPDOWN IS `useWorkshopSelection` AND NOT A NEW LOADER ────────────────────
 *
 * The requirement is that the primary dropdown default to the most recent workshop THIS USER HAS
 * ACCESS TO, and that behaviour already exists, argued and paid for, in
 * `forms/WorkshopSelect.useWorkshopSelection`: it loads the list in occurrence order and then walks
 * DOWN that order probing `GET /workshops/{id}/submission-check`, stopping at the first workshop the
 * account may actually submit to, and settling on the most recent one anyway when every recent
 * workshop belongs to somebody else. Taking the head of the list outright — which is what
 * `FunnelFilters` does, deliberately, for a different job — lands a researcher on a workshop that is
 * not theirs whenever the newest one is not.
 *
 * TWO THINGS ABOUT REUSING IT ARE WORTH SAYING OUT LOUD, because both look like oversights:
 *
 *  1. **`isEdit` is false even though this is an edit page.** That flag does not mean "is the user
 *     editing"; it means "is this picker bound to a stored column that must not be clobbered". On a
 *     record form it is, and the default is suppressed so opening a record never rewrites its
 *     workshop link. Here the picker is bound to NOTHING — it is a filter over a list — so there is
 *     no link to protect and the default is the whole point. Passing true would leave the dropdown
 *     empty and the control useless.
 *
 *  2. **`<WorkshopSelect>` is NOT rendered, only its hook.** That component draws the window notice
 *     ("This workshop ended on 23 Sept 2026. Saving now is recorded as a late submission.") and
 *     hosts the late-submission dialog. Both are true things to say about a workshop you are about
 *     to SAVE INTO and nonsense over one you are merely browsing: nothing here writes anything, so
 *     there is no submission to be late. A filter that warned about late submissions would be
 *     teaching the researcher to ignore that warning in the one place it matters.
 *
 * `canSubmit` is admittedly a proxy for "has access to" rather than the thing itself — it is the
 * strictest question this API can answer about a workshop and a user, and it is the question the
 * record forms already ask. It only ever chooses a DEFAULT: every workshop the account can see stays
 * selectable in the dropdown, and what comes back under one is decided by `viewable_where` on the
 * server, not here.
 *
 * ── ANDROID PARITY ─────────────────────────────────────────────────────────────────────────────
 *
 * `android/app/src/main/java/com/fieldrepository/app/ui/RecordSwitcher.kt` is this control, composed
 * out of `rememberWorkshopPicker` and `SearchableSelectField` the way this one is composed out of
 * `useWorkshopSelection` and `ComboBox`. Change a rule here and change it there.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { CalendarRange, PencilLine } from "lucide-react";

import { cutOf, LIST_PAGE_CEILING, mergeById, type ListCut } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { useWorkshopSelection } from "@/components/forms/WorkshopSelect";
import { workshopScopeLabel } from "@/components/WorkshopScopeSelect";
import { ComboBox, Dropdown } from "@/components/ui/Dropdown";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import { listResource } from "@/lib/api";


/**
 * "WHICH RECORD AM I EDITING?" — THE PICKER'S RULES, AS FUNCTIONS THE COMPONENT ONLY CALLS.
 *
 * Every update surface in this product opens on ONE record, chosen somewhere else: a row in a list,
 * a search result, a dashboard tile, a `?edit=` link. Once a researcher is on that page there is no
 * way to reach the NEXT record except to navigate back out, re-find the list, re-apply whatever
 * filter they had, and click again — five interactions to move between two records that were filed
 * ten minutes apart at the same workshop. Everything from here to `RecordSwitcher` at the bottom is
 * the rule half of the control that closes that: a workshop dropdown over a record dropdown, on the
 * edit page itself.
 *
 * ── WHY THE RULES ARE PURE FUNCTIONS AND NOT BRANCHES IN THE JSX ────────────────────────────────
 *
 * The same reason `recordPickers.ts` gives, and it is the same class of defect. Three of the five
 * decisions below cannot be produced on a desk on purpose:
 *
 *  - the "this list stopped short" arm needs a workshop with more than `LIST_PAGE_CEILING` records
 *    of one type in it;
 *  - the "cannot say yet" arm needs a request that is in flight at the instant somebody reads the
 *    screen;
 *  - the "could not load" arm needs the network to be down, which is the state this product exists
 *    for and the state nobody has while they are testing it.
 *
 * A decision buried in JSX is only ever exercised by somebody looking at a screen, and nobody can
 * look at those three. There is no React renderer in this repository's devDependencies either
 * (Playwright is the whole of it), so the alternative to pure functions is not a component test —
 * it is no test. Sharing a file with the component costs nothing here, because a `-unit` spec
 * imports the named exports it wants and never touches `RecordSwitcher` itself.
 *
 * ── ANDROID PARITY ──────────────────────────────────────────────────────────────────────────────
 *
 * Every function here has a Kotlin twin in
 * `android/app/src/main/java/com/fieldrepository/app/ui/RecordSwitcher.kt`, asserted on both sides
 * (`frontend/e2e/record-switcher-unit.spec.ts` here, `RecordSwitcherTest.kt` there) — and the option
 * LABELS are asserted byte-for-byte, because the two clients were already spelling them the same way
 * by hand in `RecordPickerScreen` and hand-spelled agreement is agreement until somebody edits one
 * of them. If you change a rule here and the Kotlin test still passes unchanged, you have just
 * created the defect this product family repeats most often: two surfaces answering one question
 * differently, with nothing on either to say which is right.
 *
 * ── AND THE ONE THING THIS MODULE DELIBERATELY DOES NOT DO ──────────────────────────────────────
 *
 * IT DECIDES NOTHING ABOUT PERMISSION. {@link recordEditHref} returns a URL to a surface that
 * already exists and is already guarded — the `/[id]/edit` routes and the `?edit=` deep link — and
 * the options it is picking from come from `GET /{collection}`, which every list route scopes with
 * `viewable_where(current_user)` before it pages. So the picker cannot reach a record the researcher
 * could not already have reached by typing the URL, and narrowing a dropdown by workshop subtracts
 * from what they see without adding anything. A filter is a convenience; the gate is elsewhere and
 * stays there. Do not "optimise" this into fetching a record and rendering it in place — that would
 * turn a convenience into a second, looser way in.
 */

/**
 * The record types that HAVE an update surface, and the whole list of them.
 *
 * Workshops are deliberately absent, and their absence is a decision rather than an omission. A
 * workshop IS the primary dropdown; filtering workshops by workshop is not a narrower question, it
 * is the same question asked twice, and the second dropdown would hold exactly one row — the one
 * already chosen above it. `/workshops` keeps its own inline form and its own `?edit=` deep link,
 * which is the right control for it.
 *
 * Media is absent for a different reason: it has no edit form on either client. Android's
 * `EditScreen` routes `EntryMode.MEDIA` to `ViewDataDetail` (the file and its transcript), and the
 * web's search results open the object itself. There is nothing for a picker to open.
 *
 * Questionnaire interviews are absent for a third reason, and this one is worth stating because the
 * omission looks arbitrary next to the five below: an interview is not identified by a name the way
 * a record is. Android's own picker has to GROUP interviews by artisan set and pick a representative
 * before it can label one (`RecordPickerScreen`, `interviewGroupKey`), so "the record, searchable" is
 * a different control there with a different notion of what one row means. Giving it this one would
 * mean either duplicating that grouping rule in a sixth place or offering a list of rows that do not
 * correspond to what the researcher thinks of as an interview.
 */
export const RECORD_KINDS = ["artisan", "craft", "process", "product", "tool"] as const;

export type RecordKind = (typeof RECORD_KINDS)[number];

/**
 * The fields the LABELS read, and nothing else.
 *
 * Deliberately one loose shape rather than a union of `Artisan | Craft | ProcessRecord | …`. The
 * hook that loads these rows asks five different collections through one generic `listResource`, so
 * a union would have to be narrowed by the very `kind` discriminator the label function already
 * takes — a type-level restatement of the switch below, which is a second place for the two to
 * disagree. Every field is optional because every field IS optional in at least one of the five, and
 * `recordOptionLabel` treats absence as "leave that half of the label out" rather than printing
 * "undefined".
 *
 * The `id` is not optional: a row that cannot be identified cannot be an option.
 */
export type SwitchableRecord = {
  id: string;
  /** Artisan, craft and process all name themselves here. */
  name?: string | null;
  /** Artisan and craft. */
  place?: string | null;
  /** Product. */
  productName?: string | null;
  /** Product and tool — the typed artisan name, which older rows carry with no FK beside it. */
  artisanName?: string | null;
  /** Tool. */
  toolkitName?: string | null;
  /** Process — its parent product, hydrated by the list route. */
  product?: { productName?: string | null } | null;
  /** Every one of the five carries this; null on rows filed before workshops existed. */
  workshopId?: string | null;
};

/**
 * THE SEPARATOR BETWEEN A LABEL'S TWO HALVES: U+00B7 MIDDLE DOT, SPACED.
 *
 * Named rather than inlined five times because it is the character the Kotlin twin has to match, and
 * a hyphen or an en dash typed into one of ten string templates is a difference no compiler and no
 * type check has an opinion about. It is also already what this product uses everywhere a row names
 * two things — `workshopScopeLabel` in `WorkshopScopeSelect`, Android's `RecordPickerScreen` — so a
 * reader who has met one option list recognises the next.
 */
export const LABEL_SEPARATOR = " · ";

/** `"Ram Kumar · Bagru"`, or just `"Ram Kumar"` when the second half is blank. */
function joined(head: string | null | undefined, tail: string | null | undefined): string {
  const left = (head ?? "").trim();
  const right = (tail ?? "").trim();
  if (!left) return right;
  if (!right) return left;
  return `${left}${LABEL_SEPARATOR}${right}`;
}

/**
 * What one record type is called, where its rows come from, and where editing one happens.
 *
 * ONE TABLE, FIVE ENTRIES, READ BY EVERYTHING. The five collections were already spelled out
 * independently in the two clients' pickers, in `deleteByMode`, and in every list page's
 * `listResource` call; adding a sixth independent spelling is how `/processes` ends up reachable
 * under one name here and another one there.
 */
type RecordKindSpec = {
  /** The list collection, e.g. `/artisans`. `GET {endpoint}` pages it; `{endpoint}/{id}` reads one. */
  endpoint: string;
  /**
   * The plural noun the COUNTING sentences are built around, lower case, as it reads mid-sentence.
   *
   * It carries "in this workshop" and that phrase is load-bearing rather than decorative.
   * `cappedListNotice` prints this noun verbatim into "Showing 100 of 240 …", and over a
   * workshop-scoped list the bare plural would make that a claim about the REPOSITORY: a reader who
   * takes "240 artisans" as the corpus and then cannot find one of them in the box has been told
   * something false by a sentence whose whole job is to stop exactly that.
   */
  noun: string;
  /**
   * The bare plural, for the sentences that talk about the ROWS rather than counting them against a
   * workshop. "These artisans could not be loaded" is a fact about a failed request; splicing "in
   * this workshop" into it would attach the failure to the workshop, which is the one thing it is
   * not evidence about.
   */
  plural: string;
  /** The singular, for the sentences that name one record rather than counting them. */
  singular: string;
  /** How one row reads in the dropdown. Byte-for-byte the Kotlin twin's. */
  label: (record: SwitchableRecord) => string;
};

const RECORD_KIND_SPECS: Record<RecordKind, RecordKindSpec> = {
  artisan: {
    endpoint: "/artisans",
    noun: "artisans in this workshop",
    plural: "artisans",
    singular: "artisan",
    label: (record) => joined(record.name, record.place)
  },
  craft: {
    endpoint: "/crafts",
    noun: "crafts in this workshop",
    plural: "crafts",
    singular: "craft",
    label: (record) => joined(record.name, record.place)
  },
  process: {
    endpoint: "/processes",
    noun: "processes in this workshop",
    plural: "processes",
    singular: "process",
    label: (record) => joined(record.name, record.product?.productName)
  },
  product: {
    endpoint: "/products",
    noun: "products in this workshop",
    plural: "products",
    singular: "product",
    label: (record) => joined(record.productName, record.artisanName)
  },
  tool: {
    endpoint: "/tools",
    noun: "tools in this workshop",
    plural: "tools",
    singular: "tool",
    label: (record) => joined(record.toolkitName, record.artisanName)
  }
};

export function recordEndpoint(kind: RecordKind): string {
  return RECORD_KIND_SPECS[kind].endpoint;
}

export function recordNoun(kind: RecordKind): string {
  return RECORD_KIND_SPECS[kind].noun;
}

export function recordPlural(kind: RecordKind): string {
  return RECORD_KIND_SPECS[kind].plural;
}

export function recordSingular(kind: RecordKind): string {
  return RECORD_KIND_SPECS[kind].singular;
}

/**
 * One dropdown row's text.
 *
 * A row whose every labelled field is blank still has to be pickable — it is a real record and the
 * researcher may well be opening it BECAUSE its name is empty — so it falls back to naming its type
 * rather than rendering an empty option the eye slides straight past. "Untitled artisan" is the same
 * shape as the "Untitled workshop" the workshop pickers already print for the same reason.
 */
export function recordOptionLabel(kind: RecordKind, record: SwitchableRecord): string {
  const label = RECORD_KIND_SPECS[kind].label(record).trim();
  return label || `Untitled ${RECORD_KIND_SPECS[kind].singular}`;
}

/**
 * THE QUERY PARAMETER THE INLINE-FORM PAGES RECEIVE AN EDIT INTENT ON.
 *
 * It is `EDIT_PARAM` in `components/hooks/useEditDeepLink.ts`, and this is a second spelling of it
 * rather than an import ON PURPOSE. That module is a React hook: it pulls in `next/navigation`,
 * `apiFetch` and the reduced-motion provider, none of which survive being imported into the Node
 * process a `-unit` spec runs in — and the whole value of this file is that a test can reach it
 * without a browser. So the literal is duplicated and the duplication is PINNED: the spec reads
 * `useEditDeepLink.ts` as text and fails if the two ever say different words. That check is the
 * import; this constant is just where it lands.
 */
export const EDIT_QUERY_PARAM = "edit";

/**
 * WHERE "OPEN THIS RECORD FOR EDITING" GOES — and the reason this is the only thing the picker does.
 *
 * Two shapes, because this application has two kinds of update surface and always has:
 *
 *   - artisans, products and tools have real routes, `/{collection}/{id}/edit`;
 *   - crafts and processes are edited by an INLINE form on their list page, so the intent has to
 *     travel as `?edit=<id>` and be picked up by `useEditDeepLink`.
 *
 * Handing back a URL — rather than fetching the record and swapping it into the form in place — is
 * what keeps this control a convenience instead of a new way in. The destination page does its own
 * `GET`, meets its own 403, renders its own error banner and (for the inline pages) runs its own
 * unsaved-changes guard on the way. Every one of those behaviours already exists and is already
 * tested; a picker that loaded the record itself would be a second implementation of all of them,
 * and the first one to drift would be the one nobody is looking at.
 *
 * The id is percent-encoded. Ids are cuid-shaped today and need no encoding, which is exactly why
 * this would be the line nobody noticed the day one of them was not.
 */
export function recordEditHref(kind: RecordKind, id: string): string {
  const encoded = encodeURIComponent(id);
  switch (kind) {
    case "craft":
      return `/crafts?${EDIT_QUERY_PARAM}=${encoded}`;
    case "process":
      return `/processes?${EDIT_QUERY_PARAM}=${encoded}`;
    case "artisan":
      return `/artisans/${encoded}/edit`;
    case "product":
      return `/products/${encoded}/edit`;
    case "tool":
      return `/tools/${encoded}/edit`;
  }
}

/** True when this kind is edited by a `?edit=` deep link rather than by its own route. */
export function isInlineEditSurface(kind: RecordKind): boolean {
  return kind === "craft" || kind === "process";
}

/**
 * THE CONTROL'S OWN HEADING — "Open a different artisan".
 *
 * A function rather than a literal in the JSX, because Android draws this string too: there it is the
 * title of the `RecordCard` the switcher sits in, which is a different element in a different file,
 * and a heading typed at each call site is a heading that says something slightly different on each
 * surface within a release. Both sides assert it.
 */
export function recordSwitcherTitle(kind: RecordKind): string {
  return `Open a different ${recordSingular(kind)}`;
}

/**
 * How far the loading of one workshop's records has got.
 *
 * Deliberately three values and not a boolean, and deliberately NOT collapsed into "do we have
 * rows". `recordPickers.ts` carries the long-form version of this argument under
 * `artisansLoadedForCrafts`: "no records" is a claim about the repository, and a screen that makes it
 * while the answer is still in flight — or after the answer failed to arrive — is stating as fact
 * something it has no basis for. The researcher's reasonable response to "no artisans are linked to
 * this workshop" is to go and create one, which is how a duplicate gets filed.
 */
export type RecordListState = "pending" | "loaded" | "unavailable";

/**
 * WHICH workshop the rows on screen belong to, compared with the one now selected.
 *
 * A separate question from {@link RecordListState} because the two come apart for a whole frame
 * every time the workshop changes: the previous workshop's rows are still in state, the new
 * workshop's request has been issued, and anything said about "this workshop" in that window is said
 * about the wrong one. Callers must pass the workshop the LOADED rows came from, not the selected
 * one, or this degenerates into `state === "loaded"` and stops being worth asking.
 */
export function recordsAreForWorkshop(loadedForWorkshop: string | null, workshopId: string): boolean {
  return loadedForWorkshop !== null && loadedForWorkshop === workshopId;
}

/**
 * THE SENTENCE UNDER THE RECORD DROPDOWN, or null when the control speaks for itself.
 *
 * Ordered so the states that must never be mistaken for each other are tested first, and the one
 * that matters most is last: "there are no records here" is only ever said once the rows on screen
 * are KNOWN to be this workshop's and KNOWN to have arrived. Said a moment early it is a claim about
 * the repository that nothing yet supports, and the researcher's reasonable response to it is to go
 * and create a record that may already exist — which is the defect `recordPickers.ts` was written
 * after, one dropdown over.
 *
 * ── WHAT IS DELIBERATELY NOT HERE: "NO MATCHES" ─────────────────────────────────────────────────
 *
 * An empty SEARCH is not a state this function has an opinion about, and giving it one would have
 * been the easy mistake. The picker's options array is never narrowed by the query — the control
 * filters it internally — so a `shown` counted here could only ever be the unfiltered length, and a
 * "no matches" arm hung off it would be a branch no input could reach. Both clients' pickers already
 * answer that question INSIDE the open panel, where the reader is looking when they are typing
 * ("No matches" in `SearchableSelect`, `emptyMessage` and the "12 of 74 match" count line on
 * Android). One sentence, in the one place it is being read, is the whole of what is wanted.
 *
 * Every string has a Kotlin twin and the two are asserted byte-for-byte. They are wordier than a
 * dash would be, and that is the point: each one names what is true and what is not, because the
 * reader is deciding whether to go looking somewhere else.
 */
export function recordListMessage({
  kind,
  state,
  loadedForWorkshop,
  workshopId,
  shown
}: {
  kind: RecordKind;
  state: RecordListState;
  /** The workshop the rows currently in state were loaded for; null before the first answer. */
  loadedForWorkshop: string | null;
  /** The workshop selected in the dropdown above. */
  workshopId: string;
  /** How many rows the dropdown is offering — the whole option list, not a filtered view of it. */
  shown: number;
}): string | null {
  if (state === "unavailable") {
    // Named as a LOADING failure and not as an empty workshop, and it says the open record is fine —
    // because the commonest way to reach this arm is a researcher in a courtyard with no signal, and
    // the question they are actually asking at that moment is whether their work is still there.
    return `These ${recordPlural(kind)} could not be loaded. The record open below is unaffected.`;
  }
  if (state === "pending" || !recordsAreForWorkshop(loadedForWorkshop, workshopId)) {
    // Covers both "the first request has not answered yet" and "the workshop changed a frame ago and
    // these are still the previous one's rows". Neither is a fact about the workshop now selected.
    return "Loading this workshop's records…";
  }
  if (shown === 0) {
    return `No ${recordNoun(kind)} yet. Pick another workshop above, or file the first one.`;
  }
  return null;
}

/**
 * THE CUT UNDER THE RECORD DROPDOWN — "this workshop has more than this list holds".
 *
 * A thin wrapper over `cutOf` and worth having anyway, because it is the one place that decides the
 * NOUN. Passing the plural by hand at each of the ten call sites (five kinds, two clients) is how
 * two screens come to describe one cut in two sentences, which `cappedList.ts` argues at length
 * teaches a reader that neither of them means much.
 */
export function recordListCut(kind: RecordKind, loaded: number, total: number): ListCut | null {
  return cutOf(loaded, total, recordNoun(kind));
}

/**
 * HOW LONG A KEYSTROKE WAITS BEFORE IT COSTS A REQUEST.
 *
 * 350ms, which is what every list page in this application already uses for its own live search
 * (`app/(protected)/products/page.tsx` and its four siblings all say `setTimeout(…, 350)`). Matching
 * them is not tidiness: a researcher types into the products list and into this picker in the same
 * session, and a control that felt slower or twitchier than the one next to it would read as the app
 * being inconsistent rather than as a different debounce.
 *
 * The Kotlin twin is `SEARCH_DEBOUNCE_MS` in `ui/RecordSwitcher.kt`, and the two are asserted equal.
 */
export const SEARCH_DEBOUNCE_MS = 350;

/**
 * SHOULD A KEYSTROKE REACH THE SERVER AT ALL?
 *
 * ONLY when the list on screen is not the whole answer — and for a workshop-scoped list that is the
 * uncommon case, which is the entire reason this predicate exists rather than an unconditional
 * request behind a debounce.
 *
 * The arithmetic: `GET /{collection}?workshopId=…` is clamped to `LIST_PAGE_CEILING = 100` rows
 * server-side and cannot be widened from any client. One WORKSHOP's records are a small fraction of
 * a table — the sibling deployment's 878 products spread over 196 workshops — so the first page is,
 * in practice, all of them, and every match a researcher could possibly type is already in the
 * browser. Filtering those locally is instant, costs nothing, and — the half that matters for this
 * product — WORKS WITH NO SIGNAL. Firing a request per keystroke into that would be pure loss:
 * slower, offline-fragile, and unable to find anything the local filter could not.
 *
 * When the list IS cut, the opposite holds and it holds absolutely: the rows past the cut are not in
 * the browser, so no amount of local filtering can reach them, and a search box that quietly
 * searched only the first hundred would be the exact lie `cappedList.ts` was written to end. Then,
 * and only then, the debounced request goes out.
 *
 * A blank query never searches the server either way — that is not a search, it is the list.
 */
export function shouldSearchServer(cut: ListCut | null, query: string): boolean {
  return cut !== null && query.trim().length > 0;
}

export function RecordSwitcher({
  kind,
  currentId,
  currentWorkshopId,
  onNavigate,
  className
}: {
  kind: RecordKind;
  /** The record the page is editing right now, so the picker can show what it is pointing at. */
  currentId: string;
  /**
   * The workshop that record is linked to, when the page knows it. Powers the one affordance that
   * makes the "most recent workshop" default liveable — see `canJumpToRecordWorkshop` below. Leave it out
   * and the control simply does not offer that shortcut; nothing else changes.
   */
  currentWorkshopId?: string | null;
  /**
   * How to go to the chosen record. Defaults to `router.push`.
   *
   * OVERRIDE IT ON A PAGE THAT PARKS NAVIGATION BEHIND ITS OWN UNSAVED-CHANGES PROMPT. `/crafts`
   * does: its `guard()` holds the action until the researcher has answered the dialog, and then runs
   * exactly the action that was asked for — which is what makes Discard land on the record they
   * PICKED rather than on wherever Back happens to point. See the note on `open` below for what the
   * pages that do not override it get instead, and why that is still safe.
   */
  onNavigate?: (href: string) => void;
  className?: string;
}) {
  const router = useRouter();
  const endpoint = recordEndpoint(kind);
  /**
   * "Is a form on this page holding unsaved work?" — asked of the shared guard, which is the same
   * thing the round back control in `PageHeader` asks before it navigates.
   *
   * IT IS NOT A QUESTION, IT IS AN ACTION: a form that is dirty puts its own prompt on screen and
   * answers true, meaning the navigation must be abandoned. Returns false where no form registered —
   * which is the honest answer for a page that has nothing to lose — so this is safe on every surface
   * whether or not one is mounted.
   */
  const intercept = useLeaveInterceptor();

  // The workshop list plus the access-aware default. See the header for why `isEdit` is false here
  // and why the matching component is deliberately not rendered.
  const workshop = useWorkshopSelection();
  const workshopId = workshop.workshopId;

  const [records, setRecords] = useState<SwitchableRecord[]>([]);
  const [state, setState] = useState<RecordListState>("pending");
  /**
   * WHICH workshop the rows in `records` belong to — not a boolean, for the reason
   * `recordPickers.useCraftAndArtisanOptions` gives about `artisansLoadedForCrafts`. Between a
   * workshop being chosen and its rows arriving there is a window in which `records` is populated
   * and describes the PREVIOUS workshop, and everything this control says about "this workshop" in
   * that window is said about the wrong one.
   */
  const [loadedForWorkshop, setLoadedForWorkshop] = useState<string | null>(null);
  const [cut, setCut] = useState<ListCut | null>(null);
  /** What is in the dropdown's own filter box, mirrored out of it through `onSearch`. */
  const [query, setQuery] = useState("");
  /** The same term once it has stopped changing for {@link SEARCH_DEBOUNCE_MS}. */
  const [applied, setApplied] = useState("");

  /**
   * THE WORKSHOP'S OWN PAGE OF RECORDS.
   *
   * `setRecords([])` on the way in is not tidying up, it is the correctness line in this effect. The
   * rows are merged into by the search effect below, and merging is additive on purpose — but rows
   * belonging to workshop A must never be allowed to survive into workshop B's list, where they
   * would be offered as that workshop's records and would open under a filter that does not contain
   * them. A workshop change is the one moment the list is REPLACED rather than added to.
   */
  useEffect(() => {
    if (!workshopId) return;
    let cancelled = false;
    setState("pending");
    setRecords([]);
    setLoadedForWorkshop(null);
    setCut(null);
    listResource<SwitchableRecord>(endpoint, { workshopId, pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setRecords(result.items);
        // The envelope, not just the items. `total` is what says whether typing needs to reach the
        // server at all, and it is the number the sentence under the control prints.
        setCut(recordListCut(kind, result.items.length, result.total));
        setLoadedForWorkshop(workshopId);
        setState("loaded");
      })
      .catch(() => {
        // Offline, a dead tunnel, a transient 5xx. The control says so and says the record already
        // open is unaffected; it must NOT fall through to "no records in this workshop", which is a
        // statement about the repository that a failed request is no evidence for.
        if (!cancelled) setState("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint, kind, workshopId]);

  /**
   * THE DEBOUNCE — and note what it is guarding, which is not what a debounce usually guards.
   *
   * `shouldSearchServer` is asked FIRST, so for the ordinary case — a workshop whose records fit
   * inside one page — no timer is ever armed and no request is ever made, however fast anybody
   * types. The dropdown's own local filter is the complete answer there, it is instant, and it is
   * the half of this control that keeps working with no signal at all.
   *
   * Only when the list is genuinely cut does typing start costing requests, and then it costs one
   * per 350ms of quiet rather than one per keystroke. Clearing the box drops `applied` back to
   * empty, which parks the search effect below rather than firing an unfiltered re-request: the
   * workshop's own page is already in state and has not gone anywhere.
   */
  useEffect(() => {
    if (!shouldSearchServer(cut, query)) {
      setApplied("");
      return;
    }
    const timer = setTimeout(() => setApplied(query.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [cut, query]);

  /**
   * THE ROWS PAST THE CUT, fetched by name.
   *
   * MERGED, NEVER SUBSTITUTED, which is what makes this safe to do under a control that is also
   * filtering locally. The array grows to the union of "this workshop's first page" and "everything
   * matching what has been typed so far"; the dropdown then narrows that union by the same query,
   * so a row that no longer matches is hidden by the filter rather than torn out of the options
   * under the reader's finger. It also means the record currently open cannot vanish from its own
   * picker because somebody typed — `mergeById`'s whole argument, one control along.
   *
   * A failure is silent ON PURPOSE. The local matches are still on screen and still correct; the
   * only thing that has not happened is a widening. An error banner here would tell a researcher
   * with no signal that something is broken, when what is actually true is that they have every row
   * this device holds.
   */
  useEffect(() => {
    if (!workshopId || !applied) return;
    let cancelled = false;
    listResource<SwitchableRecord>(endpoint, { workshopId, search: applied, pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (!cancelled) setRecords((previous) => mergeById(previous, result.items));
      })
      .catch(() => {
        // See above: nothing to report and nothing lost.
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint, workshopId, applied]);

  const workshopOptions = useMemo(
    () => workshop.workshops.map((row) => ({ value: row.id, label: workshopScopeLabel(row) })),
    [workshop.workshops]
  );

  const recordOptions = useMemo(
    () => records.map((record) => ({ value: record.id, label: recordOptionLabel(kind, record) })),
    [kind, records]
  );

  /**
   * IS THE RECORD ON SCREEN ONE OF THE OPTIONS?
   *
   * The dropdown's value is DERIVED from this rather than stored, and that is what makes the control
   * self-correcting. Picking a record navigates; it sets no local state at all. So if the navigation
   * is refused — by the unsaved-changes interceptor, by a guard on the destination, by the user
   * pressing Back — the dropdown goes on showing the record that is actually open, because that is
   * the only thing it was ever showing. A stored selection would have drifted away from the page
   * beneath it with nothing to pull it back.
   */
  const showsCurrentRecord = recordOptions.some((option) => option.value === currentId);

  const message = recordListMessage({
    kind,
    state,
    loadedForWorkshop,
    workshopId,
    shown: recordOptions.length
  });

  /**
   * OPEN THE PICKED RECORD — and the one thing that must happen before the navigation does.
   *
   * A Next.js `router.push` is a CLIENT navigation. `beforeunload` does not fire for one, so the
   * handler every record form installs against closing the tab is no protection here at all: without
   * the line below, picking a second record out of this dropdown would throw away whatever had been
   * typed into the first one, instantly, with nothing asked and nothing said. That is the single
   * worst thing this control could do, and it is exactly what the obvious implementation does.
   *
   * `intercept()` is the mechanism the page's own back control already uses: a dirty form raises its
   * `UnsavedChangesDialog` and answers true, and this returns without navigating. The researcher then
   * saves, discards, or keeps editing.
   *
   * WHERE THAT LEAVES THEM AFTERWARDS IS WORTH WRITING DOWN, because it is imperfect. The forms that
   * register `useLeaveGuard` wire their Discard to `router.back()` — that is the contract they were
   * written to, when the only caller was a back button — so discarding lands on wherever Back points
   * rather than on the record that was just picked, and they have to pick it again. Nothing is lost
   * and nothing is saved by surprise; it costs one extra interaction in the one case where there was
   * unsaved work. Carrying the destination THROUGH the prompt would fix it and means changing a
   * context four forms implement, which is not this control's change to make. A page that already
   * parks navigation properly hands us `onNavigate` and gets the better behaviour — `/crafts` does.
   */
  const open = useCallback(
    (nextId: string) => {
      if (!nextId || nextId === currentId) return;
      const href = recordEditHref(kind, nextId);
      if (onNavigate) {
        onNavigate(href);
        return;
      }
      if (intercept()) return;
      router.push(href);
    },
    [currentId, intercept, kind, onNavigate, router]
  );

  const singular = recordSingular(kind);
  const noWorkshops = !workshop.loading && workshop.workshops.length === 0;

  /**
   * "The one this record is filed under" — the escape hatch for the default.
   *
   * The primary dropdown defaults to the most recent workshop the account may submit to, which is
   * the right default for a researcher who has just come off a day in the field and is the behaviour
   * asked for. It is however NOT usually the workshop of the record already open, and without a way
   * back to that one the control would answer "which other record can I edit" while quietly refusing
   * to answer "which of this record's siblings". One button, only when there is somewhere to go.
   */
  const canJumpToRecordWorkshop = Boolean(
    currentWorkshopId && currentWorkshopId !== workshopId && workshopOptions.some((o) => o.value === currentWorkshopId)
  );

  /**
   * The two controls are wrapped in plain `<div>`s carrying a `field-label` span, which is the shape
   * `WorkshopScopeSelect` uses — NOT `FormControls.Field`, and the difference is not cosmetic. That
   * helper renders a `<label>` around its children, so the "this record's workshop" button below
   * would sit inside a label and a click on it would also activate the control the label names. A
   * filter row is not a form field and borrowing the form field's wrapper here would buy one line of
   * markup and a control that does two things per click.
   */
  return (
    <section
      className={className ?? "panel grid gap-3 p-4 md:grid-cols-2"}
      aria-label={`Open a different ${singular} for editing`}
    >
      <div className="flex items-center gap-2 text-sm font-medium text-ink-900 md:col-span-2">
        <PencilLine className="h-4 w-4 text-purple-700" aria-hidden />
        <span>{recordSwitcherTitle(kind)}</span>
      </div>

      <div className="grid min-w-0 content-start gap-1.5">
        <span className="field-label inline-flex items-center gap-1.5">
          <CalendarRange className="h-3.5 w-3.5" aria-hidden />
          Workshop
        </span>
        {/*
          `advanceOnSelect={false}` — this dropdown FILTERS the control beside it rather than filling
          in a form field, and `Dropdown`'s own prop documentation names that as the case for turning
          it off: throwing focus to the next field after adjusting a filter moves the reader away from
          the thing they are adjusting.
        */}
        <Dropdown
          value={workshopId}
          onChange={workshop.setWorkshopId}
          options={workshopOptions}
          ariaLabel="Workshop to list records from"
          placeholder={workshop.loading ? "Loading workshops…" : "Select a workshop"}
          advanceOnSelect={false}
        />
        {noWorkshops ? (
          <p className="text-xs leading-5 text-ink-500">
            No workshops are recorded yet, so there is nothing to list records from.
          </p>
        ) : null}
        {canJumpToRecordWorkshop ? (
          <button
            type="button"
            onClick={() => workshop.setWorkshopId(currentWorkshopId as string)}
            className="justify-self-start text-[11px] font-semibold text-purple-700 hover:underline"
          >
            Show this {singular}&apos;s own workshop
          </button>
        ) : null}
      </div>

      <div className="grid min-w-0 content-start gap-1.5">
        <span className="field-label">{`${singular[0].toUpperCase()}${singular.slice(1)}`}</span>
        {/*
          `ComboBox` rather than `Dropdown`: it forces the search box on regardless of how few rows a
          deployment happens to hold today, which is what its own docstring says it is for. A picker
          that is searchable on the laptop with 40 records and silently not searchable on the handset
          with 6 is two controls wearing one name.
        */}
        <ComboBox
          value={showsCurrentRecord ? currentId : ""}
          onChange={open}
          onSearch={setQuery}
          options={recordOptions}
          ariaLabel={`${singular} to open for editing`}
          placeholder={`Search ${singular} records…`}
          emptyLabel={`No ${singular} records in this workshop`}
          advanceOnSelect={false}
          disabled={!workshopId || state === "unavailable"}
        />
        {message ? <p className="text-xs leading-5 text-ink-500">{message}</p> : null}
        {/*
          `reach="search"` is only honest because of the effect above: the term genuinely goes to the
          server when — and only when — this cut is non-null, which is the same condition that makes
          this notice render at all. Do not copy this prop onto a picker that filters locally.
        */}
        <CappedListNotice cuts={[cut]} reach="search" />
      </div>
    </section>
  );
}
