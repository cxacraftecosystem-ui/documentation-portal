import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { EditedFlag } from "@/components/media/TranscriptBlock";

/**
 * THE RECORD-PARITY COLUMNS, ON THE WEB FORMS THAT HAVE TO FILL THEM.
 *
 * A previous change landed `Artisan.craftStartDate`, `Artisan.experienceMonths`,
 * `ToolDocumentation.heightInches`, `MediaFile.transcriptEditedAt`/`transcriptEditedById`,
 * `Workshop.workshopType` and `clientKey` on four tables — in the database, in the Pydantic schemas,
 * in `frontend/lib/types.ts` and in Android's `ApiModels.kt`. NOTHING RENDERED ANY OF THEM. A column
 * no client can write is a column that can only ever hold its default, so every one of them was a
 * migration that had changed nothing anybody could see.
 *
 * This spec is the half of that nobody can check by looking at a screen, and it is one section per
 * way the wiring can be silently wrong:
 *
 *   1. **A box that exists but posts nothing** — a control whose value never reaches the payload
 *      looks identical to one that works, right up until the record is reopened.
 *   2. **A box that posts the WRONG SHAPE of nothing.** `""` is not `null`: on a PATCH an omitted
 *      key means "leave it alone" and `""` is a 422 from a date field, so only an explicit null
 *      clears a column. And `0` is not nothing at all — `Number(x) || null` turns "nought months"
 *      into "no answer", which is two of the three states this column deliberately keeps apart.
 *   3. **A reading stored in the wrong column.** `GridMeasurement.onHeight` returns INCHES and wrote
 *      the unit-less `height` box, so every grid-measured tool height in this repository was saved
 *      with no recoverable unit, under a 200, with the number looking right on screen.
 *   4. **A rendering that asserts more than the row knows.** `!!media.transcriptEditedAt` prints
 *      "the machine said this" over text a researcher may have typed, because NULL means both "not
 *      edited" and "this row cannot say".
 *
 * WHY A NODE SPEC AND NOT A BROWSER RUN. There is no React renderer in this repository's
 * devDependencies — Playwright is the whole of it — so mounting a component is not available, and
 * `record-pickers-unit.spec.ts` and `record-form-dictation-unit.spec.ts` read their structural
 * halves out of source for exactly that reason. `EditedFlag` IS genuinely executed below: it is a
 * plain function of its props with no hooks, so calling it and reading the element it returns is a
 * real test of the three-state rule rather than a regex over the file that implements it. What none
 * of this proves is that a browser PAINTS any of it; that gap is named at the bottom of the file
 * rather than left to be rediscovered.
 */

/**
 * Read a source file, NORMALISING LINE ENDINGS.
 *
 * `core.autocrlf` is true on the machines this is developed on, so the working tree is CRLF while
 * the repository stores LF — which means a source literal in this file containing a newline matches
 * on one checkout and not on another, and a passing test becomes a failing one with no source change
 * at all. `record-form-dictation-unit.spec.ts` paid for that once (see the note beside its
 * `signatureBlock`) and answered it by never spanning a line; this answers it at the read instead, so
 * the assertions below can say what they mean. Nothing here asserts anything ABOUT line endings, so
 * there is nothing to lose by collapsing them.
 */
const read = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

const TOOL_FORM = read("components", "forms", "ToolForm.tsx");
const PRODUCT_FORM = read("components", "forms", "ProductForm.tsx");
const ARTISAN_FORM = read("components", "forms", "ArtisanForm.tsx");
const PROCESS_FORM = read("components", "forms", "ProcessForm.tsx");
const WORKSHOPS_PAGE = read("app", "(protected)", "workshops", "page.tsx");
const FORM_CONTROLS = read("components", "FormControls.tsx");
const TRANSCRIPT_BLOCK = read("components", "media", "TranscriptBlock.tsx");
const REQUIRED_MARK = read("components", "ui", "RequiredMark.tsx");
const REVIEW_EDIT_PANEL = read("components", "review", "ReviewEditPanel.tsx");
const TASK_PRIMITIVES = read("components", "tasks", "TaskPrimitives.tsx");

/**
 * Comments in these files argue about the very patterns being banned — the `!!` reading, the
 * `|| null` collapse — and quoting a defect is how the argument against it is made. Ban them in
 * CODE.
 */
function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

/**
 * The whole `<TextInput …/>` element that carries `name="<name>"`.
 *
 * SLICED, NOT WINDOWED. A character budget across a multi-line element measures FORMATTING — the
 * same assertion goes red the moment the working tree's line endings become CRLF, because every
 * newline in the window costs two characters instead of one. `record-form-dictation-unit.spec.ts`
 * paid for that lesson once; this reads the region and asks the question inside it.
 *
 * The name test is the CLOSING QUOTE INCLUDED, deliberately: `name="height"` and
 * `name="heightInches"` are two different boxes on one form, and a prefix test would let an
 * assertion about the unit-bearing one pass by reading the unit-less one.
 */
function inputFor(source: string, name: string, label: string): string {
  const at = source.indexOf(`name="${name}"`);
  expect(at, `${label}: there must be a control named ${name}`).toBeGreaterThan(-1);
  const open = source.lastIndexOf("<TextInput", at);
  expect(open, `${label}: ${name} must be a <TextInput>`).toBeGreaterThan(-1);
  const close = source.indexOf("/>", at);
  return source.slice(open, close + 2);
}

/**
 * The whole element that carries `name="<name>"`, for a control with a closing tag.
 *
 * Same slicing rule as `inputFor` and the same reason: the region is located by its own landmarks
 * rather than by a character budget, so re-indenting a JSX block cannot turn a passing assertion red.
 * The opening tag is found by searching BACKWARDS from the name, which is what makes this correct on
 * a page holding several `<Select>`s.
 */
function elementFor(source: string, name: string, open: string, close: string, label: string): string {
  const at = source.indexOf(`name="${name}"`);
  expect(at, `${label}: there must be a control named ${name}`).toBeGreaterThan(-1);
  const from = source.lastIndexOf(open, at);
  expect(from, `${label}: ${name} must be a ${open}>`).toBeGreaterThan(-1);
  const to = source.indexOf(close, at);
  expect(to, `${label}: ${name} must be closed with ${close}`).toBeGreaterThan(-1);
  return source.slice(from, to + close.length);
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The tool's third measurement, and the two boxes that both say "Height"
 * ──────────────────────────────────────────────────────────────────────────── */

test("the tool form has the unit-bearing height box, and it is the one the grid panel fills", () => {
  // The box itself. A number input, not a dictated one — a recogniser spells digits out in words and
  // a native number input discards them silently, which is an empty box after a spoken answer.
  const box = inputFor(TOOL_FORM, "heightInches", "ToolForm");
  expect(box, "heightInches is a decimal measurement").toContain('type="number"');
  expect(box, "and it is controlled, so the grid panel can fill it").toContain("value={heightInches}");
  /*
    THIS ASSERTION CHANGED WITH THE PROVENANCE WORK, AND IT IS STRICTLY STRONGER NOW. It read
    `onChange={(event) => setHeightInches(event.target.value)}` — a bare setter. Every dimension box
    on both record forms now goes through each form's `typeInto(setter, column)` factory, which does
    two things and not one: it writes the box AND it forgets whatever a measurement route proposed
    into it, because a method marker is a claim about how THIS number was obtained and is false the
    instant somebody types over it. The column name is the second argument, so a box wired to the
    wrong key would file — or fail to clear — a marker under a dimension it never touched. See
    `components/forms/measurementMethods.ts`.
  */
  expect(box).toContain('onChange={typeInto(setHeightInches, "heightInches")}');
  expect(TOOL_FORM, "no microphone on a measurement").not.toMatch(
    /<DictatedTextInput[\s\S]{0,400}?name="heightInches"/
  );

  // The payload. A box that never reaches the body is indistinguishable from one that works.
  expect(TOOL_FORM, "and the value reaches the request").toContain("heightInches: toNum(heightInches)");

  /*
    THE REDIRECT, WHICH IS THE ACTUAL DEFECT. `GridMeasurement.onHeight` hands back a reading in
    INCHES. It used to be written into `height` — the old unit-less column — so the number was right,
    the box was filled, the save returned 200, and only the UNIT was lost, on every grid-measured
    tool in the repository. Asserted on the BLOCK rather than on the file, because `setHeight` is
    still a perfectly good function that the unit-less box's own onChange calls.
  */
  // The landmark gained a `method` parameter with the provenance work — `GridMeasurement` now hands
  // the server's own marker through the accept — so the slice is anchored on the new signature. The
  // guard itself is unchanged and is the whole reason this test exists.
  const onHeightAt = TOOL_FORM.indexOf("onHeight={(value, method) => {");
  expect(onHeightAt, "the grid panel's height callback must exist").toBeGreaterThan(-1);
  const onHeight = TOOL_FORM.slice(onHeightAt, TOOL_FORM.indexOf("onFilesChange=", onHeightAt));
  expect(onHeight, "the inches reading goes in the inches box").toContain("setHeightInches(value)");
  expect(onHeight, "and never again in the unit-less one").not.toContain("setHeight(value)");

  /*
    AND THE SAME REDIRECT ON THE DETERMINISTIC PANEL, which did not exist when this test was written.
    `RecordPhotoMeasure` proposes by COLUMN NAME, so the mistake it can make is the same one in a
    different spelling: routing `heightInches` into `setHeight`. Both routes on this form must land in
    one box, or a researcher who tries the panel and then the fallback is looking at two heights with
    nothing saying why there are two.
  */
  const proposeAt = TOOL_FORM.indexOf("onPropose={(key, text, method) => {");
  expect(proposeAt, "the deterministic panel must be wired").toBeGreaterThan(-1);
  const onPropose = TOOL_FORM.slice(proposeAt, TOOL_FORM.indexOf("onPhotoChange=", proposeAt));
  expect(onPropose, "the inches proposal goes in the inches box").toContain('key === "heightInches") setHeightInches(text)');
  expect(onPropose, "and the unit-less column is not a destination").not.toContain("setHeight(text)");
});

test("both height boxes point at one sentence that says which is which", () => {
  /*
    Two boxes labelled with the word "Height" on one form is a question a researcher cannot answer
    from the labels, and the honest answer does not fit in a label. BOTH inputs must name the
    paragraph — a reader who tabs into either one has exactly the same question, and describing only
    the new box leaves the old one looking like the normal one.
  */
  expect(
    (TOOL_FORM.match(/aria-describedby=\{heightHelpId\}/g) ?? []).length,
    "both height inputs describe themselves with it — one is worse than none"
  ).toBe(2);
  expect(inputFor(TOOL_FORM, "height", "ToolForm")).toContain("aria-describedby={heightHelpId}");
  expect(inputFor(TOOL_FORM, "heightInches", "ToolForm")).toContain("aria-describedby={heightHelpId}");
  expect(TOOL_FORM, "and the id is generated, so two mounted forms cannot collide").toContain(
    "const heightHelpId = useId();"
  );

  /*
    IT IS A <p> AND IT IS OUTSIDE BOTH `Field`s, and that is markup law rather than taste: `Field`
    renders a `<label>`, whose content model is phrasing content — a `<p>` inside one is invalid AND
    folds the whole paragraph into the box's accessible name, so a screen reader reads the
    explanation before ever saying "Height". Proven by position: the nearest `</Field>` before the
    paragraph must be nearer than the nearest `<Field`, i.e. every Field is closed.
  */
  const paragraphAt = TOOL_FORM.indexOf("<p id={heightHelpId}");
  expect(paragraphAt, "the sentence must exist").toBeGreaterThan(-1);
  expect(
    TOOL_FORM.lastIndexOf("</Field>", paragraphAt),
    "the paragraph sits between Fields, never inside one"
  ).toBeGreaterThan(TOOL_FORM.lastIndexOf("<Field", paragraphAt));
  const paragraph = TOOL_FORM.slice(paragraphAt, TOOL_FORM.indexOf("</p>", paragraphAt));
  expect(paragraph, "it names the box to fill in").toContain("Height (inches)");
  expect(paragraph, "and spans the row, or it reads as a note about one box").toMatch(/md:col-span-2/);
});

test("no measurement or price on the tool and product forms accepts a negative", () => {
  /*
    HALF OF A PAIR, AND THE OTHER HALF IS `ge=0` ON THE SERVER (backend/app/schemas/records.py).
    They are not interchangeable: `min` refuses the value IN THE BOX, by name, before a request is
    made; the Pydantic bound refuses it for every client that is not this one. Shipping only the
    server half means a researcher meets a 422 for a number that is still on screen.

    THE LIST IS EVERY MEASUREMENT AND EVERY PRICE, not the ones that looked likely. Every one of
    these boxes took a negative and stored it, and a bound that covers eight of nine is a bound that
    a reader will trust for the ninth.
  */
  for (const name of [
    "yearsInUse",
    "height",
    "width",
    "lengthInches",
    "breadthInches",
    "heightInches",
    "thickness",
    "weight",
    "radius",
    "replacementCost"
  ]) {
    expect(inputFor(TOOL_FORM, name, "ToolForm"), `ToolForm ${name} must refuse a negative`).toContain("min={0}");
  }
  for (const name of ["lengthInches", "breadthInches", "heightInches", "costOfMaking", "sellingPrice"]) {
    expect(inputFor(PRODUCT_FORM, name, "ProductForm"), `ProductForm ${name} must refuse a negative`).toContain(
      "min={0}"
    );
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The artisan's two experience answers, and the three states of the second
 * ──────────────────────────────────────────────────────────────────────────── */

test("the artisan form collects the craft start date and clears it with an explicit null", () => {
  const box = inputFor(ARTISAN_FORM, "craftStartDate", "ArtisanForm");
  expect(box, "a date, because a stated number of years is wrong within a year").toContain('type="date"');
  expect(box, "seeded from the record, trimmed to the date the input understands").toContain(
    'defaultValue={initial?.craftStartDate ? String(initial.craftStartDate).slice(0, 10) : ""}'
  );
  expect(box, "a craft taken up next year is a typo the browser can refuse").toContain(
    'max={new Date().toISOString().slice(0, 10)}'
  );
  expect(box, "a date fires no themed-control event, so the guard is armed by hand").toContain("onChange={markDirty}");
  expect(ARTISAN_FORM, "not dictated — both readings of an ambiguous spoken date are valid dates").not.toMatch(
    /<DictatedTextInput[\s\S]{0,400}?name="craftStartDate"/
  );

  /*
    `|| null` AND NOT A BARE READ. On a PATCH an omitted key means "leave it alone" and `""` is a 422
    from a `datetime | None` field, so only an EXPLICIT null clears the column — and `craftStartDate`
    is in `_CLEARABLE_COLUMNS` (backend/app/api/routes/artisans.py) precisely so that it can be.
    A date typed into the wrong box has to be retractable from the form that typed it.
  */
  expect(ARTISAN_FORM, "an empty box clears the column rather than doing nothing").toContain(
    'craftStartDate: textValue(form, "craftStartDate") || null'
  );
});

test("the months box is a closed 0..11 picker and keeps null and zero apart", () => {
  /*
    ELEVEN AND NOT TWELVE. `experienceMonths` is a REMAINDER on top of `experienceYears`, and the
    column carries `CHECK ("experienceMonths" BETWEEN 0 AND 11)` — a CHECK violation surfaces as a
    driver error raised from inside the write, i.e. a bare 500 naming no field, on a save the
    researcher cannot correct. A picker is what stops 12 from ever being typed.
  */
  expect(ARTISAN_FORM, "the rows are derived, not typed out").toContain(
    "const EXPERIENCE_MONTH_OPTIONS = Array.from({ length: 12 }, (_, month) => month);"
  );
  const picker = elementFor(ARTISAN_FORM, "experienceMonths", "<Select", "</Select>", "ArtisanForm");
  expect(picker, '"Not stated" is a real row, and it is not "0 months"').toContain('<option value="">Not stated</option>');
  expect(picker, "seeded from the record, and NOT collapsed with ?? — 0 is a real stored answer").toContain(
    'defaultValue={initial?.experienceMonths != null ? String(initial.experienceMonths) : ""}'
  );
  expect(picker, "a themed dropdown is a <button> and fires no input event").toContain("onChange={markDirty}");
  expect(ARTISAN_FORM, "no microphone on a closed twelve-row picker").not.toMatch(
    /<DictatedTextInput[\s\S]{0,400}?name="experienceMonths"/
  );

  /*
    THE ONE-LINER THAT IS BANNED, AND WHY THIS TEST EXISTS AT ALL.

    `Number(textValue(form, "experienceMonths")) || null` reads perfectly and is wrong: zero is
    falsy, so it turns "nought months" into "no answer". NULL and 0 are different answers this
    column deliberately keeps apart — NULL is "the artisan said nothing about months", 0 is "the
    artisan said none" — and `clean_data` plus `_CLEARABLE_COLUMNS` go to some trouble on the server
    to keep all three states reachable. Collapsing two of them in the browser throws that away where
    nothing can see it.
  */
  const code = codeOnly(ARTISAN_FORM);
  expect(code, "zero is not nothing").not.toMatch(/experienceMonths[^\n]*\|\|\s*null/);
  expect(code, "the null test is against null, not against truthiness").toContain(
    "experienceMonths: experienceMonthsText === null ? null : Number(experienceMonthsText)"
  );
  expect(code, "and the raw text is read once, so the payload cannot re-test it loosely").toContain(
    'const experienceMonthsText = textValue(form, "experienceMonths");'
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. What KIND of workshop a Workshop row records
 * ──────────────────────────────────────────────────────────────────────────── */

test("the workshop form can set the workshop kind, with the sibling product's own words", () => {
  /*
    A COLUMN NO CLIENT CAN SET IS A COLUMN THAT CAN NEVER HOLD `DESIGN_PROTOTYPE`. The wire half —
    the Pydantic schemas, the `GET /workshops?workshopType=` filter, `lib/types.ts`, Android's
    `ApiModels.kt` — shipped with migration 20260913120300 and this control is what makes it
    reachable. Without it every workshop this product writes is permanently unclassifiable to the
    sibling repository that the token exists for.
  */
  expect(WORKSHOPS_PAGE, "both tokens, and only these two").toContain(
    '{ value: "DESIGN_PROTOTYPE", label: "Design & Prototype Development Workshop" }'
  );
  expect(WORKSHOPS_PAGE).toContain('{ value: "OTHER", label: "Other workshop" }');
  // The labels are the sibling's verbatim: the stored value is a Postgres enum and the label is a
  // sentence, and two products printing two sentences for one enum value is how one decision comes
  // to be described two ways. Never print the token.
  expect(WORKSHOPS_PAGE, "the enum token is never what a researcher reads").not.toMatch(
    />\s*DESIGN_PROTOTYPE\s*</
  );

  const picker = elementFor(WORKSHOPS_PAGE, "workshopType", "<Select", "</Select>", "workshops page");
  expect(picker, "OTHER is the default — it is what every existing row implicitly was").toContain(
    'defaultValue={editing?.workshopType ?? "OTHER"}'
  );
  expect(picker, "a themed dropdown fires no input event for the form's onInput").toContain(
    "onChange={() => setDirty(true)}"
  );

  /*
    SENT ON BOTH CREATE AND UPDATE. `WorkshopUpdate.workshopType` is optional and the route dumps
    with `exclude_unset=True`, so an omitted key means "leave the stored kind alone" — which sounds
    safe and is wrong here, because the picker is on screen holding a value the editor can see.
    Omitting it means switching the dropdown and pressing Update returns 200 and changes nothing.
    The rosters below it are the deliberate exception and they carry their own argument.
  */
  expect(WORKSHOPS_PAGE, "the kind is part of the one payload both methods send").toContain(
    'workshopType: requiredText(form, "workshopType") || "OTHER"'
  );
  const payload = WORKSHOPS_PAGE.slice(
    WORKSHOPS_PAGE.indexOf("const payload: Record<string, unknown> = {"),
    WORKSHOPS_PAGE.indexOf("if (!editing || !sameIdSet(artisanIds")
  );
  expect(payload, "and it is inside the object literal, not behind an edit branch").toContain("workshopType:");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. Who stands behind a transcript — three answers, not two
 * ──────────────────────────────────────────────────────────────────────────── */

test("the edited flag has three states and draws nothing for the one that cannot answer", () => {
  /*
    EXECUTED, NOT READ. `EditedFlag` is a plain function of its props with no hooks, so calling it
    and reading the element it returns tests the rule rather than the file that implements it.

    `undefined` IS THE ONE THAT MATTERS. `MediaFile.transcriptEditedAt` is NULL both because the
    queue wrote this text and nobody touched it, AND because the row predates migration
    20260913120200 and genuinely cannot say — `POST /media/{id}/transcript` has been replacing
    transcripts since long before those columns existed. Drawing nothing is the only honest rendering
    of the second, and it is byte-for-byte what every reader saw before the columns existed.
  */
  expect(EditedFlag({ edited: undefined }), "not stated draws nothing at all").toBeNull();
  expect(EditedFlag({}), "and an omitted prop is the same answer").toBeNull();

  expect(flagText(EditedFlag({ edited: true })), "a person stands behind this text").toContain(
    "Checked by a researcher"
  );
  expect(
    flagText(EditedFlag({ edited: true, at: "2026-09-13T10:00:00Z" })),
    "with the date, which is the part a reader can use"
  ).toMatch(/Checked by a researcher · .*2026/);
  expect(flagText(EditedFlag({ edited: false })), "known not edited says so, in its own words").toBe("As transcribed");

  /*
    THE WORDING IS NOT "EDITED BY A HUMAN". `POST /media/{id}/transcript` stamps both a researcher
    who retyped a paragraph AND one who read an AI refinement and pressed Accept, on the argument
    that the second is still a person deciding these are the right words. The sentence must keep
    claiming only what the stamp means.
  */
  expect(flagText(EditedFlag({ edited: true })), "never a claim that a person typed every character").not.toMatch(
    /typed|wrote/i
  );
  // `transcriptEditedById` is a bare id with no relation behind it, so the API answers an id and no
  // name. Printing a CUID at a reader is worse than printing nothing.
  expect(codeOnly(TRANSCRIPT_BLOCK), "the editor is never named, because only an id is knowable").not.toContain(
    "transcriptEditedById"
  );
});

test("the transcript block passes the three-state value and never the two-state one", () => {
  const code = codeOnly(TRANSCRIPT_BLOCK);
  expect(code, "the ternary, which preserves not-stated").toContain(
    "<EditedFlag edited={media.transcriptEditedAt ? true : undefined} at={media.transcriptEditedAt} />"
  );
  /*
    `!!` IS THE WHOLE BUG IN TWO CHARACTERS. It answers `false` for a row that simply cannot say, and
    the flag would then print "As transcribed" over text a researcher may have written every word of
    — the single assertion these columns were added to stop being made silently. Banned in CODE
    only: the comments in this file and in `backend/app/api/routes/media.py` argue against it by
    quoting it, which is how the argument gets made.
  */
  expect(code, "never the collapsing reading").not.toMatch(/!!\s*media\.transcriptEditedAt/);
  expect(code, "and the flag's own contract keeps the third state").toContain("if (edited === undefined) return null;");
});

/** The readable text of a returned element, whatever shape its children arrived in. */
function flagText(node: unknown): string {
  if (node === null || node === undefined || typeof node === "boolean") return "";
  if (typeof node === "string") return node;
  if (typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(flagText).join("");
  const element = node as { props?: { children?: unknown } };
  return element.props ? flagText(element.props.children) : "";
}

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The last three hand-written asterisks, and the colour they were holding up
 * ──────────────────────────────────────────────────────────────────────────── */

test("every required mark in the product comes from one component, and it is red", () => {
  /*
    `record-form-dictation-unit.spec.ts` censuses the seven files for the hand-written literal and
    flips its colour assertion when the census comes back empty. This is the other half: that the
    three converted call sites actually MOUNT the shared component rather than merely having had the
    literal deleted. A label that lost its asterisk passes a census and is a required field a reader
    can no longer see is required.
  */
  expect(FORM_CONTROLS, "Field is most of the required marks in the product").toContain("<RequiredMark when={required} />");
  expect(REVIEW_EDIT_PANEL, "the review editor's field list").toContain("<RequiredMark when={field.required} />");
  expect(TASK_PRIMITIVES, "and FieldBlock, which labels everything containing a button").toContain(
    "<RequiredMark when={required} />"
  );
  for (const [name, source] of [
    ["FormControls", FORM_CONTROLS],
    ["ReviewEditPanel", REVIEW_EDIT_PANEL],
    ["TaskPrimitives", TASK_PRIMITIVES]
  ] as const) {
    expect(source, `${name} imports the one owner`).toContain('from "@/components/ui/RequiredMark"');
  }
  // The colour the three conversions were the precondition for. Two colours of required mark on one
  // screen reads as two kinds of requirement, which is a thing this product does not have — so this
  // and the census are one change and neither ships without the other.
  expect(REQUIRED_MARK).toContain('<span className="text-error-600 dark:text-red-400"> *</span>');
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. A microphone per note, on the last free-prose box that had none
 * ──────────────────────────────────────────────────────────────────────────── */

test("every note row gets its own microphone, named so a screen reader hears which", () => {
  /*
    `MultiNoteField` was the one free-prose box on a dictated form with no button, because it lives
    in `components/FormControls.tsx` and that file was outside the reach of the sweep that dictated
    everything else. `ProcessForm`'s `MultiNoteInput` is the line-for-line precedent and the three
    arguments below are its, unchanged.
  */
  const row = FORM_CONTROLS.slice(
    FORM_CONTROLS.indexOf("{notes.map((note, index) => ("),
    FORM_CONTROLS.indexOf("+ Add note")
  );
  expect(row, "the button is inside the note row").toContain("<OnDeviceDictationButton");

  /*
    PER ROW, NOT PER GROUP: one microphone over several textareas has to guess which note the phrase
    belongs in, and its only defensible guess (the last one) is wrong exactly when somebody is going
    back to fill in note two. The label carries the ordinal for the same reason — a screen-reader
    user hears "Dictate Notes, note 2", not three identically-named buttons.
  */
  expect(row).toContain("fieldLabel={notes.length > 1 ? `${label}, note ${index + 1}` : label}");
  /*
    ONE SENTENCE PER FORM, NOT ONE PER ROW. A note group can be eight textareas tall, so the default
    would draw eight copies of the "this browser cannot dictate" paragraph inside one field, which is
    how a true sentence becomes wallpaper. The flag's own contract is that the surrounding form
    carries the sentence once — see the gap named at the bottom of this file.
  */
  expect(row).toContain("explainWhenUnavailable={false}");
  /*
    COMMITTING APPENDS. The recogniser stops and starts across a long answer, so a commit that
    replaced the box would delete everything already in it the moment somebody paused for breath, and
    without the shared space rule a note dictated in three goes comes out as "…the warpis sized…".
    `appendDictatedPhrase` is the one place that rule lives; a local joiner here would be the third
    copy of it.
  */
  expect(row, "the shared joiner, never a local one").toContain("appendDictatedPhrase(n, phrase)");
  expect(FORM_CONTROLS).toContain('import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";');

  // BETWEEN the box and Remove, so the tab order reads write · dictate · delete and the destructive
  // control stays last.
  const textareaAt = row.indexOf("<textarea");
  const micAt = row.indexOf("<OnDeviceDictationButton");
  // Located by its HANDLER rather than by the word "Remove": the word appears in the comment above
  // the microphone too, and an index that could land on prose is not measuring the DOM order.
  const removeAt = row.indexOf("prev.filter((_, j) => j !== index)");
  expect(micAt, "the microphone follows the box").toBeGreaterThan(textareaAt);
  expect(removeAt, "and precedes the remove button").toBeGreaterThan(micAt);

  // The precedent it was copied from has not moved.
  expect(PROCESS_FORM, "ProcessForm still owns its own per-note button").toContain(
    "fieldLabel={rows.length > 1 ? `${label}, note ${index + 1}` : label}"
  );
});

test("the multi-note mirror stays hidden, and that is not an inconsistency with Select", () => {
  /*
    `Select`'s mirror in the same file is a zero-size `type="text"` because hidden inputs are exempt
    from constraint validation and a `required` Select would otherwise never block a submit.
    `MultiNoteField` takes no `required` and no call site marks a note group mandatory, so there is
    nothing to validate and nothing to exempt — the two are consistent with the RULE even though they
    disagree about the attribute. Pinned here because "make the two the same" is the obvious tidy-up.
  */
  expect(FORM_CONTROLS).toContain('<input type="hidden" name={name} value={joined} />');
  expect(FORM_CONTROLS, "and the Select mirror is still the validating kind").toMatch(
    /type="text"[\s\S]{0,300}?tabIndex=\{-1\}[\s\S]{0,200}?opacity-0/
  );
  // The blank-line join is what two platforms split this column on. A single newline silently
  // collapses several notes into one, on a handset, days later.
  expect(FORM_CONTROLS).toContain('.join("\\n\\n")');
});

/**
 * ── WHAT THIS SPEC DOES NOT PROVE, NAMED RATHER THAN LEFT TO BE REDISCOVERED ────────────────────
 *
 *  - THAT A BROWSER PAINTS ANY OF IT. There is no React renderer here. `EditedFlag` is genuinely
 *    called, but nothing above mounts a form, types into a box or presses Save, so a control that
 *    throws on mount would pass every assertion in this file.
 *  - THAT `/questionnaire` SAYS WHY THE MICROPHONE IS MISSING. It mounts `MultiNoteField` and does
 *    NOT mount `DictationUnavailableNotice`, so on Firefox its interview-notes buttons now vanish
 *    with nothing anywhere explaining it — which is precisely the silent-nothing
 *    `explainWhenUnavailable={false}` is only allowed to create when the surrounding form speaks for
 *    it. That page belongs to another lane and there is deliberately NO assertion about it here: an
 *    assertion about work nobody has done is a red spec, not a reminder. This paragraph is the
 *    reminder, and it is also in the handoff.
 *  - THE SERVER'S HALF OF ANY OF THESE PAIRS. `ge=0`, the `experienceMonths` CHECK, the
 *    `WorkshopType` enum and `client_key_replay` all have their own tests in `backend/tests/`. This
 *    file only asserts that a client stops handing the server values it would have to refuse.
 *  - THE OFFLINE IDEMPOTENCY KEY, which is `e2e/record-client-key-unit.spec.ts`.
 */
