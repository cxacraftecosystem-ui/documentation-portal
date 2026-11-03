"use client";

import { useEffect, useState } from "react";
import { ArrowDown, ArrowUp, GripVertical, Pencil, Plus, Save, Trash2 } from "lucide-react";

import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { Field, Select, TextArea, TextInput } from "@/components/FormControls";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";
import { Accordion } from "@/components/ui/Accordion";
import { Badge } from "@/components/ui/Badge";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import { apiFetch } from "@/lib/api";
import { ARTISAN_FIELD_OPTIONS, artisanFieldLabel } from "@/lib/questionArtisanFields";
import type { QuestionnaireQuestion, QuestionnaireSection } from "@/lib/types";


type DragQuestion = { questionId: string; sectionId: string };
type DropIndicator = { sectionId: string; index: number };

function reindexQuestions(list: QuestionnaireQuestion[], section: { id: string; code: string; title: string }): QuestionnaireQuestion[] {
  return list.map((question, index) => ({
    ...question,
    sortOrder: index + 1,
    sectionId: section.id,
    sectionCode: section.code,
    sectionTitle: section.title
  }));
}

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

  let insertAt = from === to && srcIndex < gapIndex ? gapIndex - 1 : gapIndex;
  const [moved] = from.questions.splice(srcIndex, 1);
  insertAt = Math.max(0, Math.min(insertAt, to.questions.length));
  if (from === to && insertAt === srcIndex) return sections;
  to.questions.splice(insertAt, 0, moved);
  from.questions = reindexQuestions(from.questions, from);
  if (to !== from) to.questions = reindexQuestions(to.questions, to);
  return clone;
}

function SectionTitleField({ section }: { section: QuestionnaireSection }) {
  const [value, setValue] = useState(section.title);

  useEffect(() => {
    setValue(section.title);
  }, [section.id, section.title]);

  return (
    <DictatedTextInput
      name="title"
      label="Title"
      required
      explainWhenUnavailable={false}
      value={value}
      onChange={setValue}
    />
  );
}

export function QuestionnaireAdminEditor({
  sections,
  questionnaireId,
  onChanged,
  defaultOpen = false,
  mayEdit = false,
  mayDelete = false
}: {
  sections: QuestionnaireSection[];
  questionnaireId: string | null;
  onChanged: () => Promise<void>;
  defaultOpen?: boolean;
  mayEdit?: boolean;
  mayDelete?: boolean;
}) {
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
  const [questionNonce, setQuestionNonce] = useState<Record<string, number>>({});

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
        body: JSON.stringify({ questionnaireId, code: newCode.trim(), title: newTitle.trim() })
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
        isActive: mayDelete || !section.isActive ? form.get("isActive") === "on" : true
      })
    });
    await onChanged();
  }

  async function removeSection(section: QuestionnaireSection) {
    const ok = await confirm({
      title: `Remove section ${section.code}?`,
      body: (
        <>
          <span className="font-medium text-ink-900">{section.title}</span> is hidden from new interviews.
        </>
      ),
      note: "Existing answers are kept.",
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

  async function saveQuestion(
    question: QuestionnaireQuestion,
    prompt: string,
    isActive: boolean,
    artisanField: string
  ): Promise<boolean> {
    setBusy(true);
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/questions/${question.id}`, {
        method: "PATCH",
        body: JSON.stringify({
          prompt: prompt.trim(),
          isActive,
          ...(artisanField !== (question.artisanField ?? "") ? { artisanField: artisanField || null } : {})
        })
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
      note: "Existing answers are kept.",
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
    if (next === localSections) return;
    persistMove(next, toSectionId, drag.sectionId !== toSectionId ? { questionId: drag.questionId, toSectionId } : undefined);
  }

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

  if (!mayEdit) {
    return (
      <Accordion title="Questionnaire Builder" subtitle="Read only." defaultOpen={defaultOpen}>
        <div className="grid gap-4" data-builder-read-only>
          <p className="text-sm text-ink-muted">Only the form&rsquo;s author or an admin can edit it.</p>
          {localSections.length === 0 ? <p className="text-sm text-ink-muted">No sections yet.</p> : null}
          {localSections.map((section) => (
            <section key={section.id} className="rounded-md border border-line-200 bg-card p-3" aria-labelledby={`builder-section-${section.id}`}>
              <h3 id={`builder-section-${section.id}`} className="font-display text-xl font-bold text-ink">
                {section.sortOrder}. {section.code} - {section.title}
                {!section.isActive ? <span className="ml-2 rounded bg-line-200 px-1.5 py-0.5 align-middle text-xs font-normal text-ink-500">inactive</span> : null}
              </h3>
              {section.questions.length === 0 ? (
                <p className="mt-2 text-sm text-ink-muted">No questions yet.</p>
              ) : (
                <ol className="mt-3 grid gap-1">
                  {section.questions.map((question) => (
                    <li key={question.id} className="rounded-md border border-line-200 bg-field-50 p-2.5 text-sm text-ink-900">
                      <span className="mr-1 font-semibold text-ink-500">{question.sortOrder}.</span>
                      {question.prompt}
                      {!question.isActive ? <span className="ml-2 rounded bg-line-200 px-1.5 py-0.5 text-xs text-ink-500">inactive</span> : null}
                      {artisanFieldLabel(question.artisanField) ? (
                        <Badge className="ml-2">From artisan: {artisanFieldLabel(question.artisanField)}</Badge>
                      ) : null}
                    </li>
                  ))}
                </ol>
              )}
            </section>
          ))}
        </div>
      </Accordion>
    );
  }

  return (
    <Accordion
      title="Questionnaire Builder"
      subtitle="Edit sections and questions."
      defaultOpen={defaultOpen}
      headerRight={message ? <span className="text-sm text-red-700">{message}</span> : null}
    >
      <div className="grid gap-4">
        <form onSubmit={addSection} className="grid gap-3 rounded-md border border-line-200 bg-field-100 p-3 md:grid-cols-[160px_1fr_auto]">
          <Field label="Section code" required>
            <TextInput value={newCode} onChange={(event) => setNewCode(event.target.value)} placeholder="A, RESP, FIELD..." required />
          </Field>
          <DictatedTextInput
            label="Section title"
            placeholder="Section title"
            required
            explainWhenUnavailable={false}
            value={newTitle}
            onChange={setNewTitle}
          />
          <ButtonRow className="items-end">
            <button className="field-button" disabled={saving}>
              <Plus className="h-4 w-4" aria-hidden />
              Add section
            </button>
          </ButtonRow>
        </form>

        <p className="text-xs text-ink-muted">
          Drag to reorder, or use the arrows and &ldquo;Move to&rdquo;.
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
                  className="grid h-9 w-9 cursor-grab place-items-center rounded-md border border-line-200 bg-field-50 text-ink-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-field-600 active:cursor-grabbing disabled:opacity-60"
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
                <Field label="Code" required>
                  <TextInput name="code" defaultValue={section.code} required />
                </Field>
                <SectionTitleField section={section} />
                <div className="flex items-end">
                  <Toggle name="isActive" defaultChecked={section.isActive} label="Active" disabled={section.isActive && !mayDelete} />
                </div>
                <ButtonRow
                  className="items-end"
                  destructive={
                    mayDelete ? (
                      <button type="button" className="field-danger" onClick={() => removeSection(section)}>
                        <Trash2 className="h-4 w-4" aria-hidden />
                        Remove
                      </button>
                    ) : null
                  }
                >
                  <button className="field-button-secondary" type="submit">
                    <Save className="h-4 w-4" aria-hidden />
                    Save
                  </button>
                </ButtonRow>
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
                      onSave={(prompt, isActive, artisanField) => saveQuestion(question, prompt, isActive, artisanField)}
                      mayDeactivate={mayDelete}
                      onRemove={mayDelete ? () => removeQuestion(question) : undefined}
                    />
                  </div>
                ))}
                <DropLine active={dragActive && dropIndicator?.sectionId === section.id && dropIndicator.index === section.questions.length} />
                {section.questions.length === 0 ? (
                  <div
                    className={`rounded-md border border-dashed p-3 text-center text-xs font-semibold ${
                      dragActive ? "border-field-600 bg-field-100 text-field-700 dark:text-purple-300" : "border-[#d7c7bc] text-ink-muted"
                    }`}
                  >
                    {dragActive ? `Drop a question here to move it into ${section.code}` : "No questions yet — add one below."}
                  </div>
                ) : null}

                <form
                  className="mt-2 grid gap-3 rounded-md border border-dashed border-[#d7c7bc] p-3 md:grid-cols-[1fr_auto]"
                  onSubmit={async (event) => {
                    event.preventDefault();
                    const formElement = event.currentTarget;
                    setMessage(null);
                    try {
                      await addQuestion(section, new FormData(formElement));
                      formElement.reset();
                      setQuestionNonce((current) => ({ ...current, [section.id]: (current[section.id] ?? 0) + 1 }));
                    } catch (err) {
                      setMessage(err instanceof Error ? err.message : "Unable to add question");
                    }
                  }}
                >
                  <DictatedTextArea
                    key={questionNonce[section.id] ?? 0}
                    name="prompt"
                    label={`New question in ${section.code}`}
                    helper="Write the question prompt..."
                    required
                    explainWhenUnavailable={false}
                  />
                  <ButtonRow className="items-end">
                    <button className="field-button-secondary">
                      <Plus className="h-4 w-4" aria-hidden />
                      Add question
                    </button>
                  </ButtonRow>
                </form>
              </div>
            </div>
          ))}
        </div>
      </div>
    </Accordion>
  );
}

function DropLine({ active }: { active: boolean }) {
  return <div className={`h-0.5 rounded-full transition-colors ${active ? "bg-purple-700" : "bg-transparent"}`} aria-hidden />;
}

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
  mayDeactivate,
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
  onSave: (prompt: string, isActive: boolean, artisanField: string) => Promise<boolean>;
  mayDeactivate: boolean;
  onRemove?: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [prompt, setPrompt] = useState(question.prompt);
  const [isActive, setIsActive] = useState(question.isActive);
  const [artisanField, setArtisanField] = useState(question.artisanField ?? "");
  const [savingTile, setSavingTile] = useState(false);

  useEffect(() => {
    setPrompt(question.prompt);
    setIsActive(question.isActive);
    setArtisanField(question.artisanField ?? "");
  }, [question.id, question.prompt, question.isActive, question.artisanField]);

  function pointerAfter(event: React.DragEvent) {
    const rect = event.currentTarget.getBoundingClientRect();
    return event.clientY > rect.top + rect.height / 2;
  }

  const otherSections = sections.filter((section) => section.id !== currentSectionId);

  async function handleSave() {
    setSavingTile(true);
    const ok = await onSave(prompt, isActive, artisanField);
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
        className="mt-0.5 grid h-8 w-8 shrink-0 cursor-grab place-items-center rounded-md border border-line-200 bg-card text-ink-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-field-600 active:cursor-grabbing disabled:opacity-60"
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
          <>
            <TextArea
              aria-label={`Prompt for question ${question.sectionCode}${question.sortOrder}`}
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              rows={2}
            />
            <OnDeviceDictationButton
              fieldLabel={`the prompt for question ${question.sectionCode}${question.sortOrder}`}
              explainWhenUnavailable={false}
              disabled={disabled || savingTile}
              onCommit={(phrase) => setPrompt((current) => appendDictatedPhrase(current, phrase))}
            />
            <div className="mt-2 grid gap-1 sm:max-w-xs">
              <span className="field-label" id={`artisan-field-${question.id}`}>Fill from artisan</span>
              <SearchableSelect
                value={artisanField}
                onChange={setArtisanField}
                options={[{ value: "", label: "Nothing" }, ...ARTISAN_FIELD_OPTIONS]}
                ariaLabel={`Fill question ${question.sectionCode}${question.sortOrder} from the artisan`}
                advanceOnSelect={false}
                disabled={disabled || savingTile}
              />
            </div>
          </>
        ) : (
          <p className="text-sm text-ink-900">
            <span className="mr-1 font-semibold text-ink-500">{question.sortOrder}.</span>
            {question.prompt}
            {!question.isActive ? <span className="ml-2 rounded bg-line-200 px-1.5 py-0.5 text-xs text-ink-500">inactive</span> : null}
            {artisanFieldLabel(question.artisanField) ? (
              <Badge className="ml-2">From artisan: {artisanFieldLabel(question.artisanField)}</Badge>
            ) : null}
          </p>
        )}

        <div className="mt-2 flex flex-wrap items-center gap-2">
          {editing ? (
            <>
              <Toggle
                className="w-auto"
                label="Active"
                checked={isActive}
                onChange={setIsActive}
                disabled={question.isActive && !mayDeactivate}
              />
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
                  setArtisanField(question.artisanField ?? "");
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
                Update
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
              {onRemove ? (
                <button type="button" className="text-sm font-semibold text-red-700" onClick={onRemove} disabled={disabled}>
                  Remove
                </button>
              ) : null}
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
