import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const PAGE = read("app", "(protected)", "questionnaire", "page.tsx");
const BUILDER = read("components", "questionnaires", "InstrumentBuilder.tsx");
const ON_DEVICE_BUTTON = read("components", "dictation", "OnDeviceDictationButton.tsx");
const DICTATED_INPUT = read("components", "richtext", "DictatedTextInput.tsx");
const DICTATED_TEXTAREA = read("components", "richtext", "DictatedTextArea.tsx");
const DICTATED_VALUE = read("components", "richtext", "dictatedValue.ts");
const UNAVAILABLE_NOTICE = read("components", "richtext", "DictationUnavailableNotice.tsx");
const HELP_TEXT = read("components", "questionnaires", "QuestionHelpText.tsx");
const TYPES = read("lib", "types.ts");

function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

const CODE = codeOnly(`${PAGE}\n${BUILDER}`);

function region(start: string, end: string): string {
  const from = CODE.indexOf(start);
  expect(from, `${start} is not in this file`).toBeGreaterThan(-1);
  const to = CODE.indexOf(end, from);
  expect(to, `${end} does not follow ${start}`).toBeGreaterThan(from);
  return CODE.slice(from, to);
}

const countOf = (source: string, pattern: RegExp) => (source.match(pattern) ?? []).length;

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
  expect(CODE, "no server transcriber on the dictation path").not.toMatch(/transcribeMediaFile/);
  expect(CODE, "this page must not build its own recogniser").not.toMatch(/SpeechRecognition/);
  expect(PAGE.match(/^import .*(?:[Dd]ictat|richtext).*$/gm) ?? []).toEqual([
    'import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";',
    'import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";',
    'import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";',
    'import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";',
    'import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";'
  ]);
});

test("the consented recorder stays inside startRecording and nothing dictated can reach it", () => {
  const recorder = region("async function startRecording(", "function stopRecording(");
  expect(recorder, "the consented recorder is where the consented recorder has always been").toMatch(
    /getUserMedia/
  );
  expect(recorder).toMatch(/new MediaRecorder\(/);
  expect(countOf(CODE, /getUserMedia/g), "exactly one microphone stream is opened on this page").toBe(1);
  expect(countOf(CODE, /new MediaRecorder\(/g), "and exactly one recorder is constructed").toBe(1);
  expect(CODE, "the interview clips are still uploaded and still transcribed").toMatch(/alwaysTranscribe: true/);
});

test("every microphone on this page is silent about Firefox, and the page says it once instead", () => {
  const controls =
    countOf(CODE, /<DictatedTextInput\b/g) +
    countOf(CODE, /<DictatedTextArea\b/g) +
    countOf(CODE, /<OnDeviceDictationButton\b/g);
  expect(controls, "this page carries the dictation controls the sweep added").toBe(7);
  expect(
    countOf(CODE, /explainWhenUnavailable=\{false\}/g),
    "EVERY control is silenced — one that is not prints the paragraph beside itself"
  ).toBe(controls);
  expect(CODE, "and none of them may opt back in").not.toMatch(/explainWhenUnavailable=\{true\}/);
  expect(
    countOf(CODE, /<DictationUnavailableNotice\b/g),
    "EXACTLY once on the page — zero is a silent nothing on Firefox, two is the noise the component exists to remove"
  ).toBe(1);
  expect(CODE).toMatch(/<DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" \/>/);
});

test("the dictated boxes on this page are exactly the free-text ones, named", () => {
  const named = [...CODE.matchAll(/<DictatedText(?:Input|Area)\b([\s\S]*?)\/>/g)].map(
    (match) => /name="([^"]+)"/.exec(match[1])?.[1] ?? "(no name)"
  );
  expect([...named].sort(), "title and place in the header; prompt and title in the builder").toEqual([
    "(no name)",
    "place",
    "prompt",
    "title",
    "title"
  ]);

  expect(CODE, "the instrument picker is a Select").toMatch(/<Select\s+name="questionnaireId"/);
  expect(CODE, "status is a Select").toMatch(/<Select name="status"/);
  expect(CODE, "the artisans are picked from a multi-select, not typed").toMatch(
    /<MultiSelectDropdown\s+values=\{selectedArtisanIds\}/
  );
  expect(CODE, "no artisan is ever dictated into a box").not.toMatch(/name="(primary)?[aA]rtisan(Id|Ids)?"/);
  expect(CODE, "a section code is typed, never spoken").toMatch(
    /<TextInput value=\{newCode\} onChange=\{\(event\) => setNewCode\(event\.target\.value\)\}/
  );
  expect(CODE, "and so is a section code being edited").toMatch(/<TextInput name="code" defaultValue=\{section\.code\}/);
  expect(CODE, "no interview date field exists at all").not.toMatch(/name="interviewDate"/);
});

test("the three header boxes keep the names their own submit reads them by", () => {
  for (const name of ["title", "place"] as const) {
    expect(CODE, `${name} must submit under its own name`).toMatch(new RegExp(`<DictatedTextInput\\s+name="${name}"`));
  }
  expect(CODE, "language must submit under its own name").toMatch(/<Select name="language" value=\{language\}/);
  expect(CODE, "language is picked from the shared list").toMatch(/languageOptions\(language\)\.map/);
  expect(CODE, "and submit still reads all three out of the FormData").toMatch(/textValue\(form, "title"\)/);
  expect(CODE).toMatch(/place: textValue\(form, "place"\)/);
  expect(CODE).toMatch(/language: textValue\(form, "language"\)/);

  expect(CODE).toMatch(/name="title"[\s\S]*?titleCased/);
  expect(CODE).toMatch(/name="place"[\s\S]*?titleCased/);

  expect(CODE, "the title carries the cap the schema declares").toMatch(/maxLength=\{INTERVIEW_TITLE_MAX\}/);
  expect(CODE).toMatch(/const INTERVIEW_TITLE_MAX = 220;/);
});

test("a form that clears itself in place also clears the boxes React owns", () => {
  const submit = region("async function submit(", "async function remove(");
  expect(countOf(submit, /formElement\.reset\(\);/g), "the two clearing paths are both still here").toBe(2);
  expect(
    countOf(submit, /formElement\.reset\(\);\s*clearHeaderBoxes\(\);/g),
    "and each one clears the controlled boxes in the same breath"
  ).toBe(2);
  expect(CODE).toMatch(/function clearHeaderBoxes\(\) \{\s*setTitle\(""\);\s*setPlace\(""\);\s*setLanguage\(""\);/);
});

test("every answer box has a microphone, and it appends to the answer as it stands now", () => {
  expect(CODE, "the answer box is still named by its prompt rather than by a label of its own").toMatch(
    /aria-labelledby=\{`question-label-\$\{question\.id\}`\}/
  );
  expect(CODE, "a committed phrase appends to the CURRENT answer").toMatch(
    /\[question\.id\]: appendDictatedPhrase\(current\[question\.id\] \?\? "", phrase\)/
  );
  expect(CODE, "through the shared joiner, never a hand-rolled one").not.toMatch(/const joiner =/);

  expect(countOf(CODE, /<OnDeviceDictationButton\b/g), "the answer box and the builder's tile").toBe(2);
  expect(CODE, "the tile's box is named, because the microphone's label points at it").toMatch(
    /aria-label=\{`Prompt for question \$\{question\.sectionCode\}\$\{question\.sortOrder\}`\}/
  );

  const answerBranch = CODE.slice(CODE.indexOf("{capture.hideAnswers &&"));
  expect(answerBranch.slice(0, answerBranch.indexOf("</>")), "the mic is inside the hideAnswers branch").toMatch(
    /<OnDeviceDictationButton/
  );
});

test("adding a question empties the box it was typed into, so the next press cannot re-file it", () => {
  expect(CODE, "the box is keyed on its own section's nonce").toMatch(
    /<DictatedTextArea\s*\n\s*key=\{questionNonce\[section\.id\] \?\? 0\}/
  );
  expect(CODE, "and the nonce is bumped in the same block as the reset").toMatch(
    /formElement\.reset\(\);\s*setQuestionNonce\(\(current\) => \(\{ \.\.\.current, \[section\.id\]: \(current\[section\.id\] \?\? 0\) \+ 1 \}\)\);/
  );
  expect(CODE).toMatch(/const \[questionNonce, setQuestionNonce\] = useState<Record<string, number>>\(\{\}\);/);
  expect(CODE).toMatch(/<DictatedTextArea[\s\S]*?name="prompt"[\s\S]*?required/);
});

test("a section title edited in the builder still submits, and re-seeds when the server moves it", () => {
  expect(CODE).toMatch(/function SectionTitleField\(\{ section \}: \{ section: QuestionnaireSection \}\)/);
  expect(CODE, "it submits under the name updateSection reads").toMatch(/<DictatedTextInput\s+name="title"/);
  expect(CODE, "and it re-seeds on the row's identity AND its title").toMatch(
    /useEffect\(\(\) => \{\s*setValue\(section\.title\);\s*\}, \[section\.id, section\.title\]\);/
  );
});

test("the question type declares the four columns the server already sends", () => {
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
  const helpCode = codeOnly(HELP_TEXT);
  expect(helpCode, "no intersection type survives here").not.toMatch(/QuestionnaireQuestion & \{/);
  expect(helpCode, "the help text renders from the declared field").toMatch(/question: QuestionnaireQuestion;/);
  expect(helpCode, "and the instrument's marker is not the form's asterisk").not.toMatch(/RequiredMark/);
  expect(CODE, "help text is drawn under each prompt").toMatch(/<QuestionHelpText question=\{question\} \/>/);
  expect(CODE, "and the marker beside it").toMatch(/<RequiredByInstrument question=\{question\} \/>/);
});
