"use client";

/**
 * `/questionnaires` — the forms library (UNIFY 4.2): tabs Mine · Shared · Workshop, and Deleted for
 * content admins; pills New form · Upload workbook · Download pro-forma · Open bundle for form
 * authors (questionnaire managers). Forms are grouped by kind. The Workshop tab picks a workshop and
 * offers its annexure. Admins set each workshop type's default form here.
 *
 * Everything that writes needs a connection and says so on the button (UNIFY 4.3).
 */

import { FilterBar } from "@/components/questionnaire/FilterBar";
import { useListMemory } from "@/components/hooks/useListMemory";
import { useUrlFilters } from "@/components/questionnaire/useUrlFilters";
import { filtersToQuery } from "@/lib/questionnaire/filters";
import { qs } from "@/lib/questionnaire/qs";
import { ClipboardList, Download, FilePlus2, FileSpreadsheet, FileUp, Package, RotateCcw } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { NewFormDialog, OpenBundleDialog } from "@/components/questionnaires/FormDialogs";
import { TypeDefaultsCard } from "@/components/questionnaires/TypeDefaultsCard";
import { UploadReport } from "@/components/questionnaires/UploadReport";
import { NEEDS_CONNECTION, useOnline } from "@/components/questionnaires/useOnline";
import { WorkbookUploadDialog } from "@/components/questionnaires/WorkbookUploadDialog";
import { downloadProForma, saveWorkbook, type QWorkbookReport } from "@/components/questionnaires/workbookApi";
import { OpenableBlock, RecordTitleLink } from "@/components/records/OpenableRow";
import { RowActions, rowAction } from "@/components/RowActions";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { useToast } from "@/components/ui/Toast";
import { canDownloadDataset, canManageQuestionnaire, isContentAdmin } from "@/lib/permissions";
import {
  defaultTab,
  downloads,
  emptyLine,
  formSummary,
  groupByKind,
  isOffline,
  listCacheKey,
  readFormsCache,
  savedCopyLine,
  writeFormsCache,
  kindLabel,
  KIND_NOT_STATED,
  KINDS,
  library,
  libraryError,
  libraryTabs,
  saveDownload,
  type FormScope,
  type LibraryForm
} from "@/lib/questionnaireLibrary";
import { draftSummary, listDrafts, sendDraft } from "@/lib/questionnaireDraftForms";
import { loadWorkshopList } from "@/lib/workshopList";

export default function QuestionnairesLibraryPage() {
  const { user } = useAuth();
  const router = useRouter();
  const { toast } = useToast();
  const online = useOnline();
  const config = useQuestionnaireConfig();
  const author = canManageQuestionnaire(user);
  const tabs = libraryTabs(user);

  const [scope, setScope] = useState<FormScope | null>(null);
  const [kind, setKind] = useState("");
  const [workshopId, setWorkshopId] = useState("");
  const [workshops, setWorkshops] = useState<{ id: string; title: string }[]>([]);
  const [forms, setForms] = useState<LibraryForm[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [dialog, setDialog] = useState<"new" | "upload" | "bundle" | null>(null);
  const [report, setReport] = useState<QWorkbookReport | null>(null);
  const [reload, setReload] = useState(0);
  const [saved, setSaved] = useState<string | null>(null);
  const tab = scope ?? defaultTab(user);
  // The shared filters (owner, 31 Oct 2026): forms by their words, sections and questions, and by
  // the sessions held on them (artisans, workshops, dates …), kept in the URL.
  const { filters: sharedFilters, setFilters: setSharedFilters, ready: filtersReady } = useUrlFilters();

  /** The tab, kind and workshop in the URL, and the scroll, so Back from a form lands where the reader was. */
  const memory = useListMemory("questionnaires", { filters: { tab: scope ?? "", kind, workshop: workshopId } }, forms !== null);
  useEffect(() => {
    const remembered = memory.recall()?.filters ?? {};
    const wanted = tabs.find((entry) => entry.id === remembered.tab);
    if (wanted) setScope(wanted.id as FormScope);
    if (remembered.kind) setKind(remembered.kind);
    if (remembered.workshop) setWorkshopId(remembered.workshop);
    // Once, on arrival.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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

  // A late answer for an earlier tab or filter is dropped by the `cancelled` flag.
  useEffect(() => {
    if (!user || !filtersReady) return;
    let cancelled = false;
    const options = { workshopId: tab === "workshop" ? workshopId || null : null, kind: kind || null };
    const key = listCacheKey(tab, options) + (Object.keys(sharedFilters).length ? `|${filtersToQuery(sharedFilters)}` : "");
    library
      .list(tab, { ...options, filters: sharedFilters })
      .then((rows) => {
        if (cancelled) return;
        writeFormsCache(user.id, { list: { key, forms: rows } });
        setForms(rows);
        setSaved(null);
        setError(null);
      })
      .catch((err) => {
        if (cancelled) return;
        // Offline (A16): the copy this account last read, never another account's.
        const held = isOffline(err) ? readFormsCache(user.id) : null;
        if (held?.lists[key]) {
          setForms(held.lists[key]);
          setSaved(held.at);
          setError(null);
          return;
        }
        setForms([]);
        setError(libraryError(err, "Couldn't load the forms."));
      });
    return () => {
      cancelled = true;
    };
  }, [user, tab, workshopId, kind, reload, sharedFilters, filtersReady]);

  const load = () => setReload((n) => n + 1);

  // Forms written offline on this device (A15): listed under Mine, and sent once there is a
  // connection, oldest first; each lands as a form of its own and leaves the device.
  const [draftRevision, setDraftRevision] = useState(0);
  const drafts = user ? listDrafts(user.id) : [];
  void draftRevision;
  useEffect(() => {
    if (!online || !user) return;
    let cancelled = false;
    void (async () => {
      let sent = 0;
      for (const draft of listDrafts(user.id)) if (await sendDraft(draft)) sent += 1;
      if (cancelled) return;
      setDraftRevision((n) => n + 1);
      if (sent) setReload((n) => n + 1);
    })();
    return () => {
      cancelled = true;
    };
  }, [online, user]);

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

  const open = (form: Pick<LibraryForm, "id">) => router.push(`/questionnaires/${encodeURIComponent(form.id)}`);
  const offlineTitle = online ? undefined : NEEDS_CONNECTION;
  const annexureAllowed = (author || canDownloadDataset(user)) && config?.v2Enabled === true;

  return (
    <>
      <PageHeader
        title="Questionnaires"
        description="Your forms, shared forms and each workshop's forms."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          author ? (
            <>
              <button type="button" className="field-button" onClick={() => setDialog("new")}>
                <FilePlus2 className="h-4 w-4" aria-hidden /> New form
              </button>
              <button type="button" className="field-button-secondary" disabled={!online} title={offlineTitle} onClick={() => setDialog("upload")}>
                <FileUp className="h-4 w-4" aria-hidden /> Upload workbook
              </button>
              <button
                type="button"
                className="field-button-secondary"
                disabled={!online || busy === "pro-forma"}
                title={offlineTitle}
                onClick={() => run("pro-forma", async () => saveWorkbook(await downloadProForma()))}
              >
                <FileSpreadsheet className="h-4 w-4" aria-hidden /> Download pro-forma
              </button>
              <button type="button" className="field-button-secondary" disabled={!online} title={offlineTitle} onClick={() => setDialog("bundle")}>
                <Package className="h-4 w-4" aria-hidden /> Open bundle
              </button>
            </>
          ) : null
        }
      />

      {!online && author ? <p className="mb-4 text-sm text-ink-500">{NEEDS_CONNECTION} to create or change forms.</p> : null}

      {report ? <UploadReport report={report} className="mb-5" /> : null}

      <section className="panel mb-5 grid gap-4 p-4">
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div data-forms-tabs>
          <SegmentedChoice
            label="Forms"
            value={tab}
            options={tabs.map((t) => ({ value: t.id, label: t.label }))}
            onChange={(next) => {
              setForms(null);
              setScope(next as FormScope);
            }}
          />
          </div>
          <div className="grid gap-3 sm:grid-cols-2 md:w-[28rem]">
            {tab === "workshop" ? (
              <SearchableSelect
                value={workshopId}
                onChange={setWorkshopId}
                options={workshops.map((w) => ({ value: w.id, label: w.title }))}
                placeholder="Any workshop"
                emptyLabel="No workshops"
                ariaLabel="Workshop"
                searchable
                clearable
                advanceOnSelect={false}
              />
            ) : (
              <span className="hidden sm:block" aria-hidden />
            )}
            <SearchableSelect
              value={kind}
              onChange={setKind}
              options={[
                { value: "", label: "Every kind" },
                ...KINDS.map((k) => ({ value: k, label: kindLabel(k) })),
                { value: "none", label: KIND_NOT_STATED }
              ]}
              ariaLabel="Kind"
              advanceOnSelect={false}
            />
          </div>
        </div>

        <FilterBar
          filters={sharedFilters}
          onChange={(next) => {
            setForms(null);
            setSharedFilters(next);
          }}
          placeholder={qs("filterSearchForms")}
          hide={["questionnaireId", "kind"]}
        />

        {tab === "workshop" && workshopId && annexureAllowed ? (
          <ButtonRow align="start" size="sm">
            <button
              type="button"
              className="field-button-secondary"
              disabled={!online || busy === "annexure"}
              title={offlineTitle}
              onClick={() => run("annexure", async () => saveDownload(await downloads.annexure(workshopId)))}
            >
              <Download className="h-4 w-4" aria-hidden /> Download annexure
            </button>
          </ButtonRow>
        ) : null}

        {error ? <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p> : null}
        {saved ? (
          <p className="text-sm text-ink-500" data-saved-copy>
            {savedCopyLine(saved)}
          </p>
        ) : null}

        {tab === "mine" && drafts.length ? (
          <ul className="divide-y divide-line-200 rounded-md border border-dashed border-line-200" data-draft-forms>
            {drafts.map((draft) => (
              <li key={draft.tempId} className="p-3">
                <RecordTitleLink href={`/questionnaires/${encodeURIComponent(draft.tempId)}`}>{draft.title}</RecordTitleLink>
                <p className="mt-0.5 text-xs text-ink-500">
                  {draftSummary(draft)} · On this device{draft.error ? ` · ${draft.error}` : ""}
                </p>
              </li>
            ))}
          </ul>
        ) : null}

        {forms === null ? (
          <p className="text-sm text-ink-500">Loading…</p>
        ) : forms.length === 0 ? (
          <EmptyState title={emptyLine(tab)} />
        ) : (
          <div className="grid gap-5" data-testid="forms-library">
            {groupByKind(forms).map((group) => (
              <div key={group.kind ?? "none"} className="grid gap-2">
                <h3 className="field-label">{group.label}</h3>
                <ul className="divide-y divide-line-200 rounded-md border border-line-200">
                  {group.forms.map((form) => (
                    <li key={form.id}>
                      <OpenableBlock href={`/questionnaires/${encodeURIComponent(form.id)}`} className="flex flex-col gap-2 p-3 sm:flex-row sm:items-center sm:justify-between">
                        <div className="min-w-0">
                          <RecordTitleLink href={`/questionnaires/${encodeURIComponent(form.id)}`}>{form.title}</RecordTitleLink>
                          <p className="mt-0.5 text-xs text-ink-500">
                            {formSummary(form)}
                            {form.isShared ? " · Shared" : ""}
                            {form.isDefault ? " · Default" : ""}
                            {form.workshopCount ? ` · ${form.workshopCount} ${form.workshopCount === 1 ? "workshop" : "workshops"}` : ""}
                            {form.deletedAt ? ` · Deleted ${new Date(form.deletedAt).toLocaleDateString("en-IN")}` : ""}
                          </p>
                        </div>
                        {tab === "deleted" && form.can?.restore ? (
                          <RowActions>
                            <button
                              type="button"
                              className={rowAction("neutral")}
                              disabled={!online || busy === form.id}
                              title={offlineTitle}
                              onClick={() =>
                                run(form.id, async () => {
                                  await library.restore(form.id);
                                  toast({ tone: "success", title: "Restored", description: form.title });
                                  load();
                                })
                              }
                            >
                              <RotateCcw className="h-4 w-4" aria-hidden /> Restore
                            </button>
                          </RowActions>
                        ) : null}
                      </OpenableBlock>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </section>

      {isContentAdmin(user) && config?.v2Enabled ? <TypeDefaultsCard online={online} /> : null}

      <NewFormDialog open={dialog === "new"} onClose={() => setDialog(null)} onCreated={(form) => open(form)} online={online} owner={user?.id ?? null} />
      <OpenBundleDialog
        open={dialog === "bundle"}
        onClose={() => setDialog(null)}
        onImported={(form, created) => {
          setDialog(null);
          if (!created) toast({ tone: "info", title: "Already added", description: form.title });
          open(form);
        }}
      />
      <WorkbookUploadDialog
        open={dialog === "upload"}
        onClose={() => setDialog(null)}
        workshops={workshops}
        onUploaded={(result) => {
          setDialog(null);
          setReport(result.report);
          setScope("mine");
          load();
        }}
      />
    </>
  );
}
