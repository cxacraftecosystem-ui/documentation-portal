import { exportFilename } from "@/lib/naming";

export const BUNDLE_VERSION = 2;
export const BUNDLE_EXTENSIONS = [".dpwq", ".frwq"] as const;
export const BUNDLE_LIMITS = { maxSections: 200, maxQuestionsPerSection: 500, maxTextChars: 4000, maxUncompressedBytes: 5 * 1024 * 1024 };

export type BundleSection = { code: string; title: string; sortOrder: number };
export type BundleQuestion = {
  sectionCode: string;
  prompt: string;
  helpText: string | null;
  isRequired: boolean;
  artisanField: string | null;
  questionCode: string | null;
  sortOrder: number;
};
export type QuestionnaireBundle = {
  v: number;
  bundleId: string;
  createdAt: string;
  portal: string;
  form: { title: string; description: string | null; kind: string | null };
  sections: BundleSection[];
  questions: BundleQuestion[];
};

export class BundleError extends Error {
  constructor(
    readonly code: "BUNDLE_VERSION" | "BUNDLE_INVALID" | "BUNDLE_TOO_LARGE",
    message: string
  ) {
    super(message);
  }
}

function randomHex(bytes = 16): string {
  const out = new Uint8Array(bytes);
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") crypto.getRandomValues(out);
  else for (let i = 0; i < bytes; i++) out[i] = Math.floor(Math.random() * 256);
  return Array.from(out, (b) => b.toString(16).padStart(2, "0")).join("");
}

const clip = (value: unknown, max = BUNDLE_LIMITS.maxTextChars): string => String(value ?? "").slice(0, max);

export function buildBundle(
  form: {
    title: string;
    description?: string | null;
    kind?: string | null;
    sections: { code: string; title: string; sortOrder?: number; questions: { prompt: string; helpText?: string | null; isRequired?: boolean; artisanField?: string | null; questionCode?: string | null; sortOrder?: number; isActive?: boolean }[] }[];
  },
  portal: string,
  now: Date = new Date()
): QuestionnaireBundle {
  const sections = form.sections.slice(0, BUNDLE_LIMITS.maxSections);
  return {
    v: BUNDLE_VERSION,
    bundleId: randomHex(),
    createdAt: now.toISOString(),
    portal,
    form: { title: clip(form.title, 220), description: form.description ? clip(form.description) : null, kind: form.kind ?? null },
    sections: sections.map((s, i) => ({ code: clip(s.code, 16), title: clip(s.title, 220), sortOrder: s.sortOrder ?? i + 1 })),
    questions: sections.flatMap((s) =>
      s.questions
        .filter((q) => q.isActive !== false)
        .slice(0, BUNDLE_LIMITS.maxQuestionsPerSection)
        .map((q, i) => ({
          sectionCode: clip(s.code, 16),
          prompt: clip(q.prompt),
          helpText: q.helpText ? clip(q.helpText) : null,
          isRequired: Boolean(q.isRequired),
          artisanField: q.artisanField ?? null,
          questionCode: q.questionCode ?? null,
          sortOrder: q.sortOrder ?? i + 1
        }))
    )
  };
}

export function bundleFileName(bundle: Pick<QuestionnaireBundle, "form" | "createdAt">, extension: string): string {
  return exportFilename("Questionnaire", "Handoff", extension.replace(/^\./, ""), { subject: bundle.form.title, at: bundle.createdAt });
}

async function gzip(text: string): Promise<Blob> {
  const stream = new Blob([text], { type: "application/json" }).stream().pipeThrough(new CompressionStream("gzip"));
  return new Response(stream).blob();
}

async function gunzip(blob: Blob): Promise<string> {
  const head = new Uint8Array(await blob.slice(0, 2).arrayBuffer());
  if (head[0] !== 0x1f || head[1] !== 0x8b) return blob.text();
  const stream = blob.stream().pipeThrough(new DecompressionStream("gzip"));
  return new Response(stream).text();
}

export async function bundleBlob(bundle: QuestionnaireBundle): Promise<Blob> {
  const blob = await gzip(JSON.stringify(bundle));
  return new Blob([blob], { type: "application/octet-stream" });
}

export function validateBundle(raw: unknown): QuestionnaireBundle {
  if (!raw || typeof raw !== "object") throw new BundleError("BUNDLE_INVALID", "That file is not a questionnaire bundle.");
  const b = raw as Record<string, unknown>;
  const v = typeof b.v === "number" ? b.v : 1;
  if (Math.floor(v) > BUNDLE_VERSION) throw new BundleError("BUNDLE_VERSION", "This bundle was made by a newer app. Update to open it.");
  const form = (b.form ?? {}) as Record<string, unknown>;
  const title = String(form.title ?? "").trim();
  if (!title) throw new BundleError("BUNDLE_INVALID", "That bundle has no form title.");
  const sections = Array.isArray(b.sections) ? b.sections : [];
  const questions = Array.isArray(b.questions) ? b.questions : [];
  if (sections.length > BUNDLE_LIMITS.maxSections) throw new BundleError("BUNDLE_TOO_LARGE", "That bundle has too many sections.");
  const codes = new Set<string>();
  const outSections: BundleSection[] = sections.map((s, i) => {
    const row = (s ?? {}) as Record<string, unknown>;
    const code = String(row.code ?? "").trim();
    if (!code || codes.has(code)) throw new BundleError("BUNDLE_INVALID", "A section in that bundle has no code, or a repeated one.");
    codes.add(code);
    return { code: clip(code, 16), title: clip(row.title, 220), sortOrder: Number(row.sortOrder ?? i + 1) || i + 1 };
  });
  const outQuestions: BundleQuestion[] = questions.map((q, i) => {
    const row = (q ?? {}) as Record<string, unknown>;
    const sectionCode = String(row.sectionCode ?? "").trim();
    if (!codes.has(sectionCode)) throw new BundleError("BUNDLE_INVALID", "A question in that bundle names a section it does not have.");
    const prompt = String(row.prompt ?? "").trim();
    if (!prompt) throw new BundleError("BUNDLE_INVALID", "A question in that bundle has no wording.");
    return {
      sectionCode,
      prompt: clip(prompt),
      helpText: row.helpText ? clip(row.helpText) : null,
      isRequired: Boolean(row.isRequired),
      artisanField: typeof row.artisanField === "string" ? row.artisanField : null,
      questionCode: typeof row.questionCode === "string" ? row.questionCode : null,
      sortOrder: Number(row.sortOrder ?? i + 1) || i + 1
    };
  });
  return {
    v,
    bundleId: typeof b.bundleId === "string" && b.bundleId ? b.bundleId : `v1-${title}-${outQuestions.length}`,
    createdAt: typeof b.createdAt === "string" ? b.createdAt : new Date().toISOString(),
    portal: typeof b.portal === "string" ? b.portal : "unknown",
    form: { title: clip(title, 220), description: form.description ? clip(form.description) : null, kind: typeof form.kind === "string" ? form.kind : null },
    sections: outSections,
    questions: outQuestions
  };
}

export async function readBundleFile(file: Blob): Promise<QuestionnaireBundle> {
  if (file.size > BUNDLE_LIMITS.maxUncompressedBytes) throw new BundleError("BUNDLE_TOO_LARGE", "That file is too large to be a questionnaire bundle.");
  let text: string;
  try {
    text = await gunzip(file);
  } catch {
    throw new BundleError("BUNDLE_INVALID", "That file could not be opened as a questionnaire bundle.");
  }
  if (text.length > BUNDLE_LIMITS.maxUncompressedBytes) throw new BundleError("BUNDLE_TOO_LARGE", "That bundle is too large.");
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new BundleError("BUNDLE_INVALID", "That file is not a questionnaire bundle.");
  }
  return validateBundle(parsed);
}

const IMPORTED_KEY = "questionnaire_bundles_imported";

export function importedBundles(): Record<string, string> {
  try {
    const raw = window.localStorage.getItem(IMPORTED_KEY);
    const parsed = raw ? (JSON.parse(raw) as unknown) : {};
    return parsed && typeof parsed === "object" ? (parsed as Record<string, string>) : {};
  } catch {
    return {};
  }
}

export function rememberImported(bundleId: string, formId: string): void {
  try {
    window.localStorage.setItem(IMPORTED_KEY, JSON.stringify({ ...importedBundles(), [bundleId]: formId }));
  } catch {
  }
}
