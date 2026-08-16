import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  createBrowserRecognition,
  describeSpeechError,
  DICTATION_LANGUAGES,
  NO_RECOGNISER_SENTENCE,
  startRecognition,
  type SpeechRecognitionLike
} from "@/components/dictation/onDeviceSpeech";
import {
  appendStoredParagraph,
  decodeStoredRichText,
  encodeStoredRichText
} from "@/components/richtext/storedRichText";
import { fromStored, toStored, toPlain } from "@/lib/richText";

/**
 * DICTATION AND RICH TEXT ON THE RECORD FORMS — and the two things that must NOT have moved.
 *
 * The requirement was "the dictate option along with the rich text formatting for the bigger fields
 * should be there", refined to: on-device recognition ONLY (no server transcription from a record
 * form), rich text on the LARGER boxes only, and no column migration — every affected column stays
 * `String?` and every existing reader keeps working.
 *
 * That shape creates exactly three ways to ship a silent defect, and this spec is one section per
 * way:
 *
 *   1. **A voice recording leaving the device from a record form.** This backend already has an
 *      audio-transcription door — `POST /media/transcribe`, gated on nothing but `get_current_user`
 *      — which exists for the questionnaire's section-audio workflow, where the subject is told, the
 *      clip is stored as a listed `MediaFile`, and it can be deleted. A microphone beside the
 *      artisan's Notes box has none of that around it. So the record-form control must have no
 *      transport AT ALL, and that is asserted by reading its source for the network primitives,
 *      because "it happens not to call fetch today" is not a property a type can carry.
 *   2. **A document stringified into a searched column.** `artisans.py:229`, `products.py:89-91` and
 *      `tools.py:99` run raw Prisma `contains` clauses against exactly the columns this change
 *      touches, `cell()` at `record_fields.py:102` drops them straight into the CSV and XLSX
 *      surfaces, and — the part that is worse here than in the application this code came from —
 *      **the backend has no rich-text model at all.** The only readers of a stored document in this
 *      repository are `lib/richText.ts` here and the Android port in `android/…/ui/richtext/`, both
 *      of which arrived with this change and both of which apply the same plain-when-plain rule.
 *      Nothing on the server does. `encodeStoredRichText` therefore writes prose whenever the
 *      document is expressible as prose, and this spec pins that rule from both directions. The
 *      Kotlin half of the same rule is `isPlainProse` / `recordStoredFromDoc` in
 *      `android/…/ui/RecordProseText.kt`; if you change a rule here, change it there in the same
 *      breath, or a field edited on a phone reads as changed the instant a browser reopens it.
 *   3. **The multi-note contract being broken by the new controls.** `Artisan.notes` and
 *      `ProcessStep.notes` are blank-line separated and SPLIT back apart by `MultiNoteField` here
 *      and `MultiNoteInput` in Android's `MainActivity.kt`. Single newlines in either column
 *      silently collapse several notes into one, on a handset, days later.
 *
 * WHY A NODE SPEC AND NOT A BROWSER RUN. This repository has no React renderer in its
 * devDependencies — Playwright is the whole of it — so mounting a component is not available, and
 * `record-pickers-unit.spec.ts` reads its structural half out of source for exactly this reason.
 * The encode/decode half and the recogniser half here are genuinely EXECUTED against a fake
 * recogniser. What none of it proves is that a browser paints the microphone; that gap is named at
 * the bottom of the file rather than left to be rediscovered.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const ON_DEVICE_SPEECH = read("components", "dictation", "onDeviceSpeech.ts");
const ON_DEVICE_BUTTON = read("components", "dictation", "OnDeviceDictationButton.tsx");
const EDITOR = read("components", "richtext", "RichTextEditor.tsx");
const RICH_TEXT_FIELD = read("components", "richtext", "RichTextField.tsx");
const DICTATED_TEXTAREA = read("components", "richtext", "DictatedTextArea.tsx");
const ARTISAN_FORM = read("components", "forms", "ArtisanForm.tsx");
const PRODUCT_FORM = read("components", "forms", "ProductForm.tsx");
const TOOL_FORM = read("components", "forms", "ToolForm.tsx");
const PROCESS_FORM = read("components", "forms", "ProcessForm.tsx");

/** Comments in these files DISCUSS the server door at length — that is the point of them. */
function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Nothing a record form hears may leave the device
 * ──────────────────────────────────────────────────────────────────────────── */

test("the on-device dictation path contains no transport of any kind", () => {
  for (const [name, source] of [
    ["onDeviceSpeech.ts", ON_DEVICE_SPEECH],
    ["OnDeviceDictationButton.tsx", ON_DEVICE_BUTTON]
  ] as const) {
    const code = codeOnly(source);
    expect(code, `${name} must not fetch`).not.toMatch(/\bfetch\s*\(/);
    expect(code, `${name} must not record audio`).not.toMatch(/MediaRecorder/);
    expect(code, `${name} must not open a microphone stream`).not.toMatch(/getUserMedia/);
    expect(code, `${name} must not reach the media library`).not.toMatch(/@\/lib\/media/);
    expect(code, `${name} must not name the transcription route`).not.toMatch(/transcribe/i);
    expect(code, `${name} must not import the API client`).not.toMatch(/@\/lib\/api/);
  }
});

test("the four record forms reach the microphone only through the on-device components", () => {
  for (const [name, source] of [
    ["ArtisanForm", ARTISAN_FORM],
    ["ProductForm", PRODUCT_FORM],
    ["ToolForm", TOOL_FORM],
    ["ProcessForm", PROCESS_FORM]
  ] as const) {
    // `transcribeMediaFile` is the questionnaire's path to `POST /media/transcribe`. It is a real
    // export of `lib/media` that these four files already import from, so importing it by accident
    // is one autocomplete away — which is exactly why it is asserted rather than assumed.
    expect(source, `${name} must not call the server transcriber`).not.toMatch(/transcribeMediaFile/);
    expect(source, `${name} must not build its own recogniser`).not.toMatch(/SpeechRecognition/);
    expect(source, `${name} must not record audio itself`).not.toMatch(/MediaRecorder|getUserMedia/);
  }
});

test("the editor hosts the on-device button and has no id-shaped prop to route it elsewhere", () => {
  // The application this editor was ported from selects between two dictation components on the
  // presence of a `workshopId`. There is no consent-gated route in this backend to be the other
  // half of that choice, so the prop is gone and the on-device button is unconditional. A prop
  // reappearing here is how a server rung would sneak back in.
  expect(EDITOR, "no workshop id on this editor").not.toMatch(/workshopId/);
  expect(EDITOR, "the on-device button is mounted directly").toMatch(/<OnDeviceDictationButton fieldLabel=/);
  expect((EDITOR.match(/<OnDeviceDictationButton/g) ?? []).length, "exactly one microphone in the editor").toBe(1);
  // And it is the ONLY dictation thing this file imports. A second import line is how a control
  // with a transport would arrive, and it would not otherwise show up in any assertion above.
  expect(EDITOR.match(/^import .*Dictation.*$/gm) ?? []).toEqual([
    'import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";'
  ]);
  // One insertion path, named, so a fix to caret handling has one place to land.
  expect(EDITOR).toMatch(/const commitDictated = useCallback\(/);
  expect(EDITOR, "a dictated phrase is inserted at the caret, never appended as a string").toMatch(
    /insertText\(docRef\.current, range, `\$\{joiner\}\$\{phrase\}`, marks\)/
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The columns stay plain-compatible
 * ──────────────────────────────────────────────────────────────────────────── */

test("an unformatted document is stored as the prose it is, byte for byte", () => {
  const typed = fromStored("The warp is sized with rice starch.\nThe weft is undyed.");
  expect(encodeStoredRichText(toStored(typed))).toBe(
    "The warp is sized with rice starch.\nThe weft is undyed."
  );
  // Which is exactly what `contains` search, `cell()` and the Android form would have read out of
  // the column before this change existed.
  expect(encodeStoredRichText(toStored(typed))).toBe(toPlain(typed));
});

test("a FORMATTED document is stored as JSON, and comes back as the same document", () => {
  const bolded = toStored(
    fromStored({ blocks: [{ kind: "PARAGRAPH", spans: [{ text: "Sized", marks: ["BOLD"] }, { text: " with starch." }] }] })
  );
  const stored = encodeStoredRichText(bolded);
  expect(stored.startsWith("{"), "a formatted document has to be encoded").toBeTruthy();

  const reopened = decodeStoredRichText(stored);
  expect(typeof reopened, "the editor must be handed an object, never the JSON string").toBe("object");
  expect(toStored(fromStored(reopened))).toEqual(bolded);
});

test("a table, an image, a heading, a list, a quote and an alignment each force the encoded form", () => {
  // The polarity that matters: a NEW block kind must default to "store as JSON". Storing it as prose
  // would flatten a researcher's table into pipe-separated lines on save, and they would not find
  // out until somebody read the record back.
  const cases: Array<[string, unknown]> = [
    ["heading", { blocks: [{ kind: "HEADING", level: 2, spans: [{ text: "Dyeing" }] }] }],
    ["bullet", { blocks: [{ kind: "BULLET_ITEM", spans: [{ text: "Indigo" }] }] }],
    ["quote", { blocks: [{ kind: "QUOTE", spans: [{ text: "As my father did." }] }] }],
    ["align", { blocks: [{ kind: "PARAGRAPH", align: "CENTER", spans: [{ text: "Centred" }] }] }],
    ["table", { blocks: [{ kind: "TABLE", spans: [], rows: [[[{ text: "a" }], [{ text: "b" }]]] }] }],
    ["image", { blocks: [{ kind: "IMAGE", media: "media-1", widthPct: 70, spans: [{ text: "The loom" }] }] }]
  ];
  for (const [label, doc] of cases) {
    expect(encodeStoredRichText(toStored(fromStored(doc))).startsWith("{"), `${label} must be encoded`).toBeTruthy();
  }
});

test("decoding leaves prose alone, including prose that merely begins with a brace", () => {
  expect(decodeStoredRichText(null)).toBeNull();
  expect(decodeStoredRichText("Plain notes.")).toBe("Plain notes.");
  expect(decodeStoredRichText("{not json at all")).toBe("{not json at all");
  // Valid JSON, but not a block document. Read as what it is rather than as an empty document,
  // which is how a pasted configuration snippet in a notes box would otherwise be silently blanked.
  expect(decodeStoredRichText('{"a":1}')).toBe('{"a":1}');
});

test('a multi-note column keeps its blank-line contract under join="paragraph"', () => {
  // `MultiNoteField` in `components/FormControls.tsx` and `MultiNoteInput` in Android's
  // MainActivity.kt both split this column on blank lines. Single newlines would silently collapse three notes into one the
  // next time the record was opened on a handset.
  const doc = toStored(fromStored("Note one.\nNote two.\nNote three."));
  expect(encodeStoredRichText(doc, "paragraph")).toBe("Note one.\n\nNote two.\n\nNote three.");
  expect(encodeStoredRichText(doc, "line")).toBe("Note one.\nNote two.\nNote three.");
});

test("the EXIF remark is appended INTO a document, and onto prose exactly as it always was", () => {
  // The prose branch must be byte-for-byte `appendRemarksWithExif`, or every unformatted record
  // changes shape on its next save for no reason.
  expect(appendStoredParagraph("Woven in March.", "Photo 1 · 2026-03-04")).toBe(
    "Woven in March.\n\nPhoto 1 · 2026-03-04"
  );
  expect(appendStoredParagraph(null, "Photo 1")).toBe("Photo 1");
  expect(appendStoredParagraph("Woven in March.", "")).toBe("Woven in March.");
  expect(appendStoredParagraph(null, "")).toBeNull();

  // The document branch is the bug this helper exists to prevent: string-concatenating onto JSON
  // produces a value that is neither valid JSON nor readable prose.
  const stored = encodeStoredRichText(
    toStored(fromStored({ blocks: [{ kind: "PARAGRAPH", spans: [{ text: "Woven", marks: ["BOLD"] }] }] }))
  );
  const appended = appendStoredParagraph(stored, "Photo 1\nPhoto 2");
  expect(appended?.startsWith("{"), "still a document").toBeTruthy();
  expect(toPlain(fromStored(decodeStoredRichText(appended)))).toBe("Woven\nPhoto 1\nPhoto 2");
});

test("the three forms that machine-append EXIF use the document-aware helper", () => {
  for (const [name, source] of [
    ["ArtisanForm", ARTISAN_FORM],
    ["ProductForm", PRODUCT_FORM],
    ["ToolForm", TOOL_FORM]
  ] as const) {
    expect(source, `${name} still concatenates onto a possibly-JSON column`).not.toMatch(/appendRemarksWithExif\(/);
    expect(source, `${name} must append through appendStoredParagraph`).toMatch(/appendStoredParagraph\(/);
  }
  // And the artisan column is the blank-line one, so its append has to say so.
  expect(ARTISAN_FORM, "Artisan.notes is blank-line separated").toMatch(
    /appendStoredParagraph\(textValue\(form, "notes"\) as string \| null, exifRemark, "paragraph"\)/
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * The shared recogniser lifecycle, actually driven
 * ──────────────────────────────────────────────────────────────────────────── */

/** The four callbacks a `SpeechRecognition` fires, under test control. */
class FakeRecognition implements SpeechRecognitionLike {
  lang = "";
  continuous = false;
  interimResults = false;
  maxAlternatives = 0;
  started = 0;
  stopped = 0;
  throwOnStart = false;
  onresult: SpeechRecognitionLike["onresult"] = null;
  onerror: SpeechRecognitionLike["onerror"] = null;
  onend: SpeechRecognitionLike["onend"] = null;
  onaudiostart: SpeechRecognitionLike["onaudiostart"] = null;
  start() {
    this.started += 1;
    if (this.throwOnStart) throw new Error("InvalidStateError");
  }
  stop() {
    this.stopped += 1;
  }
  abort() {
    this.stopped += 1;
  }
}

let latest: FakeRecognition | null = null;

test.beforeEach(() => {
  latest = null;
  (globalThis as unknown as { window: unknown }).window = {
    SpeechRecognition: function () {
      latest = new FakeRecognition();
      return latest;
    }
  };
});

test.afterEach(() => {
  delete (globalThis as unknown as { window?: unknown }).window;
});

function harness() {
  const phrases: string[] = [];
  const interims: string[] = [];
  const problems: string[] = [];
  const stops: string[] = [];
  const recognition = createBrowserRecognition({
    language: "or-IN",
    onPhrase: (t) => phrases.push(t),
    onInterim: (t) => interims.push(t),
    onProblem: (s) => problems.push(s),
    onStopped: (r) => stops.push(r)
  });
  expect(recognition).not.toBeNull();
  return { recognition: recognition as SpeechRecognitionLike, phrases, interims, problems, stops };
}

test("only FINAL results are committed, and interims are drawn separately", () => {
  const { recognition, phrases, interims } = harness();
  expect(recognition.lang).toBe("or-IN");
  expect(recognition.continuous, "a spoken paragraph has pauses in it").toBeTruthy();
  expect(recognition.interimResults, "without this the button looks dead for three seconds").toBeTruthy();

  recognition.onresult?.({
    resultIndex: 0,
    results: { length: 1, 0: { isFinal: false, length: 1, 0: { transcript: "the wharf is", confidence: 0.4 } } }
  });
  recognition.onresult?.({
    resultIndex: 0,
    results: { length: 1, 0: { isFinal: true, length: 1, 0: { transcript: " the warp is sized ", confidence: 0.9 } } }
  });
  expect(phrases, "the recogniser's first guess must never be committed").toEqual(["the warp is sized"]);
  expect(interims).toEqual(["the wharf is", ""]);
});

test("iteration starts at resultIndex, so a long dictation does not repeat itself", () => {
  const { recognition, phrases } = harness();
  recognition.onresult?.({
    resultIndex: 1,
    results: {
      length: 2,
      0: { isFinal: true, length: 1, 0: { transcript: "already committed", confidence: 1 } },
      1: { isFinal: true, length: 1, 0: { transcript: "and this one", confidence: 1 } }
    }
  });
  expect(phrases).toEqual(["and this one"]);
});

test("a deliberate stop says nothing; the four real failures each say something different", () => {
  const { recognition, problems, stops } = harness();
  recognition.onerror?.({ error: "aborted" });
  expect(problems, "narrating every normal stop as a failure trains people to ignore the line").toEqual([]);
  expect(stops).toEqual(["error"]);

  recognition.onerror?.({ error: "not-allowed" });
  recognition.onerror?.({ error: "no-speech" });
  recognition.onerror?.({ error: "audio-capture" });
  recognition.onerror?.({ error: "network" });
  expect(problems.length).toBe(4);
  expect(new Set(problems).size, "four distinct next moves, not one catch-all").toBe(4);
  expect(problems[0]).toContain("Allow it for this site");
  expect(problems[2]).toContain("No microphone was found");

  // The unknown case still names the code rather than saying "dictation failed".
  expect(describeSpeechError("service-not-allowed")).toBe(describeSpeechError("not-allowed"));
  expect(describeSpeechError("wobbly")).toContain("(wobbly)");
});

test("onend releases the handle and onerror does not, because onend follows onerror", () => {
  const { recognition, stops } = harness();
  recognition.onerror?.({ error: "network" });
  recognition.onend?.();
  expect(stops).toEqual(["error", "end"]);
  expect(ON_DEVICE_BUTTON, "only the end branch may null the ref").toMatch(
    /if \(reason === "end"\) recognitionRef\.current = null;/
  );
});

test("Safari's double-tap throw is reported, so the button cannot stick on Stop", () => {
  const { recognition } = harness();
  expect(startRecognition(recognition)).toBeTruthy();
  (recognition as FakeRecognition).throwOnStart = true;
  expect(startRecognition(recognition), "false is how the caller knows to put the button back").toBeFalsy();
  expect(latest?.started).toBe(2);
  expect(ON_DEVICE_BUTTON, "and the caller must actually put it back").toMatch(
    /if \(!startRecognition\(recognition\)\) \{\s*setListening\(false\);/
  );
});

test("stopping uses stop() and only an unmount aborts, so the last sentence is never thrown away", () => {
  // `abort()` discards the phrase the recogniser is still holding. That is right on unmount, where
  // there is no field left for it to land in, and wrong everywhere else.
  expect(ON_DEVICE_SPEECH, "stopRecognition must not abort").toMatch(/export function stopRecognition[\s\S]{0,120}recognition\?\.stop\(\)/);
  expect(ON_DEVICE_BUTTON, "the teardown is the one place abort belongs").toMatch(
    /const teardown = useCallback\(\(\) => \{\s*recognitionRef\.current\?\.abort\(\);/
  );
});

test("the whole control is withheld, with a reason, where the browser has no recogniser", () => {
  delete (globalThis as unknown as { window?: unknown }).window;
  expect(createBrowserRecognition({ language: "en-IN", onPhrase: () => {}, onInterim: () => {}, onProblem: () => {}, onStopped: () => {} })).toBeNull();

  // And the sentence that is drawn instead is not a disabled button. This repository's most-repeated
  // defect is a control that appears to work and does nothing; a dead microphone on Firefox would be
  // exactly that, and hiding it with no explanation reads as a broken build.
  expect(NO_RECOGNISER_SENTENCE).toContain("Firefox");
  expect(NO_RECOGNISER_SENTENCE).toContain("Type the answer in");
  expect(ON_DEVICE_BUTTON, "the absent branch must render the sentence, not a disabled button").toMatch(
    /availability === "absent"[\s\S]{0,600}?\{NO_RECOGNISER_SENTENCE\}/
  );
  expect(ON_DEVICE_BUTTON, "and it must not draw a disabled mic instead").not.toMatch(/disabled=\{true\}/);
  // Detected in an effect, never during render: `window` does not exist on the server, and deciding
  // in render produces different markup on the two sides and a hydration mismatch across the form.
  expect(ON_DEVICE_BUTTON, "availability starts unknown and draws nothing").toMatch(
    /if \(availability === "unknown"\) return null;/
  );
  expect(ON_DEVICE_BUTTON, "and is decided inside an effect").toMatch(
    /useEffect\(\(\) => \{[\s\S]{0,200}?setAvailability\(speechRecognitionConstructor\(\) \? "ready" : "absent"\);/
  );
});

test("the language list is shared and remembered under one key", () => {
  expect(DICTATION_LANGUAGES.map((entry) => entry.value)).toContain("or-IN");
  expect(DICTATION_LANGUAGES.length, "eleven languages, English first").toBe(11);
  expect(DICTATION_LANGUAGES[0].value).toBe("en-IN");
  expect(ON_DEVICE_SPEECH, "one key for the whole application — re-picking Odia per form is the defect").toContain(
    '"field_repo_dictation_language"'
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The sweep: which boxes got what
 * ──────────────────────────────────────────────────────────────────────────── */

test("every qualifying record-form box has a control, and the skipped ones stay skipped", () => {
  // Larger narrative boxes — editor with the on-device mic inside it.
  for (const name of ["notes"]) expect(ARTISAN_FORM).toMatch(new RegExp(`<RichTextField[\\s\\S]{0,200}?name="${name}"`));
  for (const name of ["rawMaterialsUsed", "mainToolsUsed", "productFunctionUse", "remarks"]) {
    expect(PRODUCT_FORM, `${name} must have the editor`).toMatch(new RegExp(`<RichTextField[\\s\\S]{0,200}?name="${name}"`));
    expect(PRODUCT_FORM, `${name} must arm the unsaved-changes guard`).toMatch(
      new RegExp(`name="${name}"[\\s\\S]{0,300}?onDirty`)
    );
  }
  for (const name of ["suggestionsForToolImprovement", "remarks"]) {
    expect(TOOL_FORM).toMatch(new RegExp(`<RichTextField[\\s\\S]{0,200}?name="${name}"`));
  }

  // Multi-line but not narrative — microphone, no formatting.
  expect(ARTISAN_FORM, "an address is spoken, never bolded").toMatch(/<DictatedTextArea[\s\S]{0,200}?name="address"/);
  expect(PROCESS_FORM, "the step-notes rows get a mic each").toMatch(/<OnDeviceDictationButton/);
  expect(PROCESS_FORM, "and the explanation is carried once, by the first row").toMatch(
    /explainWhenUnavailable=\{index === 0\}/
  );

  // Deliberately untouched. Each of these is recorded in the report with its reason; the assertions
  // are here so that "somebody adds a toolbar to the Aadhaar box" fails a test rather than a review.
  expect(ARTISAN_FORM, "dos/donts stay the numbered-list control").toMatch(/<DosDontsField/);
  expect(ARTISAN_FORM, "no editor on an identity number").not.toMatch(/<RichTextField[\s\S]{0,200}?name="aadhaarNumber"/);
  expect(PROCESS_FORM, "step notes must NOT become a document — two platforms split that column").not.toMatch(
    /<RichTextField/
  );
  expect(TOOL_FORM, "processUsedIn is a single-line input here and stays one").not.toMatch(
    /<RichTextField[\s\S]{0,200}?name="processUsedIn"/
  );
});

test("the mount components keep the contracts the forms depend on", () => {
  // `FormData` cannot see a contenteditable. The hidden input under the same `name` is the whole
  // reason no payload builder had to change.
  expect(RICH_TEXT_FIELD, "the form reads the value through a hidden input, as before").toMatch(
    /<input type="hidden" name=\{name\} value=\{submitValue\} \/>/
  );
  expect(RICH_TEXT_FIELD, "the editor's live document is never fed back in as `value`").toMatch(
    /const \[initialValue\] = useState<unknown>\(\(\) => decodeStoredRichText\(defaultValue\)\)/
  );
  expect(RICH_TEXT_FIELD, "everything submitted goes through the encode rule").toMatch(
    /setSubmitValue\(encodeStoredRichText\(doc, join\)\)/
  );
  // The plain box is a real textarea with a real name, so spellcheck, FormData and `textValue` are
  // untouched — and its commit APPENDS with a space, or a paragraph dictated in five goes runs
  // together.
  expect(DICTATED_TEXTAREA).toMatch(/<textarea[\s\S]{0,300}?name=\{name\}/);
  expect(DICTATED_TEXTAREA).toMatch(/const joiner = !value \|\| \/\\s\$\/\.test\(value\) \? "" : " ";/);
});

/*
 * NOT PROVEN HERE, and worth naming rather than leaving as a gap somebody rediscovers:
 *
 *  - that a browser PAINTS the microphone on `/artisans/new`, that pressing it opens the recogniser,
 *    and that a dictated phrase lands at the caret. That needs the signed-in dev server plus an
 *    `addInitScript` stub of `window.SpeechRecognition`, because headless Chromium ships no real
 *    one. The `data-dictation-on-device` attribute on the button exists to be the handle for it.
 *  - that the SERVER can read a stored document. It cannot: there is no rich-text model in
 *    `backend/`, so `cell()` hands one to a CSV verbatim. That is precisely why
 *    `encodeStoredRichText` writes prose for everything except a deliberately formatted field — see
 *    the header of `components/richtext/storedRichText.ts` for the bounded cost that leaves, and
 *    port the model to Python before removing the condition.
 *  - that this spec and the Android lane's equivalent agree. They are asserted separately, in two
 *    languages, against two implementations of one rule, and only a human reading both keeps them
 *    in step. Nothing in either build fails when they drift.
 */
