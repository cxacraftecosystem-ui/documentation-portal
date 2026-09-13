"use client";

/**
 * "Upload a workbook" — the door the craft-toolkit questionnaire comes in through.
 *
 * ONE COMPONENT FOR BOTH UPLOAD ENDPOINTS, because they are the same act from the admin's side:
 * `POST /questionnaires/upload` makes a new questionnaire, `POST /questionnaires/{id}/upload` edits
 * an existing one, and which of the two runs is decided by `questionnaireId` being present. Two
 * dialogs would drift, and the half that drifted would be the re-upload — the rarer path, and the
 * one whose mistakes cost recorded answers.
 *
 * WHAT DIFFERS BETWEEN THE TWO, and it is only ever copy: a re-upload runs the edit-after-answers
 * rule, so this says so BEFORE the file is chosen rather than reporting it afterwards. An admin about
 * to re-upload needs to know that rewording an answered question will ADD a question rather than
 * change one, because the alternative is discovering it in the report and reading it as the import
 * having duplicated their instrument.
 *
 * THE DESCRIPTION FIELD IS ONLY ON THE CREATE PATH, and that is not a style choice:
 * `POST /questionnaires/{id}/upload` accepts a title and nothing else, so on the re-upload path it
 * would be a control whose value the request cannot carry — a box somebody types into and a change
 * that silently never happens.
 */

import { useRef, useState } from "react";
import { FileSpreadsheet, Upload } from "lucide-react";

import { FieldDialog } from "@/components/dialogs";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
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
  questionnaireTitle
}: {
  open: boolean;
  onClose: () => void;
  onUploaded: (result: QWorkbookUploadResult) => void;
  /** Present = re-upload over this questionnaire; absent = create a new one. */
  questionnaireId?: string;
  /** Named in the heading so an admin can see WHICH instrument they are about to overwrite. */
  questionnaireTitle?: string;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const editing = Boolean(questionnaireId);

  function reset() {
    setFile(null);
    setTitle("");
    setDescription("");
    setError(null);
    // The DOM input holds the chosen file independently of React state, so clearing only the state
    // leaves the browser reporting the previous file name and re-choosing THE SAME file fires no
    // change event at all — the second upload of a corrected workbook would silently do nothing.
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
            description: description.trim() || undefined
          });
      reset();
      onUploaded(result);
    } catch (err) {
      // KEPT IN THE DIALOG rather than raised to the page: the file the admin picked is still chosen
      // here, so the fix (pick the other file, remove the password, Save As .xlsx) is one click from
      // the message. The 415, the 413, the 409 "that workbook came from a different questionnaire"
      // and the 422 out of the parser all carry a sentence written to be shown as-is.
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
          ? "The workbook you downloaded from this questionnaire, with your edits. Questions are matched by the Question ID column, so rows you changed are edited rather than added again."
          : "The .xlsx pro-forma with your questions typed into it, or a question set downloaded from another questionnaire."
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
          // Said BEFORE the file is chosen, not reported afterwards. An admin who rewords an
          // answered question is going to get a new question next to the old one; discovering that
          // in the change report, after the fact, reads as the import having duplicated their form.
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            Questions that already have answers recorded in interviews keep their wording. If you have reworded one, the
            original and its answers are kept and your new wording is added as a new question — nothing is overwritten
            and nothing is lost. Questions you deleted from the sheet are retired, not deleted, if anyone has answered
            them, and a section you removed is switched off rather than deleted. The report afterwards names every
            question this happened to.
          </p>
        ) : (
          <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            <p className="font-medium text-ink-900">Two files work here.</p>
            <ul className="mt-1 grid gap-1">
              <li>
                <span className="font-medium text-ink-900">The blank pro-forma</span>, with your questions typed into
                it.
              </li>
              <li>
                <span className="font-medium text-ink-900">A question set</span> downloaded from another questionnaire —
                you get its questions in a new questionnaire of your own.
              </li>
            </ul>
            <p className="mt-1">
              Answers typed into the Answer column are counted and reported back to you, and are not stored. An answer
              belongs to an interview, against an artisan, recorded by the researcher who ran it.
            </p>
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
            {file ? file.name : "Excel workbooks only. Save As → Excel Workbook (.xlsx) if yours is an older .xls."}
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
