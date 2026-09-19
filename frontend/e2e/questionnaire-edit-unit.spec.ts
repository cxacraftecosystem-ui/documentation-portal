import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * "THERE IS NO WAY TO EDIT THE ALREADY RECORDED QUESTIONNAIRES" — the defect, pinned.
 *
 * ── WHAT WAS ON SCREEN ───────────────────────────────────────────────────────────────────────────
 *
 * The browser could CREATE an interview and DELETE one, and nothing in between. `/data`'s "Edit
 * record" for a questionnaire was declared as
 *
 *     editHref: () => "/questionnaire"
 *
 * — the only arm of eight to throw away the `id` its own signature is handed. The bare route renders
 * the capture form in CREATE mode, so "Edit this interview" and "Take a new interview" landed on the
 * identical blank page. Filling it in POSTed, which files a SECOND sitting rather than correcting
 * the one that was clicked; and because there is one entry per artisan set, that POST either folded
 * the answers into a shared entry nobody had asked for or came back 409.
 *
 * This is the same defect `useEditDeepLink` was written for on /crafts, /workshops and /processes —
 * its own header says so. The questionnaire page is the FOURTH inline-form page and was simply never
 * wired to it. The handset has had the edit path all along (`InterviewEditLoader` →
 * `QuestionnaireForm(editing = …)` → `repository.updateQuestionnaireInterview`), which is why the
 * two clients disagreed about whether this product can edit an interview at all.
 *
 * ── EVERY ASSERTION BELOW THAT WOULD HAVE FAILED BEFORE THE FIX ──────────────────────────────────
 *
 * A test that passes before the change proves nothing, so this is spelled out rather than implied.
 * Checked against `git show HEAD:…page.tsx` / `…data/page.tsx`, where each one is false:
 *
 *  1. **"the page reads ?edit= through the shared hook"** — `useEditDeepLink` did not occur in
 *     page.tsx at all. Neither did `Suspense`, which Next 16 requires around the `useSearchParams`
 *     that hook reads.
 *  2. **"an edit PATCHes the record's own path"** — the only interview write was a literal
 *     `method: "POST"` to a literal `"/questionnaire/interviews"`. There was no `editing` state to
 *     branch on.
 *  3. **"the update body never names an instrument"** — vacuous before the fix (there was no update
 *     body), and the assertion that matters most now: `QuestionnaireInterviewUpdate` declares no
 *     `questionnaireId` and `APIModel` is extra="forbid", so sending one is a 422 raised by pydantic
 *     before the handler runs. A sitting is half of @@unique([questionnaireId, artisanSetKey]) and
 *     its answers are keyed to that instrument's question ids.
 *  4. **"an edit does not restamp when the capture happened"** — `recordedAt` IS accepted by the
 *     update schema, which is exactly why leaving it out has to be asserted rather than assumed.
 *     Sending it would rewrite the stamp to whatever the remounted "Captured at" row reads, silently
 *     restamping last week's fieldwork every time somebody fixes a typo in its title.
 *  5. **"an edit with no fix does not clear the coordinates"** — `forbid_clearing_location` rejects
 *     an explicit null ("omit to keep, send to replace"), so the key has to be absent and not null.
 *  6. **"the instrument picker is disabled on an edit"** — it was disabled only while saving or
 *     while the list was empty. This is the web half of the rule `MainActivity.kt` already keeps.
 *  7. **"the form remounts per record"** — there was no `key` on the form, so the uncontrolled half
 *     (notes, status, location, capture rows) would have kept the previous occupant's values.
 *  8. **"the browse table offers Edit"** — the row's only action was Delete, behind `adminMode`.
 *  9. **"the data browser carries the id"** — the `editHref` above.
 * 10. **"the shared-entry banner is not shown against the record being edited"** — the lookup keys
 *     on the artisan set and instrument, which an open edit normally still has both of, so the panel
 *     would have announced the record to itself and promised that saving "adds to" it.
 * 11. **"the workshop effect cannot re-point an open edit"** — guarded only by `instrumentTouched`.
 *
 * ── WHY THIS FILE HAS NO `page` ──────────────────────────────────────────────────────────────────
 *
 * Reproducing it in a browser needs a saved interview, its answers, its artisan set and a second
 * instrument to collide with. The failure is also silent — the old path answered 201 — so there is
 * nothing for a browser assertion to catch. Same shape, and the same argument, as
 * `e2e/questionnaire-artisan-scope-unit.spec.ts` and `e2e/questionnaire-dictation-unit.spec.ts`.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");
const PAGE = read("app", "(protected)", "questionnaire", "page.tsx");
const DATA_PAGE = read("app", "(protected)", "data", "page.tsx");

/**
 * The file with its comments removed — the same helper, character for character, as
 * `e2e/questionnaire-artisan-scope-unit.spec.ts` and `e2e/questionnaire-dictation-unit.spec.ts` use
 * on this same page, and copied rather than shared for the reason those two give: a spec's fixtures
 * are part of what it asserts, and a shared one moves under it.
 *
 * IT IS LOAD-BEARING HERE. This page's comments necessarily spell out `questionnaireId`,
 * `recordedAt` and `"POST"` while explaining why the update body leaves them out — the exact strings
 * several assertions below forbid. Matched against the raw file those assertions would fail on the
 * paragraph explaining the fix rather than on the defect, and the cheapest way to make a red test
 * green is to delete the paragraph that explains the code.
 */
function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

const CODE = codeOnly(PAGE);
const DATA_CODE = codeOnly(DATA_PAGE);

/** The two interview payload literals, sliced apart so each can be asserted on independently. */
function payloadBranches(): { edit: string; create: string } {
  const start = CODE.indexOf("const interviewPayload = editingId");
  expect(start, "`const interviewPayload = editingId` is how this spec finds the two bodies").toBeGreaterThan(-1);
  const end = CODE.indexOf("const outcome = await saveOrQueue", start);
  expect(end, "the payload is expected to be built immediately before the save").toBeGreaterThan(start);
  const region = CODE.slice(start, end);
  /*
    The create branch begins at the ternary's `: {`, and it has to be matched as the start of a
    MULTI-LINE object — `/\n\s*:\s*\{\s*\n/` — rather than as the substring ": {".

    The edit body contains `...(location ? { location } : {})`, whose `: {})` carries ": {" inside
    it. A plain indexOf therefore split the ternary in the middle of the edit branch and handed the
    location spread to the create side, so the one assertion that proves an edit cannot clear a
    sitting's coordinates was reading the wrong half. It failed rather than passing wrongly, which
    is the only reason it was noticed.
  */
  const split = region.search(/\n\s*:\s*\{\s*\n/);
  expect(split, "the payload is expected to be a ternary with an object literal on each side").toBeGreaterThan(-1);
  return { edit: region.slice(0, split), create: region.slice(split) };
}

test("the page reads ?edit= through the shared hook, inside the Suspense boundary Next 16 needs", () => {
  expect(CODE).toContain('import { useEditDeepLink } from "@/components/hooks/useEditDeepLink"');
  // Endpoint and basePath, so the record is fetched BY ID and the spent parameter is stripped back
  // to this page's own route rather than to whichever route happened to be current.
  expect(CODE).toMatch(/endpoint:\s*"\/questionnaire\/interviews"/);
  expect(CODE).toMatch(/basePath:\s*"\/questionnaire"/);
  // The hook reads useSearchParams; without a boundary the route fails to build.
  expect(CODE).toMatch(/<Suspense\b/);
  expect(CODE).toMatch(/import \{ Suspense,/);
});

test("an edit PATCHes the record's own path and a capture still POSTs to the collection", () => {
  expect(CODE).toMatch(/method:\s*editingId\s*\?\s*"PATCH"\s*:\s*"POST"/);
  expect(CODE).toMatch(/endpoint:\s*editingId\s*\?\s*`\/questionnaire\/interviews\/\$\{editingId\}`/);
  // `saveOrQueue` takes the method, so a correction made offline is banked and replays as the PATCH
  // it was — not as a second sitting. If this ever became a bare apiFetch the offline path is lost.
  expect(CODE).toMatch(/const outcome = await saveOrQueue<QuestionnaireInterview>\(\{/);
});

test("the update body never names an instrument, because the API forbids the field", () => {
  const { edit, create } = payloadBranches();
  expect(edit).not.toContain("questionnaireId");
  // Still sent on a create — the whole point of the picker, and what stops the server resolving the
  // sitting onto whatever the default has become by replay time.
  expect(create).toContain("questionnaireId: questionnaireId || null");
});

test("an edit does not restamp when the capture happened", () => {
  const { edit, create } = payloadBranches();
  expect(edit).not.toContain("recordedAt");
  expect(edit).not.toContain("recordedTimezone");
  expect(create).toContain("recordedAt");
  expect(create).toContain("recordedTimezone");
});

test("an edit omits location rather than sending null, which would be refused as a clear", () => {
  const { edit, create } = payloadBranches();
  // Spread only when there is one: `forbid_clearing_location` is "omit to keep, send to replace".
  expect(edit).toMatch(/\.\.\.\(location \? \{ location \} : \{\}\)/);
  // A create has nothing to clear, so null is the ordinary "no fix was available".
  expect(create).toMatch(/\blocation\b/);
});

test("the instrument picker is disabled on an edit, as it is on the handset", () => {
  expect(CODE).toMatch(/disabled=\{saving \|\| Boolean\(editing\) \|\| instrumentOptions\.length === 0\}/);
  // And the record's own instrument is merged into the options, so a sitting on a RETIRED
  // instrument still draws its own name instead of a blank on a control nobody can correct.
  expect(CODE).toMatch(/const instrumentOptions = useMemo\(/);
  expect(CODE).toMatch(/if \(own && !rows\.some\(\(row\) => row\.id === own\.id\)\) rows\.push\(own\)/);
});

test("the form remounts per record, so the uncontrolled boxes re-seed", () => {
  expect(CODE).toMatch(/key=\{editing\?\.id \?\? "new"\}/);
  expect(CODE).toMatch(/ref=\{formRef\}/);
  // The uncontrolled half reads its value once per mount, which is what the key is for.
  expect(CODE).toMatch(/defaultValue=\{editing\?\.notes \?\? null\}/);
  expect(CODE).toMatch(/defaultValue=\{editing\?\.status \?\? "APPROVED"\}/);
});

test("the answers are seeded by question id, not by position", () => {
  // A response whose question was retired or reordered must not land on a different question.
  expect(CODE).toMatch(/\.map\(\(response\) => \[response\.questionId, response\.answerText \?\? ""\]\)/);
  // And the artisan set comes off the hydrated link rows.
  expect(CODE).toMatch(/\(interview\.artisans \?\? \[\]\)\.map\(\(link\) => link\.artisan\?\.id\)/);
});

test("the browse table offers Edit, and it is the same ?edit= navigation as everywhere else", () => {
  expect(CODE).toMatch(/href=\{`\/questionnaire\?edit=\$\{interview\.id\}`\}/);
  // Delete stays behind adminMode; Edit does not, because the server decides who may write.
  expect(CODE).toMatch(/\{adminMode \? \(\s*<button className=\{rowAction\("danger"\)\}/);
});

test("the data browser carries the interview id instead of dropping it", () => {
  expect(DATA_CODE).toMatch(/editHref: \(id\) => `\/questionnaire\?edit=\$\{id\}`/);
  // The bug, named so reinstating it goes red rather than quietly shipping.
  expect(DATA_CODE).not.toMatch(/editHref: \(\) => "\/questionnaire"/);
});

test("the shared-entry banner is not shown against the record being edited", () => {
  expect(CODE).toMatch(/existingEntry && existingEntry\.id !== editing\?\.id/);
});

test("the workshop effect cannot re-point the instrument under an open edit", () => {
  expect(CODE).toMatch(/if \(editing \|\| instrumentTouched\.current\) return;/);
});

test("an edit carries nothing forward and leaves edit mode on save", () => {
  // The carry bag seeds the NEXT capture with where the researcher is now; a correction to last
  // week's sitting is no evidence of that, and re-arming it would hand the next form a stale
  // workshop and a stale artisan.
  expect(CODE).toMatch(/if \(editingId\) \{\s*resetToCreate\(\);\s*await loadInterviews\(\);\s*return;\s*\}/);
  expect(CODE).toMatch(/function resetToCreate\(\)/);
});

test("these assertions fail on the code and not on the comments explaining the code", () => {
  // The guard the other questionnaire specs keep: `codeOnly` has to actually strip, or every
  // `not.toContain` above is satisfied by prose rather than by the absence of a field.
  expect(PAGE).toContain("questionnaireId");
  expect(codeOnly("/* questionnaireId: x */\nconst a = 1;")).not.toContain("questionnaireId");
  expect(codeOnly("// recordedAt\nconst b = 2;")).not.toContain("recordedAt");
  expect(codeOnly(" * editHref: () => \"/questionnaire\"\nconst c = 3;")).not.toContain("editHref");
});
