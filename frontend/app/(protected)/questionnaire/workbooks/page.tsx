"use client";

/**
 * /questionnaire/workbooks — the instrument as a spreadsheet. ADMIN and MASTER ADMIN only.
 *
 * ── WHY THIS PAGE EXISTS ────────────────────────────────────────────────────────────────────────
 *
 * The craft-toolkit questionnaire is twenty-two sections and eighty-one questions long and it
 * arrives from the ministry as a Word document, not as eighty-one clicks in the builder. This is the
 * door it comes in through: download a pro-forma, type or paste the instrument into it in Excel,
 * upload it back. A correction to question 54 next month is one cell in the same spreadsheet.
 *
 * ── THREE GATES, MIRRORED, AND NONE OF THEM IS THE REAL ONE ─────────────────────────────────────
 *
 * The real gate is `require_admin` on every route this page calls. The three below are the web
 * client agreeing with it:
 *
 *   1. `ROUTE_GUARDS` (lib/permissions.ts) — AppShell's lock panel for somebody who follows a link.
 *   2. `ADMIN_CHROME_ROUTES` (components/AdminViewProvider.tsx) — the "hidden while admin view is
 *      off" panel, for an admin browsing as an ordinary user.
 *   3. The `permitted` check below — the in-page belt, for a URL typed directly.
 *
 * All three keep their role check, so the admin-view toggle can only ever SUBTRACT. A forged
 * `adminMode` satisfies half of `adminChromeVisible` and never the role half.
 *
 * ── WHY THE REPORT IS A PANEL AND NOT A TOAST ───────────────────────────────────────────────────
 *
 * `components/ui/Toast.tsx` dismisses after five seconds. An upload report can name forty rows with
 * their Excel row numbers and the server's sentence for each, and reading that takes longer than
 * five seconds. It is mounted above the list and left there until the admin navigates away.
 */

import { useCallback, useEffect, useState } from "react";
import { FileSpreadsheet, FileUp, RefreshCw } from "lucide-react";

import { useAdminView, adminChromeVisible } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { ArtefactNotice } from "@/components/questionnaires/ArtefactNotice";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { WorkbookUploadDialog } from "@/components/questionnaires/WorkbookUploadDialog";
import {
  downloadProForma,
  downloadQuestionSet,
  downloadQuestionnaireWorkbook,
  saveWorkbook,
  type QWorkbookInstrument,
  type QWorkbookReport
} from "@/components/questionnaires/workbookApi";
import { RowActions, rowAction } from "@/components/RowActions";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { useToast } from "@/components/ui/Toast";
import { apiFetch } from "@/lib/api";
import { isAdmin } from "@/lib/permissions";

export default function QuestionnaireWorkbooksPage() {
  const { user, loading: authLoading } = useAuth();
  const { adminMode } = useAdminView();
  const permitted = isAdmin(user) && adminChromeVisible(user, adminMode);
  const { toast } = useToast();

  const [instruments, setInstruments] = useState<QWorkbookInstrument[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [dialog, setDialog] = useState<{ id?: string; title?: string } | null>(null);
  const [report, setReport] = useState<QWorkbookReport | null>(null);

  const load = useCallback(() => {
    apiFetch<QWorkbookInstrument[]>("/questionnaires?activeOnly=false")
      .then(setInstruments)
      .catch((err) =>
        toast({
          tone: "error",
          title: "Unable to load the questionnaires",
          description: err instanceof Error ? err.message : "The request failed."
        })
      );
  }, [toast]);

  useEffect(() => {
    if (authLoading || !permitted) return;
    load();
  }, [authLoading, permitted, load]);

  /**
   * Run one download and save it.
   *
   * EVERY FAILURE IS SHOWN WITH THE SERVER'S OWN SENTENCE. These routes answer 403 with a paragraph
   * about the admin/builder split and 404 with "Record not found"; `fetchWorkbook` already turns
   * both into a real message, and swallowing it into "download failed" would throw away the only
   * thing on screen that says what to do next.
   */
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

  const header = (
    <PageHeader
      title="Questionnaire workbooks"
      description="Download the instrument as a spreadsheet, edit it in Excel, and upload it back. Neither download carries an answer, and no upload stores one."
      icon={<FileSpreadsheet className="h-5 w-5" aria-hidden />}
      actions={
        permitted ? (
          <>
            <button
              type="button"
              className="field-button-secondary"
              disabled={busy === "pro-forma"}
              onClick={() => take("pro-forma", downloadProForma)}
            >
              <FileSpreadsheet className="h-4 w-4" aria-hidden />
              {busy === "pro-forma" ? "Building…" : "Download pro-forma"}
            </button>
            <button type="button" className="field-button" onClick={() => setDialog({})}>
              <FileUp className="h-4 w-4" aria-hidden />
              Upload a workbook
            </button>
          </>
        ) : undefined
      }
    />
  );

  if (authLoading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }

  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body="Uploading a questionnaire workbook re-states the whole instrument in one press, so it is available to admins and the master admin only. Adding or editing individual questions is on the Questionnaire page and is open to professors and questionnaire managers."
        />
      </>
    );
  }

  return (
    <>
      {header}

      {/* ABOVE THE LIST AND NOT DISMISSED. See the note on toasts in this file's docstring. */}
      {report ? <UploadReport report={report} className="mb-4" /> : null}

      <ArtefactNotice className="mb-4" />

      <section className="panel p-4">
        <div className="mb-3 flex items-center justify-between gap-2">
          <h2 className="font-display text-lg font-bold text-ink-900">Questionnaires</h2>
          <button type="button" className={rowAction("neutral")} onClick={load}>
            <RefreshCw className="h-4 w-4" aria-hidden />
            Refresh
          </button>
        </div>

        {instruments === null ? (
          <p className="text-sm text-ink-500">Loading…</p>
        ) : instruments.length === 0 ? (
          <EmptyState
            title="No questionnaires yet"
            body="Download the pro-forma, type the instrument into it, and upload it back."
          />
        ) : (
          <ul className="grid gap-2">
            {instruments.map((instrument) => (
              <li
                key={instrument.id}
                className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3 md:flex md:items-center md:justify-between"
              >
                <div className="min-w-0">
                  <p className="text-sm font-semibold text-ink-900">
                    {instrument.title}
                    {instrument.isDefault ? (
                      <span className="ml-2 rounded bg-field-200 px-1.5 py-0.5 text-[11px] font-medium text-field-600">
                        default
                      </span>
                    ) : null}
                    {instrument.isActive === false ? (
                      <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-medium text-amber-800">
                        retired
                      </span>
                    ) : null}
                  </p>
                  <p className="mt-0.5 text-xs leading-5 text-ink-500">
                    {instrument.sectionCount ?? 0} sections · {instrument.questionCount ?? 0} questions
                    {typeof instrument.version === "number" ? ` · version ${instrument.version}` : ""}
                    {instrument.sourceFilename ? ` · last uploaded from ${instrument.sourceFilename}` : ""}
                  </p>
                </div>
                <RowActions>
                  <button
                    type="button"
                    className={rowAction("neutral")}
                    disabled={busy === `xlsx-${instrument.id}`}
                    onClick={() =>
                      take(`xlsx-${instrument.id}`, () => downloadQuestionnaireWorkbook(instrument.id))
                    }
                  >
                    Download .xlsx
                  </button>
                  <button
                    type="button"
                    className={rowAction("neutral")}
                    disabled={busy === `set-${instrument.id}`}
                    onClick={() => take(`set-${instrument.id}`, () => downloadQuestionSet(instrument.id))}
                  >
                    Download question set
                  </button>
                  <button
                    type="button"
                    className={rowAction("edit")}
                    onClick={() => setDialog({ id: instrument.id, title: instrument.title })}
                  >
                    Re-upload
                  </button>
                </RowActions>
              </li>
            ))}
          </ul>
        )}
      </section>

      <WorkbookUploadDialog
        open={dialog !== null}
        onClose={() => setDialog(null)}
        questionnaireId={dialog?.id}
        questionnaireTitle={dialog?.title}
        onUploaded={(result) => {
          setDialog(null);
          setReport(result.report);
          load();
          // The panel above carries everything that happened; the toast only says WHERE to look,
          // because the panel is below the fold on a long list.
          toast({
            tone: "success",
            title: "The workbook was applied",
            description: "The report at the top of this page names every row the import had to decide about."
          });
        }}
      />
    </>
  );
}
