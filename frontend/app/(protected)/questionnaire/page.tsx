"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ArrowDown, ArrowUp, ClipboardList, Mic, Plus, Save, Square, Trash2 } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { RecordedAtField } from "@/components/forms/RecordedAtField";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { locationFromForm, parseJsonMetadata, recordedAtFromForm, recordedTimezoneFromForm, textValue } from "@/lib/forms";
import { uploadMediaBatch } from "@/lib/media";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";
import type { Artisan, PageResult, QuestionnaireInterview, QuestionnaireQuestion, QuestionnaireSection } from "@/lib/types";
import { CalendarLume } from "@/components/ui/calendar-lume";

export default function QuestionnairePage() {
  const { user } = useAuth();
  const searchParams = useSearchParams();
  const [sections, setSections] = useState<QuestionnaireSection[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [data, setData] = useState<PageResult<QuestionnaireInterview> | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [questionAudioFiles, setQuestionAudioFiles] = useState<Record<string, File[]>>({});
  const [selectedArtisanId, setSelectedArtisanId] = useState(searchParams.get("artisanId") ?? "");
  const [answerMode, setAnswerMode] = useState<"audio" | "text">("audio");
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [recordingQuestionId, setRecordingQuestionId] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);

  const questions = useMemo(() => sections.flatMap((section) => section.questions), [sections]);

  const orderedGroups = useMemo(() => {
    return sections.map((section) => [section.code, { section, title: section.title, items: section.questions }] as const);
  }, [sections]);

  const selectedArtisan = useMemo(() => artisans.find((artisan) => artisan.id === selectedArtisanId), [artisans, selectedArtisanId]);

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

  async function load() {
    try {
      const [sectionList, artisanResult, interviewResult] = await Promise.all([
        apiFetch<QuestionnaireSection[]>("/questionnaire/sections"),
        listResource<Artisan>("/artisans", { pageSize: 100 }),
        listResource<QuestionnaireInterview>("/questionnaire/interviews", { page, pageSize: 20 })
      ]);
      setSections(sectionList);
      setArtisans(artisanResult.items);
      setData(interviewResult);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load questionnaire");
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  useEffect(() => {
    return () => {
      recorderRef.current?.stream.getTracks().forEach((track) => track.stop());
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  async function startQuestionRecording(question: QuestionnaireQuestion) {
    try {
      if (recordingQuestionId) recorderRef.current?.stop();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      chunksRef.current = [];
      const recorder = new MediaRecorder(stream);
      recorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.onstop = () => {
        const blob = new Blob(chunksRef.current, { type: "audio/webm" });
        const filename = `${safeFileName(question.sectionCode)}-${question.sortOrder}-${safeFileName(question.prompt)}.webm`;
        const file = new File([blob], filename, { type: "audio/webm" });
        setQuestionAudioFiles((current) => ({
          ...current,
          [question.id]: [...(current[question.id] ?? []), file]
        }));
        stream.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        recorderRef.current = null;
        setRecordingQuestionId(null);
      };
      recorder.start();
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
    setSaving(true);
    setError(null);
    const form = new FormData(event.currentTarget);
    const artisanIds = Array.from(new Set([selectedArtisanId, ...form.getAll("artisanIds").map(String)].filter(Boolean)));
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
          interviewDate: textValue(form, "interviewDate") ? new Date(String(form.get("interviewDate"))).toISOString() : undefined,
          place: textValue(form, "place"),
          language: textValue(form, "language"),
          notes: textValue(form, "notes"),
          status: textValue(form, "status") || "PENDING",
          artisanIds,
          responses,
          recordedAt,
          recordedTimezone,
          location,
          extraMetadata: parseJsonMetadata(form.get("extraMetadata"))
        })
      });
      if (mediaFiles.length) {
        await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "questionnaire",
          linkedRecordId: saved.id,
          caption: `Interview audio for ${saved.title}`,
          location,
          recordedAt,
          recordedTimezone,
          transcribeAudio: true
        });
      }
      const questionsById = new Map(questions.map((question) => [question.id, question]));
      for (const [questionId, files] of Object.entries(questionAudioFiles)) {
        const question = questionsById.get(questionId);
        if (!question || files.length === 0) continue;
        await uploadMediaBatch({
          files,
          linkedRecordType: "questionnaire",
          linkedRecordId: saved.id,
          caption: `Question audio: ${question.sectionCode}${question.sortOrder} - ${question.prompt}`,
          location,
          recordedAt,
          recordedTimezone,
          transcribeAudio: true,
          extraMetadata: { questionId, questionPrompt: question.prompt, sectionCode: question.sectionCode }
        });
      }
      event.currentTarget.reset();
      setAnswers({});
      setMediaFiles([]);
      setQuestionAudioFiles({});
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save interview");
    } finally {
      setSaving(false);
    }
  }

  async function remove(id: string) {
    if (!window.confirm("Delete this questionnaire interview?")) return;
    await apiFetch(`/questionnaire/interviews/${id}`, { method: "DELETE" });
    load();
  }

  return (
    <>
      <PageHeader
        title="Questionnaire"
        description="Interview artisans section by section, answer only the questions asked, and link the interview to one or more artisans."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {isMasterAdmin(user) ? <QuestionnaireAdminEditor sections={sections} onChanged={load} /> : null}
      <form onSubmit={submit} className="panel mb-5 grid gap-4 p-4">
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          <Field label="Interview title" required>
            <TextInput name="title" required />
          </Field>
          <Field label="Date">
            <CalendarLume name="interviewDate" value={new Date()} />
          </Field>
          <Field label="Place">
            <TextInput name="place" />
          </Field>
          <Field label="Language">
            <TextInput name="language" placeholder="Bangla, Hindi, English..." />
          </Field>
          <Field label="Status">
            <Select name="status" defaultValue="PENDING">
              {["DRAFT", "PENDING", "APPROVED", "REJECTED"].map((status) => (
                <option key={status}>{status}</option>
              ))}
            </Select>
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
            <select name="artisanIds" className="field-input min-h-32" multiple>
              {artisans.map((artisan) => (
                <option key={artisan.id} value={artisan.id}>
                  {artisan.name} - {artisan.craft?.name ?? "No craft"} - {artisan.place}
                </option>
              ))}
            </select>
          </Field>
        </div>
        {selectedArtisan ? (
          <section className="rounded-lg border border-[#e6dfd8] bg-field-100 p-4">
            <h3 className="font-serif text-lg text-ink">RESP. Respondent Information</h3>
            <div className="mt-2 grid gap-2 text-sm text-ink-muted sm:grid-cols-2 lg:grid-cols-3">
              <div><span className="font-medium text-ink">Name:</span> {selectedArtisan.name}</div>
              <div><span className="font-medium text-ink">Craft:</span> {selectedArtisan.craft?.name ?? "-"}</div>
              <div><span className="font-medium text-ink">Place:</span> {selectedArtisan.place}</div>
              <div><span className="font-medium text-ink">Gender:</span> {selectedArtisan.gender ?? "-"}</div>
              <div><span className="font-medium text-ink">Contact:</span> {selectedArtisan.phone || selectedArtisan.email || "-"}</div>
            </div>
          </section>
        ) : null}
        <div className="flex flex-wrap gap-2 rounded-lg border border-[#e6dfd8] bg-field-100 p-3">
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
            description="Record or upload interview audio. The backend will transcribe it when OPENAI_API_KEY is configured; otherwise the audio is still saved."
            allowDocuments={false}
            defaultAudio
            allowedTypes={["AUDIO"]}
          />
        ) : null}
        <LocationFields />
        <RecordedAtField />
        <div className="grid gap-3">
          {orderedGroups.map(([code, group], index) => (
            <details key={code} className="rounded-md border border-[#e6dfd8] bg-field-100 p-3" open={index === 0}>
              <summary className="cursor-pointer font-serif text-lg text-ink">
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
                    {answerMode === "text" ? (
                      <TextArea value={answers[question.id] ?? ""} onChange={(event) => setAnswers((current) => ({ ...current, [question.id]: event.target.value }))} />
                    ) : null}
                  </Field>
                ))}
              </div>
            </details>
          ))}
        </div>
        <Field label="Interview notes">
          <TextArea name="notes" />
        </Field>
        <Field label="Extra metadata JSON">
          <TextArea name="extraMetadata" placeholder='{"interpreter":"...","consent":"verbal"}' />
        </Field>
        <div>
          <button className="field-button" disabled={saving}>
            <Plus className="h-4 w-4" aria-hidden />
            {saving ? "Saving..." : "Save interview"}
          </button>
        </div>
      </form>
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-neutral-600">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No questionnaire interviews yet" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[980px] text-left text-sm">
              <thead className="bg-neutral-50 text-xs uppercase text-neutral-500">
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
              <tbody className="divide-y divide-neutral-200">
                {data.items.map((interview) => (
                  <tr key={interview.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-neutral-900">{interview.title}</div>
                      <div className="text-xs text-neutral-500">{interview.place ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-neutral-600">
                      {interview.artisans?.map((link) => link.artisan.name).join(", ") || "-"}
                    </td>
                    <td className="px-4 py-3 text-neutral-600">
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
                    <td className="px-4 py-3 text-neutral-600">{interview.createdBy?.email ?? "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={interview.status} />
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatDate(interview.interviewDate ?? interview.createdAt)}</td>
                    <td className="px-4 py-3 text-right">
                      {isAdmin(user) ? (
                        <button className="text-sm font-semibold text-red-700" onClick={() => remove(interview.id)}>
                          Delete
                        </button>
                      ) : (
                        <span className="text-xs text-neutral-500">Admin only</span>
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

function moveItem<T>(items: T[], index: number, direction: -1 | 1) {
  const nextIndex = index + direction;
  if (nextIndex < 0 || nextIndex >= items.length) return items;
  const copy = [...items];
  [copy[index], copy[nextIndex]] = [copy[nextIndex], copy[index]];
  return copy;
}

function QuestionnaireAdminEditor({ sections, onChanged }: { sections: QuestionnaireSection[]; onChanged: () => Promise<void> }) {
  const [newCode, setNewCode] = useState("");
  const [newTitle, setNewTitle] = useState("");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

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
    if (!window.confirm(`Remove section ${section.code}? Questions are deactivated, not erased from historical interviews.`)) return;
    await apiFetch(`/questionnaire/sections/${section.id}`, { method: "DELETE" });
    await onChanged();
  }

  async function reorderSections(nextSections: QuestionnaireSection[]) {
    await apiFetch("/questionnaire/sections/reorder", {
      method: "POST",
      body: JSON.stringify({ sectionIds: nextSections.map((section) => section.id) })
    });
    await onChanged();
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

  async function updateQuestion(question: QuestionnaireQuestion, form: FormData) {
    await apiFetch(`/questionnaire/questions/${question.id}`, {
      method: "PATCH",
      body: JSON.stringify({
        sectionId: String(form.get("sectionId") ?? question.sectionId ?? ""),
        prompt: String(form.get("prompt") ?? "").trim(),
        isActive: form.get("isActive") === "on"
      })
    });
    await onChanged();
  }

  async function removeQuestion(question: QuestionnaireQuestion) {
    if (!window.confirm("Remove this question from future interviews? Historical responses remain linked.")) return;
    await apiFetch(`/questionnaire/questions/${question.id}`, { method: "DELETE" });
    await onChanged();
  }

  async function reorderQuestions(section: QuestionnaireSection, questionIndex: number, direction: -1 | 1) {
    const nextQuestions = moveItem(section.questions, questionIndex, direction);
    await apiFetch("/questionnaire/questions/reorder", {
      method: "POST",
      body: JSON.stringify({ sectionId: section.id, questionIds: nextQuestions.map((question) => question.id) })
    });
    await onChanged();
  }

  return (
    <section className="panel mb-5 grid gap-4 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="font-serif text-2xl text-ink">Questionnaire Builder</h2>
          <p className="text-sm text-ink-muted">Master admin controls for sections, ordering, question text, moves and removals.</p>
        </div>
        {message ? <span className="text-sm text-red-700">{message}</span> : null}
      </div>

      <form onSubmit={addSection} className="grid gap-3 rounded-md border border-[#e6dfd8] bg-field-100 p-3 md:grid-cols-[160px_1fr_auto]">
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

      <div className="grid gap-4">
        {sections.map((section, sectionIndex) => (
          <details key={section.id} className="rounded-md border border-[#e6dfd8] bg-white p-3" open>
            <summary className="cursor-pointer font-serif text-xl text-ink">
              {section.sortOrder}. {section.code} - {section.title}
            </summary>
            <form
              className="mt-3 grid gap-3 md:grid-cols-[120px_1fr_auto_auto]"
              onSubmit={async (event) => {
                event.preventDefault();
                await updateSection(section, new FormData(event.currentTarget));
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
                <button type="button" className="field-button-secondary" disabled={sectionIndex === 0} onClick={() => reorderSections(moveItem(sections, sectionIndex, -1))}>
                  <ArrowUp className="h-4 w-4" aria-hidden />
                </button>
                <button type="button" className="field-button-secondary" disabled={sectionIndex === sections.length - 1} onClick={() => reorderSections(moveItem(sections, sectionIndex, 1))}>
                  <ArrowDown className="h-4 w-4" aria-hidden />
                </button>
                <button type="button" className="text-sm font-semibold text-red-700" onClick={() => removeSection(section)}>
                  <Trash2 className="inline h-4 w-4" aria-hidden /> Remove
                </button>
              </div>
            </form>

            <div className="mt-4 grid gap-3">
              {section.questions.map((question, questionIndex) => (
                <form
                  key={question.id}
                  className="grid gap-3 rounded-md bg-field-100 p-3 lg:grid-cols-[140px_1fr_auto]"
                  onSubmit={async (event) => {
                    event.preventDefault();
                    await updateQuestion(question, new FormData(event.currentTarget));
                  }}
                >
                  <Field label="Move to section">
                    <Select name="sectionId" defaultValue={question.sectionId ?? section.id}>
                      {sections.map((option) => (
                        <option key={option.id} value={option.id}>
                          {option.code}
                        </option>
                      ))}
                    </Select>
                  </Field>
                  <Field label={`Question ${question.sortOrder}`}>
                    <TextArea name="prompt" defaultValue={question.prompt} required />
                  </Field>
                  <div className="flex flex-wrap items-end gap-2">
                    <label className="flex items-center gap-2 text-sm text-ink-muted">
                      <input name="isActive" type="checkbox" defaultChecked={question.isActive} />
                      Active
                    </label>
                    <button className="field-button-secondary" type="submit">
                      <Save className="h-4 w-4" aria-hidden />
                      Save
                    </button>
                    <button type="button" className="field-button-secondary" disabled={questionIndex === 0} onClick={() => reorderQuestions(section, questionIndex, -1)}>
                      <ArrowUp className="h-4 w-4" aria-hidden />
                    </button>
                    <button type="button" className="field-button-secondary" disabled={questionIndex === section.questions.length - 1} onClick={() => reorderQuestions(section, questionIndex, 1)}>
                      <ArrowDown className="h-4 w-4" aria-hidden />
                    </button>
                    <button type="button" className="text-sm font-semibold text-red-700" onClick={() => removeQuestion(question)}>
                      Remove
                    </button>
                  </div>
                </form>
              ))}
              <form
                className="grid gap-3 rounded-md border border-dashed border-[#d7c7bc] p-3 md:grid-cols-[1fr_auto]"
                onSubmit={async (event) => {
                  event.preventDefault();
                  await addQuestion(section, new FormData(event.currentTarget));
                  event.currentTarget.reset();
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
          </details>
        ))}
      </div>
    </section>
  );
}
