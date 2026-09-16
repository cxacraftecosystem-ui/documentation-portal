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
    THIS ASSERTION HAS CHANGED TWICE, AND IT IS STRICTLY STRONGER EACH TIME.

    It first read `onChange={(event) => setHeightInches(event.target.value)}` — a bare setter. The
    provenance work moved every dimension box on both record forms onto each form's factory, which
    does two things and not one: it writes the box AND it forgets whatever a measurement route
    proposed into it, because a method marker is a claim about how THIS number was obtained and is
    false the instant somebody types over it. The column name is an argument, so a box wired to the
    wrong key would file — or fail to clear — a marker under a dimension it never touched. See
    `components/forms/measurementMethods.ts`.

    THE CENTIMETRE PAIRING ADDED THE THIRD ARGUMENT AND RENAMED THE FACTORY. `ToolForm.typeInto`
    is `typeInches` now, and the third argument is the CENTIMETRE PARTNER this inch box fills as the
    researcher types: `heightInches` ↔ `height`, `breadthInches` ↔ `width`. The partner is asserted
    by name because the failure it guards against is silent and symmetrical — wiring `heightInches`
    to `setWidth` puts a height into the width column with nothing on screen or in the payload
    saying so. `lengthInches` is STANDALONE and passes no partner; `ProductForm` has no pairing at
    all and keeps `typeInto`.
  */
  expect(box).toContain('onChange={typeInches(setHeightInches, "heightInches", setHeight)}');
  expect(inputFor(TOOL_FORM, "breadthInches", "ToolForm"), "breadth fills the width box").toContain(
    'onChange={typeInches(setBreadth, "breadthInches", setWidth)}'
  );
  expect(inputFor(TOOL_FORM, "lengthInches", "ToolForm"), "length has no centimetre partner").toContain(
    'onChange={typeInches(setLength, "lengthInches")}'
  );
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
  /*
    `setHeight(value)` WAS THE DEFECT AND STILL IS — but the box it names is no longer unit-less.
    `height` is the CENTIMETRE partner of `heightInches` now, so the grid's height accept legitimately
    reaches `setHeight`; what it may never do is put the INCHES STRAIGHT IN, which is what
    `setHeight(value)` spells and what lost the unit on every grid-measured tool in this repository.
    So the negative assertion is kept verbatim — it is still exactly the wrong line — and a positive
    one is added beside it naming the conversion. A partner filled with `value` and a partner filled
    with `cmTextFromInches(value)` differ by a factor of 2.54 and by nothing a reader can see.
  */
  expect(onHeight, "the inches are never written verbatim into the centimetre box").not.toContain("setHeight(value)");
  expect(onHeight, "the centimetre partner is filled, converted").toContain(
    "propagate(value, cmTextFromInches, setHeight)"
  );

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
  expect(onPropose, "the inches proposal goes in the inches box").toMatch(
    /key === "heightInches"\)\s*\{?\s*\n?\s*setHeightInches\(text\);?/
  );
  // Same distinction as the grid route above: the centimetre partner is filled by CONVERSION, and
  // the raw inch string is never written into it.
  expect(onPropose, "the inches are never written verbatim into the centimetre box").not.toContain("setHeight(text)");
  expect(onPropose, "the centimetre partner is filled, converted").toContain(
    "propagate(text, cmTextFromInches, setHeight)"
  );
  expect(onPropose, "and breadth fills its own partner, `width`").toContain(
    "propagate(text, cmTextFromInches, setWidth)"
  );
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
  expect(paragraph, "it names both boxes of the pair").toContain("Height (inches)");
  expect(paragraph, "and the centimetre one by its new label").toContain("Height (cm)");
  expect(paragraph, "and spans the row, or it reads as a note about one box").toMatch(/md:col-span-2/);
  /*
    THE INSTRUCTION REVERSED ON 2026-09-15, AND THIS IS THE ASSERTION THAT HOLDS THE NEW ONE SHUT.
    The paragraph used to say the two boxes were different columns and to *"leave it empty unless you
    are correcting one of those"*. They are now the SAME measurement in two units and filling either
    fills the other, so the old sentence is not merely stale — it tells a researcher to do the one
    thing the form no longer expects. A screen that still carried it would be a worse defect than no
    note at all, because it would be confidently wrong.
  */
  expect(paragraph, "the retired instruction is gone from the screen").not.toContain("leave it empty unless");
  expect(paragraph, "and the new truth is stated").toContain("filling either fills the other");
  expect(paragraph, "with the factor, so nobody has to guess what it converted by").toContain("2.54");
  // It must stay honest about the rows that predate the pairing: this form NEVER converts on load,
  // so an old record can show two figures that disagree and the note is the only thing that says so.
  expect(paragraph, "it admits the old rows").toContain("before this pairing existed");
  // The standalone box is named as standalone, or a reader is left to infer it from an absence.
  expect(paragraph, "and Length is declared to have no partner").toContain("has no centimetre box");
});

test("the tool form's centimetre boxes are labelled as centimetres and never renamed on the wire", () => {
  /*
    THE LABEL SAYS THE UNIT; THE FIELD NAME DOES NOT MOVE.

    `height` and `width` are the same columns, the same wire keys and the same entries in
    `_CLEARABLE_COLUMNS` / `ToolCreate` / `ToolUpdate` they always were — renaming any of those would
    be a migration wearing a label change. What changed is the only half a person reads. Asserted
    together because the pair is the decision: a label without the name, or a name without the label.
  */
  expect(TOOL_FORM, "the centimetre height says so").toContain('<Field label="Height (cm)">');
  expect(TOOL_FORM, "and the centimetre width").toContain('<Field label="Width (cm)">');
  expect(TOOL_FORM, "the unqualified labels are gone from this form").not.toContain('<Field label="Height">');
  expect(TOOL_FORM, "both of them").not.toContain('<Field label="Width">');
  expect(inputFor(TOOL_FORM, "height", "ToolForm"), "and the wire key is untouched").toContain('name="height"');
  expect(inputFor(TOOL_FORM, "width", "ToolForm"), "as is the other one").toContain('name="width"');
  /*
    WIDTH IS CONTROLLED NOW, WHICH IS NOT COSMETIC: a partner box is written by code, and an
    uncontrolled `defaultValue` input cannot be. A `defaultValue` left here would take the researcher's
    typing and silently ignore every conversion, which looks exactly like a working form.
  */
  expect(inputFor(TOOL_FORM, "width", "ToolForm"), "width is controlled, or nothing can fill it").toContain(
    "value={width}"
  );
  expect(inputFor(TOOL_FORM, "width", "ToolForm"), "and not half-converted from FormData").not.toContain(
    "defaultValue"
  );
  expect(TOOL_FORM, "and the payload reads it from state").toContain("width: toNum(width)");
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
 * 1b. The tool's two multi-selects, and the name that follows another name
 * ──────────────────────────────────────────────────────────────────────────── */

test("the tool form links SEVERAL crafts and artisans, and still fills the first-of-each columns", () => {
  /*
    ONE TOOL COVERS SEVERAL CRAFTS, AND THE SCALARS DO NOT GO AWAY. `tool.craftId` keeps the FIRST
    selected craft and `tool.artisanId` the first artisan, because every existing filter, index,
    report, carry-forward and data-browser branch reads them; `craftName` holds every craft name
    joined ", " in the same order; the `ToolCraft` / `ToolArtisan` join tables hold all of them. A
    form that sent only the lists would leave those columns holding whatever they held before, which
    is a record whose links and whose own columns disagree — and nothing on any screen would say so.
  */
  expect(TOOL_FORM, "crafts are a multi-select").toMatch(
    /<Field label="Linked crafts \(fills craft name\)">[\s\S]{0,400}?<MultiSelectDropdown/
  );
  expect(TOOL_FORM, "and so are artisans").toMatch(
    /<Field label="Linked artisans \(fills artisan \+ place\)">[\s\S]{0,400}?<MultiSelectDropdown/
  );
  // Both lists reach the body, and both scalars are still derived from element 0 rather than kept as
  // a second piece of state that could drift from the selection.
  const payload = TOOL_FORM.slice(TOOL_FORM.indexOf("const payload = {"), TOOL_FORM.indexOf("// Offline this queues"));
  expect(payload, "the craft list is sent").toContain("craftIds: craftLinksChanged ? craftIds : undefined,");
  expect(payload, "the artisan list is sent").toContain("artisanIds: artisanLinksChanged ? artisanIds : undefined,");
  expect(payload, "and the first-of-each columns with them").toContain("craftId: craftId || null");
  expect(payload, "and the first-of-each columns with them").toContain("artisanId: artisanId || null");
  expect(TOOL_FORM, "the scalars are DERIVED, never a second state").toContain('const craftId = craftIds[0] ?? "";');
  expect(TOOL_FORM, "both of them").toContain('const artisanId = artisanIds[0] ?? "";');
  /*
    SENT ONLY WHEN THE PICKER CHANGED, AND THE ASSERTION THIS REPLACED IS QUOTED RATHER THAN DROPPED.
    It read `expect(payload, "the craft list is sent").toMatch(/^\s*craftIds,$/m)` — i.e. that the
    state array was sent unconditionally, on every save. That was right about `[]` and wrong about
    everything else: `PATCH /tools/{id}` refuses ANY send of a populated relation from a caller who is
    not an admin, the author, or an EDIT-grantee, so an unconditional send answered a contributor who
    had typed into "Material" with *"Only the original contributor or an admin can change populated
    relation: craftIds"*, about a picker they never opened — after the row had already been
    updated and a revision already committed. It also re-sent a mount-time list over links a
    colleague had added since, which `_replace_artisan_links` deletes.

    `[]` STILL TRAVELS, which is the half that must not be lost: the diff is against the record's own
    stored links, so an EMPTIED picker differs from them and is still sent as `[]`. A
    `craftIds.length ? craftIds : undefined` is the shape that would break it, and the two
    assertions below still ban exactly that.
  */
  expect(payload, "an empty selection is not hidden behind a conditional").not.toMatch(/craftIds\.length \?/);
  expect(payload, "and neither is the artisan one").not.toMatch(/artisanIds\.length \?/);
  expect(TOOL_FORM, "the craft diff is a SET comparison against the stored links").toContain(
    "const craftLinksChanged = !initial || !sameIdSet(craftIds, storedCraftIds(initial));"
  );
  expect(TOOL_FORM, "and so is the artisan one").toContain(
    "const artisanLinksChanged = !initial || !sameIdSet(artisanIds, storedArtisanIds(initial));"
  );
  // The craft-deselection rule is the shared one, where a test can reach it — never re-derived here.
  expect(TOOL_FORM, "deselection goes through the shared rule").toContain("craftsChangeClearsArtisans({");
  /*
    AND IT IS TOLD WHICH CRAFTS WENT AWAY. Without `removedCraftIds` the rule answers "is this artisan
    of a craft that is not ticked", which drops people whose craft was never ticked in the first
    place — every artisan linked through "Assign a tool to multiple artisans", whose craft this
    picker has nothing to do with. Only the caller can compute the difference, so only the caller can
    get it wrong.
  */
  expect(TOOL_FORM, "the removed crafts are computed from the PREVIOUS selection").toContain(
    "const removed = craftIds.filter((id) => !next.includes(id));"
  );
  expect(TOOL_FORM, "and handed to the rule").toContain("removedCraftIds: removed,");
  expect(TOOL_FORM, "and the A→Z order is the shared one too").toContain("sortArtisansByCraft(offered, selectedCrafts)");
});

test("the tool form keeps `artisanId` and `artisanName` naming the SAME person", () => {
  /*
    THREE WAYS ONE RECORD CAME TO NAME TWO PEOPLE, and the three rules that close them. `artisanId` is
    element 0 of the picker; `artisanName` and `place` are single NOT NULL columns describing that
    person. Every failure below returned 200 with nothing on screen having changed.

    1. THE SEED. `artisanLinks` does not mean "this tool's artisans" — it means "also assigned to".
       "Assign a tool to multiple artisans" writes rows for anybody and touches none of the scalars,
       and `DELETE /tools/{id}/artisans/{id}` removes rows just as freely, the tool's own artisan
       included. Seeding from the links alone put somebody else at element 0, so opening a tool to fix
       a typo and pressing Update re-pointed `tool.artisanId` at them. The stored scalar leads.
    2. THE ORDER. `artisanLinks` had no order at all until `_order_artisan_links` landed beside this
       change — `hydrate_relations` issues no `order`, and `_replace_artisan_links` restamps every row
       in one `create_many`, so `createdAt asc` could not have broken the tie either. The server pins
       the scalar first now; the client hoists it anyway, because a tool whose `artisanId` has no link
       row has nothing to pin and because this bundle may be reading an older API.
    3. THE PROMOTION. Unticking a CRAFT drops that craft's artisans, which can drop the head — and
       that path rewrote neither name box, while the artisan picker's own `onChange` did. Same end
       state, two different stored rows. Both go through one helper now.
  */
  expect(TOOL_FORM, "the stored scalar leads the seeded craft list").toContain(
    "  return [head, ...linked.filter((id) => id !== head)];"
  );
  expect(TOOL_FORM, "the craft picker is seeded from it").toContain("const seeded = storedCraftIds(initial);");
  expect(TOOL_FORM, "and the artisan picker from its twin").toContain("const seeded = storedArtisanIds(initial);");
  // The links are read ONLY through those two helpers, which is what makes the hoist unskippable: a
  // second reader of `initial.artisanLinks` inside the component is a second seeding rule.
  expect(TOOL_FORM, "the component never reads the raw links").not.toContain("initial?.artisanLinks");
  expect(TOOL_FORM, "nor the raw craft links").not.toContain("initial?.craftLinks");

  // ONE WRITER for the two companion columns, reached from both gestures.
  const sync = TOOL_FORM.slice(
    TOOL_FORM.indexOf("function syncArtisanColumns("),
    TOOL_FORM.indexOf("function onCraftsChanged(")
  );
  expect(sync, "nothing is written when element 0 did not move").toContain(
    'if (!head || head === (previous[0] ?? "")) return;'
  );
  expect(sync, "and nothing when the new head is off-page").toContain("if (!first) return;");
  expect(sync, "the two columns come from the head artisan's own row").toContain("setArtisanName(first.name);");
  expect(sync, "both of them").toContain("setPlace(first.place);");
  expect(TOOL_FORM, "the craft cascade re-derives them").toContain("syncArtisanColumns(kept, artisanIds);");
  expect(TOOL_FORM, "and so does the artisan picker").toContain("syncArtisanColumns(next, previous);");
  /*
    AND NEITHER COLUMN IS WRITTEN ANYWHERE ELSE. `routes/tools.py` does NOT derive `artisanName` or
    `place` from `artisanIds` — both are things a researcher legitimately corrects by hand
    ("A. Khatri" → "Abdul Khatri"), so the body's values stand. A second writer that fired on every
    toggle is what reverted a hand-corrected Place the moment a second artisan was ticked.
  */
  const code = codeOnly(TOOL_FORM);
  expect(code.match(/setArtisanName\(first\.name\)/g) ?? [], "one writer, not two").toHaveLength(1);
  expect(code.match(/setPlace\(first\.place\)/g) ?? [], "one writer, not two").toHaveLength(1);
});

test("the tool form never converts a dimension on load, and never watches both halves of a pair", () => {
  /*
    TWO PROHIBITIONS, AND BOTH ARE THE KIND THAT LOOK LIKE TIDY-UPS WHEN SOMEBODY ADDS THEM BACK.

    NO CONVERSION ON LOAD. Rows saved before the pairing hold two genuinely unrelated numbers in
    `height` and `heightInches`; seeding either box from its partner would rewrite a stored value the
    moment a researcher merely OPENED the record, and the next save would make it permanent. Each box
    is seeded from its own column and nothing else.

    NO WATCHER OVER BOTH HALVES. `useEffect`/`useMemo` over `[height, heightInches]` fires for
    whichever of the two changed and re-derives the source from the value it just wrote — 1 cm
    becomes 0.39 in becomes 0.99 cm, decaying on every keystroke. The conversion is written ONLY from
    inside the typing box's own handler, which cannot do that. `ToolForm` has no `useEffect` at all,
    so the strong form of the assertion is available: there is no effect in this file to hide one in.
  */
  expect(TOOL_FORM, "the centimetre height is seeded from its own column").toContain(
    'const [height, setHeight] = useState(initial?.height != null ? String(initial.height) : "");'
  );
  expect(TOOL_FORM, "and the centimetre width from its own").toContain(
    'const [width, setWidth] = useState(initial?.width != null ? String(initial.width) : "");'
  );
  expect(TOOL_FORM, "no conversion helper is reached at construction").not.toMatch(
    /useState\([^)]*(cmTextFromInches|inchesTextFromCm)/
  );
  /*
    ASSERTED AT THE IMPORT AND NOT ON THE BODY, because the body legitimately NAMES `useEffect` — the
    comments beside both conversion factories argue at length about why the watcher version is the
    one shape that cannot work, and an assertion that banned the word would ban the explanation. An
    effect cannot be written without importing the hook (this codebase never writes `React.useEffect`
    and has no `React` default import anywhere), so the import line is the airtight half and the
    comments stay where a reader will meet them.
  */
  const reactImport = TOOL_FORM.slice(TOOL_FORM.indexOf('import {'), TOOL_FORM.indexOf('} from "react";') + 15);
  expect(reactImport, "this form imports no effect hook").not.toContain("useEffect");
  expect(TOOL_FORM, "and reaches for none through a namespace either").not.toMatch(/React\.use(Effect|LayoutEffect)/);
});

test("the toolkit name mirrors into the English name, and stops the moment a person touches it", () => {
  /*
    ── A ONE-WAY DOOR, AND THE THREE WAYS IT IS HELD SHUT ──────────────────────────────────────

    The rule: "English name" follows "Toolkit name" as it is typed, UNLESS the researcher has edited
    the English box themselves — after which it is theirs for the life of the form. The failure modes
    are all silent, so each is asserted by source rather than left to a reading:

    1. **A `useRef`, not a `useState`.** The latch is read inside a callback handed to a child; a
       state value captured in a stale closure would re-arm a divorced form on the next keystroke and
       overwrite a hand-typed name.
    2. **Mirrored at the WRITE SITE, never in an effect.** An effect keyed on `toolkitName` fires on
       MOUNT with the loaded value, so an edit whose stored English name is empty — legitimately
       armed — would be rewritten and marked dirty by merely being OPENED. (The blanket "no effects
       on this form" assertion above covers the file; this names the reason.)
    3. **Every writer goes through one helper.** A second `setToolkitName(...)` anywhere else is a
       write that silently does not mirror, which is indistinguishable from the feature working until
       somebody uses that path.
  */
  expect(TOOL_FORM, "the latch is a ref").toMatch(/const mirrorArmed = useRef\(/);
  expect(TOOL_FORM, "and it is never state").not.toMatch(/\[\s*mirrorArmed\s*,/);
  /*
    THE INITIAL STATE IS THE EDGE CASE. A create always arms. An EDIT arms only when the stored
    English name is blank or RAW-EQUAL to the stored toolkit name — i.e. when mirroring cannot
    destroy anything anybody chose. A record whose two names genuinely differ opens DIVORCED, so
    correcting the toolkit name of an existing tool never clobbers its English name.
  */
  // Sliced from the declaration to the helper BELOW it — `indexOf("applyToolkitName")` from the top
  // of the file lands in the latch's own docblock, which names the helper before declaring it.
  const latchAt = TOOL_FORM.indexOf("const mirrorArmed = useRef(");
  const latch = TOOL_FORM.slice(latchAt, TOOL_FORM.indexOf("function applyToolkitName", latchAt));
  expect(latch, "a create arms").toContain("initial ?");
  expect(latch, "an edit arms on a blank stored name").toContain('(initial.englishName ?? "").trim() === ""');
  expect(latch, "or on raw equality — no trim, no case fold").toContain(
    '(initial.englishName ?? "") === (initial.toolkitName ?? "")'
  );

  // ONE WRITER. The helper is the only thing that may call `setToolkitName`, so no path can skip the
  // mirror; `applyToolkitName` itself is the single exception the count allows for.
  expect(TOOL_FORM.match(/setToolkitName\(/g)?.length, "exactly one writer of the toolkit name").toBe(1);
  expect(TOOL_FORM, "and it is the helper").toMatch(/function applyToolkitName\([\s\S]{0,200}?setToolkitName\(next\);/);
  expect(TOOL_FORM, "which mirrors only while armed").toMatch(/if \(mirrorArmed\.current\) setEnglishName\(next\);/);
  expect(TOOL_FORM, "the toolkit box goes through it").toContain(
    "onChange={(next) => applyToolkitName(next, { user: true })}"
  );

  /*
    TOUCHING THE ENGLISH BOX DISARMS — INCLUDING CLEARING IT BY HAND. Every route into that handler
    is a person (a keystroke, a paste, a dictated phrase, a backspace to nothing), so the latch drops
    there unconditionally: no `if (next)` guard, or emptying the box would leave it armed and the
    next keystroke in "Toolkit name" would refill what somebody had just deliberately cleared.
  */
  const englishAt = TOOL_FORM.indexOf('name="englishName"');
  expect(englishAt, "the English name box must exist").toBeGreaterThan(-1);
  const englishBox = TOOL_FORM.slice(englishAt, TOOL_FORM.indexOf("/>", englishAt));
  expect(englishBox, "it disarms the mirror").toContain("mirrorArmed.current = false;");
  expect(englishBox, "unconditionally — clearing the box is an edit").not.toMatch(/if \(next\)[\s\S]{0,80}mirrorArmed/);
  // A PROGRAMMATIC write of the English name must NOT disarm, so the latch may be set false in
  // exactly one place: that handler.
  expect(TOOL_FORM.match(/mirrorArmed\.current = false/g)?.length, "one disarm site, and one only").toBe(1);
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
