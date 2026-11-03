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

function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

test("the on-device dictation path contains no transport of any kind", () => {
  for (const [name, source] of [
    ["onDeviceSpeech.ts", ON_DEVICE_SPEECH],
    ["OnDeviceDictationButton.tsx", ON_DEVICE_BUTTON],
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
    expect(source, `${name} must not call the server transcriber`).not.toMatch(/transcribeMediaFile/);
    expect(source, `${name} must not build its own recogniser`).not.toMatch(/SpeechRecognition/);
    expect(source, `${name} must not record audio itself`).not.toMatch(/MediaRecorder|getUserMedia/);
  }
});

test("the three inline record pages reach the microphone only through the on-device components", () => {
  for (const [name, source] of [
    ["crafts", CRAFTS_PAGE],
    ["workshops", WORKSHOPS_PAGE],
    ["media", MEDIA_PAGE]
  ] as const) {
    expect(source, `${name} must not call the server transcriber`).not.toMatch(/transcribeMediaFile/);
    expect(source, `${name} must not build its own recogniser`).not.toMatch(/SpeechRecognition/);
    expect(source, `${name} must not record audio itself`).not.toMatch(/MediaRecorder|getUserMedia/);
  }
  expect(LOCATION_FIELDS, "the location card must not call the server transcriber").not.toMatch(/transcribeMediaFile/);
  expect(LOCATION_FIELDS, "nor build its own recogniser").not.toMatch(/SpeechRecognition/);
});

test("the editor hosts the on-device button and has no id-shaped prop to route it elsewhere", () => {
  expect(EDITOR, "no workshop id on this editor").not.toMatch(/workshopId/);
  expect(EDITOR, "the on-device button is mounted directly").toMatch(/<OnDeviceDictationButton fieldLabel=/);
  expect((EDITOR.match(/<OnDeviceDictationButton/g) ?? []).length, "exactly one microphone in the editor").toBe(1);
  expect(EDITOR.match(/^import .*Dictation.*$/gm) ?? []).toEqual([
    'import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";'
  ]);
  expect(EDITOR).toMatch(/const commitDictated = useCallback\(/);
  expect(EDITOR, "a dictated phrase is inserted at the caret, never appended as a string").toMatch(
    /insertText\(docRef\.current, range, `\$\{joiner\}\$\{phrase\}`, marks\)/
  );
});

test("an unformatted document is stored as the prose it is, byte for byte", () => {
  const typed = fromStored("The warp is sized with rice starch.\nThe weft is undyed.");
  expect(encodeStoredRichText(toStored(typed))).toBe(
    "The warp is sized with rice starch.\nThe weft is undyed."
  );
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
  expect(decodeStoredRichText('{"a":1}')).toBe('{"a":1}');
});

test('a multi-note column keeps its blank-line contract under join="paragraph"', () => {
  const doc = toStored(fromStored("Note one.\nNote two.\nNote three."));
  expect(encodeStoredRichText(doc, "paragraph")).toBe("Note one.\n\nNote two.\n\nNote three.");
  expect(encodeStoredRichText(doc, "line")).toBe("Note one.\nNote two.\nNote three.");
});

test("the EXIF remark is appended INTO a document, and onto prose exactly as it always was", () => {
  expect(appendStoredParagraph("Woven in March.", "Photo 1 · 2026-03-04")).toBe(
    "Woven in March.\n\nPhoto 1 · 2026-03-04"
  );
  expect(appendStoredParagraph(null, "Photo 1")).toBe("Photo 1");
  expect(appendStoredParagraph("Woven in March.", "")).toBe("Woven in March.");
  expect(appendStoredParagraph(null, "")).toBeNull();

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
  expect(ARTISAN_FORM, "Artisan.notes is blank-line separated").toMatch(
    /appendStoredParagraph\(textValue\(form, "notes"\) as string \| null, exifRemark, "paragraph"\)/
  );
});

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
  expect(problems[0]).toContain("Allow it in the address bar");
  expect(problems[2]).toContain("No microphone found");

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
  expect(ON_DEVICE_SPEECH, "stopRecognition must not abort").toMatch(/export function stopRecognition[\s\S]{0,120}recognition\?\.stop\(\)/);
  expect(ON_DEVICE_BUTTON, "the teardown is the one place abort belongs").toMatch(
    /const teardown = useCallback\(\(\) => \{\s*recognitionRef\.current\?\.abort\(\);/
  );
});

test("the whole control is withheld, with a reason, where the browser has no recogniser", () => {
  delete (globalThis as unknown as { window?: unknown }).window;
  expect(createBrowserRecognition({ language: "en-IN", onPhrase: () => {}, onInterim: () => {}, onProblem: () => {}, onStopped: () => {} })).toBeNull();

  expect(NO_RECOGNISER_SENTENCE).toContain("Use Chrome, Edge or Safari");
  expect(NO_RECOGNISER_SENTENCE).toContain("type instead");
  expect(ON_DEVICE_BUTTON, "the absent branch must render the sentence, not a disabled button").toMatch(
    /availability === "absent"[\s\S]{0,600}?\{NO_RECOGNISER_SENTENCE\}/
  );
  expect(ON_DEVICE_BUTTON, "and it must not draw a disabled mic instead").not.toMatch(/disabled=\{true\}/);
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

test("the joiner rule is one function and it appends with exactly one space", () => {
  expect(appendDictatedPhrase("", "the warp is sized")).toBe("the warp is sized");
  expect(appendDictatedPhrase("The loom is set.", "The warp is sized.")).toBe("The loom is set. The warp is sized.");
  expect(appendDictatedPhrase("The loom is set.\n", "Then")).toBe("The loom is set.\nThen");
  expect(appendDictatedPhrase("The loom is set. ", "Then")).toBe("The loom is set. Then");

  expect(DICTATED_TEXTAREA, "the inline joiner moved to dictatedValue").not.toMatch(/const joiner =/);
  expect(PROCESS_FORM, "and so did the process form's").not.toMatch(/const joiner =/);
});

test("the column ceiling is enforced in JS, because a DOM maxLength cannot see a state write", () => {
  expect(clampToColumn("abcdef", 3)).toBe("abc");
  expect(clampToColumn("abcdef", undefined), "no declared ceiling means no invented one").toBe("abcdef");
  expect(columnFullSentence(2000)).toContain("2,000");
});

test("every qualifying record-form box has a control, and the skipped ones stay skipped", () => {
  const dictatedInputs: Array<[string, string, string[]]> = [
    ["ArtisanForm", ARTISAN_FORM, ["name", "localName", "place"]],
    [
      "ProductForm",
      PRODUCT_FORM,
      ["productName", "localName", "artisanName", "place", "timeTakenToCompleteProduct", "size"]
    ],
    [
      "ToolForm",
      TOOL_FORM,
      ["toolkitName", "localName", "englishName", "artisanName", "place", "processUsedIn", "material"]
    ],
    ["crafts", CRAFTS_PAGE, ["name", "localName"]],
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
  expect(PROCESS_FORM, "the process name is reachable by the refusal ladder").toMatch(
    /<DictatedTextInput\s+id="process-name"/
  );
  expect(PROCESS_FORM, "and so is every step name").toMatch(/<DictatedTextInput\s+id=\{`step-name-\$\{step\.key\}`\}/);

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
  expect(ARTISAN_FORM, 'Artisan.notes stays blank-line separated').toMatch(
    /name="notes"[\s\S]{0,200}?join="paragraph"/
  );

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
  expect((PROCESS_FORM.match(/<RichTextField/g) ?? []).length, "exactly one editor on this form").toBe(1);
  expect(PROCESS_FORM, "and it is the process column, not the step column").toMatch(
    /<RichTextField[\s\S]{0,300}?name="notes"[\s\S]{0,300}?onValueChange=\{setNotes\}/
  );
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
  expect(LOCATION_FIELDS).toMatch(/name="village"[\s\S]{0,300}?explainWhenUnavailable=\{false\}/);
  expect(LOCATION_FIELDS).not.toMatch(/<DictationUnavailableNotice/);
  expect(UNAVAILABLE_NOTICE).toMatch(/if \(absent !== true\) return null;/);
  expect(UNAVAILABLE_NOTICE).toMatch(/setAbsent\(!speechRecognitionConstructor\(\)\)/);
});

test("the zero-size mirrors are untouched by the dictation sweep", () => {
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
  for (const rel of [
    ["components", "forms", "AadhaarField.tsx"],
    ["components", "forms", "ArtisanForm.tsx"],
    ["components", "forms", "DosDontsField.tsx"],
    ["components", "forms", "LocationFields.tsx"]
  ]) {
    expect(read(...rel), `${rel.join("/")} must not hand-write the mark`).not.toMatch(/\? " \*" : ""/);
  }
  expect(MEDIA_PAGE, "and it is not typed into a label string either").not.toMatch(/label="[^"]*\*"/);

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
  expect(RICH_TEXT_FIELD, "the form reads the value through a hidden input, as before").toMatch(
    /<input type="hidden" name=\{name\} value=\{submitValue\} \/>/
  );
  expect(RICH_TEXT_FIELD, "the editor's live document is never fed back in as `value`").toMatch(
    /const \[initialValue\] = useState<unknown>\(\(\) => decodeStoredRichText\(defaultValue\)\)/
  );
  expect(RICH_TEXT_FIELD, "everything submitted goes through the encode rule").toMatch(
    /const encoded = encodeStoredRichText\(doc, join\);\s*\n\s*setSubmitValue\(encoded\);/
  );
  expect(DICTATED_TEXTAREA).toMatch(/<textarea[\s\S]{0,300}?name=\{name\}/);

  expect(DICTATED_TEXTINPUT, "no internal state — one mode, not two").not.toMatch(/useState/);
  expect(DICTATED_TEXTINPUT, "the ceiling is enforced in JS, not only by the attribute").toMatch(
    /onChange\(clampToColumn\(next, maxLength\)\)/
  );
  expect(DICTATED_TEXTINPUT, "titleCased mounts the real component, never a copy of its hint").toMatch(
    /\? TitleCasedInput\s*\n?\s*: TextInput/
  );
  expect(DICTATED_TEXTINPUT, "it draws its own label, because Field is a <label>").toMatch(
    /<label className="field-label" htmlFor=/
  );
  expect(DICTATED_TEXTAREA, "and so does the multi-line one").toMatch(/<label className="field-label" htmlFor=/);
  expect(DICTATED_TEXTAREA, "which now takes required, so a mandatory box stays mandatory").toMatch(
    /required=\{required\}/
  );

  expect(PROCESS_FORM).toMatch(/id="process-name"/);
  expect(PROCESS_FORM).toMatch(/id=\{`step-name-\$\{step\.key\}`\}/);
  expect(PROCESS_FORM, "one refusal path, for both save buttons").toMatch(/\bnoValidate\b/);
  expect(PROCESS_FORM, "and the ladder it protects is still there").toMatch(
    /document\.getElementById\(focusId\)\?\.focus\(\)/
  );

  expect(MEDIA_PAGE).toMatch(/formElement\.reset\(\);[\s\S]{0,400}?setMediaTitle\(""\)/);
  expect(MEDIA_PAGE).toMatch(/formElement\.reset\(\);[\s\S]{0,400}?setResetNonce\(/);
  expect(CRAFTS_PAGE, "the craft form remounts on every reset, including the offline one").toMatch(
    /key=\{`\$\{editing\?\.id \?\? "new"\}-\$\{formKey\}`\}/
  );
  expect(WORKSHOPS_PAGE, "and so does the workshop form — same key, same trap, same fix").toMatch(
    /key=\{`\$\{editing\?\.id \?\? "new"\}-\$\{formKey\}`\}/
  );
  expect(ARTISAN_FORM, "discardEntry clears the boxes that live outside the keyed form").toMatch(
    /function discardEntry\(\)[\s\S]{0,800}?setPlace\(initial\?\.place \?\? ""\)/
  );
  expect(LOCATION_FIELDS, "a dictated village arms the unsaved-changes guard").toMatch(
    /name="village"[\s\S]{0,500}?onDirty\?\.\(\)/
  );
});
