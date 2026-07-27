/**
 * Types for `GET /questionnaire/artisans/{id}/consolidated` — one artisan's questionnaire gathered
 * from every interview they sat in.
 *
 * Kept in their own module rather than appended to `lib/types.ts` so this feature adds no lines to a
 * file every other screen imports.
 */

/** The sitting had ONE artisan, so what was recorded in it is theirs. */
export const SOLE = "SOLE";
/**
 * The sitting had several artisans and the schema records no per-artisan link on a response, so who
 * actually spoke is NOT stored. The UI must say so rather than let the reader assume.
 */
export const GROUP = "GROUP";

export type Attribution = typeof SOLE | typeof GROUP;
export type AnswerKind = "TYPED" | "RECORDED";

/** Which column the displayed date came from — `interviewDate` is null on every stored interview. */
export type DateBasis = "interviewDate" | "recordedAt" | "createdAt" | "unknown";

export type InterviewSource = {
  id: string;
  title: string;
  date: string | null;
  dateBasis: DateBasis;
  status: string;
  workshopTitle: string | null;
  artisanCount: number;
  coParticipants: string[];
  attribution: Attribution;
};

/** One answer, with the provenance that makes it citable. */
export type ConsolidatedAnswer = {
  kind: AnswerKind;
  sourceId: string;
  answerText?: string | null;
  notes?: string | null;
  recordedByName?: string | null;
  /** RECORDED only. */
  mediaId?: string;
  filename?: string | null;
  mediaType?: string | null;
  url?: string | null;
  transcriptText?: string | null;
  transcriptStatus?: string | null;
  interviewId: string;
  interviewTitle: string;
  interviewDate: string | null;
  dateBasis: DateBasis;
  interviewStatus: string;
  workshopTitle: string | null;
  artisanCount: number;
  coParticipants: string[];
  attribution: Attribution;
};

export type ConsolidatedQuestion = {
  id: string;
  prompt: string;
  sortOrder: number;
  /** Two or more materially different answers to this question across interviews. */
  conflict: boolean;
  answers: ConsolidatedAnswer[];
};

export type ConsolidatedSection = {
  id: string;
  code: string;
  title: string;
  sortOrder: number;
  questions: ConsolidatedQuestion[];
  /** Clips that resolved to this section but to no single question. */
  recordings: ConsolidatedAnswer[];
};

export type ConsolidatedQuestionnaire = {
  artisan: { id: string; name: string; craftName: string | null; place: string | null };
  generatedAt: string;
  interviews: InterviewSource[];
  sections: ConsolidatedSection[];
  /** Recordings whose section could not be determined — shown, never dropped. */
  unfiled: ConsolidatedAnswer[];
  summary: {
    interviewCount: number;
    groupSittingCount: number;
    soleSittingCount: number;
    answeredQuestionCount: number;
    typedAnswerCount: number;
    recordedAnswerCount: number;
    unfiledRecordingCount: number;
    conflictCount: number;
  };
  meta: { queryCount: number };
};

/** How a fallback date is explained in the UI, so a system timestamp is never read as a field date. */
export const DATE_BASIS_NOTE: Record<DateBasis, string> = {
  interviewDate: "Date of interview, as recorded on the entry.",
  recordedAt: "No interview date was entered; this is when the entry was recorded in the app.",
  createdAt: "No interview or recording date; this is when the entry was created.",
  unknown: "No date of any kind is stored on this entry."
};
