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
  appendDictatedPhrase,
  clampToColumn,
  columnFullSentence
} from "@/components/richtext/dictatedValue";
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
const DICTATED_TEXTINPUT = read("components", "richtext", "DictatedTextInput.tsx");
const DICTATED_VALUE = read("components", "richtext", "dictatedValue.ts");
const UNAVAILABLE_NOTICE = read("components", "richtext", "DictationUnavailableNotice.tsx");
const REQUIRED_MARK = read("components", "ui", "RequiredMark.tsx");
const FORM_CONTROLS = read("components", "FormControls.tsx");
const LOCATION_FIELDS = read("components", "forms", "LocationFields.tsx");
const CRAFTS_PAGE = read("app", "(protected)", "crafts", "page.tsx");
const WORKSHOPS_PAGE = read("app", "(protected)", "workshops", "page.tsx");
const MEDIA_PAGE = read("app", "(protected)", "media", "page.tsx");

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
    ["OnDeviceDictationButton.tsx", ON_DEVICE_BUTTON],
    // The three files the record-page sweep added. They are exactly where a network primitive would
    // be added first — a "just send the audio too" one-liner in the one-line box, in the shared
    // value module, or in the notice that already knows whether the browser can dictate.
    ["DictatedTextInput.tsx", DICTATED_TEXTINPUT],
    ["dictatedValue.ts", DICTATED_VALUE],
    ["DictationUnavailableNotice.tsx", UNAVAILABLE_NOTICE]
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

test("the three inline record pages reach the microphone only through the on-device components", () => {
  // `/crafts`, `/workshops` and `/media` build their forms inline rather than through a form
  // component, so they are not covered by the loop above — and all three now carry microphones.
  for (const [name, source] of [
    ["crafts", CRAFTS_PAGE],
    ["workshops", WORKSHOPS_PAGE],
    ["media", MEDIA_PAGE]
  ] as const) {
    expect(source, `${name} must not call the server transcriber`).not.toMatch(/transcribeMediaFile/);
    expect(source, `${name} must not build its own recogniser`).not.toMatch(/SpeechRecognition/);
    expect(source, `${name} must not record audio itself`).not.toMatch(/MediaRecorder|getUserMedia/);
  }
  // And the shared location card, which is mounted by six surfaces and now holds a microphone of its
  // own — one box on six screens, so a transport added here would be added six times.
  expect(LOCATION_FIELDS, "the location card must not call the server transcriber").not.toMatch(/transcribeMediaFile/);
  expect(LOCATION_FIELDS, "nor build its own recogniser").not.toMatch(/SpeechRecognition/);
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

/* ───────────────────────────────────────────────────────────────────────────
 * 2b. The three rules every dictated box obeys — run, not read
 * ─────────────────────────────────────────────────────────────────────────── */

test("the joiner rule is one function and it appends with exactly one space", () => {
  expect(appendDictatedPhrase("", "the warp is sized")).toBe("the warp is sized");
  expect(appendDictatedPhrase("The loom is set.", "The warp is sized.")).toBe("The loom is set. The warp is sized.");
  // A researcher who typed a newline meant that newline: a space appended after it would push the
  // dictated sentence off the line they put it on.
  expect(appendDictatedPhrase("The loom is set.\n", "Then")).toBe("The loom is set.\nThen");
  expect(appendDictatedPhrase("The loom is set. ", "Then")).toBe("The loom is set. Then");

  // And it is the ONLY copy: the two inline ones it replaced must be gone. This assertion used to
  // read the literal `const joiner = …` out of `DictatedTextArea`; it was pinning the RULE, and the
  // rule has not changed, only its address — and it is now executed above rather than grepped.
  expect(DICTATED_TEXTAREA, "the inline joiner moved to dictatedValue").not.toMatch(/const joiner =/);
  expect(PROCESS_FORM, "and so did the process form's").not.toMatch(/const joiner =/);
});

test("the column ceiling is enforced in JS, because a DOM maxLength cannot see a state write", () => {
  // A DOM `maxLength` bounds typing and pasting and has no opinion at all about a value written into
  // React state, which is exactly what a committed phrase is. An over-long value 422s the WHOLE
  // body and `saveOrQueue` will not bank a 4xx, so the rest of the form is gone with it.
  expect(clampToColumn("abcdef", 3)).toBe("abc");
  expect(clampToColumn("abcdef", undefined), "no declared ceiling means no invented one").toBe("abcdef");
  // Said on screen, never silent: a box that quietly stops accepting words is indistinguishable
  // from a microphone that stopped working, and the next move differs completely.
  expect(columnFullSentence(2000)).toContain("2,000");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The sweep: which boxes got what
 * ──────────────────────────────────────────────────────────────────────────── */

test("every qualifying record-form box has a control, and the skipped ones stay skipped", () => {
  /*
    ONE ASSERTION PER DICTATED BOX, BY `name`, because `name` is the half that can go missing without
    anything refusing. `DictatedTextInput.name` is optional — `ProcessForm` genuinely has no
    `FormData` — so on every other surface a dropped attribute submits nothing at all: loud on
    `/workshops` (`requiredText` throws), silent almost everywhere else.

    THE WINDOW IS `\s+` AND NOT A CHARACTER BUDGET. The obvious form,
    `<DictatedTextInput[\s\S]{0,400}?name="place"`, happily matches ACROSS a neighbouring control: the
    regex starts at the first mount on the page and scans four hundred characters, which on these
    forms is two boxes. `name` is the first attribute on every one of these mounts, so requiring it
    to be adjacent is both tighter and true.
  */
  const dictatedInputs: Array<[string, string, string[]]> = [
    ["ArtisanForm", ARTISAN_FORM, ["name", "localName", "newCraftName", "place"]],
    [
      "ProductForm",
      PRODUCT_FORM,
      ["productName", "localName", "craftName", "artisanName", "place", "timeTakenToCompleteProduct", "size"]
    ],
    [
      "ToolForm",
      TOOL_FORM,
      ["toolkitName", "localName", "englishName", "craftName", "artisanName", "place", "processUsedIn", "material"]
    ],
    ["crafts", CRAFTS_PAGE, ["name", "localName", "category", "place"]],
    ["workshops", WORKSHOPS_PAGE, ["title", "place"]],
    ["media", MEDIA_PAGE, ["mediaTitle"]],
    ["LocationFields", LOCATION_FIELDS, ["village"]]
  ];
  for (const [form, source, names] of dictatedInputs) {
    for (const name of names) {
      expect(source, `${form}.${name} must have a microphone AND submit under its own name`).toMatch(
        new RegExp(`<DictatedTextInput\\s+name="${name}"`)
      );
    }
  }
  // ProcessForm is the exception that proves the rule: it builds its body from React state and never
  // constructs a `FormData`, so its two dictated boxes carry an `id` (the focus ladder reaches them
  // by `getElementById`) and deliberately NO `name`.
  expect(PROCESS_FORM, "the process name is reachable by the refusal ladder").toMatch(
    /<DictatedTextInput\s+id="process-name"/
  );
  expect(PROCESS_FORM, "and so is every step name").toMatch(/<DictatedTextInput\s+id=\{`step-name-\$\{step\.key\}`\}/);

  // Multi-line but not narrative — microphone, no formatting.
  expect(ARTISAN_FORM, "an address is spoken, never bolded").toMatch(/<DictatedTextArea[\s\S]{0,300}?name="address"/);
  expect(CRAFTS_PAGE, "a craft description is dictated but never a document").toMatch(
    /<DictatedTextArea[\s\S]{0,300}?name="description"/
  );
  expect(WORKSHOPS_PAGE).toMatch(/<DictatedTextArea[\s\S]{0,300}?name="description"/);
  expect(MEDIA_PAGE, "the caption re-seeds on reset, so it is keyed").toMatch(/<DictatedTextArea\s+key=\{resetNonce\}/);
  expect(PROCESS_FORM, "the step-notes rows get a mic each").toMatch(/<OnDeviceDictationButton/);
  expect(PROCESS_FORM, "every note row is silent; the form says it once at the top").toMatch(
    /explainWhenUnavailable=\{false\}/
  );
  expect(PROCESS_FORM, "no row elects itself to carry the sentence any more").not.toMatch(/index === 0/);

  // Larger narrative boxes — editor with the on-device mic inside it, at the caret.
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
  // And the one that decides four notes are not one note, which sits in the same JSX block as the
  // flag this sweep added and is one careless line away from being dropped.
  expect(ARTISAN_FORM, 'Artisan.notes stays blank-line separated').toMatch(
    /name="notes"[\s\S]{0,200}?join="paragraph"/
  );

  // ── Deliberately untouched, each for a stated rule ────────────────────────────────────
  expect(ARTISAN_FORM, "dos/donts stay the numbered-list control").toMatch(/<DosDontsField/);
  expect(ARTISAN_FORM, "no editor on an identity number").not.toMatch(/<RichTextField[\s\S]{0,200}?name="aadhaarNumber"/);
  expect(ARTISAN_FORM, "no microphone on an identity number").not.toMatch(
    /<DictatedTextInput[\s\S]{0,300}?name="aadhaarNumber"/
  );
  expect(ARTISAN_FORM, "nor on the other one").not.toMatch(/<DictatedTextInput[\s\S]{0,300}?name="pehchanCardNumber"/);
  expect(ARTISAN_FORM, "nor on a date").not.toMatch(/<DictatedTextInput[\s\S]{0,300}?name="dateOfBirth"/);
  expect(ARTISAN_FORM, "nor on an email").not.toMatch(/<DictatedTextInput[\s\S]{0,300}?name="email"/);
  for (const name of ["lengthInches", "breadthInches", "heightInches", "costOfMaking", "sellingPrice"]) {
    expect(PRODUCT_FORM, `${name} is a number box`).not.toMatch(new RegExp(`<DictatedTextInput[\\s\\S]{0,300}?name="${name}"`));
  }
  for (const name of ["yearsInUse", "height", "width", "thickness", "weight", "radius", "replacementCost"]) {
    expect(TOOL_FORM, `${name} is a number box`).not.toMatch(new RegExp(`<DictatedTextInput[\\s\\S]{0,300}?name="${name}"`));
  }
  for (const name of ["pincode", "latitude", "longitude", "altitude", "accuracy"]) {
    expect(LOCATION_FIELDS, `${name} is digits`).not.toMatch(new RegExp(`<DictatedTextInput[\\s\\S]{0,300}?name="${name}"`));
  }
  expect(TOOL_FORM, "processUsedIn is single-line here and stays one").not.toMatch(
    /<RichTextField[\s\S]{0,200}?name="processUsedIn"/
  );
});

test("step notes stay several textareas and never become one document", () => {
  // Two platforms split `ProcessStep.notes` on a blank line (`MultiNoteField` here, `MultiNoteInput`
  // in Android's MainActivity.kt). A document in one of these rows comes back as one note holding
  // JSON. The form now has exactly ONE editor and it is the WHOLE-PROCESS notes column, which nothing
  // splits — the assertion has to tell the two columns apart rather than banning the component.
  expect((PROCESS_FORM.match(/<RichTextField/g) ?? []).length, "exactly one editor on this form").toBe(1);
  expect(PROCESS_FORM, "and it is the process column, not the step column").toMatch(
    /<RichTextField[\s\S]{0,300}?name="notes"[\s\S]{0,300}?onValueChange=\{setNotes\}/
  );
  // It reports through `onValueChange` because this form never builds a `FormData` — and `notes` has
  // to reach `signature`, or the guard cannot see the one box on this form that has no event at all.
  //
  // SLICED RATHER THAN WINDOWED. The first draft of this assertion was
  // `/const signature = JSON\.stringify\(\{[\s\S]{0,600}?\n    notes,/`, and it went red the moment the
  // working tree's line endings became CRLF: every newline inside the window costs two characters
  // instead of one, the real distance is 640, and a passing test became a failing one with no source
  // change at all. A character budget over a multi-line region is a measurement of formatting, not of
  // the rule — so this reads the region and asks the question inside it.
  const signatureBlock = PROCESS_FORM.slice(
    PROCESS_FORM.indexOf("const signature = JSON.stringify({"),
    PROCESS_FORM.indexOf("const [initialSignature]")
  );
  expect(signatureBlock, "the notes value joins the unsaved-changes signature").toMatch(/\n\s*notes,/);
  const multiNote = PROCESS_FORM.slice(
    PROCESS_FORM.indexOf("function MultiNoteInput"),
    PROCESS_FORM.indexOf("export function ProcessForm")
  );
  expect(multiNote, "the step rows are still plain textareas").toMatch(/<textarea/);
  expect(multiNote, "and carry no editor").not.toMatch(/RichTextField/);
});

test("every form that silences its microphones says it once instead", () => {
  for (const [name, source] of [
    ["ArtisanForm", ARTISAN_FORM],
    ["ProductForm", PRODUCT_FORM],
    ["ToolForm", TOOL_FORM],
    ["ProcessForm", PROCESS_FORM],
    ["crafts", CRAFTS_PAGE],
    ["workshops", WORKSHOPS_PAGE],
    ["media", MEDIA_PAGE]
  ] as const) {
    expect(source, `${name} passes explainWhenUnavailable={false}`).toMatch(/explainWhenUnavailable=\{false\}/);
    expect(
      (source.match(/<DictationUnavailableNotice/g) ?? []).length,
      `${name} must say it EXACTLY once — zero is a silent nothing on Firefox, two is the noise the component exists to remove`
    ).toBe(1);
    expect(source, `${name} must never pass true`).not.toMatch(/explainWhenUnavailable=\{true\}/);
  }
  // LocationFields is mounted by six of those forms and carries no notice of its own, by design:
  // the mounting form owns the sentence. Its box must therefore be silent.
  expect(LOCATION_FIELDS).toMatch(/name="village"[\s\S]{0,300}?explainWhenUnavailable=\{false\}/);
  expect(LOCATION_FIELDS).not.toMatch(/<DictationUnavailableNotice/);
  // And the notice decides for itself rather than being told — `!== true`, so "not yet decided"
  // draws nothing rather than being read as "the recogniser is present".
  expect(UNAVAILABLE_NOTICE).toMatch(/if \(absent !== true\) return null;/);
  expect(UNAVAILABLE_NOTICE).toMatch(/setAbsent\(!speechRecognitionConstructor\(\)\)/);
});

test("the zero-size mirrors are untouched by the dictation sweep", () => {
  // A dictated control is never given a mirror and never put behind one: it renders a REAL visible
  // input with the real `name`, `required` and `maxLength`, so there is nothing to mirror. The
  // controls that DO have mirrors are the four on the not-dictated list, and the two rules never
  // meet — a control needs a mirror because it is not a text box, and is dictatable because it is.
  expect(FORM_CONTROLS, "Select's mirror is a real text input, never hidden").toMatch(
    /type="text"[\s\S]{0,300}?tabIndex=\{-1\}[\s\S]{0,200}?opacity-0/
  );
  const dosDonts = read("components", "forms", "DosDontsField.tsx");
  expect(dosDonts, "and DosDontsField's must be a textarea — an input strips CR/LF").toMatch(
    /<textarea\s+name=\{name\}[\s\S]{0,300}?tabIndex=\{-1\}/
  );
  expect(RICH_TEXT_FIELD, "RichTextField's hidden input STAYS — seven call sites read it").toMatch(
    /<input type="hidden" name=\{name\} value=\{submitValue\} \/>/
  );
  expect(RICH_TEXT_FIELD, "and onValueChange is additive, reporting the encoded string").toMatch(
    /onValueChange\?\.\(encoded\)/
  );
  // `noValidate` is a ProcessForm-only exception. Anywhere else it would disarm the mirrors above.
  for (const [name, source] of [
    ["ArtisanForm", ARTISAN_FORM],
    ["ProductForm", PRODUCT_FORM],
    ["ToolForm", TOOL_FORM],
    ["crafts", CRAFTS_PAGE],
    ["workshops", WORKSHOPS_PAGE],
    ["media", MEDIA_PAGE]
  ] as const) {
    expect(source, `${name} keeps the browser's own check`).not.toMatch(/\bnoValidate\b/);
  }
});

test("the required asterisk has one owner, and one colour on screen at a time", () => {
  /*
    THE CENSUS IS THE CONSEQUENT, NOT THE CONDITION. Run as `required ? " *"` it finds five of the
    seven sites: `ArtisanForm` tests `available` and `LocationFields` tests `stateRequired`, and both
    are in files this sweep edits heavily. That miss is how a form ships with two colours of asterisk
    on it while this test passes.
  */
  for (const rel of [
    ["components", "forms", "AadhaarField.tsx"],
    ["components", "forms", "ArtisanForm.tsx"],
    ["components", "forms", "DosDontsField.tsx"],
    ["components", "forms", "LocationFields.tsx"]
  ]) {
    expect(read(...rel), `${rel.join("/")} must not hand-write the mark`).not.toMatch(/\? " \*" : ""/);
  }
  expect(MEDIA_PAGE, "and it is not typed into a label string either").not.toMatch(/label="[^"]*\*"/);

  /*
    THE THREE THAT ARE NOT CONVERTED YET, AND THE RULE THAT KEEPS THE SCREEN COHERENT UNTIL THEY ARE.

    `components/FormControls.tsx`, `components/review/ReviewEditPanel.tsx` and
    `components/tasks/TaskPrimitives.tsx` still write `{required ? " *" : ""}` by hand; they were
    outside the set of files this change could touch. While ANY of them remains, `RequiredMark` must
    inherit its colour, because a red mark on Name and Place beside an ink one on Craft and Status —
    the same artisan form, at the same time — reads as two different kinds of requirement, which is
    a thing this product does not have.

    When the last one is converted this assertion flips and TELLS you to give the component its red
    (`text-error-600 dark:text-red-400`), which is the whole reason the argument for that colour is
    written down in `components/ui/RequiredMark.tsx` rather than lost with this sweep.
  */
  const handWritten = [
    ["components", "FormControls.tsx"],
    ["components", "review", "ReviewEditPanel.tsx"],
    ["components", "tasks", "TaskPrimitives.tsx"]
  ].filter((rel) => /\? " \*" : ""/.test(read(...rel)));
  if (handWritten.length > 0) {
    expect(
      REQUIRED_MARK,
      `${handWritten.map((rel) => rel.join("/")).join(", ")} still draw the mark by hand, so the shared one must inherit`
    ).toMatch(/text-inherit/);
  } else {
    expect(REQUIRED_MARK, "the last hand-written mark is gone — now give the component its red").toMatch(
      /text-error-600 dark:text-red-400/
    );
  }
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
  // The encode rule still runs on everything submitted — it is now named first so the hidden input
  // and the string handed to `onValueChange` can never be two different values.
  expect(RICH_TEXT_FIELD, "everything submitted goes through the encode rule").toMatch(
    /const encoded = encodeStoredRichText\(doc, join\);\s*\n\s*setSubmitValue\(encoded\);/
  );
  // The plain box is a real textarea with a real name, so spellcheck, FormData and `textValue` are
  // untouched — and its commit APPENDS with a space, or a paragraph dictated in five goes runs
  // together.
  expect(DICTATED_TEXTAREA).toMatch(/<textarea[\s\S]{0,300}?name=\{name\}/);

  // The one-line box is caller-controlled, has no second mode, and clamps in JS.
  expect(DICTATED_TEXTINPUT, "no internal state — one mode, not two").not.toMatch(/useState/);
  expect(DICTATED_TEXTINPUT, "the ceiling is enforced in JS, not only by the attribute").toMatch(
    /onChange\(clampToColumn\(next, maxLength\)\)/
  );
  expect(DICTATED_TEXTINPUT, "titleCased mounts the real component, never a copy of its hint").toMatch(
    /\? TitleCasedInput\s*\n?\s*: TextInput/
  );
  // Both draw their own label, because `Field` is a `<label>` and the button under the box is a
  // second control inside it: clicking "Dictate" would then also focus the box and, on a phone,
  // throw the keyboard up over the interim readout the researcher is watching.
  expect(DICTATED_TEXTINPUT, "it draws its own label, because Field is a <label>").toMatch(
    /<label className="field-label" htmlFor=/
  );
  expect(DICTATED_TEXTAREA, "and so does the multi-line one").toMatch(/<label className="field-label" htmlFor=/);
  expect(DICTATED_TEXTAREA, "which now takes required, so a mandatory box stays mandatory").toMatch(
    /required=\{required\}/
  );

  // ProcessForm: the focus ladder reaches two boxes by id — a generated id would land on nothing —
  // and the ladder is the ONLY refusal path, which is what `noValidate` guarantees. Ship both or
  // neither: `required` without `noValidate` gives the submit button a browser bubble and the
  // unsaved-changes dialog's Save button a red paragraph, for the same empty box.
  expect(PROCESS_FORM).toMatch(/id="process-name"/);
  expect(PROCESS_FORM).toMatch(/id=\{`step-name-\$\{step\.key\}`\}/);
  expect(PROCESS_FORM, "one refusal path, for both save buttons").toMatch(/\bnoValidate\b/);
  expect(PROCESS_FORM, "and the ladder it protects is still there").toMatch(
    /document\.getElementById\(focusId\)\?\.focus\(\)/
  );

  // Every surface that clears in place clears its dictated state in the same block.
  expect(MEDIA_PAGE).toMatch(/formElement\.reset\(\);[\s\S]{0,400}?setMediaTitle\(""\)/);
  expect(MEDIA_PAGE).toMatch(/formElement\.reset\(\);[\s\S]{0,400}?setResetNonce\(/);
  expect(CRAFTS_PAGE, "the craft form remounts on every reset, including the offline one").toMatch(
    /key=\{`\$\{editing\?\.id \?\? "new"\}-\$\{formKey\}`\}/
  );
  expect(WORKSHOPS_PAGE, "and so does the workshop form — same key, same trap, same fix").toMatch(
    /key=\{`\$\{editing\?\.id \?\? "new"\}-\$\{formKey\}`\}/
  );
  expect(ARTISAN_FORM, "discardEntry clears the four boxes that live outside the keyed form").toMatch(
    /function discardEntry\(\)[\s\S]{0,800}?setNewCraftName\(""\)/
  );
  // A dictated village must arm the guard: typing bubbled an `input` event into the form's
  // `onInput={markDirty}`, and a state write does not. This box had never called `onDirty` at all.
  expect(LOCATION_FIELDS, "a dictated village arms the unsaved-changes guard").toMatch(
    /name="village"[\s\S]{0,500}?onDirty\?\.\(\)/
  );
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
 *  - that this file is RUN. `frontend/package.json` now declares `test:unit`, but no workflow invokes
 *    it: `.github/workflows/` holds android-build, deploy-backend, deploy-frontend and
 *    keep-supabase-active. Until one of them runs `npm run test:unit`, every assertion here is a note
 *    to whoever remembers to type the command — and a spec that is DELETED is as green as a spec
 *    that passes.
 *  - that the seven per-form DICTATION registers agree with the assertions above. The registers are
 *    prose and this file is regex; only a human reading both keeps them in step. A box whose
 *    microphone is removed leaves a register claiming it has one, and the register is the only thing
 *    that distinguishes a decision from an oversight.
 *  - that a REMOUNT actually clears a box. The `key=` assertions prove the key is WRITTEN, not that
 *    React tears the component down — that needs a browser. The two bugs this commit fixes (the
 *    crafts and workshops form keys) are both of that shape, and a regex is the weakest possible
 *    guard on them.
 *  - ANYTHING ON `/questionnaire`. That page was owned by another lane while this sweep ran and is
 *    untouched here: its three header boxes, its per-answer microphones and the Builder's
 *    add-question box (which needs a per-section nonce, or `formElement.reset()` leaves the prompt
 *    in the box and the next press files the SAME question again) are all still to do. There are
 *    deliberately NO assertions about that file above, because an assertion about work nobody has
 *    done is a red spec, not a reminder — this paragraph is the reminder.
 *  - that `MultiNoteField`'s note rows have a microphone. They DID NOT when this file was written —
 *    that control lives in `components/FormControls.tsx`, which was outside this change — and they
 *    do now: the record-parity sweep added the per-note button, and
 *    `e2e/record-parity-fields-unit.spec.ts` is what holds it and its three arguments in place. This
 *    bullet is kept rather than deleted because the gap it describes was real and the reasoning for
 *    closing it is worth being able to find; what is NOT still true is the claim in its last
 *    sentence, and `/questionnaire` remains the one mount site with no `DictationUnavailableNotice`
 *    over it, which the record-parity spec asserts as a named gap rather than a rule.
 */
