"use client";

import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ClipboardList, Plus } from "lucide-react";

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
import { isAdmin } from "@/lib/permissions";
import type { Artisan, PageResult, QuestionnaireInterview, QuestionnaireQuestion } from "@/lib/types";
import { CalendarLume } from "@/components/ui/calendar-lume";

export default function QuestionnairePage() {
  const { user } = useAuth();
  const searchParams = useSearchParams();
  const [questions, setQuestions] = useState<QuestionnaireQuestion[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [data, setData] = useState<PageResult<QuestionnaireInterview> | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [selectedArtisanId, setSelectedArtisanId] = useState(searchParams.get("artisanId") ?? "");
  const [answerMode, setAnswerMode] = useState<"audio" | "text">("audio");
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [page, setPage] = useState(1);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const groupedQuestions = useMemo(() => {
    return questions.reduce<Record<string, { title: string; items: QuestionnaireQuestion[] }>>((groups, question) => {
      groups[question.sectionCode] ??= { title: question.sectionTitle, items: [] };
      groups[question.sectionCode].items.push(question);
      return groups;
    }, {});
  }, [questions]);

  const orderedGroups = useMemo(() => {
    return Object.entries(groupedQuestions).sort(([left], [right]) => {
      if (left === "RESP") return -1;
      if (right === "RESP") return 1;
      return left.localeCompare(right, undefined, { numeric: true });
    });
  }, [groupedQuestions]);

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
      const [questionList, artisanResult, interviewResult] = await Promise.all([
        apiFetch<QuestionnaireQuestion[]>("/questionnaire/questions"),
        listResource<Artisan>("/artisans", { pageSize: 100 }),
        listResource<QuestionnaireInterview>("/questionnaire/interviews", { page, pageSize: 20 })
      ]);
      setQuestions(questionList);
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
      event.currentTarget.reset();
      setAnswers({});
      setMediaFiles([]);
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
        {answerMode === "text" ? (
        <div className="grid gap-3">
          {orderedGroups.map(([code, group], index) => (
            <details key={code} className="rounded-md border border-[#e6dfd8] bg-field-100 p-3" open={index === 0}>
              <summary className="cursor-pointer font-serif text-lg text-ink">
                {code}. {group.title}
              </summary>
              <div className="mt-3 grid gap-3">
                {group.items.map((question) => (
                  <Field key={question.id} label={`${question.sortOrder}. ${question.prompt}`}>
                    <TextArea value={answers[question.id] ?? ""} onChange={(event) => setAnswers((current) => ({ ...current, [question.id]: event.target.value }))} />
                  </Field>
                ))}
              </div>
            </details>
          ))}
        </div>
        ) : null}
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
