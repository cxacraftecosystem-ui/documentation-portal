"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ArrowDown, ArrowUp, ChevronDown, ClipboardList, GripVertical, Lock, Mic, Pencil, Plus, Save, Square, Trash2 } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, MultiNoteField, Select, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { RecordingStrip } from "@/components/media/Waveform";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { EMPTY_FUNNEL, FunnelFilters, type FunnelValue } from "@/components/FunnelFilters";
import { StatusBadge } from "@/components/StatusBadge";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { audioExtensionForMimeType, pickAudioRecorderMimeType, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { canManageQuestionnaire, hasRank, isAdmin } from "@/lib/permissions";
import { UploadsProvider, useUploads } from "@/lib/uploads";
import type { Artisan, PageResult, QuestionnaireInterview, QuestionnaireQuestion, QuestionnaireSection } from "@/lib/types";

/** Section ids the two questionnaire upload paths publish under, for the page-level tray. */
const INTERVIEW_SECTION = "interview-audio";
const INTERVIEW_SECTION_LABEL = "Interview audio";
const questionSectionId = (questionId: string) => `question-audio-${questionId}`;

export default function QuestionnairePage() {
  return (
    <UploadsProvider>
      <QuestionnairePageBody />
      <UploadTray />
    </UploadsProvider>
  );
}

function QuestionnairePageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const searchParams = useSearchParams();
  const [sections, setSections] = useState<QuestionnaireSection[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [data, setData] = useState<PageResult<QuestionnaireInterview> | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [questionAudioFiles, setQuestionAudioFiles] = useState<Record<string, File[]>>({});
  const [selectedArtisanId, setSelectedArtisanId] = useState(searchParams.get("artisanId") ?? "");
  const [additionalArtisanIds, setAdditionalArtisanIds] = useState<string[]>([]);
  const [existingEntry, setExistingEntry] = useState<QuestionnaireInterview | null>(null);
  const [answerMode, setAnswerMode] = useState<"audio" | "text">("audio");
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const [questionAudioPreviews, setQuestionAudioPreviews] = useState<Record<string, PreviewMedia[]>>({});
  const [recordingQuestionId, setRecordingQuestionId] = useState<string | null>(null);
  // The live stream is state (not just a ref) because <RecordingStrip>'s waveform re-renders on it.
  const [questionStream, setQuestionStream] = useState<MediaStream | null>(null);
  const [questionElapsedMs, setQuestionElapsedMs] = useState(0);
  const [interviewProgress, setInterviewProgress] = useState<BatchProgress | null>(null);
  const [questionProgress, setQuestionProgress] = useState<Record<string, BatchProgress | null>>({});
  const [page, setPage] = useState(1);
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  const [searchInput, setSearchInput] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
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

  // Professors and above may pick a record's status; everyone below is forced to PENDING
  // (mirrors the backend, which silently drops an unauthorized status on create).
  const canPickStatus = hasRank(user, "PROFESSOR");

  // The workshop this interview was taken at. Create-only form, so it always defaults to the most
  // recent workshop the interviewer may submit to (see components/forms/WorkshopSelect).
  const workshop = useWorkshopSelection();

  const questions = useMemo(() => sections.flatMap((section) => section.questions), [sections]);

  const orderedGroups = useMemo(() => {
    return sections.map((section) => [section.code, { section, title: section.title, items: section.questions }] as const);
  }, [sections]);

  const selectedArtisan = useMemo(() => artisans.find((artisan) => artisan.id === selectedArtisanId), [artisans, selectedArtisanId]);

  // The exact set of artisans this interview covers (primary + additional), de-duplicated. There is a
  // single shared questionnaire entry per such set — we look it up so the researcher sees that it has
  // already been started and which sections others have answered, instead of making a duplicate.
  const selectedArtisanIds = useMemo(
    () => Array.from(new Set([selectedArtisanId, ...additionalArtisanIds].filter(Boolean))),
    [selectedArtisanId, additionalArtisanIds]
  );
  const selectedSetKey = useMemo(() => [...selectedArtisanIds].sort().join(","), [selectedArtisanIds]);

  useEffect(() => {
    if (selectedArtisanIds.length === 0) {
      setExistingEntry(null);
      return;
    }
    let active = true;
    const query = selectedArtisanIds.map((id) => `artisanIds=${encodeURIComponent(id)}`).join("&");
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
  }, [selectedSetKey, selectedArtisanIds]);

  useEffect(() => {
    if (!selectedArtisan || questions.length === 0) return;
    const respondentAnswers: Record<string, string> = {};
    questions
      .filter((question) => question.sectionCode === "RESP")
      .forEach((question) => {
        const prompt = question.prompt.toLowerCase();
        if (prompt.includes("name")) respondentAnswers[question.id] = selectedArtisan.name;
        else if (prompt.includes("craft")) respondentAnswers[question.id] = selectedArtisan.craft?.name ?? "";
        else if (prompt.includes("state") || prompt.includes("district") || prompt.includes("village")) respondentAnswers[question.id] = selectedArtisan.place;
        else if (prompt.includes("gender")) respondentAnswers[question.id] = selectedArtisan.gender ?? "";
        else if (prompt.includes("contact")) respondentAnswers[question.id] = selectedArtisan.phone ?? selectedArtisan.email ?? "";
        else if (prompt.includes("date")) respondentAnswers[question.id] = new Date().toLocaleDateString("en-IN");
        else if (prompt.includes("interviewer")) respondentAnswers[question.id] = user?.name ?? user?.email ?? "";
      });
    setAnswers((current) => ({ ...respondentAnswers, ...current }));
  }, [questions, selectedArtisan, user]);

  // Sections + artisans back the capture form and the builder; they change only when an admin edits
  // the questionnaire, so they load once (and again on `onChanged`) rather than per list page/filter.
  async function loadMeta() {
    try {
      const [sectionList, artisanResult] = await Promise.all([
        apiFetch<QuestionnaireSection[]>("/questionnaire/sections"),
        listResource<Artisan>("/artisans", { pageSize: 100 })
      ]);
      setSections(sectionList);
      setArtisans(artisanResult.items);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load questionnaire");
    }
  }

  async function loadInterviews() {
    try {
      const result = await listResource<QuestionnaireInterview>("/questionnaire/interviews", {
        page,
        pageSize: 20,
        artisanId: funnel.artisanId || undefined,
        search: searchQuery || undefined
      });
      setData(result);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load questionnaire interviews");
    }
  }

  useEffect(() => {
    loadMeta();
  }, []);

  // Backend already returns interviews most-recent-first (createdAt desc); the funnel narrows by
  // artisan (the only list param the interviews endpoint supports) and the search box by text.
  useEffect(() => {
    loadInterviews();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, funnel.artisanId, searchQuery]);

  // Leaving the page mid-recording must release the microphone and the clock, not leak either.
  useEffect(() => {
    return () => {
      stopElapsedTimer();
      recorderRef.current?.stream.getTracks().forEach((track) => track.stop());
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

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
    // Clearing the box (the red X) immediately drops the filter, matching the funnel's live feel.
    if (value === "") {
      setSearchQuery("");
      setPage(1);
    }
  }

  function applySearch() {
    setSearchQuery(searchInput.trim());
    setPage(1);
  }

  async function startQuestionRecording(question: QuestionnaireQuestion) {
    try {
      if (recordingQuestionId) recorderRef.current?.stop();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      chunksRef.current = [];
      // Ask the browser what it can actually record: Safari/iOS produces audio/mp4, so a hardcoded
      // "audio/webm" name and type would lie about the bytes and break playback and transcription.
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
        const filename = `${safeFileName(question.sectionCode)}-${question.sortOrder}-${safeFileName(question.prompt)}.${extension}`;
        const file = new File([blob], filename, { type: mimeType });
        setQuestionAudioFiles((current) => ({
          ...current,
          [question.id]: [...(current[question.id] ?? []), file]
        }));
        stream.getTracks().forEach((track) => track.stop());
        // Tapping "Record this question" on ANOTHER question stops this recorder while the next one
        // is already running — only tear down the shared recording UI if this is still the live one.
        if (recorderRef.current !== recorder) return;
        streamRef.current = null;
        recorderRef.current = null;
        stopElapsedTimer();
        setQuestionStream(null);
        setQuestionElapsedMs(0);
        setRecordingQuestionId(null);
      };
      // The clock starts when the recorder really starts; only it needs a timer, because the bars
      // run on <Waveform>'s own requestAnimationFrame loop.
      recorder.onstart = () => {
        const startedAt = Date.now();
        setQuestionElapsedMs(0);
        elapsedTimerRef.current = window.setInterval(() => setQuestionElapsedMs(Date.now() - startedAt), 250);
      };
      recorder.start();
      setQuestionStream(stream);
      setRecordingQuestionId(question.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to start question recording");
    }
  }

  function stopQuestionRecording() {
    recorderRef.current?.stop();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    const artisanIds = selectedArtisanIds;
    const responses = Object.entries(answers)
      .filter(([, answerText]) => answerText.trim())
      .map(([questionId, answerText]) => ({ questionId, answerText: answerText.trim() }));
    try {
      const location = locationFromForm(form);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const saved = await apiFetch<QuestionnaireInterview>("/questionnaire/interviews", {
        method: "POST",
        body: JSON.stringify({
          title: textValue(form, "title") || `Interview ${new Date().toLocaleDateString()}`,
          // interviewDate is deliberately not sent: the server derives it from recordedAt.
          place: textValue(form, "place"),
          language: textValue(form, "language"),
          notes: textValue(form, "notes"),
          status: canPickStatus ? textValue(form, "status") || "APPROVED" : "PENDING",
          workshopId: workshop.workshopId || null,
          artisanIds,
          responses,
          recordedAt,
          recordedTimezone,
          location
        })
      });
      if (mediaFiles.length) {
        const { uploaded } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "questionnaire",
          linkedRecordId: saved.id,
          caption: `Interview audio for ${saved.title}`,
          location,
          recordedAt,
          recordedTimezone,
          transcribeAudio: true,
          onProgress: setInterviewProgress
        });
        setInterviewProgress(null);
        // Uploaded clips surface twice: as chips under this section and in the page-level tray.
        addCompleted(INTERVIEW_SECTION, INTERVIEW_SECTION_LABEL, uploaded);
      }
      const questionsById = new Map(questions.map((question) => [question.id, question]));
      for (const [questionId, files] of Object.entries(questionAudioFiles)) {
        const question = questionsById.get(questionId);
        if (!question || files.length === 0) continue;
        const { uploaded } = await uploadMediaBatch({
          files,
          linkedRecordType: "questionnaire",
          linkedRecordId: saved.id,
          caption: `Question audio: ${question.sectionCode}${question.sortOrder} - ${question.prompt}`,
          location,
          recordedAt,
          recordedTimezone,
          transcribeAudio: true,
          extraMetadata: { questionId, questionPrompt: question.prompt, sectionCode: question.sectionCode },
          onProgress: (progress) => setQuestionProgress((current) => ({ ...current, [questionId]: progress }))
        });
        addCompleted(questionSectionId(questionId), `Q${question.sectionCode}${question.sortOrder} audio`, uploaded);
      }
      // Cleared only once every question has been pushed, so the tray's page-level total counts the
      // whole run rather than shrinking back to whichever question is uploading right now.
      setQuestionProgress({});
      formElement.reset();
      setAnswers({});
      setMediaFiles([]);
      setQuestionAudioFiles({});
      setAdditionalArtisanIds([]);
      // Show the freshly saved (most recent) interview at the top of page one.
      if (page !== 1) setPage(1);
      else await loadInterviews();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save interview");
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
        "This permanently deletes the interview and every answer in it. This action cannot be undone.",
        "The recordings made during the interview, and their transcripts, are deleted with it."
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

  const additionalArtisanOptions = useMemo(
    () =>
      artisans
        .filter((artisan) => artisan.id !== selectedArtisanId)
        .map((artisan) => ({ value: artisan.id, label: `${artisan.name} - ${artisan.craft?.name ?? "No craft"} - ${artisan.place}` })),
    [artisans, selectedArtisanId]
  );

  return (
    <>
      <PageHeader
        title="Questionnaire"
        description="Interview artisans section by section, answer only the questions asked, and link the interview to one or more artisans."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      {/* 1) Completion matrix — top of the page, collapsed by default. */}
      <CompletionMatrixPanel canOverride={adminMode && isAdmin(user)} />

      <form onSubmit={submit} onKeyDown={handleFormEnter} className="panel mb-5 grid gap-4 p-4">
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <Field label="Interview title" required>
            <TextInput name="title" required />
          </Field>
          {/* No date field: the server derives interviewDate from recordedAt, which is when the
              interview was actually captured. Asking a researcher to confirm today's date was a
              field to tab past that could only ever be wrong. */}
          <Field label="Place">
            <TextInput name="place" />
          </Field>
          <Field label="Language">
            <TextInput name="language" placeholder="Bangla, Hindi, English..." />
          </Field>
          {/* The workshop leads every other dropdown: it is the context the interview belongs to. */}
          <WorkshopSelect state={workshop} saving={saving} />
          <Field label="Status">
            {canPickStatus ? (
              <Select name="status" defaultValue="APPROVED">
                {["DRAFT", "PENDING", "APPROVED", "REJECTED"].map((status) => (
                  <option key={status}>{status}</option>
                ))}
              </Select>
            ) : (
              <span
                className="inline-flex min-h-10 items-center gap-2 self-start rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm font-medium text-ink-muted"
                title="New interviews are submitted for review as Pending."
              >
                <Lock className="h-4 w-4" aria-hidden />
                Pending
              </span>
            )}
          </Field>
          <Field label="Primary artisan">
            <Select name="primaryArtisanId" value={selectedArtisanId} onChange={(event) => setSelectedArtisanId(event.target.value)}>
              <option value="">Select artisan</option>
              {artisans.map((artisan) => (
                <option key={artisan.id} value={artisan.id}>
                  {artisan.name} - {artisan.craft?.name ?? "No craft"} - {artisan.place}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Additional artisans">
            <MultiSelectDropdown
              values={additionalArtisanIds}
              onChange={setAdditionalArtisanIds}
              options={additionalArtisanOptions}
              placeholder="Add more artisans to this set"
              emptyLabel="No other artisans"
            />
          </Field>
        </div>
        {/* The theme defines exactly three amber tokens (100/500/800); 50/200/300/700 are not
            Tailwind classes here and silently render as nothing. */}
        {existingEntry ? (
          <section className="rounded-lg border border-amber-500 bg-amber-100 p-4">
            <h3 className="font-display font-bold text-lg text-amber-800">A shared entry already exists for this set of artisans</h3>
            <p className="mt-1 text-sm text-amber-800">
              There is one questionnaire entry per set of artisans. Saving below adds your answers and media to{" "}
              <span className="font-medium">{existingEntry.title}</span> — it will not create a duplicate. Questions
              already answered by someone else are shown here and can only be changed by that contributor or an admin.
            </p>
            {existingEntry.responses && existingEntry.responses.length > 0 ? (
              <div className="mt-3 grid gap-2">
                <div className="text-xs font-semibold uppercase tracking-wide text-amber-800">
                  Already recorded ({existingEntry.responses.length})
                </div>
                {existingEntry.responses.map((response) => (
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
            ) : (
              <p className="mt-2 text-xs text-amber-800">No questions answered yet — you can be the first to fill them in.</p>
            )}
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
              <div><span className="font-medium text-ink">Contact:</span> {selectedArtisan.phone || selectedArtisan.email || "-"}</div>
            </div>
          </section>
        ) : null}
        <div className="flex flex-wrap gap-2 rounded-lg border border-line-200 bg-field-100 p-3">
          <button type="button" className={answerMode === "audio" ? "field-button" : "field-button-secondary"} onClick={() => setAnswerMode("audio")}>
            Record audio answers
          </button>
          <button type="button" className={answerMode === "text" ? "field-button" : "field-button-secondary"} onClick={() => setAnswerMode("text")}>
            Type answers manually
          </button>
        </div>
        {answerMode === "audio" ? (
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={setMediaFiles}
            title="Interview audio"
            description="Record or upload interview audio. The backend will transcribe it when a transcription provider API key (ElevenLabs, Deepgram, or OpenAI) is configured; otherwise the audio is still saved."
            allowDocuments={false}
            allowedTypes={["AUDIO"]}
          />
        ) : null}
        <UploadProgress progress={interviewProgress} sectionId={INTERVIEW_SECTION} label={INTERVIEW_SECTION_LABEL} />
        <LocationFields />
        <div className="grid gap-3">
          {orderedGroups.map(([code, group], index) => (
            <details key={code} className="rounded-md border border-line-200 bg-field-100 p-3" open={index === 0}>
              <summary className="cursor-pointer font-display font-bold text-lg text-ink">
                {code}. {group.title}
              </summary>
              <div className="mt-3 grid gap-3">
                {group.items.map((question) => (
                  <Field key={question.id} label={`${question.sortOrder}. ${question.prompt}`}>
                    <div className="mb-2 flex flex-wrap items-center gap-2">
                      <button
                        type="button"
                        className="field-button-secondary"
                        onClick={() => (recordingQuestionId === question.id ? stopQuestionRecording() : startQuestionRecording(question))}
                      >
                        {recordingQuestionId === question.id ? <Square className="h-4 w-4" aria-hidden /> : <Mic className="h-4 w-4" aria-hidden />}
                        {recordingQuestionId === question.id ? "Stop question recording" : "Record this question"}
                      </button>
                      {questionAudioFiles[question.id]?.length ? (
                        <>
                          <span className="text-xs text-ink-muted">{questionAudioFiles[question.id].length} audio clip(s) ready</span>
                          <button
                            type="button"
                            className="text-xs font-semibold text-red-700"
                            onClick={() => setQuestionAudioFiles((current) => ({ ...current, [question.id]: [] }))}
                          >
                            Clear clips
                          </button>
                        </>
                      ) : null}
                    </div>
                    {recordingQuestionId === question.id ? (
                      <div className="mb-3">
                        <RecordingStrip stream={questionStream} elapsedMs={questionElapsedMs} />
                      </div>
                    ) : null}
                    {questionAudioPreviews[question.id]?.length ? (
                      <div className="mb-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                        {questionAudioPreviews[question.id].map((item, itemIndex) => (
                          <MediaPreviewTile
                            key={item.key}
                            item={item}
                            onOpen={() => setActivePreview(item)}
                            action={
                              <button
                                type="button"
                                className="text-xs font-semibold text-red-700"
                                onClick={() =>
                                  setQuestionAudioFiles((current) => ({
                                    ...current,
                                    [question.id]: (current[question.id] ?? []).filter((_, index) => index !== itemIndex)
                                  }))
                                }
                              >
                                Remove
                              </button>
                            }
                          />
                        ))}
                      </div>
                    ) : null}
                    {answerMode === "text" ? (
                      <TextArea value={answers[question.id] ?? ""} onChange={(event) => setAnswers((current) => ({ ...current, [question.id]: event.target.value }))} />
                    ) : null}
                    <UploadProgress
                      progress={questionProgress[question.id] ?? null}
                      sectionId={questionSectionId(question.id)}
                      label={`Q${question.sectionCode}${question.sortOrder} audio`}
                      className="mt-2"
                    />
                  </Field>
                ))}
              </div>
            </details>
          ))}
        </div>
        <MultiNoteField name="notes" label="Interview notes" />
        <div>
          <button className="field-button" disabled={saving}>
            <Plus className="h-4 w-4" aria-hidden />
            {saving ? "Saving..." : existingEntry ? "Add to shared entry" : "Save interview"}
          </button>
        </div>
      </form>

      <div className="mb-4 grid gap-3">
        <FunnelFilters value={funnel} onChange={(next) => { setFunnel(next); setPage(1); }} showArtisan />
        <SearchInput
          value={searchInput}
          onChange={handleSearchChange}
          onSubmit={applySearch}
          placeholder="Search interviews by title, place, or notes"
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
                  <tr key={interview.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">{interview.title}</div>
                      <div className="text-xs text-ink-500">{interview.place ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {interview.artisans?.map((link) => link.artisan.name).join(", ") || "-"}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      <details>
                        <summary className="cursor-pointer font-semibold text-field-700">{interview.responses?.length ?? 0} answers</summary>
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
                    {/* interviewDate is server-derived; recordedAt is what it is derived FROM, so it
                        is the right fallback for a row saved before the derivation existed. */}
                    <td className="px-4 py-3 text-ink-700">
                      {formatDate(interview.interviewDate ?? interview.recordedAt ?? interview.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      {adminMode ? (
                        <RowActions>
                          <button className={rowAction("danger")} onClick={() => remove(interview.id)}>
                            Delete
                          </button>
                        </RowActions>
                      ) : (
                        <span className="text-xs text-ink-500">Admin view only</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      {/* 2) Questionnaire builder — bottom of the page, collapsed by default. */}
      {canManageQuestionnaire(user) ? <QuestionnaireAdminEditor sections={sections} onChanged={loadMeta} /> : null}

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

/**
 * Collapsible panel with a rotating chevron. Collapsed by default; `onOpenChange` lets a panel lazy-load
 * its contents the first time it is opened.
 */
function Accordion({
  title,
  subtitle,
  defaultOpen = false,
  onOpenChange,
  headerRight,
  children
}: {
  title: string;
  subtitle?: string;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  headerRight?: React.ReactNode;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  function toggle() {
    const next = !open;
    setOpen(next);
    onOpenChange?.(next);
  }
  return (
    <section className="panel mb-5">
      <div className="flex items-center gap-3 p-4">
        <button
          type="button"
          onClick={toggle}
          aria-expanded={open}
          className="flex min-w-0 flex-1 items-start gap-3 text-left"
        >
          <ChevronDown className={`mt-0.5 h-5 w-5 shrink-0 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
          <span className="min-w-0">
            <span className="block font-display text-lg font-bold text-ink">{title}</span>
            {subtitle ? <span className="mt-1 block text-sm text-ink-muted">{subtitle}</span> : null}
          </span>
        </button>
        {headerRight}
      </div>
      {open ? <div className="border-t border-line-200 p-4">{children}</div> : null}
    </section>
  );
}

// --- "Check completion" cellular matrix (Android parity) ---

type CompletionMatrix = {
  sections: Array<{ id: string; code: string; title: string; sortOrder: number }>;
  artisans: Array<{ id: string; name: string }>;
  cells: Array<{ artisanId: string; sectionId: string; derived: boolean; status: string | null; setByName?: string | null }>;
};

/**
 * Artisans down the rows, questionnaire sections across the columns. A cell is green when the
 * section is covered — either derived from recorded answers/audio or forced by an admin override
 * (overrides carry an amber ring). In admin view, admins click a cell to cycle the override:
 * complete -> not complete -> clear (back to the derived state).
 */
function CompletionMatrixPanel({ canOverride }: { canOverride: boolean }) {
  const [matrix, setMatrix] = useState<CompletionMatrix | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyCell, setBusyCell] = useState<string | null>(null);
  const requested = useRef(false);

  async function refresh() {
    try {
      setMatrix(await apiFetch<CompletionMatrix>("/questionnaire/completion"));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load the completion matrix");
    }
  }

  // Lazy-load the matrix the first time the accordion is opened.
  function handleOpenChange(open: boolean) {
    if (!open || requested.current) return;
    requested.current = true;
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
    // No override yet -> force complete; forced complete -> force not complete; any other override -> clear.
    const next = !cell?.status ? "COMPLETED" : cell.status === "COMPLETED" ? "NEEDS_REDO" : null;
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
      subtitle={`Which questionnaire sections are covered for each artisan, derived from recorded answers and audio.${
        canOverride ? " Click a cell to cycle an admin override: complete, not complete, clear." : ""
      }`}
      onOpenChange={handleOpenChange}
    >
      {error ? <div className="mb-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {loading ? <div className="text-sm text-ink-muted">Loading completion matrix...</div> : null}
      {matrix && matrix.artisans.length === 0 && !loading ? (
        <p className="text-sm text-ink-muted">No artisans yet — the matrix appears once artisans are recorded.</p>
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
                      const state = overridden
                        ? `override ${cell?.status === "COMPLETED" ? "complete" : "not complete"}${cell?.setByName ? ` by ${cell.setByName}` : ""}`
                        : complete
                          ? "complete"
                          : "not complete";
                      const title = `${artisan.name} — ${section.code}: ${state}`;
                      const square = `h-6 w-6 rounded ${complete ? "bg-success-600" : "bg-line-200"}${overridden ? " ring-2 ring-amber-500" : ""}`;
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
              <span className="h-3.5 w-3.5 rounded bg-line-200 ring-2 ring-amber-500" aria-hidden /> Admin override
            </span>
          </div>
        </>
      ) : null}
    </Accordion>
  );
}

// --- Questionnaire builder (admin) — sections + drag-and-drop question tiles ---

type DragQuestion = { questionId: string; sectionId: string };
type DropIndicator = { sectionId: string; index: number };

/** Renumber a section's questions after an optimistic move and stamp them with the section identity. */
function reindexQuestions(list: QuestionnaireQuestion[], section: { id: string; code: string; title: string }): QuestionnaireQuestion[] {
  return list.map((question, index) => ({
    ...question,
    sortOrder: index + 1,
    sectionId: section.id,
    sectionCode: section.code,
    sectionTitle: section.title
  }));
}

/**
 * Pure optimistic move: takes the current sections and returns a new array with `questionId` moved
 * from its section to `gapIndex` within `toSectionId` (a drop-gap index, 0..length). Returns the
 * SAME reference when the move is a no-op so callers can skip the network round-trip.
 */
function moveQuestionInSections(
  sections: QuestionnaireSection[],
  questionId: string,
  fromSectionId: string,
  toSectionId: string,
  gapIndex: number
): QuestionnaireSection[] {
  const clone = sections.map((section) => ({ ...section, questions: [...section.questions] }));
  const from = clone.find((section) => section.id === fromSectionId);
  const to = clone.find((section) => section.id === toSectionId);
  if (!from || !to) return sections;
  const srcIndex = from.questions.findIndex((question) => question.id === questionId);
  if (srcIndex < 0) return sections;

  // A drop-gap counts the dragged tile; removing it first shifts every later gap left by one.
  let insertAt = from === to && srcIndex < gapIndex ? gapIndex - 1 : gapIndex;
  const [moved] = from.questions.splice(srcIndex, 1);
  insertAt = Math.max(0, Math.min(insertAt, to.questions.length));
  if (from === to && insertAt === srcIndex) return sections; // dropped back onto itself
  to.questions.splice(insertAt, 0, moved);
  from.questions = reindexQuestions(from.questions, from);
  if (to !== from) to.questions = reindexQuestions(to.questions, to);
  return clone;
}

function QuestionnaireAdminEditor({ sections, onChanged }: { sections: QuestionnaireSection[]; onChanged: () => Promise<void> }) {
  const confirm = useConfirm();
  const [localSections, setLocalSections] = useState<QuestionnaireSection[]>(sections);
  const [newCode, setNewCode] = useState("");
  const [newTitle, setNewTitle] = useState("");
  const [dragSectionId, setDragSectionId] = useState<string | null>(null);
  const [dragQuestion, setDragQuestion] = useState<DragQuestion | null>(null);
  const [dropIndicator, setDropIndicator] = useState<DropIndicator | null>(null);
  const [busy, setBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  // Server state is authoritative: whenever the parent reloads sections, replace the local copy
  // (this also lands the canonical result after an optimistic drag persists).
  useEffect(() => {
    setLocalSections(sections);
  }, [sections]);

  async function addSection(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setMessage(null);
    try {
      await apiFetch("/questionnaire/sections", {
        method: "POST",
        body: JSON.stringify({ code: newCode.trim(), title: newTitle.trim() })
      });
      setNewCode("");
      setNewTitle("");
      await onChanged();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to add section");
    } finally {
      setSaving(false);
    }
  }

  async function updateSection(section: QuestionnaireSection, form: FormData) {
    await apiFetch(`/questionnaire/sections/${section.id}`, {
      method: "PATCH",
      body: JSON.stringify({
        code: String(form.get("code") ?? "").trim(),
        title: String(form.get("title") ?? "").trim(),
        isActive: form.get("isActive") === "on"
      })
    });
    await onChanged();
  }

  async function removeSection(section: QuestionnaireSection) {
    // Amber, not red: this is a deactivation. Nothing already collected is lost, and saying so is the
    // difference between a manager pruning the form and a manager afraid to touch it.
    const ok = await confirm({
      title: `Remove section ${section.code}?`,
      body: (
        <>
          <span className="font-medium text-ink-900">{section.title}</span> and its questions stop appearing in new
          interviews.
        </>
      ),
      note: "Questions are deactivated, not erased: answers already recorded against them stay on the interviews that hold them.",
      tone: "warning",
      confirmLabel: "Remove section"
    });
    if (!ok) return;
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/sections/${section.id}`, { method: "DELETE" });
      await onChanged();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to remove section");
    }
  }

  // Optimistically reorder sections, persist, and roll back if the server rejects it.
  async function performSectionReorder(fromId: string, toId: string) {
    if (!fromId || fromId === toId) return;
    const fromIndex = localSections.findIndex((section) => section.id === fromId);
    const toIndex = localSections.findIndex((section) => section.id === toId);
    if (fromIndex < 0 || toIndex < 0) return;
    const next = [...localSections];
    const [moved] = next.splice(fromIndex, 1);
    next.splice(toIndex, 0, moved);
    const reindexed = next.map((section, index) => ({ ...section, sortOrder: index + 1 }));
    const snapshot = localSections;
    setLocalSections(reindexed);
    setBusy(true);
    setMessage(null);
    try {
      await apiFetch("/questionnaire/sections/reorder", {
        method: "POST",
        body: JSON.stringify({ sectionIds: reindexed.map((section) => section.id) })
      });
      await onChanged();
    } catch (err) {
      setLocalSections(snapshot);
      setMessage(err instanceof Error ? err.message : "Unable to reorder sections");
    } finally {
      setBusy(false);
    }
  }

  async function addQuestion(section: QuestionnaireSection, form: FormData) {
    const prompt = String(form.get("prompt") ?? "").trim();
    if (!prompt) return;
    await apiFetch("/questionnaire/questions", {
      method: "POST",
      body: JSON.stringify({ sectionId: section.id, prompt })
    });
    await onChanged();
  }

  async function saveQuestion(question: QuestionnaireQuestion, prompt: string, isActive: boolean): Promise<boolean> {
    setBusy(true);
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/questions/${question.id}`, {
        method: "PATCH",
        body: JSON.stringify({ prompt: prompt.trim(), isActive })
      });
      await onChanged();
      return true;
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to update question");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function removeQuestion(question: QuestionnaireQuestion) {
    const ok = await confirm({
      title: "Remove this question?",
      body: (
        <>
          <span className="font-medium text-ink-900">{question.prompt}</span> stops appearing in new interviews.
        </>
      ),
      note: "Answers already recorded against it stay linked to the interviews that hold them.",
      tone: "warning",
      confirmLabel: "Remove question"
    });
    if (!ok) return;
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/questions/${question.id}`, { method: "DELETE" });
      await onChanged();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to remove question");
    }
  }

  /**
   * Optimistically apply a question move, then persist. Within a section this is a single reorder;
   * across sections we first re-parent the question (updateQuestion{sectionId}) and then reorder the
   * TARGET section — exactly the two existing API calls. Rolls back on any error.
   */
  async function persistMove(next: QuestionnaireSection[], reorderSectionId: string, reparent?: { questionId: string; toSectionId: string }) {
    const snapshot = localSections;
    setLocalSections(next);
    setBusy(true);
    setMessage(null);
    try {
      if (reparent) {
        await apiFetch(`/questionnaire/questions/${reparent.questionId}`, {
          method: "PATCH",
          body: JSON.stringify({ sectionId: reparent.toSectionId })
        });
      }
      const target = next.find((section) => section.id === reorderSectionId);
      if (target) {
        await apiFetch("/questionnaire/questions/reorder", {
          method: "POST",
          body: JSON.stringify({ sectionId: reorderSectionId, questionIds: target.questions.map((question) => question.id) })
        });
      }
      await onChanged();
    } catch (err) {
      setLocalSections(snapshot);
      setMessage(err instanceof Error ? err.message : "Unable to move question");
    } finally {
      setBusy(false);
    }
  }

  function performQuestionMove(drag: DragQuestion, toSectionId: string, gapIndex: number) {
    const next = moveQuestionInSections(localSections, drag.questionId, drag.sectionId, toSectionId, gapIndex);
    setDragQuestion(null);
    setDropIndicator(null);
    if (next === localSections) return; // no-op
    persistMove(next, toSectionId, drag.sectionId !== toSectionId ? { questionId: drag.questionId, toSectionId } : undefined);
  }

  // Keyboard fallback: swap a question with its neighbour inside the same section.
  function keyboardMove(section: QuestionnaireSection, question: QuestionnaireQuestion, direction: -1 | 1) {
    const current = localSections.find((item) => item.id === section.id);
    if (!current) return;
    const index = current.questions.findIndex((item) => item.id === question.id);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= current.questions.length) return;
    const nextQuestions = [...current.questions];
    [nextQuestions[index], nextQuestions[target]] = [nextQuestions[target], nextQuestions[index]];
    const next = localSections.map((item) =>
      item.id === section.id ? { ...item, questions: reindexQuestions(nextQuestions, item) } : item
    );
    persistMove(next, section.id);
  }

  function moveQuestionToSection(question: QuestionnaireQuestion, fromSectionId: string, toSectionId: string) {
    if (!toSectionId || toSectionId === fromSectionId) return;
    const target = localSections.find((section) => section.id === toSectionId);
    performQuestionMove({ questionId: question.id, sectionId: fromSectionId }, toSectionId, target ? target.questions.length : 0);
  }

  const dragActive = Boolean(dragQuestion);

  return (
    <Accordion
      title="Questionnaire Builder"
      subtitle="Master admin controls for sections, ordering, question text, moves and removals."
      headerRight={message ? <span className="text-sm text-red-700">{message}</span> : null}
    >
      <div className="grid gap-4">
        <form onSubmit={addSection} className="grid gap-3 rounded-md border border-line-200 bg-field-100 p-3 md:grid-cols-[160px_1fr_auto]">
          <Field label="Section code">
            <TextInput value={newCode} onChange={(event) => setNewCode(event.target.value)} placeholder="A, RESP, FIELD..." required />
          </Field>
          <Field label="Section title">
            <TextInput value={newTitle} onChange={(event) => setNewTitle(event.target.value)} placeholder="Section title" required />
          </Field>
          <div className="flex items-end">
            <button className="field-button" disabled={saving}>
              <Plus className="h-4 w-4" aria-hidden />
              Add section
            </button>
          </div>
        </form>

        <p className="text-xs text-ink-muted">
          Drag a question by its grip to reorder it within a section or drop it into another section. Keyboard users can use the
          up/down buttons on each tile, or the &ldquo;Move to&rdquo; menu to change section.
        </p>

        <div className="grid gap-4">
          {localSections.map((section) => (
            <div
              key={section.id}
              className={`rounded-md border bg-card p-3 transition ${dragSectionId === section.id ? "border-field-600 ring-2 ring-field-200" : "border-line-200"}`}
              onDragOver={(event) => {
                if (dragSectionId) event.preventDefault();
              }}
              onDrop={(event) => {
                if (!dragSectionId) return;
                event.preventDefault();
                performSectionReorder(dragSectionId, section.id);
                setDragSectionId(null);
              }}
            >
              <div className="flex items-center gap-2 font-display font-bold text-xl text-ink">
                <button
                  type="button"
                  className="grid h-9 w-9 cursor-grab place-items-center rounded-md border border-line-200 bg-field-50 text-ink-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-field-600 active:cursor-grabbing disabled:opacity-60"
                  draggable={!busy}
                  disabled={busy}
                  aria-label={`Drag section ${section.code}`}
                  onDragStart={(event) => {
                    setDragSectionId(section.id);
                    event.dataTransfer.effectAllowed = "move";
                    event.dataTransfer.setData("text/plain", section.id);
                  }}
                  onDragEnd={() => setDragSectionId(null)}
                >
                  <GripVertical className="h-4 w-4" aria-hidden />
                </button>
                <span>{section.sortOrder}. {section.code} - {section.title}</span>
              </div>

              <form
                className="mt-3 grid gap-3 md:grid-cols-[120px_1fr_auto_auto]"
                onSubmit={async (event) => {
                  event.preventDefault();
                  const formData = new FormData(event.currentTarget);
                  setMessage(null);
                  try {
                    await updateSection(section, formData);
                  } catch (err) {
                    setMessage(err instanceof Error ? err.message : "Unable to update section");
                  }
                }}
              >
                <Field label="Code">
                  <TextInput name="code" defaultValue={section.code} required />
                </Field>
                <Field label="Title">
                  <TextInput name="title" defaultValue={section.title} required />
                </Field>
                <label className="flex items-end gap-2 pb-2 text-sm text-ink-muted">
                  <input name="isActive" type="checkbox" defaultChecked={section.isActive} />
                  Active
                </label>
                <div className="flex flex-wrap items-end gap-2">
                  <button className="field-button-secondary" type="submit">
                    <Save className="h-4 w-4" aria-hidden />
                    Save
                  </button>
                  <button type="button" className="text-sm font-semibold text-red-700" onClick={() => removeSection(section)}>
                    <Trash2 className="inline h-4 w-4" aria-hidden /> Remove
                  </button>
                </div>
              </form>

              <div
                className="mt-4 grid gap-1"
                onDragOver={(event) => {
                  if (dragActive && section.questions.length === 0) {
                    event.preventDefault();
                    setDropIndicator({ sectionId: section.id, index: 0 });
                  }
                }}
                onDrop={(event) => {
                  if (dragQuestion && section.questions.length === 0) {
                    event.preventDefault();
                    performQuestionMove(dragQuestion, section.id, 0);
                  }
                }}
              >
                {section.questions.map((question, index) => (
                  <div key={question.id}>
                    <DropLine active={dragActive && dropIndicator?.sectionId === section.id && dropIndicator.index === index} />
                    <QuestionTile
                      question={question}
                      index={index}
                      total={section.questions.length}
                      sections={localSections}
                      currentSectionId={section.id}
                      dragActive={dragActive}
                      dragging={dragQuestion?.questionId === question.id}
                      disabled={busy}
                      onDragStart={() => setDragQuestion({ questionId: question.id, sectionId: section.id })}
                      onDragEnd={() => {
                        setDragQuestion(null);
                        setDropIndicator(null);
                      }}
                      onHover={(after) => setDropIndicator({ sectionId: section.id, index: index + (after ? 1 : 0) })}
                      onDropTile={(after) => performQuestionMove(dragQuestion as DragQuestion, section.id, index + (after ? 1 : 0))}
                      onMoveUp={() => keyboardMove(section, question, -1)}
                      onMoveDown={() => keyboardMove(section, question, 1)}
                      onMoveToSection={(toSectionId) => moveQuestionToSection(question, section.id, toSectionId)}
                      onSave={(prompt, isActive) => saveQuestion(question, prompt, isActive)}
                      onRemove={() => removeQuestion(question)}
                    />
                  </div>
                ))}
                <DropLine active={dragActive && dropIndicator?.sectionId === section.id && dropIndicator.index === section.questions.length} />
                {section.questions.length === 0 ? (
                  <div
                    className={`rounded-md border border-dashed p-3 text-center text-xs font-semibold ${
                      dragActive ? "border-field-600 bg-field-100 text-field-700" : "border-[#d7c7bc] text-ink-muted"
                    }`}
                  >
                    {dragActive ? `Drop a question here to move it into ${section.code}` : "No questions yet — add one below."}
                  </div>
                ) : null}

                <form
                  className="mt-2 grid gap-3 rounded-md border border-dashed border-[#d7c7bc] p-3 md:grid-cols-[1fr_auto]"
                  onSubmit={async (event) => {
                    event.preventDefault();
                    // Capture before the await: React nulls event.currentTarget afterwards.
                    const formElement = event.currentTarget;
                    setMessage(null);
                    try {
                      await addQuestion(section, new FormData(formElement));
                      formElement.reset();
                    } catch (err) {
                      setMessage(err instanceof Error ? err.message : "Unable to add question");
                    }
                  }}
                >
                  <Field label={`New question in ${section.code}`}>
                    <TextArea name="prompt" placeholder="Write the question prompt..." required />
                  </Field>
                  <div className="flex items-end">
                    <button className="field-button-secondary">
                      <Plus className="h-4 w-4" aria-hidden />
                      Add question
                    </button>
                  </div>
                </form>
              </div>
            </div>
          ))}
        </div>
      </div>
    </Accordion>
  );
}

/** Purple insertion line shown between question tiles while dragging. */
function DropLine({ active }: { active: boolean }) {
  return <div className={`h-0.5 rounded-full transition-colors ${active ? "bg-purple-700" : "bg-transparent"}`} aria-hidden />;
}

/**
 * A single draggable question tile: grip handle drags it, up/down buttons are the keyboard fallback for
 * within-section reordering, the "Move to" menu changes section, and Edit toggles inline prompt/active
 * editing.
 */
function QuestionTile({
  question,
  index,
  total,
  sections,
  currentSectionId,
  dragActive,
  dragging,
  disabled,
  onDragStart,
  onDragEnd,
  onHover,
  onDropTile,
  onMoveUp,
  onMoveDown,
  onMoveToSection,
  onSave,
  onRemove
}: {
  question: QuestionnaireQuestion;
  index: number;
  total: number;
  sections: QuestionnaireSection[];
  currentSectionId: string;
  dragActive: boolean;
  dragging: boolean;
  disabled: boolean;
  onDragStart: () => void;
  onDragEnd: () => void;
  onHover: (after: boolean) => void;
  onDropTile: (after: boolean) => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onMoveToSection: (toSectionId: string) => void;
  onSave: (prompt: string, isActive: boolean) => Promise<boolean>;
  onRemove: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [prompt, setPrompt] = useState(question.prompt);
  const [isActive, setIsActive] = useState(question.isActive);
  const [savingTile, setSavingTile] = useState(false);

  useEffect(() => {
    setPrompt(question.prompt);
    setIsActive(question.isActive);
  }, [question.id, question.prompt, question.isActive]);

  function pointerAfter(event: React.DragEvent) {
    const rect = event.currentTarget.getBoundingClientRect();
    return event.clientY > rect.top + rect.height / 2;
  }

  const otherSections = sections.filter((section) => section.id !== currentSectionId);

  async function handleSave() {
    setSavingTile(true);
    const ok = await onSave(prompt, isActive);
    setSavingTile(false);
    if (ok) setEditing(false);
  }

  return (
    <div
      className={`flex items-start gap-2 rounded-md border border-line-200 bg-field-50 p-2.5 transition ${dragging ? "opacity-50" : ""}`}
      onDragOver={(event) => {
        if (!dragActive) return;
        event.preventDefault();
        onHover(pointerAfter(event));
      }}
      onDrop={(event) => {
        if (!dragActive) return;
        event.preventDefault();
        onDropTile(pointerAfter(event));
      }}
    >
      <button
        type="button"
        className="mt-0.5 grid h-8 w-8 shrink-0 cursor-grab place-items-center rounded-md border border-line-200 bg-card text-ink-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-field-600 active:cursor-grabbing disabled:opacity-60"
        draggable={!disabled}
        disabled={disabled}
        aria-label={`Drag question ${question.sortOrder}`}
        onDragStart={(event) => {
          onDragStart();
          event.dataTransfer.effectAllowed = "move";
          event.dataTransfer.setData("text/plain", question.id);
        }}
        onDragEnd={onDragEnd}
      >
        <GripVertical className="h-4 w-4" aria-hidden />
      </button>

      <div className="min-w-0 flex-1">
        {editing ? (
          <TextArea value={prompt} onChange={(event) => setPrompt(event.target.value)} rows={2} />
        ) : (
          <p className="text-sm text-ink-900">
            <span className="mr-1 font-semibold text-ink-500">{question.sortOrder}.</span>
            {question.prompt}
            {!question.isActive ? <span className="ml-2 rounded bg-line-200 px-1.5 py-0.5 text-xs text-ink-500">inactive</span> : null}
          </p>
        )}

        <div className="mt-2 flex flex-wrap items-center gap-2">
          {editing ? (
            <>
              <label className="flex items-center gap-2 text-sm text-ink-muted">
                <input type="checkbox" checked={isActive} onChange={(event) => setIsActive(event.target.checked)} />
                Active
              </label>
              <button type="button" className="field-button-secondary" onClick={handleSave} disabled={savingTile || disabled}>
                <Save className="h-4 w-4" aria-hidden />
                {savingTile ? "Saving..." : "Save"}
              </button>
              <button
                type="button"
                className="text-sm font-semibold text-ink-muted"
                onClick={() => {
                  setPrompt(question.prompt);
                  setIsActive(question.isActive);
                  setEditing(false);
                }}
              >
                Cancel
              </button>
            </>
          ) : (
            <>
              <button type="button" className="field-button-secondary" onClick={() => setEditing(true)} disabled={disabled}>
                <Pencil className="h-4 w-4" aria-hidden />
                Edit
              </button>
              {otherSections.length ? (
                <label className="flex items-center gap-1.5 text-xs text-ink-muted">
                  <span>Move to</span>
                  <span className="w-32">
                    <Select value="" onChange={(event) => onMoveToSection(event.target.value)} aria-label={`Move question ${question.sortOrder} to another section`}>
                      <option value="">Section...</option>
                      {otherSections.map((section) => (
                        <option key={section.id} value={section.id}>
                          {section.code}
                        </option>
                      ))}
                    </Select>
                  </span>
                </label>
              ) : null}
              <button type="button" className="text-sm font-semibold text-red-700" onClick={onRemove} disabled={disabled}>
                Remove
              </button>
            </>
          )}
        </div>
      </div>

      <div className="flex shrink-0 flex-col gap-1">
        <button
          type="button"
          className="grid h-7 w-7 place-items-center rounded-md border border-line-200 bg-card text-ink-muted transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-40"
          aria-label={`Move question ${question.sortOrder} up`}
          disabled={disabled || index === 0}
          onClick={onMoveUp}
        >
          <ArrowUp className="h-4 w-4" aria-hidden />
        </button>
        <button
          type="button"
          className="grid h-7 w-7 place-items-center rounded-md border border-line-200 bg-card text-ink-muted transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-40"
          aria-label={`Move question ${question.sortOrder} down`}
          disabled={disabled || index === total - 1}
          onClick={onMoveDown}
        >
          <ArrowDown className="h-4 w-4" aria-hidden />
        </button>
      </div>
    </div>
  );
}
