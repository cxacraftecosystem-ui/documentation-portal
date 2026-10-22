import { readFileSync } from "node:fs";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  bindingsFor,
  decodeBundleBytes,
  defaultTab,
  emptyLine,
  formSummary,
  groupByKind,
  isBundleName,
  isOffline,
  KIND_LABELS,
  kindLabel,
  kindOptions,
  libraryClient,
  libraryError,
  libraryTabs,
  listCacheKey,
  P16,
  p17,
  readFormsCache,
  savedCopyLine,
  sourceLabel,
  summariseBundle,
  WORKSHOP_TYPES,
  writeFormsCache
} from "@/lib/questionnaireLibrary";
import type { User } from "@/lib/types";

/**
 * The forms library on Field Repository web (UNIFY U3, section 4.2): the tabs, kinds, bindings,
 * bundles, the prompts P16 and P17, the error codes it shows first, and the calls each screen makes.
 * The same flows run against a real API in `questionnaire-library-stack.spec.ts`.
 */

const root = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(root, ...parts), "utf8");
const shared = (name: string) => JSON.parse(readFileSync(join(root, "..", "shared", name), "utf8"));
const LIBRARY_PAGE = read("app", "(protected)", "questionnaires", "page.tsx");
const FORM_PAGE = read("app", "(protected)", "questionnaires", "[id]", "page.tsx");
const DIALOGS = read("components", "questionnaires", "FormDialogs.tsx");
const UPLOAD = read("components", "questionnaires", "WorkbookUploadDialog.tsx");
const WORKBOOK_API = read("components", "questionnaires", "workbookApi.ts");

const person = (role: string, extra: Partial<User> = {}): User => ({ id: `u-${role}`, email: `${role}@example.org`, name: role, role, ...extra }) as User;

/** Every call the library makes, recorded: method, path and body. */
function recorder() {
  const calls: { method: string; path: string; body: unknown; headers: Headers }[] = [];
  const fetcher = async <T>(path: string, init: RequestInit = {}): Promise<T> => {
    calls.push({ method: init.method ?? "GET", path, body: init.body ? JSON.parse(String(init.body)) : null, headers: new Headers(init.headers) });
    return {} as T;
  };
  return { calls, client: libraryClient(fetcher) };
}

test("@qf:D3 the tabs: Mine first for a form author; Deleted for authors and content admins", () => {
  expect(libraryTabs(person("PROFESSOR")).map((t) => t.label)).toEqual(["Mine", "Shared", "Workshop", "Deleted"]);
  expect(libraryTabs(person("RESEARCHER")).map((t) => t.label)).toEqual(["Mine", "Shared", "Workshop"]);
  expect(libraryTabs(person("ADMIN")).map((t) => t.id)).toEqual(["mine", "shared", "workshop", "deleted"]);
  expect(libraryTabs(person("MINISTRY_ADMIN")).map((t) => t.id)).toContain("deleted");
  expect(defaultTab(person("PROFESSOR"))).toBe("mine");
  expect(defaultTab(person("RESEARCHER"))).toBe("shared");
  expect(defaultTab(person("RESEARCHER", { canManageQuestionnaire: true }))).toBe("mine");
  expect(emptyLine("mine")).toBe("You haven't made a form yet.");
  // New form is a form author's, and creates under an Idempotency-Key.
  expect(LIBRARY_PAGE).toContain("const author = canManageQuestionnaire(user);");
  expect(LIBRARY_PAGE).toContain('onClick={() => setDialog("new")}');
  expect(DIALOGS).toContain("await library.create({ title: title.trim(), description: description.trim() || null, kind: kind || null })");
});

test("@qf:A6 a form of your own: create, then build its sections and questions on its page", async () => {
  const { calls, client } = recorder();
  await client.create({ title: "Craft survey 2026", kind: "MARKET_SURVEY" });
  expect(calls[0]).toMatchObject({ method: "POST", path: "/questionnaires", body: { title: "Craft survey 2026", kind: "MARKET_SURVEY" } });
  expect(calls[0].headers.get("Idempotency-Key")).toMatch(/^form-[0-9a-f]{32}$/);
  // The form page embeds the builder, editable by its author or a content admin (the server's `can`).
  expect(FORM_PAGE).toContain("<QuestionnaireAdminEditor");
  expect(FORM_PAGE).toContain("mayEdit={can.edit && online}");
  expect(FORM_PAGE).toContain("mayDelete={can.delete && online}");
});

test("@qf:A8 kinds: the shared vocabulary, a kind per form, and forms grouped by kind", async () => {
  const enums = shared("questionnaire-enums.json");
  expect(Object.keys(KIND_LABELS)).toEqual(enums.enums.QuestionnaireKind);
  expect(KIND_LABELS).toEqual(enums.labels.QuestionnaireKind);
  expect(kindLabel(null)).toBe("Kind not stated");
  expect(kindLabel("NEW_KIND")).toBe("NEW_KIND");
  expect(kindOptions().map((o) => o.value)).toEqual(["", "WORKSHOP_INTERVIEW", "MARKET_SURVEY"]);
  const groups = groupByKind([
    { id: "a", kind: null },
    { id: "b", kind: "MARKET_SURVEY" },
    { id: "c", kind: "WORKSHOP_INTERVIEW" },
    { id: "d", kind: "MARKET_SURVEY" }
  ]);
  expect(groups.map((g) => g.label)).toEqual(["Workshop interview", "Market survey", "Kind not stated"]);
  expect(groups[1].forms.map((f) => f.id)).toEqual(["b", "d"]);
  const { calls, client } = recorder();
  await client.patch("q1", { kind: "WORKSHOP_INTERVIEW" });
  await client.list("mine", { kind: "none" });
  expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual(["PATCH /questionnaires/q1", "GET /questionnaires?scope=mine&kind=none"]);
  expect(FORM_PAGE).toContain("await library.patch(form.id, { kind: value || null });");
});

test("@qf:A7 share with everyone is an admin's switch", async () => {
  const { calls, client } = recorder();
  await client.patch("q1", { isShared: true });
  await client.list("shared");
  expect(calls[0]).toMatchObject({ method: "PATCH", body: { isShared: true } });
  expect(calls[1].path).toBe("/questionnaires?scope=shared");
  expect(FORM_PAGE).toContain("{can.share ? (");
  expect(FORM_PAGE).toContain('label="Share with everyone"');
});

test("@qf:B32 the default form is an admin's switch", async () => {
  const { calls, client } = recorder();
  await client.setDefault("q1", true);
  expect(calls[0]).toMatchObject({ method: "PUT", path: "/questionnaires/q1/default", body: { isDefault: true } });
  expect(FORM_PAGE).toContain("{can.setDefault ? (");
  expect(FORM_PAGE).toContain('label="Default form"');
});

test("@qf:A9 bindings: several workshops, one primary each", async () => {
  expect(bindingsFor(["w1", "w2", "w1", ""], ["w2", "w9"])).toEqual([
    { workshopId: "w1", isPrimary: false },
    { workshopId: "w2", isPrimary: true }
  ]);
  const { calls, client } = recorder();
  await client.putBindings("q1", bindingsFor(["w1"], ["w1"]));
  expect(calls[0]).toMatchObject({ method: "PUT", path: "/questionnaire/forms/q1/bindings", body: { bindings: [{ workshopId: "w1", isPrimary: true }] } });
  const card = read("components", "questionnaires", "FormBindingsCard.tsx");
  expect(card).toContain('ariaLabel="Workshops this form is the primary form for"');
  expect(card).toContain("setPrimary((current) => current.filter((id) => values.includes(id)));");
});

test("a default form per workshop type, set by an admin", async () => {
  expect([...WORKSHOP_TYPES]).toEqual(shared("questionnaire-enums.json").enums.WorkshopType);
  const { calls, client } = recorder();
  await client.putTypeDefault("DESIGN_PROTOTYPE", "q1");
  await client.putTypeDefault("OTHER", null);
  expect(calls.map((c) => c.body)).toEqual([
    { workshopType: "DESIGN_PROTOTYPE", questionnaireId: "q1" },
    { workshopType: "OTHER", questionnaireId: null }
  ]);
  expect(LIBRARY_PAGE).toContain("<TypeDefaultsCard online={online} />");
});

test("@qf:A5 reuse copies a form's questions into a form of your own", async () => {
  const { calls, client } = recorder();
  await client.reuse("q1", { title: null });
  await client.reuse("q1", { title: "Market survey 2027", kind: null });
  expect(calls[0]).toMatchObject({ method: "POST", path: "/questionnaires/q1/reuse", body: { title: null } });
  expect(calls[1].body).toEqual({ title: "Market survey 2027", kind: null });
  expect(calls[0].headers.get("Idempotency-Key")).toBeTruthy();
  expect(DIALOGS).toContain('description="Copies its questions into a new form of yours."');
  expect(FORM_PAGE).toContain("router.push(`/questionnaires/${encodeURIComponent(copy.id)}`)");
});

test("@qf:A10 a handoff bundle: read in the browser, P17, and imported once", async () => {
  expect(isBundleName("Toolkit_Handoff.frwq")).toBe(true);
  expect(isBundleName("Toolkit_Handoff.DPWQ")).toBe(true);
  expect(isBundleName("toolkit.xlsx")).toBe(false);
  const v2 = {
    v: 2,
    bundleId: "ab".repeat(16),
    form: { title: "2nd Craft Toolkit", description: null, kind: "WORKSHOP_INTERVIEW" },
    sections: [{ code: "A", title: "About", sortOrder: 1 }, { code: "B", title: "Tools", sortOrder: 2 }],
    questions: [{ sectionCode: "A", prompt: "How long?", sortOrder: 1 }]
  };
  const decoded = await decodeBundleBytes(new Uint8Array(gzipSync(JSON.stringify(v2))));
  expect(decoded).toEqual(v2);
  expect(summariseBundle(decoded)).toEqual({ title: "2nd Craft Toolkit", sections: 2, questions: 1, tooNew: false });
  // designer-portal Android's v1 file.
  const v1 = { format: "dp", schemaVersion: 1, title: "Toolkit", sections: [{ code: "A", questions: [{}, {}] }] };
  expect(summariseBundle(await decodeBundleBytes(new TextEncoder().encode(JSON.stringify(v1))))).toEqual({
    title: "Toolkit",
    sections: 1,
    questions: 2,
    tooNew: false
  });
  expect(summariseBundle({ ...v2, v: 3 })?.tooNew).toBe(true);
  await expect(decodeBundleBytes(new TextEncoder().encode("not a bundle"))).rejects.toThrow("isn't a questionnaire bundle");
  // P17, as the web can say it: a bundle names no sender.
  expect(p17({ title: "2nd Craft Toolkit", sections: 24 })).toEqual({
    title: "Received a form",
    body: "'2nd Craft Toolkit' (24 sections).",
    buttons: ["Not now", "Add"]
  });
  expect(p17({ title: "2nd Craft Toolkit", sections: 24, from: "Nandini" }).body).toBe("Nandini sent '2nd Craft Toolkit' (24 sections).");
  const { calls, client } = recorder();
  await client.importBundle(v2);
  expect(calls[0]).toMatchObject({ method: "POST", path: "/questionnaires/bundles/import", body: v2 });
  expect(calls[0].headers.get("Idempotency-Key")).toBeTruthy();
  expect(DIALOGS).toContain("{prompt.buttons[0]}");
  expect(DIALOGS).toContain('{busy ? "Adding…" : prompt.buttons[1]}');
  expect(FORM_PAGE).toContain("saveDownload(await downloads.bundle(form.id))");
});

test("@qf:A10 BUNDLE_VERSION and FORM_DELETED are said in words", () => {
  const contract = shared("questionnaire-contract.json");
  expect(contract.errorCodes).toEqual(expect.arrayContaining(["FORM_DELETED", "BUNDLE_VERSION"]));
  const refusal = (code: string) => new ApiError(422, "x", { detail: { code, message: "x" } });
  expect(libraryError(refusal("BUNDLE_VERSION"))).toBe("This file was made by a newer version of the app. Update, then open it again.");
  expect(libraryError(refusal("FORM_DELETED"))).toBe("This form was deleted.");
  expect(libraryError(new ApiError(403, "Only an admin can do this.", { detail: { code: "FORBIDDEN" } }))).toBe("Only an admin can do this.");
});

test("@qf:A13 delete is P16 and restorable; Deleted lists and restores for content admins", async () => {
  expect(P16).toEqual({ title: "Delete this form?", body: "Admins can restore it from Deleted.", buttons: ["Cancel", "Delete"] });
  const { calls, client } = recorder();
  await client.remove("q1");
  await client.list("deleted");
  await client.restore("q1");
  expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([
    "DELETE /questionnaires/q1",
    "GET /questionnaires?scope=deleted",
    "POST /questionnaires/q1/restore"
  ]);
  expect(FORM_PAGE).toContain("const ok = await confirm({ title: P16.title, body: P16.body, tone: \"danger\"");
  expect(LIBRARY_PAGE).toContain('{tab === "deleted" && form.can?.restore ? (');
});

test("@qf:A14 a form's deleted sessions, restored by a content admin", () => {
  const card = read("components", "questionnaires", "FormSessionsCard.tsx");
  const lib = read("lib", "questionnaireLibrary.ts");
  expect(lib).toContain('deleted: deleted ? "true" : undefined');
  expect(lib).toContain("`/questionnaire/sessions/${encodeURIComponent(sessionId)}/restore`, { method: \"POST\" }");
  expect(card).toContain("await formSessions.restore(row.id);");
  expect(FORM_PAGE).toContain("canSeeDeleted={isContentAdmin(user)}");
  // And every deleted session, under the sessions list, for content admins.
  const sessionsPage = read("app", "(protected)", "questionnaire", "page.tsx");
  expect(sessionsPage).toContain("<FormSessionsCard questionnaireId={null} canSeeDeleted deletedOnly online={online} />");
  expect(sourceLabel("UPLOAD")).toBe("Uploaded");
  expect(sourceLabel("APP")).toBeNull();
});

test("@qf:A1 @qf:A3 the pro-forma for form authors, a form's workbook and question set", () => {
  expect(LIBRARY_PAGE).toContain("downloadProForma()");
  expect(FORM_PAGE).toContain("downloadQuestionnaireWorkbook(form.id)");
  expect(FORM_PAGE).toContain("downloadQuestionSet(form.id)");
  expect(WORKBOOK_API).toContain('fetchWorkbook("/questionnaires/pro-forma"');
});

test("@qf:A2 a hand-filled workbook: kind, workshop, and its answers as uploaded sessions", () => {
  expect(WORKBOOK_API).toContain('if (options?.kind) body.append("kind", options.kind);');
  expect(WORKBOOK_API).toContain('if (options?.workshopId) body.append("workshopId", options.workshopId);');
  expect(UPLOAD).toContain("Each answer column becomes an uploaded session.");
  expect(UPLOAD).toContain('ariaLabel="Workshop for the answers"');
  const report = read("components", "questionnaires", "UploadReport.tsx");
  expect(report).toContain('{ label: "answers imported", value: report.answersImported ?? 0 }');
  expect(report).toContain('provenance.action === "answersImported"');
});

test("@qf:A4 re-upload over a form is its editors'", () => {
  expect(FORM_PAGE).toContain("questionnaireId={form.id}");
  expect(UPLOAD).toContain("Answered questions are never overwritten");
});

test("@qf:A12 the workshop annexure, grouped by kind", async () => {
  const { calls, client } = recorder();
  await client.annexure("w1");
  expect(calls[0].path).toBe("/questionnaire/annexure?workshopId=w1");
  expect(LIBRARY_PAGE).toContain("saveDownload(await downloads.annexure(workshopId))");
  expect(read("lib", "questionnaireLibrary.ts")).toContain('format: "txt"');
});

test("@qf:B29 the corpus, for dataset downloaders", () => {
  const lib = read("lib", "questionnaireLibrary.ts");
  expect(lib).toContain('`/questionnaire/corpus.csv${buildQuery({ questionnaireId, kind: "answers" })}`');
  expect(lib).toContain('`/questionnaire/corpus.csv${buildQuery({ questionnaireId, kind: "recordings" })}`');
  expect(FORM_PAGE).toContain("{v2 && canDownloadDataset(user) ? (");
});

test("every write in the library needs a connection and says so", () => {
  expect(read("components", "questionnaires", "useOnline.ts")).toContain('export const NEEDS_CONNECTION = "Needs a connection";');
  for (const page of [LIBRARY_PAGE, FORM_PAGE]) expect(page).toContain("title={offlineTitle}");
  expect(formSummary({ sectionCount: 1, questionCount: 2 })).toBe("1 section · 2 questions");
});

test("@qf:A16 a form reads offline from this account's saved copy, never another account's", () => {
  const data = new Map<string, string>();
  const store = { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v), removeItem: (k: string) => void data.delete(k) };
  const form = { id: "f1", title: "2nd Craft Toolkit", isActive: true, isDefault: false, sections: [] };
  const key = listCacheKey("mine", { kind: null });
  writeFormsCache("u1", { list: { key, forms: [form] }, form }, store, new Date("2026-10-22T04:42:00Z"));
  expect(readFormsCache("u1", store)?.lists[key]?.map((f) => f.id)).toEqual(["f1"]);
  expect(readFormsCache("u1", store)?.forms.f1?.title).toBe("2nd Craft Toolkit");
  // Another account on this browser: the copy is dropped, not shown.
  expect(readFormsCache("u2", store)).toBeNull();
  expect(data.size).toBe(0);
  expect(isOffline(new TypeError("Failed to fetch"))).toBe(true);
  expect(isOffline(new ApiError(403, "No", null))).toBe(false);
  expect(savedCopyLine("2026-10-22T04:42:00Z")).toBe("Offline · saved copy from 22 Oct, 10:12 am.");
  expect(LIBRARY_PAGE).toContain("const held = isOffline(err) ? readFormsCache(user.id) : null;");
  expect(FORM_PAGE).toContain("const held = isOffline(err) ? readFormsCache(owner) : null;");
});
