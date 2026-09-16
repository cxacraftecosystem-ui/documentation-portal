import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { cutOf, type ListCut } from "@/components/data/cappedList";
import {
  ARTISAN_PAGE_BUDGET,
  artisanPickerOptionLabel,
  artisanPickerOptions,
  artisanScopeNoun,
  artisanSetKey,
  artisansNotAtWorkshop,
  outOfWorkshopNotice,
  primaryInterviewArtisanId,
  workshopArtisanParams,
  workshopScopeSettling
} from "@/components/questionnaires/interviewArtisans";
import type { Artisan, Craft } from "@/lib/types";

/**
 * "WHY ARE ARTISANS FROM OTHER WORKSHOPS SHOWING UP?" — the defect, pinned.
 *
 * ── WHAT WAS ON SCREEN, AND WHY NOTHING CAUGHT IT ────────────────────────────────────────────────
 *
 * The capture form at `/questionnaire` loaded its artisans exactly once, at mount, inside
 * `loadMeta()`:
 *
 *     listResource<Artisan>("/artisans", { pageSize: 100 })
 *
 * No workshop parameter of any kind, and never asked again when the workshop moved. The answer fed
 * BOTH artisan controls — a single-select "Primary artisan" and a multi-select "Additional
 * artisans" — so a researcher who had named a workshop in the box above was offered every artisan in
 * the deployment in the two boxes below, twice, out of a hundred-row window on a list ordered
 * `createdAt desc`.
 *
 * ── EVERY ASSERTION BELOW THAT WOULD HAVE FAILED AGAINST 0.0.5, NAMED ────────────────────────────
 *
 * A test that passes before the change proves nothing, so this is spelled out rather than implied:
 *
 *  1. **"an artisan of another workshop is not offered once a workshop is chosen"** — THE
 *     REGRESSION. Against 0.0.5 the options array was the mount load's `.items` verbatim, mapped
 *     one-for-one into `<option>`s (and, for the multi-select, the same array minus the primary
 *     pick). Every artisan in the deployment was in it, at every workshop, by construction — so this
 *     assertion was false for every workshop with a neighbour. It is the owner's report, written as
 *     an expectation.
 *  2. **"the request carries the workshop"** — the request built by `workshopArtisanParams` would
 *     have failed against a page whose only artisan request was `{ pageSize: 100 }`. The companion
 *     assertion, that the SINGULAR `workshopId` is not sent beside the plural, would also have
 *     failed the obvious "fix": `list_artisans` ANDs its filters and the singular is the NARROWER of
 *     the two, so sending both silently drops everybody linked to the workshop only by an interview
 *     — the group a questionnaire form most wants — and breaks parity with Android, which sends the
 *     plural.
 *  3. **"the roster REPLACES, it does not merge"** — the trap the obvious fix falls into. Adding a
 *     scoped request whose answer is `mergeById`-ed into the existing array (which is exactly what
 *     `components/forms/recordPickers` does, correctly, for a different problem) leaves the previous
 *     workshop's artisans on offer at the next one. The defect survives the fix and looks fixed.
 *  4. **"the list is not cut silently"** — 0.0.5 threw `total` away on this call. `cutOf` over a
 *     budgeted read is what puts the sentence on screen.
 *  5. **§7, read off the page source** — the only assertions here that can see the defect in its
 *     original shape rather than the rule that replaced it, and the ones that will go red again if
 *     somebody reinstates it. Every one of them was checked against `git show HEAD~:…page.tsx` and
 *     every one of them failed there: no scoped artisan request, a `setAdditionalArtisanIds` state,
 *     a `name="primaryArtisanId"` control, no `selectedArtisanIds`, and an interview list with no
 *     workshop on the wire.
 *
 * ── WHY THIS FILE HAS NO `page` ──────────────────────────────────────────────────────────────────
 *
 * Reproducing any of it in a browser needs two workshops, a roster in each, and enough artisans to
 * cross a hundred-row boundary — nobody arranges that before a release, and the failure is silent:
 * the form saves an interview at workshop B naming workshop A's people and answers 201. So the
 * rulings live in `components/questionnaires/interviewArtisans.ts` where a test can stand in front
 * of them. This repository has no React renderer in its devDependencies, which is why this is a
 * Playwright spec that never opens a browser rather than a component test — the same shape, and the
 * same argument, as `e2e/record-pickers-unit.spec.ts` and `e2e/questionnaire-dictation-unit.spec.ts`.
 *
 * ── ANDROID PARITY ───────────────────────────────────────────────────────────────────────────────
 *
 * Every rule asserted here is a contract BOTH clients owe; the module header lists it in five lines.
 * The Kotlin side of it is `repository.artisans(workshopIds = …)` held on a settled scope
 * (`ui/ConsolidatedQuestionnaireScreen.kt` is the in-repo pattern) and the interview list scoped by
 * the same workshop. If you change a rule in this file and nothing in
 * `android/app/src/test/java/com/fieldrepository/app/` changes with it, you have just built the
 * disagreement between the two clients that this whole round of work exists to end.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const PAGE = read("app", "(protected)", "questionnaire", "page.tsx");

/**
 * The file with its comments removed — the same helper, character for character, as
 * `e2e/questionnaire-dictation-unit.spec.ts` and `e2e/questionnaire-workbook-unit.spec.ts` use on
 * this same page, and copied rather than shared for the reason those two give: a spec's fixtures
 * are part of what it asserts, and a shared one moves under it.
 *
 * IT IS NOT A TIDINESS MEASURE HERE, it is what makes §7 mean anything. This codebase records what a
 * thing REPLACED and why, so the page's own comments necessarily spell out `additionalArtisanIds`,
 * `primaryArtisanId` and `/artisans` — the exact strings §7 forbids. Matched against the raw file
 * those assertions would fail on the explanation of the fix rather than on the defect, and the
 * cheapest way to make a red test green is to delete the paragraph that explains the code.
 */
function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

const CODE = codeOnly(PAGE);

/**
 * Only the fields the rules actually read are meaningful; the cast keeps each fixture to the point
 * rather than inventing a plausible-looking whole artisan record that nothing asserts on. Same
 * device as `e2e/record-pickers-unit.spec.ts`.
 */
function artisan(id: string, name: string, craftName: string | null = "Weaving", place = "Bhuj"): Artisan {
  return {
    id,
    name,
    place,
    status: "APPROVED",
    craftId: craftName ? craftName.toLowerCase() : null,
    craft: craftName ? ({ id: craftName.toLowerCase(), name: craftName } as Craft) : null
  } as unknown as Artisan;
}

/** The three artisans this file argues about: two at the workshop on screen, one somewhere else. */
const RAMESH = artisan("a-ramesh", "Ramesh Kumar");
const SITA = artisan("a-sita", "Sita Devi", "Block printing", "Ajrakhpur");
/** Documented at ANOTHER workshop entirely. The server never returns them under this scope. */
const OUTSIDER = artisan("a-outsider", "Hiren Patel", "Pottery", "Khavda");

/* ============================================================================================== */
/* 1. THE REGRESSION                                                                              */
/* ============================================================================================== */

test.describe("the artisan picker under a chosen workshop", () => {
  /**
   * THE OWNER'S FIRST REPORT, AS AN EXPECTATION.
   *
   * Against 0.0.5 the options were the mount load's rows verbatim — the whole deployment — so
   * `OUTSIDER` was offered at every workshop. The fix is that the OFFER is the server's scoped
   * answer and nothing else: `known` holds every row the page has ever loaded, including the
   * repository-wide reachability probe that `carryScope` needs, and not one of those rows reaches
   * the picker unless the scoped roster names it or the researcher has already ticked it.
   */
  test("an artisan of another workshop is not offered", () => {
    const options = artisanPickerOptions({
      scoped: [RAMESH, SITA],
      // The outsider IS loaded — this is the state the page is really in, and the state the naive
      // fix gets wrong. They are on the repository-wide probe's page, sitting in memory.
      known: [RAMESH, SITA, OUTSIDER],
      selectedIds: []
    });
    expect(options.map((option) => option.value)).toEqual(["a-ramesh", "a-sita"]);
    expect(options.some((option) => option.label.includes("Hiren Patel"))).toBe(false);
  });

  /**
   * THE TRAP IN THE OBVIOUS FIX, stated as its own case because it passes every OTHER assertion in
   * this file.
   *
   * `mergeById` is the right rule in `components/forms/recordPickers`, where three requests describe
   * one world and a narrower answer must not look like a shorter one. Reached for here it would
   * carry the previous workshop's roster forward into the next workshop's picker — the reported
   * defect, reintroduced by a helper written to prevent a different one, and invisible until
   * somebody switches workshops twice.
   */
  test("the previous workshop's roster does not survive into the next workshop's options", () => {
    const atWorkshopOne = artisanPickerOptions({ scoped: [RAMESH, SITA], known: [RAMESH, SITA], selectedIds: [] });
    expect(atWorkshopOne.map((option) => option.value)).toEqual(["a-ramesh", "a-sita"]);
    // The workshop changes; the server answers with that workshop's roster. `known` has grown,
    // because it must (labels), and the offer has NOT.
    const atWorkshopTwo = artisanPickerOptions({
      scoped: [OUTSIDER],
      known: [RAMESH, SITA, OUTSIDER],
      selectedIds: []
    });
    expect(atWorkshopTwo.map((option) => option.value)).toEqual(["a-outsider"]);
  });

  /**
   * The counterweight, and the reason the offer is not simply `scoped`.
   *
   * `SearchableMultiSelect` draws its trigger from the labels of the options that match its values,
   * so a ticked id with no option makes the control read "Nothing selected" over a selection that is
   * about to be submitted. That happens for real: `/questionnaire?artisanId=…` is how the artisan
   * page hands an interview off, and a carried context can name somebody outside this workshop.
   * Drawing a row that is ALREADY TICKED is the opposite of offering an unticked one.
   */
  test("an artisan already ticked is still drawn, even when the roster does not hold them", () => {
    const options = artisanPickerOptions({
      scoped: [RAMESH, SITA],
      known: [RAMESH, SITA, OUTSIDER],
      selectedIds: ["a-outsider"]
    });
    expect(options.map((option) => option.value)).toEqual(["a-ramesh", "a-sita", "a-outsider"]);
    // …and the row carries a NAME rather than a blank chip, which is the whole point of drawing it.
    expect(options.at(-1)?.label).toContain("Hiren Patel");
  });

  /**
   * ORDER IS PART OF PARITY. `GET /artisans` answers `createdAt desc` and Android renders that order
   * untouched, so the web must not sort. A spec is the only thing that will catch an "improvement"
   * here: an alphabetical sort looks tidier on one screen and makes the two clients show the same
   * people in two different orders, which nobody files a bug about and everybody notices.
   */
  test("options keep the server's order", () => {
    const serverOrder = [SITA, OUTSIDER, RAMESH];
    const options = artisanPickerOptions({ scoped: serverOrder, known: serverOrder, selectedIds: [] });
    expect(options.map((option) => option.value)).toEqual(["a-sita", "a-outsider", "a-ramesh"]);
  });

  test("the label reads as a person and never as an id", () => {
    expect(artisanPickerOptionLabel(SITA)).toBe("Sita Devi - Block printing - Ajrakhpur");
    // An artisan with no craft recorded still reads as a row, not as a gap. `e2e/dropdown-option-
    // labels.spec.ts` asserts the same shape against a live panel.
    expect(artisanPickerOptionLabel(artisan("a-x", "Nameless Craft", null, "Nirona"))).toBe(
      "Nameless Craft - No craft - Nirona"
    );
  });
});

/* ============================================================================================== */
/* 2. WHAT GOES ON THE WIRE                                                                       */
/* ============================================================================================== */

test.describe("the artisan request", () => {
  /**
   * Against 0.0.5 the only artisan request this page made was `{ pageSize: 100 }`. There was no
   * workshop on the wire at all, which is the defect at its source.
   */
  test("carries the chosen workshop as the PLURAL scope", () => {
    expect(workshopArtisanParams("w-3", 1)).toEqual({ workshopIds: "w-3", page: 1, pageSize: 100 });
  });

  /**
   * AND DOES NOT ALSO SEND THE SINGULAR, which is the mistake `components/forms/recordPickers` would
   * teach by example if this were not written down. There, `craftId` is sent beside `craftIds`
   * because the two are exactly equivalent for a one-element selection and the doubled request is
   * byte-identical against an older API. Here they are NOT equivalent: `workshopId` narrows on the
   * artisan's own column or the `WorkshopArtisan` roster, while `workshopIds` goes through
   * `artisan_workshop_clause` and also counts an artisan who SAT IN an interview taken at the
   * workshop. `list_artisans` ANDs everything it is given, so sending both intersects down to the
   * narrower answer and silently drops the third group — the very people a questionnaire form is
   * most likely to want, and the group Android's request includes.
   */
  test("does not send the singular workshopId beside it", () => {
    expect(Object.keys(workshopArtisanParams("w-3", 1))).not.toContain("workshopId");
  });

  /**
   * NO WORKSHOP SELECTED MEANS EVERY WORKSHOP — decided once, in `workshopArtisanParams`, and the
   * same on both clients (`repository.artisans(workshopIds = null)` sends nothing and gets
   * everybody).
   *
   * The alternative was an empty picker under "choose a workshop first", and it was rejected because
   * `submit` sends `workshopId: workshop.workshopId || null` against a nullable column: an interview
   * that belongs to no workshop is a record this product stores, and a picker that offered nobody
   * until a workshop was named would leave a researcher filing that interview under a workshop it
   * was not taken at — corrupting the very scoping this work establishes.
   */
  test("asks for everybody when no workshop is chosen", () => {
    expect(workshopArtisanParams("", 1)).toEqual({ workshopIds: undefined, page: 1, pageSize: 100 });
  });

  test("pages, and the page number is the only thing that moves", () => {
    expect(workshopArtisanParams("w-3", 4)).toEqual({ workshopIds: "w-3", page: 4, pageSize: 100 });
    // The budget is a bound on requests, not a bound anybody may quietly raise instead of shipping
    // the notice — see `components/data/cappedList`: moving a cut is not telling anyone where it is.
    expect(ARTISAN_PAGE_BUDGET).toBeGreaterThan(1);
  });
});

/* ============================================================================================== */
/* 3. HOLDING THE REQUEST UNTIL THE WORKSHOP HAS SETTLED                                          */
/* ============================================================================================== */

test.describe("workshopScopeSettling", () => {
  const workshops = [{ id: "w-1" }, { id: "w-2" }];

  test("while the workshop list is still loading", () => {
    expect(workshopScopeSettling({ loading: true, touched: false, workshopId: "", workshops })).toBe(true);
  });

  /**
   * THE WINDOW THE WHOLE GUARD EXISTS FOR. `useWorkshopSelection` finds the form's default workshop
   * asynchronously — it walks the most recent few asking `/workshops/{id}/submission-check` — and
   * until that walk lands `workshopId` is `""`, which is indistinguishable from "the researcher
   * chose no workshop". Firing here means one unscoped request, the whole repository painted into
   * the picker, and a scoped request replacing it a beat later under a workshop name that has just
   * appeared above: the reported screen, staged for a quarter of a second by the fix for it.
   */
  test("while the default probe is still walking", () => {
    expect(workshopScopeSettling({ loading: false, touched: false, workshopId: "", workshops })).toBe(true);
  });

  test("not once the probe has landed on a workshop", () => {
    expect(workshopScopeSettling({ loading: false, touched: false, workshopId: "w-1", workshops })).toBe(false);
  });

  test("not once the researcher has touched the control", () => {
    // "Not linked to a workshop", chosen deliberately: an empty id that must NOT be read as unsettled.
    expect(workshopScopeSettling({ loading: false, touched: true, workshopId: "", workshops })).toBe(false);
  });

  /**
   * The arm that would otherwise hang the picker forever. On a deployment with no workshops at all
   * the probe never runs and `workshopId` stays `""` for the whole session, so a guard that only
   * asked "is the workshop empty" would hold the artisan list hostage and the form would offer
   * nobody, with nothing on screen to explain it.
   */
  test("not on a deployment with no workshops at all", () => {
    expect(workshopScopeSettling({ loading: false, touched: false, workshopId: "", workshops: [] })).toBe(false);
  });
});

/* ============================================================================================== */
/* 4. THE ONE ARTISAN ANYTHING SINGLE-VALUED USES                                                 */
/* ============================================================================================== */

test.describe("primaryInterviewArtisanId", () => {
  /**
   * The RESP respondent block and the carry-forward bag each hold ONE artisan and the interview
   * holds a set, so something has to choose. It is element 0 — the same rule the server applies to a
   * tool (`data["artisanId"] = artisan_ids[0]`, `backend/app/api/routes/tools.py`) and the same one
   * Android's tool sheet mirrors (`artisanIds.firstOrNull().orEmpty()`).
   */
  test("is the head of the selection", () => {
    expect(primaryInterviewArtisanId(["a-sita", "a-ramesh"])).toBe("a-sita");
  });

  /**
   * STABLE WHEN A SECOND PERSON JOINS, which is the property that matters: the RESP block is
   * prefilled from this artisan and the carry bag is banked with them, so a head that moved on every
   * tick would rewrite the respondent's name, craft, place, gender and contact under the
   * researcher's hands halfway through an interview.
   */
  test("does not move when another artisan is ticked", () => {
    expect(primaryInterviewArtisanId(["a-sita"])).toBe("a-sita");
    expect(primaryInterviewArtisanId(["a-sita", "a-ramesh"])).toBe("a-sita");
    expect(primaryInterviewArtisanId(["a-sita", "a-ramesh", "a-outsider"])).toBe("a-sita");
  });

  /**
   * It DOES move when the researcher removes the person at the head, and that is the documented rule
   * rather than a hole in it: they took that artisan off the record. The rejected alternative was a
   * sticky ref pinning the first artisan ever ticked, which survives its own artisan being unticked
   * — so the carry bag and the RESP block would go on naming somebody the interview no longer covers
   * — and which is the "primary" concept the owner asked us to delete, smuggled back in somewhere no
   * control shows it and nobody can correct it.
   */
  test("moves to the next when the head is untickd", () => {
    expect(primaryInterviewArtisanId(["a-ramesh"])).toBe("a-ramesh");
  });

  test("is empty when nobody is ticked, and skips a blank", () => {
    expect(primaryInterviewArtisanId([])).toBe("");
    expect(primaryInterviewArtisanId(["", "a-ramesh"])).toBe("a-ramesh");
  });
});

test.describe("artisanSetKey", () => {
  /**
   * "A shared entry already exists for this set of artisans" is keyed on THIS, and it is the only
   * thing stopping a second researcher from opening a duplicate sitting. It must not notice the
   * order the boxes were ticked in — the interview is stored once per exact SET.
   */
  test("two tick orders are one interview", () => {
    expect(artisanSetKey(["a-ramesh", "a-sita"])).toBe(artisanSetKey(["a-sita", "a-ramesh"]));
  });

  test("blanks and duplicates never make a different set", () => {
    expect(artisanSetKey(["a-ramesh", "", "a-ramesh"])).toBe("a-ramesh");
    expect(artisanSetKey([])).toBe("");
  });

  /**
   * And it is NOT the order the ids are sent in. Sorting the key and sorting the selection are
   * different acts: `primaryInterviewArtisanId` reads the selection's own head, so collapsing the
   * two into one sorted array would hand the RESP block and the carry bag whichever artisan happened
   * to sort first by cuid.
   */
  test("does not describe the order the ids are sent in", () => {
    const selection = ["b-second", "a-first"];
    expect(artisanSetKey(selection)).toBe("a-first,b-second");
    expect(primaryInterviewArtisanId(selection)).toBe("b-second");
  });
});

/* ============================================================================================== */
/* 5. WHAT A WORKSHOP CHANGE DOES TO A SELECTION ALREADY MADE                                     */
/* ============================================================================================== */

/**
 * THE RULE REVERSED IN THIS PASS, AND THE ASSERTIONS THAT PIN IT.
 *
 * Until now the web ran `artisansToKeepForWorkshop` through `useArtisanSelectionScope` and wrote the
 * survivors back into the form's state: a workshop change silently unticked anybody the new
 * workshop's complete roster did not hold. Android never did, and said so in a comment arguing the
 * opposite. One gesture, two clients, two different sets of `QuestionnaireInterviewArtisan` rows.
 *
 * The rule is now Android's on both clients — A TICK IS NEVER REMOVED BY A WORKSHOP CHANGE — and the
 * three-way ruling survives as a SENTENCE instead of a deletion. The tests below therefore assert
 * the same three arms, with the consequence inverted: where the old suite expected an id to be
 * dropped from the selection, this one expects it to be NAMED and kept.
 *
 * The argument is in `interviewArtisans.ts` rule 6: `artisan_workshop_clause` counts three links and
 * the third is having sat in an interview taken at the workshop, which is the link THIS FORM CREATES
 * — so a roster's silence about somebody is not evidence that ticking them was a mistake.
 */
test.describe("artisansNotAtWorkshop", () => {
  const OFFERED = ["a-ramesh", "a-sita"];

  /**
   * The case: the form opens on last week's workshop, the researcher ticks two people, then corrects
   * the workshop to the one they are standing in. The tick rides along — deliberately — and the
   * sentence under the picker is what stops it being a surprise when the record is read back.
   */
  test("an artisan the workshop's complete roster does not hold is named", () => {
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: OFFERED,
        loadedForWorkshop: "w-2",
        workshopId: "w-2",
        cut: null
      })
    ).toEqual(["a-outsider"]);
  });

  /**
   * ONLY WHAT IS KNOWN IS SAID — `craftChangeClearsArtisan`'s rule
   * (`components/forms/recordPickers`) applied to a workshop, for the identical reason: "absent from
   * the list" has three causes and only one of them is "this workshop does not know them". The
   * roster for the workshop now selected has not landed, so the page knows nothing and says nothing.
   *
   * THIS ARM ALSO COVERS THE FAILED REQUEST. `useWorkshopArtisans` leaves `loadedForWorkshop` null
   * when a scoped request fails, so a dropped packet can no more produce this sentence than it can
   * produce an untick.
   */
  test("nobody is named before this workshop's roster has landed", () => {
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: OFFERED,
        // Still the PREVIOUS workshop's answer — or, after a failure, none at all.
        loadedForWorkshop: "w-1",
        workshopId: "w-2",
        cut: null
      })
    ).toEqual([]);
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: [],
        loadedForWorkshop: null,
        workshopId: "w-2",
        cut: null
      })
    ).toEqual([]);
  });

  /**
   * AND NOBODY IS NAMED OFF A TRUNCATED ROSTER. An artisan absent from a list that stopped at the
   * page budget may be perfectly well linked to this workshop and simply past the cut; announcing
   * them as "not recorded at this workshop" off a pagination boundary is precisely the silent
   * failure `components/data/cappedList` exists to end, one layer down — and the capped-list
   * sentence is already on screen saying the list is short, so this one would be contradicting it.
   */
  test("nobody is named while the roster is cut", () => {
    const cut: ListCut = { noun: "artisans at this workshop", loaded: 500, total: 640 };
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: OFFERED,
        loadedForWorkshop: "w-2",
        workshopId: "w-2",
        cut
      })
    ).toEqual([]);
  });

  /**
   * THE NAMES KEEP THE RESEARCHER'S ORDER, so the sentence reads the same on the handset and in the
   * browser. It also demonstrates the half that matters most: the SELECTION is untouched, so
   * `primaryInterviewArtisanId` still reads the same head and the RESP block does not change person
   * as a side effect of a workshop correction. That was the old rule's worst symptom — the drop
   * moved the respondent silently.
   */
  test("the named ids keep the researcher's own order, and the selection is untouched", () => {
    const selected = ["a-sita", "a-outsider", "a-stranger", "a-ramesh"];
    expect(
      artisansNotAtWorkshop({
        selectedIds: selected,
        offeredIds: OFFERED,
        loadedForWorkshop: "w-2",
        workshopId: "w-2",
        cut: null
      })
    ).toEqual(["a-outsider", "a-stranger"]);
    expect(primaryInterviewArtisanId(selected)).toBe("a-sita");
  });

  /**
   * The unscoped state is ruled on by the same rule and needs no arm of its own: with no workshop
   * selected the roster IS every artisan, so absence really does mean the row is gone or no longer
   * visible — and if the unscoped read was itself cut, the arm above keeps everybody quiet.
   */
  test("with no workshop selected the same rule applies to the unscoped list", () => {
    expect(
      artisansNotAtWorkshop({
        selectedIds: ["a-ramesh", "a-outsider"],
        offeredIds: OFFERED,
        loadedForWorkshop: "",
        workshopId: "",
        cut: null
      })
    ).toEqual(["a-outsider"]);
  });
});

/**
 * THE SENTENCE ITSELF. `ui/RecordPickers.outOfWorkshopNotice` is the Kotlin twin and these strings
 * are the specification for it — the same reason `listCutNotice` has one.
 */
test.describe("outOfWorkshopNotice", () => {
  test("says nothing when there is nothing to say", () => {
    expect(outOfWorkshopNotice([])).toBe("");
    expect(outOfWorkshopNotice(["", "   "])).toBe("");
  });

  /** It NAMES people, because a count would make the reader go and look up who. */
  test("names one artisan, and does not read as an accusation", () => {
    const line = outOfWorkshopNotice(["Ramesh Kumar"]);
    expect(line).toContain("Ramesh Kumar is not recorded at this workshop yet");
    expect(line).toContain("They stay ticked");
    // The whole point of the rule: saving is what creates the link, so the sentence says so rather
    // than telling a researcher they have made a mistake they have not made.
    expect(line).toContain("saving this interview here is what links them to it");
  });

  test("names several, in the order given", () => {
    expect(outOfWorkshopNotice(["Sita Devi", "Ramesh Kumar"])).toContain("Sita Devi, Ramesh Kumar are");
  });

  /** A selection of thirty must not print a paragraph. */
  test("caps the list and counts the remainder", () => {
    const line = outOfWorkshopNotice(["A", "B", "C", "D", "E", "F"]);
    expect(line).toContain("A, B, C, D and 2 more are");
    expect(line).not.toContain("E");
  });
});

/* ============================================================================================== */
/* 6. SAYING WHAT THE LIST IS NOT SHOWING                                                         */
/* ============================================================================================== */

test.describe("the capped-list report", () => {
  /**
   * 0.0.5 asked for 100 rows and threw `total` away, so a deployment with more artisans than that
   * offered a truncated list of PEOPLE on a form whose whole purpose is linking an interview to the
   * right one — with nothing on screen to say a row was missing. The picker now pages up to
   * `ARTISAN_PAGE_BUDGET` and then reports whatever it still could not reach.
   */
  test("a roster inside the budget says nothing at all", () => {
    expect(cutOf(64, 64, artisanScopeNoun("w-2"))).toBeNull();
  });

  test("a roster past the budget is reported, in the scope's own words", () => {
    const cut = cutOf(ARTISAN_PAGE_BUDGET * 100, 640, artisanScopeNoun("w-2"));
    expect(cut).toEqual({ noun: "artisans at this workshop", loaded: 500, total: 640 });
  });

  /**
   * The noun names the SCOPE and not just the record type, because the two sentences answer
   * different questions: under a workshop the reader needs to know that this WORKSHOP holds more
   * people than are listed, not that the repository does.
   */
  test("the noun follows the scope", () => {
    expect(artisanScopeNoun("w-2")).toBe("artisans at this workshop");
    expect(artisanScopeNoun("")).toBe("artisans");
  });
});

/* ============================================================================================== */
/* 7. THE DEFECT IN ITS ORIGINAL SHAPE                                                            */
/* ============================================================================================== */

test.describe("the capture page itself", () => {
  /**
   * THE ONE ASSERTION HERE THAT CAN SEE THE OLD CODE. Everything above tests a rule that did not
   * exist in 0.0.5; this reads the page and refuses the line that WAS the defect — an unscoped,
   * unpaged artisan load sitting in `loadMeta`, where it also re-fired on every instrument switch
   * and every edit in the questionnaire builder.
   *
   * Read off the source rather than driven in a browser for the reason the file header gives, and
   * kept deliberately narrow: it matches the artisan endpoint with a `pageSize` and no `workshopIds`
   * beside it, so it fails on a reinstatement however the call is spelled, and does not fire on the
   * repository-wide reachability probe inside `useWorkshopArtisans`, which lives in another file and
   * is a different thing with a written reason.
   */
  test("no longer loads artisans without a workshop scope", () => {
    expect(CODE, "the page itself must not fetch /artisans at all any more").not.toMatch(/["'`]\/artisans/);
  });

  /** The two controls really are one, and the page holds ONE selection rather than a pair. */
  test("holds one artisan selection and not a primary/additional pair", () => {
    expect(CODE, "no second artisan state").not.toMatch(/additionalArtisanIds/);
    expect(CODE, "no single-select primary control").not.toMatch(/primaryArtisanId"/);
    expect(CODE).toMatch(/const \[selectedArtisanIds, setSelectedArtisanIds\]/);
    expect(CODE, "one multi-select, fed by that one selection").toMatch(
      /<MultiSelectDropdown\s+values=\{selectedArtisanIds\}/
    );
  });

  /**
   * THE GUARD'S OWN GUARD, the same one `e2e/questionnaire-workbook-unit.spec.ts` keeps over its own
   * `codeOnly`, and for the same reason: the three assertions above are unfalsifiable in the other
   * direction without it. They forbid strings the page's PROSE is required to contain — a comment
   * that says what a thing replaced has to name the thing — so if `codeOnly` ever stopped stripping
   * comments they would go red on correct code, and the cheapest way to make a red test green is to
   * delete the paragraph that explains the fix.
   */
  test("those assertions fail on the code and not on the comments explaining the code", () => {
    expect(PAGE, "the page still says what the pair was").toMatch(/additionalArtisanIds/);
    expect(PAGE, "and still says where the artisan load went").toMatch(/\/artisans/);
    expect(CODE, "but neither survives into the code").not.toMatch(/additionalArtisanIds|\/artisans/);
  });

  /**
   * THE INTERVIEW LIST IS SCOPED TOO — the web half of the owner's SECOND report, the Android one
   * where *"the questionnaire from the previous workshop are showing up even in the third
   * workshop"*. This list had the same hole: the funnel has offered a workshop filter since it was
   * written and the call quietly dropped it, under a comment claiming artisan was "the only list
   * param the interviews endpoint supports" — which was never true of `list_interviews`.
   *
   * The SINGULAR is right here and the plural is right on `/artisans`, and the asymmetry is
   * deliberate at both ends. `list_interviews` declares both, but for one real id they are the same
   * predicate, and this funnel is single-select with no "not linked to a workshop" option — so the
   * singular says everything there is to say and is the spelling every deployed API understands.
   * `/artisans` is the opposite case: there the plural is BROADER than the singular, and the two
   * cannot be substituted for one another. `components/questionnaires/interviewArtisans.ts` carries
   * that half.
   */
  test("scopes the interview list by the funnel's workshop", () => {
    expect(CODE).toMatch(/workshopId: funnel\.workshopId \|\| undefined/);
    expect(CODE, "and re-runs when that workshop changes").toMatch(/\[funnelReady, page, funnel\.workshopId,/);
  });
});
