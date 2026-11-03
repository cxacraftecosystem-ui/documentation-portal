import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { EditedFlag } from "@/components/media/TranscriptBlock";

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

function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

function inputFor(source: string, name: string, label: string): string {
  const at = source.indexOf(`name="${name}"`);
  expect(at, `${label}: there must be a control named ${name}`).toBeGreaterThan(-1);
  const open = source.lastIndexOf("<TextInput", at);
  expect(open, `${label}: ${name} must be a <TextInput>`).toBeGreaterThan(-1);
  const close = source.indexOf("/>", at);
  return source.slice(open, close + 2);
}

function elementFor(source: string, name: string, open: string, close: string, label: string): string {
  const at = source.indexOf(`name="${name}"`);
  expect(at, `${label}: there must be a control named ${name}`).toBeGreaterThan(-1);
  const from = source.lastIndexOf(open, at);
  expect(from, `${label}: ${name} must be a ${open}>`).toBeGreaterThan(-1);
  const to = source.indexOf(close, at);
  expect(to, `${label}: ${name} must be closed with ${close}`).toBeGreaterThan(-1);
  return source.slice(from, to + close.length);
}

test("the tool form has the unit-bearing height box, and it is the one the grid panel fills", () => {
  const box = inputFor(TOOL_FORM, "heightInches", "ToolForm");
  expect(box, "heightInches is a decimal measurement").toContain('type="number"');
  expect(box, "and it is controlled, so the grid panel can fill it").toContain("value={heightInches}");
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

  expect(TOOL_FORM, "and the value reaches the request").toContain("heightInches: toNum(heightInches)");

  const onHeightAt = TOOL_FORM.indexOf("onHeight={(value, method) => {");
  expect(onHeightAt, "the grid panel's height callback must exist").toBeGreaterThan(-1);
  const onHeight = TOOL_FORM.slice(onHeightAt, TOOL_FORM.indexOf("onFilesChange=", onHeightAt));
  expect(onHeight, "the inches reading goes in the inches box").toContain("setHeightInches(value)");
  expect(onHeight, "the inches are never written verbatim into the centimetre box").not.toContain("setHeight(value)");
  expect(onHeight, "the centimetre partner is filled, converted").toContain(
    "propagate(value, cmTextFromInches, setHeight)"
  );

  const proposeAt = TOOL_FORM.indexOf("onPropose={(key, text, method) => {");
  expect(proposeAt, "the deterministic panel must be wired").toBeGreaterThan(-1);
  const onPropose = TOOL_FORM.slice(proposeAt, TOOL_FORM.indexOf("onPhotoChange=", proposeAt));
  expect(onPropose, "the inches proposal goes in the inches box").toMatch(
    /key === "heightInches"\)\s*\{?\s*\n?\s*setHeightInches\(text\);?/
  );
  expect(onPropose, "the inches are never written verbatim into the centimetre box").not.toContain("setHeight(text)");
  expect(onPropose, "the centimetre partner is filled, converted").toContain(
    "propagate(text, cmTextFromInches, setHeight)"
  );
  expect(onPropose, "and breadth fills its own partner, `width`").toContain(
    "propagate(text, cmTextFromInches, setWidth)"
  );
});

test("both height boxes point at one sentence that says which is which", () => {
  expect(
    (TOOL_FORM.match(/aria-describedby=\{heightHelpId\}/g) ?? []).length,
    "both height inputs describe themselves with it — one is worse than none"
  ).toBe(2);
  expect(inputFor(TOOL_FORM, "height", "ToolForm")).toContain("aria-describedby={heightHelpId}");
  expect(inputFor(TOOL_FORM, "heightInches", "ToolForm")).toContain("aria-describedby={heightHelpId}");
  expect(TOOL_FORM, "and the id is generated, so two mounted forms cannot collide").toContain(
    "const heightHelpId = useId();"
  );

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
  expect(paragraph, "the retired instruction is gone from the screen").not.toContain("leave it empty unless");
  expect(paragraph, "and the new truth is stated").toContain("fill each other");
  expect(paragraph, "with the factor, so nobody has to guess what it converted by").toContain("2.54");
  expect(paragraph, "it warns about disagreeing pairs").toContain("may have two numbers that disagree");
  expect(paragraph, "and Length is declared to have no partner").toContain("has no centimetre box");
});

test("the tool form's centimetre boxes are labelled as centimetres and never renamed on the wire", () => {
  expect(TOOL_FORM, "the centimetre height says so").toContain('<Field label="Height (cm)">');
  expect(TOOL_FORM, "and the centimetre width").toContain('<Field label="Width (cm)">');
  expect(TOOL_FORM, "the unqualified labels are gone from this form").not.toContain('<Field label="Height">');
  expect(TOOL_FORM, "both of them").not.toContain('<Field label="Width">');
  expect(inputFor(TOOL_FORM, "height", "ToolForm"), "and the wire key is untouched").toContain('name="height"');
  expect(inputFor(TOOL_FORM, "width", "ToolForm"), "as is the other one").toContain('name="width"');
  expect(inputFor(TOOL_FORM, "width", "ToolForm"), "width is controlled, or nothing can fill it").toContain(
    "value={width}"
  );
  expect(inputFor(TOOL_FORM, "width", "ToolForm"), "and not half-converted from FormData").not.toContain(
    "defaultValue"
  );
  expect(TOOL_FORM, "and the payload reads it from state").toContain("width: toNum(width)");
});

test("no measurement or price on the tool and product forms accepts a negative", () => {
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

test("the tool form links SEVERAL crafts and artisans, and still fills the first-of-each columns", () => {
  expect(TOOL_FORM, "crafts are a multi-select, from the master list").toMatch(/<CraftSelect\s+multiple\s+name="craftIds"/);
  expect(TOOL_FORM, "and so are artisans").toMatch(
    /<Field label="Linked artisans \(fills artisan \+ place\)">[\s\S]{0,400}?<MultiSelectDropdown/
  );
  const payload = TOOL_FORM.slice(TOOL_FORM.indexOf("const payload = {"), TOOL_FORM.indexOf("const outcome = await saveOrQueue<ToolDocumentation>("));
  expect(payload, "the craft list is sent").toContain("craftIds: craftLinksChanged ? craftIds : undefined,");
  expect(payload, "the artisan list is sent").toContain("artisanIds: artisanLinksChanged ? artisanIds : undefined,");
  expect(payload, "and the first-of-each columns with them").toContain("craftId: craftId || null");
  expect(payload, "and the first-of-each columns with them").toContain("artisanId: artisanId || null");
  expect(TOOL_FORM, "the scalars are DERIVED, never a second state").toContain('const craftId = craftIds[0] ?? "";');
  expect(TOOL_FORM, "both of them").toContain('const artisanId = artisanIds[0] ?? "";');
  expect(payload, "an empty selection is not hidden behind a conditional").not.toMatch(/craftIds\.length \?/);
  expect(payload, "and neither is the artisan one").not.toMatch(/artisanIds\.length \?/);
  expect(TOOL_FORM, "the craft diff is a SET comparison against the stored links").toContain(
    "const craftLinksChanged = !initial || !sameIdSet(craftIds, storedCraftIds(initial));"
  );
  expect(TOOL_FORM, "and so is the artisan one").toContain(
    "const artisanLinksChanged = !initial || !sameIdSet(artisanIds, storedArtisanIds(initial));"
  );
  expect(TOOL_FORM, "deselection goes through the shared rule").toContain("craftsChangeClearsArtisans({");
  expect(TOOL_FORM, "the removed crafts are computed from the PREVIOUS selection").toContain(
    "const removed = craftIds.filter((id) => !next.includes(id));"
  );
  expect(TOOL_FORM, "and handed to the rule").toContain("removedCraftIds: removed,");
  expect(TOOL_FORM, "and the A→Z order is the shared one too").toContain("sortArtisansByCraft(offered, selectedCrafts)");
});

test("the tool form keeps `artisanId` and `artisanName` naming the SAME person", () => {
  expect(TOOL_FORM, "the stored scalar leads the seeded craft list").toContain(
    "  return [head, ...linked.filter((id) => id !== head)];"
  );
  expect(TOOL_FORM, "the craft picker is seeded from it").toContain("const seeded = storedCraftIds(initial);");
  expect(TOOL_FORM, "and the artisan picker from its twin").toContain("const seeded = storedArtisanIds(initial);");
  expect(TOOL_FORM, "the component never reads the raw links").not.toContain("initial?.artisanLinks");
  expect(TOOL_FORM, "nor the raw craft links").not.toContain("initial?.craftLinks");

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
  const code = codeOnly(TOOL_FORM);
  expect(code.match(/setArtisanName\(first\.name\)/g) ?? [], "one writer, not two").toHaveLength(1);
  expect(code.match(/setPlace\(first\.place\)/g) ?? [], "one writer, not two").toHaveLength(1);
});

test("the tool form never converts a dimension on load, and never watches both halves of a pair", () => {
  expect(TOOL_FORM, "the centimetre height is seeded from its own column").toContain(
    'const [height, setHeight] = useState(initial?.height != null ? String(initial.height) : "");'
  );
  expect(TOOL_FORM, "and the centimetre width from its own").toContain(
    'const [width, setWidth] = useState(initial?.width != null ? String(initial.width) : "");'
  );
  expect(TOOL_FORM, "no conversion helper is reached at construction").not.toMatch(
    /useState\([^)]*(cmTextFromInches|inchesTextFromCm)/
  );
  const reactImport = TOOL_FORM.slice(TOOL_FORM.indexOf('import {'), TOOL_FORM.indexOf('} from "react";') + 15);
  expect(reactImport, "this form imports no effect hook").not.toContain("useEffect");
  expect(TOOL_FORM, "and reaches for none through a namespace either").not.toMatch(/React\.use(Effect|LayoutEffect)/);
});

test("the toolkit name mirrors into the English name, and stops the moment a person touches it", () => {
  expect(TOOL_FORM, "the latch is a ref").toMatch(/const mirrorArmed = useRef\(/);
  expect(TOOL_FORM, "and it is never state").not.toMatch(/\[\s*mirrorArmed\s*,/);
  const latchAt = TOOL_FORM.indexOf("const mirrorArmed = useRef(");
  const latch = TOOL_FORM.slice(latchAt, TOOL_FORM.indexOf("function applyToolkitName", latchAt));
  expect(latch, "a create arms").toContain("initial ?");
  expect(latch, "an edit arms on a blank stored name").toContain('(initial.englishName ?? "").trim() === ""');
  expect(latch, "or on raw equality — no trim, no case fold").toContain(
    '(initial.englishName ?? "") === (initial.toolkitName ?? "")'
  );

  expect(TOOL_FORM.match(/setToolkitName\(/g)?.length, "exactly one writer of the toolkit name").toBe(1);
  expect(TOOL_FORM, "and it is the helper").toMatch(/function applyToolkitName\([\s\S]{0,200}?setToolkitName\(next\);/);
  expect(TOOL_FORM, "which mirrors only while armed").toMatch(/if \(mirrorArmed\.current\) setEnglishName\(next\);/);
  expect(TOOL_FORM, "the toolkit box goes through it").toContain(
    "onChange={(next) => applyToolkitName(next, { user: true })}"
  );

  const englishAt = TOOL_FORM.indexOf('name="englishName"');
  expect(englishAt, "the English name box must exist").toBeGreaterThan(-1);
  const englishBox = TOOL_FORM.slice(englishAt, TOOL_FORM.indexOf("/>", englishAt));
  expect(englishBox, "it disarms the mirror").toContain("mirrorArmed.current = false;");
  expect(englishBox, "unconditionally — clearing the box is an edit").not.toMatch(/if \(next\)[\s\S]{0,80}mirrorArmed/);
  expect(TOOL_FORM.match(/mirrorArmed\.current = false/g)?.length, "one disarm site, and one only").toBe(1);
});

test("the artisan form collects the craft start date and clears it with an explicit null", () => {
  const at = ARTISAN_FORM.indexOf('name="craftStartDate"');
  expect(at, "there must be a control named craftStartDate").toBeGreaterThan(-1);
  const box = ARTISAN_FORM.slice(ARTISAN_FORM.lastIndexOf("<CalendarField", at), ARTISAN_FORM.indexOf("/>", at) + 2);
  expect(box, "a date, because a stated number of years is wrong within a year").toContain("<CalendarField");
  expect(box, "seeded from the record, trimmed to the yyyy-mm-dd the field understands").toContain(
    'defaultValue={restored.craftStartDate ?? (initial?.craftStartDate ? String(initial.craftStartDate).slice(0, 10) : "")}'
  );
  expect(box, "a craft taken up next year is refused: today, in LOCAL parts").toContain("max={toIsoDate(new Date())}");
  expect(box, "a themed field fires no native input event, so the guard is armed by hand").toContain(
    "onChange={() => markDirty()}"
  );
  expect(ARTISAN_FORM, "not dictated — both readings of an ambiguous spoken date are valid dates").not.toMatch(
    /<DictatedTextInput[\s\S]{0,400}?name="craftStartDate"/
  );

  expect(ARTISAN_FORM, "an empty box clears the column rather than doing nothing").toContain(
    'craftStartDate: textValue(form, "craftStartDate") || null'
  );
});

test("the months box is a closed 0..11 picker and keeps null and zero apart", () => {
  expect(ARTISAN_FORM, "the rows are derived, not typed out").toContain(
    "const EXPERIENCE_MONTH_OPTIONS = Array.from({ length: 12 }, (_, month) => month);"
  );
  const picker = elementFor(ARTISAN_FORM, "experienceMonths", "<Select", "</Select>", "ArtisanForm");
  expect(picker, '"Not stated" is a real row, and it is not "0 months"').toContain('<option value="">Not stated</option>');
  expect(picker, "seeded from a continued draft, else the record — and the record side NOT collapsed with ??: 0 is a real stored answer").toContain(
    'defaultValue={restored.experienceMonths ?? (initial?.experienceMonths != null ? String(initial.experienceMonths) : "")}'
  );
  expect(picker, "a themed dropdown is a <button> and fires no input event").toContain("onChange={markDirty}");
  expect(ARTISAN_FORM, "no microphone on a closed twelve-row picker").not.toMatch(
    /<DictatedTextInput[\s\S]{0,400}?name="experienceMonths"/
  );

  const code = codeOnly(ARTISAN_FORM);
  expect(code, "zero is not nothing").not.toMatch(/experienceMonths[^\n]*\|\|\s*null/);
  expect(code, "the null test is against null, not against truthiness").toContain(
    "experienceMonths: experienceMonthsText === null ? null : Number(experienceMonthsText)"
  );
  expect(code, "and the raw text is read once, so the payload cannot re-test it loosely").toContain(
    'const experienceMonthsText = textValue(form, "experienceMonths");'
  );
});

test("the workshop form can set the workshop kind, with the sibling product's own words", () => {
  expect(WORKSHOPS_PAGE, "both tokens, and only these two").toContain(
    '{ value: "DESIGN_PROTOTYPE", label: "Design & Prototype Development Workshop" }'
  );
  expect(WORKSHOPS_PAGE).toContain('{ value: "OTHER", label: "Other workshop" }');
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

  expect(WORKSHOPS_PAGE, "the kind is part of the one payload both methods send").toContain(
    'workshopType: requiredText(form, "workshopType") || "OTHER"'
  );
  const payload = WORKSHOPS_PAGE.slice(
    WORKSHOPS_PAGE.indexOf("const payload: Record<string, unknown> = {"),
    WORKSHOPS_PAGE.indexOf("if (!editing || !sameIdSet(artisanIds")
  );
  expect(payload, "and it is inside the object literal, not behind an edit branch").toContain("workshopType:");
});

test("the edited flag has three states and draws nothing for the one that cannot answer", () => {
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

  expect(flagText(EditedFlag({ edited: true })), "never a claim that a person typed every character").not.toMatch(
    /typed|wrote/i
  );
  expect(codeOnly(TRANSCRIPT_BLOCK), "the editor is never named, because only an id is knowable").not.toContain(
    "transcriptEditedById"
  );
});

test("the transcript block passes the three-state value and never the two-state one", () => {
  const code = codeOnly(TRANSCRIPT_BLOCK);
  expect(code, "the ternary, which preserves not-stated").toContain(
    "<EditedFlag edited={media.transcriptEditedAt ? true : undefined} at={media.transcriptEditedAt} />"
  );
  expect(code, "never the collapsing reading").not.toMatch(/!!\s*media\.transcriptEditedAt/);
  expect(code, "and the flag's own contract keeps the third state").toContain("if (edited === undefined) return null;");
});

function flagText(node: unknown): string {
  if (node === null || node === undefined || typeof node === "boolean") return "";
  if (typeof node === "string") return node;
  if (typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(flagText).join("");
  const element = node as { props?: { children?: unknown } };
  return element.props ? flagText(element.props.children) : "";
}

test("every required mark in the product comes from one component, and it is red", () => {
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
  expect(REQUIRED_MARK).toContain('<span className="text-error-600 dark:text-red-400"> *</span>');
});

test("every note row gets its own microphone, named so a screen reader hears which", () => {
  const row = FORM_CONTROLS.slice(
    FORM_CONTROLS.indexOf("{notes.map((note, index) => ("),
    FORM_CONTROLS.indexOf("+ Add note")
  );
  expect(row, "the button is inside the note row").toContain("<OnDeviceDictationButton");

  expect(row).toContain("fieldLabel={notes.length > 1 ? `${label}, note ${index + 1}` : label}");
  expect(row).toContain("explainWhenUnavailable={false}");
  expect(row, "the shared joiner, never a local one").toContain("appendDictatedPhrase(n, phrase)");
  expect(FORM_CONTROLS).toContain('import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";');

  const textareaAt = row.indexOf("<textarea");
  const micAt = row.indexOf("<OnDeviceDictationButton");
  const removeAt = row.indexOf("prev.filter((_, j) => j !== index)");
  expect(micAt, "the microphone follows the box").toBeGreaterThan(textareaAt);
  expect(removeAt, "and precedes the remove button").toBeGreaterThan(micAt);

  expect(PROCESS_FORM, "ProcessForm still owns its own per-note button").toContain(
    "fieldLabel={rows.length > 1 ? `${label}, note ${index + 1}` : label}"
  );
});

test("the multi-note mirror stays hidden, and that is not an inconsistency with Select", () => {
  expect(FORM_CONTROLS).toContain('<input type="hidden" name={name} value={joined} />');
  expect(FORM_CONTROLS, "and the Select mirror is still the validating kind").toMatch(
    /type="text"[\s\S]{0,300}?tabIndex=\{-1\}[\s\S]{0,200}?opacity-0/
  );
  expect(FORM_CONTROLS).toContain('.join("\\n\\n")');
});
