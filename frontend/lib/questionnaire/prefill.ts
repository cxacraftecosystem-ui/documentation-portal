import type { AnswerItem } from "@/lib/questionnaire/api";

export type PrefillArtisan = Record<string, unknown> & { id: string };
export type PrefillContext = { sessionDate?: string | null; interviewer?: string | null };

const DEFAULT_COUNTRY_CODE = "+91";

export function splitPhone(countryCode: string | null | undefined, phone: string | null | undefined): [string, string] {
  let digits = (phone ?? "").replace(/\D/g, "");
  let code = (countryCode ?? "").trim();
  if (code && !code.startsWith("+")) code = `+${code}`;
  if (!code) {
    code = DEFAULT_COUNTRY_CODE;
    if ((phone ?? "").trim().startsWith("+") && digits.startsWith("91") && digits.length === 12) digits = digits.slice(2);
  } else if (code === "+91" && digits.startsWith("91") && digits.length > 10) {
    digits = digits.slice(code.length - 1);
  }
  return [code, digits];
}

export function formatPhone(countryCode: string | null | undefined, phone: string | null | undefined): string | null {
  const [code, digits] = splitPhone(countryCode, phone);
  if (!digits) return null;
  if (code === "+91" && digits.length === 10) return `+91 ${digits.slice(0, 5)} ${digits.slice(5)}`;
  return `${code} ${digits}`;
}

export function prefillText(artisan: PrefillArtisan, field: string, context: PrefillContext = {}): string | null {
  if (field === "phone") return formatPhone(artisan.phoneCountryCode as string | null, artisan.phone as string | null);
  if (field === "sessionDate" || field === "interviewDate") return context.sessionDate ? String(context.sessionDate).slice(0, 10) : null;
  if (field === "interviewer") return context.interviewer?.trim() || null;
  if (field === "craft") {
    const craft = artisan.craft;
    if (craft && typeof craft === "object") return ((craft as { name?: string | null }).name ?? "").trim() || null;
    return typeof craft === "string" && craft.trim() ? craft.trim() : null;
  }
  const value = artisan[field];
  if (value === null || value === undefined || value === "") return null;
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}T/.test(value)) return value.slice(0, 10);
  return String(value);
}

export function prefillItems(input: {
  sessionId: string;
  questions: { lineageId: string; artisanField: string | null }[];
  artisans: PrefillArtisan[];
  answered: ReadonlySet<string>;
  context?: PrefillContext;
}): AnswerItem[] {
  const out: AnswerItem[] = [];
  for (const q of input.questions) {
    if (!q.artisanField) continue;
    for (const artisan of input.artisans) {
      if (input.answered.has(`${artisan.id}|${q.lineageId}`)) continue;
      const text = prefillText(artisan, q.artisanField, input.context);
      if (!text) continue;
      out.push({
        sessionId: input.sessionId,
        artisanId: artisan.id,
        questionLineageId: q.lineageId,
        text,
        origin: "PREFILL",
        attribution: "INDIVIDUAL",
        sourceRecordingId: null
      });
    }
  }
  return out;
}
