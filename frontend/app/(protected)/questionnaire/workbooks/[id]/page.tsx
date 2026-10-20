"use client";

/**
 * /questionnaire/workbooks/[id] — one questionnaire, read-only: every section and every question in
 * the order a researcher meets them, with the "required" word and the help text exactly as the
 * capture screen prints them (`QuestionHelpText`, `RequiredByInstrument`).
 *
 * A click on an instrument's row on the workbooks list lands here. Reading it changes nothing; the
 * one way to change it is Update, which is the same workbook upload the list's Re-upload opens.
 * Gated like the list (`ROUTE_GUARDS` and `ADMIN_CHROME_ROUTES` both match this path by prefix), and
 * `GET /questionnaires/{id}` is the builder's own read — it carries no answer.
 */

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { FileSpreadsheet, FileUp } from "lucide-react";

import { useAdminView, adminChromeVisible } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { QuestionHelpText, RequiredByInstrument } from "@/components/questionnaires/QuestionHelpText";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { WorkbookUploadDialog } from "@/components/questionnaires/WorkbookUploadDialog";
import {
  downloadQuestionSet,
  downloadQuestionnaireWorkbook,
  saveWorkbook,
  type QWorkbookReport
} from "@/components/questionnaires/workbookApi";
import { useToast } from "@/components/ui/Toast";
import { apiFetch } from "@/lib/api";
import { canManageQuestionnaire, isAdmin, isContentAdmin } from "@/lib/permissions";
import type { Questionnaire, QuestionnaireSection } from "@/lib/types";

type Instrument = Questionnaire & { version?: number; sourceFilename?: string | null; sections: QuestionnaireSection[] };

export default function QuestionnaireInstrumentPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { user, loading: authLoading } = useAuth();
  const { adminMode } = useAdminView();
  const permitted = isAdmin(user) && adminChromeVisible(user, adminMode);
  const mayUpdate = permitted && canManageQuestionnaire(user);
  // Deleting an instrument retires it (soft); content admins in admin view (2026-10-20).
  const mayDelete = permitted && adminMode && isContentAdmin(user);
  const { toast } = useToast();
  const confirm = useConfirm();
  const router = useRouter();

  const [instrument, setInstrument] = useState<Instrument | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [report, setReport] = useState<QWorkbookReport | null>(null);

  const load = useCallback(() => {
    let cancelled = false;
    apiFetch<Instrument>(`/questionnaires/${encodeURIComponent(id)}`)
      .then((next) => {
        if (!cancelled) setInstrument(next);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Unable to load this questionnaire.");
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  useEffect(() => {
    if (authLoading || !permitted) return;
    return load();
  }, [authLoading, permitted, load]);

  async function take(key: string, run: () => Promise<{ blob: Blob; fileName: string }>) {
    setBusy(key);
    try {
      saveWorkbook(await run());
    } catch (err) {
      toast({
        tone: "error",
        title: "The download did not start",
        description: err instanceof Error ? err.message : "The request failed."
      });
    } finally {
      setBusy(null);
    }
  }

  async function remove(target: Instrument) {
    const ok = await confirm(deleteConfirm(`Delete “${target.title}”?`, undefined, "Its sessions and answers are kept."));
    if (!ok) return;
    setBusy("delete");
    try {
      await apiFetch(`/questionnaires/${encodeURIComponent(target.id)}`, { method: "DELETE" });
      toast({ tone: "success", title: "Questionnaire deleted" });
      router.push("/questionnaire/workbooks");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete this questionnaire.");
    } finally {
      setBusy(null);
    }
  }

  const sections = [...(instrument?.sections ?? [])].sort((a, b) => a.sortOrder - b.sortOrder);
  const questionCount = sections.reduce((sum, section) => sum + section.questions.length, 0);

  return (
    <>
      <PageHeader
        title={instrument?.title ?? "Questionnaire"}
        description={
          instrument
            ? [
                `${sections.length} sections · ${questionCount} questions`,
                typeof instrument.version === "number" ? `version ${instrument.version}` : null,
                instrument.sourceFilename ? `last uploaded from ${instrument.sourceFilename}` : null
              ]
                .filter(Boolean)
                .join(" · ")
            : undefined
        }
        icon={<FileSpreadsheet className="h-5 w-5" aria-hidden />}
        actions={
          permitted && instrument ? (
            <>
              <button
                type="button"
                className="field-button-secondary"
                disabled={busy === "xlsx"}
                onClick={() => take("xlsx", () => downloadQuestionnaireWorkbook(instrument.id))}
              >
                Download .xlsx
              </button>
              <button
                type="button"
                className="field-button-secondary"
                disabled={busy === "set"}
                onClick={() => take("set", () => downloadQuestionSet(instrument.id))}
              >
                Download question set
              </button>
              {mayUpdate ? (
                <button type="button" className="field-button" onClick={() => setUploading(true)}>
                  <FileUp className="h-4 w-4" aria-hidden />
                  Update
                </button>
              ) : null}
              {mayDelete && instrument.isActive !== false ? (
                <button
                  type="button"
                  data-danger
                  className="field-button-secondary field-danger"
                  disabled={busy === "delete"}
                  onClick={() => void remove(instrument)}
                >
                  Delete
                </button>
              ) : null}
            </>
          ) : undefined
        }
      />

      {report ? <UploadReport report={report} className="mb-4" /> : null}
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {authLoading || (!instrument && !error) ? (
        <section className="panel p-6 text-sm text-ink-500">Loading…</section>
      ) : !instrument ? null : sections.length === 0 ? (
        <section className="panel p-4">
          <EmptyState title="No sections yet" body="Upload a workbook to add questions." />
        </section>
      ) : (
        <div className="grid gap-4">
          {sections.map((section) => {
            const questions = [...section.questions].sort((a, b) => a.sortOrder - b.sortOrder);
            return (
              <section key={section.id} className="panel p-4" aria-labelledby={`section-${section.id}`}>
                <h2 id={`section-${section.id}`} className="font-display text-lg font-bold text-ink-900">
                  Section {section.code} · {section.title}
                  {section.isActive === false ? (
                    <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 align-middle text-[11px] font-medium text-amber-800">
                      not asked
                    </span>
                  ) : null}
                </h2>
                {questions.length === 0 ? (
                  <p className="mt-2 text-sm text-ink-500">No questions in this section.</p>
                ) : (
                  <ol className="mt-3 grid gap-3">
                    {questions.map((question) => (
                      <li key={question.id} className="grid gap-1">
                        <p className="text-sm text-ink-900">
                          <span className="font-medium">{question.sortOrder}.</span> {question.prompt}
                          <RequiredByInstrument question={question} />
                          {question.isActive === false || question.retiredAt ? (
                            <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 align-middle text-[11px] font-medium text-amber-800">
                              no longer asked
                            </span>
                          ) : null}
                        </p>
                        <QuestionHelpText question={question} />
                      </li>
                    ))}
                  </ol>
                )}
              </section>
            );
          })}
        </div>
      )}

      {mayUpdate && instrument ? (
        <WorkbookUploadDialog
          open={uploading}
          onClose={() => setUploading(false)}
          questionnaireId={instrument.id}
          questionnaireTitle={instrument.title}
          onUploaded={(result) => {
            setUploading(false);
            setReport(result.report);
            load();
            toast({
              tone: "success",
              title: "The workbook was applied",
              description: "See the report above."
            });
          }}
        />
      ) : null}
    </>
  );
}
