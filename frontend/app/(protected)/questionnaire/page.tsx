"use client";

import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ClipboardList, Lock, Mic, Pencil, Plus, Save, Square } from "lucide-react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { EmptyState } from "@/components/EmptyState";
import { interviewFromSearchParams } from "@/lib/unifiedSearch";
import { Field, MultiNoteField, Select, TextArea } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { SpokenLanguagePicker, useSpokenLanguageChoice } from "@/components/transcript/SpokenLanguagePicker";
import { setSpokenLanguages } from "@/lib/transcript/uploadChoice";
import { QuestionnaireCaptureControls, useCapturePrefs } from "@/components/forms/QuestionnaireCaptureControls";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { useEditDeepLink } from "@/components/hooks/useEditDeepLink";
import { useListReturn } from "@/components/hooks/useListReturn";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { RecordingStrip } from "@/components/media/Waveform";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { PageHeader } from "@/components/PageHeader";
import { CompletenessFilter } from "@/components/records/CompletenessFilter";
import { RowCompletenessRing } from "@/components/records/CompletenessRing";
import { LiveCompleteness } from "@/components/records/LiveCompleteness";
import { OpenableRow, RecordTitleLink } from "@/components/records/OpenableRow";
import { Pagination } from "@/components/Pagination";
import {
  artisanPickerOptions,
  artisanSetKey,
  artisansNotAtWorkshop,
  outOfWorkshopNotice,
  primaryInterviewArtisanId,
  useWorkshopArtisans,
  workshopScopeSettling
} from "@/components/questionnaires/interviewArtisans";
import { QuestionHelpText, RequiredByInstrument } from "@/components/questionnaires/QuestionHelpText";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { EMPTY_FUNNEL, FunnelFilters, type FunnelValue } from "@/components/FunnelFilters";
import { StatusBadge, statusLabel } from "@/components/StatusBadge";
import { Accordion } from "@/components/ui/Accordion";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { useWorkshopScope, WorkshopScopeSelect } from "@/components/WorkshopScopeSelect";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { ApiError, apiFetch, buildQuery, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { parseCompletenessFilter, type CompletenessFilter as CompletenessValue } from "@/lib/completenessCore";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, useFocusField } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import {
  audioExtensionForMimeType,
  pickAudioRecorderMimeType,
  SPEECH_AUDIO_CONSTRAINTS,
  uploadMediaBatch,
  type BatchProgress
} from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { interviewBody, interviewTitle as interviewTitleFor } from "@/lib/recordPayloads";
import { recordUpdateHref, recordViewHref } from "@/lib/recordView";
import { canManageQuestionnaire, hasRank, isContentAdmin, mayDeleteQuestionnaire, mayEditQuestionnaire } from "@/lib/permissions";
import { UploadsProvider, useEagerStaging, useUploads } from "@/lib/uploads";
import type { MediaFile, PageResult, Questionnaire, QuestionnaireInterview, QuestionnaireQuestion, QuestionnaireSection } from "@/lib/types";
import { settleBatch, unsentMessage } from "@/lib/questionnaireUploads";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { ReviewNoteBanner } from "@/components/review/ReviewNoteBanner";
import { completionVerdictLabel, nextCompletionStatus } from "@/lib/completionVerdict";
import { DraftOfferCard, useFormDraft } from "@/components/forms/FormDraft";
import { artisanStatedSource } from "@/components/forms/statedPrefill";
import { artisanPrefill, automaticInterviewTitle } from "@/lib/questionArtisanFields";
import { applyTranscripts, TRANSCRIPT_SOURCE, transcriptAnswerText, transcriptOffer } from "@/lib/transcriptAnswer";
import { Badge } from "@/components/ui/Badge";
import { AnswerDraftsPanel, isMachineSuggested, MachineSuggestedBadge } from "@/components/questionnaires/AnswerDraftsPanel";
import { phoneOf } from "@/lib/standardValues";
import { languageOptions } from "@/lib/standardValues";
import { QuestionnaireAdminEditor } from "@/components/questionnaires/InstrumentBuilder";
import { SessionsList } from "@/components/questionnaire/SessionsList";
import { FormSessionsCard } from "@/components/questionnaires/FormSessionsCard";
import { useOnline } from "@/components/questionnaires/useOnline";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";

const INTERVIEW_TITLE_MAX = 220;

const INTERVIEW_SECTION = "interview-audio";
const INTERVIEW_SECTION_LABEL = "Interview audio";

const SECTION_CLIP_PREFIX = "section:";
const sectionClipKey = (sectionId: string) => `${SECTION_CLIP_PREFIX}${sectionId}`;
const isSectionClipKey = (key: string) => key.startsWith(SECTION_CLIP_PREFIX);
const clipTraySectionId = (key: string) => `question-audio-${key.replace(SECTION_CLIP_PREFIX, "section-")}`;

const SECTION_CAPTION_PREFIX = "Section audio:";
const QUESTION_CAPTION_PREFIX = "Question audio:";

function captionBody(caption: string | null | undefined, prefix: string): string | null {
  const text = (caption ?? "").trim();
  return text.startsWith(prefix) ? text.slice(prefix.length).trim() : null;
}

function captionIsWholeSection(caption: string | null | undefined, section: { code: string }): boolean {
  const rest = captionBody(caption, SECTION_CAPTION_PREFIX);
  if (rest === null) return false;
  return rest === section.code || rest.startsWith(`${section.code} `);
}

function captionAnswersQuestion(
  caption: string | null | undefined,
  question: { sectionCode: string; sortOrder: number }
): boolean {
  const rest = captionBody(caption, QUESTION_CAPTION_PREFIX);
  if (rest === null) return false;
  const stem = `${question.sectionCode}${question.sortOrder}`;
  return rest === stem || rest.startsWith(`${stem} `) || rest.startsWith(`${stem}-`);
}

function clipAnswersQuestion(
  media: { caption?: string | null; extraMetadata?: unknown },
  question: { id: string; sectionCode: string; prompt: string }
): boolean {
  const extra = media.extraMetadata && typeof media.extraMetadata === "object" ? (media.extraMetadata as Record<string, unknown>) : null;
  if (typeof extra?.questionId === "string") return extra.questionId === question.id;
  const rest = captionBody(media.caption, QUESTION_CAPTION_PREFIX);
  if (rest === null) return false;
  const found = /^([A-Za-z]+?)\s*(\d+)\b\s*-?\s*([\s\S]*)$/.exec(rest);
  if (!found || found[1].toUpperCase() !== question.sectionCode.toUpperCase()) return false;
  const words = (text: string) => text.trim().split(/\s+/).join(" ").toLowerCase();
  return Boolean(found[3].trim()) && words(found[3]) === words(question.prompt);
}

function captionBelongsToSection(caption: string | null | undefined, section: { code: string }): boolean {
  if (captionIsWholeSection(caption, section)) return true;
  const rest = captionBody(caption, QUESTION_CAPTION_PREFIX);
  if (rest === null) return false;
  if (!rest.startsWith(section.code)) return false;
  const next = rest.charAt(section.code.length);
  return next >= "0" && next <= "9";
}

function savedMediaPreview(media: MediaFile): PreviewMedia {
  return {
    key: media.id,
    id: media.id,
    name: media.originalFilename,
    mediaType: media.mediaType,
    mimeType: media.mimeType,
    sizeBytes: media.sizeBytes,
    url: media.url,
    caption: media.caption,
    transcriptStatus: media.transcriptStatus,
    transcriptText: media.transcriptText,
    transcriptError: media.transcriptError
  };
}

function sharedEntrySummary(responses: number, recordings: number): string {
  const answers = `${responses} typed answer${responses === 1 ? "" : "s"}`;
  const clips = `${recordings} recording${recordings === 1 ? "" : "s"}`;
  if (responses && recordings) return `${clips} and ${answers}.`;
  if (recordings) return `${clips}, no typed answers.`;
  if (responses) return `${answers}, no recordings.`;
  return "Nothing recorded yet.";
}

const ARTISAN_SET_TAKEN = "artisan_set_taken";
const MERGE_ANSWER_CONFLICT = "merge_answer_conflict";

type ConflictHolder = { id: string; title: string | null };
type MergeAnswerConflict = { questionId: string; sectionCode: string | null; prompt: string | null; fields: string[] };

function conflictDetail(error: unknown, code: string): Record<string, unknown> | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const payload = error.payload;
  if (!payload || typeof payload !== "object") return null;
  const detail = (payload as { detail?: unknown }).detail;
  if (!detail || typeof detail !== "object" || Array.isArray(detail)) return null;
  const record = detail as Record<string, unknown>;
  return record.code === code ? record : null;
}

function artisanSetHolder(error: unknown): ConflictHolder | null {
  const detail = conflictDetail(error, ARTISAN_SET_TAKEN);
  const holder = detail?.holder;
  if (!holder || typeof holder !== "object") return null;
  const { id, title } = holder as { id?: unknown; title?: unknown };
  if (typeof id !== "string" || !id) return null;
  return { id, title: typeof title === "string" && title.trim() ? title.trim() : null };
}

function mergeAnswerConflicts(error: unknown): MergeAnswerConflict[] | null {
  const detail = conflictDetail(error, MERGE_ANSWER_CONFLICT);
  if (!detail) return null;
  const rows = Array.isArray(detail.questions) ? detail.questions : [];
  return rows
    .filter((row): row is Record<string, unknown> => Boolean(row) && typeof row === "object")
    .map((row) => ({
      questionId: typeof row.questionId === "string" ? row.questionId : "",
      sectionCode: typeof row.sectionCode === "string" ? row.sectionCode : null,
      prompt: typeof row.prompt === "string" ? row.prompt : null,
      fields: Array.isArray(row.fields) ? row.fields.filter((f): f is string => typeof f === "string") : []
    }));
}

const MERGE_FIELD_LABELS: Record<string, string> = { answerText: "the written answer", notes: "the notes" };
function conflictFieldLabel(field: string): string {
  return MERGE_FIELD_LABELS[field] ?? field;
}

export default function QuestionnairePage() {
  const config = useQuestionnaireConfig();
  const pathname = usePathname();
  const { user } = useAuth();
  const online = useOnline();
  if (config === null) return <div className="panel p-4 text-sm text-ink-500">Loading...</div>;
  if (config.v2Enabled && !pathname.startsWith("/questionnaire/builder")) {
    return (
      <>
        <SessionsList />
        {isContentAdmin(user) ? (
          <div className="mt-5">
            <FormSessionsCard questionnaireId={null} canSeeDeleted deletedOnly online={online} />
          </div>
        ) : null}
      </>
    );
  }
  return (
    <UploadsProvider>
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">Loading...</div>}>
        <QuestionnairePageBody />
      </Suspense>
      <UploadTray />
    </UploadsProvider>
  );
}

function QuestionnairePageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { adminMode, canDeleteRecords } = useAdminView();
  const { addCompleted } = useUploads();
  const searchParams = useSearchParams();
  const router = useRouter();
  const interviewLink = interviewFromSearchParams(searchParams);
  useEffect(() => {
    if (interviewLink) router.replace(`/questionnaire/interviews/${encodeURIComponent(interviewLink)}`);
  }, [interviewLink, router]);
  const [sections, setSections] = useState<QuestionnaireSection[]>([]);
  const [instruments, setInstruments] = useState<Questionnaire[]>([]);
  const [questionnaireId, setQuestionnaireId] = useState<string | null>(null);
  const instrumentTouched = useRef(false);
  const [data, setData] = useState<PageResult<QuestionnaireInterview> | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [questionAudioFiles, setQuestionAudioFiles] = useState<Record<string, File[]>>({});
  const [selectedArtisanIds, setSelectedArtisanIds] = useState<string[]>(() => {
    const seeded = searchParams.get("artisanId");
    return seeded ? [seeded] : [];
  });
  const [existingEntry, setExistingEntry] = useState<QuestionnaireInterview | null>(null);
  const { prefs: capture, update: updateCapture } = useCapturePrefs();
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const [questionAudioPreviews, setQuestionAudioPreviews] = useState<Record<string, PreviewMedia[]>>({});
  const [recordingKey, setRecordingKey] = useState<string | null>(null);
  const [questionStream, setQuestionStream] = useState<MediaStream | null>(null);
  const [questionElapsedMs, setQuestionElapsedMs] = useState(0);
  const [interviewProgress, setInterviewProgress] = useState<BatchProgress | null>(null);
  const [questionProgress, setQuestionProgress] = useState<Record<string, BatchProgress | null>>({});
  const [page, setPage] = useState(1);
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [funnelReady, setFunnelReady] = useState(false);
  const [searchInput, setSearchInput] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [completeness, setCompleteness] = useState<CompletenessValue>("");
  const listReturn = useListReturn("questionnaire", { query: searchQuery, page, funnel, completeness }, Boolean(data));
  const restoredPage = useRef<number | null>(null);
  useEffect(() => {
    const note = listReturn.restored;
    if (!note) return;
    if (note.query) {
      setSearchInput(note.query);
      setSearchQuery(note.query);
    }
    restoredPage.current = note.page;
    setPage(note.page);
    setCompleteness(parseCompletenessFilter(note.completeness));
  }, [listReturn.restored]);
  const restoreListScroll = listReturn.restoreScroll;
  useEffect(() => restoreListScroll(Boolean(data)), [data, restoreListScroll]);
  const rememberPlace = () => listReturn.remember({ query: searchQuery, page, funnel, completeness });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<QuestionnaireInterview | null>(null);
  const [acceptedNow, setAcceptedNow] = useState<{ interviewId: string | null; ids: string[] }>({ interviewId: null, ids: [] });
  const machineOrigin = useMemo(() => {
    const next: Record<string, string> = {};
    for (const response of editing?.responses ?? []) {
      if (isMachineSuggested(response.answerOrigin)) next[response.questionId] = String(response.answerOrigin);
    }
    if (editing && acceptedNow.interviewId === editing.id) {
      for (const id of acceptedNow.ids) next[id] = next[id] ?? "MACHINE_SUGGESTED";
    }
    return next;
  }, [editing, acceptedNow]);
  const [answeredOnRecord, setAnsweredOnRecord] = useState<Set<string>>(() => new Set());
  const [mergeConflicts, setMergeConflicts] = useState<MergeAnswerConflict[]>([]);
  const [mergeNotice, setMergeNotice] = useState<string | null>(null);
  const formRef = useRef<HTMLFormElement | null>(null);
  const [title, setTitle] = useState("");
  const [place, setPlace] = useState("");
  const [language, setLanguage] = useState("");
  const [fromTranscript, setFromTranscript] = useState<Set<string>>(() => new Set());
  const [answersTouched, setAnswersTouched] = useState(false);
  const [restoredNotes, setRestoredNotes] = useState<string | null>(null);
  const [draftNonce, setDraftNonce] = useState(0);
  const placeFromArtisan = useRef<string | null>(null);
  const [placeFilled, setPlaceFilled] = useState<string | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const elapsedTimerRef = useRef<number | null>(null);

  function stopElapsedTimer() {
    if (elapsedTimerRef.current !== null) {
      window.clearInterval(elapsedTimerRef.current);
      elapsedTimerRef.current = null;
    }
  }

  const canPickStatus = hasRank(user, "PROFESSOR");

  const workshop = useWorkshopSelection({ defaultRule: "mostRecent" });

  const workshopSettling = workshopScopeSettling(workshop);
  const artisanScope = useWorkshopArtisans({ workshopId: workshop.workshopId, settling: workshopSettling });
  const knownArtisans = artisanScope.known;

  const questions = useMemo(() => sections.flatMap((section) => section.questions), [sections]);
  const questionsById = useMemo(() => new Map(questions.map((question) => [question.id, question])), [questions]);
  const sectionsById = useMemo(() => new Map(sections.map((section) => [section.id, section])), [sections]);

  const orderedGroups = useMemo(() => {
    return sections.map((section) => [section.code, { section, title: section.title, items: section.questions }] as const);
  }, [sections]);

  const primaryArtisanId = primaryInterviewArtisanId(selectedArtisanIds);
  const selectedArtisan = useMemo(
    () => knownArtisans.find((artisan) => artisan.id === primaryArtisanId),
    [knownArtisans, primaryArtisanId]
  );

  const selectedSetKey = artisanSetKey(selectedArtisanIds);
  const spoken = useSpokenLanguageChoice(selectedArtisanIds);
  const spokenValue = spoken.value;
  useEffect(() => {
    setSpokenLanguages(mediaFiles, spokenValue);
  }, [mediaFiles, spokenValue]);

  useEffect(() => {
    if (selectedArtisanIds.length === 0) {
      setExistingEntry(null);
      return;
    }
    let active = true;
    const query = [
      ...selectedArtisanIds.map((id) => `artisanIds=${encodeURIComponent(id)}`),
      ...(questionnaireId ? [`questionnaireId=${encodeURIComponent(questionnaireId)}`] : [])
    ].join("&");
    apiFetch<QuestionnaireInterview | null>(`/questionnaire/interviews/by-artisans?${query}`)
      .then((result) => {
        if (active) setExistingEntry(result ?? null);
      })
      .catch(() => {
        if (active) setExistingEntry(null);
      });
    return () => {
      active = false;
    };
  }, [selectedSetKey, selectedArtisanIds, questionnaireId]);

  const prefilled = useRef<Record<string, string>>({});

  const currentInterviewLoad = useRef(0);

  const currentMetaLoad = useRef(0);

  useEffect(() => {
    if (!selectedArtisan || questions.length === 0) return;
    const respondentAnswers = artisanPrefill(questions, selectedArtisan, user);
    const previous = prefilled.current;
    prefilled.current = respondentAnswers;
    setAnswers((current) => {
      const next = { ...current };
      let changed = false;
      Object.entries(respondentAnswers).forEach(([questionId, value]) => {
        const existing = next[questionId] ?? "";
        if (existing && existing !== (previous[questionId] ?? "")) return;
        if (existing === value) return;
        next[questionId] = value;
        changed = true;
      });
      return changed ? next : current;
    });
  }, [questions, selectedArtisan, user]);

  useEffect(() => {
    if (editing || !selectedArtisan) return;
    const offered = selectedArtisan.place?.trim() ?? "";
    if (!offered) return;
    setPlace((current) => {
      if (current.trim() && current !== placeFromArtisan.current) return current;
      placeFromArtisan.current = offered;
      return offered;
    });
    setPlaceFilled(offered);
  }, [selectedArtisan, editing]);

  const automaticTitle = automaticInterviewTitle(
    selectedArtisan?.name,
    selectedArtisanIds.length,
    instruments.find((instrument) => instrument.id === questionnaireId)?.title
  );

  const transcriptByQuestion = useMemo(() => {
    const out: Record<string, string> = {};
    const media = [...(editing?.media ?? [])].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
    for (const question of questions) {
      const clip = media.find((item) => clipAnswersQuestion(item, question) && transcriptAnswerText(item.transcriptText));
      if (clip) out[question.id] = transcriptAnswerText(clip.transcriptText);
    }
    return out;
  }, [editing, questions]);
  const transcriptsReady = Object.entries(transcriptByQuestion).filter(([id, text]) => transcriptOffer(answers[id], text) !== null).length;

  const interviewDirty = Boolean(
    title.trim() ||
      language.trim() ||
      answersTouched ||
      mediaFiles.length ||
      Object.values(questionAudioFiles).some((files) => files.length) ||
      fromTranscript.size
  );
  const interviewDraft = useFormDraft({
    kind: "questionnaire",
    recordId: editing?.id ?? null,
    parentId: editing ? null : searchParams.get("artisanId"),
    parentLabel: editing ? null : (selectedArtisan?.name ?? null),
    dirty: interviewDirty,
    label: title.trim() || automaticTitle,
    fields: {
      title,
      place,
      language,
      answers,
      selectedArtisanIds,
      questionAudioFiles,
      mediaFiles,
      fromTranscript: Array.from(fromTranscript),
      questionnaireId,
      workshopId: workshop.workshopId
    },
    collect: () => {
      const notes = formRef.current ? new FormData(formRef.current).get("notes") : null;
      return typeof notes === "string" ? { notes } : {};
    },
    onRestore: (fields) => {
      const text = (key: string) => (typeof fields[key] === "string" ? (fields[key] as string) : "");
      setTitle(text("title"));
      setPlace(text("place"));
      setLanguage(text("language"));
      if (fields.answers && typeof fields.answers === "object") setAnswers(fields.answers as Record<string, string>);
      if (Array.isArray(fields.selectedArtisanIds)) setSelectedArtisanIds(fields.selectedArtisanIds as string[]);
      if (fields.questionAudioFiles && typeof fields.questionAudioFiles === "object") {
        setQuestionAudioFiles(fields.questionAudioFiles as Record<string, File[]>);
      }
      if (Array.isArray(fields.mediaFiles)) setMediaFiles(fields.mediaFiles as File[]);
      if (Array.isArray(fields.fromTranscript)) setFromTranscript(new Set(fields.fromTranscript as string[]));
      const instrument = text("questionnaireId");
      if (instrument && !editing) {
        instrumentTouched.current = true;
        setQuestionnaireId(instrument);
        void loadMeta(instrument);
      }
      const workshopId = text("workshopId");
      if (workshopId) workshop.setWorkshopId(workshopId);
      setAnswersTouched(true);
      setRestoredNotes(text("notes") || null);
      setDraftNonce((nonce) => nonce + 1);
    }
  });

  function takeTranscriptAsAnswer(questionId: string) {
    const offer = transcriptOffer(answers[questionId], transcriptByQuestion[questionId]);
    if (offer === null) return;
    setAnswers((current) => ({ ...current, [questionId]: offer }));
    setFromTranscript((current) => new Set(current).add(questionId));
  }

  function takeAllTranscripts() {
    const { answers: next, filled } = applyTranscripts(answers, transcriptByQuestion);
    if (!filled.length) return;
    setAnswers(next);
    setFromTranscript((current) => {
      const out = new Set(current);
      filled.forEach((id) => out.add(id));
      return out;
    });
  }

  async function loadMeta(instrumentId?: string | null) {
    const generation = (currentMetaLoad.current += 1);
    try {
      const [instrumentList, sectionList] = await Promise.all([
        apiFetch<Questionnaire[]>("/questionnaires"),
        apiFetch<QuestionnaireSection[]>(
          `/questionnaire/sections${buildQuery({ questionnaireId: instrumentId ?? undefined })}`
        )
      ]);
      if (generation !== currentMetaLoad.current) return;
      setInstruments(instrumentList);
      const resolved = instrumentId ?? sectionList[0]?.questionnaireId
        ?? instrumentList.find((instrument) => instrument.isDefault)?.id
        ?? instrumentList[0]?.id
        ?? null;
      setQuestionnaireId(resolved);
      setSections(sectionList);
      setError(null);
    } catch (err) {
      if (generation !== currentMetaLoad.current) return;
      setError(err instanceof Error ? err.message : "Unable to load questionnaire");
    }
  }

  const boundInstrumentId = useMemo(
    () => workshop.workshops?.find((row) => row.id === workshop.workshopId)?.questionnaireId ?? null,
    [workshop.workshops, workshop.workshopId]
  );

  useEffect(() => {
    if (editing || instrumentTouched.current) return;
    const bound = boundInstrumentId;
    if (!bound || bound === questionnaireId) return;
    setQuestionnaireId(bound);
    void loadMeta(bound);
  }, [boundInstrumentId, questionnaireId, editing]);

  const carry = useCarryContext({
    scopes: [carryScope("artisan", artisanScope.referenceState, knownArtisans)],
    applies: ["artisan", "workshop"],
    onApply: (context) => {
      if (context.artisanId) {
        const carried = context.artisanId;
        setSelectedArtisanIds((current) => (current.includes(carried) ? current : [carried, ...current]));
      }
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  function clearCarriedContext() {
    carry.change();
    setSelectedArtisanIds([]);
  }

  const artisansOutOfWorkshop = useMemo(
    () =>
      artisansNotAtWorkshop({
        selectedIds: selectedArtisanIds,
        offeredIds: artisanScope.scoped.map((artisan) => artisan.id),
        loadedForWorkshop: artisanScope.loadedForWorkshop,
        workshopId: workshop.workshopId,
        cut: artisanScope.cut
      }),
    [selectedArtisanIds, artisanScope.scoped, artisanScope.loadedForWorkshop, artisanScope.cut, workshop.workshopId]
  );
  const outOfWorkshopMessage = useMemo(
    () =>
      outOfWorkshopNotice(
        artisansOutOfWorkshop
          .map((id) => knownArtisans.find((artisan) => artisan.id === id)?.name)
          .filter((name): name is string => Boolean(name))
      ),
    [artisansOutOfWorkshop, knownArtisans]
  );

  async function loadInterviews() {
    const generation = (currentInterviewLoad.current += 1);
    try {
      const result = await listResource<QuestionnaireInterview>("/questionnaire/interviews", {
        page,
        pageSize: 20,
        workshopId: funnel.workshopId || undefined,
        artisanId: funnel.artisanId || undefined,
        search: searchQuery || undefined,
        completeness: completeness || undefined
      });
      if (generation !== currentInterviewLoad.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (generation !== currentInterviewLoad.current) return;
      setError(err instanceof Error ? err.message : "Unable to load questionnaire interviews");
    }
  }

  useEffect(() => {
    loadMeta();
  }, []);

  useEffect(() => {
    if (!funnelReady) return;
    loadInterviews();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [funnelReady, page, funnel.workshopId, funnel.artisanId, searchQuery, completeness]);

  useEffect(() => {
    return () => {
      stopElapsedTimer();
      recorderRef.current?.stream.getTracks().forEach((track) => track.stop());
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  const stagedQuestionAudio = useMemo(() => Object.values(questionAudioFiles).flat(), [questionAudioFiles]);
  const questionAudioStaging = useEagerStaging(stagedQuestionAudio, "Question recordings");
  const stagedByFile = useMemo(
    () => new Map(stagedQuestionAudio.map((file, index) => [file, questionAudioStaging.entries[index]])),
    [stagedQuestionAudio, questionAudioStaging.entries]
  );

  function clipCountLabel(key: string): string {
    const files = questionAudioFiles[key] ?? [];
    const entries = files.map((file) => stagedByFile.get(file) ?? null);
    const ready = entries.filter((entry) => entry?.status === "ready").length;
    const failed = entries.filter((entry) => entry?.status === "error").length;
    const clips = `${files.length} clip${files.length === 1 ? "" : "s"}`;
    if (failed) return `${clips} · ${failed} failed, retries on save`;
    if (ready === files.length) return `${clips} · uploaded`;
    return `${clips} · ${ready} of ${files.length} uploaded`;
  }

  useEffect(() => {
    const nextPreviews: Record<string, PreviewMedia[]> = {};
    Object.entries(questionAudioFiles).forEach(([questionId, files]) => {
      nextPreviews[questionId] = files.map((file, index) => ({
        key: `${questionId}-${file.name}-${file.size}-${file.lastModified}-${index}`,
        name: file.name,
        mediaType: "AUDIO",
        mimeType: file.type || "audio/webm",
        sizeBytes: file.size,
        url: URL.createObjectURL(file)
      }));
    });
    setQuestionAudioPreviews(nextPreviews);
    return () => {
      Object.values(nextPreviews).flat().forEach((item) => {
        if (item.url) URL.revokeObjectURL(item.url);
      });
    };
  }, [questionAudioFiles]);

  function handleSearchChange(value: string) {
    setSearchInput(value);
    if (value === "") {
      setSearchQuery("");
      setPage(1);
    }
  }

  function applySearch() {
    setSearchQuery(searchInput.trim());
    setPage(1);
  }

  async function startRecording(key: string, filenameBase: string) {
    try {
      if (recordingKey) recorderRef.current?.stop();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: SPEECH_AUDIO_CONSTRAINTS });
      streamRef.current = stream;
      chunksRef.current = [];
      const preferredType = pickAudioRecorderMimeType();
      const recorder = new MediaRecorder(stream, preferredType ? { mimeType: preferredType } : undefined);
      recorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.onstop = () => {
        const mimeType = recorder.mimeType || preferredType || "audio/webm";
        const extension = audioExtensionForMimeType(mimeType);
        const blob = new Blob(chunksRef.current, { type: mimeType });
        const file = new File([blob], `${filenameBase}.${extension}`, { type: mimeType });
        setQuestionAudioFiles((current) => ({
          ...current,
          [key]: [...(current[key] ?? []), file]
        }));
        stream.getTracks().forEach((track) => track.stop());
        if (recorderRef.current !== recorder) return;
        streamRef.current = null;
        recorderRef.current = null;
        stopElapsedTimer();
        setQuestionStream(null);
        setQuestionElapsedMs(0);
        setRecordingKey(null);
      };
      recorder.onstart = () => {
        const startedAt = Date.now();
        setQuestionElapsedMs(0);
        elapsedTimerRef.current = window.setInterval(() => setQuestionElapsedMs(Date.now() - startedAt), 250);
      };
      recorder.start();
      setQuestionStream(stream);
      setRecordingKey(key);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to start recording");
    }
  }

  function stopRecording() {
    recorderRef.current?.stop();
  }

  function clipBatch(key: string): { caption: string; extraMetadata: Record<string, unknown>; trayLabel: string } | null {
    if (isSectionClipKey(key)) {
      const section = sectionsById.get(key.slice(SECTION_CLIP_PREFIX.length));
      if (!section) return null;
      return {
        caption: `Section audio: ${section.code} ${section.title}`.trim(),
        extraMetadata: { sectionId: section.id, sectionCode: section.code, sectionTitle: section.title, spokenLanguage: spoken.value },
        trayLabel: `Section ${section.code} audio`
      };
    }
    const question = questionsById.get(key);
    if (!question) return null;
    return {
      caption: `Question audio: ${question.sectionCode}${question.sortOrder} - ${question.prompt}`,
      extraMetadata: { questionId: question.id, questionPrompt: question.prompt, sectionCode: question.sectionCode, spokenLanguage: spoken.value },
      trayLabel: `Q${question.sectionCode}${question.sortOrder} audio`
    };
  }

  function clearHeaderBoxes() {
    setTitle("");
    setPlace("");
    setLanguage("");
    placeFromArtisan.current = null;
    setPlaceFilled(null);
    setFromTranscript(new Set());
  }

  function resetToCreate() {
    setEditing(null);
    formRef.current?.reset();
    clearHeaderBoxes();
    setAnswers({});
    setMediaFiles([]);
    setQuestionAudioFiles({});
    setSelectedArtisanIds([]);
    setInterviewProgress(null);
    setQuestionProgress({});
    setAnsweredOnRecord(new Set());
    setFromTranscript(new Set());
    setMergeConflicts([]);
    setMergeNotice(null);
  }

  function seedFromInterview(interview: QuestionnaireInterview) {
    setEditing(interview);
    setTitle(interview.title ?? "");
    setPlace(interview.place ?? "");
    setLanguage(interview.language ?? "");
    setAnswers(
      Object.fromEntries(
        (interview.responses ?? [])
          .filter((response) => response.questionId)
          .map((response) => [response.questionId, response.answerText ?? ""])
      )
    );
    setAnsweredOnRecord(
      new Set(
        (interview.responses ?? [])
          .filter((response) => response.questionId && (response.answerText ?? "").trim())
          .map((response) => response.questionId)
      )
    );
    setSelectedArtisanIds((interview.artisans ?? []).map((link) => link.artisan?.id).filter(Boolean) as string[]);
    setFromTranscript(
      new Set(
        (interview.responses ?? [])
          .filter((response) => response.questionId && response.answerSource === TRANSCRIPT_SOURCE)
          .map((response) => response.questionId)
      )
    );
    setMediaFiles([]);
    setQuestionAudioFiles({});
    instrumentTouched.current = true;
    setQuestionnaireId(interview.questionnaireId);
    void loadMeta(interview.questionnaireId);
    if (interview.workshopId) workshop.setWorkshopId(interview.workshopId);
  }

  const { loading: deepLinkLoading } = useEditDeepLink<QuestionnaireInterview>({
    endpoint: "/questionnaire/interviews",
    basePath: "/questionnaire",
    targetRef: formRef,
    onEdit: seedFromInterview,
    onNew: resetToCreate,
    onError: setError,
    allowed: true,
    errorMessage: "Unable to load that interview"
  });
  useFocusField(Boolean(editing) && !deepLinkLoading);
  const completenessValues = useMemo(() => (editing ? undefined : { interviewDate: "on-save" }), [editing]);
  const completenessCounts = useMemo(
    () => ({
      artisans: selectedArtisanIds.length,
      answers: Object.values(answers).filter((answer) => answer.trim()).length,
      recordings: mediaFiles.length + Object.values(questionAudioFiles).reduce((sum, files) => sum + files.length, 0)
    }),
    [selectedArtisanIds, answers, mediaFiles, questionAudioFiles]
  );

  async function offerMergeOnConflict(error: unknown, sourceId: string): Promise<boolean> {
    const holder = artisanSetHolder(error);
    if (!holder) return false;
    const holderName = holder.title ?? "Another interview";
    setMergeConflicts([]);
    setMergeNotice(null);
    const ok = await confirm({
      title: `${holderName} already covers these artisans`,
      body: (
        <>
          Move this interview&apos;s saved answers and recordings into{" "}
          <span className="font-semibold">{holderName}</span>? This interview is then removed.
        </>
      ),
      note: (
        <>
          Only <span className="font-semibold">saved</span> work moves — unsaved changes here will be lost.
          Nothing moves if the two disagree on an answer.
        </>
      ),
      confirmLabel: "Move in",
      cancelLabel: "Cancel",
      tone: "warning"
    });
    if (!ok) {
      setError(
        `Not saved — ${holderName} already covers these artisans. Restore the original artisans, or add the answers there.`
      );
      return true;
    }
    try {
      const survivor = await apiFetch<QuestionnaireInterview>(
        `/questionnaire/interviews/${sourceId}/merge-into/${holder.id}`,
        { method: "POST" }
      );
      seedFromInterview(survivor);
      setError(null);
      setMergeNotice(
        `Moved into ${survivor.title || holderName}, open below.`
      );
      await loadInterviews();
      return true;
    } catch (mergeError) {
      const conflicts = mergeAnswerConflicts(mergeError);
      if (conflicts) {
        setMergeConflicts(conflicts);
        setError(mergeError instanceof Error ? mergeError.message : "The two interviews answer some questions differently.");
        return true;
      }
      setError(mergeError instanceof Error ? mergeError.message : "Unable to move this interview");
      return true;
    }
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    setMergeConflicts([]);
    setMergeNotice(null);
    const artisanIds = selectedArtisanIds;
    const responses = Object.entries(answers)
      .filter(([, answerText]) => answerText.trim())
      .map(([questionId, answerText]) => ({
        questionId,
        answerText: answerText.trim(),
        ...(fromTranscript.has(questionId) ? { answerSource: TRANSCRIPT_SOURCE } : {})
      }));
    try {
      const location = locationFromForm(form);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const interviewTitle = interviewTitleFor(form, automaticTitle);
      const editingId = editing?.id ?? null;
      const interviewPayload = interviewBody(form, {
        editingId,
        automaticTitle,
        canPickStatus,
        workshopId: workshop.workshopId,
        artisanIds,
        responses,
        questionnaireId
      });
      const outcome = await saveOrQueue<QuestionnaireInterview>({
        label: editingId ? `Interview (edit) · ${interviewTitle}` : `Interview · ${interviewTitle}`,
        endpoint: editingId ? `/questionnaire/interviews/${editingId}` : "/questionnaire/interviews",
        method: editingId ? "PATCH" : "POST",
        body: interviewPayload,
        media: [
          {
            files: mediaFiles,
            linkedRecordType: "questionnaire",
            caption: `Interview audio for ${interviewTitle}`,
            location,
            recordedAt,
            recordedTimezone,
            alwaysTranscribe: true
          },
          ...Object.entries(questionAudioFiles).flatMap(([key, files]) => {
            const batch = clipBatch(key);
            if (!batch || !files.length) return [];
            return [
              {
                files,
                linkedRecordType: "questionnaire",
                caption: batch.caption,
                location,
                recordedAt,
                recordedTimezone,
                alwaysTranscribe: true,
                extraMetadata: batch.extraMetadata
              }
            ];
          })
        ]
      });
      interviewDraft.clear();
      setAnswersTouched(false);
      setRestoredNotes(null);
      if (outcome.queued) {
        if (editingId) {
          resetToCreate();
          setSaving(false);
          if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
          return;
        }
        formElement.reset();
        clearHeaderBoxes();
        setAnswers({});
        setMediaFiles([]);
        setQuestionAudioFiles({});
        setSelectedArtisanIds((current) => current.slice(0, 1));
        setSaving(false);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      const interviewBatch = await settleBatch(mediaFiles, () =>
        uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "questionnaire",
          linkedRecordId: saved.id,
          caption: `Interview audio for ${saved.title}`,
          location,
          recordedAt,
          recordedTimezone,
          alwaysTranscribe: true,
          onProgress: setInterviewProgress
        })
      );
      setInterviewProgress(null);
      if (interviewBatch.uploaded.length) addCompleted(INTERVIEW_SECTION, INTERVIEW_SECTION_LABEL, interviewBatch.uploaded);
      const clipsLeft: Record<string, File[]> = {};
      for (const [key, files] of Object.entries(questionAudioFiles)) {
        const batch = clipBatch(key);
        if (!batch || files.length === 0) continue;
        const clipBatchOutcome = await settleBatch(files, () =>
          uploadMediaBatch({
            files,
            linkedRecordType: "questionnaire",
            linkedRecordId: saved.id,
            caption: batch.caption,
            location,
            recordedAt,
            recordedTimezone,
            alwaysTranscribe: true,
            extraMetadata: batch.extraMetadata,
            onProgress: (progress) => setQuestionProgress((current) => ({ ...current, [key]: progress }))
          })
        );
        if (clipBatchOutcome.uploaded.length) addCompleted(clipTraySectionId(key), batch.trayLabel, clipBatchOutcome.uploaded);
        if (clipBatchOutcome.left.length) clipsLeft[key] = clipBatchOutcome.left;
      }
      setQuestionProgress({});
      const unsent = [...interviewBatch.left, ...Object.values(clipsLeft).flat()];
      if (unsent.length) {
        seedFromInterview(saved);
        setMediaFiles(interviewBatch.left);
        setQuestionAudioFiles(clipsLeft);
        setError(unsentMessage(unsent.map((file) => file.name)));
        await loadInterviews();
        return;
      }
      if (editingId) {
        resetToCreate();
        await loadInterviews();
        return;
      }
      const interviewed = knownArtisans.find((artisan) => artisan.id === primaryArtisanId);
      if (interviewed) {
        carry.remember({
          artisanId: interviewed.id,
          artisanName: interviewed.name,
          place: interviewed.place,
          craftId: interviewed.craftId,
          craftName: interviewed.craft?.name ?? null,
          workshopId: workshop.workshopId,
          workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
        });
      }
      formElement.reset();
      clearHeaderBoxes();
      setAnswers({});
      setMediaFiles([]);
      setQuestionAudioFiles({});
      setSelectedArtisanIds((current) => current.slice(0, 1));
      if (page !== 1) setPage(1);
      else await loadInterviews();
    } catch (err) {
      const handled = editing && isContentAdmin(user) ? await offerMergeOnConflict(err, editing.id) : false;
      if (!handled) setError(err instanceof Error ? err.message : "Unable to save interview");
    } finally {
      setSaving(false);
      setInterviewProgress(null);
      setQuestionProgress({});
    }
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this interview?",
        "Deletes the interview, its answers, recordings and transcripts. This can’t be undone.",
        undefined
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/questionnaire/interviews/${id}`, { method: "DELETE" });
      await loadInterviews();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete interview");
    }
  }

  const artisanOptions = useMemo(
    () => artisanPickerOptions({ scoped: artisanScope.scoped, known: knownArtisans, selectedIds: selectedArtisanIds }),
    [artisanScope.scoped, knownArtisans, selectedArtisanIds]
  );

  const instrumentOptions = useMemo(() => {
    const rows = [...instruments];
    const own = editing?.questionnaire ?? null;
    if (own && !rows.some((row) => row.id === own.id)) rows.push(own);
    return rows;
  }, [instruments, editing]);

  const savedMedia = useMemo(() => {
    const all = editing?.media ?? [];
    const bySection = new Map<string, MediaFile[]>();
    const placed = new Set<string>();
    sections.forEach((section) => {
      const mine = all.filter((media) => captionBelongsToSection(media.caption, section));
      if (mine.length) bySection.set(section.id, mine);
      mine.forEach((media) => placed.add(media.id));
    });
    return { bySection, other: all.filter((media) => !placed.has(media.id)) };
  }, [editing, sections]);

  const sectionsOpenOnEdit = useMemo(() => {
    const open = new Set<string>();
    if (!editing) return open;
    sections.forEach((section) => {
      const hasAnswer = section.questions.some((question) => answeredOnRecord.has(question.id));
      if (hasAnswer || (savedMedia.bySection.get(section.id)?.length ?? 0) > 0) open.add(section.id);
    });
    return open;
  }, [editing, sections, answeredOnRecord, savedMedia]);

  const existingEntryTally = useMemo(() => {
    const responses = existingEntry?.responses ?? [];
    const recordings = existingEntry?.media ?? [];
    return { responses, recordings, summary: sharedEntrySummary(responses.length, recordings.length) };
  }, [existingEntry]);

  function sectionTally(group: { section: QuestionnaireSection; items: QuestionnaireQuestion[] }) {
    const clips = savedMedia.bySection.get(group.section.id) ?? [];
    const wholeSectionTake =
      (questionAudioFiles[sectionClipKey(group.section.id)]?.length ?? 0) > 0 ||
      clips.some((media) => captionIsWholeSection(media.caption, group.section));
    const answered = wholeSectionTake
      ? group.items.length
      : group.items.filter(
          (question) =>
            (answers[question.id] ?? "").trim().length > 0 ||
            (questionAudioFiles[question.id]?.length ?? 0) > 0 ||
            clips.some((media) => captionAnswersQuestion(media.caption, question))
        ).length;
    return { questions: group.items.length, answered, saved: clips.length };
  }

  return (
    <>
      <PageHeader
        title="Questionnaire"
        description="Interview artisans section by section."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {mergeNotice ? (
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3 rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm text-ink-700">
          <span className="min-w-0">{mergeNotice}</span>
          <button type="button" className="text-xs font-semibold text-purple-700 dark:text-purple-300" onClick={() => setMergeNotice(null)}>
            Dismiss
          </button>
        </div>
      ) : null}
      {mergeConflicts.length ? (
        <section className="mb-4 rounded-lg border border-amber-500 bg-amber-100 p-4">
          <h2 className="font-display font-bold text-lg text-amber-800">
            Not moved: {mergeConflicts.length} conflicting answer{mergeConflicts.length === 1 ? "" : "s"}
          </h2>
          <p className="mt-1 text-sm text-amber-800">Make these answers match, then try again.</p>
          <ul className="mt-3 grid gap-2">
            {mergeConflicts.map((row, index) => (
              <li key={row.questionId || index} className="rounded-md border border-amber-500 bg-card/70 p-2 text-xs">
                <div className="font-semibold text-ink">
                  {row.sectionCode ? `[${row.sectionCode}] ` : ""}
                  {row.prompt ?? "Unnamed question"}
                </div>
                <div className="mt-1 text-amber-800">
                  {row.fields.length
                    ? `The two interviews disagree on ${row.fields.map(conflictFieldLabel).join(" and ")}.`
                    : "The two interviews disagree on this answer."}
                </div>
              </li>
            ))}
          </ul>
          <button
            type="button"
            className="mt-3 text-xs font-semibold text-purple-700"
            onClick={() => setMergeConflicts([])}
          >
            Dismiss
          </button>
        </section>
      ) : null}

      <CompletionMatrixPanel canOverride={adminMode && isContentAdmin(user)} questionnaireId={questionnaireId} />

      <form
        key={`${editing?.id ?? "new"}-${draftNonce}`}
        ref={formRef}
        onSubmit={submit}
        onKeyDown={handleFormEnter}
        className="panel mb-5 grid gap-4 p-4 scroll-mt-28"
      >
        {editing ? (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-field-300 bg-field-100 px-3 py-2">
            <div className="text-sm text-ink-700">
              <span className="font-semibold text-ink-900">Editing interview</span>
              <span className="px-1.5 text-ink-500">·</span>
              <span>{editing.title || "Untitled interview"}</span>
              {editing.questionnaire?.title ? (
                <span className="block text-xs text-ink-500">On {editing.questionnaire.title}</span>
              ) : null}
              <span className="block text-xs text-ink-500">
                Existing recordings are kept.
              </span>
            </div>
            <ButtonRow align="end">
              {transcriptsReady > 0 ? (
                <button type="button" className="field-button-secondary" onClick={takeAllTranscripts} disabled={saving}>
                  Use {transcriptsReady} transcript{transcriptsReady === 1 ? "" : "s"} as answers
                </button>
              ) : null}
              <button type="button" className="field-button-secondary" onClick={resetToCreate} disabled={saving}>
                Cancel edit
              </button>
            </ButtonRow>
          </div>
        ) : null}
        {editing ? <ReviewNoteBanner recordType="questionnaire" recordId={editing.id} status={editing.status} /> : null}
        {deepLinkLoading ? (
          <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-500">
            Loading that interview...
          </div>
        ) : null}
        <DraftOfferCard draft={interviewDraft} />
        <LiveCompleteness kind="interview" formRef={formRef} stored={editing} values={completenessValues} counts={completenessCounts} />
        {editing ? null : <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />}
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" />
          <DictatedTextInput
            name="title"
            label="Interview title"
            titleCased
            placeholder={automaticTitle}
            maxLength={INTERVIEW_TITLE_MAX}
            explainWhenUnavailable={false}
            value={title}
            onChange={setTitle}
          />
          <div className="grid min-w-0 content-start gap-1" data-field="place">
            <DictatedTextInput
              name="place"
              label="Place"
              titleCased
              explainWhenUnavailable={false}
              value={place}
              onChange={setPlace}
            />
            {placeFilled && place === placeFilled ? <p className="text-xs text-ink-500">From the artisan.</p> : null}
          </div>
          <div className="min-w-0" data-field="language">
            <Field label="Language">
              <Select name="language" value={language} onChange={(event) => setLanguage(event.target.value)}>
                <option value="">Select language</option>
                {languageOptions(language).map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <WorkshopSelect state={workshop} saving={saving} />
          <Field label="Questionnaire">
            <Select
              name="questionnaireId"
              value={questionnaireId ?? ""}
              disabled={saving || Boolean(editing) || instrumentOptions.length === 0}
              onChange={(event) => {
                instrumentTouched.current = true;
                setQuestionnaireId(event.target.value);
                void loadMeta(event.target.value);
              }}
            >
              {instrumentOptions.map((instrument) => (
                <option key={instrument.id} value={instrument.id}>
                  {instrument.title}
                  {instrument.id === boundInstrumentId ? " (this workshop)" : ""}
                </option>
              ))}
            </Select>
            {editing ? (
              <p className="text-xs text-ink-500">
                Can’t be changed after the interview is taken.
              </p>
            ) : null}
          </Field>
          <Field label="Status">
            {canPickStatus ? (
              <Select name="status" defaultValue={editing?.status ?? "PENDING"}>
                {["DRAFT", "PENDING", "APPROVED", "REJECTED"].map((status) => (
                  <option key={status} value={status}>{statusLabel(status)}</option>
                ))}
              </Select>
            ) : (
              <span
                className="inline-flex min-h-10 items-center gap-2 self-start rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm font-medium text-ink-muted"
                title="Submitted for review."
              >
                <Lock className="h-4 w-4" aria-hidden />
                Pending
              </span>
            )}
          </Field>
          <div className="md:col-span-2">
            <Field label="Artisans interviewed" fieldKey="artisans">
              <MultiSelectDropdown
                values={selectedArtisanIds}
                onChange={(next) => {
                  setSelectedArtisanIds(next);
                  const head = knownArtisans.find((artisan) => artisan.id === primaryInterviewArtisanId(next));
                  if (head) {
                    carry.remember(
                      {
                        artisanId: head.id,
                        artisanName: head.name,
                        place: head.place,
                        craftId: head.craftId,
                        craftName: head.craft?.name ?? null
                      },
                      { explicit: true }
                    );
                  }
                }}
                options={artisanOptions}
                searchable
                placeholder="Select the artisans this interview is with"
                emptyLabel={
                  artisanScope.failed
                    ? "This workshop's artisan list could not be loaded"
                    : artisanScope.loadedForWorkshop !== workshop.workshopId
                      ? "Loading artisans…"
                      : workshop.workshopId
                        ? "No artisans are recorded at this workshop yet"
                        : "No artisans recorded yet"
                }
              />
              {artisanScope.failed ? (
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  Couldn&apos;t load this workshop&apos;s artisans. Pick the workshop again to retry.
                </p>
              ) : null}
              <CappedListNotice cuts={[artisanScope.cut]} />
              {outOfWorkshopMessage ? (
                <p className="mt-1 text-xs leading-5 text-ink-500">{outOfWorkshopMessage}</p>
              ) : null}
            </Field>
          </div>
        </div>
        {existingEntry && existingEntry.id !== editing?.id ? (
          <section className="rounded-lg border border-amber-500 bg-amber-100 p-4">
            <h3 className="font-display font-bold text-lg text-amber-800">These artisans already have an entry</h3>
            <p className="mt-1 text-sm text-amber-800">
              {editing ? (
                <>
                  <span className="font-medium">{existingEntry.title}</span> already covers these artisans. Restore
                  the original artisans, or edit that interview instead.
                </>
              ) : (
                <>
                  Saving adds your answers to <span className="font-medium">{existingEntry.title}</span>. Answers by
                  others can only be changed by them or an admin.
                </>
              )}
            </p>
            <p className="mt-2 text-xs text-amber-800">{existingEntryTally.summary}</p>
            {existingEntryTally.responses.length > 0 ? (
              <div className="mt-3 grid gap-2">
                <div className="text-xs font-semibold uppercase tracking-wide text-amber-800">
                  Typed answers ({existingEntryTally.responses.length})
                </div>
                {existingEntryTally.responses.map((response) => (
                  <div key={response.id} className="rounded-md border border-amber-500 bg-card/70 p-2 text-xs">
                    <div className="font-semibold text-ink">
                      {response.question?.sectionCode ? `[${response.question.sectionCode}] ` : ""}
                      {response.question?.prompt ?? "Question"}
                    </div>
                    <div className="mt-1 whitespace-pre-wrap text-ink-muted">{response.answerText}</div>
                    {response.answeredBy?.name ? (
                      <div className="mt-1 text-amber-800">Recorded by {response.answeredBy.name}</div>
                    ) : null}
                  </div>
                ))}
              </div>
            ) : null}
            {existingEntryTally.recordings.length > 0 ? (
              <div className="mt-3 grid gap-2">
                <div className="text-xs font-semibold uppercase tracking-wide text-amber-800">
                  Recordings and files ({existingEntryTally.recordings.length})
                </div>
                <ul className="grid gap-1 text-xs text-amber-800">
                  {existingEntryTally.recordings.map((media) => (
                    <li key={media.id} className="truncate">
                      {media.caption || media.originalFilename}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </section>
        ) : null}
        {selectedArtisan ? (
          <section className="rounded-lg border border-line-200 bg-field-100 p-4">
            <h3 className="font-display font-bold text-lg text-ink">RESP. Respondent Information</h3>
            <div className="mt-2 grid gap-2 text-sm text-ink-muted sm:grid-cols-2 lg:grid-cols-3">
              <div><span className="font-medium text-ink">Name:</span> {selectedArtisan.name}</div>
              <div><span className="font-medium text-ink">Craft:</span> {selectedArtisan.craft?.name ?? "-"}</div>
              <div><span className="font-medium text-ink">Place:</span> {selectedArtisan.place}</div>
              <div><span className="font-medium text-ink">Gender:</span> {selectedArtisan.gender ?? "-"}</div>
              <div><span className="font-medium text-ink">Contact:</span> {phoneOf(selectedArtisan) || selectedArtisan.email || "-"}</div>
            </div>
          </section>
        ) : null}
        <QuestionnaireCaptureControls prefs={capture} onChange={updateCapture} />
        <SpokenLanguagePicker value={spoken.value} onChange={spoken.choose} suggestion={spoken.suggestion} className="sm:max-w-sm" />
        <div data-field="recording">
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={setMediaFiles}
            title="Interview audio"
            description="Record or upload. Transcribed when available."
            allowDocuments={false}
            allowedTypes={["AUDIO"]}
            transcribeChoice={false}
          />
        </div>
        <UploadProgress progress={interviewProgress} sectionId={INTERVIEW_SECTION} label={INTERVIEW_SECTION_LABEL} />
        <LocationFields prefillFrom={editing ? null : artisanStatedSource(selectedArtisan)} />
        <div className="grid gap-3" data-field="answers">
          {orderedGroups.map(([code, group], index) => {
            const tally = sectionTally(group);
            const sectionClips = savedMedia.bySection.get(group.section.id) ?? [];
            return (
            <details
              key={code}
              className="rounded-md border border-line-200 bg-field-100 p-3"
              open={index === 0 || sectionsOpenOnEdit.has(group.section.id)}
            >
              <summary className="cursor-pointer font-display font-bold text-lg text-ink">
                {code}. {group.title}
                <span className="mt-0.5 block font-sans text-xs font-normal text-ink-500">
                  {tally.questions} question{tally.questions === 1 ? "" : "s"} · {tally.answered} answered
                  {tally.saved ? ` · ${tally.saved} saved recording${tally.saved === 1 ? "" : "s"}` : ""}
                </span>
              </summary>
              <div className="mt-3 grid gap-3">
                {capture.recordingMode === "SECTION" || questionAudioFiles[sectionClipKey(group.section.id)]?.length ? (
                  <div className="rounded-md border border-line-200 bg-card p-3">
                    <ClipRecorder
                      hint={
                        capture.recordingMode === "SECTION"
                          ? "Record this entire section in one take."
                          : "Earlier section take — still saved."
                      }
                      showRecordButton={capture.recordingMode === "SECTION"}
                      recording={recordingKey === sectionClipKey(group.section.id)}
                      recordLabel="Record section"
                      stopLabel="Stop section recording"
                      onStart={() =>
                        startRecording(
                          sectionClipKey(group.section.id),
                          `${safeFileName(group.section.code)}-sec-${safeFileName(group.section.title)}`
                        )
                      }
                      onStop={stopRecording}
                      files={questionAudioFiles[sectionClipKey(group.section.id)] ?? []}
                      previews={questionAudioPreviews[sectionClipKey(group.section.id)] ?? []}
                      countLabel={clipCountLabel(sectionClipKey(group.section.id))}
                      onClear={() => setQuestionAudioFiles((current) => ({ ...current, [sectionClipKey(group.section.id)]: [] }))}
                      onRemove={(index) =>
                        setQuestionAudioFiles((current) => ({
                          ...current,
                          [sectionClipKey(group.section.id)]: (current[sectionClipKey(group.section.id)] ?? []).filter((_, i) => i !== index)
                        }))
                      }
                      onOpenPreview={setActivePreview}
                      stream={questionStream}
                      elapsedMs={questionElapsedMs}
                    />
                    <UploadProgress
                      progress={questionProgress[sectionClipKey(group.section.id)] ?? null}
                      sectionId={clipTraySectionId(sectionClipKey(group.section.id))}
                      label={`Section ${group.section.code} audio`}
                      className="mt-2"
                    />
                  </div>
                ) : null}
                {editing ? (
                  <AnswerDraftsPanel
                    interviewId={editing.id}
                    section={group.section}
                    questions={group.items}
                    onAccepted={(kept) => {
                      setAnswers((current) => ({ ...current, ...kept }));
                      setAcceptedNow((current) => ({
                        interviewId: editing.id,
                        ids: [...(current.interviewId === editing.id ? current.ids : []), ...Object.keys(kept)]
                      }));
                    }}
                  />
                ) : null}
                {group.items.map((question) => (
                  <div key={question.id} className="grid gap-1">
                    <span className="field-label" id={`question-label-${question.id}`}>
                      {question.sortOrder}. {question.prompt}
                      <RequiredByInstrument question={question} />
                      {machineOrigin[question.id] ? (
                        <MachineSuggestedBadge edited={machineOrigin[question.id] === "MACHINE_SUGGESTED_EDITED"} />
                      ) : null}
                    </span>
                    <QuestionHelpText question={question} />
                    {capture.recordingMode === "INDIVIDUAL" || questionAudioFiles[question.id]?.length ? (
                      <ClipRecorder
                        hint={
                          capture.recordingMode === "INDIVIDUAL"
                            ? undefined
                            : "Earlier take — still saved."
                        }
                        showRecordButton={capture.recordingMode === "INDIVIDUAL"}
                        recording={recordingKey === question.id}
                        recordLabel="Record this question"
                        stopLabel="Stop question recording"
                        onStart={() =>
                          startRecording(
                            question.id,
                            `${safeFileName(question.sectionCode)}-${question.sortOrder}-${safeFileName(question.prompt)}`
                          )
                        }
                        onStop={stopRecording}
                        files={questionAudioFiles[question.id] ?? []}
                        previews={questionAudioPreviews[question.id] ?? []}
                        countLabel={clipCountLabel(question.id)}
                        onClear={() => setQuestionAudioFiles((current) => ({ ...current, [question.id]: [] }))}
                        onRemove={(index) =>
                          setQuestionAudioFiles((current) => ({
                            ...current,
                            [question.id]: (current[question.id] ?? []).filter((_, i) => i !== index)
                          }))
                        }
                        onOpenPreview={setActivePreview}
                        stream={questionStream}
                        elapsedMs={questionElapsedMs}
                      />
                    ) : null}
                    {transcriptOffer(answers[question.id], transcriptByQuestion[question.id]) !== null ? (
                      <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-xs text-purple-950">
                        <span>Transcript ready.</span>
                        <button
                          type="button"
                          className="field-button-secondary min-h-8 px-3 py-1 text-xs"
                          onClick={() => takeTranscriptAsAnswer(question.id)}
                        >
                          Use as answer
                        </button>
                      </div>
                    ) : null}
                    {capture.hideAnswers && !answeredOnRecord.has(question.id) && !fromTranscript.has(question.id) ? null : (
                      <>
                        {capture.hideAnswers && !fromTranscript.has(question.id) ? (
                          <p className="text-xs leading-5 text-ink-500">
                            Shown because this question already has an answer.
                          </p>
                        ) : null}
                        {fromTranscript.has(question.id) ? (
                          <Badge>From transcript</Badge>
                        ) : null}
                        <TextArea
                          aria-labelledby={`question-label-${question.id}`}
                          value={answers[question.id] ?? ""}
                          onChange={(event) => {
                            const next = event.target.value;
                            setAnswersTouched(true);
                            setAnswers((current) => ({ ...current, [question.id]: next }));
                            setFromTranscript((current) => {
                              if (!current.has(question.id)) return current;
                              const out = new Set(current);
                              out.delete(question.id);
                              return out;
                            });
                          }}
                        />
                        <OnDeviceDictationButton
                          fieldLabel={`the answer to question ${question.sectionCode}${question.sortOrder}`}
                          explainWhenUnavailable={false}
                          onCommit={(phrase) =>
                            setAnswers((current) => ({
                              ...current,
                              [question.id]: appendDictatedPhrase(current[question.id] ?? "", phrase)
                            }))
                          }
                        />
                      </>
                    )}
                    <UploadProgress
                      progress={questionProgress[question.id] ?? null}
                      sectionId={clipTraySectionId(question.id)}
                      label={`Q${question.sectionCode}${question.sortOrder} audio`}
                      className="mt-2"
                    />
                  </div>
                ))}
                <SavedMediaPanel
                  title="Saved recordings & media for this section"
                  description="Already saved."
                  items={sectionClips}
                  onOpenPreview={setActivePreview}
                />
              </div>
            </details>
            );
          })}
        </div>
        <SavedMediaPanel
          title="Other saved recordings & media"
          description="Interview-wide audio and files."
          items={savedMedia.other}
          onOpenPreview={setActivePreview}
        />
        <MultiNoteField name="notes" label="Interview notes" defaultValue={restoredNotes ?? editing?.notes ?? null} />
        <ButtonRow>
          <button className="field-button" disabled={saving}>
            {editing ? <Save className="h-4 w-4" aria-hidden /> : <Plus className="h-4 w-4" aria-hidden />}
            {saving
              ? "Saving..."
              : editing
                ? "Update interview"
                : existingEntry
                  ? "Add to shared entry"
                  : "Save interview"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={resetToCreate} disabled={saving}>
              Cancel
            </button>
          ) : null}
        </ButtonRow>
      </form>

      <div className="mb-4 grid gap-3">
        <FunnelFilters
          value={funnel}
          onChange={(next) => {
            setFunnel(next);
            setFunnelReady(true);
            setPage(restoredPage.current ?? 1);
            restoredPage.current = null;
          }}
          showArtisan
          restore={listReturn.restoredFunnel}
        />
        <SearchInput
          value={searchInput}
          onChange={handleSearchChange}
          onSubmit={applySearch}
          placeholder="Search interviews by title, place, or notes"
        />
        <CompletenessFilter
          value={completeness}
          onChange={(next) => {
            setCompleteness(next);
            setPage(1);
          }}
        />
      </div>

      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No questionnaire interviews yet" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[980px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <th className="px-4 py-3">Interview</th>
                  <th className="px-4 py-3">Artisans</th>
                  <th className="px-4 py-3">Responses</th>
                  <th className="px-4 py-3">Researcher</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Date</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((interview) => (
                  <OpenableRow key={interview.id} href={recordViewHref("questionnaire", interview.id)!} onOpen={rememberPlace}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <RowCompletenessRing block={interview.completeness} />
                        <RecordTitleLink href={recordViewHref("questionnaire", interview.id)!} onOpen={rememberPlace}>
                          {interview.title}
                        </RecordTitleLink>
                      </div>
                      <div className="text-xs text-ink-500">{interview.place ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {interview.artisans?.map((link) => link.artisan.name).join(", ") || "-"}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      <details data-row-ignore>
                        <summary className="cursor-pointer font-semibold text-field-700 dark:text-purple-300">{interview.responses?.length ?? 0} answers</summary>
                        <div className="mt-2 grid max-w-lg gap-2">
                          {interview.responses?.map((response) => (
                            <div key={response.id} className="rounded-md bg-field-100 p-2 text-xs">
                              <div className="font-semibold text-ink">{response.question?.prompt}</div>
                              <div className="mt-1 whitespace-pre-wrap text-ink-muted">{response.answerText}</div>
                            </div>
                          ))}
                        </div>
                      </details>
                    </td>
                    <td className="px-4 py-3 text-ink-700">{interview.createdBy?.email ?? "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={interview.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {formatDate(interview.interviewDate ?? interview.recordedAt ?? interview.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        <Link className={rowAction("edit")} href={recordUpdateHref("questionnaire", interview.id)!}>
                          <Pencil className="h-3.5 w-3.5" aria-hidden />
                          Update
                        </Link>
                        {canDeleteRecords ? (
                          <button className={rowAction("danger")} onClick={() => remove(interview.id)}>
                            Delete
                          </button>
                        ) : null}
                      </RowActions>
                      {canDeleteRecords ? null : (
                        <span className="sr-only">Deleting is for admins only</span>
                      )}
                    </td>
                  </OpenableRow>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      {canManageQuestionnaire(user) ? (
        <QuestionnaireAdminEditor
          sections={sections}
          questionnaireId={questionnaireId}
          onChanged={() => loadMeta(questionnaireId)}
          mayEdit={mayEditQuestionnaire(user, instruments.find((instrument) => instrument.id === questionnaireId)?.createdById)}
          mayDelete={mayDeleteQuestionnaire(user, instruments.find((instrument) => instrument.id === questionnaireId)?.createdById)}
        />
      ) : null}

      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </>
  );
}

function safeFileName(value: string) {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 80) || "question-audio";
}

function ClipRecorder({
  hint,
  showRecordButton,
  recording,
  recordLabel,
  stopLabel,
  onStart,
  onStop,
  files,
  previews,
  countLabel,
  onClear,
  onRemove,
  onOpenPreview,
  stream,
  elapsedMs
}: {
  hint?: string;
  showRecordButton: boolean;
  recording: boolean;
  recordLabel: string;
  stopLabel: string;
  onStart: () => void;
  onStop: () => void;
  files: File[];
  previews: PreviewMedia[];
  countLabel: string;
  onClear: () => void;
  onRemove: (index: number) => void;
  onOpenPreview: (item: PreviewMedia) => void;
  stream: MediaStream | null;
  elapsedMs: number;
}) {
  return (
    <>
      {hint ? <p className="mb-2 text-xs text-ink-muted">{hint}</p> : null}
      <div className="mb-2 flex flex-wrap items-center gap-2">
        {showRecordButton ? (
          <button type="button" className="field-button-secondary" onClick={() => (recording ? onStop() : onStart())}>
            {recording ? <Square className="h-4 w-4" aria-hidden /> : <Mic className="h-4 w-4" aria-hidden />}
            {recording ? stopLabel : recordLabel}
          </button>
        ) : null}
        {files.length ? (
          <>
            <span className="text-xs text-ink-muted">{countLabel}</span>
            <button type="button" className="text-xs font-semibold text-red-700" onClick={onClear}>
              Clear clips
            </button>
          </>
        ) : null}
      </div>
      {recording ? (
        <div className="mb-3">
          <RecordingStrip stream={stream} elapsedMs={elapsedMs} />
        </div>
      ) : null}
      {previews.length ? (
        <div className="mb-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {previews.map((item, index) => (
            <MediaPreviewTile
              key={item.key}
              item={item}
              onOpen={() => onOpenPreview(item)}
              downloadFile={files[index] ?? null}
              action={
                <button type="button" className="text-xs font-semibold text-red-700" onClick={() => onRemove(index)}>
                  Remove
                </button>
              }
            />
          ))}
        </div>
      ) : null}
    </>
  );
}

function SavedMediaPanel({
  title,
  description,
  items,
  onOpenPreview
}: {
  title: string;
  description: string;
  items: MediaFile[];
  onOpenPreview: (item: PreviewMedia) => void;
}) {
  if (!items.length) return null;
  return (
    <div className="rounded-md border border-line-200 bg-card p-3">
      <h4 className="font-display font-bold text-sm text-ink">
        {title} ({items.length})
      </h4>
      <p className="mt-0.5 text-xs text-ink-muted">{description}</p>
      <div className="mt-2 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((media) => {
          const preview = savedMediaPreview(media);
          return <MediaPreviewTile key={preview.key} item={preview} onOpen={() => onOpenPreview(preview)} />;
        })}
      </div>
    </div>
  );
}

type CompletionMatrix = {
  sections: Array<{ id: string; code: string; title: string; sortOrder: number }>;
  artisans: Array<{ id: string; name: string }>;
  cells: Array<{ artisanId: string; sectionId: string; derived: boolean; status: string | null; setByName?: string | null }>;
  unassignedInterviews?: number;
  questionnaireId?: string | null;
  questionnaireTitle?: string | null;
  overridesAreRepositoryWide?: boolean;
};

function CompletionMatrixPanel({
  canOverride,
  questionnaireId
}: {
  canOverride: boolean;
  questionnaireId: string | null;
}) {
  const [matrix, setMatrix] = useState<CompletionMatrix | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyCell, setBusyCell] = useState<string | null>(null);
  const opened = useRef(false);

  const scope = useWorkshopScope();

  const refresh = useCallback(async () => {
    try {
      setMatrix(
        await apiFetch<CompletionMatrix>(
          `/questionnaire/completion${buildQuery({
            workshopIds: scope.queryValue,
            questionnaireId: questionnaireId ?? undefined
          })}`
        )
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load the completion matrix");
    }
  }, [scope.queryValue, questionnaireId]);

  useEffect(() => {
    if (!opened.current || scope.settling) return;
    setLoading(true);
    refresh().finally(() => setLoading(false));
  }, [refresh, scope.settling]);

  function handleOpenChange(open: boolean) {
    if (!open || opened.current) return;
    opened.current = true;
    if (scope.settling) return;
    setLoading(true);
    refresh().finally(() => setLoading(false));
  }

  const cellByKey = useMemo(() => {
    const map = new Map<string, CompletionMatrix["cells"][number]>();
    matrix?.cells.forEach((cell) => map.set(`${cell.artisanId}:${cell.sectionId}`, cell));
    return map;
  }, [matrix]);

  async function cycle(artisanId: string, sectionId: string) {
    if (!canOverride) return;
    const key = `${artisanId}:${sectionId}`;
    const cell = cellByKey.get(key);
    const next = nextCompletionStatus(cell?.status);
    setBusyCell(key);
    try {
      await apiFetch("/questionnaire/completion", {
        method: "PUT",
        body: JSON.stringify({ artisanId, sectionId, status: next })
      });
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to update the completion cell");
    } finally {
      setBusyCell(null);
    }
  }

  return (
    <Accordion
      title="Check completion"
      subtitle={`${
        matrix?.questionnaireTitle ? `${matrix.questionnaireTitle} — ` : ""
      }Sections covered per artisan.${canOverride ? " Click a cell to override." : ""}`}
      onOpenChange={handleOpenChange}
    >
      <div className="mb-4 max-w-xl">
        <WorkshopScopeSelect scope={scope} label="Workshops in this matrix" />
      </div>
      {error ? <div className="mb-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {loading ? <div className="text-sm text-ink-muted">Loading completion matrix...</div> : null}

      {matrix && (matrix.unassignedInterviews ?? 0) > 0 ? (
        <div className="mb-3 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          <span className="font-semibold">
            {matrix.unassignedInterviews} interview{matrix.unassignedInterviews === 1 ? " has" : "s have"} no
            workshop
          </span>{" "}
          and {matrix.unassignedInterviews === 1 ? "isn’t" : "aren’t"} counted. Choose{" "}
          <span className="font-semibold">All records</span> to include{" "}
          {matrix.unassignedInterviews === 1 ? "it" : "them"}
          {canOverride ? (
            <>
              , or file {matrix.unassignedInterviews === 1 ? "it" : "them"} under a workshop from{" "}
              <Link href="/workshops" className="font-semibold underline">
                Workshops
              </Link>
            </>
          ) : null}
          .
        </div>
      ) : null}
      {matrix && matrix.artisans.length === 0 && !loading ? (
        <p className="text-sm text-ink-muted">
          {scope.workshopIds.length
            ? "No artisans in these workshops."
            : "No artisans yet."}
        </p>
      ) : null}
      {matrix && matrix.artisans.length > 0 ? (
        <>
          <div className="overflow-x-auto">
            <table className="text-left text-sm">
              <thead>
                <tr>
                  <th className="py-2 pr-4 text-xs font-semibold uppercase text-ink-500">Artisan</th>
                  {matrix.sections.map((section) => (
                    <th
                      key={section.id}
                      className="px-1 pb-2 text-center align-bottom text-xs font-semibold text-ink-500"
                      title={`${section.code}. ${section.title}`}
                    >
                      {section.code}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {matrix.artisans.map((artisan) => (
                  <tr key={artisan.id}>
                    <td className="whitespace-nowrap py-1 pr-4 font-medium text-ink">{artisan.name}</td>
                    {matrix.sections.map((section) => {
                      const key = `${artisan.id}:${section.id}`;
                      const cell = cellByKey.get(key);
                      const overridden = Boolean(cell?.status);
                      const complete = cell ? (cell.status ? cell.status === "COMPLETED" : cell.derived) : false;
                      const verdict = overridden ? completionVerdictLabel(cell?.status) : null;
                      const state = verdict
                        ? `${verdict}${cell?.setByName ? ` (set by ${cell.setByName})` : ""}`
                        : complete
                          ? "complete"
                          : "not complete";
                      const title = `${artisan.name} — ${section.code}: ${state}`;
                      const fill =
                        cell?.status === "NEEDS_REVIEW"
                          ? "bg-amber-500"
                          : cell?.status === "NEEDS_REDO"
                            ? "bg-error-600"
                            : complete
                              ? "bg-success-600"
                              : "bg-line-200";
                      const square = `h-6 w-6 rounded ${fill}${overridden ? " ring-2 ring-purple-700 ring-offset-1 ring-offset-card" : ""}`;
                      return (
                        <td key={section.id} className="px-1 py-1 text-center">
                          {canOverride ? (
                            <button
                              type="button"
                              className={`${square} align-middle disabled:opacity-60`}
                              title={title}
                              aria-label={title}
                              disabled={busyCell === key}
                              onClick={() => cycle(artisan.id, section.id)}
                            />
                          ) : (
                            <div className={`mx-auto ${square}`} title={title} role="img" aria-label={title} />
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-ink-muted">
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-success-600" aria-hidden /> Complete
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-line-200" aria-hidden /> Not complete
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-amber-500" aria-hidden /> Requires review
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-error-600" aria-hidden /> Needs to be redone
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-line-200 ring-2 ring-purple-700 ring-offset-1 ring-offset-card" aria-hidden /> Admin override
            </span>
          </div>
          {matrix.overridesAreRepositoryWide ? (
            <p className="mt-2 text-[11px] leading-4 text-ink-500">
              Admin overrides apply across all workshops.
            </p>
          ) : null}
        </>
      ) : null}
    </Accordion>
  );
}

