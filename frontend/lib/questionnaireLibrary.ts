/**
 * The forms library (UNIFY U3, section 4.2 `/questionnaires`): the calls, the vocabulary and the
 * pure rules the library screens draw from.
 *
 * Field Repository's own module. The session screens' shared tree (`components/questionnaire/`, and
 * `lib/questionnaire/` once designer-portal's U1 lands it) is synced from designer-portal and is not
 * edited here; this file holds only what the library pages need. The rules mirror the server's
 * (`backend/app/questionnaire/authoring.py` and `backend/app/services/questionnaire_library.py`):
 *
 * - a form is changed and deleted by its AUTHOR or a CONTENT ADMIN;
 * - sharing with everyone, the default form, type defaults and bindings are ADMIN acts;
 * - a deleted form is restored by a content admin, from the Deleted tab;
 * - creating, reusing and opening a bundle are a questionnaire manager's (Professor and above).
 *
 * The server sends `can` on every form it lists, so the screens draw exactly what it will accept.
 */

import { API_BASE, ApiError, apiFetch, assertApiConfigured, buildQuery, describeApiDetail } from "@/lib/api";
import { canManageQuestionnaire, isContentAdmin } from "@/lib/permissions";
import { saveBlobToDevice } from "@/lib/saveToDevice";
import type { JsonFetcher } from "@/lib/questionnaireApi";
import type { User } from "@/lib/types";

export type FormScope = "mine" | "shared" | "workshop" | "deleted";
export type FormKind = "WORKSHOP_INTERVIEW" | "MARKET_SURVEY";

/** `shared/questionnaire-enums.json` labels; `e2e/questionnaire-library-unit.spec.ts` holds them equal. */
export const KIND_LABELS: Record<FormKind, string> = {
  WORKSHOP_INTERVIEW: "Workshop interview",
  MARKET_SURVEY: "Market survey"
};
export const KIND_NOT_STATED = "Kind not stated";
export const KINDS: FormKind[] = ["WORKSHOP_INTERVIEW", "MARKET_SURVEY"];

/** `Workshop.workshopType`, the keys of a type default (owner, amendment 2 point 2). */
export const WORKSHOP_TYPES = ["DESIGN_PROTOTYPE", "OTHER"] as const;
export type WorkshopType = (typeof WORKSHOP_TYPES)[number];
export const WORKSHOP_TYPE_LABELS: Record<WorkshopType, string> = {
  DESIGN_PROTOTYPE: "Design and prototype workshops",
  OTHER: "Other workshops"
};

export type FormCan = {
  edit: boolean;
  delete: boolean;
  restore: boolean;
  share: boolean;
  bind: boolean;
  setDefault: boolean;
};

export type LibraryForm = {
  id: string;
  title: string;
  description?: string | null;
  isActive: boolean;
  isDefault: boolean;
  sortOrder?: number;
  version?: number | null;
  sourceFilename?: string | null;
  sectionCount?: number;
  questionCount?: number;
  workshopCount?: number;
  createdAt?: string | null;
  createdById?: string | null;
  ownerId?: string | null;
  isShared?: boolean;
  kind?: FormKind | null;
  kindLabel?: string;
  deletedAt?: string | null;
  deletedById?: string | null;
  can?: FormCan;
};

export type LibraryQuestion = {
  id: string;
  prompt: string;
  displayCode?: string | null;
  helpText?: string | null;
  isRequired?: boolean;
  isActive?: boolean;
};
export type LibrarySection = {
  id: string;
  code: string;
  title: string;
  isActive?: boolean;
  questions: LibraryQuestion[];
};
export type LibraryFormDetail = LibraryForm & { sections: LibrarySection[] };

export type FormBinding = { workshopId?: string | null; designWorkshopId?: string | null; isPrimary: boolean };
export type TypeDefault = { questionnaireId: string; title: string; workshopType: WorkshopType | null; designWorkshopKind?: string | null };

export type AnnexureAnswer = { subject: string; text: string };
export type AnnexureDoc = {
  workshopId: string;
  workshopTitle?: string | null;
  groups: {
    kind: FormKind | null;
    label: string;
    forms: {
      id: string;
      title: string;
      sessionCount: number;
      sections: { code: string; title: string; questions: { displayCode: string; prompt: string; answers: AnnexureAnswer[] }[] }[];
    }[];
  }[];
};

/* ────────────────────────────────────────────────────────────────────────────
 * The tabs and the copy
 * ──────────────────────────────────────────────────────────────────────────── */

export type LibraryTab = { id: FormScope; label: string };

/** Mine · Shared · Workshop, and Deleted for content admins (UNIFY 4.2). */
export function libraryTabs(user: User | null | undefined): LibraryTab[] {
  const tabs: LibraryTab[] = [
    { id: "mine", label: "Mine" },
    { id: "shared", label: "Shared" },
    { id: "workshop", label: "Workshop" }
  ];
  if (isContentAdmin(user)) tabs.push({ id: "deleted", label: "Deleted" });
  return tabs;
}

/** The first tab to open: your own forms when you can write one, else the shared ones. */
export function defaultTab(user: User | null | undefined): FormScope {
  return canManageQuestionnaire(user) ? "mine" : "shared";
}

export function kindLabel(kind: string | null | undefined): string {
  if (!kind) return KIND_NOT_STATED;
  return (KIND_LABELS as Record<string, string>)[kind] ?? kind;
}

export function kindOptions(): { value: string; label: string }[] {
  return [{ value: "", label: KIND_NOT_STATED }, ...KINDS.map((kind) => ({ value: kind, label: KIND_LABELS[kind] }))];
}

/** Forms grouped by kind, kinds in the vocabulary's order and "not stated" last. */
export function groupByKind<T extends { kind?: string | null }>(forms: T[]): { kind: string | null; label: string; forms: T[] }[] {
  const order = [...KINDS, null] as (string | null)[];
  const groups = new Map<string | null, T[]>();
  for (const form of forms) {
    const kind = form.kind && (KINDS as string[]).includes(form.kind) ? form.kind : null;
    groups.set(kind, [...(groups.get(kind) ?? []), form]);
  }
  return order.filter((kind) => groups.has(kind)).map((kind) => ({ kind, label: kindLabel(kind), forms: groups.get(kind) ?? [] }));
}

/** "24 sections · 81 questions", without a zero count dressed up as a fact. */
export function formSummary(form: Pick<LibraryForm, "sectionCount" | "questionCount">): string {
  const sections = form.sectionCount ?? 0;
  const questions = form.questionCount ?? 0;
  return `${sections} ${sections === 1 ? "section" : "sections"} · ${questions} ${questions === 1 ? "question" : "questions"}`;
}

/** The empty line for a tab. */
export function emptyLine(scope: FormScope): string {
  return {
    mine: "You haven't made a form yet.",
    shared: "No forms are shared yet.",
    workshop: "No forms are bound to this workshop.",
    deleted: "Nothing deleted."
  }[scope];
}

/** P16 (UNIFY 4.2): deleting a form. Two buttons, Cancel · Delete. */
export const P16 = { title: "Delete this form?", body: "Admins can restore it from Deleted.", buttons: ["Cancel", "Delete"] } as const;

/** P17 (UNIFY 4.2): a received bundle. A bundle carries no sender, so the web names the form only. */
export function p17(summary: { title: string; sections: number; from?: string | null }) {
  const sections = `${summary.sections} ${summary.sections === 1 ? "section" : "sections"}`;
  return {
    title: "Received a form",
    body: summary.from ? `${summary.from} sent '${summary.title}' (${sections}).` : `'${summary.title}' (${sections}).`,
    buttons: ["Not now", "Add"] as const
  };
}

/** The sentence for a contract error the library shows (FORM_DELETED, BUNDLE_VERSION), else the server's. */
export function libraryError(error: unknown, fallback = "That didn't work. Try again."): string {
  if (error instanceof ApiError) {
    const payload = error.payload as { detail?: { code?: string } } | null;
    const code = payload && typeof payload === "object" && payload.detail && typeof payload.detail === "object" ? payload.detail.code : null;
    if (code === "FORM_DELETED") return "This form was deleted.";
    if (code === "BUNDLE_VERSION") return "This file was made by a newer version of the app. Update, then open it again.";
    return error.message || fallback;
  }
  if (error instanceof Error) return error.message || fallback;
  return fallback;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Bindings: one primary per workshop
 * ──────────────────────────────────────────────────────────────────────────── */

/** The binding list after choosing the workshops a form is bound to and which of them it is primary for. */
export function bindingsFor(workshopIds: string[], primaryFor: string[]): FormBinding[] {
  const unique = Array.from(new Set(workshopIds.filter(Boolean)));
  const primary = new Set(primaryFor);
  return unique.map((workshopId) => ({ workshopId, isPrimary: primary.has(workshopId) }));
}

/* ────────────────────────────────────────────────────────────────────────────
 * The handoff bundle, read in the browser (shared/questionnaire-bundle.json)
 * ──────────────────────────────────────────────────────────────────────────── */

export const BUNDLE_EXTENSIONS = [".frwq", ".dpwq"] as const;
export const BUNDLE_MAX_BYTES = 5 * 1024 * 1024;
export const BUNDLE_SUPPORTED_VERSION = 2;

export type BundleSummary = { title: string; sections: number; questions: number; tooNew: boolean };

export function isBundleName(name: string): boolean {
  const lower = name.toLowerCase();
  return BUNDLE_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

/** What a received bundle holds, for P17, from either the v2 envelope or designer-portal's v1 file. */
export function summariseBundle(document: unknown): BundleSummary | null {
  if (!document || typeof document !== "object") return null;
  const doc = document as Record<string, unknown>;
  if (doc.v !== undefined) {
    const major = Number(String(doc.v).split(".")[0]);
    const form = doc.form as { title?: unknown } | undefined;
    const sections = Array.isArray(doc.sections) ? doc.sections.length : 0;
    const questions = Array.isArray(doc.questions) ? doc.questions.length : 0;
    const title = typeof form?.title === "string" ? form.title.trim() : "";
    if (!Number.isFinite(major)) return null;
    return { title, sections, questions, tooNew: major > BUNDLE_SUPPORTED_VERSION };
  }
  if (Array.isArray(doc.sections) && typeof doc.title === "string") {
    const sections = doc.sections as { questions?: unknown[] }[];
    return {
      title: doc.title.trim(),
      sections: sections.length,
      questions: sections.reduce((sum, section) => sum + (Array.isArray(section?.questions) ? section.questions.length : 0), 0),
      tooNew: false
    };
  }
  return null;
}

/** Gzipped JSON (either extension) or plain JSON, decoded with the browser's own stream. */
export async function decodeBundleBytes(bytes: Uint8Array): Promise<unknown> {
  let data = bytes;
  if (bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b) {
    const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream("gzip"));
    data = new Uint8Array(await new Response(stream).arrayBuffer());
  }
  if (data.length > BUNDLE_MAX_BYTES) throw new Error("This bundle is too large.");
  try {
    return JSON.parse(new TextDecoder().decode(data));
  } catch {
    throw new Error("This file isn't a questionnaire bundle.");
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The calls
 * ──────────────────────────────────────────────────────────────────────────── */

const FORMS = "/questionnaires";

function idempotencyKey(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return `form-${Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("")}`;
}

function json(method: string, body: unknown, create = false): RequestInit {
  return {
    method,
    body: JSON.stringify(body),
    headers: create ? { "Idempotency-Key": idempotencyKey() } : undefined
  };
}

/** The library over any fetcher: the browser uses `library` (over `apiFetch`); the real-stack spec
 * passes its own. */
export function libraryClient(fetchJson: JsonFetcher = apiFetch) {
  return {
    list: (scope: FormScope, options: { workshopId?: string | null; kind?: string | null } = {}) =>
      fetchJson<LibraryForm[]>(`${FORMS}${buildQuery({ scope, workshopId: options.workshopId || undefined, kind: options.kind || undefined })}`),
    get: (id: string) => fetchJson<LibraryFormDetail>(`${FORMS}/${encodeURIComponent(id)}`),
    create: (body: { title: string; description?: string | null; kind?: string | null }) =>
      fetchJson<LibraryForm>(FORMS, json("POST", body, true)),
    patch: (id: string, body: Partial<{ title: string; description: string | null; kind: string | null; isShared: boolean }>) =>
      fetchJson<LibraryForm>(`${FORMS}/${encodeURIComponent(id)}`, json("PATCH", body)),
    remove: (id: string) => fetchJson<LibraryForm>(`${FORMS}/${encodeURIComponent(id)}`, { method: "DELETE" }),
    restore: (id: string) => fetchJson<LibraryForm>(`${FORMS}/${encodeURIComponent(id)}/restore`, { method: "POST" }),
    setDefault: (id: string, isDefault: boolean) =>
      fetchJson<LibraryForm>(`${FORMS}/${encodeURIComponent(id)}/default`, json("PUT", { isDefault })),
    reuse: (id: string, body: { title?: string | null; kind?: string | null }) =>
      fetchJson<LibraryForm>(`${FORMS}/${encodeURIComponent(id)}/reuse`, json("POST", body, true)),
    importBundle: (document: unknown) =>
      fetchJson<{ questionnaire: LibraryForm; created: boolean }>(`${FORMS}/bundles/import`, json("POST", document, true)),
    bindings: (id: string) =>
      fetchJson<{ questionnaireId: string; bindings: FormBinding[] }>(`/questionnaire/forms/${encodeURIComponent(id)}/bindings`),
    putBindings: (id: string, bindings: FormBinding[]) =>
      fetchJson<{ questionnaireId: string; bindings: FormBinding[] }>(
        `/questionnaire/forms/${encodeURIComponent(id)}/bindings`,
        json("PUT", { bindings })
      ),
    typeDefaults: () => fetchJson<{ workshopTypes: WorkshopType[]; defaults: TypeDefault[] }>("/questionnaire/type-defaults"),
    putTypeDefault: (workshopType: WorkshopType, questionnaireId: string | null) =>
      fetchJson<unknown>("/questionnaire/type-defaults", json("PUT", { workshopType, questionnaireId })),
    annexure: (workshopId: string) => fetchJson<AnnexureDoc>(`/questionnaire/annexure${buildQuery({ workshopId })}`)
  };
}

export const library = libraryClient();

export type DownloadedFile = { blob: Blob; fileName: string };

function fileNameFrom(header: string | null): string | null {
  if (!header) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
}

/** A download by hand: `apiFetch` reads every body as JSON or text, which mangles a binary file. */
export async function fetchDownload(path: string, fallbackName: string): Promise<DownloadedFile> {
  assertApiConfigured();
  const response = await fetch(`${API_BASE}/api${path}`, { cache: "no-store" });
  if (!response.ok) {
    const type = response.headers.get("content-type") ?? "";
    const payload = type.includes("application/json") ? await response.json() : await response.text();
    const detail = typeof payload === "object" && payload && "detail" in payload ? (payload as { detail: unknown }).detail : undefined;
    throw new ApiError(response.status, describeApiDetail(detail, response.statusText || "The download didn't start. Try again."), payload);
  }
  return { blob: await response.blob(), fileName: fileNameFrom(response.headers.get("content-disposition")) ?? fallbackName };
}

export const downloads = {
  bundle: (id: string) => fetchDownload(`${FORMS}/${encodeURIComponent(id)}/bundle`, "Questionnaire_Handoff.frwq"),
  annexure: (workshopId: string) =>
    fetchDownload(`/questionnaire/annexure${buildQuery({ workshopId, format: "txt" })}`, "Questionnaire_Annexure.txt"),
  corpusAnswers: (questionnaireId: string) =>
    fetchDownload(`/questionnaire/corpus.csv${buildQuery({ questionnaireId, kind: "answers" })}`, "Questionnaire_Corpus-Answers.csv"),
  corpusRecordings: (questionnaireId: string) =>
    fetchDownload(`/questionnaire/corpus.csv${buildQuery({ questionnaireId, kind: "recordings" })}`, "Questionnaire_Corpus-Recordings.csv")
};

export function saveDownload(file: DownloadedFile) {
  saveBlobToDevice(file.blob, file.fileName);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Sessions on a form: the live list and, for content admins, the deleted one
 * ──────────────────────────────────────────────────────────────────────────── */

export type FormSession = {
  id: string;
  title: string;
  sessionDate?: string | null;
  source?: string | null;
  deletedAt?: string | null;
  participantCount?: number;
};

export const formSessions = {
  list: (questionnaireId: string, deleted = false) =>
    apiFetch<{ items: FormSession[]; nextCursor: string | null }>(
      `/questionnaire/sessions${buildQuery({ questionnaireId, deleted: deleted ? "true" : undefined, limit: 50 })}`
    ),
  restore: (sessionId: string) => apiFetch<unknown>(`/questionnaire/sessions/${encodeURIComponent(sessionId)}/restore`, { method: "POST" })
};

/** "Uploaded" for a session imported from a workbook, "Handed over" for one from a bundle. */
export function sourceLabel(source: string | null | undefined): string | null {
  if (source === "UPLOAD") return "Uploaded";
  if (source === "HANDOFF") return "Handed over";
  return null;
}
