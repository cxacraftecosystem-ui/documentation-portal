"use client";

import { useRef, useState } from "react";
import { FileSpreadsheet, Upload } from "lucide-react";

import { FieldDialog } from "@/components/dialogs";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { kindOptions } from "@/lib/questionnaireLibrary";
import {
  reuploadQuestionnaire,
  uploadQuestionnaire,
  type QWorkbookUploadResult
} from "@/components/questionnaires/workbookApi";

export function WorkbookUploadDialog({
  open,
  onClose,
  onUploaded,
  questionnaireId,
  questionnaireTitle,
  workshops = []
}: {
  open: boolean;
  onClose: () => void;
  onUploaded: (result: QWorkbookUploadResult) => void;
  questionnaireId?: string;
  questionnaireTitle?: string;
  workshops?: { id: string; title: string }[];
}) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [kind, setKind] = useState("");
  const [workshopId, setWorkshopId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const editing = Boolean(questionnaireId);

  function reset() {
    setFile(null);
    setTitle("");
    setDescription("");
    setKind("");
    setWorkshopId("");
    setError(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function submit() {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const result = questionnaireId
        ? await reuploadQuestionnaire(questionnaireId, file, { title: title.trim() || undefined })
        : await uploadQuestionnaire(file, {
            title: title.trim() || undefined,
            description: description.trim() || undefined,
            kind: kind || undefined,
            workshopId: workshopId || undefined
          });
      reset();
      onUploaded(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to read that workbook");
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog
      open={open}
      onClose={close}
      busy={busy}
      title={
        editing
          ? questionnaireTitle
            ? `Upload an edited copy of “${questionnaireTitle}”`
            : "Upload an edited copy of this questionnaire"
          : "Upload a filled-in pro-forma"
      }
      description={
        editing
          ? "Your edited copy of this questionnaire’s workbook."
          : "A filled-in template or a question set."
      }
      icon={<FileSpreadsheet className="h-5 w-5" aria-hidden />}
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={close} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="field-button" onClick={submit} disabled={!file || busy}>
            {busy ? "Reading the workbook…" : editing ? "Upload and apply" : "Upload questionnaire"}
          </button>
        </>
      }
    >
      <div className="grid gap-4">
        {error ? (
          <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
        ) : null}

        {editing ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            Answered questions are never overwritten: rewording adds a new question, and removed ones are retired.
          </p>
        ) : (
          <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            <p>Each answer column becomes an uploaded session.</p>
          </div>
        )}

        <FieldBlock label="Workbook" required>
          <label className="file-trigger">
            <Upload className="h-4 w-4" aria-hidden />
            {file ? "Choose a different file" : "Choose an .xlsx file"}
            <input
              ref={fileInputRef}
              type="file"
              className="sr-only"
              accept=".xlsx,.xlsm,.xltx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              onChange={(event) => {
                setFile(event.target.files?.[0] ?? null);
                setError(null);
              }}
            />
          </label>
          <p className="mt-1 text-xs leading-5 text-ink-500">
            {file ? file.name : ".xlsx only."}
          </p>
        </FieldBlock>

        <Field label="Title">
          <TextInput
            value={title}
            maxLength={220}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={
              editing ? "Leave blank to keep the current title" : "Leave blank to use the title on the Details sheet"
            }
          />
        </Field>

        {editing ? null : (
          <div className="grid gap-4 sm:grid-cols-2">
            <FieldBlock label="Kind">
              <SearchableSelect value={kind} onChange={setKind} options={kindOptions()} ariaLabel="Kind" advanceOnSelect={false} />
            </FieldBlock>
            <FieldBlock label="Workshop for the answers">
              <SearchableSelect
                value={workshopId}
                onChange={setWorkshopId}
                options={workshops.map((workshop) => ({ value: workshop.id, label: workshop.title }))}
                placeholder="None"
                emptyLabel="No workshops"
                ariaLabel="Workshop for the answers"
                searchable
                clearable
                advanceOnSelect={false}
              />
            </FieldBlock>
          </div>
        )}

        {editing ? null : (
          <Field label="Description">
            <TextArea
              value={description}
              maxLength={2000}
              rows={2}
              onChange={(event) => setDescription(event.target.value)}
              placeholder="Leave blank to use the description on the Details sheet"
            />
          </Field>
        )}
      </div>
    </FieldDialog>
  );
}
