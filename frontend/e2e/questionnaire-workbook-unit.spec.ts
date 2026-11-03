import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

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

function codeOnly(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("*") && !line.trim().startsWith("//"))
    .join("\n");
}

function entryAt(source: string, anchor: string): string {
  const start = source.indexOf(anchor);
  expect(start, `${anchor} does not appear in this file at all`).toBeGreaterThan(-1);
  const rest = source.slice(start);
  const close = /\r?\n\s*\}/.exec(rest);
  return close ? rest.slice(0, close.index) : rest;
}

function functionBody(source: string, name: string, nextName: string): string {
  const start = source.indexOf(`export function ${name}`);
  expect(start, `${name} is not exported from this module`).toBeGreaterThan(-1);
  const end = source.indexOf(`export function ${nextName}`, start);
  return source.slice(start, end === -1 ? source.length : end);
}

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

test("neither upload sets a Content-Type, so the browser writes the multipart boundary itself", () => {
  const api = codeOnly(WORKBOOK_API);
  for (const [name, body] of [
    ["uploadQuestionnaire", functionBody(api, "uploadQuestionnaire", "reuploadQuestionnaire")],
    ["reuploadQuestionnaire", functionBody(api, "reuploadQuestionnaire", "saveWorkbook")]
  ] as const) {
    expect(body, `${name} must not set a Content-Type`).not.toMatch(/[Cc]ontent-?[Tt]ype/);
    expect(body, `${name} must not build a header bag at all`).not.toMatch(/headers/i);
    expect(body, `${name} sends a FormData`).toMatch(/const body = new FormData\(\);/);
    expect(body, `${name} appends the file under the name the route declares`).toMatch(
      /body\.append\("file", file\);/
    );
    expect(body, `${name} goes through apiFetch`).toMatch(/apiFetch<QWorkbookUploadResult>\(/);
  }
  expect(
    functionBody(api, "uploadQuestionnaire", "reuploadQuestionnaire"),
    "the create path sends title and description as form fields"
  ).toMatch(/body\.append\("title", options\.title\)[\s\S]*?body\.append\("description", options\.description\)/);
});

test("no workbook call may be queued for replay, because a File cannot survive the outbox", () => {
  const api = codeOnly(WORKBOOK_API);
  expect(api, "the workbook module must not import the outbox").not.toMatch(/@\/lib\/offline/);
  expect(api, "nor reach saveOrQueue by any other spelling").not.toMatch(/saveOrQueue/);
  for (const page of [codeOnly(LIBRARY_PAGE), codeOnly(FORM_PAGE)]) {
    expect(page, "the library must not queue an upload").not.toMatch(/saveOrQueue|@\/lib\/offline/);
  }
});

test("every problem the parser reported is printed, with no cap and no ellipsis", () => {
  const report = codeOnly(UPLOAD_REPORT);
  expect(report, "no slicing a list of problems").not.toMatch(/\.slice\(/);
  expect(report, "nor the other spelling of it").not.toMatch(/\.substring\(|\.substr\(/);
  expect(report, "no truncation helper").not.toMatch(/truncate/i);
  expect(report, 'no "and N more" affordance of any kind').not.toMatch(/\bmore\b/i);

  expect(report, "every problem row is rendered").toMatch(/problems\.map\(\(problem, index\) =>/);
  expect(report, "every supersede/retire detail is rendered").toMatch(/details\.map\(\(detail\) =>/);
  expect(report, "the heading prints the true count").toMatch(/\{title\} \(\{problems\.length\}\)/);
});

test("the server's own sentences reach the screen verbatim, never paraphrased into a chip", () => {
  const report = codeOnly(UPLOAD_REPORT);
  expect(report, "the provenance sentence is printed as written").toMatch(/\{provenance\.reason\}/);
  expect(report, "so is each problem's").toMatch(/\{problem\.reason\}/);
  expect(report, "and each supersede/retire detail's").toMatch(/\{detail\.reason\}/);
  expect(report, "errors and warnings stay two lists").toMatch(
    /const errors = problems\.filter\(\(problem\) => problem\.severity === "error"\);/
  );
  expect(report).toMatch(/const warnings = problems\.filter\(\(problem\) => problem\.severity !== "error"\);/);
  expect(report, "a problem names its Excel row").toMatch(/Row \$\{problem\.row\}/);
});

test("the assertions above fail on the code and not on the comments explaining the code", () => {
  expect(codeOnly(WORKBOOK_API), "the outbox and the header stay out of the code").not.toMatch(
    /saveOrQueue|Content-Type/
  );
  expect(codeOnly(UPLOAD_REPORT), "the code carries no \"and 12 more\"").not.toMatch(/\bmore\b/i);

  expect(codeOnly(WORKBOOK_API), "the real exports survive stripping").toMatch(
    /export function uploadQuestionnaire\(/
  );
  expect(codeOnly(UPLOAD_REPORT), "and so does the component").toMatch(/export function UploadReport\(/);
});
