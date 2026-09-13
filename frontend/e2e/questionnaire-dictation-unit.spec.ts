import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * DICTATION ON /questionnaire, AND THE ONE THING THIS SCREEN MAY NOT BECOME.
 *
 * The record-page sweep put a microphone under every free-text box on eight surfaces and stopped at
 * this one, which is the largest: an eighty-one-question instrument, answered by a researcher sitting
 * on a workshop floor with a phone in one hand and a question sheet in the other. Every answer box on
 * it is now dictatable, and so are the three header boxes and the builder's prompt boxes.
 *
 * ── WHY THIS PAGE NEEDS ITS OWN SPEC AND NOT A ROW IN THE RECORD-FORM ONE ──────────────────────
 *
 * Because it is the ONE dictated surface in this app that legitimately owns a `MediaRecorder`.
 *
 * `e2e/record-form-dictation-unit.spec.ts` §1 can assert that a record form contains no
 * `MediaRecorder`, no `getUserMedia` and no `transcribeMediaFile` full stop, because a microphone
 * beside the artisan's Notes box has nothing around it: nobody was told a recording is being made,
 * there is no stored object to point at afterwards and there is nothing to delete. That flat ban is
 * unavailable here. This page RECORDS THE INTERVIEW — per question, per section, and as one clip for
 * the sitting — and it uploads those clips to `POST /media/transcribe` on purpose, because the
 * artisan was told, the clip becomes a listed `MediaFile` with a filename and a delete button, and
 * the transcription is a step in a workflow they can see.
 *
 * So the invariant here is a BOUNDARY rather than an absence, and it is the boundary that this whole
 * file exists to pin: the consented recorder is confined to `startRecording`, and the dictated boxes
 * reach the microphone only through `OnDeviceDictationButton`, which has no transport at all. The
 * failure this prevents is a one-line "while we are listening anyway, send the audio too" — trivial
 * to write on THIS page and nowhere else, because on this page the uploader is already imported, the
 * recorder already exists and `transcribeAudio: true` is already being passed five lines away. That
 * one line would silently convert "the browser is listening" into "this artisan's voice was sent to a
 * third-party transcription provider", with nobody asked and nothing on screen to delete.
 *
 * ── WHY A NODE SPEC AND NOT A BROWSER RUN ─────────────────────────────────────────────────────
 *
 * This repository has no React renderer in its devDependencies — Playwright is the whole of it — so
 * mounting the page is not available, and `e2e/questionnaire-capture.spec.ts`, which DOES drive a
 * browser, needs credentials and a running dev server and is skipped without them. The rules below
 * must hold on every run, including the ones with no server. The joiner and the column ceiling are
 * not re-tested here: they are pure functions in `components/richtext/dictatedValue.ts` and
 * `record-form-dictation-unit.spec.ts` EXECUTES them, which is a stronger guard than a second copy
 * of the same expectations.
 *
 * WHAT THIS FILE CANNOT PROVE, stated rather than left to be rediscovered: that a browser paints the
 * microphones, and that a spoken phrase lands in the right box. `questionnaire-capture.spec.ts` is
 * the half that opens a page.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const PAGE = read("app", "(protected)", "questionnaire", "page.tsx");
const ON_DEVICE_BUTTON = read("components", "dictation", "OnDeviceDictationButton.tsx");
const DICTATED_INPUT = read("components", "richtext", "DictatedTextInput.tsx");
const DICTATED_TEXTAREA = read("components", "richtext", "DictatedTextArea.tsx");
const DICTATED_VALUE = read("components", "richtext", "dictatedValue.ts");
const UNAVAILABLE_NOTICE = read("components", "richtext", "DictationUnavailableNotice.tsx");
const HELP_TEXT = read("components", "questionnaires", "QuestionHelpText.tsx");
const TYPES = read("lib", "types.ts");

/**
 * Comments removed. On this page that matters more than anywhere else in the repository: the file
 * argues about `MediaRecorder`, `getUserMedia` and `transcribeMediaFile` at length in prose — it has
 * to, they are the things it is careful about — so a regex over the raw text answers questions about
 * the paragraphs rather than about the code. Identical rule to the one in
 * `e2e/record-form-dictation-unit.spec.ts` and `e2e/questionnaire-workbook-unit.spec.ts`; keep the
 * three copies the same until one of them can be hoisted into a shared helper.
 */
function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

const CODE = codeOnly(PAGE);

/** The body of a top-level function in the page, from its own declaration to the next named one. */
function region(start: string, end: string): string {
  const from = CODE.indexOf(start);
  expect(from, `${start} is not in this file`).toBeGreaterThan(-1);
  const to = CODE.indexOf(end, from);
  expect(to, `${end} does not follow ${start}`).toBeGreaterThan(from);
  return CODE.slice(from, to);
}

const countOf = (source: string, pattern: RegExp) => (source.match(pattern) ?? []).length;

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The boundary: what is dictated never leaves the device
 * ──────────────────────────────────────────────────────────────────────────── */

test("the dictation controls this page mounts contain no transport of any kind", () => {
  for (const [name, source] of [
    ["OnDeviceDictationButton.tsx", ON_DEVICE_BUTTON],
    ["DictatedTextInput.tsx", DICTATED_INPUT],
    ["DictatedTextArea.tsx", DICTATED_TEXTAREA],
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

test("the questionnaire page reaches a microphone for DICTATION only through the on-device button", () => {
  // `transcribeMediaFile` is a real export of `@/lib/media`, which this page already imports from —
  // so importing it is one autocomplete away, and it would not otherwise show up in any assertion in
  // this file. It reaches `POST /media/transcribe` (backend/app/api/routes/media.py), a route gated
  // on nothing but `get_current_user`.
  expect(CODE, "no server transcriber on the dictation path").not.toMatch(/transcribeMediaFile/);
  // The recogniser lifecycle lives in `components/dictation/onDeviceSpeech.ts` and is reached ONLY
  // through the button. A `SpeechRecognition` constructed on this page would be a second
  // implementation of interim-vs-final results, `resultIndex`, Safari's double-tap throw and
  // stop-versus-abort — four things that go wrong, re-derived where no test drives them.
  expect(CODE, "this page must not build its own recogniser").not.toMatch(/SpeechRecognition/);
  // And the dictation imports are exactly the four shared modules, named. A fifth import line is how
  // a control with a transport would arrive.
  expect(PAGE.match(/^import .*(?:[Dd]ictat|richtext).*$/gm) ?? []).toEqual([
    'import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";',
    'import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";',
    'import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";',
    'import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";',
    'import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";'
  ]);
});

test("the consented recorder stays inside startRecording and nothing dictated can reach it", () => {
  /*
    THE POINT OF THIS TEST, which is the one assertion in this file that could not be written as a
    flat ban. `MediaRecorder` and `getUserMedia` are legitimate HERE — the interview is being
    recorded, with the artisan's knowledge, into a listed `MediaFile` that has a delete button — so
    what has to be pinned is that there is exactly ONE of each and both are inside the function that
    the record buttons call. A second `getUserMedia` appearing anywhere else in this file is the
    shape the "send the dictated audio too" defect would take.
  */
  const recorder = region("async function startRecording(", "function stopRecording(");
  expect(recorder, "the consented recorder is where the consented recorder has always been").toMatch(
    /getUserMedia/
  );
  expect(recorder).toMatch(/new MediaRecorder\(/);
  expect(countOf(CODE, /getUserMedia/g), "exactly one microphone stream is opened on this page").toBe(1);
  expect(countOf(CODE, /new MediaRecorder\(/g), "and exactly one recorder is constructed").toBe(1);
  // The clips that DO leave the device all carry `transcribeAudio: true`, and that is correct: they
  // are the interview. Their presence is what makes the absence above meaningful rather than
  // accidental — this page is not transport-free, it is transport-BOUNDED.
  expect(CODE, "the interview clips are still uploaded and still transcribed").toMatch(/transcribeAudio: true/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. One sentence for the whole page
 * ──────────────────────────────────────────────────────────────────────────── */

test("every microphone on this page is silent about Firefox, and the page says it once instead", () => {
  // Firefox implements no `SpeechRecognition` at all, and neither do some locked-down Chromium
  // builds. `OnDeviceDictationButton` then draws no button and prints a sentence in its place —
  // right for a screen with one or two microphones, and intolerable on a screen with eighty-one:
  // the reader learns to skip a block of grey text and then skips the one place it mattered.
  const controls =
    countOf(CODE, /<DictatedTextInput\b/g) +
    countOf(CODE, /<DictatedTextArea\b/g) +
    countOf(CODE, /<OnDeviceDictationButton\b/g);
  expect(controls, "this page carries the dictation controls the sweep added").toBe(8);
  expect(
    countOf(CODE, /explainWhenUnavailable=\{false\}/g),
    "EVERY control is silenced — one that is not prints the paragraph beside itself"
  ).toBe(controls);
  expect(CODE, "and none of them may opt back in").not.toMatch(/explainWhenUnavailable=\{true\}/);
  expect(
    countOf(CODE, /<DictationUnavailableNotice\b/g),
    "EXACTLY once on the page — zero is a silent nothing on Firefox, two is the noise the component exists to remove"
  ).toBe(1);
  // It spans the field grid rather than wrapping into one cell of four.
  expect(CODE).toMatch(/<DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" \/>/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. Which boxes got a microphone, and which deliberately did not
 * ──────────────────────────────────────────────────────────────────────────── */

test("the dictated boxes on this page are exactly the free-text ones, named", () => {
  /*
    THE WHOLE SET IN ONE ASSERTION, rather than one `not.toMatch` per box that must not have a
    microphone. A list of forbidden names only catches the boxes somebody thought to forbid; the set
    catches a microphone appearing anywhere, including under a control that does not exist yet.

    A `null` in this list is a dictated box with NO `name`, which is legal exactly once here: the
    builder's add-section title, whose form builds its body out of React state and never constructs a
    `FormData`. Everywhere else on this page a missing `name` submits NOTHING, silently, with a 201
    in reply — `submit` reads `title`, `place` and `language` out of a `FormData`, and `addQuestion`
    reads `prompt` out of one.
  */
  const named = [...CODE.matchAll(/<DictatedText(?:Input|Area)\b([\s\S]*?)\/>/g)].map(
    // `"(no name)"` and not `null`, because `Array.prototype.sort` compares STRINGIFIED elements: a
    // literal null sorts as "null", lands between "language" and "place", and the expected list below
    // would then have to be written in an order that reads like a mistake.
    (match) => /name="([^"]+)"/.exec(match[1])?.[1] ?? "(no name)"
  );
  expect([...named].sort(), "title, place and language in the header; prompt and title in the builder").toEqual([
    "(no name)",
    "language",
    "place",
    "prompt",
    "title",
    "title"
  ]);

  // The closed vocabularies, the identity code and the date, each still the control it was. A
  // recogniser asked for "RESP" hands back "resp", "rest" or "R E S P" with equal confidence, and a
  // box whose value must be exact is the one box dictation must not touch.
  expect(CODE, "the instrument picker is a Select").toMatch(/<Select\s+name="questionnaireId"/);
  expect(CODE, "status is a Select").toMatch(/<Select name="status"/);
  expect(CODE, "the primary artisan is a Select").toMatch(/<Select\s+name="primaryArtisanId"/);
  expect(CODE, "a section code is typed, never spoken").toMatch(
    /<TextInput value=\{newCode\} onChange=\{\(event\) => setNewCode\(event\.target\.value\)\}/
  );
  expect(CODE, "and so is a section code being edited").toMatch(/<TextInput name="code" defaultValue=\{section\.code\}/);
  // No date box was added back with a microphone under it: the server derives interviewDate from
  // recordedAt. A spoken date is the one thing a recogniser gets wrong in a way that still parses.
  expect(CODE, "no interview date field exists at all").not.toMatch(/name="interviewDate"/);
});

test("the three header boxes keep the names their own submit reads them by", () => {
  // The failure mode this closes is silent in every direction: a `DictatedTextInput` with no `name`
  // renders identically, type-checks, submits nothing, and the API answers 201. The interview saves
  // with a generated title, no place and no language, and nobody finds out until the record is read
  // back — by which time the artisan has gone home.
  for (const name of ["title", "place", "language"] as const) {
    expect(CODE, `${name} must submit under its own name`).toMatch(new RegExp(`<DictatedTextInput\\s+name="${name}"`));
  }
  expect(CODE, "and submit still reads all three out of the FormData").toMatch(/textValue\(form, "title"\)/);
  expect(CODE).toMatch(/place: textValue\(form, "place"\)/);
  expect(CODE).toMatch(/language: textValue\(form, "language"\)/);

  // `titleCased` follows the SERVER rule and is not a styling choice: `create_interview` runs
  // `clean_data(...)`, which title-cases every column in `TITLE_CASE_FIELDS`
  // (backend/app/services/records.py). `title` and `place` are in that set; `language` is not. The
  // hint matters more with a microphone than without one — a recogniser hands back its own casing
  // and nobody typed it, so without it the value changes silently after saving.
  expect(CODE).toMatch(/name="title"[\s\S]*?titleCased/);
  expect(CODE).toMatch(/name="place"[\s\S]*?titleCased/);
  const languageBox = /<DictatedTextInput\s+name="language"([\s\S]*?)\/>/.exec(CODE)?.[1] ?? "";
  expect(languageBox, "language is not title-cased server-side, so it must not claim to be").not.toMatch(
    /titleCased/
  );

  // The API's own ceiling, enforced in React state where the DOM attribute cannot reach. Dictation is
  // the one path that can carry a value past a column's limit, and an over-long title 422s the WHOLE
  // body — every answer in the interview lost, and `saveOrQueue` will not bank a 4xx for later.
  expect(CODE, "the title carries the cap the schema declares").toMatch(/maxLength=\{INTERVIEW_TITLE_MAX\}/);
  expect(CODE).toMatch(/const INTERVIEW_TITLE_MAX = 220;/);
});

test("a form that clears itself in place also clears the boxes React owns", () => {
  /*
    THE DEFECT: `formElement.reset()` rewrites the DOM nodes and tells React nothing at all, so a
    controlled box keeps its value across it. `submit` resets in TWO places — once when the sitting
    was queued to the outbox and once when it saved — and a `clearHeaderBoxes()` missing from either
    one opens the next interview with the previous artisan's title and place already typed in. That is
    worse here than on any other screen in this app: the questionnaire's uniqueness key is the artisan
    SET, so a researcher who does not notice files a second sitting under a title naming the wrong
    person.
  */
  const submit = region("async function submit(", "async function remove(");
  expect(countOf(submit, /formElement\.reset\(\);/g), "the two clearing paths are both still here").toBe(2);
  expect(
    countOf(submit, /formElement\.reset\(\);\s*clearHeaderBoxes\(\);/g),
    "and each one clears the controlled boxes in the same breath"
  ).toBe(2);
  // `""` and not `undefined`: a controlled input handed undefined switches to uncontrolled, React
  // warns, and the box keeps whatever the DOM node last held — which is the bug this closes.
  expect(CODE).toMatch(/function clearHeaderBoxes\(\) \{\s*setTitle\(""\);\s*setPlace\(""\);\s*setLanguage\(""\);\s*\}/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The answer boxes — the reason this page has microphones at all
 * ──────────────────────────────────────────────────────────────────────────── */

test("every answer box has a microphone, and it appends to the answer as it stands now", () => {
  expect(CODE, "the answer box is still named by its prompt rather than by a label of its own").toMatch(
    /aria-labelledby=\{`question-label-\$\{question\.id\}`\}/
  );
  // THE UPDATER FORM, NOT THE RENDER CLOSURE. `OnDeviceDictationButton` installs its recogniser
  // handlers once and calls this through a ref, so the CALLBACK is current; what is not current is
  // any value the closure captured. Reading `answers[question.id]` from the closure would append each
  // phrase to the answers as they stood when the microphone was pressed, silently discarding
  // everything typed into any box in between — invisible with one short phrase and obvious only to
  // the researcher who dictated three paragraphs, which is the worst order to find it in.
  expect(CODE, "a committed phrase appends to the CURRENT answer").toMatch(
    /\[question\.id\]: appendDictatedPhrase\(current\[question\.id\] \?\? "", phrase\)/
  );
  expect(CODE, "through the shared joiner, never a hand-rolled one").not.toMatch(/const joiner =/);

  // Both microphones that are bare buttons rather than dictated components are there because the box
  // they sit under is named from outside it — the answer box by its prompt heading, the builder's
  // tile by an aria-label. Mounting `DictatedTextArea` at either would print the label twice and a
  // screen reader would read it twice.
  expect(countOf(CODE, /<OnDeviceDictationButton\b/g), "the answer box and the builder's tile").toBe(2);
  expect(CODE, "the tile's box is named, because the microphone's label points at it").toMatch(
    /aria-label=\{`Prompt for question \$\{question\.sectionCode\}\$\{question\.sortOrder\}`\}/
  );

  // The microphone lives INSIDE the answer-box branch. In the default capture mode the answer boxes
  // are hidden and a microphone with no box to fill would be a control that does nothing —
  // `questionnaire-capture.spec.ts` asserts a section holds zero textareas on first paint.
  const answerBranch = CODE.slice(CODE.indexOf("{capture.hideAnswers ? null : ("));
  expect(answerBranch.slice(0, answerBranch.indexOf("</>")), "the mic is inside the hideAnswers branch").toMatch(
    /<OnDeviceDictationButton/
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. The builder, and the nonce without which it files a question twice
 * ──────────────────────────────────────────────────────────────────────────── */

test("adding a question empties the box it was typed into, so the next press cannot re-file it", () => {
  /*
    THE BUG, IN FULL. The add-question box is a `DictatedTextArea`, which owns its own value and
    re-seeds from `defaultValue` on REMOUNT only. The form clears itself with `formElement.reset()`,
    which rewrites the DOM node and tells React nothing. Without a key that changes, the prompt an
    admin just filed STAYS ON SCREEN, the form looks as though nothing happened, and the next press of
    "Add question" files the same question a second time. Two identical questions in one section is
    not cosmetic: the workbook download prints both, the re-upload matches both by id, and every
    interview from then on asks the artisan the same thing twice.

    The sibling application ships this screen with the bug AND with a comment asserting the opposite.
    That is why this is a test and not a paragraph.
  */
  expect(CODE, "the box is keyed on its own section's nonce").toMatch(
    /<DictatedTextArea\s*\n\s*key=\{questionNonce\[section\.id\] \?\? 0\}/
  );
  expect(CODE, "and the nonce is bumped in the same block as the reset").toMatch(
    /formElement\.reset\(\);\s*setQuestionNonce\(\(current\) => \(\{ \.\.\.current, \[section\.id\]: \(current\[section\.id\] \?\? 0\) \+ 1 \}\)\);/
  );
  // PER SECTION, NOT ONE COUNTER FOR THE BUILDER. Every section renders its own add-question form and
  // they are all on screen at once, so a single number would remount every one of them on every add
  // and throw away a prompt half-typed in another section.
  expect(CODE).toMatch(/const \[questionNonce, setQuestionNonce\] = useState<Record<string, number>>\(\{\}\);/);
  // The box keeps the browser's own empty check. Dropped on the way through, the builder would start
  // accepting empty questions silently — the box would look identical and submit anyway.
  expect(CODE).toMatch(/<DictatedTextArea[\s\S]*?name="prompt"[\s\S]*?required/);
});

test("a section title edited in the builder still submits, and re-seeds when the server moves it", () => {
  // `updateSection` reads `form.get("title")` and turns a missing key into `""` before PATCHing it —
  // so a dropped `name` here does not fail, it renames the section to nothing, on every question that
  // denormalises `sectionTitle`.
  expect(CODE).toMatch(/function SectionTitleField\(\{ section \}: \{ section: QuestionnaireSection \}\)/);
  expect(CODE, "it submits under the name updateSection reads").toMatch(/<DictatedTextInput\s+name="title"/);
  expect(CODE, "and it re-seeds on the row's identity AND its title").toMatch(
    /useEffect\(\(\) => \{\s*setValue\(section\.title\);\s*\}, \[section\.id, section\.title\]\);/
  );
  // Depending on `section.title` and not only on `section.id` is the half that catches the second
  // case: `onChanged` replaces the whole section list after any add, remove, reorder or reparent, and
  // a title changed on the server (by the workbook import, or by another admin) must not sit behind a
  // stale local value that the next Save writes straight back over.
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. The four instrument fields the server sends
 * ──────────────────────────────────────────────────────────────────────────── */

test("the question type declares the four columns the server already sends", () => {
  // NO CODEGEN EXISTS IN THIS PRODUCT. A wire field is a three-file hand edit — the Pydantic schema,
  // `lib/types.ts` here, and Android's `ApiModels.kt` — and Kotlin's `ignoreUnknownKeys` makes a
  // missed field SILENT on the phone rather than a decode failure. This assertion is the web third of
  // that edit, written down so the next person adding a question column can see what the shape is.
  const question = TYPES.slice(
    TYPES.indexOf("export type QuestionnaireQuestion = {"),
    TYPES.indexOf("export type QuestionnaireSection = {")
  );
  expect(question).toMatch(/helpText\?: string \| null;/);
  expect(question).toMatch(/isRequired\?: boolean;/);
  expect(question).toMatch(/retiredAt\?: string \| null;/);
  expect(question).toMatch(/supersededById\?: string \| null;/);
});

test("the help-text bridge is gone, now that the type it bridged to declares the fields", () => {
  // `QuestionHelpText` took `QuestionnaireQuestion & { helpText?; isRequired? }` for exactly as long
  // as `lib/types.ts` did not declare the two fields, and its own docstring said to drop the
  // intersection the moment they landed. An intersection with an inline shape accepts anything
  // satisfying EITHER half, so a misspelling reaching in stayed a silently blank line instead of a
  // compile error.
  //
  // READ THROUGH `codeOnly`, AND THAT IS NOT A DETAIL. The component's docstring QUOTES the
  // intersection it used to carry, in order to explain what was removed and why — so the obvious
  // `expect(HELP_TEXT).not.toMatch(/QuestionnaireQuestion & \{/)` goes red on the corrected file and
  // the cheapest way to make it green is to delete the paragraph that explains the fix. This is the
  // same trap `workbookApi.ts` sets for `e2e/questionnaire-workbook-unit.spec.ts`, and the same
  // answer.
  const helpCode = codeOnly(HELP_TEXT);
  expect(helpCode, "no intersection type survives here").not.toMatch(/QuestionnaireQuestion & \{/);
  expect(HELP_TEXT, "but the file still explains what was dropped").toMatch(/QuestionnaireQuestion & \{/);
  expect(helpCode, "the help text renders from the declared field").toMatch(/question: QuestionnaireQuestion;/);
  expect(helpCode, "and the instrument's marker is not the form's asterisk").not.toMatch(/RequiredMark/);
  // Both are mounted on the answering screen, which is the entire point of the columns: an admin
  // typing eighty-one help texts into a spreadsheet that no researcher ever sees is the app asking a
  // question and then ignoring the answer.
  expect(CODE, "help text is drawn under each prompt").toMatch(/<QuestionHelpText question=\{question\} \/>/);
  expect(CODE, "and the marker beside it").toMatch(/<RequiredByInstrument question=\{question\} \/>/);
});
