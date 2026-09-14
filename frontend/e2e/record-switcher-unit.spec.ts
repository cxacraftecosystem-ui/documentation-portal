import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { cappedListNotice, type ListCut } from "@/components/data/cappedList";
import {
  EDIT_QUERY_PARAM,
  isInlineEditSurface,
  LABEL_SEPARATOR,
  RECORD_KINDS,
  recordEditHref,
  recordListCut,
  recordListMessage,
  recordNoun,
  recordOptionLabel,
  recordPlural,
  recordSingular,
  recordsAreForWorkshop,
  recordSwitcherTitle,
  SEARCH_DEBOUNCE_MS,
  shouldSearchServer,
  type RecordKind,
  type SwitchableRecord
} from "@/components/forms/RecordSwitcher";

/**
 * THE TWO-LEVEL RECORD PICKER'S RULES, AND THE HANDSET'S COPY OF THEM.
 *
 * ── WHY THIS SPEC NEVER OPENS A BROWSER ────────────────────────────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies — Playwright is the whole of it —
 * so mounting `<RecordSwitcher>` is not available, which is why its decisions are pure functions and
 * why this is a Playwright spec with no `page` in it. `record-pickers-unit.spec.ts` is the same
 * shape and the same argument, one dropdown over.
 *
 * It is also the only way three of these states get exercised at all. A cut list needs a workshop
 * holding more than a hundred records of one type; the "cannot say yet" arm needs a request in
 * flight at the instant somebody is reading; the "could not load" arm needs the network to be down,
 * which is the state this product exists for and the state nobody has while they are testing it.
 * Nobody arranges any of those before a release, and all three fail SILENTLY — a picker that says
 * the wrong thing looks exactly like one that says the right thing.
 *
 * ── AND WHY HALF OF IT READS KOTLIN OFF DISK ───────────────────────────────────────────────────
 *
 * "The browser and the handset offer one control" is a claim about TWO clients, and one requirement
 * implemented twice agrees on the day it is written and drifts on the next edit — which nobody
 * notices, because nobody re-reads a `.kt` while editing TypeScript. This feature starts from
 * evidence that it happens here: `RecordPickerScreen` in `MainActivity.kt` and the web's pickers
 * were already spelling the same five option labels by hand, identically, with nothing anywhere
 * asserting it.
 *
 * Every assertion below has a Kotlin twin in
 * `android/app/src/test/java/com/fieldrepository/app/ui/RecordSwitcherTest.kt`. If you change a rule
 * here and that suite still passes unchanged, you have just created the defect this product family
 * repeats most often.
 */

/**
 * Read a source file, NORMALISING LINE ENDINGS.
 *
 * `core.autocrlf` is true on the machines this is developed on, so the working tree is CRLF while
 * the repository stores LF — which means a literal in this file containing a newline matches on one
 * checkout and not on another, and a passing test becomes a failing one with no source change at
 * all. `record-parity-fields-unit.spec.ts` answers it at the read for the same reason, and the
 * Kotlin side's `RepoSources.kt` does too. Nothing here asserts anything ABOUT line endings.
 */
const read = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const KOTLIN_SWITCHER = ["android", "app", "src", "main", "java", "com", "fieldrepository", "app", "ui", "RecordSwitcher.kt"];
const KOTLIN_MAIN = ["android", "app", "src", "main", "java", "com", "fieldrepository", "app", "MainActivity.kt"];
const KOTLIN_PICKERS = ["android", "app", "src", "main", "java", "com", "fieldrepository", "app", "ui", "RecordPickers.kt"];

/** Only the three fields any one label reads are meaningful; the cast keeps the fixture to the point. */
function row(fields: Partial<SwitchableRecord>): SwitchableRecord {
  return { id: "r1", ...fields } as SwitchableRecord;
}

test.describe("the option labels", () => {
  test("an artisan reads as name then place", () => {
    expect(recordOptionLabel("artisan", row({ name: "Ram Kumar", place: "Bagru" }))).toBe("Ram Kumar · Bagru");
  });

  /**
   * THE HAND-TYPED MIDDLE DOT THAT USED TO PRINT WITH NOTHING AFTER IT.
   *
   * Android's `RecordPickerScreen` built this label as `"${it.name} · ${it.place}"`, which on a row
   * with no place renders "Ram Kumar · " — a separator promising a second half that is not coming.
   * Two of its five templates had it and three had a `let` guard, so one list printed two shapes
   * depending on which record type it held. Both clients now go through one rule.
   */
  test("a missing second half takes the separator with it", () => {
    expect(recordOptionLabel("artisan", row({ name: "Ram Kumar", place: "" }))).toBe("Ram Kumar");
    expect(recordOptionLabel("artisan", row({ name: "Ram Kumar", place: "   " }))).toBe("Ram Kumar");
    expect(recordOptionLabel("artisan", row({ name: "Ram Kumar", place: null }))).toBe("Ram Kumar");
  });

  test("a craft reads as name then place, a process as name then parent product", () => {
    expect(recordOptionLabel("craft", row({ name: "Block printing" }))).toBe("Block printing");
    expect(recordOptionLabel("craft", row({ name: "Block printing", place: "Bagru" }))).toBe("Block printing · Bagru");
    expect(recordOptionLabel("process", row({ name: "Dyeing" }))).toBe("Dyeing");
    expect(recordOptionLabel("process", row({ name: "Dyeing", product: { productName: "Cotton scarf" } }))).toBe(
      "Dyeing · Cotton scarf"
    );
  });

  test("a product and a tool read as their own name then the artisan", () => {
    expect(recordOptionLabel("product", row({ productName: "Cotton scarf", artisanName: "Ram Kumar" }))).toBe(
      "Cotton scarf · Ram Kumar"
    );
    expect(recordOptionLabel("tool", row({ toolkitName: "Bamboo comb", artisanName: "Ram Kumar" }))).toBe(
      "Bamboo comb · Ram Kumar"
    );
  });

  /**
   * A row with nothing to say for itself is still a row and still has to be pickable — it is a real
   * record, and the researcher may well be opening it BECAUSE its name is empty. A blank option is
   * one the eye slides past and the pointer cannot aim at.
   */
  test("a record with no name at all is named by its type", () => {
    expect(recordOptionLabel("artisan", row({}))).toBe("Untitled artisan");
    expect(recordOptionLabel("product", row({}))).toBe("Untitled product");
    expect(recordOptionLabel("tool", row({ toolkitName: "  " }))).toBe("Untitled tool");
  });

  test("the separator is the spaced middle dot", () => {
    expect(LABEL_SEPARATOR).toBe(" · ");
  });
});

test.describe("where a picked record opens", () => {
  /**
   * THE WHOLE OF WHAT THIS CONTROL DOES, so it is the whole of what has to be right. Every
   * destination is a surface that already exists and is already guarded — a picker that built any
   * other URL would be a second, looser way into a record.
   */
  test("the three record types with routes of their own", () => {
    expect(recordEditHref("artisan", "abc")).toBe("/artisans/abc/edit");
    expect(recordEditHref("product", "abc")).toBe("/products/abc/edit");
    expect(recordEditHref("tool", "abc")).toBe("/tools/abc/edit");
  });

  /**
   * And the two that are edited INLINE on their list page, which is the pair easiest to get wrong:
   * they do not look like edit pages from the route table, and linking them at their bare route is
   * the exact defect `useEditDeepLink` was written after — "Edit this craft" and "New craft" landed
   * on the identical blank form, and filling it in created a SECOND record.
   */
  test("the two edited by an inline form carry the intent as a parameter", () => {
    expect(recordEditHref("craft", "abc")).toBe("/crafts?edit=abc");
    expect(recordEditHref("process", "abc")).toBe("/processes?edit=abc");
    expect(isInlineEditSurface("craft")).toBe(true);
    expect(isInlineEditSurface("process")).toBe(true);
    expect(isInlineEditSurface("artisan")).toBe(false);
  });

  test("an id is encoded, on both shapes", () => {
    expect(recordEditHref("craft", "a b&c")).toBe("/crafts?edit=a%20b%26c");
    expect(recordEditHref("tool", "a b&c")).toBe("/tools/a%20b%26c/edit");
  });

  /**
   * THE DUPLICATED LITERAL, PINNED.
   *
   * `EDIT_QUERY_PARAM` is a second spelling of `EDIT_PARAM` in `components/hooks/useEditDeepLink.ts`
   * rather than an import of it, because that module is a React hook: it pulls in `next/navigation`,
   * `apiFetch` and the reduced-motion provider, none of which survive being imported into the Node
   * process this spec runs in — and the value of these rules is that a test can reach them without a
   * browser. THIS assertion is the import. Without it the two could drift and the `?edit=` links
   * would silently stop being picked up, which looks exactly like the deep link "not working".
   */
  test("the parameter name is the one the deep-link hook actually reads", () => {
    const hook = read("frontend", "components", "hooks", "useEditDeepLink.ts");
    expect(hook).toContain(`export const EDIT_PARAM = "${EDIT_QUERY_PARAM}";`);
  });
});

test.describe("the sentence under the record dropdown", () => {
  const base = {
    kind: "artisan" as RecordKind,
    state: "loaded" as const,
    loadedForWorkshop: "w1",
    workshopId: "w1",
    shown: 3
  };

  test("a settled list with rows in it says nothing", () => {
    expect(recordListMessage(base)).toBeNull();
  });

  /**
   * THE ARM THAT MATTERS MOST. "No artisans in this workshop yet" is a claim about the repository,
   * and the researcher's reasonable response to it is to go and file one — so it must never be
   * printed off rows that have not arrived. It is the same defect `recordPickers.ts` was written
   * after, one dropdown over, where "No artisans are linked to this craft yet" was printed off a
   * list that had simply not loaded.
   */
  test("an empty workshop is only announced once its rows have arrived", () => {
    expect(recordListMessage({ ...base, shown: 0 })).toBe(
      "No artisans in this workshop yet. Pick another workshop above, or file the first one."
    );
    expect(recordListMessage({ ...base, state: "pending", loadedForWorkshop: null, shown: 0 })).toBe(
      "Loading this workshop's records…"
    );
  });

  /**
   * THE FRAME IN WHICH THE ROWS BELONG TO THE PREVIOUS WORKSHOP.
   *
   * `state` is "loaded" and `shown` is non-zero, so every boolean a shorter implementation would
   * have reached for says "we have an answer" — and the answer is about w1 while the dropdown now
   * reads w2. This is why the loaded-for workshop is carried as an id and not as a flag.
   */
  test("rows belonging to the workshop just left are not described as this one's", () => {
    expect(recordListMessage({ ...base, workshopId: "w2", shown: 12 })).toBe("Loading this workshop's records…");
    expect(recordsAreForWorkshop("w1", "w2")).toBe(false);
    expect(recordsAreForWorkshop(null, "w2")).toBe(false);
    expect(recordsAreForWorkshop("w2", "w2")).toBe(true);
  });

  /**
   * A FAILED REQUEST IS NOT EVIDENCE ABOUT A WORKSHOP, and the sentence says so as well as saying
   * the thing the reader actually wants to know at that moment: the commonest way to reach this arm
   * is a researcher in a courtyard with no signal, and the question in their head is whether their
   * work is still there.
   */
  test("a failed load is named as a failed load and reassures about the open record", () => {
    expect(recordListMessage({ ...base, state: "unavailable", loadedForWorkshop: null, shown: 0 })).toBe(
      "These artisans could not be loaded. The record open below is unaffected."
    );
    // The BARE plural, not "artisans in this workshop": the failure is not evidence about the
    // workshop, and attaching it to one would be the same overclaim in the other direction.
    expect(
      recordListMessage({ ...base, kind: "process", state: "unavailable", loadedForWorkshop: null, shown: 0 })
    ).toBe("These processes could not be loaded. The record open below is unaffected.");
  });

  test("the two nouns are kept apart for every kind", () => {
    expect(recordNoun("tool")).toBe("tools in this workshop");
    expect(recordPlural("tool")).toBe("tools");
    expect(recordSingular("tool")).toBe("tool");
  });

  test("the title names the record type", () => {
    expect(recordSwitcherTitle("artisan")).toBe("Open a different artisan");
    expect(recordSwitcherTitle("process")).toBe("Open a different process");
  });
});

test.describe("when a keystroke is allowed to cost a request", () => {
  /**
   * THE ORDINARY CASE COSTS NOTHING, and that is the whole design. One workshop's records fit inside
   * the 100-row page in practice; the dropdown's own filter is then the complete answer, it is
   * instant, and it works with no signal — which is the half that matters for this product.
   */
  test("a whole list never sends a keystroke anywhere", () => {
    expect(shouldSearchServer(null, "ram")).toBe(false);
  });

  test("a cut list searches the server, and a blank box is not a search", () => {
    const cut = recordListCut("artisan", 100, 240) as ListCut;
    expect(cut).not.toBeNull();
    expect(shouldSearchServer(cut, "ram")).toBe(true);
    expect(shouldSearchServer(cut, "")).toBe(false);
    expect(shouldSearchServer(cut, "   ")).toBe(false);
  });

  test("a complete workshop list is not a cut at all", () => {
    expect(recordListCut("artisan", 40, 40)).toBeNull();
    // `total` is a plain cast off the wire, so a deployment that has not shipped it must produce
    // silence rather than a cut of NaN.
    expect(recordListCut("artisan", 40, undefined as unknown as number)).toBeNull();
  });

  test("the debounce is the 350ms every list page already uses", () => {
    expect(SEARCH_DEBOUNCE_MS).toBe(350);
    // Not a constant defined and then not used: the timer the switcher arms must be THIS number.
    expect(read("frontend", "components", "forms", "RecordSwitcher.tsx")).toContain("SEARCH_DEBOUNCE_MS)");
  });

  /**
   * THE SENTENCE THAT IS ONLY HONEST BECAUSE THE REQUEST IS REAL.
   *
   * `cappedList.ts` refused to have a `"search"` arm when it was written, and said why at length:
   * writing "search to reach the rest" over a box that only filters what is already loaded would be
   * the same lie one layer down. The arm exists now because the switcher genuinely sends the term —
   * so this test and `shouldSearchServer` above are two halves of one claim, and neither is worth
   * anything without the other.
   */
  test("a searchable cut invites typing rather than warning about a limit", () => {
    const cut = recordListCut("artisan", 100, 240) as ListCut;
    expect(cappedListNotice(cut, "search")).toBe(
      "Showing 100 of 240 artisans in this workshop — type to search all 240."
    );
    expect(cappedListNotice(cut)).toBe(
      "Showing 100 of 240 artisans in this workshop — the other 140 are not on this list, and typing here searches only the 100 shown."
    );
  });
});

test.describe("the handset's copy of all of it", () => {
  const kotlin = () => read(...KOTLIN_SWITCHER);

  /**
   * WHY THE SENTENCES ARE CHECKED AS FRAGMENTS AND NOT AS WHOLE LITERALS.
   *
   * Both sides build these strings by interpolation and the two languages spell that differently —
   * `${recordPlural(kind)}` here, `${kind.plural}` there — so a whole literal can never match
   * character for character, and asserting that it does would mean asserting on interpolation syntax
   * instead of on the English. What IS comparable is every run of text outside the holes, and that
   * is what a researcher reads. The rules above already pin the assembled sentence on this side and
   * `RecordSwitcherTest` pins it on that one, so between the three the whole string is covered.
   */
  test("the handset prints the same four sentences", () => {
    const source = kotlin();
    for (const fragment of [
      " could not be loaded. The record open below is unaffected.",
      "Loading this workshop's records…",
      " yet. Pick another workshop above, or file the first one.",
      "Open a different "
    ]) {
      expect(source, `RecordSwitcher.kt no longer contains: ${fragment}`).toContain(fragment);
    }
  });

  /**
   * THE FIVE KINDS, IN ORDER, BUILT FROM `RECORD_KINDS`.
   *
   * Derived rather than typed out, so adding a sixth kind here fails until the handset declares it
   * too. Order is asserted along with membership: a set comparison would pass a reordering, and
   * these two lists are read positionally by the tests on both sides.
   */
  test("the handset declares the same record kinds in the same order", () => {
    const source = kotlin();
    const declared = RECORD_KINDS.map(
      (kind) => `${kind.toUpperCase()}("${recordSingular(kind)}", "${recordPlural(kind)}")`
    );
    // The enum body, in order, as Kotlin spells it — commas between and a semicolon after the last.
    expect(source).toContain(declared.join(",\n    ") + ";");
  });

  test("the handset agrees about the separator and the debounce", () => {
    const source = kotlin();
    expect(source).toContain(`const val LABEL_SEPARATOR = "${LABEL_SEPARATOR}"`);
    expect(source).toContain(`const val SEARCH_DEBOUNCE_MS = ${SEARCH_DEBOUNCE_MS}L`);
  });

  /**
   * The cut sentences live in `RecordPickers.kt` there, beside the other capped-list rules.
   *
   * THE FRAGMENTS STOP AT EVERY `+`. Kotlin's line length pushes two of these sentences across a
   * string concatenation, and a fragment spanning one matches nothing however identical the rendered
   * text is — the file holds `…which are not " +
    "searched by…`, not the sentence. That is the
   * same trap `record-form-dictation-unit.spec.ts` names beside its own `signatureBlock` and the
   * reason this file normalises line endings at the read: an assertion about wording must not
   * quietly become an assertion about formatting. The Kotlin suite asserts the ASSEMBLED strings, so
   * the joins are covered there.
   */
  test("the handset's capped-list sentences still match", () => {
    const source = read(...KOTLIN_PICKERS);
    for (const fragment of [
      " — type to search all ",
      " are not on this list, ",
      "typing here searches only the ",
      " — use the pager to reach the rest, which are not ",
      "searched by the box above.",
      " could be listed here — this is not an empty repository."
    ]) {
      expect(source, `RecordPickers.kt no longer contains: ${fragment}`).toContain(fragment);
    }
  });
});

/**
 * THE CONTROL IS ACTUALLY MOUNTED — on all five of this client's update surfaces, and on the
 * handset's edit screen.
 *
 * Everything above is about a rule, and a rule nothing draws is a rule that ships as nothing at all.
 * That is precisely the defect `record-parity-fields-unit.spec.ts` was written after, where six
 * columns landed in the database, the Pydantic schemas, `lib/types.ts` and Android's `ApiModels.kt`
 * and NOTHING RENDERED ANY OF THEM. It is also the kind of gap an edit reopens silently: no build,
 * type check or lint has an opinion about a page that is one component shorter than it was.
 */
test.describe("every update surface actually carries it", () => {
  /**
   * EVERY WEB SURFACE THAT UPDATES A RECORD — the whole list, and the reason it is a list.
   *
   * Three record types have a route of their own; crafts and processes are edited by an inline form
   * on their list page and reached by `?edit=<id>`. Naming all five here is what makes "on the
   * update page for EVERY record type" a checked claim rather than a sentence in a commit message —
   * and the two inline ones are the easy pair to forget, precisely because they do not look like
   * edit pages from the route table.
   */
  const SURFACES: Array<{ parts: string[]; kind: RecordKind }> = [
    { parts: ["frontend", "app", "(protected)", "artisans", "[id]", "edit", "page.tsx"], kind: "artisan" },
    { parts: ["frontend", "app", "(protected)", "products", "[id]", "edit", "page.tsx"], kind: "product" },
    { parts: ["frontend", "app", "(protected)", "tools", "[id]", "edit", "page.tsx"], kind: "tool" },
    { parts: ["frontend", "app", "(protected)", "crafts", "page.tsx"], kind: "craft" },
    { parts: ["frontend", "app", "(protected)", "processes", "page.tsx"], kind: "process" }
  ];

  for (const surface of SURFACES) {
    test(`${surface.kind} — its update page mounts the switcher for its own kind`, () => {
      const source = read(...surface.parts);
      expect(source).toContain("<RecordSwitcher");
      expect(source).toContain(`kind="${surface.kind}"`);
    });
  }

  test("the handset mounts it on its edit screen, at the destination every other route uses", () => {
    const main = read(...KOTLIN_MAIN);
    expect(main).toContain("RecordSwitcher(");
    expect(main).toContain("onOpenRecord = ");
    // A switcher that grew its own loading path would be a second, looser way in. It hands back an
    // id and the existing screen does the rest — see `RecordSwitcher.kt`'s header.
    expect(main).toContain("Screen.Edit(s.mode, picked)");
  });

  /**
   * AND THE PRIMITIVE THAT MAKES THE DEBOUNCE POSSIBLE IS STILL WIRED.
   *
   * Local filtering lives inside `SearchableSelect`, so without a way to observe its query the
   * switcher cannot know what to send and the cut sentence's promise ("type to search all 240")
   * becomes false with nothing failing. The prop is optional and every other call site leaves it
   * undefined, which is exactly why a tidy-up could remove it without any of them noticing.
   */
  test("the shared select still reports its filter box out", () => {
    const primitive = read("frontend", "components", "ui", "SearchableSelect.tsx");
    expect(primitive).toContain("onSearch?: (query: string) => void;");
    expect(primitive).toContain("searchRef.current?.(next);");
    expect(read("frontend", "components", "ui", "Dropdown.tsx")).toContain("onSearch={onSearch}");
    expect(read("frontend", "components", "forms", "RecordSwitcher.tsx")).toContain("onSearch={setQuery}");
  });
});
