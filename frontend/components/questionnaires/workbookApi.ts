/**
 * The wire contract for the questionnaire workbook, and the five calls that use it.
 *
 * SEPARATE FROM lib/types.ts BECAUSE THE REPORT IS NOT A RECORD. Every type here describes what one
 * upload DID, which is read once on one screen and never stored, listed, filtered or cached. Putting
 * it in types.ts beside Artisan and Workshop would suggest it is a thing the repository holds.
 *
 * NONE OF THESE CALLS MAY EVER GO THROUGH `saveOrQueue` (lib/offline.ts). That helper serialises a
 * JSON body to a string and replays it when the device is next online; a `File` cannot be replayed
 * out of one, and `created: true` semantics do not apply to an upload whose whole result is a change
 * report. Routing a workbook through the outbox produces a queued entry that can never drain and a
 * badge that never clears. This module therefore does not import `@/lib/offline` at all, and
 * `e2e/questionnaire-workbook-unit.spec.ts` asserts the absence of that import rather than trusting
 * the comment.
 */

import { API_BASE, ApiError, apiFetch, assertApiConfigured, describeApiDetail, getToken } from "@/lib/api";

export type QWorkbookProblem = {
  sheet: string | null;
  /** The 1-based worksheet row exactly as Excel's gutter shows it: "row 34" means Ctrl+G, 34. */
  row: number | null;
  severity: "error" | "warning";
  reason: string;
  value: string | null;
};

export type QWorkbookProvenance = {
  action: "answersNotImported" | string;
  sourceQuestionnaireId: string | null;
  answersSkipped: number;
  /** Written on the server to be shown VERBATIM. Never paraphrase it here. */
  reason: string;
};

export type QWorkbookDetail = {
  action: "superseded" | "retired" | "sectionRetired" | string;
  /** The question — or, for `sectionRetired`, the section — this detail is about. */
  questionId: string;
  replacementId?: string;
  before?: string;
  after?: string;
  reason: string;
};

export type QWorkbookReport = {
  created: number;
  updated: number;
  /** The number of sections IN THE WORKBOOK, on both the create and the edit path. */
  sections: number;
  sectionsCreated: number;
  sectionsRetired: number;
  superseded: number;
  retired: number;
  removed: number;
  unchanged: number;
  answersSkipped: number;
  assignedTasksAffected: number;
  provenance: QWorkbookProvenance | null;
  versionBefore: number;
  versionAfter: number;
  problems: QWorkbookProblem[];
  details: QWorkbookDetail[];
};

export type QWorkbookInstrument = {
  id: string;
  title: string;
  description?: string | null;
  version?: number;
  sourceFilename?: string | null;
  isActive?: boolean;
  isDefault?: boolean;
  sectionCount?: number;
  questionCount?: number;
  sections?: unknown[];
};

export type QWorkbookUploadResult = {
  questionnaire: QWorkbookInstrument;
  report: QWorkbookReport;
};

export type QWorkbookFile = { blob: Blob; fileName: string };

/**
 * Fetch one .xlsx by hand, because `apiFetch` cannot.
 *
 * That helper reads every response as JSON or TEXT (lib/api.ts), and reading a workbook as text
 * hands back a mangled string cast to the caller's type — a download that "succeeds" and produces a
 * file Excel refuses to open. So the fetch is built here with the same three obligations `apiFetch`
 * discharges: refuse the request when this build has no usable API address, attach the bearer token,
 * and turn a failure body into the sentence the server actually sent rather than "[object Object]".
 */
async function fetchWorkbook(path: string, fallbackName: string): Promise<QWorkbookFile> {
  assertApiConfigured();

  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${API_BASE}/api${path}`, { headers, cache: "no-store" });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json") ? await response.json() : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload ? (payload as { detail: unknown }).detail : undefined;
    // `statusText` is empty over HTTP/2 — which every deployed request is — so it can never be the
    // last resort on its own, or a body-less failure reaches the screen as a blank error box.
    throw new ApiError(
      response.status,
      describeApiDetail(detail, response.statusText || `The server refused the request (HTTP ${response.status}).`),
      payload
    );
  }
  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? fallbackName
  };
}

function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    // A name that is not valid percent-encoding is still a usable name; `decodeURIComponent` throws
    // on a stray "%" and losing the whole filename over one character would be the worse answer.
    return match[1];
  }
}

/** The blank workbook the questionnaire is typed into. */
export function downloadProForma() {
  return fetchWorkbook("/questionnaires/pro-forma", "questionnaire-pro-forma.xlsx");
}

/**
 * This questionnaire as the same workbook — Question IDs filled in, and no answers.
 *
 * The ids are what make re-uploading this file an EDIT of these questions rather than a second copy
 * of them, which is why the download half exists at all and why the UI must send an admin here
 * rather than to a fresh pro-forma when they want to change an existing instrument in Excel.
 */
export function downloadQuestionnaireWorkbook(id: string) {
  return fetchWorkbook(`/questionnaires/${id}/xlsx`, "questionnaire.xlsx");
}

/**
 * The QUESTION SET: the same questions with the Question IDs blank.
 *
 * UPLOADING IT CREATES A NEW QUESTIONNAIRE rather than editing this one, which is the entire
 * difference between the two downloads and the reason there are two routes rather than one route
 * with a flag. A UI that offered this file to a "re-upload" control would be offering to fork the
 * live instrument.
 */
export function downloadQuestionSet(id: string) {
  return fetchWorkbook(`/questionnaires/${id}/question-set.xlsx`, "questionnaire-questions.xlsx");
}

/**
 * Create a questionnaire from a filled-in pro-forma.
 *
 * `title` and `description` are FORM FIELDS on the same multipart body as the file, because the
 * route declares them with `Form(...)`. Appending them to the URL instead has them silently ignored:
 * an untitled questionnaire with a 201 saying it went fine.
 *
 * NO Content-Type IS SET AND NONE MAY BE. `apiFetch` skips the JSON header for a FormData body
 * (lib/api.ts:168) precisely so the browser can write the multipart boundary into the header itself;
 * setting one by hand produces a body the server cannot parse and a 422 with no useful detail.
 */
export function uploadQuestionnaire(file: File, options?: { title?: string | null; description?: string | null }) {
  const body = new FormData();
  body.append("file", file);
  if (options?.title) body.append("title", options.title);
  if (options?.description) body.append("description", options.description);
  return apiFetch<QWorkbookUploadResult>("/questionnaires/upload", { method: "POST", body });
}

/** Re-upload an edited workbook over an existing questionnaire. Runs the edit-after-answers rule. */
export function reuploadQuestionnaire(id: string, file: File, options?: { title?: string | null }) {
  const body = new FormData();
  body.append("file", file);
  if (options?.title) body.append("title", options.title);
  return apiFetch<QWorkbookUploadResult>(`/questionnaires/${id}/upload`, { method: "POST", body });
}

/**
 * Save a fetched workbook to disk.
 *
 * `components/DownloadCsvButton.tsx` does the same dance and cannot be reused: it turns every
 * failure into "Unable to export CSV (HTTP 403)" and throws the server's own sentence away, which on
 * these routes is the sentence that says what to do next — remove the password, use Save As, upload
 * the other file.
 */
export function saveWorkbook(file: QWorkbookFile) {
  const url = window.URL.createObjectURL(file.blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = file.fileName;
  anchor.click();
  window.URL.revokeObjectURL(url);
}
