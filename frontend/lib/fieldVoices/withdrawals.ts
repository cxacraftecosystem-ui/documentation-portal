import { apiFetch } from "@/lib/api";

import STRINGS from "./strings.json";

export type FieldVoiceSubjectType = "ARTISAN" | "RECORDING" | "WORKSHOP" | "USER";
export type WithdrawalBasis = "VERBAL_RECORDED" | "VERBAL_WITNESSED" | "SIGNED_FORM";

export type Withdrawal = {
  subjectType: FieldVoiceSubjectType;
  subjectId: string;
  shown: boolean;
  state: string;
  basis: WithdrawalBasis | null;
  note: string | null;
  recordedAt: string | null;
};

export type WithdrawalChange = { shown: boolean; basis: WithdrawalBasis; note: string };

export type FieldVoicesStringKey = keyof typeof STRINGS.strings;

export const NOTE_MAX_LENGTH = 500;

export const BASES: readonly WithdrawalBasis[] = ["VERBAL_RECORDED", "VERBAL_WITNESSED", "SIGNED_FORM"];

const BASIS_LABEL: Record<WithdrawalBasis, FieldVoicesStringKey> = {
  VERBAL_RECORDED: "basisRecorded",
  VERBAL_WITNESSED: "basisWitnessed",
  SIGNED_FORM: "basisSigned"
};

const RECORD_SUBJECTS: Record<string, FieldVoiceSubjectType> = {
  artisan: "ARTISAN",
  workshop: "WORKSHOP",
  media: "RECORDING"
};

export function fieldVoicesText(key: FieldVoicesStringKey, values: Record<string, string> = {}): string {
  return STRINGS.strings[key].replace(/\{(\w+)\}/g, (whole, name: string) => values[name] ?? whole);
}

export function basisOptions(): { value: WithdrawalBasis; label: string }[] {
  return BASES.map((value) => ({ value, label: fieldVoicesText(BASIS_LABEL[value]) }));
}

export function isWithdrawalBasis(value: string): value is WithdrawalBasis {
  return (BASES as readonly string[]).includes(value);
}

export function fieldVoiceSubjectOf(kind: string): FieldVoiceSubjectType | null {
  return RECORD_SUBJECTS[kind] ?? null;
}

export function withdrawalPath(subjectType: FieldVoiceSubjectType, subjectId: string): string {
  return `/field-voices/withdrawals/${subjectType}/${encodeURIComponent(subjectId)}`;
}

export function withdrawalBody(change: WithdrawalChange): { shown: boolean; basis: WithdrawalBasis; note: string | null } {
  const note = change.note.trim().slice(0, NOTE_MAX_LENGTH).trim();
  return { shown: change.shown, basis: change.basis, note: note || null };
}

export function withdrawalLine(status: Withdrawal, formatDay: (value: string) => string): string {
  if (status.shown) return fieldVoicesText("shown");
  if (!status.recordedAt) return fieldVoicesText("withdrawn");
  return fieldVoicesText("withdrawnOn", { date: formatDay(status.recordedAt) });
}

export function loadWithdrawal(subjectType: FieldVoiceSubjectType, subjectId: string): Promise<Withdrawal> {
  return apiFetch<Withdrawal>(withdrawalPath(subjectType, subjectId));
}

export function saveWithdrawal(
  subjectType: FieldVoiceSubjectType,
  subjectId: string,
  change: WithdrawalChange
): Promise<Withdrawal> {
  return apiFetch<Withdrawal>(withdrawalPath(subjectType, subjectId), {
    method: "POST",
    body: JSON.stringify(withdrawalBody(change))
  });
}
