import { expect, test } from "@playwright/test";

import { cappedListNotice, cutOf, mergeById, type ListCut } from "@/components/data/cappedList";
import { craftChangeClearsArtisan } from "@/components/forms/recordPickers";
import type { Artisan } from "@/lib/types";

/**
 * A CRAFT CORRECTION THAT DELETED AN ARTISAN LINK, and the ceiling that hid it.
 *
 * WHY THESE RULES ARE FUNCTIONS AND WHY THIS FILE HAS NO `page`. Reproducing the defect on screen
 * needs a repository holding more than 100 artisans AND a record old enough to sort off the first
 * page of `GET /artisans` (which orders `createdAt desc`). Nobody arranges that before a release,
 * and the failure is silent — a link disappears and the save returns 200 — so the decision was
 * lifted out of the two form components into `components/forms/recordPickers` where a test can
 * reach it. This repository has no React renderer in its devDependencies, which is why this is a
 * Playwright spec that never opens a browser rather than a component test; it is the same shape,
 * and the same argument, as the sibling repository's `discarded-work-unit.spec.ts`.
 *
 * ANDROID PARITY. Every assertion below has a Kotlin twin in
 * `android/app/src/test/java/com/fieldrepository/app/RecordPickersTest.kt`, against
 * `ui/RecordPickers.kt`. Two surfaces answering one question differently is this product family's
 * most repeated defect class; if you change a rule here and the Kotlin test still passes unchanged,
 * you have just created one.
 */

function artisan(id: string, craftId: string | null): Artisan {
  // Only the three fields the rule reads are meaningful; the cast keeps the fixture to the point
  // rather than inventing a plausible-looking whole artisan record that nothing asserts on.
  return { id, name: `Artisan ${id}`, place: "Place", status: "APPROVED", craftId } as unknown as Artisan;
}

test.describe("craftChangeClearsArtisan", () => {
  test("an artisan known to practise another craft is unlinked", () => {
    const artisans = [artisan("a1", "weaving")];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "a1", artisans })).toBe(true);
  });

  test("an artisan known to practise the chosen craft is kept", () => {
    const artisans = [artisan("a1", "pottery")];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "a1", artisans })).toBe(false);
  });

  /**
   * THE REGRESSION. This is the case the old
   * `!artisans.some((a) => a.id === artisanId && a.craftId === next)` got wrong: the artisan is
   * simply not on the loaded page, which says nothing whatever about their craft. The old
   * expression read that silence as "wrong craft" and blanked the field, and because `artisanId` is
   * in the backend's CLEARABLE_KEYS and both forms submit `artisanId: artisanId || null`, the blank
   * was written through as a real unlink. Opening a record to fix its CRAFT destroyed its ARTISAN.
   */
  test("an artisan the picker cannot see keeps their link", () => {
    const pageOne = [artisan("someone-else", "pottery")];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "off-page", artisans: pageOne })).toBe(false);
    // And when the new craft is one no loaded artisan practises — the shape a 100-row page produces
    // most often — the answer must still be "keep".
    expect(craftChangeClearsArtisan({ nextCraftId: "blockprinting", artisanId: "off-page", artisans: pageOne })).toBe(false);
  });

  test("an artisan on the page with no craft recorded is a known difference", () => {
    const artisans = [artisan("a1", null)];
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "a1", artisans })).toBe(true);
  });

  test("unlinking the craft never touches the artisan", () => {
    const artisans = [artisan("a1", "weaving")];
    // "Unlinked / type below" is a blank craft id. Clearing the artisan too would destroy a second
    // link the researcher never touched.
    expect(craftChangeClearsArtisan({ nextCraftId: "", artisanId: "a1", artisans })).toBe(false);
    expect(craftChangeClearsArtisan({ nextCraftId: "pottery", artisanId: "", artisans })).toBe(false);
  });
});

test.describe("the sentence under a capped list", () => {
  test("a complete list says nothing", () => {
    expect(cutOf(42, 42, "artisans")).toBeNull();
    expect(cappedListNotice(null)).toBe("");
  });

  /**
   * `total` is a plain cast off the wire — `apiFetch` does not validate a schema — so a deployment
   * that has not shipped the field yet sends `undefined`, and the screen must say NOTHING rather
   * than print a cut of `NaN`.
   */
  test("a missing total is silence, not NaN", () => {
    expect(cutOf(10, undefined as unknown as number, "artisans")).toBeNull();
  });

  test("a cut list prints both numbers and admits the search is local", () => {
    const cut = cutOf(100, 749, "artisans") as ListCut;
    expect(cappedListNotice(cut)).toBe(
      "Showing 100 of 749 artisans — the other 649 are not on this list, and typing here searches only the 100 shown."
    );
    expect(cappedListNotice(cut, "pager")).toBe(
      "Showing 100 of 749 artisans — use the pager to reach the rest, which are not searched by the box above."
    );
  });

  /**
   * The arm no live database produces from page one, and the reason this is a pure function rather
   * than a ternary in JSX: nothing renders, so nobody can screenshot it, so this test is the only
   * place the wording is ever checked. It must not tell the reader to search or to page — neither
   * would help — and it must deny the reading the empty control invites.
   */
  test("nothing loaded over a non-empty repository gets its own words", () => {
    const cut = cutOf(0, 749, "artisans") as ListCut;
    expect(cappedListNotice(cut)).toBe(
      "None of the 749 artisans could be listed here — this is not an empty repository."
    );
  });
});

test.describe("mergeById", () => {
  test("adds rows and never removes one", () => {
    const page = [artisan("a1", "weaving"), artisan("a2", "pottery")];
    const roster = [artisan("a2", "pottery"), artisan("a3", "pottery")];
    expect(mergeById(page, roster).map((row) => row.id)).toEqual(["a1", "a2", "a3"]);
  });

  test("a narrower answer never shortens the options", () => {
    // The craft-scoped roster arriving must not make an edit form forget the artisan it is editing.
    const page = [artisan("editing-this-one", "weaving")];
    expect(mergeById(page, [])).toBe(page);
    expect(mergeById(page, [artisan("other", "pottery")]).some((row) => row.id === "editing-this-one")).toBe(true);
  });

  test("first writer wins on a duplicate id", () => {
    const first = artisan("a1", "weaving");
    const second = { ...artisan("a1", "pottery"), name: "Renamed" };
    const merged = mergeById([first], [second]);
    expect(merged).toHaveLength(1);
    // The row already on screen is not swapped for a differently-shaped one mid-interaction.
    expect(merged[0].name).toBe("Artisan a1");
  });
});
