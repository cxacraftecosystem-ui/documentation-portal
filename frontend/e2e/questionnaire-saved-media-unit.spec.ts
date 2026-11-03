import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const PAGE = read("app", "(protected)", "questionnaire", "page.tsx");
const CAPTURE_CONTROLS = read("components", "forms", "QuestionnaireCaptureControls.tsx");
const EXISTING_MEDIA = read("components", "media", "ExistingMedia.tsx");
const TOOL_FORM = read("components", "forms", "ToolForm.tsx");
const ROUTE = readFileSync(
  join(__dirname, "..", "..", "backend", "app", "api", "routes", "questionnaire.py"),
  "utf8"
);

function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

const CODE = stripComments(PAGE);

function region(start: string, end: string): string {
  const from = CODE.indexOf(start);
  expect(from, `expected to find ${start}`).toBeGreaterThan(-1);
  const to = CODE.indexOf(end, from);
  expect(to, `expected ${end} to follow ${start}`).toBeGreaterThan(from);
  return CODE.slice(from, to);
}

test("g. two loadMeta runs cannot race: the newest generation is the only one that may write", () => {
  expect(CODE).toMatch(/const currentMetaLoad = useRef\(0\);/);
  expect(CODE).toMatch(/const currentInterviewLoad = useRef\(0\);/);

  const loadMeta = region("async function loadMeta(", "const boundInstrumentId");
  expect(loadMeta).toMatch(/const generation = \(currentMetaLoad\.current \+= 1\);/);
  expect(loadMeta.match(/if \(generation !== currentMetaLoad\.current\) return;/g) ?? []).toHaveLength(2);

  const guard = loadMeta.indexOf("if (generation !== currentMetaLoad.current) return;");
  expect(guard).toBeGreaterThan(-1);
  expect(guard).toBeLessThan(loadMeta.indexOf("setInstruments(instrumentList)"));
  expect(guard).toBeLessThan(loadMeta.indexOf("setSections(sectionList)"));
});

test("g. it is counted, not aborted, because apiFetch takes no signal", () => {
  const loadMeta = region("async function loadMeta(", "const boundInstrumentId");
  expect(loadMeta).toMatch(/const generation = \(currentMetaLoad\.current \+= 1\);/);
  expect(loadMeta).not.toContain("AbortController");
  expect(loadMeta).not.toContain("signal");
  expect(CODE).toMatch(/useEffect\(\(\) => \{\s*loadMeta\(\);\s*\}, \[\]\);/);
  expect(CODE).toMatch(/void loadMeta\(interview\.questionnaireId\);/);
});

test("a. saved clips are drawn per section, with a catch-all, as the handset does", () => {
  expect(CODE.match(/<SavedMediaPanel/g) ?? []).toHaveLength(2);
  expect(CODE).toMatch(/items=\{sectionClips\}/);
  expect(CODE).toMatch(/const sectionClips = savedMedia\.bySection\.get\(group\.section\.id\) \?\? \[\];/);
  expect(CODE).toMatch(/items=\{savedMedia\.other\}/);
  expect(CODE).toMatch(/title="Other saved recordings & media"/);
  const grouping = region("const savedMedia = useMemo(", "const sectionsOpenOnEdit");
  expect(grouping).toMatch(/other: all\.filter\(\(media\) => !placed\.has\(media\.id\)\)/);
  expect(grouping).toMatch(/const all = editing\?\.media \?\? \[\];/);
});

test("a. the repository's own ExistingMedia panel exists, is used elsewhere, and is not what is mounted here", () => {
  expect(EXISTING_MEDIA).toContain("export function ExistingMedia({");
  expect(TOOL_FORM).toContain("<ExistingMedia");
  expect(EXISTING_MEDIA).toMatch(/linkedRecordType: string;\s*\n\s*linkedRecordId: string;/);
  expect(EXISTING_MEDIA).toMatch(/listResource<MediaFile>\("\/media", \{ linkedRecordType, linkedRecordId/);
  expect(CODE).not.toContain("ExistingMedia");
  expect(CODE).toMatch(/function SavedMediaPanel\(\{/);
  expect(CODE).toMatch(/<SavedMediaPanel/);
});

test("a. saved media is READ-ONLY and never reaches the upload tray", () => {
  const panel = region("function SavedMediaPanel(", 'function CompletionMatrixPanel(');
  expect(panel).toMatch(/<MediaPreviewTile key=\{preview\.key\} item=\{preview\} onOpen=\{\(\) => onOpenPreview\(preview\)\} \/>/);
  expect(panel).not.toContain("onRemove");
  (CODE.match(/setMediaFiles\([^)]*\)/g) ?? []).forEach((call) =>
    expect(["setMediaFiles([])", "setMediaFiles(fields.mediaFiles as File[])", "setMediaFiles(interviewBatch.left)"]).toContain(call)
  );
  expect(CODE).not.toMatch(/set(MediaFiles|QuestionAudioFiles)\([^;]*savedMedia/);
  expect(CODE).not.toMatch(/set(MediaFiles|QuestionAudioFiles)\([^;]*editing\?\.media/);
  expect(region("function seedFromInterview(", "const { loading: deepLinkLoading }")).toMatch(
    /setMediaFiles\(\[\]\);\s*\n\s*setQuestionAudioFiles\(\{\}\);/
  );
});

test("a. it reuses the page's one lightbox rather than mounting a second", () => {
  expect(CODE.match(/<MediaLightbox/g) ?? []).toHaveLength(1);
  expect(CODE).toMatch(/onOpenPreview=\{setActivePreview\}/);
  expect(CODE).toMatch(/function savedMediaPreview\(media: MediaFile\): PreviewMedia/);
  expect(CODE).toMatch(/transcriptText: media\.transcriptText/);
});

test("a. a clip's section is read off its caption, with the digit rule that stops D claiming DA", () => {
  expect(CODE).toMatch(/const SECTION_CAPTION_PREFIX = "Section audio:";/);
  expect(CODE).toMatch(/const QUESTION_CAPTION_PREFIX = "Question audio:";/);
  const belongs = region("function captionBelongsToSection(", "function savedMediaPreview(");
  expect(belongs).toMatch(/const next = rest\.charAt\(section\.code\.length\);/);
  expect(belongs).toMatch(/return next >= "0" && next <= "9";/);
  const wholeSection = region("function captionIsWholeSection(", "function captionAnswersQuestion(");
  expect(wholeSection).toMatch(/rest === section\.code \|\| rest\.startsWith\(`\$\{section\.code\} `\)/);
  const answers = region("function captionAnswersQuestion(", "function captionBelongsToSection(");
  expect(answers).toMatch(/rest === stem \|\| rest\.startsWith\(`\$\{stem\} `\) \|\| rest\.startsWith\(`\$\{stem\}-`\)/);
});

test("b. an answer the record carries is drawn even while the preference hides the boxes", () => {
  expect(CODE).toMatch(
    /\{capture\.hideAnswers && !answeredOnRecord\.has\(question\.id\) && !fromTranscript\.has\(question\.id\) \? null : \(/
  );
  expect(CODE).not.toMatch(/\{capture\.hideAnswers \? null : \(/);
  expect(CODE).toMatch(/<TextArea\s*\n\s*aria-labelledby=\{`question-label-\$\{question\.id\}`\}/);
  expect(CODE).toMatch(/Shown because this question already has an answer\./);
});

test("b. the set is frozen at seed time, counts only real words, and is emptied on cancel", () => {
  expect(CODE).toMatch(/const \[answeredOnRecord, setAnsweredOnRecord\] = useState<Set<string>>\(\(\) => new Set\(\)\);/);
  const seed = region("function seedFromInterview(", "const { loading: deepLinkLoading }");
  expect(seed).toMatch(/\.filter\(\(response\) => response\.questionId && \(response\.answerText \?\? ""\)\.trim\(\)\)/);
  expect(region("function resetToCreate(", "function seedFromInterview(")).toMatch(/setAnsweredOnRecord\(new Set\(\)\);/);
});

test("b. the stored preference is not touched, on the page or in its default", () => {
  expect(CODE.match(/updateCapture/g) ?? []).toHaveLength(2);
  expect(CODE).toMatch(/const \{ prefs: capture, update: updateCapture \} = useCapturePrefs\(\);/);
  expect(CODE).toMatch(/onChange=\{updateCapture\}/);
  expect(CODE).not.toMatch(/updateCapture\(\{/);
  expect(stripComments(CAPTURE_CONTROLS)).toMatch(
    /export const DEFAULT_CAPTURE_PREFS: CapturePrefs = \{ recordingMode: "SECTION", hideAnswers: true \};/
  );
  expect(CODE).toMatch(/!answeredOnRecord\.has\(question\.id\)/);
});

test("c. an edit opens every section with content; a create still opens only the first", () => {
  expect(CODE).toMatch(/open=\{index === 0 \|\| sectionsOpenOnEdit\.has\(group\.section\.id\)\}/);
  expect(CODE).not.toMatch(/open=\{index === 0\}/);
  const open = region("const sectionsOpenOnEdit = useMemo(", "const existingEntryTally");
  expect(open).toMatch(/if \(!editing\) return open;/);
  expect(open).toMatch(/question\) => answeredOnRecord\.has\(question\.id\)/);
  expect(open).toMatch(/savedMedia\.bySection\.get\(section\.id\)\?\.length \?\? 0\) > 0/);
});

test("c. it reads the frozen record, never the live answers, so a section cannot shut under the caret", () => {
  const open = region("const sectionsOpenOnEdit = useMemo(", "const existingEntryTally");
  expect(open).not.toContain("answers[");
  expect(open).toMatch(/\}, \[editing, sections, answeredOnRecord, savedMedia\]\);/);
});

test("d. every section summary prints questions, answered and saved recordings", () => {
  expect(CODE).toMatch(/\{tally\.questions\} question\{tally\.questions === 1 \? "" : "s"\} · \{tally\.answered\} answered/);
  expect(CODE).toMatch(/\$\{tally\.saved\} saved recording\$\{tally\.saved === 1 \? "" : "s"\}/);
  expect(CODE).toMatch(/const tally = sectionTally\(group\);/);
});

test("d. the counting rule is the handset's: a whole-section take answers the whole section", () => {
  const tally = region("function sectionTally(", "return (");
  expect(tally).toMatch(/clips\.some\(\(media\) => captionIsWholeSection\(media\.caption, group\.section\)\)/);
  expect(tally).toMatch(/\? group\.items\.length/);
  expect(tally).toMatch(/\(answers\[question\.id\] \?\? ""\)\.trim\(\)\.length > 0/);
  expect(tally).toMatch(/\(questionAudioFiles\[question\.id\]\?\.length \?\? 0\) > 0/);
  expect(tally).toMatch(/clips\.some\(\(media\) => captionAnswersQuestion\(media\.caption, question\)\)/);
  expect(tally).toMatch(/questions: group\.items\.length/);
  expect(tally).toMatch(/saved: clips\.length/);
});

test("e. the shared-entry banner counts recordings as well as responses", () => {
  const summary = region("function sharedEntrySummary(", "const ARTISAN_SET_TAKEN");
  expect(summary).toMatch(/function sharedEntrySummary\(responses: number, recordings: number\): string/);
  expect(summary).toMatch(/if \(responses && recordings\) return `\$\{clips\} and \$\{answers\}\.`;/);
  expect(summary).toMatch(/if \(recordings\) return `\$\{clips\}, no typed answers\.`;/);
  expect(summary).toMatch(/if \(responses\) return `\$\{answers\}, no recordings\.`;/);
  expect(summary).toMatch(/return "Nothing recorded yet\.";/);
  expect(CODE).toMatch(/const recordings = existingEntry\?\.media \?\? \[\];/);
  expect(CODE).toMatch(/\{existingEntryTally\.summary\}/);
});

test("e. the sentence that called a fully recorded interview empty is gone", () => {
  expect(CODE).not.toContain("No questions answered yet");
  expect(CODE).toMatch(/Recordings and files \(\{existingEntryTally\.recordings\.length\}\)/);
  expect(CODE).toMatch(/\{media\.caption \|\| media\.originalFilename\}/);
});

test("f. the client branches on the server's code, not on its prose", () => {
  expect(CODE).toMatch(/const ARTISAN_SET_TAKEN = "artisan_set_taken";/);
  expect(CODE).toMatch(/const MERGE_ANSWER_CONFLICT = "merge_answer_conflict";/);
  expect(ROUTE).toContain('_DUPLICATE_SET_CODE = "artisan_set_taken"');
  expect(ROUTE).toContain('_MERGE_CONFLICT_CODE = "merge_answer_conflict"');
  expect(ROUTE).toContain('@questionnaire_router.post("/interviews/{interview_id}/merge-into/{target_id}")');
  const detail = region("function conflictDetail(", "function artisanSetHolder(");
  expect(detail).toMatch(/if \(!\(error instanceof ApiError\) \|\| error\.status !== 409\) return null;/);
  expect(detail).toMatch(/const detail = \(payload as \{ detail\?: unknown \}\)\.detail;/);
  expect(detail).toMatch(/Array\.isArray\(detail\)/);
});

test("f. a null holder falls back to the server's message rather than offering nothing-shaped", () => {
  const holder = region("function artisanSetHolder(", "function mergeAnswerConflicts(");
  expect(holder).toMatch(/if \(typeof id !== "string" \|\| !id\) return null;/);
  expect(CODE).toMatch(/if \(!holder\) return false;/);
  expect(CODE).toMatch(/if \(!handled\) setError\(err instanceof Error \? err\.message : "Unable to save interview"\);/);
});

test("f. the merge route is called only after the researcher confirms, and only on an edit", () => {
  const offer = region("async function offerMergeOnConflict(", "async function submit(");
  expect(offer).toMatch(/const ok = await confirm\(\{/);
  expect(offer).toMatch(/tone: "warning"/);
  expect(offer).toMatch(/confirmLabel: "Move in"/);
  const declined = offer.indexOf("if (!ok) {");
  const posted = offer.indexOf("/merge-into/");
  expect(declined).toBeGreaterThan(-1);
  expect(declined).toBeLessThan(posted);
  expect(offer).toMatch(/`\/questionnaire\/interviews\/\$\{sourceId\}\/merge-into\/\$\{holder\.id\}`/);
  expect(offer).toMatch(/\{ method: "POST" \}/);
  expect(offer).not.toContain("saveOrQueue");
  expect(CODE).toMatch(/const handled = editing && isContentAdmin\(user\) \? await offerMergeOnConflict\(err, editing\.id\) : false;/);
  expect(offer).toMatch(/saved<\/span> work moves — unsaved changes here will be lost\./);
});

test("f. the merge answers with the survivor, and the screen is redrawn from it", () => {
  const offer = region("async function offerMergeOnConflict(", "async function submit(");
  expect(offer).toMatch(/const survivor = await apiFetch<QuestionnaireInterview>\(/);
  expect(offer).toMatch(/seedFromInterview\(survivor\);/);
  expect(offer).toMatch(/setMergeNotice\(/);
  expect(offer).toMatch(/await loadInterviews\(\);/);
});

test("f. the conflicting-questions 409 is listed question by question, never swallowed", () => {
  const offer = region("async function offerMergeOnConflict(", "async function submit(");
  expect(offer).toMatch(/const conflicts = mergeAnswerConflicts\(mergeError\);/);
  expect(offer).toMatch(/setMergeConflicts\(conflicts\);/);
  const parse = region("function mergeAnswerConflicts(", "const MERGE_FIELD_LABELS");
  expect(parse).toMatch(/if \(!detail\) return null;/);
  expect(parse).toMatch(/const rows = Array\.isArray\(detail\.questions\) \? detail\.questions : \[\];/);
  expect(CODE).toMatch(/\{row\.sectionCode \? `\[\$\{row\.sectionCode\}\] ` : ""\}/);
  expect(CODE).toMatch(/\{row\.prompt \?\? "Unnamed question"\}/);
  expect(CODE).toMatch(/row\.fields\.map\(conflictFieldLabel\)/);
  expect(CODE).toMatch(/answerText: "the written answer", notes: "the notes"/);
});

test("f. nothing-was-moved is carried by words as well as by the amber panel", () => {
  expect(CODE).toMatch(/Not moved: \{mergeConflicts\.length\} conflicting answer/);
  const panel = region("{mergeConflicts.length ? (", "<CompletionMatrixPanel");
  (panel.match(/amber-\d+/g) ?? []).forEach((token) => expect(["amber-100", "amber-500", "amber-800"]).toContain(token));
  (panel.match(/text-(purple|blue|green|teal)-\d+/g) ?? []).forEach((token) => expect(token).toBe("text-purple-700"));
});

test("these assertions fail on the code and not on the prose explaining the code", () => {
  expect(PAGE).not.toContain("No questions answered yet");
  expect(PAGE).not.toContain("ExistingMedia");
  expect(stripComments("/* answers[question.id] */\nconst a = 1;")).not.toContain("answers[");
  expect(stripComments("// updateCapture({ hideAnswers: false })\nconst b = 2;")).not.toContain("updateCapture");
  expect(stripComments(' * open={index === 0}\nconst c = 3;')).not.toContain("index === 0");
});
