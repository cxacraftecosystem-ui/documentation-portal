import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * THE WORKBOOK'S REGISTRATIONS, NOW THAT IT LIVES IN THE FORMS LIBRARY (UNIFY U3).
 *
 * The pro-forma, a new form from a workbook and each form's own workbook moved from the admin-only
 * `/questionnaire/workbooks` into `/questionnaires`, which every record creator opens and which draws
 * each control from the server's `can`. So the registrations changed shape, and each is checked here
 * because none of them is checked by a type:
 *
 *   1. `ROUTE_GUARDS` (lib/permissions.ts) has NO row for the library or the old workbook route:
 *      both are open pages, and the server gates every write.
 *   2. `NAV_ITEMS` (components/DynamicIslandNav.tsx) carries "My questionnaires" in Record.
 *   3. `ADMIN_CHROME_ROUTES` (components/AdminViewProvider.tsx) no longer lists the workbook leaf.
 *   4. The settings-hub tile (app/(protected)/admin/page.tsx) opens the library.
 *   5. `/questionnaire/workbooks` and `/questionnaire/workbooks/[id]` redirect into it.
 *
 * ── WHY THIS SPEC NEVER OPENS A BROWSER ────────────────────────────────────────────────────────
 *
 * Reproducing any of the four failures on screen needs a signed-in admin, a second signed-in user of
 * a lower tier, and the admin-view toggle flipped between them; three of the four failures are
 * ABSENCES, which a page cannot be asked about. This repository also has no React renderer in its
 * devDependencies — Playwright is the whole of it — so mounting a component is not available either.
 * `record-pickers-unit.spec.ts` and `record-form-dictation-unit.spec.ts` read their structural halves
 * out of source for exactly this reason, and this is the same shape and the same argument.
 *
 * WHAT THIS FILE CANNOT PROVE, stated here rather than left to be rediscovered: that the four
 * registrations behave correctly at runtime. It proves they EXIST and that they agree with each
 * other. `e2e/feature-entry-points.spec.ts` is the half that drives a browser.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const PERMISSIONS = read("lib", "permissions.ts");
const NAV = read("components", "DynamicIslandNav.tsx");
const ADMIN_VIEW = read("components", "AdminViewProvider.tsx");
const ADMIN_PAGE = read("app", "(protected)", "admin", "page.tsx");
const WORKBOOK_API = read("components", "questionnaires", "workbookApi.ts");
const UPLOAD_REPORT = read("components", "questionnaires", "UploadReport.tsx");
const WORKBOOKS_PAGE = read("app", "(protected)", "questionnaire", "workbooks", "page.tsx");
const WORKBOOK_PAGE = read("app", "(protected)", "questionnaire", "workbooks", "[id]", "page.tsx");
const LIBRARY_PAGE = read("app", "(protected)", "questionnaires", "page.tsx");
const FORM_PAGE = read("app", "(protected)", "questionnaires", "[id]", "page.tsx");

const ROUTE = "/questionnaires";
const OLD_ROUTE = "/questionnaire/workbooks";

/**
 * The source with its comments removed — and on THIS subject that is the difference between a test
 * and a test-shaped thing that always passes.
 *
 * `workbookApi.ts` opens with a paragraph headed "NONE OF THESE CALLS MAY EVER GO THROUGH
 * `saveOrQueue` (lib/offline.ts)", and goes on to name `@/lib/offline` and `Content-Type` several
 * more times while explaining why neither may appear. `UploadReport.tsx` carries a comment reading
 * `NO "and 12 more"`. Every one of those is the RIGHT thing for those files to say and the exact
 * string this spec is hunting for: run the obvious `expect(source).not.toMatch(/saveOrQueue/)` over
 * the raw text and it fails on a file that is correct, so the next person deletes the assertion —
 * or, far worse, writes it as a positive match and gets a green from prose while the import it was
 * meant to forbid sits three lines below. §4 below asserts that this function is load-bearing by
 * checking both halves: the raw source names these things, the code does not.
 *
 * A COPY, NOT AN IMPORT, AND THE COPY IS DELIBERATE. The original is a module-private function in
 * `e2e/record-form-dictation-unit.spec.ts` (the record-form dictation sweep wrote it; a handoff
 * citing `record-pickers-unit.spec.ts:77-83` is naming the file the READING-SOURCE technique came
 * from, not the helper). It is not exported, and a spec file cannot import from another spec file
 * without registering that file's twenty-odd `test()` calls a second time inside this one — the same
 * assertions running twice under two names, with every failure reported against the wrong file.
 * Hoisting it into a shared `e2e/` helper is the right end state and is a one-line change to a file
 * this lane does not own; until then, the rule is duplicated and this paragraph is the pointer
 * between the two copies. Keep them identical.
 */
function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

/**
 * One object literal out of a table, from the line that identifies it to the line that closes it.
 *
 * WHY THE ENTRY AND NOT THE FILE. "`adminSurface: true` appears somewhere in DynamicIslandNav.tsx"
 * is true of eight other entries and says nothing at all about this one; the assertion that matters
 * is that the flag sits in the SAME entry as this href. Reading the whole file would go green on the
 * day somebody registers the workbook route with no flag, because the settings hub two lines below
 * still has one.
 *
 * A WINDOW OF N CHARACTERS WOULD HAVE BEEN THE OTHER SPELLING, and it is the wrong one: a character
 * budget over a multi-line region measures FORMATTING, not the rule. `record-form-dictation-unit`
 * has a paragraph on the day a working tree switched to CRLF, every newline inside a window started
 * costing two characters instead of one, and a passing test went red with no source change at all.
 * This finds the end of the literal instead.
 */
function entryAt(source: string, anchor: string): string {
  const start = source.indexOf(anchor);
  expect(start, `${anchor} does not appear in this file at all`).toBeGreaterThan(-1);
  const rest = source.slice(start);
  const close = /\r?\n\s*\}/.exec(rest);
  return close ? rest.slice(0, close.index) : rest;
}

/** The named export's body, from its own `export function` line to the next one. */
function functionBody(source: string, name: string, nextName: string): string {
  const start = source.indexOf(`export function ${name}`);
  expect(start, `${name} is not exported from this module`).toBeGreaterThan(-1);
  const end = source.indexOf(`export function ${nextName}`, start);
  return source.slice(start, end === -1 ? source.length : end);
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The registrations
 * ──────────────────────────────────────────────────────────────────────────── */

test("neither the library nor the old workbook route is locked by a client guard", () => {
  const guards = codeOnly(PERMISSIONS);
  expect(guards, "ROUTE_GUARDS must still be the exported table AppShell reads").toMatch(
    /export const ROUTE_GUARDS: RouteGuard\[\] = \[/
  );
  expect(guards).not.toContain(`path: "${OLD_ROUTE}"`);
  expect(guards).not.toContain(`path: "${ROUTE}"`);
});

test("the library has a way in from the menu, in Record, for every record creator", () => {
  const nav = codeOnly(NAV);
  const entry = entryAt(nav, `href: "${ROUTE}"`);
  expect(entry).toMatch(/label: "My questionnaires"/);
  expect(entry).toMatch(/group: "Record"/);
  expect(entry).toMatch(/can: canCreateRecords/);
  expect(entry, "an open page is not admin chrome").not.toMatch(/adminSurface/);
  expect(nav, "the admin-only workbook entry is gone").not.toContain(`href: "${OLD_ROUTE}"`);
});

test("the old workbook leaf is no longer admin chrome", () => {
  const chrome = codeOnly(ADMIN_VIEW);
  expect(chrome, "ADMIN_CHROME_ROUTES must still be the exported table AppShell reads").toMatch(
    /export const ADMIN_CHROME_ROUTES: AdminChromeRoute\[\] = \[/
  );
  expect(chrome).not.toContain(`path: "${OLD_ROUTE}"`);
  expect(chrome, "/questionnaire itself is not admin chrome and must never be listed").not.toMatch(/path: "\/questionnaire",/);
});

test("the settings hub's tile opens the library", () => {
  const hub = codeOnly(ADMIN_PAGE);
  const entry = entryAt(hub, `href: "${ROUTE}"`);
  expect(entry, "the tile needs an icon or it reads as a gap in the grid").toMatch(/icon: /);
  const hubEntry = hub.slice(Math.max(0, hub.indexOf(`href: "${ROUTE}"`) - 600), hub.indexOf(`href: "${ROUTE}"`));
  expect(hubEntry, "the tile is named").toMatch(/label: "My questionnaires"/);
  expect(hubEntry, "and says what it does").toMatch(/description: "/);
});

test("old workbook links land in the library", () => {
  expect(codeOnly(WORKBOOKS_PAGE)).toContain('router.replace("/questionnaires")');
  expect(codeOnly(WORKBOOK_PAGE)).toContain("router.replace(`/questionnaires/${encodeURIComponent(params.id)}`)");
});

test("the library offers the workbook to form authors, and a form's own workbook to its editors", () => {
  const library = codeOnly(LIBRARY_PAGE);
  expect(library).toContain("const author = canManageQuestionnaire(user);");
  expect(library).toContain("downloadProForma()");
  expect(library).toContain('onClick={() => setDialog("upload")}');
  const form = codeOnly(FORM_PAGE);
  expect(form).toContain("{can.edit ? (");
  expect(form).toContain("downloadQuestionnaireWorkbook(form.id)");
  expect(form).toContain("downloadQuestionSet(form.id)");
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. The upload calls: multipart, and never through the outbox
 * ──────────────────────────────────────────────────────────────────────────── */

test("neither upload sets a Content-Type, so the browser writes the multipart boundary itself", () => {
  const api = codeOnly(WORKBOOK_API);
  for (const [name, body] of [
    ["uploadQuestionnaire", functionBody(api, "uploadQuestionnaire", "reuploadQuestionnaire")],
    ["reuploadQuestionnaire", functionBody(api, "reuploadQuestionnaire", "saveWorkbook")]
  ] as const) {
    // A `FormData` body carries its own boundary token, which the browser can only supply if nobody
    // has set the header by hand. `apiFetch` skips its JSON header for exactly this reason
    // (lib/api.ts); writing `Content-Type: multipart/form-data` back in produces a body the server
    // cannot parse and a 422 whose detail says nothing an admin can act on.
    expect(body, `${name} must not set a Content-Type`).not.toMatch(/[Cc]ontent-?[Tt]ype/);
    expect(body, `${name} must not build a header bag at all`).not.toMatch(/headers/i);
    expect(body, `${name} sends a FormData`).toMatch(/const body = new FormData\(\);/);
    expect(body, `${name} appends the file under the name the route declares`).toMatch(
      /body\.append\("file", file\);/
    );
    expect(body, `${name} goes through apiFetch`).toMatch(/apiFetch<QWorkbookUploadResult>\(/);
  }
  // `title` and `description` are `Form(...)` parameters on the same multipart body, not query
  // string. Appended to the URL they are silently ignored: an untitled questionnaire, created with a
  // 201 saying it went fine.
  expect(
    functionBody(api, "uploadQuestionnaire", "reuploadQuestionnaire"),
    "the create path sends title and description as form fields"
  ).toMatch(/body\.append\("title", options\.title\)[\s\S]*?body\.append\("description", options\.description\)/);
});

test("no workbook call may be queued for replay, because a File cannot survive the outbox", () => {
  const api = codeOnly(WORKBOOK_API);
  // `saveOrQueue` serialises a JSON body to a string and replays it when the device is next online.
  // A `File` does not serialise out of one, and `created: true` means nothing for an upload whose
  // entire result is a change report. Routed through the outbox this produces a queued entry that
  // can never drain and a badge on the nav that never clears — and the admin believes the workbook
  // was applied.
  expect(api, "the workbook module must not import the outbox").not.toMatch(/@\/lib\/offline/);
  expect(api, "nor reach saveOrQueue by any other spelling").not.toMatch(/saveOrQueue/);
  // And the screen that calls it must not queue on its behalf either — the module being clean is no
  // defence if the page wraps the call.
  for (const page of [codeOnly(LIBRARY_PAGE), codeOnly(FORM_PAGE)]) {
    expect(page, "the library must not queue an upload").not.toMatch(/saveOrQueue|@\/lib\/offline/);
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The report is the feature, so nothing in it may be trimmed
 * ──────────────────────────────────────────────────────────────────────────── */

test("every problem the parser reported is printed, with no cap and no ellipsis", () => {
  const report = codeOnly(UPLOAD_REPORT);
  // An admin who uploads eighty-one questions and is shown seventy-nine, with no way to find out
  // which two are missing or why, does not trust the import again — and every likely cause (a merged
  // cell, a formula Excel never calculated, "maybe" typed in the Required column) is invisible from
  // the result. The parser has ALREADY capped what it reports at the source (MAX_QUESTIONS /
  // MAX_SECTIONS in backend/app/services/questionnaire_xlsx.py), so a second cap here would hide rows
  // that survived the first.
  expect(report, "no slicing a list of problems").not.toMatch(/\.slice\(/);
  expect(report, "nor the other spelling of it").not.toMatch(/\.substring\(|\.substr\(/);
  expect(report, "no truncation helper").not.toMatch(/truncate/i);
  // "and 12 more" is the specific shape this forbids: a reader who sees the first five of forty
  // problems concludes the import went mostly fine. If the list is long, the workbook is wrong, and
  // the LENGTH IS THE MESSAGE.
  expect(report, 'no "and N more" affordance of any kind').not.toMatch(/\bmore\b/i);

  // The positive half: the lists are mapped whole, and the count printed is the real length.
  expect(report, "every problem row is rendered").toMatch(/problems\.map\(\(problem, index\) =>/);
  expect(report, "every supersede/retire detail is rendered").toMatch(/details\.map\(\(detail\) =>/);
  expect(report, "the heading prints the true count").toMatch(/\{title\} \(\{problems\.length\}\)/);
});

test("the server's own sentences reach the screen verbatim, never paraphrased into a chip", () => {
  const report = codeOnly(UPLOAD_REPORT);
  // `reason` is written on the server to be shown as-is, on all three of these. This component is the
  // third place in the stack that could paraphrase the answers-are-not-imported rule and the one
  // where paraphrasing it would cost an admin their understanding of where answers live.
  expect(report, "the provenance sentence is printed as written").toMatch(/\{provenance\.reason\}/);
  expect(report, "so is each problem's").toMatch(/\{problem\.reason\}/);
  expect(report, "and each supersede/retire detail's").toMatch(/\{detail\.reason\}/);
  // Errors and warnings are never merged: an "error" is a row where NOTHING was stored, a "warning"
  // is a row that was stored with something assumed. Those are two different jobs for the admin.
  expect(report, "errors and warnings stay two lists").toMatch(
    /const errors = problems\.filter\(\(problem\) => problem\.severity === "error"\);/
  );
  expect(report).toMatch(/const warnings = problems\.filter\(\(problem\) => problem\.severity !== "error"\);/);
  // The row number is what makes a problem actionable: "row 34" means press Ctrl+G and type 34.
  expect(report, "a problem names its Excel row").toMatch(/Row \$\{problem\.row\}/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. The guard's own guard
 * ──────────────────────────────────────────────────────────────────────────── */

test("the assertions above fail on the code and not on the comments explaining the code", () => {
  /*
    THIS TEST EXISTS BECAUSE §2 AND §3 WOULD OTHERWISE BE UNFALSIFIABLE IN THE OTHER DIRECTION.

    Both of those files argue their rules at length in prose, and the prose necessarily names the
    exact strings the rules forbid. If `codeOnly` ever stopped removing comments — a refactor to
    line-comment stripping only, a `/*` inside a template literal swallowing half a file — §2 and §3
    would go RED on correct code, and the cheapest way to make a red test green is to weaken it. So
    the helper's behaviour is asserted directly, from both ends: the raw file says these words, the
    code-only view does not.
  */
  expect(WORKBOOK_API, "the module still explains why the outbox is forbidden").toMatch(/saveOrQueue/);
  expect(WORKBOOK_API, "and still names the header nobody may set").toMatch(/Content-Type/);
  expect(codeOnly(WORKBOOK_API), "but strips both to nothing in the code").not.toMatch(
    /saveOrQueue|Content-Type/
  );

  expect(UPLOAD_REPORT, 'the report still carries its NO "and 12 more" comment').toMatch(/\bmore\b/);
  expect(codeOnly(UPLOAD_REPORT), "and the code carries no such word").not.toMatch(/\bmore\b/i);

  // And the stripper must not be so eager that it eats the code around a comment — a dropped
  // `saveOrQueue` line would otherwise look exactly like a clean module.
  expect(codeOnly(WORKBOOK_API), "the real exports survive stripping").toMatch(
    /export function uploadQuestionnaire\(/
  );
  expect(codeOnly(UPLOAD_REPORT), "and so does the component").toMatch(/export function UploadReport\(/);
});
