import { API_BASE, ApiError, apiFetch, assertApiConfigured, describeApiDetail } from "@/lib/api";
import { saveBlobToDevice } from "@/lib/saveToDevice";
import { exportFilename } from "@/lib/naming";

export type QWorkbookProblem = {
  sheet: string | null;
  row: number | null;
  severity: "error" | "warning";
  reason: string;
  value: string | null;
};

export type QWorkbookProvenance = {
  action: "answersNotImported" | string;
  sourceQuestionnaireId: string | null;
  answersSkipped: number;
  reason: string;
};

export type QWorkbookDetail = {
  action: "superseded" | "retired" | "sectionRetired" | string;
  questionId: string;
  replacementId?: string;
  before?: string;
  after?: string;
  reason: string;
};

export type QWorkbookReport = {
  created: number;
  updated: number;
  sections: number;
  sectionsCreated: number;
  sectionsRetired: number;
  superseded: number;
  retired: number;
  removed: number;
  unchanged: number;
  answersSkipped: number;
  answersImported?: number;
  sessionsCreated?: number;
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
  createdById?: string | null;
};

export type QWorkbookUploadResult = {
  questionnaire: QWorkbookInstrument;
  report: QWorkbookReport;
};

export type QWorkbookFile = { blob: Blob; fileName: string };

async function fetchWorkbook(path: string, fallbackName: string): Promise<QWorkbookFile> {
  assertApiConfigured();

  const headers = new Headers();

  const response = await fetch(`${API_BASE}/api${path}`, { headers, cache: "no-store" });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json") ? await response.json() : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload ? (payload as { detail: unknown }).detail : undefined;
    throw new ApiError(
      response.status,
      describeApiDetail(detail, response.statusText || "The request didn’t go through. Try again."),
      payload
    );
  }
  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? fallbackName
  };
}

export function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
}

export function downloadProForma() {
  return fetchWorkbook("/questionnaires/pro-forma", exportFilename("Questionnaire", "Pro-Forma", "xlsx", { dated: false }));
}

export function downloadQuestionnaireWorkbook(id: string) {
  return fetchWorkbook(`/questionnaires/${id}/xlsx`, exportFilename("Questionnaire", "Export", "xlsx"));
}

export function downloadQuestionSet(id: string) {
  return fetchWorkbook(`/questionnaires/${id}/question-set.xlsx`, exportFilename("Questionnaire", "Questions", "xlsx"));
}

export function uploadQuestionnaire(
  file: File,
  options?: { title?: string | null; description?: string | null; kind?: string | null; workshopId?: string | null }
) {
  const body = new FormData();
  body.append("file", file);
  if (options?.title) body.append("title", options.title);
  if (options?.description) body.append("description", options.description);
  if (options?.kind) body.append("kind", options.kind);
  if (options?.workshopId) body.append("workshopId", options.workshopId);
  return apiFetch<QWorkbookUploadResult>("/questionnaires/upload", { method: "POST", body });
}

export function reuploadQuestionnaire(id: string, file: File, options?: { title?: string | null }) {
  const body = new FormData();
  body.append("file", file);
  if (options?.title) body.append("title", options.title);
  return apiFetch<QWorkbookUploadResult>(`/questionnaires/${id}/upload`, { method: "POST", body });
}

export function saveWorkbook(file: QWorkbookFile) {
  saveBlobToDevice(file.blob, file.fileName);
}
