export const SOLE = "SOLE";
export const GROUP = "GROUP";

export type Attribution = typeof SOLE | typeof GROUP;
export type AnswerKind = "TYPED" | "RECORDED";

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

export type ConsolidatedAnswer = {
  kind: AnswerKind;
  sourceId: string;
  answerText?: string | null;
  notes?: string | null;
  recordedByName?: string | null;
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
  conflict: boolean;
  answers: ConsolidatedAnswer[];
};

export type ConsolidatedSection = {
  id: string;
  code: string;
  title: string;
  sortOrder: number;
  questions: ConsolidatedQuestion[];
  recordings: ConsolidatedAnswer[];
};

export type ConsolidatedQuestionnaire = {
  artisan: { id: string; name: string; craftName: string | null; place: string | null };
  generatedAt: string;
  interviews: InterviewSource[];
  sections: ConsolidatedSection[];
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

export const DATE_BASIS_NOTE: Record<DateBasis, string> = {
  interviewDate: "Date of interview, as recorded on the entry.",
  recordedAt: "No interview date; showing when it was entered.",
  createdAt: "No interview date; showing when it was created.",
  unknown: "No date of any kind is stored on this entry."
};
