import { readFileSync } from "node:fs";

import { expect, test } from "@playwright/test";

import { ApiError, describeApiDetail } from "@/lib/api";
import { mintKey, questionnaireClient, type JsonFetcher } from "@/lib/questionnaireApi";
import { bindingsFor, decodeBundleBytes, libraryClient, summariseBundle, type LibraryForm } from "@/lib/questionnaireLibrary";

/**
 * THE FORMS LIBRARY AGAINST A REAL BACKEND (UNIFY U3). The web's own library client
 * (`libraryClient`, the object `library` is) over a local FastAPI with QUESTIONNAIRE_V2 on and a
 * migrated Postgres, as three seeded people: a professor (a form author), a researcher and a content
 * admin. Scopes, sharing, kinds, reuse, the handoff bundle, bindings and the form resolution, type
 * defaults, the soft delete and restore, a form's workbook round trip, and the workshop annexure.
 *
 * CI only: the `web-v2-stack` job seeds the database (`scripts/ci/seed_qv2_stack.py`) and sets
 * QV2_STACK_API and QV2_STACK_SEED. It refuses any host but loopback, because it writes.
 */

const API = process.env.QV2_STACK_API ?? "";
const SEED_PATH = process.env.QV2_STACK_SEED ?? "";
const LOCAL = /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/?$/.test(API);

test.skip(!API || !SEED_PATH, "Set QV2_STACK_API and QV2_STACK_SEED (the web-v2-stack CI job does).");
test.skip(Boolean(API) && !LOCAL, "This spec writes; it runs only against a local stack.");
test.describe.configure({ mode: "serial" });

type Seed = {
  questionnaireId: string;
  workshopId: string;
  questions: Record<string, string>;
  artisans: string[];
  tokens: { lead: string; other: string; admin: string; prof: string };
};
const seed = (): Seed => JSON.parse(readFileSync(SEED_PATH, "utf8")) as Seed;

function stackFetch(token: string): JsonFetcher {
  return async <T>(path: string, init: RequestInit = {}): Promise<T> => {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${token}`);
    if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    headers.set("X-Client-Time", new Date().toISOString());
    headers.set("X-App-Version", "web-ci");
    headers.set("X-App-Platform", "web");
    const response = await fetch(`${API.replace(/\/$/, "")}/api${path}`, { ...init, headers, redirect: "manual" });
    if (response.status === 204) return undefined as T;
    const type = response.headers.get("content-type") ?? "";
    const body = type.includes("application/json") ? await response.json() : await response.text();
    if (!response.ok) {
      const detail = typeof body === "object" && body && "detail" in body ? (body as { detail: unknown }).detail : undefined;
      throw new ApiError(response.status, describeApiDetail(detail, `HTTP ${response.status}`), body, response.headers);
    }
    return body as T;
  };
}

async function download(token: string, path: string): Promise<{ bytes: Uint8Array; name: string }> {
  const response = await fetch(`${API.replace(/\/$/, "")}/api${path}`, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.status, path).toBe(200);
  const disposition = response.headers.get("content-disposition") ?? "";
  return { bytes: new Uint8Array(await response.arrayBuffer()), name: disposition };
}

async function refused(work: Promise<unknown>): Promise<ApiError> {
  try {
    await work;
  } catch (err) {
    if (err instanceof ApiError) return err;
    throw err;
  }
  throw new Error("expected a refusal");
}

const code = (err: ApiError) => (err.payload as { detail?: { code?: string } })?.detail?.code;
const ids = (forms: LibraryForm[]) => forms.map((f) => f.id);
let made: LibraryForm;
let copy: LibraryForm;

test("@qf:D3 @qf:A6 a professor creates a form of their own; Mine is theirs alone", async () => {
  const s = seed();
  const prof = libraryClient(stackFetch(s.tokens.prof));
  const lead = libraryClient(stackFetch(s.tokens.lead));
  made = await prof.create({ title: `Stack survey ${mintKey("t")}`, kind: "MARKET_SURVEY" });
  expect(made.ownerId).toBeTruthy();
  expect(made.kind).toBe("MARKET_SURVEY");
  expect(made.can?.edit).toBe(true);
  expect(ids(await prof.list("mine"))).toContain(made.id);
  expect(ids(await lead.list("mine"))).not.toContain(made.id);
  // A researcher cannot create one.
  expect((await refused(lead.create({ title: "Not mine" }))).status).toBe(403);
});

test("@qf:A7 sharing is an admin's; a shared form is in everyone's Shared tab", async () => {
  const s = seed();
  const prof = libraryClient(stackFetch(s.tokens.prof));
  const admin = libraryClient(stackFetch(s.tokens.admin));
  const lead = libraryClient(stackFetch(s.tokens.lead));
  expect((await refused(prof.patch(made.id, { isShared: true }))).status).toBe(403);
  const shared = await admin.patch(made.id, { isShared: true });
  expect(shared.isShared).toBe(true);
  expect(ids(await lead.list("shared"))).toContain(made.id);
});

test("@qf:A8 the kind is the author's, and filters the list", async () => {
  const s = seed();
  const prof = libraryClient(stackFetch(s.tokens.prof));
  const lead = libraryClient(stackFetch(s.tokens.lead));
  expect((await prof.patch(made.id, { kind: "WORKSHOP_INTERVIEW" })).kindLabel).toBe("Workshop interview");
  expect(ids(await lead.list("shared", { kind: "WORKSHOP_INTERVIEW" }))).toContain(made.id);
  expect(ids(await lead.list("shared", { kind: "MARKET_SURVEY" }))).not.toContain(made.id);
  expect((await refused(lead.patch(made.id, { kind: null }))).status).toBe(403);
});

test("@qf:A5 reuse copies the seeded form's questions into a form of the professor's own", async () => {
  const s = seed();
  const prof = libraryClient(stackFetch(s.tokens.prof));
  copy = await prof.reuse(s.questionnaireId, { title: null });
  expect(copy.id).not.toBe(s.questionnaireId);
  expect(copy.questionCount).toBe(Object.keys(s.questions).length);
  expect(copy.isShared).toBe(false);
  expect(ids(await prof.list("mine"))).toContain(copy.id);
});

test("@qf:A10 a handoff bundle downloads, reads in the browser and imports once per person", async () => {
  const s = seed();
  const prof = libraryClient(stackFetch(s.tokens.prof));
  const file = await download(s.tokens.prof, `/questionnaires/${copy.id}/bundle`);
  expect(file.name).toContain("_Handoff.frwq");
  const document = await decodeBundleBytes(file.bytes);
  const summary = summariseBundle(document);
  expect(summary?.questions).toBe(Object.keys(s.questions).length);
  expect(summary?.tooNew).toBe(false);
  const first = await prof.importBundle(document);
  const again = await prof.importBundle(document);
  expect(first.created).toBe(true);
  expect(again.created).toBe(false);
  expect(again.questionnaire.id).toBe(first.questionnaire.id);
  const newer = await refused(prof.importBundle({ ...(document as object), v: 3 }));
  expect(code(newer)).toBe("BUNDLE_VERSION");
});

test("@qf:A9 a primary binding makes a form the workshop's start-sheet default", async () => {
  const s = seed();
  const admin = libraryClient(stackFetch(s.tokens.admin));
  const prof = libraryClient(stackFetch(s.tokens.prof));
  expect((await refused(prof.putBindings(made.id, bindingsFor([s.workshopId], [s.workshopId])))).status).toBe(403);
  const saved = await admin.putBindings(made.id, bindingsFor([s.workshopId], [s.workshopId]));
  expect(saved.bindings).toEqual([expect.objectContaining({ workshopId: s.workshopId, isPrimary: true })]);
  expect(ids(await prof.list("workshop", { workshopId: s.workshopId }))).toContain(made.id);
  const resolution = await stackFetch(s.tokens.lead)<{ questionnaireId: string; reason: string }>(
    `/questionnaire/form-for?workshopId=${encodeURIComponent(s.workshopId)}`
  );
  expect(resolution).toMatchObject({ questionnaireId: made.id, reason: "PRIMARY_BINDING" });
  // Unbound again, so the other stack specs' sessions on the seeded form are not refused.
  expect((await admin.putBindings(made.id, [])).bindings).toEqual([]);
});

test("an admin sets a workshop type's default form", async () => {
  const s = seed();
  const admin = libraryClient(stackFetch(s.tokens.admin));
  await admin.putTypeDefault("OTHER", copy.id);
  const current = await admin.typeDefaults();
  expect(current.defaults).toEqual(expect.arrayContaining([expect.objectContaining({ workshopType: "OTHER", questionnaireId: copy.id })]));
  await admin.putTypeDefault("OTHER", null);
  expect((await admin.typeDefaults()).defaults.find((d) => d.workshopType === "OTHER")).toBeUndefined();
});

test("@qf:A13 the author deletes a form; an admin's Deleted tab restores it", async () => {
  const s = seed();
  const prof = libraryClient(stackFetch(s.tokens.prof));
  const admin = libraryClient(stackFetch(s.tokens.admin));
  const lead = libraryClient(stackFetch(s.tokens.lead));
  expect((await refused(lead.remove(copy.id))).status).toBe(403);
  const gone = await prof.remove(copy.id);
  expect(gone.deletedAt).toBeTruthy();
  expect(ids(await prof.list("mine"))).not.toContain(copy.id);
  expect((await refused(prof.list("deleted"))).status).toBe(403);
  expect(ids(await admin.list("deleted"))).toContain(copy.id);
  const deleted = await refused(lead.get(copy.id));
  expect(deleted.status).toBe(410);
  expect(code(deleted)).toBe("FORM_DELETED");
  expect((await refused(prof.restore(copy.id))).status).toBe(403);
  expect((await admin.restore(copy.id)).deletedAt).toBeNull();
  expect(ids(await prof.list("mine"))).toContain(copy.id);
});

test("@qf:A1 @qf:A3 @qf:A4 the pro-forma, a form's workbook and question set, and a re-upload of it", async () => {
  const s = seed();
  const proForma = await download(s.tokens.prof, "/questionnaires/pro-forma");
  expect(String.fromCharCode(proForma.bytes[0], proForma.bytes[1])).toBe("PK");
  const questions = await download(s.tokens.prof, `/questionnaires/${copy.id}/question-set.xlsx`);
  expect(questions.name).toContain("_Questions.xlsx");
  const workbook = await download(s.tokens.prof, `/questionnaires/${copy.id}/xlsx`);
  const body = new FormData();
  body.append("file", new Blob([workbook.bytes as BlobPart], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }), "copy.xlsx");
  const result = await stackFetch(s.tokens.prof)<{ report: { unchanged: number; created: number } }>(`/questionnaires/${copy.id}/upload`, {
    method: "POST",
    body
  });
  expect(result.report.created).toBe(0);
  expect(result.report.unchanged).toBeGreaterThan(0);
  // A researcher who is not its author is refused the form's workbook.
  const refusedDownload = await fetch(`${API.replace(/\/$/, "")}/api/questionnaires/${copy.id}/xlsx`, {
    headers: { Authorization: `Bearer ${s.tokens.lead}` }
  });
  expect(refusedDownload.status).toBe(403);
});

test("@qf:A12 the workshop annexure groups forms by kind and prints live answers", async () => {
  const s = seed();
  const lead = questionnaireClient(stackFetch(s.tokens.lead));
  const session = await lead.createSession({
    clientKey: mintKey("annex"),
    questionnaireId: s.questionnaireId,
    workshopId: s.workshopId,
    designWorkshopId: null,
    sessionDate: new Date().toISOString().slice(0, 10),
    startedAt: new Date().toISOString(),
    purpose: "FIRST_SITTING",
    place: null,
    languageCode: null,
    languageOther: null,
    note: null,
    participants: [{ artisanId: s.artisans[0] }]
  });
  const lineage = Object.values(s.questions)[1];
  const saved = await lead.putAnswers([
    { artisanId: s.artisans[0], questionLineageId: lineage, text: "Indigo from the annexure test.", origin: "TYPED", attribution: "INDIVIDUAL", sessionId: session.id, sourceRecordingId: null }
  ]);
  expect(["SAVED", "CONFLICT"]).toContain(saved.results[0].status);
  // A content admin may set the kind of any form; the annexure then files it under that kind.
  await libraryClient(stackFetch(s.tokens.admin)).patch(s.questionnaireId, { kind: "WORKSHOP_INTERVIEW" });
  const doc = await libraryClient(stackFetch(s.tokens.prof)).annexure(s.workshopId);
  const interviews = doc.groups.find((g) => g.kind === "WORKSHOP_INTERVIEW");
  expect(interviews?.label).toBe("Workshop interview");
  const answered = interviews?.forms.find((f) => f.id === s.questionnaireId);
  expect(answered?.sessionCount ?? 0).toBeGreaterThan(0);
  const printed = answered?.sections.flatMap((section) => section.questions.flatMap((q) => q.answers.map((a) => a.text))) ?? [];
  if (saved.results[0].status === "SAVED") expect(printed).toContain("Indigo from the annexure test.");
  const text = await download(s.tokens.prof, `/questionnaire/annexure?workshopId=${encodeURIComponent(s.workshopId)}&format=txt`);
  expect(text.name).toContain("_Annexure.txt");
  // A researcher without questionnaire or dataset access is refused the annexure.
  expect((await refused(libraryClient(stackFetch(s.tokens.lead)).annexure(s.workshopId))).status).toBe(403);
});

test("@qf:B29 the corpus is a dataset downloader's", async () => {
  const s = seed();
  const answers = await fetch(`${API.replace(/\/$/, "")}/api/questionnaire/corpus.csv?questionnaireId=${s.questionnaireId}&kind=answers`, {
    headers: { Authorization: `Bearer ${s.tokens.admin}` }
  });
  expect(answers.status).toBe(200);
  expect(answers.headers.get("content-type") ?? "").toContain("text/csv");
  const researcher = await fetch(`${API.replace(/\/$/, "")}/api/questionnaire/corpus.csv?questionnaireId=${s.questionnaireId}&kind=answers`, {
    headers: { Authorization: `Bearer ${s.tokens.lead}` }
  });
  expect(researcher.status).toBe(403);
});
