import { expect, test, type APIRequestContext } from "@playwright/test";

/**
 * The web client's v2 calls against a REAL backend running `feature/q-backend` with
 * QUESTIONNAIRE_V2 on (BUILD_PLAN §3.3, the one end-to-end spec).
 *
 * IT WRITES: it creates a session and returns it for changes. So it runs only against a local stack
 * on a throwaway database: E2E_QV2_BASE_URL must be a localhost URL, and E2E_QV2_QUESTIONNAIRE_ID and
 * E2E_QV2_ARTISAN_ID name a seeded instrument and artisan there. It refuses any other host.
 */

const BASE = process.env.E2E_QV2_BASE_URL ?? "";
const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const QUESTIONNAIRE = process.env.E2E_QV2_QUESTIONNAIRE_ID ?? "";
const ARTISAN = process.env.E2E_QV2_ARTISAN_ID ?? "";
const LOCAL = /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/?$/.test(BASE);

test.skip(!BASE || !EMAIL || !PASSWORD || !QUESTIONNAIRE || !ARTISAN, "Set E2E_QV2_BASE_URL, E2E_EMAIL, E2E_PASSWORD, E2E_QV2_QUESTIONNAIRE_ID and E2E_QV2_ARTISAN_ID.");
test.skip(Boolean(BASE) && !LOCAL, "This spec writes; it runs only against a local stack.");
test.use({ baseURL: BASE });

async function signIn(request: APIRequestContext) {
  const res = await request.post("/api/auth/login", { data: { email: EMAIL, password: PASSWORD } });
  expect(res.ok(), await res.text()).toBe(true);
}

test("session create, read, review and inbox speak the backend's shapes", async ({ request }) => {
  await signIn(request);

  const config = await (await request.get("/api/questionnaire/config")).json();
  expect(config.v2Enabled).toBe(true);
  expect(config.transcriptionPaused).toBe(true);

  const clientKey = `e2e-${Date.now()}`;
  const body = {
    clientKey,
    questionnaireId: QUESTIONNAIRE,
    workshopId: null,
    designWorkshopId: null,
    sessionDate: new Date().toISOString().slice(0, 10),
    startedAt: new Date().toISOString(),
    purpose: "FIRST_SITTING",
    place: null,
    languageCode: null,
    languageOther: null,
    note: "e2e",
    participants: [{ artisanId: ARTISAN }]
  };
  const created = await request.post("/api/questionnaire/sessions", { data: body, headers: { "Idempotency-Key": clientKey } });
  expect(created.status(), await created.text()).toBeLessThan(300);
  const session = await created.json();
  expect(session.title).toBeTruthy();
  expect(session.title).not.toContain("·");

  // A client title is refused: titles are generated.
  const titled = await request.post("/api/questionnaire/sessions", { data: { ...body, clientKey: `${clientKey}-t`, title: "Mine" } });
  expect((await titled.json()).detail.code).toBe("TITLE_IS_GENERATED");

  // A replayed clientKey returns the same row.
  const again = await request.post("/api/questionnaire/sessions", { data: body, headers: { "Idempotency-Key": clientKey } });
  expect((await again.json()).id).toBe(session.id);

  const detail = await (await request.get(`/api/questionnaire/sessions/${session.id}`)).json();
  expect(Array.isArray(detail.recordings)).toBe(true);
  expect(detail.grid.questionnaireId).toBe(QUESTIONNAIRE);

  // Review uses the `questionnaire` record type; an empty session cannot be approved.
  const approve = await request.post(`/api/review/questionnaire/${session.id}/approve`, { data: { notes: null } });
  expect(approve.status()).toBe(409);
  const returned = await request.post(`/api/review/questionnaire/${session.id}/revise`, { data: { notes: "Add the place." } });
  expect(returned.status(), await returned.text()).toBe(200);

  const inbox = await (await request.get("/api/questionnaire/inbox")).json();
  for (const key of ["toFile", "needsDecision", "legacy", "setDates", "noParticipants", "emptySessions", "primaryChanged"]) {
    expect(Array.isArray(inbox[key]), key).toBe(true);
  }
  expect(typeof inbox.counts.olderThan24h).toBe("number");

  // Clean up: an empty session deletes freely.
  expect((await request.delete(`/api/questionnaire/sessions/${session.id}`)).status()).toBe(204);
});
