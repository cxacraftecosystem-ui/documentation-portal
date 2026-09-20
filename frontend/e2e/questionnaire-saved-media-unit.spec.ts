import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * "ALREADY EXISTING ENTRIES AND MEDIA DO NOT SHOW UP IN THE RESPECTIVE SECTIONS" — pinned, for the
 * WEB half of the questionnaire, plus the two defects that sit underneath it.
 *
 * ── WHAT WAS ON SCREEN ───────────────────────────────────────────────────────────────────────────
 *
 * A researcher opened a recorded interview for editing in the browser and was shown an empty form:
 * empty recorders, empty answer boxes, nine collapsed section headings that said nothing at all, and
 * — on the page's own shared-entry banner — the sentence "No questions answered yet" about a sitting
 * a colleague had recorded end to end.
 *
 * NOTHING WAS ACTUALLY LOST, AND THAT IS WHY IT SURVIVED SO LONG. The answers were seeded correctly
 * by `seedFromInterview`; the recordings were on the record, hydrated by `RELATIONS` and delivered
 * with it. Every one of these was a DISPLAY GATE:
 *
 *  a. THE SAVED RECORDINGS WERE DRAWN NOWHERE. This was the only edit form in the repository with no
 *     saved-media block — `components/media/ExistingMedia` is mounted on artisans, products, tools,
 *     crafts and workshops, and was never mounted here. The handset has drawn them per section, plus
 *     an "Other saved recordings & media" catch-all, since it gained its edit path.
 *  b. THE ANSWER BOXES WERE NOT DRAWN AT ALL, because `hideAnswers` defaults to TRUE
 *     (`DEFAULT_CAPTURE_PREFS`) — a correct default for CAPTURE and a wrong one for an EDIT that is
 *     carrying somebody's typed words.
 *  c. ONLY THE FIRST SECTION WAS OPEN (`open={index === 0}`), so the sections that held the work were
 *     closed.
 *  d. AND A CLOSED SECTION SAID NOTHING ABOUT ITSELF. The `<summary>` printed "D. RAW MATERIALS" and
 *     no counts; Android prints "N questions · M answered · K saved recording(s)" under every
 *     heading, which is the one line on the screen that could have told the researcher their work
 *     was there.
 *  e. THE BANNER COUNTED ONLY `responses`. On an instrument whose sections are captured as one audio
 *     take each with the answer boxes hidden, a correctly recorded sitting has ZERO response rows —
 *     so the one number being measured is the one that is reliably zero for good work.
 *  f. A COLLIDING SAVE WAS A DEAD END. The 409 now carries `{code, message, holder}` and there is a
 *     `merge-into` route to act on it; before this, the form could only print a sentence.
 *  g. AND THE RACE UNDERNEATH ALL OF IT — this repository's alone. Two `loadMeta` runs happen on
 *     every edit with no generation guard, so the DEFAULT instrument's questions could win the race
 *     against the record's own, leaving answers keyed to one instrument beside questions belonging
 *     to another. The section codes of the two instruments collide completely, so nothing looks
 *     wrong: every box is simply blank. Commit 544262b fixed exactly this on the handset ("held
 *     until the record's own sections arrive") and the browser never got it.
 *
 * ── WHY THIS FILE HAS NO `page` ──────────────────────────────────────────────────────────────────
 *
 * Reproducing any of it in a browser needs a saved interview with answers, recordings attached to
 * named sections, a second instrument to lose the race to, and a second interview holding a colliding
 * artisan set. Every failure is also SILENT — the old paths answered 200 and drew a plausible empty
 * form — so there is nothing for a browser assertion to catch. Same shape, and the same argument, as
 * `e2e/questionnaire-edit-unit.spec.ts` and `e2e/questionnaire-artisan-scope-unit.spec.ts`.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const PAGE = read("app", "(protected)", "questionnaire", "page.tsx");
const CAPTURE_CONTROLS = read("components", "forms", "QuestionnaireCaptureControls.tsx");
const EXISTING_MEDIA = read("components", "media", "ExistingMedia.tsx");
const TOOL_FORM = read("components", "forms", "ToolForm.tsx");
/** The contract half. Read from the repository so the client's literals cannot drift off it. */
const ROUTE = readFileSync(
  join(__dirname, "..", "..", "backend", "app", "api", "routes", "questionnaire.py"),
  "utf8"
);

/**
 * The file with its comments removed — the same helper, character for character, as the `codeOnly`
 * in `e2e/questionnaire-edit-unit.spec.ts`, `e2e/questionnaire-artisan-scope-unit.spec.ts` and
 * `e2e/questionnaire-dictation-unit.spec.ts`, copied rather than shared for the reason those three
 * give: a spec's fixtures are part of what it asserts, and a shared one moves under it.
 *
 * IT IS LOAD-BEARING IN THIS FILE MORE THAN IN ANY OF THEM. Every defect above is explained in prose
 * at the site that fixes it, in this repository's house style of long block headers — so the page now
 * contains the literal sentence "No questions answered yet", the words `hideAnswers` beside an
 * argument for not flipping it, and `answers[` inside a paragraph explaining why the open-on-content
 * rule must not read it. Matched against the raw file, the `not.toContain` assertions below would
 * fail on the paragraphs that explain the fix, and the cheapest way to turn a red test green is to
 * delete the explanation.
 */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

const CODE = stripComments(PAGE);

/** One named function's body, so an assertion about it cannot be satisfied from elsewhere. */
function region(start: string, end: string): string {
  const from = CODE.indexOf(start);
  expect(from, `expected to find ${start}`).toBeGreaterThan(-1);
  const to = CODE.indexOf(end, from);
  expect(to, `expected ${end} to follow ${start}`).toBeGreaterThan(from);
  return CODE.slice(from, to);
}

/* ── g. THE RACE ─────────────────────────────────────────────────────────────────────────────────
   First, because it is the one that makes the other four look like they did not work. */

test("g. two loadMeta runs cannot race: the newest generation is the only one that may write", () => {
  // Its own counter, not the list's — the two are independent requests and sharing one would make
  // a list refresh discard a live instrument load.
  expect(CODE).toMatch(/const currentMetaLoad = useRef\(0\);/);
  expect(CODE).toMatch(/const currentInterviewLoad = useRef\(0\);/);

  const loadMeta = region("async function loadMeta(", "const boundInstrumentId");
  // Stamped at the top of the call, so the counter orders the REQUESTS and not their replies.
  expect(loadMeta).toMatch(/const generation = \(currentMetaLoad\.current \+= 1\);/);
  // Two guards: one before the state writes, one in the catch. A superseded request must neither
  // paint its sections nor its error.
  expect(loadMeta.match(/if \(generation !== currentMetaLoad\.current\) return;/g) ?? []).toHaveLength(2);

  // AND THE GUARD IS BEFORE THE FIRST SETTER. A guard placed after `setInstruments` would let a
  // stale wave write half of itself — an instrument list from one request beside sections from
  // another — which is the same bug in a costume.
  const guard = loadMeta.indexOf("if (generation !== currentMetaLoad.current) return;");
  expect(guard).toBeGreaterThan(-1);
  expect(guard).toBeLessThan(loadMeta.indexOf("setInstruments(instrumentList)"));
  expect(guard).toBeLessThan(loadMeta.indexOf("setSections(sectionList)"));
});

test("g. it is counted, not aborted, because apiFetch takes no signal", () => {
  const loadMeta = region("async function loadMeta(", "const boundInstrumentId");
  // The counter IS the mechanism — no signal is threaded through, and none could be.
  expect(loadMeta).toMatch(/const generation = \(currentMetaLoad\.current \+= 1\);/);
  expect(loadMeta).not.toContain("AbortController");
  expect(loadMeta).not.toContain("signal");
  // The two calls that race: the mount effect with no instrument, and the seed with the record's.
  expect(CODE).toMatch(/useEffect\(\(\) => \{\s*loadMeta\(\);\s*\}, \[\]\);/);
  expect(CODE).toMatch(/void loadMeta\(interview\.questionnaireId\);/);
});

/* ── a. THE SAVED RECORDINGS ─────────────────────────────────────────────────────────────────── */

test("a. saved clips are drawn per section, with a catch-all, as the handset does", () => {
  // Two mounts and only two: this section's clips, and everything that files nowhere.
  expect(CODE.match(/<SavedMediaPanel/g) ?? []).toHaveLength(2);
  expect(CODE).toMatch(/items=\{sectionClips\}/);
  expect(CODE).toMatch(/const sectionClips = savedMedia\.bySection\.get\(group\.section\.id\) \?\? \[\];/);
  expect(CODE).toMatch(/items=\{savedMedia\.other\}/);
  expect(CODE).toMatch(/title="Other saved recordings & media"/);
  // The catch-all is fed by SUBTRACTION, so a clip this client cannot file is surfaced rather than
  // dropped. Dropping it is the defect, not a tidier list.
  const grouping = region("const savedMedia = useMemo(", "const sectionsOpenOnEdit");
  expect(grouping).toMatch(/other: all\.filter\(\(media\) => !placed\.has\(media\.id\)\)/);
  // Off the record already in hand — `RELATIONS` hydrates `media` on GET /interviews/{id}.
  expect(grouping).toMatch(/const all = editing\?\.media \?\? \[\];/);
});

test("a. the repository's own ExistingMedia panel exists, is used elsewhere, and is not what is mounted here", () => {
  // It is a real panel and really is the norm on the other edit forms — so choosing against it has
  // to be a choice, which is what this asserts rather than assumes.
  expect(EXISTING_MEDIA).toContain("export function ExistingMedia({");
  expect(TOOL_FORM).toContain("<ExistingMedia");
  // Two reasons it does not fit, both structural and both checkable: its API is flat (one record,
  // one list, no seam to group a SECTION by) and it fetches what the interview record already
  // carries.
  expect(EXISTING_MEDIA).toMatch(/linkedRecordType: string;\s*\n\s*linkedRecordId: string;/);
  expect(EXISTING_MEDIA).toMatch(/listResource<MediaFile>\("\/media", \{ linkedRecordType, linkedRecordId/);
  expect(CODE).not.toContain("ExistingMedia");
  // What is mounted instead, so this is a choice between two panels rather than a page with none.
  expect(CODE).toMatch(/function SavedMediaPanel\(\{/);
  expect(CODE).toMatch(/<SavedMediaPanel/);
});

test("a. saved media is READ-ONLY and never reaches the upload tray", () => {
  const panel = region("function SavedMediaPanel(", 'function CompletionMatrixPanel(');
  // A tile grows a delete affordance the moment it is handed `onRemove`; this one is handed the
  // page's existing preview and nothing else.
  expect(panel).toMatch(/<MediaPreviewTile key=\{preview\.key\} item=\{preview\} onOpen=\{\(\) => onOpenPreview\(preview\)\} \/>/);
  expect(panel).not.toContain("onRemove");
  // Nothing anywhere loads a stored file into either upload map — that would re-upload the whole
  // interview on every save and duplicate it in the completion matrix. Every write to them is a
  // clear.
  (CODE.match(/setMediaFiles\([^)]*\)/g) ?? []).forEach((call) => expect(call).toBe("setMediaFiles([])"));
  // And neither map is ever handed the record's stored files, under any spelling.
  expect(CODE).not.toMatch(/set(MediaFiles|QuestionAudioFiles)\([^;]*savedMedia/);
  expect(CODE).not.toMatch(/set(MediaFiles|QuestionAudioFiles)\([^;]*editing\?\.media/);
  expect(region("function seedFromInterview(", "const { loading: deepLinkLoading }")).toMatch(
    /setMediaFiles\(\[\]\);\s*\n\s*setQuestionAudioFiles\(\{\}\);/
  );
});

test("a. it reuses the page's one lightbox rather than mounting a second", () => {
  expect(CODE.match(/<MediaLightbox/g) ?? []).toHaveLength(1);
  expect(CODE).toMatch(/onOpenPreview=\{setActivePreview\}/);
  // The tile is handed the same shape the freshly recorded clips are, so playback, download and the
  // transcript block are identical for a three-second-old clip and a three-week-old one.
  expect(CODE).toMatch(/function savedMediaPreview\(media: MediaFile\): PreviewMedia/);
  expect(CODE).toMatch(/transcriptText: media\.transcriptText/);
});

test("a. a clip's section is read off its caption, with the digit rule that stops D claiming DA", () => {
  expect(CODE).toMatch(/const SECTION_CAPTION_PREFIX = "Section audio:";/);
  expect(CODE).toMatch(/const QUESTION_CAPTION_PREFIX = "Question audio:";/);
  // Section codes are one to three letters, so "DA1" starts with "D": without the digit test,
  // section D swallows every one of section DA's question clips and DA's panel reads as empty.
  const belongs = region("function captionBelongsToSection(", "function savedMediaPreview(");
  expect(belongs).toMatch(/const next = rest\.charAt\(section\.code\.length\);/);
  expect(belongs).toMatch(/return next >= "0" && next <= "9";/);
  // A whole-section take, and the two question-clip separators — the web writes "D3 - prompt" and
  // the handset writes "D3 prompt", and a recording made on a phone must appear under its question
  // in the browser.
  const wholeSection = region("function captionIsWholeSection(", "function captionAnswersQuestion(");
  expect(wholeSection).toMatch(/rest === section\.code \|\| rest\.startsWith\(`\$\{section\.code\} `\)/);
  const answers = region("function captionAnswersQuestion(", "function captionBelongsToSection(");
  expect(answers).toMatch(/rest === stem \|\| rest\.startsWith\(`\$\{stem\} `\) \|\| rest\.startsWith\(`\$\{stem\}-`\)/);
});

/* ── b. THE RECORDED ANSWERS ─────────────────────────────────────────────────────────────────── */

test("b. an answer the record carries is drawn even while the preference hides the boxes", () => {
  expect(CODE).toMatch(/\{capture\.hideAnswers && !answeredOnRecord\.has\(question\.id\) \? null : \(/);
  // The old gate, named so reinstating it goes red rather than quietly shipping.
  expect(CODE).not.toMatch(/\{capture\.hideAnswers \? null : \(/);
  // Drawn EDITABLE, not read-only: this page is the correction surface, and text a researcher can
  // see but not fix is a screen that shows them the typo and refuses to let them touch it.
  expect(CODE).toMatch(/<TextArea\s*\n\s*aria-labelledby=\{`question-label-\$\{question\.id\}`\}/);
  // And it says why one box is on screen against the setting — a signal nobody can read is not one.
  expect(CODE).toMatch(/Shown because this interview already has a written answer here\./);
});

test("b. the set is frozen at seed time, counts only real words, and is emptied on cancel", () => {
  expect(CODE).toMatch(/const \[answeredOnRecord, setAnsweredOnRecord\] = useState<Set<string>>\(\(\) => new Set\(\)\);/);
  const seed = region("function seedFromInterview(", "const { loading: deepLinkLoading }");
  // `.trim()` is what tells "somebody typed something" from "a response row exists" — the merge
  // route's own "EMPTY survivor rows" branch is written for rows of the second kind.
  expect(seed).toMatch(/\.filter\(\(response\) => response\.questionId && \(response\.answerText \?\? ""\)\.trim\(\)\)/);
  // Emptied with the edit: left behind, it would draw answer boxes on a BLANK capture form for
  // question ids the previous sitting answered, overriding the preference with no record behind it.
  expect(region("function resetToCreate(", "function seedFromInterview(")).toMatch(/setAnsweredOnRecord\(new Set\(\)\);/);
});

test("b. the stored preference is not touched, on the page or in its default", () => {
  // The page reads the preference and never writes it: `updateCapture` is handed to the control that
  // owns it and called from nowhere else.
  expect(CODE.match(/updateCapture/g) ?? []).toHaveLength(2);
  expect(CODE).toMatch(/const \{ prefs: capture, update: updateCapture \} = useCapturePrefs\(\);/);
  expect(CODE).toMatch(/onChange=\{updateCapture\}/);
  expect(CODE).not.toMatch(/updateCapture\(\{/);
  // And the default is still TRUE. Flipping it there would have been the one-line "fix" that
  // silently changes how every future interview is captured on the device.
  expect(stripComments(CAPTURE_CONTROLS)).toMatch(
    /export const DEFAULT_CAPTURE_PREFS: CapturePrefs = \{ recordingMode: "SECTION", hideAnswers: true \};/
  );
  // What moved instead of the preference: the display gate, and only for a question the record
  // already answers. Without this line the two assertions above are satisfied by a page that simply
  // never solved the problem.
  expect(CODE).toMatch(/!answeredOnRecord\.has\(question\.id\)/);
});

/* ── c. THE SECTIONS THAT HAVE SOMETHING IN THEM ─────────────────────────────────────────────── */

test("c. an edit opens every section with content; a create still opens only the first", () => {
  expect(CODE).toMatch(/open=\{index === 0 \|\| sectionsOpenOnEdit\.has\(group\.section\.id\)\}/);
  expect(CODE).not.toMatch(/open=\{index === 0\}/);
  const open = region("const sectionsOpenOnEdit = useMemo(", "const existingEntryTally");
  // Empty in create mode by construction, so the default capture screen is untouched.
  expect(open).toMatch(/if \(!editing\) return open;/);
  expect(open).toMatch(/question\) => answeredOnRecord\.has\(question\.id\)/);
  expect(open).toMatch(/savedMedia\.bySection\.get\(section\.id\)\?\.length \?\? 0\) > 0/);
});

test("c. it reads the frozen record, never the live answers, so a section cannot shut under the caret", () => {
  const open = region("const sectionsOpenOnEdit = useMemo(", "const existingEntryTally");
  // React re-applies `open` whenever the value it renders changes. Derived from live state, clearing
  // the last answer in a section would fold the panel up with the caret inside it.
  expect(open).not.toContain("answers[");
  expect(open).toMatch(/\}, \[editing, sections, answeredOnRecord, savedMedia\]\);/);
});

/* ── d. THE COUNTS ───────────────────────────────────────────────────────────────────────────── */

test("d. every section summary prints questions, answered and saved recordings", () => {
  expect(CODE).toMatch(/\{tally\.questions\} question\{tally\.questions === 1 \? "" : "s"\} · \{tally\.answered\} answered/);
  expect(CODE).toMatch(/\$\{tally\.saved\} saved recording\$\{tally\.saved === 1 \? "" : "s"\}/);
  expect(CODE).toMatch(/const tally = sectionTally\(group\);/);
});

test("d. the counting rule is the handset's: a whole-section take answers the whole section", () => {
  const tally = region("function sectionTally(", "return (");
  expect(tally).toMatch(/clips\.some\(\(media\) => captionIsWholeSection\(media\.caption, group\.section\)\)/);
  expect(tally).toMatch(/\? group\.items\.length/);
  // Otherwise: typed text, a clip staged this visit, or a clip already saved against the question.
  expect(tally).toMatch(/\(answers\[question\.id\] \?\? ""\)\.trim\(\)\.length > 0/);
  expect(tally).toMatch(/\(questionAudioFiles\[question\.id\]\?\.length \?\? 0\) > 0/);
  expect(tally).toMatch(/clips\.some\(\(media\) => captionAnswersQuestion\(media\.caption, question\)\)/);
  // Counted over what is RENDERED, so the number can be verified by looking at the screen.
  expect(tally).toMatch(/questions: group\.items\.length/);
  expect(tally).toMatch(/saved: clips\.length/);
});

/* ── e. THE BANNER ───────────────────────────────────────────────────────────────────────────── */

test("e. the shared-entry banner counts recordings as well as responses", () => {
  const summary = region("function sharedEntrySummary(", "const ARTISAN_SET_TAKEN");
  expect(summary).toMatch(/function sharedEntrySummary\(responses: number, recordings: number\): string/);
  // All four cases, each saying what it FOUND. The only branch allowed to report nothing is the one
  // where both counts really are zero.
  expect(summary).toMatch(/if \(responses && recordings\) return `\$\{clips\} and \$\{answers\} already recorded in it\.`;/);
  expect(summary).toMatch(/if \(recordings\) return `\$\{clips\}, no typed answers yet\.`;/);
  expect(summary).toMatch(/if \(responses\) return `\$\{answers\}, no recordings yet\.`;/);
  expect(summary).toMatch(/return "Nothing recorded in it yet — you can be the first to fill it in\.";/);
  // Wired to the banner off the same record the lookup already returned — `by-artisans` hydrates
  // `media` with the rest of RELATIONS, so counting it costs no request.
  expect(CODE).toMatch(/const recordings = existingEntry\?\.media \?\? \[\];/);
  expect(CODE).toMatch(/\{existingEntryTally\.summary\}/);
});

test("e. the sentence that called a fully recorded interview empty is gone", () => {
  expect(CODE).not.toContain("No questions answered yet");
  // And the banner lists the recordings it counted, so the number can be checked rather than taken.
  expect(CODE).toMatch(/Recordings and files \(\{existingEntryTally\.recordings\.length\}\)/);
  expect(CODE).toMatch(/\{media\.caption \|\| media\.originalFilename\}/);
});

/* ── f. THE MERGE OFFER ──────────────────────────────────────────────────────────────────────── */

test("f. the client branches on the server's code, not on its prose", () => {
  expect(CODE).toMatch(/const ARTISAN_SET_TAKEN = "artisan_set_taken";/);
  expect(CODE).toMatch(/const MERGE_ANSWER_CONFLICT = "merge_answer_conflict";/);
  // THE CONTRACT, read from the route itself. The 409's sentence has been reworded before and any
  // client that greps it stops offering the move the next time somebody improves it.
  expect(ROUTE).toContain('_DUPLICATE_SET_CODE = "artisan_set_taken"');
  expect(ROUTE).toContain('_MERGE_CONFLICT_CODE = "merge_answer_conflict"');
  expect(ROUTE).toContain('@questionnaire_router.post("/interviews/{interview_id}/merge-into/{target_id}")');
  // The structure is on the BODY: `describeApiDetail` has already reduced `message` and thrown the
  // rest away, so the holder cannot be read off `ApiError.message`.
  const detail = region("function conflictDetail(", "function artisanSetHolder(");
  expect(detail).toMatch(/if \(!\(error instanceof ApiError\) \|\| error\.status !== 409\) return null;/);
  expect(detail).toMatch(/const detail = \(payload as \{ detail\?: unknown \}\)\.detail;/);
  // A FastAPI 422 body is a LIST of per-field errors and must never read as a conflict envelope.
  expect(detail).toMatch(/Array\.isArray\(detail\)/);
});

test("f. a null holder falls back to the server's message rather than offering nothing-shaped", () => {
  const holder = region("function artisanSetHolder(", "function mergeAnswerConflicts(");
  expect(holder).toMatch(/if \(typeof id !== "string" \|\| !id\) return null;/);
  // `holder: null` is a real answer the server documents, and it takes the same path as "not this
  // conflict": there is nobody to offer, so the page prints `ApiError.message`.
  expect(CODE).toMatch(/if \(!holder\) return false;/);
  expect(CODE).toMatch(/if \(!handled\) setError\(err instanceof Error \? err\.message : "Unable to save interview"\);/);
});

test("f. the merge route is called only after the researcher confirms, and only on an edit", () => {
  const offer = region("async function offerMergeOnConflict(", "async function submit(");
  // The repo's own dialog, in its recoverable tone.
  expect(offer).toMatch(/const ok = await confirm\(\{/);
  expect(offer).toMatch(/tone: "warning"/);
  expect(offer).toMatch(/confirmLabel: "Move this interview in"/);
  // The decline arm RETURNS before the POST, so there is no path from a 409 to a write.
  const declined = offer.indexOf("if (!ok) {");
  const posted = offer.indexOf("/merge-into/");
  expect(declined).toBeGreaterThan(-1);
  expect(declined).toBeLessThan(posted);
  expect(offer).toMatch(/`\/questionnaire\/interviews\/\$\{sourceId\}\/merge-into\/\$\{holder\.id\}`/);
  expect(offer).toMatch(/\{ method: "POST" \}/);
  // The PATCH is not retried — the refusal stands; the fold is a separate, deliberate call.
  expect(offer).not.toContain("saveOrQueue");
  // Only an edit has a stored source row to move; a create for a taken set is folded server-side.
  expect(CODE).toMatch(/const handled = editing \? await offerMergeOnConflict\(err, editing\.id\) : false;/);
  // The dialog states the one cost that is easy to leave out: the refused PATCH never reached the
  // server, so anything typed since the form opened is not what moves.
  expect(offer).toMatch(/already saved<\/span> on this interview moves\./);
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
  // An empty `questions` list is still a refusal that must be shown: only a non-conflict returns
  // null, and every arm of the catch reports something.
  const parse = region("function mergeAnswerConflicts(", "const MERGE_FIELD_LABELS");
  expect(parse).toMatch(/if \(!detail\) return null;/);
  expect(parse).toMatch(/const rows = Array\.isArray\(detail\.questions\) \? detail\.questions : \[\];/);
  // The panel names the section and the prompt, which is what a researcher can act on — the server
  // resolves both for exactly this reason.
  expect(CODE).toMatch(/\{row\.sectionCode \? `\[\$\{row\.sectionCode\}\] ` : ""\}/);
  expect(CODE).toMatch(/\{row\.prompt \?\? "A question this client could not name"\}/);
  expect(CODE).toMatch(/row\.fields\.map\(conflictFieldLabel\)/);
  // `answerText` / `notes` are columns; the reader should not have to know the schema.
  expect(CODE).toMatch(/answerText: "the written answer", notes: "the notes"/);
});

test("f. nothing-was-moved is carried by words as well as by the amber panel", () => {
  expect(CODE).toMatch(/Nothing was moved: \{mergeConflicts\.length\} question/);
  // The theme defines exactly three amber tokens; 50/200/300/700 are not classes here and would
  // silently render as nothing.
  const panel = region("{mergeConflicts.length ? (", "<CompletionMatrixPanel");
  (panel.match(/amber-\d+/g) ?? []).forEach((token) => expect(["amber-100", "amber-500", "amber-800"]).toContain(token));
  // Purple is the only action colour in this repository.
  (panel.match(/text-(purple|blue|green|teal)-\d+/g) ?? []).forEach((token) => expect(token).toBe("text-purple-700"));
});

/* ── the guard every source-reading spec in this family keeps ─────────────────────────────────── */

test("these assertions fail on the code and not on the prose explaining the code", () => {
  // The page argues each of these rules in a comment at the site that keeps it, so every
  // `not.toContain` above is only meaningful if `stripComments` really strips.
  expect(PAGE).toContain("No questions answered yet");
  expect(CODE).not.toContain("No questions answered yet");
  expect(PAGE).toContain("ExistingMedia");
  expect(CODE).not.toContain("ExistingMedia");
  expect(stripComments("/* answers[question.id] */\nconst a = 1;")).not.toContain("answers[");
  expect(stripComments("// updateCapture({ hideAnswers: false })\nconst b = 2;")).not.toContain("updateCapture");
  expect(stripComments(' * open={index === 0}\nconst c = 3;')).not.toContain("index === 0");
});
