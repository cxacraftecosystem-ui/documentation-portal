"use client";

import { ClipboardList, FileSpreadsheet, FileUp, Trash2, Undo2 } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs";
import { PageHeader } from "@/components/PageHeader";
import { QuestionnaireAdminEditor } from "@/components/questionnaires/InstrumentBuilder";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { WorkbookUploadDialog } from "@/components/questionnaires/WorkbookUploadDialog";
import { downloadQuestionnaireWorkbook, saveWorkbook, type QWorkbookReport } from "@/components/questionnaires/workbookApi";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { apiFetch, buildQuery } from "@/lib/api";
import { canManageQuestionnaire, mayDeleteQuestionnaire, mayEditQuestionnaire } from "@/lib/permissions";
import { qs } from "@/lib/strings/qs";
import type { Questionnaire, QuestionnaireSection } from "@/lib/types";

/**
 * `/questionnaire/builder` (DESIGN §4.1, audit H15): the instrument builder and nothing else. It used
 * to be the legacy capture page reached at another path, so under v2 a researcher pressing "Builder"
 * landed in the old interview form. Managers only (`canManageQuestionnaire`); the page header
 * carries the one Back control.
 *
 * DELETING FROM A FORM IS ITS AUTHOR'S OR A CONTENT ADMIN'S (owner, 22 Oct 2026): the selected
 * instrument's Delete, its sections' and questions' Remove, and the Restore of a deleted one are drawn
 * for `mayDeleteQuestionnaire(user, createdById)`. The list is read with `activeOnly=false` so the
 * deleted forms this account may restore are on the same screen; the picker offers the live ones.
 *
 * EDITING A FORM IS ITS AUTHOR'S OR A CONTENT ADMIN'S TOO (owner, 22 Oct 2026; the server's
 * `assert_may_edit_form`): `mayEditQuestionnaire(user, createdById)` draws the editor, and anybody
 * else who manages questionnaires sees the selected form read-only. The same editors get the form's
 * own workbook here — Download .xlsx and Re-upload — because `/questionnaire/workbooks` stays an admin
 * route (creating a form from a workbook is admin-only) while re-uploading over a form is its author's.
 */
export function BuilderScreen() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const allowed = canManageQuestionnaire(user);
  const [instruments, setInstruments] = useState<Questionnaire[]>([]);
  const [deleted, setDeleted] = useState<Questionnaire[]>([]);
  const [busy, setBusy] = useState(false);
  const [questionnaireId, setQuestionnaireId] = useState<string | null>(null);
  const [sections, setSections] = useState<QuestionnaireSection[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [report, setReport] = useState<QWorkbookReport | null>(null);
  const generation = useRef(0);

  const load = useCallback(async (instrumentId: string | null) => {
    const mine = (generation.current += 1);
    try {
      const [everything, rows] = await Promise.all([
        apiFetch<Questionnaire[]>(`/questionnaires${buildQuery({ activeOnly: "false" })}`),
        apiFetch<QuestionnaireSection[]>(`/questionnaire/sections${buildQuery({ questionnaireId: instrumentId ?? undefined })}`)
      ]);
      if (mine !== generation.current) return;
      const list = everything.filter((instrument) => instrument.isActive !== false);
      setInstruments(list);
      setDeleted(everything.filter((instrument) => instrument.isActive === false));
      setQuestionnaireId(instrumentId ?? rows[0]?.questionnaireId ?? list.find((i) => i.isDefault)?.id ?? list[0]?.id ?? null);
      setSections(rows);
      setError(null);
    } catch (err) {
      if (mine === generation.current) setError(err instanceof Error ? err.message : qs("recordLoadError"));
    }
  }, []);

  useEffect(() => {
    if (allowed) void load(null);
  }, [allowed, load]);

  const selected = instruments.find((instrument) => instrument.id === questionnaireId) ?? null;
  const mayEdit = mayEditQuestionnaire(user, selected?.createdById);
  const mayDelete = mayDeleteQuestionnaire(user, selected?.createdById);
  const restorable = deleted.filter((instrument) => mayDeleteQuestionnaire(user, instrument.createdById));

  async function deleteSelected() {
    if (!selected) return;
    const ok = await confirm(deleteConfirm(`Delete “${selected.title}”?`, undefined, qs("deleteQuestionnaireNote")));
    if (!ok) return;
    setBusy(true);
    try {
      await apiFetch(`/questionnaires/${encodeURIComponent(selected.id)}`, { method: "DELETE" });
      await load(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : qs("recordLoadError"));
    } finally {
      setBusy(false);
    }
  }

  async function downloadWorkbook() {
    if (!selected) return;
    setBusy(true);
    try {
      saveWorkbook(await downloadQuestionnaireWorkbook(selected.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : qs("recordLoadError"));
    } finally {
      setBusy(false);
    }
  }

  async function restore(instrument: Questionnaire) {
    setBusy(true);
    try {
      await apiFetch(`/questionnaires/${encodeURIComponent(instrument.id)}`, {
        method: "PATCH",
        body: JSON.stringify({ isActive: true })
      });
      await load(instrument.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : qs("recordLoadError"));
    } finally {
      setBusy(false);
    }
  }

  const header = <PageHeader title={qs("builder")} icon={<ClipboardList className="h-5 w-5" aria-hidden />} />;
  if (!allowed) {
    return (
      <>
        {header}
        <RestrictedPanel title={qs("builderLocked")} body={qs("builderLockedBody")} />
      </>
    );
  }

  return (
    <>
      {header}
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {report ? <UploadReport report={report} className="mb-4" /> : null}
      <div className="panel mb-4 grid gap-1 p-4" data-builder-instrument>
        <span className="field-label">{qs("fieldInstrument")}</span>
        <SearchableSelect
          value={questionnaireId ?? ""}
          onChange={(value) => void load(value || null)}
          options={instruments.map((i) => ({ value: i.id, label: i.title }))}
          searchable
          advanceOnSelect={false}
          ariaLabel={qs("fieldInstrument")}
        />
        {selected && mayEdit ? (
          <ButtonRow
            className="mt-2"
            destructive={
              mayDelete && !selected.isDefault ? (
                <button type="button" data-danger className="field-button-secondary field-danger" disabled={busy} onClick={() => void deleteSelected()}>
                  <Trash2 className="h-4 w-4" aria-hidden />
                  {qs("deleteQuestionnaire")}
                </button>
              ) : null
            }
          >
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void downloadWorkbook()}>
              <FileSpreadsheet className="h-4 w-4" aria-hidden />
              {qs("downloadWorkbook")}
            </button>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => setUploading(true)}>
              <FileUp className="h-4 w-4" aria-hidden />
              {qs("reuploadWorkbook")}
            </button>
          </ButtonRow>
        ) : null}
      </div>
      {sections === null ? (
        <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>
      ) : (
        <QuestionnaireAdminEditor
          sections={sections}
          questionnaireId={questionnaireId}
          onChanged={() => load(questionnaireId)}
          defaultOpen
          mayEdit={mayEdit}
          mayDelete={mayDelete}
        />
      )}
      {selected && mayEdit ? (
        <WorkbookUploadDialog
          open={uploading}
          onClose={() => setUploading(false)}
          questionnaireId={selected.id}
          questionnaireTitle={selected.title}
          onUploaded={(result) => {
            setUploading(false);
            setReport(result.report);
            void load(selected.id);
          }}
        />
      ) : null}
      {restorable.length > 0 ? (
        <section className="panel mt-4 grid gap-2 p-4" data-builder-deleted>
          <h2 className="font-display font-bold text-ink-900">{qs("deletedQuestionnaires")}</h2>
          {restorable.map((instrument) => (
            <div key={instrument.id} className="flex flex-wrap items-center justify-between gap-2">
              <span className="text-sm text-ink-700">{instrument.title}</span>
              <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void restore(instrument)}>
                <Undo2 className="h-4 w-4" aria-hidden />
                {qs("restoreQuestionnaire")}
              </button>
            </div>
          ))}
        </section>
      ) : null}
    </>
  );
}
