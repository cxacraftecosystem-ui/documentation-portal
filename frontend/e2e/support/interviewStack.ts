import { createHash, randomBytes } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { expect, type Browser, type BrowserContext, type Page } from "@playwright/test";

import { ApiError, describeApiDetail } from "@/lib/api";
import { mintKey, questionnaireClient, type JsonFetcher, type Session, type SessionCreate } from "@/lib/questionnaire/api";
import { enqueueStep, existingSessionUnit, fetchTransport, memoryQueueStore, runUnit, type ClipStep } from "@/lib/questionnaire/queue";
import { questionnaireStrings } from "@/lib/questionnaire/strings";
import { INSECURE_SESSION_COOKIE, INSECURE_SESSION_MARKER_COOKIE, SESSION_COOKIE, SESSION_MARKER_COOKIE } from "@/lib/sessionCookie";

export const S = questionnaireStrings;

export type Who = "lead" | "other" | "admin";

export type InterviewSeed = {
  portal: "designer-portal" | "documentation-portal";
  questionnaireId: string;
  workshopField: "designWorkshopId" | "workshopId";
  workshopId: string;
  place: string;
  sections: Record<string, string>;
  sectionTitles: Record<string, string>;
  questions: Record<string, string>;
  artisans: Record<string, string>;
  users: Record<Who, { id: string; name: string; email: string; role: string }>;
  tokens: Record<Who, string>;
};

export type RecordBy = "SECTION" | "QUESTION";

const SEED_PATH = process.env.INTERVIEW_SEED ?? "";
export const INTERVIEW_API = (process.env.INTERVIEW_API ?? process.env.E2E_API_URL ?? "http://127.0.0.1:8000").replace(/\/$/, "");
export const INTERVIEW_SEED_MISSING = SEED_PATH ? "" : "Set INTERVIEW_SEED to the file scripts/ci/seed_interview_stack.py wrote (the interview CI jobs do).";

export const MEERA = "Meera Devi";
export const GROUP = ["Ramesh Maharana", "Sushila Sahoo", "Bhagirathi Nayak"] as const;

let cached: InterviewSeed | null = null;

export function seed(): InterviewSeed {
  cached ??= JSON.parse(readFileSync(SEED_PATH, "utf8")) as InterviewSeed;
  return cached;
}

function stackFetch(token: string): JsonFetcher {
  return async <T>(path: string, init: RequestInit = {}): Promise<T> => {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${token}`);
    if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    headers.set("X-Client-Time", new Date().toISOString());
    headers.set("X-App-Version", "web-ci");
    headers.set("X-App-Platform", "web");
    const response = await fetch(`${INTERVIEW_API}/api${path}`, { ...init, headers, redirect: "manual" });
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

export const as = (who: Who = "lead") => questionnaireClient(stackFetch(seed().tokens[who]));

export async function raw<T>(who: Who, method: string, path: string, body?: unknown): Promise<T> {
  return stackFetch(seed().tokens[who])<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body) });
}

export const today = () => new Date().toISOString().slice(0, 10);
export const artisan = (name: string) => seed().artisans[name];
export const section = (code: string) => seed().sections[code];
export const lineage = (code: string) => seed().questions[code];

export async function signInAs(context: BrowserContext, who: Who = "lead") {
  const base = new URL(process.env.E2E_BASE_URL ?? "http://localhost:3000");
  const local = base.hostname === "localhost" || base.hostname === "127.0.0.1";
  const secure = base.protocol === "https:" || local;
  const url = secure ? `https://${base.host}` : base.origin;
  const common = { url, secure, sameSite: "Lax" as const };
  await context.addCookies([
    { name: secure ? SESSION_COOKIE : INSECURE_SESSION_COOKIE, value: seed().tokens[who], httpOnly: true, ...common },
    { name: secure ? SESSION_MARKER_COOKIE : INSECURE_SESSION_MARKER_COOKIE, value: `e2e-${Date.now().toString(36)}`, httpOnly: false, ...common }
  ]);
}

export type SessionOptions = {
  artisans?: string[];
  guests?: string[];
  recordBy?: RecordBy;
  who?: Who;
  date?: string;
  note?: string | null;
};

export async function createSession(options: SessionOptions = {}): Promise<Session> {
  const s = seed();
  const date = options.date ?? today();
  const body: SessionCreate & { recordBy: RecordBy } = {
    clientKey: mintKey("interview"),
    questionnaireId: s.questionnaireId,
    workshopId: s.workshopField === "workshopId" ? s.workshopId : null,
    designWorkshopId: s.workshopField === "designWorkshopId" ? s.workshopId : null,
    sessionDate: date,
    startedAt: new Date().toISOString(),
    purpose: "FIRST_SITTING",
    place: s.place,
    languageCode: "or",
    languageOther: null,
    note: options.note ?? null,
    participants: (options.artisans ?? [MEERA]).map((name) => ({ artisanId: artisan(name) })),
    respondents: (options.guests ?? []).map((name) => ({ name, kind: "GUEST" as const })),
    recordBy: options.recordBy ?? "SECTION"
  };
  return as(options.who ?? "lead").createSession(body);
}

export function audioBytes(seconds = 1, salt = randomBytes(8)): Uint8Array {
  const rate = 8000;
  const samples = rate * seconds;
  const data = Buffer.alloc(44 + samples * 2);
  data.write("RIFF", 0);
  data.writeUInt32LE(36 + samples * 2, 4);
  data.write("WAVE", 8);
  data.write("fmt ", 12);
  data.writeUInt32LE(16, 16);
  data.writeUInt16LE(1, 20);
  data.writeUInt16LE(1, 22);
  data.writeUInt32LE(rate, 24);
  data.writeUInt32LE(rate * 2, 28);
  data.writeUInt16LE(2, 32);
  data.writeUInt16LE(16, 34);
  data.write("data", 36);
  data.writeUInt32LE(samples * 2, 40);
  for (let i = 0; i < samples; i += 1) data.writeInt16LE(Math.round(Math.sin((i + salt[i % salt.length]) / 9) * 900), 44 + i * 2);
  return new Uint8Array(data);
}

export function audioFile(dir: string, name: string, seconds = 1): { path: string; sha256: string } {
  mkdirSync(dir, { recursive: true });
  const bytes = audioBytes(seconds);
  const path = join(dir, name);
  writeFileSync(path, bytes);
  return { path, sha256: createHash("sha256").update(bytes).digest("hex") };
}

export type Target = { sectionId: string } | { questionLineageId: string };

export async function uploadTake(session: Session, target: Target | null, options: { who?: Who; artisans?: string[]; durationMs?: number; bytes?: Uint8Array } = {}) {
  const api = as(options.who ?? "lead");
  const store = memoryQueueStore();
  const bytes = options.bytes ?? audioBytes();
  const hash = createHash("sha256").update(bytes).digest("hex");
  const at = new Date().toISOString();
  const names = options.artisans ?? [MEERA];
  const targets = target ? [target] : [];
  const step: ClipStep = {
    kind: "clip",
    status: "PENDING",
    clientClipKey: mintKey("clip"),
    blobKey: "blob-1",
    label: "take",
    sizeBytes: bytes.byteLength,
    contentType: "audio/wav",
    extension: "wav",
    presign: { contentSha256: hash, targets, subjectArtisanIds: names.map(artisan), deviceCapturedAt: at, clockOffsetMs: 0 },
    recording: {
      targets,
      subjects: names.map((name) => ({ artisanId: artisan(name), role: "ANSWERED" as const, named: true, repeatReason: null })),
      deviceCapturedAt: at,
      clockOffsetMs: 0,
      capturedAtSource: "DEVICE",
      durationMs: options.durationMs ?? 60_000,
      rosterCount: names.length,
      contentSha256: hash,
      continuationOfId: null,
      replacesRecordingId: null,
      ackedRecordingIds: []
    }
  };
  await store.putBlob(step.blobKey, new Blob([new Uint8Array(bytes)]));
  await enqueueStep(store, existingSessionUnit(session), step);
  const config = await api.config();
  const out = await runUnit((await store.load())[0], api, store, fetchTransport, {
    multipartThresholdBytes: config.multipartThresholdBytes,
    partSizeBytes: config.partSizeBytes
  });
  expect((out.steps[0] as ClipStep).status, "the take reached the stack").toBe("DONE");
  return { sha256: hash, clientClipKey: step.clientClipKey };
}

export async function typeAnswers(session: Session, answers: Record<string, string>, options: { who?: Who; artisan?: string } = {}) {
  const owner = artisan(options.artisan ?? MEERA);
  const out = await as(options.who ?? "lead").putAnswers(
    Object.entries(answers).map(([code, text]) => ({
      artisanId: owner,
      questionLineageId: lineage(code),
      text,
      origin: "TYPED" as const,
      attribution: "INDIVIDUAL" as const,
      sessionId: session.id,
      sourceRecordingId: null
    }))
  );
  expect(out.results.every((r) => r.status === "SAVED" || r.status === "UNCHANGED"), "every typed answer saved").toBe(true);
}

export const E1_TYPED = "Rough cutting takes about four days, then shaping with the chisel";

export async function boardSession(options: { recordBy?: RecordBy } = {}): Promise<Session> {
  const session = await createSession({ artisans: [MEERA], recordBy: options.recordBy ?? "SECTION" });
  await uploadTake(session, { sectionId: section("A") });
  await uploadTake(session, { sectionId: section("B") });
  await typeAnswers(session, {
    C1: "Moved from Puri to Bhubaneswar for work",
    D1: "Sandstone from Lalitgiri",
    D2: "Bought through a trader",
    D3: "Prices rose this year",
    D4: "Khondalite for temple work",
    D5: "Stored in the yard",
    D6: "Quarry permits are slow",
    E1: E1_TYPED
  });
  await uploadTake(session, { questionLineageId: lineage("E2") }, { durationMs: 192_000 });
  return session;
}

export const sessionPath = (session: Pick<Session, "id">) => `/questionnaire/sessions/${encodeURIComponent(session.id)}`;

export async function openSession(page: Page, session: Pick<Session, "id">) {
  await page.goto(sessionPath(session));
  await expect(page.getByRole("button", { name: S.sessionOptions })).toBeVisible({ timeout: 30_000 });
}

export async function openSection(page: Page, code: string) {
  const title = seed().sectionTitles[code];
  await page.getByRole("button", { name: new RegExp(`^${code},? ${escape(title)}`) }).first().click();
}

export function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export type Theme = "light" | "dark";

export async function stackContext(browser: Browser, width: number, theme: Theme, height = 900): Promise<{ context: BrowserContext; page: Page }> {
  const mobile = width < 900;
  const context = await browser.newContext({
    viewport: { width, height },
    deviceScaleFactor: width <= 600 ? 2 : 1,
    isMobile: mobile,
    hasTouch: mobile,
    colorScheme: theme,
    reducedMotion: "reduce",
    serviceWorkers: "block",
    permissions: ["microphone"]
  });
  await signInAs(context);
  return { context, page: await context.newPage() };
}
