"use client";

/**
 * `/questionnaires/[id]` — one form (UNIFY 4.2): its sections and questions; its kind; the workshops
 * it is bound to; Share with everyone and Default (admins); Reuse; the workbook, question set and
 * handoff bundle; Re-upload; the corpus (dataset downloaders); Delete (its author or a content
 * admin, P16) and Restore (content admins); and the sessions recorded on it.
 *
 * What may be pressed comes from the server's `can` on the form, so a control is never drawn that
 * the server would refuse.
 */

import { ClipboardList, Copy, Download, FileSpreadsheet, FileUp, Package, Play, RotateCcw, Trash2 } from "lucide-react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs";
import { PageHeader } from "@/components/PageHeader";
import { QuestionnaireAdminEditor } from "@/components/questionnaires/InstrumentBuilder";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { DraftFormPanel } from "@/components/questionnaires/DraftFormPanel";
import { FormBindingsCard } from "@/components/questionnaires/FormBindingsCard";
import { ReuseFormDialog } from "@/components/questionnaires/FormDialogs";
import { FormSessionsCard } from "@/components/questionnaires/FormSessionsCard";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { NEEDS_CONNECTION, useOnline } from "@/components/questionnaires/useOnline";
import { WorkbookUploadDialog } from "@/components/questionnaires/WorkbookUploadDialog";
import {
  downloadQuestionnaireWorkbook,
  downloadQuestionSet,
  saveWorkbook,
  type QWorkbookReport
} from "@/components/questionnaires/workbookApi";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import { useToast } from "@/components/ui/Toast";
import { apiFetch, buildQuery } from "@/lib/api";
import { canDownloadDataset, canManageQuestionnaire, isContentAdmin } from "@/lib/permissions";
import {
  downloads,
  formSummary,
  isOffline,
  kindLabel,
  kindOptions,
  library,
  libraryError,
  P16,
  readFormsCache,
  saveDownload,
  savedCopyLine,
  writeFormsCache,
  type LibraryFormDetail
} from "@/lib/questionnaireLibrary";
import { isDraftId } from "@/lib/questionnaireDraftForms";
import type { QuestionnaireSection } from "@/lib/types";
import { loadWorkshopList } from "@/lib/workshopList";

export default function QuestionnaireFormPage() {
  const params = useParams<{ id: string }>();
  // A form written offline lives on this device until it is sent (A15).
  return isDraftId(params.id) ? <DraftFormPanel id={params.id} /> : <QuestionnaireFormView id={params.id} />;
}

function QuestionnaireFormView({ id }: { id: string }) {
  const router = useRouter();
  const { user } = useAuth();
  const confirm = useConfirm();
  const { toast } = useToast();
  const online = useOnline();
  const config = useQuestionnaireConfig();
  const v2 = config?.v2Enabled === true;

  const [form, setForm] = useState<LibraryFormDetail | null>(null);
  const [sections, setSections] = useState<QuestionnaireSection[]>([]);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [workshops, setWorkshops] = useState<{ id: string; title: string }[]>([]);
  const [reusing, setReusing] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [report, setReport] = useState<QWorkbookReport | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const owner = user?.id ?? null;
  const load = useCallback(async () => {
    try {
      const detail = await library.get(id);
      setForm(detail);
      setSaved(null);
      setError(null);
      writeFormsCache(owner, { form: detail });
      const rows = await apiFetch<QuestionnaireSection[]>(`/questionnaire/sections${buildQuery({ questionnaireId: id })}`);
      setSections(rows);
      setRevision((n) => n + 1);
    } catch (err) {
      // Offline (A16): the copy this account last read of this form, read-only.
      const held = isOffline(err) ? readFormsCache(owner) : null;
      const copy = held?.forms[id];
      if (copy) {
        setForm({ ...copy, can: { edit: false, delete: false, restore: false, share: false, bind: false, setDefault: false } });
        setSections(copy.sections as unknown as QuestionnaireSection[]);
        setSaved(held.at);
        setRevision((n) => n + 1);
        return;
      }
      setError(libraryError(err, "Couldn't load this form."));
    }
  }, [id, owner]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    loadWorkshopList(user.id)
      .then((page) => {
        if (!cancelled) setWorkshops(page.items.map((w) => ({ id: w.id, title: w.title })));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [user]);

  async function run(key: string, work: () => Promise<void>) {
    setBusy(key);
    try {
      await work();
    } catch (err) {
      toast({ tone: "error", title: "That didn't work", description: libraryError(err) });
    } finally {
      setBusy(null);
    }
  }

  if (error && !form) {
    return (
      <>
        <PageHeader title="Questionnaire" icon={<ClipboardList className="h-5 w-5" aria-hidden />} />
        <p className="panel p-4 text-sm text-ink-700">{error}</p>
      </>
    );
  }
  if (!form) {
    return (
      <>
        <PageHeader title="Questionnaire" icon={<ClipboardList className="h-5 w-5" aria-hidden />} />
        <p className="text-sm text-ink-500">Loading…</p>
      </>
    );
  }

  const can = form.can ?? { edit: false, delete: false, restore: false, share: false, bind: false, setDefault: false };
  const deleted = Boolean(form.deletedAt);
  const author = canManageQuestionnaire(user);
  const offlineTitle = online ? undefined : NEEDS_CONNECTION;
  const facts = [kindLabel(form.kind), formSummary(form), form.isShared ? "Shared" : null, form.isDefault ? "Default" : null, deleted ? "Deleted" : null]
    .filter(Boolean)
    .join(" · ");

  async function remove() {
    if (!form) return;
    const ok = await confirm({ title: P16.title, body: P16.body, tone: "danger", confirmLabel: P16.buttons[1], cancelLabel: P16.buttons[0] });
    if (!ok) return;
    await run("delete", async () => {
      await library.remove(form.id);
      toast({ tone: "success", title: "Deleted", description: form.title });
      router.push("/questionnaires");
    });
  }

  return (
    <>
      <PageHeader
        title={form.title}
        description={facts}
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          deleted ? (
            can.restore ? (
              <button
                type="button"
                className="field-button"
                disabled={!online || busy === "restore"}
                title={offlineTitle}
                onClick={() =>
                  run("restore", async () => {
                    await library.restore(form.id);
                    await load();
                  })
                }
              >
                <RotateCcw className="h-4 w-4" aria-hidden /> Restore
              </button>
            ) : null
          ) : (
            <>
              {v2 ? (
                <Link className="field-button" href={`/questionnaire/sessions/new${buildQuery({ questionnaireId: form.id })}`}>
                  <Play className="h-4 w-4" aria-hidden /> Start session
                </Link>
              ) : null}
              {author ? (
                <button type="button" className="field-button-secondary" disabled={!online} title={offlineTitle} onClick={() => setReusing(true)}>
                  <Copy className="h-4 w-4" aria-hidden /> Reuse
                </button>
              ) : null}
            </>
          )
        }
      />

      {report ? <UploadReport report={report} className="mb-5" /> : null}

      <div className="grid gap-5">
        {saved ? (
          <p className="text-sm text-ink-500" data-saved-copy>
            {savedCopyLine(saved)}
          </p>
        ) : null}
        {form.description ? <p className="text-sm leading-6 text-ink-700">{form.description}</p> : null}

        {!deleted && (can.edit || can.share || can.setDefault) ? (
          <section className="panel grid gap-4 p-4" aria-label="Settings">
            {can.edit ? (
              <div className="grid gap-1 sm:max-w-sm">
                <span className="field-label">Kind</span>
                <SearchableSelect
                  value={form.kind ?? ""}
                  onChange={(value) =>
                    void run("kind", async () => {
                      await library.patch(form.id, { kind: value || null });
                      await load();
                    })
                  }
                  options={kindOptions()}
                  ariaLabel="Kind"
                  disabled={!online || busy === "kind"}
                  advanceOnSelect={false}
                />
              </div>
            ) : null}
            {can.share ? (
              <Toggle
                label="Share with everyone"
                description="Every researcher can use it."
                checked={Boolean(form.isShared)}
                busy={busy === "share"}
                disabled={!online}
                onChange={(next) =>
                  void run("share", async () => {
                    await library.patch(form.id, { isShared: next });
                    await load();
                  })
                }
              />
            ) : null}
            {can.setDefault ? (
              <Toggle
                label="Default form"
                description="Offered when a workshop has no form of its own."
                checked={form.isDefault}
                busy={busy === "default"}
                disabled={!online}
                onChange={(next) =>
                  void run("default", async () => {
                    await library.setDefault(form.id, next);
                    await load();
                  })
                }
              />
            ) : null}
          </section>
        ) : null}

        {!deleted ? (
          <section className="panel grid gap-3 p-4" aria-labelledby="files-heading">
            <h2 id="files-heading" className="font-display text-lg font-bold text-ink-900">
              Files
            </h2>
            <ButtonRow align="start">
              {can.edit ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={!online || busy === "xlsx"}
                  title={offlineTitle}
                  onClick={() => run("xlsx", async () => saveWorkbook(await downloadQuestionnaireWorkbook(form.id)))}
                >
                  <FileSpreadsheet className="h-4 w-4" aria-hidden /> Download workbook
                </button>
              ) : null}
              {author ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={!online || busy === "questions"}
                  title={offlineTitle}
                  onClick={() => run("questions", async () => saveWorkbook(await downloadQuestionSet(form.id)))}
                >
                  <Download className="h-4 w-4" aria-hidden /> Download questions
                </button>
              ) : null}
              {author ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={!online || busy === "bundle"}
                  title={offlineTitle}
                  onClick={() => run("bundle", async () => saveDownload(await downloads.bundle(form.id)))}
                >
                  <Package className="h-4 w-4" aria-hidden /> Download bundle
                </button>
              ) : null}
              {can.edit ? (
                <button type="button" className="field-button-secondary" disabled={!online} title={offlineTitle} onClick={() => setUploading(true)}>
                  <FileUp className="h-4 w-4" aria-hidden /> Re-upload
                </button>
              ) : null}
              {v2 && canDownloadDataset(user) ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={!online || busy === "corpus"}
                  title={offlineTitle}
                  onClick={() =>
                    run("corpus", async () => {
                      saveDownload(await downloads.corpusAnswers(form.id));
                      saveDownload(await downloads.corpusRecordings(form.id));
                    })
                  }
                >
                  <Download className="h-4 w-4" aria-hidden /> Download corpus
                </button>
              ) : null}
            </ButtonRow>
          </section>
        ) : null}

        {!deleted && v2 ? (
          <FormBindingsCard questionnaireId={form.id} workshops={workshops} canBind={can.bind} online={online} />
        ) : null}

        <QuestionnaireAdminEditor
          key={revision}
          sections={sections}
          questionnaireId={form.id}
          onChanged={load}
          defaultOpen
          mayEdit={can.edit && online}
          mayDelete={can.delete && online}
        />

        {v2 ? <FormSessionsCard questionnaireId={form.id} canSeeDeleted={isContentAdmin(user)} online={online} /> : null}

        {can.delete ? (
          <ButtonRow
            destructive={
              <button type="button" className="field-button-secondary field-danger" disabled={!online || busy === "delete"} title={offlineTitle} onClick={remove}>
                <Trash2 className="h-4 w-4" aria-hidden /> Delete form
              </button>
            }
          />
        ) : null}
      </div>

      <ReuseFormDialog
        source={reusing ? form : null}
        onClose={() => setReusing(false)}
        onCreated={(copy) => {
          setReusing(false);
          router.push(`/questionnaires/${encodeURIComponent(copy.id)}`);
        }}
      />
      {can.edit ? (
        <WorkbookUploadDialog
          open={uploading}
          onClose={() => setUploading(false)}
          questionnaireId={form.id}
          questionnaireTitle={form.title}
          onUploaded={(result) => {
            setUploading(false);
            setReport(result.report);
            void load();
          }}
        />
      ) : null}
    </>
  );
}
